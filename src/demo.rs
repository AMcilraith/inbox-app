//! Sample data in Superhuman's own format, so `--demo` exercises the real parsing code.

use crate::{
    config::Config,
    model::{Message, ThreadFull},
};
use chrono::{DateTime, Duration, Utc};
use serde_json::{json, Value};

struct T {
    id: &'static str,
    from: &'static str,
    subject: &'static str,
    snippet: &'static str,
    labels: &'static [&'static str],
    hours_ago: i64,
}

fn data(email: &str) -> Vec<T> {
    match email.split('@').nth(1).unwrap_or("") {
        "company.example" => vec![
            T { id: "w1", from: "ceo@company.example", subject: "Board deck — need your numbers by EOD", snippet: "Can you drop the Q4 product metrics into slide 7 before the board pre-read goes out tonight?", labels: &["UNREAD", "IMPORTANT", "INBOX", "[1. Respond]"], hours_ago: 2 },
            T { id: "w2", from: "priya@bigclient.example", subject: "Renewal terms for 2027", snippet: "Thanks for the call. Attached are our proposed changes to the renewal — could you confirm the seat count?", labels: &["UNREAD", "IMPORTANT", "INBOX"], hours_ago: 5 },
            T { id: "w3", from: "sam@company.example", subject: "Sprint review moved to 2pm", snippet: "Heads up, sprint review is now 2–3pm so design can join.", labels: &["INBOX", "[2. FYI]"], hours_ago: 9 },
            T { id: "w4", from: "jira@company.example", subject: "[JIRA] 4 issues assigned to you", snippet: "PROD-212, PROD-219, PROD-220, PROD-231 were assigned to you.", labels: &["INBOX", "[Notifications]"], hours_ago: 11 },
            T { id: "w5", from: "legal@acme.example", subject: "DPA signature", snippet: "We're still waiting on the countersigned DPA from your side.", labels: &["INBOX", "[3. Waiting]"], hours_ago: 70 },
        ],
        "mystudio.example" => vec![
            T { id: "b1", from: "orders@shopify.com", subject: "Order #1042 — refund requested", snippet: "A customer has requested a refund for order #1042 (Ceramic mug set). Respond within 48 hours.", labels: &["UNREAD", "IMPORTANT", "INBOX"], hours_ago: 3 },
            T { id: "b2", from: "nora.blake@gmail.com", subject: "Quote for 40 custom mugs?", snippet: "Hi! I'm organising a team offsite and would love a quote for 40 mugs with our logo.", labels: &["UNREAD", "INBOX", "[1. Respond]"], hours_ago: 20 },
            T { id: "b3", from: "newsletter@craftweekly.example", subject: "This week in craft business", snippet: "Pricing tips, holiday market checklists and more.", labels: &["INBOX", "[News]", "UNREAD"], hours_ago: 26 },
        ],
        "uni.example" => vec![
            T { id: "s1", from: "prof.ortiz@uni.example", subject: "Assignment 3 due Friday 11:59pm", snippet: "Reminder that Assignment 3 submissions close Friday at 11:59pm. No extensions without documentation.", labels: &["UNREAD", "IMPORTANT", "INBOX"], hours_ago: 7 },
            T { id: "s2", from: "registrar@uni.example", subject: "Winter term registration opens Monday", snippet: "Your registration window opens Monday 8:00am.", labels: &["INBOX", "[2. FYI]"], hours_ago: 30 },
        ],
        _ => vec![
            T { id: "p1", from: "meadowoodpremierdentistry@gmail.com", subject: "Appointment reminder — please confirm", snippet: "This is a reminder of your upcoming appointment on Dec 1 at 1:30pm for teeth cleaning. Please confirm the appointment.", labels: &["UNREAD", "IMPORTANT", "INBOX", "[1. Respond]"], hours_ago: 4 },
            T { id: "p2", from: "mom@family.example", subject: "Sunday dinner?", snippet: "Are you coming Sunday? Let me know so I can plan food 🙂", labels: &["UNREAD", "INBOX"], hours_ago: 6 },
            T { id: "p3", from: "billing@hydro.example", subject: "Your bill is ready — due Dec 3", snippet: "Your Manitoba Hydro statement of $112.40 is due December 3.", labels: &["INBOX", "IMPORTANT"], hours_ago: 15 },
            T { id: "p4", from: "deals@shopnow.example", subject: "Black Friday: 40% off everything", snippet: "Doors open now — our biggest sale of the year.", labels: &["INBOX", "[Promotions]", "UNREAD"], hours_ago: 1 },
            T { id: "p5", from: "Jkolapo@sarariel.ca", subject: "Important update regarding your case worker", snippet: "Your case worker, Tiffany, will be away for approximately one month.", labels: &["INBOX", "IMPORTANT", "[2. FYI]"], hours_ago: 40 },
        ],
    }
}

fn to_value(email: &str, t: &T) -> Value {
    let at = (Utc::now() - Duration::hours(t.hours_ago)).to_rfc3339();
    json!({
        "thread_id": format!("{}-{}", email.split('@').next().unwrap_or("x"), t.id),
        "subject": t.subject, "snippet": t.snippet,
        "participants": [t.from, email], "labels": t.labels,
        "splits": if t.labels.contains(&"IMPORTANT") { vec!["Important"] } else { vec!["Other"] },
        "last_message_at": at, "message_count": 1,
        "messages": [{"message_id": t.id, "from": t.from, "to": [email], "subject": t.subject, "snippet": t.snippet, "sent_at": at}]
    })
}

