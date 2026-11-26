//! Pulls every linked Superhuman account together: unified inbox, to-dos, calendar, digest.

use crate::{
    config::{AccountPrefs, Config},
    demo,
    mcp::Mcp,
    model::{self, Event, Thread, ThreadFull},
};
use anyhow::{anyhow, Result};
use chrono::{DateTime, Datelike, Duration, Utc};
use futures::future::join_all;
use serde::Serialize;
use serde_json::{json, Value};
use std::{collections::HashMap, sync::Arc, time::Instant};
use tokio::sync::Mutex;

const PALETTE: &[&str] = &["#6366f1", "#0ea5e9", "#f59e0b", "#10b981", "#ec4899", "#8b5cf6"];

#[derive(Debug, Clone, Serialize)]
pub struct Account {
    pub email: String,
    pub label: String,
    pub color: String,
    pub primary: bool,
    pub style: Option<String>,
    pub unread: u64,
    pub important_unread: u64,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Todo {
    #[serde(flatten)]
    pub thread: Thread,
    pub priority: &'static str,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct Overview {
    pub generated_at: DateTime<Utc>,
    pub timezone: String,
    pub accounts: Vec<Account>,
    pub todo: Vec<Todo>,
    pub waiting: Vec<Thread>,
    pub events: Vec<Event>,
    pub categories: Vec<(String, usize)>,
}

pub struct Hub {
    pub cfg: Config,
    pub mcp: Option<Arc<Mcp>>,
    cache: Mutex<HashMap<String, (Instant, Value)>>,
    demo_cal: Mutex<DemoCal>,
}

/// Calendar edits made in demo mode (kept in memory).
#[derive(Default)]
struct DemoCal {
    added: Vec<(String, Value)>,
    deleted: std::collections::HashSet<String>,
    rsvp: HashMap<String, String>,
}

#[derive(Debug, Clone, serde::Deserialize)]
pub struct EventInput {
    pub account: String,
    #[serde(default)]
    pub event_id: Option<String>,
    pub title: String,
    /// Local "YYYY-MM-DDTHH:MM" (or "YYYY-MM-DD" for all-day).
    pub start: String,
    pub end: String,
    #[serde(default)]
    pub all_day: bool,
    #[serde(default)]
    pub location: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub attendees: Vec<String>,
    #[serde(default)]
    pub conference: bool,
}

impl Hub {
    pub fn new(cfg: Config, mcp: Option<Arc<Mcp>>) -> Self {
        Self { cfg, mcp, cache: Mutex::new(HashMap::new()), demo_cal: Mutex::new(DemoCal::default()) }
    }

    pub fn demo(&self) -> bool {
        self.mcp.is_none()
    }

    fn mcp(&self) -> Result<&Mcp> {
        self.mcp.as_deref().ok_or_else(|| anyhow!("demo mode"))
    }

    pub async fn clear_cache(&self) {
        self.cache.lock().await.clear();
    }

    /// Cached MCP call (90 s) for read-only tools.
    async fn cached(&self, account: &str, tool: &str, args: Value) -> Result<Value> {
        let key = format!("{account}|{tool}|{args}");
        if let Some((t, v)) = self.cache.lock().await.get(&key) {
            if t.elapsed().as_secs() < 90 {
                return Ok(v.clone());
            }
        }
        // "_" means "not tied to one account" (e.g. list_accounts): send no acting_email,
        // because Superhuman rejects unknown accounts.
        let mcp = self.mcp()?;
        let v = if account == "_" { mcp.call(tool, args).await? } else { mcp.call_as(account, tool, args).await? };
        self.cache.lock().await.insert(key, (Instant::now(), v.clone()));
        Ok(v)
    }

    pub fn prefs(&self, email: &str) -> AccountPrefs {
        self.cfg.prefs(email)
    }

    /// Linked accounts, primary first.
    pub async fn account_emails(&self) -> Result<Vec<(String, bool)>> {
        if self.demo() {
            return Ok(self.cfg.accounts.iter().enumerate().map(|(i, a)| (a.email.clone(), i == 0)).collect());
        }
        let v = self.cached("_", "list_accounts", json!({})).await?;
        let mut out: Vec<(String, bool)> = v["accounts"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|a| Some((a["accountEmail"].as_str()?.to_string(), a["isPrimary"].as_bool().unwrap_or(false))))
            .collect();
        out.sort_by_key(|(_, p)| !*p);
        Ok(out)
    }

    pub async fn primary(&self) -> Result<String> {
        self.account_emails().await?.into_iter().next().map(|(e, _)| e).ok_or_else(|| anyhow!("no Superhuman account linked"))
    }

