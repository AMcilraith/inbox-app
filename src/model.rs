use crate::config::AccountPrefs;
use chrono::{DateTime, Utc};
use serde::Serialize;
use serde_json::Value;

#[derive(Debug, Clone, Serialize)]
pub struct Thread {
    pub account: String,
    pub id: String,
    pub subject: String,
    pub snippet: String,
    pub from: String,
    pub from_name: String,
    pub participants: Vec<String>,
    pub labels: Vec<String>,
    pub date: DateTime<Utc>,
    pub unread: bool,
    pub important: bool,
    pub starred: bool,
    pub needs_reply: bool,
    pub waiting: bool,
    pub has_draft: bool,
    pub message_count: u64,
    pub category: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct Message {
    pub id: String,
    pub from: String,
    pub to: Vec<String>,
    pub sent_at: Option<DateTime<Utc>>,
    pub body: String,
    pub attachments: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ThreadFull {
    pub account: String,
    pub id: String,
    pub subject: String,
    pub messages: Vec<Message>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Event {
    pub account: String,
    pub id: String,
    pub title: String,
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    pub all_day: bool,
    pub location: Option<String>,
    pub link: Option<String>,
    pub needs_rsvp: bool,
    pub my_status: Option<String>,
    pub organizer: Option<String>,
    pub recurring: bool,
    pub clash: bool,
}

pub const PROMOTIONS: &str = "Promotions";
pub const UPDATES: &str = "Updates";
pub const NEWS: &str = "News";
pub const PRIMARY: &str = "Primary";

pub fn is_low_priority(category: &str) -> bool {
    matches!(category, PROMOTIONS | UPDATES | NEWS)
}

fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).unwrap_or_default().to_string()
}

fn strs(v: &Value, k: &str) -> Vec<String> {
    v.get(k)
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect())
        .unwrap_or_default()
}

fn time(v: &Value, k: &str) -> Option<DateTime<Utc>> {
    v.get(k)?.as_str().and_then(|t| DateTime::parse_from_rfc3339(t).ok()).map(|t| t.with_timezone(&Utc))
}

/// "jane.doe@x.com" -> "Jane Doe"; "Jane <jane@x>" -> "Jane".
pub fn display_name(addr: &str) -> String {
    if let Some(i) = addr.find('<') {
        let n = addr[..i].trim().trim_matches('"').trim();
        if !n.is_empty() {
            return n.to_string();
        }
    }
    let local = addr.trim_matches(|c| c == '<' || c == '>').split('@').next().unwrap_or(addr);
    local
        .split(|c: char| c == '.' || c == '_' || c == '-' || c == '+')
        .filter(|p| !p.is_empty())
        .map(|p| {
            let mut c = p.chars();
            c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default()
        })
        .collect::<Vec<_>>()
        .join(" ")
}

pub fn first_name(addr: &str) -> String {
    display_name(addr).split_whitespace().next().unwrap_or("there").to_string()
}

/// Parse one thread from Superhuman's `list_threads` / `get_thread` output.
pub fn parse_thread(account: &str, v: &Value, me: &str, prefs: &AccountPrefs) -> Option<Thread> {
    let id = s(v, "thread_id");
    if id.is_empty() {
        return None;
    }
    let labels = strs(v, "labels");
    let splits = strs(v, "splits");
    let messages = v.get("messages").and_then(Value::as_array);
    // Sender = author of the latest message not from me, else latest author.
    let from = messages
        .and_then(|ms| {
            ms.iter()
                .rev()
                .map(|m| s(m, "from"))
                .find(|f| !f.is_empty() && !f.eq_ignore_ascii_case(me))
                .or_else(|| ms.last().map(|m| s(m, "from")))
        })
        .or_else(|| strs(v, "participants").into_iter().find(|p| !p.eq_ignore_ascii_case(me)))
        .unwrap_or_default();
    let has = |l: &str| labels.iter().any(|x| x.eq_ignore_ascii_case(l));
    let mut t = Thread {
        account: account.to_string(),
        subject: {
            let sub = s(v, "subject");
            if sub.is_empty() { "(no subject)".into() } else { sub }
        },
        snippet: strip_tags(&s(v, "snippet")),
        from_name: display_name(&from),
        from,
        participants: strs(v, "participants"),
        date: time(v, "last_message_at").unwrap_or_else(Utc::now),
        unread: has("UNREAD"),
        important: has("IMPORTANT") || splits.iter().any(|x| x.eq_ignore_ascii_case("Important")),
        starred: has("STARRED"),
        needs_reply: has("[1. Respond]"),
        waiting: has("[3. Waiting]"),
        has_draft: v.get("has_draft").and_then(Value::as_bool).unwrap_or(false),
        message_count: v.get("message_count").and_then(Value::as_u64).unwrap_or(1),
        category: String::new(),
        labels,
        id,
    };
    categorize(prefs, &mut t);
    Some(t)
}

