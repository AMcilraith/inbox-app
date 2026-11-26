//! Sign-in to Superhuman's MCP server: OAuth 2.1 with discovery, dynamic client
//! registration and PKCE, as the MCP authorization spec describes. No developer keys needed.

use anyhow::{anyhow, bail, Context, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::{distributions::Alphanumeric, Rng};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{collections::HashMap, path::PathBuf};
use tokio::sync::Mutex;

const SCOPES: &str = "openid email profile offline_access";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Metadata {
    pub issuer: String,
    pub authorization_endpoint: String,
    pub token_endpoint: String,
    pub registration_endpoint: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Client {
    pub client_id: String,
    pub client_secret: Option<String>,
    pub redirect_uri: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Token {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub expires_at: i64,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Stored {
    metadata: Option<Metadata>,
    client: Option<Client>,
    token: Option<Token>,
}

pub struct Auth {
    http: reqwest::Client,
    resource: String,
    path: PathBuf,
    state: Mutex<Stored>,
    /// OAuth `state` -> PKCE verifier, for sign-ins in progress.
    pending: Mutex<HashMap<String, String>>,
}

fn random(n: usize) -> String {
    rand::thread_rng().sample_iter(&Alphanumeric).take(n).map(char::from).collect()
}

impl Auth {
    pub fn new(http: reqwest::Client, resource: &str, data_dir: PathBuf) -> Self {
        let path = data_dir.join("superhuman-auth.json");
        let stored = std::fs::read_to_string(&path)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        Self { http, resource: resource.into(), path, state: Mutex::new(stored), pending: Mutex::new(HashMap::new()) }
    }

    pub async fn signed_in(&self) -> bool {
        self.state.lock().await.token.is_some()
    }

    fn save(&self, s: &Stored) -> Result<()> {
        if let Some(dir) = self.path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let json = serde_json::to_string_pretty(s)?;
        std::fs::write(&self.path, json)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&self.path, std::fs::Permissions::from_mode(0o600))?;
        }
        Ok(())
    }

    async fn discover(&self) -> Result<Metadata> {
        if let Some(m) = self.state.lock().await.metadata.clone() {
            return Ok(m);
        }
        let url = reqwest::Url::parse(&self.resource)?;
        let origin = url.origin().ascii_serialization();
        let path = url.path().trim_end_matches('/');
        let mut prm: Option<Value> = None;
        for candidate in [format!("{origin}/.well-known/oauth-protected-resource{path}"), format!("{origin}/.well-known/oauth-protected-resource")] {
            if let Ok(r) = self.http.get(&candidate).send().await {
                if r.status().is_success() {
                    prm = r.json().await.ok();
                    break;
                }
            }
        }
        let issuer = prm
            .as_ref()
            .and_then(|p| p["authorization_servers"][0].as_str())
            .map(str::to_string)
            .unwrap_or(origin);
        let issuer = issuer.trim_end_matches('/').to_string();
        let meta: Metadata = self
            .http
            .get(format!("{issuer}/.well-known/oauth-authorization-server"))
            .send()
            .await?
            .error_for_status()
            .context("fetching authorization server metadata")?
            .json()
            .await?;
        let mut st = self.state.lock().await;
        st.metadata = Some(meta.clone());
        self.save(&st)?;
        Ok(meta)
    }

    async fn client(&self, meta: &Metadata, redirect_uri: &str) -> Result<Client> {
        if let Some(c) = self.state.lock().await.client.clone() {
            if c.redirect_uri == redirect_uri {
                return Ok(c);
            }
        }
        let reg = meta.registration_endpoint.as_deref().ok_or_else(|| anyhow!("server does not support client registration"))?;
        let resp: Value = self
            .http
            .post(reg)
            .json(&serde_json::json!({
                "client_name": "Inbox Hub",
                "redirect_uris": [redirect_uri],
                "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "none",
                "scope": SCOPES,
            }))
            .send()
            .await?
            .error_for_status()
            .context("registering Inbox Hub with Superhuman")?
            .json()
            .await?;
        let c = Client {
            client_id: resp["client_id"].as_str().ok_or_else(|| anyhow!("no client_id in registration"))?.into(),
            client_secret: resp["client_secret"].as_str().map(str::to_string),
            redirect_uri: redirect_uri.into(),
        };
        let mut st = self.state.lock().await;
        st.client = Some(c.clone());
        st.token = None;
        self.save(&st)?;
        Ok(c)
    }

    /// Build the URL that starts sign-in in the browser.
    pub async fn authorize_url(&self, redirect_uri: &str) -> Result<String> {
        let meta = self.discover().await?;
        let client = self.client(&meta, redirect_uri).await?;
        let verifier = random(64);
        let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
        let state = random(32);
        self.pending.lock().await.insert(state.clone(), verifier);
        let mut url = reqwest::Url::parse(&meta.authorization_endpoint)?;
        url.query_pairs_mut()
            .append_pair("response_type", "code")
            .append_pair("client_id", &client.client_id)
            .append_pair("redirect_uri", redirect_uri)
            .append_pair("scope", SCOPES)
            .append_pair("state", &state)
            .append_pair("code_challenge", &challenge)
            .append_pair("code_challenge_method", "S256")
            .append_pair("resource", &self.resource);
        Ok(url.into())
    }

    pub async fn complete(&self, code: &str, state: &str, iss: Option<&str>) -> Result<()> {
        let verifier = self.pending.lock().await.remove(state).ok_or_else(|| anyhow!("sign-in expired or unknown — try again"))?;
        let meta = self.discover().await?;
        if let Some(iss) = iss {
            if iss.trim_end_matches('/') != meta.issuer.trim_end_matches('/') {
                bail!("unexpected issuer in sign-in response");
            }
        }
        let client = self.state.lock().await.client.clone().ok_or_else(|| anyhow!("not registered"))?;
        let mut form = vec![
            ("grant_type", "authorization_code".to_string()),
            ("code", code.to_string()),
            ("redirect_uri", client.redirect_uri.clone()),
            ("client_id", client.client_id.clone()),
            ("code_verifier", verifier),
            ("resource", self.resource.clone()),
        ];
        if let Some(sec) = &client.client_secret {
            form.push(("client_secret", sec.clone()));
        }
        let tok = self.token_request(&meta, &form).await?;
        let mut st = self.state.lock().await;
        st.token = Some(tok);
        self.save(&st)
    }

    async fn token_request(&self, meta: &Metadata, form: &[(&str, String)]) -> Result<Token> {
        let r = self.http.post(&meta.token_endpoint).form(form).send().await?;
        let status = r.status();
        let v: Value = r.json().await.unwrap_or(Value::Null);
        if !status.is_success() {
            bail!("token request failed ({status}): {}", v["error_description"].as_str().or(v["error"].as_str()).unwrap_or("unknown error"));
        }
        let previous_refresh = form.iter().find(|(k, _)| *k == "refresh_token").map(|(_, v)| v.clone());
        Ok(Token {
            access_token: v["access_token"].as_str().ok_or_else(|| anyhow!("no access_token"))?.into(),
            refresh_token: v["refresh_token"].as_str().map(str::to_string).or(previous_refresh),
            expires_at: chrono::Utc::now().timestamp() + v["expires_in"].as_i64().unwrap_or(3600),
        })
    }

    /// A valid access token, refreshed if it is about to expire.
    pub async fn access_token(&self) -> Result<String> {
        self.token(false).await
    }

    pub async fn force_refresh(&self) -> Result<String> {
        self.token(true).await
    }

    async fn token(&self, force: bool) -> Result<String> {
        let (tok, client) = {
            let st = self.state.lock().await;
            (st.token.clone().ok_or_else(|| anyhow!("not signed in to Superhuman"))?, st.client.clone())
        };
        if !force && tok.expires_at - 60 > chrono::Utc::now().timestamp() {
            return Ok(tok.access_token);
        }
        let refresh = tok.refresh_token.clone().ok_or_else(|| anyhow!("Superhuman session expired — sign in again"))?;
        let client = client.ok_or_else(|| anyhow!("not registered"))?;
        let meta = self.discover().await?;
        let mut form = vec![
            ("grant_type", "refresh_token".to_string()),
            ("refresh_token", refresh),
            ("client_id", client.client_id.clone()),
            ("resource", self.resource.clone()),
        ];
        if let Some(sec) = &client.client_secret {
            form.push(("client_secret", sec.clone()));
        }
        let new = match self.token_request(&meta, &form).await {
            Ok(t) => t,
            Err(e) => {
                let mut st = self.state.lock().await;
                st.token = None;
                let _ = self.save(&st);
                return Err(e.context("Superhuman session expired — sign in again"));
            }
        };
        let mut st = self.state.lock().await;
        st.token = Some(new.clone());
        self.save(&st)?;
        Ok(new.access_token)
    }

    pub async fn sign_out(&self) -> Result<()> {
        let mut st = self.state.lock().await;
        st.token = None;
        self.save(&st)
    }
}