    fn account_meta(&self, email: &str, idx: usize, primary: bool) -> Account {
        let p = self.prefs(email);
        Account {
            email: email.into(),
            label: p.label.clone().unwrap_or_else(|| crate::names::display_name(email)),
            color: p.color.clone().unwrap_or_else(|| PALETTE[idx % PALETTE.len()].to_string()),
            primary,
            style: p.style.clone(),
            unread: 0,
            important_unread: 0,
            error: None,
        }
    }

    /// Recent inbox + Important + "[1. Respond]" threads for one account, de-duplicated.
    pub async fn threads_for(&self, email: &str) -> Result<Vec<Thread>> {
        let prefs = self.prefs(email);
        let raw: Vec<Value> = if self.demo() {
            demo::threads(email)
        } else {
            let (inbox, important, respond) = tokio::join!(
                self.cached(email, "list_threads", json!({"labels": ["INBOX"], "limit": 50})),
                self.cached(email, "list_threads", json!({"split": "Important", "limit": 50})),
                self.cached(email, "list_threads", json!({"labels": ["[1. Respond]"], "limit": 25})),
            );
            let inbox = inbox?;
            [Some(inbox), important.ok(), respond.ok()]
                .into_iter()
                .flatten()
                .flat_map(|v| v["threads"].as_array().cloned().unwrap_or_default())
                .collect()
        };
        let mut seen = std::collections::HashSet::new();
        let mut out: Vec<Thread> = raw
            .iter()
            .filter_map(|v| model::parse_thread(email, v, email, &prefs))
            .filter(|t| seen.insert(t.id.clone()))
            .collect();
        out.sort_by(|a, b| b.date.cmp(&a.date));
        Ok(out)
    }

    pub async fn events_for(&self, email: &str, days: i64) -> Result<Vec<Event>> {
        let today = self.cfg.tz()?.now().date();
        self.events_range(email, today, days).await
    }

    /// Events on one account's calendar for `days` local days starting at `from`.
    pub async fn events_range(&self, email: &str, from: chrono::NaiveDate, days: i64) -> Result<Vec<Event>> {
        let tz = self.cfg.tz()?;
        let start_local = from.and_hms_opt(0, 0, 0).unwrap();
        let start = tz.to_utc(start_local);
        let end = tz.to_utc(start_local + Duration::days(days));
        let raw: Vec<Value> = if self.demo() {
            let today = tz.to_utc(tz.now().date().and_hms_opt(0, 0, 0).unwrap());
            let cal = self.demo_cal.lock().await;
            let mut v = demo::events(email, today);
            v.extend(cal.added.iter().filter(|(e, _)| e == email).map(|(_, ev)| ev.clone()));
            v.retain(|ev| !cal.deleted.contains(ev["event_id"].as_str().unwrap_or("")));
            for ev in v.iter_mut() {
                if let Some(st) = cal.rsvp.get(ev["event_id"].as_str().unwrap_or("")) {
                    ev["my_status"] = json!(st);
                }
            }
            v
        } else {
            let v = self
                .cached(email, "list_calendar_events", json!({"start_date": start.to_rfc3339(), "end_date": end.to_rfc3339(), "max_results": 100}))
                .await?;
            v["events"].as_array().cloned().unwrap_or_default()
        };
        Ok(raw
            .iter()
            .filter_map(|e| model::parse_event(email, e, &tz))
            .filter(|e| e.end > start && e.start < end || (e.start >= start && e.start < end))
            .collect())
    }

