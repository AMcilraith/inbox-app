//! Superhuman sign-in for the desktop app: opens Superhuman's sign-in page in the browser and
//! listens on 127.0.0.1 for the single redirect back, then closes. It serves nothing else.

use crate::auth::Auth;
use anyhow::{anyhow, bail, Context, Result};
use std::time::Duration;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
    time::{timeout, Instant},
};

pub fn redirect_uri(port: u16) -> String {
    format!("http://localhost:{port}/oauth/callback")
}

pub async fn sign_in(auth: &Auth, port: u16) -> Result<()> {
    let listener = TcpListener::bind(("127.0.0.1", port))
        .await
        .with_context(|| format!("port {port} is busy — close the other copy of Inbox Hub, or change `port` in inbox-hub.toml"))?;
    let url = auth.authorize_url(&redirect_uri(port)).await?;
    open::that(&url).context("opening your browser")?;

    let deadline = Instant::now() + Duration::from_secs(600);
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        let (mut sock, _) = timeout(left, listener.accept()).await.map_err(|_| anyhow!("sign-in timed out — try again"))??;
        let mut buf = vec![0u8; 16 * 1024];
        let mut n = 0;
        while n < buf.len() {
            let r = timeout(Duration::from_secs(10), sock.read(&mut buf[n..])).await.unwrap_or(Ok(0))?;
            if r == 0 {
                break;
            }
            n += r;
            if buf[..n].windows(4).any(|w| w == b"\r\n\r\n") {
                break;
            }
        }
        let req = String::from_utf8_lossy(&buf[..n]);
        let target = req.lines().next().and_then(|l| l.split_whitespace().nth(1)).unwrap_or("");
        if !target.starts_with("/oauth/callback") {
            let _ = respond(&mut sock, 404, "Not found", "This address is only used to finish signing in to Inbox Hub.").await;
            continue;
        }
        let parsed = reqwest::Url::parse(&format!("http://localhost{target}"))?;
        let q: std::collections::HashMap<String, String> = parsed.query_pairs().into_owned().collect();
        if let Some(e) = q.get("error") {
            let msg = q.get("error_description").unwrap_or(e).clone();
            let _ = respond(&mut sock, 200, "Sign-in cancelled", &msg).await;
            bail!("sign-in cancelled: {msg}");
        }
        let (Some(code), Some(state)) = (q.get("code"), q.get("state")) else {
            let _ = respond(&mut sock, 400, "Sign-in failed", "The response was missing its code.").await;
            bail!("sign-in failed: no code in the response");
        };
        return match auth.complete(code, state, q.get("iss").map(String::as_str)).await {
            Ok(()) => {
                let _ = respond(&mut sock, 200, "Signed in", "You can close this tab and go back to Inbox Hub.").await;
                Ok(())
            }
            Err(e) => {
                let _ = respond(&mut sock, 400, "Sign-in failed", &format!("{e:#}")).await;
                Err(e)
            }
        };
    }
}

async fn respond(sock: &mut tokio::net::TcpStream, code: u16, title: &str, msg: &str) -> std::io::Result<()> {
    let esc = crate::model::html_escape;
    let body = format!(
        "<!doctype html><meta charset=utf-8><title>{t}</title><body style=\"font-family:Segoe UI,system-ui,sans-serif;display:grid;place-items:center;height:100vh;margin:0;background:#0c0d10;color:#eceef2\"><div style=\"text-align:center\"><h2>{t}</h2><p>{m}</p></div>",
        t = esc(title),
        m = esc(msg)
    );
    let resp = format!(
        "HTTP/1.1 {code} OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    sock.write_all(resp.as_bytes()).await?;
    sock.shutdown().await
}