pub fn parse_thread_full(account: &str, v: &Value) -> ThreadFull {
    let messages = v
        .get("messages")
        .and_then(Value::as_array)
        .map(|ms| {
            ms.iter()
                .map(|m| Message {
                    id: s(m, "message_id"),
                    from: s(m, "from"),
                    to: strs(m, "to"),
                    sent_at: time(m, "sent_at"),
                    body: {
                        let b = s(m, "body");
                        if b.is_empty() { html_escape(&s(m, "snippet")) } else { b }
                    },
                    attachments: strs(m, "attachments"),
                })
                .collect()
        })
        .unwrap_or_default();
    ThreadFull { account: account.into(), id: s(v, "thread_id"), subject: s(v, "subject"), messages }
}

/// Parse one event from `list_calendar_events`.
pub fn parse_event(account: &str, v: &Value, tz: &crate::time::Tz) -> Option<Event> {
    let parse = |k: &str| -> Option<(DateTime<Utc>, bool)> {
        let raw = v.get(k)?;
        let raw = raw.as_str().map(str::to_string).or_else(|| {
            raw.get("dateTime").or_else(|| raw.get("date_time")).or_else(|| raw.get("date"))?.as_str().map(str::to_string)
        })?;
        if let Ok(t) = DateTime::parse_from_rfc3339(&raw) {
            return Some((t.with_timezone(&Utc), false));
        }
        let d = chrono::NaiveDate::parse_from_str(&raw, "%Y-%m-%d").ok()?;
        Some((tz.to_utc(d.and_hms_opt(0, 0, 0)?), true))
    };
    let (start, all_day) = parse("start")?;
    let (end, _) = parse("end").unwrap_or((start, all_day));
    let link = ["video_link", "meeting_link", "video_meeting_link", "conference_link", "hangout_link", "meet_link"]
        .iter()
        .find_map(|k| v.get(*k).and_then(Value::as_str).filter(|x| !x.is_empty()).map(str::to_string));
    let status = s(v, "my_status").to_lowercase();
    let title = s(v, "title");
    Some(Event {
        account: account.into(),
        id: s(v, "event_id"),
        title: if title.is_empty() { "(untitled)".into() } else { title },
        start,
        end,
        all_day,
        location: Some(s(v, "location")).filter(|x| !x.is_empty()),
        link,
        needs_rsvp: matches!(status.as_str(), "needsaction" | "needs_action" | "none" | "not_responded"),
        my_status: Some(status).filter(|x| !x.is_empty()),
        organizer: v.get("organizer").and_then(|o| o.as_str().map(str::to_string).or_else(|| o.get("email").and_then(Value::as_str).map(str::to_string))),
        recurring: v.get("recurring").or_else(|| v.get("is_recurring")).and_then(Value::as_bool).unwrap_or(false),
        clash: false,
    })
}

fn contains_any(hay: &str, needles: &[String]) -> bool {
    let hay = hay.to_lowercase();
    needles.iter().any(|n| !n.is_empty() && hay.contains(&n.to_lowercase()))
}

pub fn is_vip(prefs: &AccountPrefs, t: &Thread) -> bool {
    contains_any(&t.from, &prefs.vip) || contains_any(&t.from_name, &prefs.vip)
}

pub fn is_urgent(prefs: &AccountPrefs, t: &Thread) -> bool {
    contains_any(&t.subject, &prefs.urgent_keywords) || contains_any(&t.snippet, &prefs.urgent_keywords)
}