    /// Create or update an event on one account's calendar.
    pub async fn save_event(&self, input: &EventInput) -> Result<Value> {
        let tz = self.cfg.tz()?;
        if input.title.trim().is_empty() {
            return Err(anyhow!("Give the event a title"));
        }
        let (start, end) = if input.all_day {
            let d1 = chrono::NaiveDate::parse_from_str(&input.start[..10.min(input.start.len())], "%Y-%m-%d")?;
            let d2 = chrono::NaiveDate::parse_from_str(&input.end[..10.min(input.end.len())], "%Y-%m-%d").unwrap_or(d1);
            (d1.and_hms_opt(0, 0, 0).unwrap(), (d2.max(d1) + Duration::days(1)).and_hms_opt(0, 0, 0).unwrap())
        } else {
            let p = |s: &str| chrono::NaiveDateTime::parse_from_str(s, "%Y-%m-%dT%H:%M").map_err(|_| anyhow!("Times must look like 2026-12-01T13:30"));
            (p(&input.start)?, p(&input.end)?)
        };
        if end <= start {
            return Err(anyhow!("The event must end after it starts"));
        }
        let mut args = json!({
            "title": input.title.trim(),
            "start": tz.rfc3339(start),
            "end": tz.rfc3339(end),
            "timezone": self.cfg.timezone,
            "is_all_day": input.all_day,
        });
        if !input.location.trim().is_empty() {
            args["location"] = json!(input.location.trim());
        }
        if !input.description.trim().is_empty() {
            args["description"] = json!(input.description.trim());
        }
        let attendees: Vec<&str> = input.attendees.iter().map(|a| a.trim()).filter(|a| a.contains('@')).collect();
        if !attendees.is_empty() {
            args["attendees"] = json!(attendees);
        }
        if input.conference {
            args["conference"] = json!(true);
        }
        if let Some(id) = input.event_id.as_deref().filter(|s| !s.is_empty()) {
            args["event_id"] = json!(id);
        }
        if self.demo() {
            let mut cal = self.demo_cal.lock().await;
            let id = input.event_id.clone().filter(|s| !s.is_empty()).unwrap_or_else(|| format!("demo-{}", rand::random::<u32>()));
            cal.added.retain(|(_, e)| e["event_id"] != json!(id));
            cal.deleted.insert(id.clone());
            let new_id = format!("{id}~");
            cal.deleted.remove(&new_id);
            let date = |n: chrono::NaiveDateTime| if input.all_day { n.date().to_string() } else { tz.rfc3339(n) };
            cal.added.push((input.account.clone(), json!({
                "event_id": new_id, "title": input.title.trim(), "start": date(start), "end": date(end),
                "location": input.location.trim(), "my_status": "accepted",
                "video_link": if input.conference { Some("https://meet.example/new") } else { None }
            })));
            return Ok(json!({"event_id": new_id}));
        }
        let r = self.mcp()?.call_as(&input.account, "create_or_update_event", args).await?;
        self.clear_cache().await;
        Ok(r)
    }

    pub async fn rsvp(&self, account: &str, event_id: &str, response: &str) -> Result<Value> {
        if !["accepted", "declined", "tentative"].contains(&response) {
            return Err(anyhow!("response must be accepted, declined or tentative"));
        }
        if self.demo() {
            self.demo_cal.lock().await.rsvp.insert(event_id.into(), response.into());
            return Ok(json!({"ok": true}));
        }
        let r = self.mcp()?.call_as(account, "set_rsvp_event", json!({"event_id": event_id, "response": response})).await?;
        self.clear_cache().await;
        Ok(r)
    }

    pub async fn delete_event(&self, account: &str, event_id: &str) -> Result<Value> {
        if self.demo() {
            self.demo_cal.lock().await.deleted.insert(event_id.into());
            return Ok(json!({"ok": true}));
        }
        let r = self.mcp()?.call_as(account, "delete_event", json!({"event_id": event_id})).await?;
        self.clear_cache().await;
        Ok(r)
    }

    pub async fn thread(&self, email: &str, id: &str) -> Result<ThreadFull> {
        if self.demo() {
            return demo::thread_full(email, id).ok_or_else(|| anyhow!("not found"));
        }
        let v = self
            .mcp()?
            .call_as(email, "get_thread", json!({"thread_id": id, "include_comments": false, "include_drafts": false, "message_limit": 20}))
            .await?;
        Ok(model::parse_thread_full(email, &v))
    }

    /// Everything across accounts, for the dashboard.
    pub async fn overview(&self) -> Result<Overview> {
        let emails = self.account_emails().await?;
        let digest_subject = self.cfg.digest_subject.to_lowercase();
        let per_account = join_all(emails.iter().map(|(e, _)| async move {
            let (t, ev, splits) = tokio::join!(self.threads_for(e), self.events_for(e, 2), self.splits(e));
            (t, ev, splits)
        }))
        .await;

        let mut accounts = Vec::new();
        let mut threads = Vec::new();
        let mut events = Vec::new();
        for (i, ((email, primary), (t, ev, splits))) in emails.iter().zip(per_account).enumerate() {
            let mut acc = self.account_meta(email, i, *primary);
            match t {
                Ok(t) => {
                    acc.unread = t.iter().filter(|x| x.unread).count() as u64;
                    threads.extend(t);
                }
                Err(e) => acc.error = Some(e.to_string()),
            }
            if let Ok((inbox_unread, imp_unread)) = splits {
                acc.unread = inbox_unread.max(acc.unread);
                acc.important_unread = imp_unread;
            }
            match ev {
                Ok(ev) => events.extend(ev),
                Err(e) => {
                    acc.error.get_or_insert(format!("calendar: {e}"));
                }
            }
            accounts.push(acc);
        }
        threads.retain(|t| !t.subject.to_lowercase().starts_with(&digest_subject));
        model::mark_clashes(&mut events);

        let mut cats: HashMap<String, usize> = HashMap::new();
        for t in &threads {
            *cats.entry(t.category.clone()).or_default() += 1;
        }
        let mut categories: Vec<_> = cats.into_iter().collect();
        categories.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));

