// Release builds are a normal Windows desktop app (no console window).
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod app;
mod auth;
mod claude;
mod config;
mod demo;
mod hub;
mod mcp;
mod model;
mod names;
mod oauth;
mod text;
mod time;

use anyhow::Result;
use chrono::{Duration, Utc};
use std::{path::PathBuf, sync::Arc};

const HELP: &str = "Inbox Hub — one desktop app for all your Superhuman accounts, with Claude built in.

USAGE:
  inbox-hub [--demo] [--config PATH]          open the app (default)
  inbox-hub digest [--send] [--config PATH]   print today's digest, or email it

Sign in once in the app; `digest --send` then works from Task Scheduler.
Set ANTHROPIC_API_KEY to turn on the Claude features.";

fn main() {
    if let Err(e) = real_main() {
        let msg = format!("{e:#}");
        eprintln!("Inbox Hub: {msg}");
        let dir = config::data_dir();
        let _ = std::fs::create_dir_all(&dir);
        let _ = std::fs::write(dir.join("last-error.txt"), &msg);
        std::process::exit(1);
    }
}

fn real_main() -> Result<()> {
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    let flag = |args: &mut Vec<String>, f: &str| {
        let had = args.iter().any(|a| a == f);
        args.retain(|a| a != f);
        had
    };
    let help = flag(&mut args, "--help") || flag(&mut args, "-h");
    let demo = flag(&mut args, "--demo");
    let _ = flag(&mut args, "--no-open"); // accepted for old shortcuts
    let send = flag(&mut args, "--send");
    let cfg_path = args.iter().position(|a| a == "--config").map(|i| {
        let p = args.get(i + 1).cloned().unwrap_or_default();
        args.drain(i..(i + 2).min(args.len()));
        PathBuf::from(p)
    });
    let command = args.first().cloned().unwrap_or_else(|| "app".into());
    let cli = help || command == "digest";
    #[cfg(windows)]
    if cli {
        attach_console();
    }
    if help {
        println!("{HELP}");
        return Ok(());
    }

    let (cfg, cfg_error) = if demo {
        (config::Config::demo(), None)
    } else {
        let found = config::find_config(cfg_path).or_else(|| if cli { None } else { config::create_starter_config() });
        if let Some(p) = &found {
            config::set_active_config(p);
        }
        match found {
            Some(p) => match config::Config::load(&p) {
                Ok(c) => (c, None),
                Err(e) if cli => return Err(e),
                Err(e) => (config::Config::default(), Some(format!("Problem in {}: {e:#} — using defaults", p.display()))),
            },
            None => (config::Config::default(), None),
        }
    };

    let rt = tokio::runtime::Runtime::new()?;
    let http = reqwest::Client::builder().timeout(std::time::Duration::from_secs(60)).user_agent("inbox-hub").build()?;
    let data = config::data_dir();
    let auth = (!demo).then(|| Arc::new(auth::Auth::new(http.clone(), &cfg.mcp_url, data.clone())));
    let mcp = auth.as_ref().map(|a| Arc::new(mcp::Mcp::new(http.clone(), &cfg.mcp_url, a.clone())));
    let claude = match cfg.claude_key() {
        Some(k) => Some(claude::Claude::new(k, cfg.claude.model.clone(), cfg.claude.effort.clone())?),
        None => None,
    };
    let port = cfg.port;
    let hub = Arc::new(hub::Hub::new(cfg, mcp, claude));

    match command.as_str() {
        "digest" => rt.block_on(async {
            if send {
                println!("{}", hub.send_digest().await?);
            } else {
                let (subject, html) = hub.build_digest().await?;
                println!("Subject: {subject}\n\n{}", text::html_to_text(&html));
            }
            Ok(())
        }),
        "app" | "serve" => {
            if !demo {
                if let Some(t) = hub.cfg.digest.send_time.clone() {
                    rt.spawn(digest_scheduler(hub.clone(), t, data.clone()));
                }
            }
            app::run(hub, auth, port, cfg_error, data, rt.handle().clone())
        }
        other => {
            eprintln!("unknown command '{other}'\n\n{HELP}");
            std::process::exit(2)
        }
    }
}

/// Let `inbox-hub digest` print to the terminal it was started from, even though release
/// builds are GUI programs.
#[cfg(windows)]
fn attach_console() {
    use windows_sys::Win32::System::Console::{AttachConsole, ATTACH_PARENT_PROCESS};
    unsafe {
        AttachConsole(ATTACH_PARENT_PROCESS);
    }
}

/// Sends the digest once a day at `send_time` (local) while the app is open.
async fn digest_scheduler(hub: Arc<hub::Hub>, send_time: String, data: PathBuf) {
    let Ok((h, m)) = config::parse_hhmm(&send_time) else { return };
    let Ok(tz) = hub.cfg.tz() else { return };
    let marker = data.join("last-digest-date");
    loop {
        let now = Utc::now();
        let today = tz.local(now).date();
        let mut target = tz.to_utc(today.and_hms_opt(h, m, 0).unwrap());
        let sent_today = std::fs::read_to_string(&marker).map(|s| s.trim() == today.to_string()).unwrap_or(false);
        if target <= now && sent_today {
            target = target + Duration::days(1);
        }
        let wait = (target - Utc::now()).to_std().unwrap_or_default();
        tokio::time::sleep(wait).await;
        let day = tz.now().date().to_string();
        if std::fs::read_to_string(&marker).map(|s| s.trim() == day).unwrap_or(false) {
            tokio::time::sleep(std::time::Duration::from_secs(60)).await;
            continue;
        }
        match hub.send_digest().await {
            Ok(msg) => {
                println!("[digest] {msg}");
                let _ = std::fs::create_dir_all(&data);
                let _ = std::fs::write(&marker, &day);
            }
            Err(e) => {
                eprintln!("[digest] failed: {e:#} — retrying in 15 minutes");
                tokio::time::sleep(std::time::Duration::from_secs(900)).await;
            }
        }
    }
}
