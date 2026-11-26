//! Claude via the Messages API (raw HTTP — there is no official Rust SDK).
//! "Ask Claude" connects Claude straight to Superhuman with the MCP connector,
//! limited to read-only tools; anything that sends mail goes through the app's own buttons.

use anyhow::{anyhow, bail, Result};
use serde::Serialize;
use serde_json::{json, Value};
use std::time::Duration;

const API: &str = "https://api.anthropic.com/v1/messages";
const VERSION: &str = "2026-10-21";
const BETAS: &str = "mcp-client-2025-11-20,server-side-fallback-2026-07-01";

/// Superhuman tools Claude may use on its own. Nothing here sends, deletes or changes mail.
pub const READ_ONLY_TOOLS: &[&str] = &[
    "list_accounts",
    "list_threads",
    "get_thread",
    "get_message",
    "query_email_and_calendar",
    "list_calendar_events",
    "get_calendar_event",
    "get_availability",
    "list_splits",
    "list_labels",
    "list_reminders",
    "list_drafts",
    "get_draft",
    "list_snippets",
];

pub struct Claude {
    http: reqwest::Client,
    key: String,
    pub model: String,
    effort: String,
}

#[derive(Debug, Serialize)]
pub struct Reply {
    pub text: String,
    pub tools_used: Vec<String>,
}

impl Claude {
    pub fn new(key: String, model: String, effort: String) -> Result<Self> {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(600)).build()?;
        Ok(Self { http, key, model, effort })
    }

    async fn send(&self, body: &Value) -> Result<Value> {
        let mut delay = Duration::from_secs(2);
        for attempt in 0..3 {
            let resp = self
                .http
                .post(API)
                .header("x-api-key", &self.key)
                .header("anthropic-version", VERSION)
                .header("anthropic-beta", BETAS)
                .json(body)
                .send()
                .await;
            let resp = match resp {
                Ok(r) => r,
                Err(e) if attempt < 2 && (e.is_connect() || e.is_timeout()) => {
                    tokio::time::sleep(delay).await;
                    delay *= 2;
                    continue;
                }
                Err(e) => return Err(e.into()),
            };
            let status = resp.status();
            let retry = resp.headers().get("x-should-retry").and_then(|v| v.to_str().ok()) != Some("false");
            let v: Value = resp.json().await.unwrap_or(Value::Null);
            if status.is_success() {
                return Ok(v);
            }
            let code = status.as_u16();
            if attempt < 2 && retry && (code == 408 || code == 409 || code == 429 || code >= 500) {
                tokio::time::sleep(delay).await;
                delay *= 2;
                continue;
            }
            let msg = v["error"]["message"].as_str().unwrap_or("request failed");
            match code {
                401 => bail!("Claude API key was rejected ({msg})"),
                429 => bail!("Claude is rate-limited right now — try again shortly"),
                _ => bail!("Claude API error {code}: {msg}"),
            }
        }
        bail!("Claude API unavailable")
    }

    fn base(&self, system: &str, max_tokens: u32) -> Value {
        json!({
            "model": self.model,
            "max_tokens": max_tokens,
            "thinking": {"type": "adaptive"},
            "output_config": {"effort": self.effort},
            "fallbacks": "default",
            "system": system,
        })
    }

    /// Run one conversational turn. `history` is updated in place (assistant content is kept
    /// exactly as returned, so later turns stay valid).
    pub async fn chat(&self, system: &str, history: &mut Vec<Value>, mcp: Option<(&str, String)>) -> Result<Reply> {
        let mut tools_used = Vec::new();
        for _ in 0..6 {
            let mut body = self.base(system, 16000);
            body["messages"] = Value::Array(history.clone());
            if let Some((url, token)) = &mcp {
                let configs: serde_json::Map<String, Value> =
                    READ_ONLY_TOOLS.iter().map(|t| (t.to_string(), json!({"enabled": true}))).collect();
                body["mcp_servers"] = json!([{"type": "url", "url": url, "name": "superhuman", "authorization_token": token}]);
                body["tools"] = json!([{
                    "type": "mcp_toolset",
                    "mcp_server_name": "superhuman",
                    "default_config": {"enabled": false},
                    "configs": configs
                }]);
            }
            let resp = self.send(&body).await?;
            let content = resp["content"].clone();
            for b in content.as_array().into_iter().flatten() {
                if b["type"] == "mcp_tool_use" {
                    if let Some(n) = b["name"].as_str() {
                        tools_used.push(n.to_string());
                    }
                }
            }
            history.push(json!({"role": "assistant", "content": content}));
            match resp["stop_reason"].as_str().unwrap_or("") {
                "pause_turn" => continue,
                "refusal" => {
                    return Ok(Reply { text: "Claude declined to answer that one. Try rephrasing the request.".into(), tools_used })
                }
                stop => {
                    let mut text = text_of(&resp);
                    if stop == "max_tokens" {
                        text.push_str("\n\n_(answer was cut short)_");
                    }
                    return Ok(Reply { text, tools_used });
                }
            }
        }
        Ok(Reply { text: "That took too many steps — try a narrower question.".into(), tools_used })
    }

    /// Single-shot completion without tools.
    pub async fn complete(&self, system: &str, prompt: &str, max_tokens: u32) -> Result<String> {
        let mut body = self.base(system, max_tokens);
        body["messages"] = json!([{"role": "user", "content": prompt}]);
        let resp = self.send(&body).await?;
        if resp["stop_reason"] == "refusal" {
            return Err(anyhow!("Claude declined this request"));
        }
        Ok(text_of(&resp))
    }
}

fn text_of(resp: &Value) -> String {
    resp["content"]
        .as_array()
        .map(|c| c.iter().filter(|b| b["type"] == "text").filter_map(|b| b["text"].as_str()).collect::<Vec<_>>().join(""))
        .unwrap_or_default()
        .trim()
        .to_string()
}