        let waiting = threads.iter().filter(|t| t.waiting).cloned().collect();
        Ok(Overview {
            generated_at: Utc::now(),
            timezone: self.cfg.timezone.clone(),
            todo: self.todos(&threads),
            waiting,
            events,
            categories,
            accounts,
        })
    }

    async fn splits(&self, email: &str) -> Result<(u64, u64)> {
        if self.demo() {
            return Ok((0, 0));
        }
        let v = self.cached(email, "list_splits", json!({})).await?;
        let mut inbox = 0;
        let mut imp = 0;
        for s in v["splits"].as_array().into_iter().flatten() {
            match s["id"].as_str() {
                Some("SH_INBOX") => inbox = s["unread_count"].as_u64().unwrap_or(0),
                Some("SH_IMPORTANT") => imp = s["unread_count"].as_u64().unwrap_or(0),
                _ => {}
            }
        }
        Ok((inbox, imp))
    }

    /// Score threads into a prioritised to-do list.
    fn todos(&self, threads: &[Thread]) -> Vec<Todo> {
        let now = Utc::now();
        let mut out: Vec<(i32, Todo)> = threads
            .iter()
            .filter(|t| !model::is_low_priority(&t.category) || t.needs_reply)
            .filter(|t| now - t.date < Duration::days(14))
            .filter_map(|t| {
                let prefs = self.prefs(&t.account);
                let vip = model::is_vip(&prefs, t);
                let urgent = model::is_urgent(&prefs, t);
                let mut score = 0;
                let mut why = Vec::new();
                if t.needs_reply {
                    score += 4;
                    why.push("marked Respond");
                }
                if vip {
                    score += 3;
                    why.push("VIP sender");
                }
                if urgent {
                    score += 2;
                    why.push("urgent wording");
                }
                if t.important {
                    score += 2;
                    why.push("Important");
                }
                if t.starred {
                    score += 1;
                    why.push("starred");
                }
                if t.unread {
                    score += 1;
                    why.push("unread");
                }
                if now - t.date < Duration::hours(36) {
                    score += 1;
                }
                if score < 3 {
                    return None;
                }
                let priority = if score >= 7 { "High" } else if score >= 5 { "Medium" } else { "Low" };
                Some((score, Todo { thread: t.clone(), priority, reason: why.join(" · ") }))
            })
            .collect();
        out.sort_by(|a, b| b.0.cmp(&a.0).then(b.1.thread.date.cmp(&a.1.thread.date)));
        out.into_iter().take(25).map(|(_, t)| t).collect()
    }

    /// Unified inbox, optionally filtered.
    pub async fn inbox(&self, account: Option<&str>, category: Option<&str>, q: Option<&str>) -> Result<Vec<Thread>> {
        let emails: Vec<String> = match account {
            Some(a) if a != "all" => vec![a.to_string()],
            _ => self.account_emails().await?.into_iter().map(|(e, _)| e).collect(),
        };
        let all = join_all(emails.iter().map(|e| self.threads_for(e))).await;
        let q = q.map(|s| s.to_lowercase()).filter(|s| !s.is_empty());
        let mut out: Vec<Thread> = all
            .into_iter()
            .filter_map(Result::ok)
            .flatten()
            .filter(|t| category.map_or(true, |c| c == "all" || t.category.eq_ignore_ascii_case(c)))
            .filter(|t| {
                q.as_ref().map_or(true, |q| {
                    t.subject.to_lowercase().contains(q) || t.snippet.to_lowercase().contains(q) || t.from.to_lowercase().contains(q)
                })
            })
            .collect();
        out.sort_by(|a, b| b.date.cmp(&a.date));
        Ok(out)
    }

    /// The newest digest email in any account's inbox.
    pub async fn latest_digest(&self) -> Result<Option<ThreadFull>> {
        if self.demo() {
            return Ok(demo::digest_thread(&self.cfg));
        }
        for (email, _) in self.account_emails().await? {
            let v = self.cached(&email, "list_threads", json!({"subject_contains": self.cfg.digest_subject, "limit": 1})).await?;
            if let Some(id) = v["threads"][0]["thread_id"].as_str() {
                return self.thread(&email, id).await.map(Some);
            }
        }
        Ok(None)
    }

    /// Draft a reply with Superhuman's own writer (it already knows your writing style).
    /// Returns the text and the Superhuman draft it saved, which Send then updates.
    pub async fn draft_reply(&self, email: &str, thread_id: &str, guidance: &str) -> Result<(String, Option<String>)> {
        self.draft_with_superhuman(email, thread_id, guidance).await
    }

    async fn draft_with_superhuman(&self, email: &str, thread_id: &str, guidance: &str) -> Result<(String, Option<String>)> {
        let mcp = self.mcp()?;
        let style = self.prefs(email).style.map(|s| format!(" Style: {s}")).unwrap_or_default();
        let ask = if guidance.trim().is_empty() { "Write the most sensible reply.".to_string() } else { guidance.trim().to_string() };
        let d = mcp
            .call_as(email, "create_or_update_draft", json!({"type": "reply", "thread_id": thread_id, "instructions": format!("{ask}{style}")}))
            .await?;
        let draft_id = d["draft"]["draft_id"].as_str().or(d["draft_id"].as_str()).map(str::to_string);
        let mut body = d["draft"]["body"].as_str().or(d["body"].as_str()).map(str::to_string);
        if body.is_none() {
            if let Some(id) = &draft_id {
                let full = mcp.call_as(email, "get_draft", json!({"draft_id": id})).await?;
                body = full["body"].as_str().or(full["draft"]["body"].as_str()).map(str::to_string);
            }
        }
        let text = crate::text::html_to_text(&body.unwrap_or_default());
        if text.trim().is_empty() {
            return Err(anyhow!("Superhuman didn't return a draft"));
        }
        Ok((text, draft_id))
    }

    /// Reply on a thread from the right account: create a Superhuman draft, then send it.
    pub async fn send_reply(&self, email: &str, thread_id: &str, text: &str, send: bool, draft_id: Option<&str>) -> Result<Value> {
        let mcp = self.mcp()?;
        let body = text_to_html(text);
        let mut args = json!({"type": "reply", "thread_id": thread_id, "body": body});
        if let Some(id) = draft_id {
            args["draft_id"] = json!(id); // update the draft Superhuman's writer made, no duplicate
        }
        let draft = mcp.call_as(email, "create_or_update_draft", args).await?;
        let draft_id = draft["draft"]["draft_id"].as_str().or(draft["draft_id"].as_str()).map(str::to_string);
        if !send {
            return Ok(json!({"status": "draft_saved", "draft_id": draft_id}));
        }
        let draft_id = draft_id.ok_or_else(|| anyhow!("Superhuman did not return a draft id"))?;
        let r = mcp.call_as(email, "send_draft", json!({"draft_id": draft_id})).await?;
        self.clear_cache().await;
        Ok(r)
    }

    /// Build today's digest as an HTML email body.
    pub async fn build_digest(&self) -> Result<(String, String)> {
        let ov = self.overview().await?;
        let tz = self.cfg.tz()?;
        let today = tz.now();
        let subject = format!("{} – {}", self.cfg.digest_subject, today.format("%A, %B %-d"));
        let mut html = template_digest(&ov, &tz);
        if !self.demo() {
            let ask = "Write a short daily briefing for today: the most important things to act on, anything waiting for my reply, \
                       bills or deadlines coming up, and anything notable on my calendar. Short bullet points.";
            if let Ok(summary) = self.ask_superhuman(ask).await {
                html = format!("<h2 style=\"margin:0 0 8px\">Briefing from Superhuman AI</h2>{}{}", markdown_to_html(&summary), html);
            }
        }
        Ok((subject, wrap_digest(&html, &ov)))
    }

    pub async fn send_digest(&self) -> Result<String> {
        let mcp = self.mcp()?;
        let primary = self.primary().await?;
        let from = self.cfg.digest.from_account.clone().unwrap_or_else(|| primary.clone());
        let to = self.cfg.digest.to.clone().unwrap_or(primary);
        let (subject, html) = self.build_digest().await?;
        let draft = mcp
            .call_as(&from, "create_or_update_draft", json!({"type": "new", "to": [to], "subject": subject, "body": html}))
            .await?;
        let draft_id = draft["draft"]["draft_id"].as_str().or(draft["draft_id"].as_str()).ok_or_else(|| anyhow!("no draft id returned"))?;
        mcp.call_as(&from, "send_draft", json!({"draft_id": draft_id})).await?;
        Ok(format!("Sent \"{subject}\" to {to}"))
    }

    /// Push an account's reply style into Superhuman's own AI writer.
    pub async fn sync_style(&self, email: &str) -> Result<Value> {
        let style = self.prefs(email).style.ok_or_else(|| anyhow!("No style set for {email} in inbox-hub.toml"))?;
        self.mcp()?.call_as(email, "update_personalization", json!({"feedback": style})).await
    }

    pub async fn link_account(&self) -> Result<Value> {
        self.mcp()?.call("add_account", json!({})).await
    }

}