pub fn threads(email: &str) -> Vec<Value> {
    data(email).iter().map(|t| to_value(email, t)).collect()
}

pub fn thread_full(email: &str, id: &str) -> Option<ThreadFull> {
    let prefix = format!("{}-", email.split('@').next().unwrap_or("x"));
    let short = id.strip_prefix(&prefix)?;
    let t = data(email).into_iter().find(|t| t.id == short)?;
    Some(ThreadFull {
        account: email.into(),
        id: id.into(),
        subject: t.subject.into(),
        messages: vec![Message {
            id: t.id.into(),
            from: t.from.into(),
            to: vec![email.into()],
            sent_at: Some(Utc::now() - Duration::hours(t.hours_ago)),
            body: format!("<p>Hi,</p><p>{}</p><p>Thanks!</p>", t.snippet),
            attachments: vec![],
        }],
    })
}

pub fn events(email: &str, today_start: DateTime<Utc>) -> Vec<Value> {
    // (title, day offset, start h, start m, minutes, status, video)
    type E = (&'static str, i64, i64, i64, i64, &'static str, bool);
    let list: Vec<E> = match email.split('@').nth(1).unwrap_or("") {
        "company.example" => vec![
            ("Product standup", -1, 9, 0, 15, "accepted", true),
            ("Product standup", 0, 9, 0, 15, "accepted", true),
            ("Renewal call — BigClient", 0, 11, 0, 60, "accepted", true),
            ("Sprint review", 0, 14, 0, 60, "needsAction", true),
            ("Product standup", 1, 9, 0, 15, "accepted", true),
            ("Roadmap workshop", 1, 13, 0, 120, "accepted", false),
            ("1:1 with Sam", 2, 10, 30, 30, "accepted", true),
            ("Product standup", 2, 9, 0, 15, "accepted", true),
            ("Board pre-read review", 3, 15, 0, 45, "tentative", true),
            ("Product standup", 3, 9, 0, 15, "accepted", true),
            ("Team lunch", 4, 12, 0, 60, "needsAction", false),
        ],
        "mystudio.example" => vec![
            ("Pottery studio — glaze firing", 0, 18, 0, 120, "accepted", false),
            ("Holiday market setup", 3, 8, 0, 180, "accepted", false),
            ("Supplier call — clay order", 1, 16, 0, 30, "accepted", true),
        ],
        "uni.example" => vec![
            ("Stats lecture", 0, 14, 30, 80, "accepted", false),
            ("Stats lecture", 2, 14, 30, 80, "accepted", false),
            ("Study group", 1, 19, 0, 90, "tentative", false),
            ("Assignment 3 due", 3, 23, 0, 59, "accepted", false),
        ],
        _ => vec![
            ("Gym", 0, 7, 0, 60, "accepted", false),
            ("Call with Mom", 0, 19, 30, 30, "accepted", false),
            ("Dentist — cleaning", 1, 13, 30, 60, "accepted", false),
            ("Gym", 2, 7, 0, 60, "accepted", false),
            ("Dinner with friends", 4, 18, 30, 150, "accepted", false),
            ("Gym", 5, 9, 0, 60, "accepted", false),
        ],
    };
    let mut out: Vec<Value> = list
        .into_iter()
        .enumerate()
        .map(|(i, (title, d, h, m, mins, st, video))| {
            let s = today_start + Duration::days(d) + Duration::hours(h) + Duration::minutes(m);
            json!({"event_id": format!("{email}-{i}"), "title": title, "start": s.to_rfc3339(),
                   "end": (s + Duration::minutes(mins)).to_rfc3339(), "my_status": st,
                   "video_link": if video { Some("https://meet.example/abc") } else { None }})
        })
        .collect();
    if !email.contains("company") && !email.contains("studio") && !email.contains("uni") {
        let d = (today_start + Duration::days(6)).date_naive();
        out.push(json!({"event_id": format!("{email}-allday"), "title": "Long weekend trip", "start": d.to_string(),
                        "end": (d + Duration::days(2)).to_string(), "my_status": "accepted"}));
    }
    out
}

pub fn digest_thread(cfg: &Config) -> Option<ThreadFull> {
    let to = cfg.accounts.first()?.email.clone();
    Some(ThreadFull {
        account: to.clone(),
        id: "digest".into(),
        subject: format!("{} – sample", cfg.digest_subject),
        messages: vec![Message {
            id: "digest".into(),
            from: to.clone(),
            to: vec![to],
            sent_at: Some(Utc::now() - Duration::hours(1)),
            body: "<h2>Top 3 priorities today</h2><ol><li><b>Board deck numbers</b> — CEO needs Q4 metrics by EOD (Work)</li>\
                   <li><b>Refund request #1042</b> — reply within 48h (Side Business)</li><li><b>Confirm dentist</b> — Dec 1, 1:30pm (Personal)</li></ol>\
                   <h2>Today's schedule</h2><ul><li>09:00 Product standup · Work</li><li>⚠️ 14:00 Sprint review · Work — clashes with Stats lecture · School</li></ul>"
                .into(),
            attachments: vec![],
        }],
    })
}
