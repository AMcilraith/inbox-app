//! Minimal MCP client (streamable HTTP transport) for calling Superhuman's tools.

use crate::auth::Auth;
use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Value};
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc,
};
use tokio::sync::Mutex;

const PROTOCOL: &str = "2025-06-18";

pub struct Mcp {
    http: reqwest::Client,
    url: String,
    auth: Arc<Auth>,
    session: Mutex<Option<Option<String>>>, // None = not initialised; Some(id?) = ready
    next_id: AtomicU64,
}

enum CallError {
    Unauthorized,
    SessionGone,
    Other(anyhow::Error),
}

impl From<anyhow::Error> for CallError {
    fn from(e: anyhow::Error) -> Self {
        CallError::Other(e)
    }
}
impl From<reqwest::Error> for CallError {
    fn from(e: reqwest::Error) -> Self {
        CallError::Other(e.into())
    }
}

impl Mcp {
    pub fn new(http: reqwest::Client, url: &str, auth: Arc<Auth>) -> Self {
        Self { http, url: url.into(), auth, session: Mutex::new(None), next_id: AtomicU64::new(1) }
    }

    async fn post(&self, token: &str, session: Option<&str>, body: &Value) -> Result<(Option<Value>, Option<String>), CallError> {
        let mut req = self
            .http
            .post(&self.url)
            .bearer_auth(token)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL)
            .json(body);
        if let Some(s) = session {
            req = req.header("Mcp-Session-Id", s);
        }
        let resp = req.send().await?;
        let status = resp.status();
        if status == reqwest::StatusCode::UNAUTHORIZED {
            return Err(CallError::Unauthorized);
        }
        if status == reqwest::StatusCode::NOT_FOUND && session.is_some() {
            return Err(CallError::SessionGone);
        }
        let sid = resp.headers().get("mcp-session-id").and_then(|v| v.to_str().ok()).map(str::to_string);
        let ctype = resp.headers().get("content-type").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CallError::Other(anyhow!("Superhuman MCP returned {status}: {}", text.chars().take(300).collect::<String>())));
        }
        if text.trim().is_empty() {
            return Ok((None, sid));
        }
        let want = body.get("id").cloned();
        let msg = if ctype.contains("text/event-stream") {
            text.lines()
                .filter_map(|l| l.strip_prefix("data:"))
                .filter_map(|d| serde_json::from_str::<Value>(d.trim()).ok())
                .find(|v| want.is_none() || v.get("id") == want.as_ref())
        } else {
            Some(serde_json::from_str(&text).context("invalid JSON from MCP server")?)
        };
        Ok((msg, sid))
    }

    async fn ensure_session(&self, token: &str) -> Result<Option<String>, CallError> {
        let mut guard = self.session.lock().await;
        if let Some(s) = guard.clone() {
            return Ok(s);
        }
        let init = json!({
            "jsonrpc": "2.0",
            "id": self.next_id.fetch_add(1, Ordering::Relaxed),
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL,
                "capabilities": {},
                "clientInfo": {"name": "inbox-hub", "version": env!("CARGO_PKG_VERSION")}
            }
        });
        let (resp, sid) = self.post(token, None, &init).await?;
        if let Some(err) = resp.as_ref().and_then(|r| r.get("error")) {
            return Err(CallError::Other(anyhow!("MCP initialize failed: {err}")));
        }
        let note = json!({"jsonrpc": "2.0", "method": "notifications/initialized"});
        let _ = self.post(token, sid.as_deref(), &note).await;
        *guard = Some(sid.clone());
        Ok(sid)
    }

    async fn try_call(&self, token: &str, name: &str, args: &Value) -> Result<Value, CallError> {
        let sid = self.ensure_session(token).await?;
        let req = json!({
            "jsonrpc": "2.0",
            "id": self.next_id.fetch_add(1, Ordering::Relaxed),
            "method": "tools/call",
            "params": {"name": name, "arguments": args}
        });
        let (resp, _) = self.post(token, sid.as_deref(), &req).await?;
        let resp = resp.ok_or_else(|| anyhow!("empty response from {name}"))?;
        if let Some(err) = resp.get("error") {
            return Err(CallError::Other(anyhow!("{name}: {}", err["message"].as_str().unwrap_or("error"))));
        }
        let result = &resp["result"];
        let text: String = result["content"]
            .as_array()
            .map(|c| c.iter().filter_map(|b| b["text"].as_str()).collect::<Vec<_>>().join("\n"))
            .unwrap_or_default();
        if result["isError"].as_bool().unwrap_or(false) {
            return Err(CallError::Other(anyhow!("{name}: {}", text.chars().take(400).collect::<String>())));
        }
        if let Some(sc) = result.get("structuredContent").filter(|v| !v.is_null()) {
            return Ok(sc.clone());
        }
        Ok(serde_json::from_str(&text).unwrap_or(Value::String(text)))
    }

    /// Call a Superhuman tool and return its parsed JSON result.
    pub async fn call(&self, name: &str, args: Value) -> Result<Value> {
        let mut token = self.auth.access_token().await?;
        for attempt in 0..3 {
            match self.try_call(&token, name, &args).await {
                Ok(v) => return Ok(v),
                Err(CallError::Unauthorized) if attempt == 0 => {
                    *self.session.lock().await = None;
                    token = self.auth.force_refresh().await?;
                }
                Err(CallError::SessionGone) => {
                    *self.session.lock().await = None;
                }
                Err(CallError::Unauthorized) => bail!("Superhuman rejected the sign-in — sign in again"),
                Err(CallError::Other(e)) => return Err(e),
            }
        }
        bail!("Superhuman MCP call {name} failed after retries")
    }

    /// Call as a specific linked account.
    pub async fn call_as(&self, account: &str, name: &str, mut args: Value) -> Result<Value> {
        // Only a real address selects an account; anything else falls back to the primary one.
        if account.contains('@') {
            if let Some(o) = args.as_object_mut() {
                o.insert("acting_email".into(), Value::String(account.into()));
            }
        }
        self.call(name, args).await
    }
}