fn text_to_html(text: &str) -> String {
    text.split("\n\n")
        .map(|p| format!("<p>{}</p>", model::html_escape(p).replace('\n', "<br>")))
        .collect::<Vec<_>>()
        .join("")
}

fn template_digest(ov: &Overview, tz: &crate::time::Tz) -> String {
    let label = |e: &str| ov.accounts.iter().find(|a| a.email == e).map(|a| a.label.clone()).unwrap_or_else(|| e.into());
    let esc = model::html_escape;
    let mut h = String::new();
    h.push_str("<h2 style=\"margin:0 0 8px\">Top priorities</h2><ol>");
    for t in ov.todo.iter().take(3) {
        h.push_str(&format!("<li><b>{}</b> — {} <span style=\"color:#888\">({})</span></li>", esc(&t.thread.subject), esc(&t.thread.from_name), esc(&label(&t.thread.account))));
    }
    h.push_str("</ol><h2 style=\"margin:16px 0 8px\">To do</h2><ul>");
    for t in &ov.todo {
        h.push_str(&format!("<li>[{}] <b>{}</b> — {}: {} <span style=\"color:#888\">· {}</span></li>", t.priority, esc(&t.thread.subject), esc(&t.thread.from_name), esc(&t.thread.snippet.chars().take(140).collect::<String>()), esc(&label(&t.thread.account))));
    }
    h.push_str("</ul><h2 style=\"margin:16px 0 8px\">Today's schedule</h2><ul>");
    let today = tz.now().date();
    for e in ov.events.iter().filter(|e| tz.local(e.start).date() == today) {
        let when = if e.all_day { "All day".into() } else { format!("{}–{}", tz.local(e.start).format("%H:%M"), tz.local(e.end).format("%H:%M")) };
        h.push_str(&format!("<li>{}{} <b>{}</b> <span style=\"color:#888\">· {}</span>{}</li>", if e.clash { "⚠️ " } else { "" }, when, esc(&e.title), esc(&label(&e.account)), if e.needs_rsvp { " — RSVP needed" } else { "" }));
    }
    h.push_str("</ul>");
    if !ov.waiting.is_empty() {
        h.push_str("<h2 style=\"margin:16px 0 8px\">Waiting on others</h2><ul>");
        for t in &ov.waiting {
            h.push_str(&format!("<li>{} — {}</li>", esc(&t.subject), esc(&t.from_name)));
        }
        h.push_str("</ul>");
    }
    h
}