/// Your rules first, then Superhuman's labels, then simple sender heuristics.
pub fn categorize(prefs: &AccountPrefs, t: &mut Thread) {
    for rule in &prefs.categories {
        if contains_any(&t.from, &rule.from) || contains_any(&t.from_name, &rule.from) || contains_any(&t.subject, &rule.subject) {
            t.category = rule.name.clone();
            return;
        }
    }
    let has = |l: &str| t.labels.iter().any(|x| x.eq_ignore_ascii_case(l));
    let sender = t.from.to_lowercase();
    t.category = if has("[Promotions]") || has("CATEGORY_PROMOTIONS") || has("Promotions") {
        PROMOTIONS
    } else if has("[News]") || sender.contains("newsletter") {
        NEWS
    } else if has("[Notifications]")
        || has("CATEGORY_UPDATES")
        || has("CATEGORY_FORUMS")
        || has("CATEGORY_SOCIAL")
        || ["noreply", "no-reply", "donotreply", "notifications@", "notification@", "alerts@", "mailer-daemon"]
            .iter()
            .any(|k| sender.contains(k))
    {
        UPDATES
    } else {
        PRIMARY
    }
    .to_string();
}

pub fn strip_tags(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut in_tag = false;
    for c in s.chars() {
        match c {
            '<' => in_tag = true,
            '>' if in_tag => in_tag = false,
            _ if !in_tag => out.push(c),
            _ => {}
        }
    }
    decode_entities(&out).split_whitespace().collect::<Vec<_>>().join(" ")
}

pub fn decode_entities(s: &str) -> String {
    s.replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
}

pub fn html_escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// Mark every pair of overlapping timed events as a clash.
pub fn mark_clashes(events: &mut [Event]) {
    events.sort_by_key(|e| e.start);
    let n = events.len();
    for i in 0..n {
        if events[i].all_day {
            continue;
        }
        for j in (i + 1)..n {
            if events[j].all_day {
                continue;
            }
            if events[j].start >= events[i].end {
                break;
            }
            events[i].clash = true;
            events[j].clash = true;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    #[test]
    fn names() {
        assert_eq!(display_name("jane.doe@x.com"), "Jane Doe");
        assert_eq!(display_name("\"Bob Ray\" <b@x.com>"), "Bob Ray");
        assert_eq!(first_name("meadowoodpremierdentistry@gmail.com"), "Meadowoodpremierdentistry");
    }

    #[test]
    fn parses_superhuman_thread() {
        let v: Value = serde_json::json!({
            "thread_id":"t1","subject":"appointment reminder","snippet":"Good <b>Afternoon</b> &amp; hi",
            "participants":["dentist@x.com","me@x.com"],
            "labels":["[1. Respond]","IMPORTANT","UNREAD","INBOX"],"splits":["Important"],
            "last_message_at":"2026-11-21T17:43:18Z","message_count":1,
            "messages":[{"from":"dentist@x.com","to":["me@x.com"]}]
        });
        let t = parse_thread("me@x.com", &v, "me@x.com", &AccountPrefs::default()).unwrap();
        assert!(t.needs_reply && t.important && t.unread);
        assert_eq!(t.from, "dentist@x.com");
        assert_eq!(t.snippet, "Good Afternoon & hi");
        assert_eq!(t.category, PRIMARY);
    }

    fn ev(h1: u32, h2: u32) -> Event {
        Event {
            account: "a".into(),
            id: format!("{h1}"),
            title: String::new(),
            start: Utc.with_ymd_and_hms(2026, 1, 1, h1, 0, 0).unwrap(),
            end: Utc.with_ymd_and_hms(2026, 1, 1, h2, 0, 0).unwrap(),
            all_day: false,
            location: None,
            link: None,
            needs_rsvp: false,
            my_status: None,
            organizer: None,
            recurring: false,
            clash: false,
        }
    }

    #[test]
    fn clashes() {
        let mut v = vec![ev(9, 10), ev(10, 11), ev(10, 12), ev(13, 14)];
        mark_clashes(&mut v);
        let flags: Vec<bool> = v.iter().map(|e| e.clash).collect();
        assert_eq!(flags, vec![false, true, true, false]);
    }
}