fn wrap_digest(inner: &str, ov: &Overview) -> String {
    let accounts: Vec<String> = ov.accounts.iter().map(|a| model::html_escape(&a.email)).collect();
    format!(
        "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;font-size:14px;line-height:1.5;color:#111;max-width:640px\">{inner}\
         <p style=\"color:#888;font-size:12px;margin-top:24px\">Inbox Hub · {} account(s): {}</p></div>",
        accounts.len(),
        accounts.join(", ")
    )
}

impl Hub {
    /// Answer a question with Superhuman's own assistant (no Claude needed), asking every
    /// linked account and labelling each answer.
    pub async fn ask_superhuman(&self, question: &str) -> Result<String> {
        let mcp = self.mcp()?;
        let emails = self.account_emails().await?;
        let answers = join_all(emails.iter().map(|(e, _)| async move {
            let r = mcp.call_as(e, "query_email_and_calendar", json!({"question": question})).await;
            (e.clone(), r)
        }))
        .await;
        let mut out = Vec::new();
        let mut errors = Vec::new();
        for (email, r) in answers {
            match r {
                Ok(v) => {
                    let text = answer_text(&v);
                    if !text.trim().is_empty() {
                        out.push((email, text));
                    }
                }
                Err(e) => errors.push(format!("{email}: {e:#}")),
            }
        }
        if out.is_empty() {
            return Err(anyhow!(if errors.is_empty() { "Superhuman had no answer".to_string() } else { errors.join("; ") }));
        }
        if out.len() == 1 {
            return Ok(out.remove(0).1);
        }
        Ok(out
            .into_iter()
            .map(|(email, text)| format!("**{}**\n{}", self.account_meta(&email, 0, false).label, text))
            .collect::<Vec<_>>()
            .join("\n\n"))
    }
}

/// Pull the readable answer out of whatever shape Superhuman returns.
fn answer_text(v: &Value) -> String {
    if let Some(s) = v.as_str() {
        return s.to_string();
    }
    for k in ["answer", "response", "text", "result", "message", "summary"] {
        if let Some(s) = v[k].as_str() {
            return s.to_string();
        }
    }
    serde_json::to_string_pretty(v).unwrap_or_default()
}

/// Superhuman AI's Markdown-ish answer as simple email HTML.
fn markdown_to_html(md: &str) -> String {
    let bold = |t: &str| {
        let parts: Vec<&str> = t.split("**").collect();
        parts.iter().enumerate().map(|(i, p)| if i % 2 == 1 { format!("<b>{}</b>", model::html_escape(p)) } else { model::html_escape(p) }).collect::<String>()
    };
    let mut out = String::new();
    let mut in_list = false;
    for line in md.lines() {
        let l = line.trim();
        let item = l.strip_prefix("- ").or_else(|| l.strip_prefix("* ")).or_else(|| l.strip_prefix("• "));
        match item {
            Some(it) => {
                if !in_list {
                    out.push_str("<ul>");
                    in_list = true;
                }
                out.push_str(&format!("<li>{}</li>", bold(it)));
            }
            None => {
                if in_list {
                    out.push_str("</ul>");
                    in_list = false;
                }
                if !l.is_empty() {
                    out.push_str(&format!("<p>{}</p>", bold(l.trim_start_matches('#').trim())));
                }
            }
        }
    }
    if in_list {
        out.push_str("</ul>");
    }
    out
}

// ------------------------------------------------------------ Ask history
// Kept in a private snippet in the primary Superhuman account, so the desktop app and the
// Android app show the same conversation. Snippets can't be sent by accident.

pub const ASK_SNIPPET: &str = "Inbox Hub — Ask history";
const ASK_TAG: &str = "inboxhub-ask-v1:";
const ASK_KEEP: usize = 100;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, PartialEq)]
pub struct ChatMsg {
    pub id: String,
    /// "user" or "ai"
    pub role: String,
    pub text: String,
    /// Unix milliseconds
    pub at: i64,
    #[serde(default)]
    pub device: String,
}

#[derive(Debug, Default, Clone, serde::Serialize, serde::Deserialize)]
pub struct AskHistory {
    #[serde(default)]
    pub cleared_at: i64,
    #[serde(default)]
    pub msgs: Vec<ChatMsg>,
}

impl AskHistory {
    pub fn decode(body: &str) -> Option<Self> {
        use base64::Engine;
        let start = body.find(ASK_TAG)? + ASK_TAG.len();
        let b64: String = body[start..].chars().take_while(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '/' | '=')).collect();
        let raw = base64::engine::general_purpose::STANDARD.decode(b64).ok()?;
        serde_json::from_slice(&raw).ok()
    }

    pub fn encode(&self) -> String {
        use base64::Engine;
        let raw = serde_json::to_vec(self).unwrap_or_default();
        format!(
            "<p>Inbox Hub keeps your Ask conversation here so your phone and computer stay in sync. You can ignore this snippet.</p><p>{ASK_TAG}{}</p>",
            base64::engine::general_purpose::STANDARD.encode(raw)
        )
    }

    /// Union by id, drop anything older than the latest "clear", newest last, capped.
    pub fn merge(mut self, other: AskHistory) -> AskHistory {
        self.cleared_at = self.cleared_at.max(other.cleared_at);
        for m in other.msgs {
            if !self.msgs.iter().any(|x| x.id == m.id) {
                self.msgs.push(m);
            }
        }
        let cut = self.cleared_at;
        self.msgs.retain(|m| m.at > cut);
        self.msgs.sort_by_key(|m| m.at);
        if self.msgs.len() > ASK_KEEP {
            self.msgs.drain(..self.msgs.len() - ASK_KEEP);
        }
        self
    }
}

impl Hub {
    async fn ask_snippet(&self) -> Result<(String, Option<String>, AskHistory)> {
        let mcp = self.mcp()?;
        let primary = self.primary().await?;
        let list = mcp.call_as(&primary, "list_snippets", json!({"query": ASK_SNIPPET, "include_team": false})).await?;
        let rows: Vec<Value> = ["snippets", "results", "items"]
            .iter()
            .find_map(|k| list[*k].as_array().cloned())
            .or_else(|| list.as_array().cloned())
            .unwrap_or_default();
        let id = rows
            .iter()
            .find(|r| r["name"].as_str() == Some(ASK_SNIPPET))
            .and_then(|r| r["id"].as_str().or(r["snippet_id"].as_str()))
            .map(str::to_string);
        let history = match &id {
            Some(id) => {
                let snip = mcp.call_as(&primary, "get_snippet", json!({"id": id})).await?;
                let body = snip["body"].as_str().or(snip["snippet"]["body"].as_str()).or(snip["html"].as_str()).unwrap_or_default();
                AskHistory::decode(body).unwrap_or_default()
            }
            None => AskHistory::default(),
        };
        Ok((primary, id, history))
    }

    /// The shared Ask conversation (same on desktop and Android).
    pub async fn load_ask_history(&self) -> Result<AskHistory> {
        if self.demo() {
            return Ok(AskHistory::default());
        }
        Ok(self.ask_snippet().await?.2.merge(AskHistory::default()))
    }

    /// Merge `local` into the shared conversation and save it back. Returns the merged result.
    pub async fn sync_ask_history(&self, local: AskHistory) -> Result<AskHistory> {
        if self.demo() {
            return Ok(local);
        }
        let (primary, id, remote) = self.ask_snippet().await?;
        let merged = remote.merge(local);
        let mut args = json!({"name": ASK_SNIPPET, "body": merged.encode()});
        if let Some(id) = id {
            args["id"] = json!(id);
        }
        self.mcp()?.call_as(&primary, "create_or_update_snippet", args).await?;
        Ok(merged)
    }
}

#[cfg(test)]
mod ask_history_tests {
    use super::*;

    fn m(id: &str, at: i64) -> ChatMsg {
        ChatMsg { id: id.into(), role: "user".into(), text: id.into(), at, device: String::new() }
    }

    #[test]
    fn round_trip_and_merge() {
        let a = AskHistory { cleared_at: 0, msgs: vec![m("a", 1), m("b", 3)] };
        let decoded = AskHistory::decode(&a.encode()).unwrap();
        assert_eq!(decoded.msgs, a.msgs);
        let b = AskHistory { cleared_at: 0, msgs: vec![m("b", 3), m("c", 2)] };
        let merged = a.merge(b);
        assert_eq!(merged.msgs.iter().map(|x| x.id.as_str()).collect::<Vec<_>>(), vec!["a", "c", "b"]);
        let cleared = merged.merge(AskHistory { cleared_at: 2, msgs: vec![] });
        assert_eq!(cleared.msgs.iter().map(|x| x.id.as_str()).collect::<Vec<_>>(), vec!["b"]);
    }
}

impl Hub {
    /// Superhuman thread actions from the email view's toolbar.
    pub async fn thread_action(&self, account: &str, thread_id: &str, last_message_id: &str, action: &str) -> Result<String> {
        let mcp = self.mcp()?;
        let tz = self.cfg.tz()?;
        let update = |extra: Value| {
            let mut args = json!({"thread_id": thread_id, "last_message_id": last_message_id});
            for (k, v) in extra.as_object().cloned().unwrap_or_default() {
                args[k] = v;
            }
            args
        };
        let local_at = |days: i64, h: u32| {
            let d = tz.now().date() + Duration::days(days);
            tz.to_utc(d.and_hms_opt(h, 0, 0).unwrap()).to_rfc3339()
        };
        let msg = match action {
            "done" => { mcp.call_as(account, "update_thread", update(json!({"mark_done": true, "mark_read": true}))).await?; "Marked done" }
            "star" => { mcp.call_as(account, "update_thread", update(json!({"mark_starred": true}))).await?; "Starred" }
            "unstar" => { mcp.call_as(account, "update_thread", update(json!({"mark_starred": false}))).await?; "Unstarred" }
            "read" => { mcp.call_as(account, "update_thread", update(json!({"mark_read": true}))).await?; "Marked read" }
            "unread" => { mcp.call_as(account, "update_thread", update(json!({"mark_read": false}))).await?; "Marked unread" }
            "important" => { mcp.call_as(account, "update_thread", update(json!({"mark_important": true}))).await?; "Moved to Important" }
            "other" => { mcp.call_as(account, "update_thread", update(json!({"mark_important": false}))).await?; "Moved to Other" }
            "remind-3h" | "remind-tomorrow" | "remind-monday" => {
                let at = match action {
                    "remind-3h" => (Utc::now() + Duration::hours(3)).to_rfc3339(),
                    "remind-tomorrow" => local_at(1, 8),
                    _ => {
                        let today = tz.now().date();
                        let ahead = (7 - today.weekday().num_days_from_monday() as i64) % 7;
                        local_at(if ahead == 0 { 7 } else { ahead }, 8)
                    }
                };
                mcp.call_as(account, "create_or_update_reminder", json!({"thread_id": thread_id, "remind_at": at})).await?;
                "Reminder set — it'll come back to the top of your inbox"
            }
            "trash" => { mcp.call_as(account, "trash_thread", json!({"thread_id": thread_id})).await?; "Moved to trash" }
            "spam" => { mcp.call_as(account, "mark_spam", json!({"thread_id": thread_id, "also_trash": true})).await?; "Marked as spam" }
            "unsubscribe" => { mcp.call_as(account, "unsubscribe", json!({"thread_id": thread_id, "also_trash": true})).await?; "Unsubscribed" }
            other => return Err(anyhow!("unknown action {other}")),
        };
        self.clear_cache().await;
        Ok(msg.to_string())
    }

    pub async fn make_primary(&self, email: &str) -> Result<()> {
        self.mcp()?.call("set_default_account", json!({"account_email": email})).await?;
        self.clear_cache().await;
        Ok(())
    }

    pub async fn remove_account(&self, email: &str) -> Result<()> {
        self.mcp()?.call("remove_account", json!({"account_email": email})).await?;
        self.clear_cache().await;
        Ok(())
    }

    /// Number of linked accounts, bypassing the cache (used to notice a newly linked account).
    pub async fn account_count_fresh(&self) -> Result<usize> {
        self.clear_cache().await;
        Ok(self.account_emails().await?.len())
    }
}
