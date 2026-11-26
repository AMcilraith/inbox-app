//! The desktop window: wires the Slint UI (ui/app.slint) to the Hub.
//!
//! Slint runs on the main thread; all network work runs on the tokio runtime and hands
//! results back with `upgrade_in_event_loop`.

use crate::{
    auth::Auth,
    hub::{Account, EventInput, Hub, Overview},
    model::{self, Event, Thread},
    text,
    time::Tz,
};
use anyhow::Result;
use chrono::{DateTime, Datelike, Duration, Months, NaiveDate, NaiveDateTime, Timelike, Utc};
use slint::{Color, ComponentHandle, Model, ModelRc, SharedString, VecModel, Weak};
use std::{
    cell::RefCell,
    collections::HashSet,
    future::Future,
    path::PathBuf,
    rc::Rc,
    sync::{Arc, Mutex},
};

slint::include_modules!();

struct Ctx {
    hub: Arc<Hub>,
    auth: Option<Arc<Auth>>,
    rt: tokio::runtime::Handle,
    port: u16,
    data: PathBuf,
    st: Mutex<St>,
}

struct St {
    accounts: Vec<Account>,
    account: String,
    category: String,
    query: String,
    search_gen: u64,
    threads: Vec<Thread>,
    cal_view: String,
    cal_anchor: NaiveDate,
    cal_hidden: HashSet<String>,
    cal_events: Vec<Event>,
    cal_start: NaiveDate,
    cal_days: i64,
    cal_gen: u64,
    /// (thread id, Superhuman draft id) made by Superhuman's writer, reused when sending.
    sh_draft: Option<(String, String)>,
}

type Pending = Rc<RefCell<Option<Box<dyn FnOnce()>>>>;

impl Ctx {
    fn spawn(&self, f: impl Future<Output = ()> + Send + 'static) {
        self.rt.spawn(f);
    }
    fn tz(&self) -> Tz {
        self.hub.cfg.tz().unwrap_or_else(|_| Tz::utc())
    }
    fn st(&self) -> std::sync::MutexGuard<'_, St> {
        self.st.lock().unwrap_or_else(|e| e.into_inner())
    }
}

// ---------------------------------------------------------------- helpers --

fn ss(s: impl AsRef<str>) -> SharedString {
    SharedString::from(s.as_ref())
}

fn model<T: Clone + 'static>(v: Vec<T>) -> ModelRc<T> {
    ModelRc::new(VecModel::from(v))
}

fn hex(c: &str) -> Color {
    let h = c.trim().trim_start_matches('#');
    let p = |i: usize| u8::from_str_radix(h.get(i..i + 2).unwrap_or("88"), 16).unwrap_or(0x88);
    if h.len() >= 6 {
        Color::from_rgb_u8(p(0), p(2), p(4))
    } else {
        Color::from_rgb_u8(0x88, 0x88, 0x99)
    }
}

fn acct(accts: &[Account], email: &str) -> (String, Color) {
    accts
        .iter()
        .find(|a| a.email.eq_ignore_ascii_case(email))
        .map(|a| (a.label.clone(), hex(&a.color)))
        .unwrap_or_else(|| (email.split('@').next().unwrap_or(email).to_string(), hex("#98a2b3")))
}

fn ago(d: DateTime<Utc>, tz: &Tz) -> String {
    let s = (Utc::now() - d).num_seconds();
    if s < 3600 {
        format!("{}m", (s / 60).max(1))
    } else if s < 86_400 {
        format!("{}h", s / 3600)
    } else if s < 604_800 {
        format!("{}d", s / 86_400)
    } else {
        tz.local(d).format("%b %-d").to_string()
    }
}

fn hm12(t: NaiveDateTime) -> String {
    t.format("%-I:%M %p").to_string()
}

fn toast(app: &MainWindow, msg: impl AsRef<str>) {
    let msg = msg.as_ref().to_string();
    app.set_toast(ss(&msg));
    let w = app.as_weak();
    slint::Timer::single_shot(std::time::Duration::from_millis(4500), move || {
        if let Some(a) = w.upgrade() {
            if a.get_toast() == msg.as_str() {
                a.set_toast(SharedString::new());
            }
        }
    });
}

fn later(w: &Weak<MainWindow>, f: impl FnOnce(MainWindow) + Send + 'static) {
    let _ = w.upgrade_in_event_loop(f);
}

fn thread_item(t: &Thread, accts: &[Account], tz: &Tz, badge: &str, kind: &str, reason: &str) -> ThreadItem {
    let (label, color) = acct(accts, &t.account);
    ThreadItem {
        account: ss(&t.account),
        id: ss(&t.id),
        color,
        account_label: ss(label),
        from: ss(if t.from_name.is_empty() { &t.from } else { &t.from_name }),
        subject: ss(&t.subject),
        snippet: ss(&t.snippet),
        when: ss(ago(t.date, tz)),
        unread: t.unread,
        badge: ss(badge),
        badge_kind: ss(kind),
        reason: ss(reason),
    }
}

fn event_item(e: &Event, accts: &[Account], tz: &Tz) -> EventItem {
    let (label, color) = acct(accts, &e.account);
    let s = tz.local(e.start);
    let en = tz.local(e.end);
    let when = if e.all_day {
        let last = (en - Duration::seconds(1)).date();
        if last > s.date() {
            format!("All day, until {}", last.format("%b %-d"))
        } else {
            "All day".into()
        }
    } else if en.date() != s.date() {
        format!("{} – {}", hm12(s), en.format("%b %-d, %-I:%M %p"))
    } else {
        format!("{} – {}", hm12(s), hm12(en))
    };
    EventItem {
        account: ss(&e.account),
        id: ss(&e.id),
        color,
        account_label: ss(label),
        title: ss(&e.title),
        when: ss(when),
        day_label: ss(s.format("%a, %b %-d").to_string()),
        date: ss(s.format("%Y-%m-%d").to_string()),
        start_hm: ss(s.format("%H:%M").to_string()),
        end_hm: ss(en.format("%H:%M").to_string()),
        location: ss(e.location.clone().unwrap_or_default()),
        link: ss(e.link.clone().unwrap_or_default()),
        organizer: ss(e.organizer.clone().unwrap_or_default()),
        status: ss(e.my_status.clone().unwrap_or_default()),
        clash: e.clash,
        needs_rsvp: e.needs_rsvp,
        all_day: e.all_day,
        recurring: e.recurring,
    }
}

/// Local [start, end) of an event, with all-day events ending at midnight.
fn local_span(e: &Event, tz: &Tz) -> (NaiveDateTime, NaiveDateTime) {
    let s = tz.local(e.start);
    let mut en = tz.local(e.end);
    if en <= s {
        en = s + Duration::minutes(if e.all_day { 24 * 60 } else { 30 });
    }
    (s, en)
}

fn on_day(e: &Event, d: NaiveDate, tz: &Tz) -> bool {
    let (s, en) = local_span(e, tz);
    let ds = d.and_hms_opt(0, 0, 0).unwrap();
    s < ds + Duration::days(1) && en > ds
}

// ------------------------------------------------------------------- run --

pub fn run(hub: Arc<Hub>, auth: Option<Arc<Auth>>, port: u16, cfg_error: Option<String>, data: PathBuf, rt: tokio::runtime::Handle) -> Result<()> {
    let app = MainWindow::new()?;
    let tz = hub.cfg.tz().unwrap_or_else(|_| Tz::utc());
    let today = tz.now().date();
    let ctx = Arc::new(Ctx {
        st: Mutex::new(St {
            accounts: Vec::new(),
            account: "all".into(),
            category: "All".into(),
            query: String::new(),
            search_gen: 0,
            threads: Vec::new(),
            cal_view: hub.cfg.calendar.default_view.clone(),
            cal_anchor: today,
            cal_hidden: HashSet::new(),
            cal_events: Vec::new(),
            cal_start: today,
            cal_days: 7,
            cal_gen: 0,
            sh_draft: None,
        }),
        hub,
        auth,
        rt,
        port,
        data,
    });
    let pending: Pending = Rc::new(RefCell::new(None));
    let chat: Rc<VecModel<ChatItem>> = Rc::new(VecModel::default());
    app.set_chat(ModelRc::from(chat.clone()));

    let signed_in = match &ctx.auth {
        Some(a) => ctx.rt.block_on(a.signed_in()),
        None => true,
    };
    app.set_signed_in(signed_in);
    app.set_demo(ctx.hub.demo());
    refresh_ai_state(&ctx, &app);
    app.set_banner_error(ss(cfg_error.unwrap_or_default()));
    let theme = std::fs::read_to_string(ctx.data.join("theme")).ok().map(|s| s.trim().to_string()).filter(|t| ["system", "light", "dark"].contains(&t.as_str()));
    app.set_theme_mode(ss(theme.unwrap_or_else(|| ctx.hub.cfg.theme.clone())));
    app.set_cal_view(ss(&ctx.hub.cfg.calendar.default_view));

    wire(&app, &ctx, &pending, &chat);

    if signed_in {
        load_view(&ctx, &app);
    }
    app.run()?;
    Ok(())
}

fn wire(app: &MainWindow, ctx: &Arc<Ctx>, pending: &Pending, chat: &Rc<VecModel<ChatItem>>) {
    macro_rules! on {
        ($setter:ident, |$app:ident, $ctx:ident $(, $arg:ident : $ty:ty)*| $body:block) => {{
            let w = app.as_weak();
            let $ctx = ctx.clone();
            app.$setter(move |$($arg: $ty),*| {
                let Some($app) = w.upgrade() else { return };
                $body
            });
        }};
    }

    on!(on_navigate, |app, ctx, _v: SharedString| { load_view(&ctx, &app); });
    on!(on_refresh, |app, ctx| {
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            c.hub.clear_cache().await;
            let c2 = c.clone();
            later(&w, move |app| load_view(&c2, &app));
        });
    });
    on!(on_set_theme, |_app, ctx, t: SharedString| {
        let _ = std::fs::create_dir_all(&ctx.data);
        let _ = std::fs::write(ctx.data.join("theme"), t.as_str());
    });

    // Switch between sample data and real accounts by restarting the app (replaces the old .bat files).
    on!(on_switch_demo, |app, _ctx, to_demo: bool| {
        let Ok(exe) = std::env::current_exe() else { return };
        let mut cmd = std::process::Command::new(exe);
        if to_demo {
            cmd.arg("--demo");
        }
        match cmd.spawn() {
            Ok(_) => {
                let _ = slint::quit_event_loop();
            }
            Err(e) => toast(&app, format!("Couldn't restart Inbox Hub: {e}")),
        }
    });
    on!(on_open_config, |app, _ctx| {
        match crate::config::active_config() {
            Some(p) => {
                if let Err(e) = open::that(&p) {
                    toast(&app, format!("Couldn't open {}: {e}", p.display()));
                }
            }
            None => toast(&app, "No settings file yet. It is created the first time you start Inbox Hub normally."),
        }
    });

    // sign in / out
    on!(on_sign_in, |app, ctx| {
        let Some(auth) = ctx.auth.clone() else { return };
        app.set_signing_in(true);
        app.set_signin_note(ss("Finish signing in on Superhuman's page in your browser. This window updates by itself."));
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            let r = crate::oauth::sign_in(&auth, c.port).await;
            c.hub.clear_cache().await;
            let c2 = c.clone();
            later(&w, move |app| {
                app.set_signing_in(false);
                match r {
                    Ok(()) => {
                        app.set_signin_note(SharedString::new());
                        app.set_signed_in(true);
                        app.set_view(ss("overview"));
                        load_view(&c2, &app);
                    }
                    Err(e) => app.set_signin_note(ss(format!("{e:#}"))),
                }
            });
        });
    });
    {
        let pending = pending.clone();
        on!(on_sign_out, |app, ctx| {
            let w = app.as_weak();
            let c = ctx.clone();
            ask_confirm(&app, &pending, "Sign out of Superhuman on this computer? You can sign in again any time.", "Sign out", move || {
                c.clone().spawn({
                    let c = c.clone();
                    async move {
                        if let Some(a) = &c.auth {
                            let _ = a.sign_out().await;
                        }
                        c.hub.clear_cache().await;
                        later(&w, |app| app.set_signed_in(false));
                    }
                });
            });
        });
    }

    // filters
    on!(on_select_account, |app, ctx, email: SharedString| {
        ctx.st().account = email.to_string();
        app.set_account_filter(email.clone());
        let v = app.get_view();
        if v == "calendar" {
            render_calendar(&ctx, &app);
        } else {
            if v != "inbox" {
                app.set_view(ss("inbox"));
            }
            load_inbox(&ctx, &app);
        }
    });
    on!(on_select_category, |app, ctx, c: SharedString| {
        ctx.st().category = c.to_string();
        app.set_category(c.clone());
        render_inbox(&ctx, &app);
    });
    on!(on_search, |app, ctx, q: SharedString| {
        let gen = {
            let mut st = ctx.st();
            st.query = q.to_string();
            st.search_gen += 1;
            st.search_gen
        };
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
            if c.st().search_gen == gen {
                let c2 = c.clone();
                later(&w, move |app| load_inbox(&c2, &app));
            }
        });
    });

    // threads
    on!(on_open_thread, |app, ctx, account: SharedString, id: SharedString| { open_thread(&ctx, &app, account.to_string(), id.to_string()); });
    on!(on_close_thread, |app, _ctx| { app.set_thread_open(false); });
    on!(on_draft_ai, |app, ctx| {
        let (account, id, guidance) = (app.get_thread_account().to_string(), app.get_thread_id().to_string(), app.get_guidance_text().to_string());
        app.set_drafting(true);
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            let r = c.hub.draft_reply(&account, &id, &guidance).await;
            let c2 = c.clone();
            later(&w, move |app| {
                app.set_drafting(false);
                match r {
                    Ok((t, draft_id)) => {
                        app.set_reply_text(ss(t.trim()));
                        c2.st().sh_draft = draft_id.map(|d| (id.clone(), d));
                    }
                    Err(e) => toast(&app, format!("{e:#}")),
                }
            });
        });
    });
    on!(on_save_draft, |app, ctx| { do_reply(&ctx, &app, false); });
    {
        let pending = pending.clone();
        on!(on_send_reply, |app, ctx| {
            if app.get_reply_text().trim().is_empty() {
                return toast(&app, "Write something first");
            }
            let w = app.as_weak();
            let c = ctx.clone();
            let msg = format!("Send this reply from {}?", app.get_thread_account());
            ask_confirm(&app, &pending, &msg, "Send", move || {
                if let Some(app) = w.upgrade() {
                    do_reply(&c, &app, true);
                }
            });
        });
    }

    // calendar
    on!(on_cal_set_view, |app, ctx, v: SharedString| {
        ctx.st().cal_view = v.to_string();
        app.set_cal_view(v.clone());
        load_calendar(&ctx, &app);
    });
    on!(on_cal_step, |app, ctx, dir: i32| {
        {
            let mut st = ctx.st();
            let a = st.cal_anchor;
            st.cal_anchor = match (dir, st.cal_view.as_str()) {
                (0, _) => ctx.tz().now().date(),
                (d, "day") => a + Duration::days(d as i64),
                (d, "month") => {
                    if d > 0 {
                        a.checked_add_months(Months::new(1)).unwrap_or(a)
                    } else {
                        a.checked_sub_months(Months::new(1)).unwrap_or(a)
                    }
                }
                (d, "agenda") => a + Duration::days(14 * d as i64),
                (d, _) => a + Duration::days(7 * d as i64),
            };
        }
        load_calendar(&ctx, &app);
    });
    on!(on_cal_toggle, |app, ctx, i: i32| {
        {
            let mut st = ctx.st();
            if let Some(a) = st.accounts.get(i as usize).map(|a| a.email.clone()) {
                if !st.cal_hidden.remove(&a) {
                    st.cal_hidden.insert(a);
                }
            }
        }
        render_calendar(&ctx, &app);
    });
    on!(on_new_event, |app, ctx| {
        let tz = ctx.tz();
        let now = tz.now();
        let (date, mins) = {
            let st = ctx.st();
            if app.get_view() == "calendar" && st.cal_anchor != now.date() {
                (st.cal_anchor, 9 * 60)
            } else {
                (now.date(), ((now.hour() * 60 + now.minute()) / 30 + 1) as i32 * 30)
            }
        };
        open_form(&ctx, &app, date, mins.min(23 * 60));
    });
    on!(on_new_event_at, |app, ctx, date: SharedString, mins: i32| {
        if let Ok(d) = NaiveDate::parse_from_str(&date, "%Y-%m-%d") {
            open_form(&ctx, &app, d, mins.clamp(0, 23 * 60 + 30));
        }
    });
    on!(on_edit_event, |app, ctx, e: EventItem| {
        let idx = ctx.st().accounts.iter().position(|a| a.email == e.account.as_str()).unwrap_or(0);
        app.set_form_event_id(e.id.clone());
        app.set_form_account(idx as i32);
        app.set_form_title(e.title.clone());
        app.set_form_date(e.date.clone());
        app.set_form_start(e.start_hm.clone());
        app.set_form_end(e.end_hm.clone());
        app.set_form_all_day(e.all_day);
        app.set_form_location(e.location.clone());
        app.set_form_description(SharedString::new());
        app.set_form_attendees(SharedString::new());
        app.set_form_video(false);
        app.set_form_error(SharedString::new());
        app.set_form_open(true);
    });
    on!(on_save_event, |app, ctx| {
        let accounts = ctx.st().accounts.clone();
        let Some(account) = accounts.get(app.get_form_account().max(0) as usize).map(|a| a.email.clone()) else {
            return app.set_form_error(ss("No calendar to save to yet — wait for your accounts to load."));
        };
        let date = app.get_form_date().trim().to_string();
        let all_day = app.get_form_all_day();
        let input = EventInput {
            account,
            event_id: Some(app.get_form_event_id().to_string()).filter(|s| !s.is_empty()),
            title: app.get_form_title().to_string(),
            start: if all_day { date.clone() } else { format!("{date}T{}", pad_hm(&app.get_form_start())) },
            end: if all_day { date.clone() } else { format!("{date}T{}", pad_hm(&app.get_form_end())) },
            all_day,
            location: app.get_form_location().to_string(),
            description: app.get_form_description().to_string(),
            attendees: app.get_form_attendees().split(|c: char| c == ',' || c == ';' || c.is_whitespace()).map(str::to_string).filter(|s| !s.is_empty()).collect(),
            conference: app.get_form_video(),
        };
        app.set_form_saving(true);
        app.set_form_error(SharedString::new());
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            let r = c.hub.save_event(&input).await;
            let c2 = c.clone();
            later(&w, move |app| {
                app.set_form_saving(false);
                match r {
                    Ok(_) => {
                        app.set_form_open(false);
                        toast(&app, "Event saved");
                        reload_calendarish(&c2, &app);
                    }
                    Err(e) => app.set_form_error(ss(format!("{e:#}"))),
                }
            });
        });
    });
    on!(on_rsvp, |app, ctx, account: SharedString, id: SharedString, resp: SharedString| {
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            let r = c.hub.rsvp(&account, &id, &resp).await;
            let c2 = c.clone();
            later(&w, move |app| match r {
                Ok(_) => {
                    app.set_event_open(false);
                    toast(&app, "Response sent");
                    reload_calendarish(&c2, &app);
                }
                Err(e) => toast(&app, format!("{e:#}")),
            });
        });
    });
    {
        let pending = pending.clone();
        on!(on_delete_event, |app, ctx, account: SharedString, id: SharedString| {
            let title = app.get_sel_event().title;
            let label = app.get_sel_event().account_label;
            let w = app.as_weak();
            let c = ctx.clone();
            ask_confirm(&app, &pending, &format!("Delete “{title}” from the {label} calendar?"), "Delete", move || {
                let (w, c2) = (w.clone(), c.clone());
                c.spawn(async move {
                    let r = c2.hub.delete_event(&account, &id).await;
                    let c3 = c2.clone();
                    later(&w, move |app| match r {
                        Ok(_) => {
                            app.set_event_open(false);
                            toast(&app, "Event deleted");
                            reload_calendarish(&c3, &app);
                        }
                        Err(e) => toast(&app, format!("{e:#}")),
                    });
                });
            });
        });
    }
    on!(on_open_link, |app, _ctx, url: SharedString| {
        if url.starts_with("https://") || url.starts_with("http://") {
            if let Err(e) = open::that(url.as_str()) {
                toast(&app, format!("Couldn't open the link: {e}"));
            }
        }
    });

    // digest
    on!(on_digest_preview, |app, ctx| {
        app.set_digest_busy(true);
        let ai = !ctx.hub.demo();
        app.set_digest_note(ss(if ai { "Superhuman AI is writing today's briefing…" } else { "Building today's digest…" }));
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            let r = c.hub.build_digest().await;
            later(&w, move |app| {
                app.set_digest_busy(false);
                match r {
                    Ok((subject, html)) => {
                        app.set_digest_title(ss(format!("{subject} (preview)")));
                        app.set_digest_note(ss(if ai { "Built from all your accounts, with a briefing from Superhuman AI." } else { "Sample digest." }));
                        app.set_digest_body(ss(text::html_to_text(&html)));
                    }
                    Err(e) => {
                        app.set_digest_note(SharedString::new());
                        toast(&app, format!("{e:#}"));
                    }
                }
            });
        });
    });
    {
        let pending = pending.clone();
        on!(on_digest_send, |app, ctx| {
            let w = app.as_weak();
            let c = ctx.clone();
            ask_confirm(&app, &pending, "Build and email today's digest now?", "Send digest", move || {
                if let Some(app) = w.upgrade() {
                    app.set_digest_busy(true);
                }
                let (w, c2) = (w.clone(), c.clone());
                c.spawn(async move {
                    let r = c2.hub.send_digest().await;
                    later(&w, move |app| {
                        app.set_digest_busy(false);
                        toast(&app, r.unwrap_or_else(|e| format!("{e:#}")));
                    });
                });
            });
        });
    }

    // accounts
    on!(on_link_account, |app, ctx| {
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            c.hub.clear_cache().await;
            let r = c.hub.link_account().await;
            let c2 = c.clone();
            later(&w, move |app| match r {
                Ok(v) => {
                    let url = v["continueUrl"].as_str().or(v["url"].as_str()).unwrap_or("").to_string();
                    if url.is_empty() {
                        app.set_link_note(ss(v.to_string()));
                    } else {
                        let _ = open::that(&url);
                        app.set_link_url(ss(&url));
                        app.set_link_note(ss("A Superhuman page opened in your browser. Sign in there as the other account and approve. It shows up here by itself (the link works for 10 minutes)."));
                        watch_for_new_account(&c2, &app);
                    }
                }
                Err(e) => toast(&app, format!("{e:#}")),
            });
        });
    });
    {
        let pending = pending.clone();
        on!(on_thread_action, |app, ctx, action: SharedString| {
            let action = action.to_string();
            let (account, id, last) = (app.get_thread_account().to_string(), app.get_thread_id().to_string(), app.get_thread_last_id().to_string());
            let run = {
                let w = app.as_weak();
                let c = ctx.clone();
                let action = action.clone();
                move || {
                    let c2 = c.clone();
                    c.spawn(async move {
                        let r = c2.hub.thread_action(&account, &id, &last, &action).await;
                        let c3 = c2.clone();
                        later(&w, move |app| match r {
                            Ok(msg) => {
                                toast(&app, msg);
                                match action.as_str() {
                                    "star" => app.set_thread_starred(true),
                                    "unstar" => app.set_thread_starred(false),
                                    "important" => app.set_thread_important(true),
                                    "other" => app.set_thread_important(false),
                                    "read" | "unread" => {}
                                    _ => app.set_thread_open(false), // done, reminders, trash, spam, unsubscribe
                                }
                                load_view(&c3, &app);
                            }
                            Err(e) => toast(&app, format!("{e:#}")),
                        });
                    });
                }
            };
            match action.as_str() {
                "trash" => ask_confirm(&app, &pending, "Move this conversation to the trash?", "Move to trash", run),
                "spam" => ask_confirm(&app, &pending, "Mark this conversation as spam and move it to the trash?", "Mark as spam", run),
                "unsubscribe" => ask_confirm(&app, &pending, "Unsubscribe from this mailing list and trash this email?", "Unsubscribe", run),
                _ => run(),
            }
        });
    }
    {
        let pending = pending.clone();
        on!(on_remove_account, |app, ctx, email: SharedString| {
            let email = email.to_string();
            let w = app.as_weak();
            let c = ctx.clone();
            ask_confirm(&app, &pending, &format!("Unlink {email} from Inbox Hub? You can link it again any time."), "Unlink", move || {
                let c2 = c.clone();
                c.spawn(async move {
                    let r = c2.hub.remove_account(&email).await;
                    let c3 = c2.clone();
                    later(&w, move |app| match r {
                        Ok(()) => { toast(&app, "Account unlinked"); load_view(&c3, &app); }
                        Err(e) => toast(&app, format!("{e:#}")),
                    });
                });
            });
        });
    }
    on!(on_make_primary, |app, ctx, email: SharedString| {
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            let r = c.hub.make_primary(&email).await;
            let c2 = c.clone();
            later(&w, move |app| match r {
                Ok(()) => { toast(&app, format!("{email} is now your primary account")); load_view(&c2, &app); }
                Err(e) => toast(&app, format!("{e:#}")),
            });
        });
    });
    on!(on_sync_style, |app, ctx, email: SharedString| {
        let w = app.as_weak();
        let c = ctx.clone();
        ctx.spawn(async move {
            let r = c.hub.sync_style(&email).await;
            later(&w, move |app| match r {
                Ok(_) => toast(&app, format!("Superhuman updated its writing style for {email}")),
                Err(e) => toast(&app, format!("{e:#}")),
            });
        });
    });

    // Ask Claude
    {
        let chat = chat.clone();
        on!(on_ask, |app, ctx, q: SharedString| {
            let q = q.trim().to_string();
            if q.is_empty() || app.get_chat_busy() {
                return;
            }
            chat.push(ChatItem { mine: true, text: ss(&q), tools: SharedString::new(), error: false });
            let asked = new_msg("user", &q);
            app.set_chat_input(SharedString::new());
            app.set_chat_busy(true);
            scroll_chat(&app);
            let w = app.as_weak();
            let c = ctx.clone();
            ctx.spawn(async move {
                let r = ask_ai(&c, q).await;
                // Save both sides of the exchange to the shared history (phone + desktop).
                if let Ok((answer, _)) = &r {
                    let local = crate::hub::AskHistory { cleared_at: 0, msgs: vec![asked, new_msg("ai", answer)] };
                    let _ = c.hub.sync_ask_history(local).await;
                }
                later(&w, move |app| {
                    app.set_chat_busy(false);
                    let item = match r {
                        Ok((t, tools)) => ChatItem { mine: false, text: ss(text::markdown_to_text(&t)), tools: ss(tools), error: false },
                        Err(e) => ChatItem { mine: false, text: ss(format!("{e:#}")), tools: SharedString::new(), error: true },
                    };
                    if let Some(m) = app.get_chat().as_any().downcast_ref::<VecModel<ChatItem>>() {
                        m.push(item);
                    }
                    scroll_chat(&app);
                });
            });
        });
    }
    {
        let chat = chat.clone();
        on!(on_new_chat, |_app, ctx| {
            chat.set_vec(Vec::new());
            let c = ctx.clone();
            ctx.spawn(async move {
                let clear = crate::hub::AskHistory { cleared_at: chrono::Utc::now().timestamp_millis(), msgs: vec![] };
                let _ = c.hub.sync_ask_history(clear).await;
            });
        });
    }
    {
        let chat = chat.clone();
        on!(on_chat_opened, |app, ctx| {
            // Pull in anything asked on the phone since last time.
            let w = app.as_weak();
            let c = ctx.clone();
            let chat_len = chat.row_count();
            ctx.spawn(async move {
                let r = c.hub.load_ask_history().await;
                later(&w, move |app| {
                    if let Ok(h) = r {
                        if !h.msgs.is_empty() || chat_len == 0 {
                            let items: Vec<ChatItem> = h
                                .msgs
                                .iter()
                                .map(|m| ChatItem {
                                    mine: m.role == "user",
                                    text: ss(if m.role == "user" { m.text.clone() } else { text::markdown_to_text(&m.text) }),
                                    tools: ss(if m.role == "user" || m.device.is_empty() { String::new() } else { format!("from {}", m.device) }),
                                    error: false,
                                })
                                .collect();
                            if let Some(model) = app.get_chat().as_any().downcast_ref::<VecModel<ChatItem>>() {
                                model.set_vec(items);
                            }
                            scroll_chat(&app);
                        }
                    }
                });
            });
        });
    }

    {
        let pending = pending.clone();
        app.on_confirm_yes(move || {
            if let Some(f) = pending.borrow_mut().take() {
                f();
            }
        });
    }
}

fn ask_confirm(app: &MainWindow, pending: &Pending, text: &str, ok: &str, f: impl FnOnce() + 'static) {
    *pending.borrow_mut() = Some(Box::new(f));
    app.set_confirm_text(ss(text));
    app.set_confirm_ok(ss(ok));
    app.set_confirm_open(true);
}

fn scroll_chat(app: &MainWindow) {
    let w = app.as_weak();
    slint::Timer::single_shot(std::time::Duration::from_millis(60), move || {
        if let Some(a) = w.upgrade() {
            a.invoke_scroll_chat_bottom();
        }
    });
}

fn pad_hm(s: &str) -> String {
    match s.trim().split_once(':') {
        Some((h, m)) => format!("{:0>2}:{:0>2}", h.trim(), m.trim()),
        None => s.trim().to_string(),
    }
}

async fn ask_ai(c: &Ctx, q: String) -> Result<(String, String)> {
    if c.hub.demo() {
        return Err(anyhow::anyhow!("Ask isn't available with sample data. Switch to your real accounts to ask Superhuman AI."));
    }
    Ok((c.hub.ask_superhuman(&q).await?, "Superhuman AI".into()))
}

fn new_msg(role: &str, text: &str) -> crate::hub::ChatMsg {
    crate::hub::ChatMsg {
        id: format!("{:016x}", rand::random::<u64>()),
        role: role.into(),
        text: text.into(),
        at: chrono::Utc::now().timestamp_millis(),
        device: "desktop".into(),
    }
}

// ----------------------------------------------------------------- views --

fn load_view(ctx: &Arc<Ctx>, app: &MainWindow) {
    match app.get_view().as_str() {
        "inbox" => {
            load_inbox(ctx, app);
            if ctx.st().accounts.is_empty() {
                load_overview(ctx, app);
            }
        }
        "calendar" => load_calendar(ctx, app),
        "digest" => load_digest(ctx, app),
        "accounts" => {
            let n = ctx.st().accounts.len();
            app.set_subtitle(ss(format!("{n} linked through Superhuman")));
            load_overview(ctx, app);
        }
        _ => load_overview(ctx, app),
    }
}

fn reload_calendarish(ctx: &Arc<Ctx>, app: &MainWindow) {
    if app.get_view() == "calendar" {
        load_calendar(ctx, app);
    } else {
        load_overview(ctx, app);
    }
}

fn load_overview(ctx: &Arc<Ctx>, app: &MainWindow) {
    if app.get_view() == "overview" {
        app.set_subtitle(ss(ctx.tz().now().format("%A, %B %-d").to_string()));
    }
    app.set_loading(true);
    let w = app.as_weak();
    let c = ctx.clone();
    ctx.spawn(async move {
        let r = c.hub.overview().await;
        let c2 = c.clone();
        later(&w, move |app| {
            app.set_loading(false);
            match r {
                Ok(ov) => apply_overview(&c2, &app, ov),
                Err(e) => app.set_overview_error(ss(format!("{e:#}"))),
            }
        });
    });
}

fn apply_overview(ctx: &Arc<Ctx>, app: &MainWindow, ov: Overview) {
    let tz = ctx.tz();
    let accts = ov.accounts.clone();
    ctx.st().accounts = accts.clone();
    app.set_overview_error(SharedString::new());

    app.set_accounts(model(
        accts
            .iter()
            .map(|a| AccountItem {
                email: ss(&a.email),
                label: ss(&a.label),
                color: hex(&a.color),
                primary: a.primary,
                unread: a.unread as i32,
                style: ss(a.style.clone().unwrap_or_default()),
                error: ss(a.error.clone().unwrap_or_default()),
            })
            .collect(),
    ));
    app.set_total_unread(accts.iter().map(|a| a.unread as i32).sum());
    app.set_account_labels(model(accts.iter().map(|a| ss(format!("{} — {}", a.label, a.email))).collect()));
    if app.get_view() == "accounts" {
        app.set_subtitle(ss(format!("{} linked through Superhuman", accts.len())));
    }

    let today = tz.now().date();
    let todays: Vec<&Event> = ov.events.iter().filter(|e| on_day(e, today, &tz)).collect();
    let now = Utc::now();
    let next = todays.iter().find(|e| !e.all_day && e.end > now).map(|e| e.title.clone());
    let unread: u64 = accts.iter().map(|a| a.unread).sum();
    let tiles = vec![
        Tile { k: ss("Unread"), v: ss(thousands(unread)), h: ss(format!("{} account{}", accts.len(), if accts.len() == 1 { "" } else { "s" })) },
        Tile {
            k: ss("Needs reply"),
            v: ss(ov.todo.iter().filter(|t| t.thread.needs_reply).count().to_string()),
            h: ss(format!("{} high priority", ov.todo.iter().filter(|t| t.priority == "High").count())),
        },
        Tile {
            k: ss("Meetings today"),
            v: ss(todays.iter().filter(|e| !e.all_day).count().to_string()),
            h: ss(if todays.is_empty() { "clear day".to_string() } else { format!("next: {}", next.unwrap_or_else(|| "done for today".into())) }),
        },
        Tile {
            k: ss("Conflicts"),
            v: ss(todays.iter().filter(|e| e.clash).count().to_string()),
            h: ss(format!("{} RSVP pending", ov.events.iter().filter(|e| e.needs_rsvp).count())),
        },
    ];
    app.set_tiles(model(tiles));
    app.set_todo(model(ov.todo.iter().map(|t| thread_item(&t.thread, &accts, &tz, t.priority, t.priority, &t.reason)).collect()));
    app.set_today_events(model(todays.iter().map(|e| event_item(e, &accts, &tz)).collect()));
    app.set_waiting(model(ov.waiting.iter().map(|t| thread_item(t, &accts, &tz, "", "", "")).collect()));
    app.set_cal_accounts(app.get_accounts());

    let errs: Vec<String> = accts.iter().filter_map(|a| a.error.as_ref().map(|e| format!("{}: {e}", a.label))).collect();
    if !errs.is_empty() {
        toast(app, errs.join(" · "));
    }
    // Colours may have arrived after the inbox did.
    if !ctx.st().threads.is_empty() {
        render_inbox(ctx, app);
    }
}

fn load_inbox(ctx: &Arc<Ctx>, app: &MainWindow) {
    let (account, q, gen) = {
        let st = ctx.st();
        (st.account.clone(), st.query.clone(), st.search_gen)
    };
    let label = if account == "all" { "All accounts".to_string() } else { acct(&ctx.st().accounts, &account).0 };
    app.set_subtitle(ss(label));
    app.set_loading(true);
    let w = app.as_weak();
    let c = ctx.clone();
    ctx.spawn(async move {
        let r = c.hub.inbox(Some(&account), None, Some(&q)).await;
        let c2 = c.clone();
        later(&w, move |app| {
            app.set_loading(false);
            if c2.st().search_gen != gen {
                return;
            }
            match r {
                Ok(t) => {
                    app.set_inbox_error(SharedString::new());
                    c2.st().threads = t;
                    render_inbox(&c2, &app);
                }
                Err(e) => app.set_inbox_error(ss(format!("{e:#}"))),
            }
        });
    });
}

fn render_inbox(ctx: &Arc<Ctx>, app: &MainWindow) {
    let tz = ctx.tz();
    let mut st = ctx.st();
    let mut counts: Vec<(String, i32)> = Vec::new();
    for t in &st.threads {
        match counts.iter_mut().find(|(c, _)| *c == t.category) {
            Some((_, n)) => *n += 1,
            None => counts.push((t.category.clone(), 1)),
        }
    }
    counts.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    if st.category != "All" && !counts.iter().any(|(c, _)| *c == st.category) {
        st.category = "All".into();
        app.set_category(ss("All"));
    }
    let mut chips = vec![Chip { name: ss("All"), count: st.threads.len() as i32 }];
    chips.extend(counts.iter().map(|(c, n)| Chip { name: ss(c), count: *n }));
    app.set_chips(model(chips));
    let shown: Vec<ThreadItem> = st
        .threads
        .iter()
        .filter(|t| st.category == "All" || t.category == st.category)
        .map(|t| {
            let tag = if t.category == model::PRIMARY { "" } else { t.category.as_str() };
            thread_item(t, &st.accounts, &tz, tag, "tag", "")
        })
        .collect();
    app.set_threads(model(shown));
}

fn open_thread(ctx: &Arc<Ctx>, app: &MainWindow, account: String, id: String) {
    if ctx.st().threads.is_empty() {
        load_inbox(ctx, app);
    }
    app.set_thread_loading(true);
    let w = app.as_weak();
    let c = ctx.clone();
    ctx.spawn(async move {
        let r = c.hub.thread(&account, &id).await;
        let c2 = c.clone();
        later(&w, move |app| {
            app.set_thread_loading(false);
            match r {
                Ok(t) => {
                    let tz = c2.tz();
                    let (label, color) = acct(&c2.st().accounts, &account);
                    let msgs: Vec<MessageItem> = t
                        .messages
                        .iter()
                        .map(|m| MessageItem {
                            from: ss(sender_line(&m.from)),
                            to: ss(m.to.join(", ")),
                            when: ss(m.sent_at.map(|d| tz.local(d).format("%b %-d, %Y, %-I:%M %p").to_string()).unwrap_or_default()),
                            body: ss(text::html_to_text(&m.body)),
                            attachments: ss(m.attachments.join(", ")),
                        })
                        .collect();
                    let changed = app.get_thread_id() != id.as_str() || app.get_thread_account() != account.as_str();
                    app.set_messages(model(msgs));
                    app.set_thread_subject(ss(if t.subject.is_empty() { "(no subject)" } else { &t.subject }));
                    app.set_thread_account(ss(&account));
                    app.set_thread_id(ss(&id));
                    app.set_thread_last_id(ss(t.messages.last().map(|m| m.id.clone()).unwrap_or_else(|| id.clone())));
                    let summary = c2.st().threads.iter().find(|x| x.id == id && x.account == account).cloned();
                    app.set_thread_starred(summary.as_ref().is_some_and(|x| x.starred));
                    app.set_thread_important(summary.as_ref().map_or(true, |x| x.important));
                    app.set_thread_account_label(ss(label));
                    app.set_thread_color(color);
                    if changed {
                        app.set_reply_text(SharedString::new());
                        app.set_guidance_text(SharedString::new());
                    }
                    app.set_reply_note(ss(if c2.hub.demo() { "Sending is disabled in demo mode." } else { "" }));
                    app.set_thread_open(true);
                }
                Err(e) => toast(&app, format!("{e:#}")),
            }
        });
    });
}

fn do_reply(ctx: &Arc<Ctx>, app: &MainWindow, send: bool) {
    let text = app.get_reply_text().to_string();
    if text.trim().is_empty() {
        return toast(app, "Write something first");
    }
    let (account, id) = (app.get_thread_account().to_string(), app.get_thread_id().to_string());
    let draft_id = ctx.st().sh_draft.clone().filter(|(t, _)| *t == id).map(|(_, d)| d);
    app.set_replying(true);
    let w = app.as_weak();
    let c = ctx.clone();
    ctx.spawn(async move {
        let r = c.hub.send_reply(&account, &id, &text, send, draft_id.as_deref()).await;
        if send && r.is_ok() {
            c.st().sh_draft = None;
        }
        later(&w, move |app| {
            app.set_replying(false);
            match r {
                Ok(_) => {
                    app.set_reply_note(ss(if send { "Sent. Superhuman holds it for about a minute, so you can still undo it in Superhuman." } else { "Saved to your Superhuman drafts." }));
                    if send {
                        app.set_reply_text(SharedString::new());
                    }
                }
                Err(e) => toast(&app, format!("{e:#}")),
            }
        });
    });
}

// -------------------------------------------------------------- calendar --

fn week_start(d: NaiveDate, monday: bool) -> NaiveDate {
    let back = if monday { d.weekday().num_days_from_monday() } else { d.weekday().num_days_from_sunday() };
    d - Duration::days(back as i64)
}

fn cal_range(view: &str, anchor: NaiveDate, monday: bool) -> (NaiveDate, i64) {
    match view {
        "day" => (anchor, 1),
        "month" => (week_start(anchor.with_day(1).unwrap_or(anchor), monday), 42),
        "agenda" => (anchor, 14),
        _ => (week_start(anchor, monday), 7),
    }
}

fn load_calendar(ctx: &Arc<Ctx>, app: &MainWindow) {
    let monday = ctx.hub.cfg.calendar.week_starts_on == "monday";
    let (start, days, gen) = {
        let mut st = ctx.st();
        let (s, d) = cal_range(&st.cal_view, st.cal_anchor, monday);
        st.cal_start = s;
        st.cal_days = d;
        st.cal_gen += 1;
        (s, d, st.cal_gen)
    };
    app.set_subtitle(ss(format!("All calendars · {}", ctx.hub.cfg.timezone)));
    render_calendar(ctx, app);
    app.set_loading(true);
    let w = app.as_weak();
    let c = ctx.clone();
    ctx.spawn(async move {
        let emails = c.hub.account_emails().await;
        let (events, errors) = match emails {
            Ok(emails) => {
                let res = futures::future::join_all(emails.iter().map(|(e, _)| c.hub.events_range(e, start, days))).await;
                let mut all = Vec::new();
                let mut errs = Vec::new();
                for ((e, _), r) in emails.iter().zip(res) {
                    match r {
                        Ok(ev) => all.extend(ev),
                        Err(err) => errs.push(format!("{e}: {err:#}")),
                    }
                }
                model::mark_clashes(&mut all);
                (all, errs)
            }
            Err(e) => (Vec::new(), vec![format!("{e:#}")]),
        };
        let c2 = c.clone();
        later(&w, move |app| {
            app.set_loading(false);
            {
                let mut st = c2.st();
                if st.cal_gen != gen {
                    return;
                }
                st.cal_events = events;
            }
            app.set_cal_error(ss(errors.join(" · ")));
            render_calendar(&c2, &app);
        });
    });
}

fn render_calendar(ctx: &Arc<Ctx>, app: &MainWindow) {
    let tz = ctx.tz();
    let cal = &ctx.hub.cfg.calendar;
    let monday = cal.week_starts_on == "monday";
    let st = ctx.st();
    let (start, days) = (st.cal_start, st.cal_days);
    let today = tz.now().date();
    let accts = &st.accounts;
    let events: Vec<&Event> = st
        .cal_events
        .iter()
        .filter(|e| !st.cal_hidden.contains(&e.account) && (st.account == "all" || e.account == st.account))
        .collect();

    app.set_cal_accounts(model(
        accts
            .iter()
            .map(|a| AccountItem {
                email: ss(&a.email),
                label: ss(&a.label),
                color: hex(&a.color),
                primary: a.primary,
                unread: 0,
                style: SharedString::new(),
                error: SharedString::new(),
            })
            .collect(),
    ));
    app.set_cal_shown(model(accts.iter().map(|a| !st.cal_hidden.contains(&a.email)).collect()));

    let last = start + Duration::days(days - 1);
    let title = match st.cal_view.as_str() {
        "day" => start.format("%A, %B %-d, %Y").to_string(),
        "month" => st.cal_anchor.format("%B %Y").to_string(),
        _ if start.month() == last.month() => format!("{} – {}", start.format("%b %-d"), last.format("%-d, %Y")),
        _ => format!("{} – {}", start.format("%b %-d"), last.format("%b %-d, %Y")),
    };
    app.set_cal_title(ss(title));

    match st.cal_view.as_str() {
        "month" => {
            let names = if monday { ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] } else { ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] };
            app.set_cal_weekdays(model(names.iter().map(ss).collect()));
            let cells = (0..42)
                .map(|i| {
                    let d = start + Duration::days(i);
                    let mut evs: Vec<&&Event> = events.iter().filter(|e| on_day(e, d, &tz)).collect();
                    evs.sort_by_key(|e| (!e.all_day, e.start));
                    let more = evs.len().saturating_sub(3) as i32;
                    MonthCell {
                        date: ss(d.format("%Y-%m-%d").to_string()),
                        day: ss(d.day().to_string()),
                        row: (i / 7) as i32,
                        col: (i % 7) as i32,
                        in_month: d.month() == st.cal_anchor.month(),
                        is_today: d == today,
                        events: model(evs.iter().take(3).map(|e| event_item(e, accts, &tz)).collect()),
                        more,
                    }
                })
                .collect();
            app.set_cal_month(model(cells));
        }
        "agenda" => {
            let mut evs: Vec<&&Event> = events.iter().collect();
            evs.sort_by_key(|e| e.start);
            let mut rows = Vec::new();
            let mut last_day = None;
            for e in evs {
                let d = tz.local(e.start).date().max(start);
                let header = if last_day != Some(d) {
                    last_day = Some(d);
                    format!("{}{}", d.format("%A, %B %-d"), if d == today { " · Today" } else { "" })
                } else {
                    String::new()
                };
                rows.push(AgendaRow { header: ss(header), ev: event_item(e, accts, &tz) });
            }
            app.set_cal_agenda(model(rows));
        }
        _ => {
            let (sh, eh) = (cal.day_start_hour as i64, cal.day_end_hour as i64);
            let span = ((eh - sh) * 60) as f32;
            let mut cols = Vec::new();
            let mut grid = Vec::new();
            let mut allday_rows = 0;
            for i in 0..days {
                let d = start + Duration::days(i);
                let day0 = d.and_hms_opt(0, 0, 0).unwrap();
                let vis0 = day0 + Duration::hours(sh);
                let alld: Vec<EventItem> = events.iter().filter(|e| e.all_day && on_day(e, d, &tz)).map(|e| event_item(e, accts, &tz)).collect();
                allday_rows = allday_rows.max(alld.len() as i32);
                cols.push(DayCol {
                    date: ss(d.format("%Y-%m-%d").to_string()),
                    label: ss(d.format("%a %-d").to_string()),
                    is_today: d == today,
                    all_day: model(alld),
                });
                // timed events in this column, clipped to the visible hours
                let mut items: Vec<(f32, f32, &Event)> = events
                    .iter()
                    .filter(|e| !e.all_day && on_day(e, d, &tz))
                    .map(|e| {
                        let (s, en) = local_span(e, &tz);
                        let top = (s.max(day0) - vis0).num_minutes() as f32;
                        let bottom = (en.min(day0 + Duration::days(1)) - vis0).num_minutes() as f32;
                        let (top, bottom) = if bottom <= 0.0 {
                            (0.0, 30.0)
                        } else if top >= span {
                            (span - 30.0, span)
                        } else {
                            (top.max(0.0), bottom.min(span).max(top.max(0.0) + 15.0))
                        };
                        (top, bottom, *e)
                    })
                    .collect();
                items.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap().then(b.1.partial_cmp(&a.1).unwrap()));
                // overlap clusters → lanes
                let mut cluster: Vec<(usize, f32, f32, &Event)> = Vec::new();
                let mut lanes_end: Vec<f32> = Vec::new();
                let mut cluster_end = f32::MIN;
                let flush = |cluster: &mut Vec<(usize, f32, f32, &Event)>, lanes: usize, grid: &mut Vec<GridEvent>| {
                    for (lane, top, bottom, e) in cluster.drain(..) {
                        grid.push(GridEvent { ev: event_item(e, accts, &tz), col: i as i32, top, minutes: bottom - top, lane: lane as i32, lanes: lanes.max(1) as i32 });
                    }
                };
                for (top, bottom, e) in items {
                    if top >= cluster_end && !cluster.is_empty() {
                        let n = lanes_end.len();
                        flush(&mut cluster, n, &mut grid);
                        lanes_end.clear();
                    }
                    let lane = match lanes_end.iter().position(|end| *end <= top) {
                        Some(l) => {
                            lanes_end[l] = bottom;
                            l
                        }
                        None => {
                            lanes_end.push(bottom);
                            lanes_end.len() - 1
                        }
                    };
                    cluster_end = if cluster.is_empty() { bottom } else { cluster_end.max(bottom) };
                    cluster.push((lane, top, bottom, e));
                }
                let n = lanes_end.len();
                flush(&mut cluster, n, &mut grid);
            }
            app.set_cal_cols(model(cols));
            app.set_cal_events(model(grid));
            app.set_cal_allday_rows(allday_rows);
            app.set_cal_start_hour(sh as i32);
            app.set_cal_end_hour(eh as i32);
            let work = cal.working_hours.as_deref().and_then(|w| w.split_once('-')).and_then(|(a, b)| {
                let (h1, m1) = crate::config::parse_hhmm(a).ok()?;
                let (h2, m2) = crate::config::parse_hhmm(b).ok()?;
                Some(((h1 * 60 + m1) as i32, (h2 * 60 + m2) as i32))
            });
            app.set_cal_work_start(work.map(|w| w.0).unwrap_or(-1));
            app.set_cal_work_end(work.map(|w| w.1).unwrap_or(-1));
            let now = tz.now();
            let col = (now.date() - start).num_days();
            app.set_cal_now_col(if (0..days).contains(&col) { col as i32 } else { -1 });
            app.set_cal_now_min(((now.hour() as i64 - sh) * 60 + now.minute() as i64) as f32);
        }
    }
}

fn open_form(ctx: &Arc<Ctx>, app: &MainWindow, date: NaiveDate, mins: i32) {
    let cal = &ctx.hub.cfg.calendar;
    let st = ctx.st();
    let idx = st.accounts.iter().position(|a| a.email == st.account).unwrap_or(0);
    let end = (mins + cal.default_duration_minutes as i32).min(24 * 60 - 1);
    app.set_form_event_id(SharedString::new());
    app.set_form_account(idx as i32);
    app.set_form_title(SharedString::new());
    app.set_form_date(ss(date.format("%Y-%m-%d").to_string()));
    app.set_form_start(ss(format!("{:02}:{:02}", mins / 60, mins % 60)));
    app.set_form_end(ss(format!("{:02}:{:02}", end / 60, end % 60)));
    app.set_form_all_day(false);
    app.set_form_location(SharedString::new());
    app.set_form_description(SharedString::new());
    app.set_form_attendees(SharedString::new());
    app.set_form_video(cal.add_video_link);
    app.set_form_error(SharedString::new());
    app.set_form_open(true);
}

// ---------------------------------------------------------------- digest --

fn load_digest(ctx: &Arc<Ctx>, app: &MainWindow) {
    let cfg = &ctx.hub.cfg;
    app.set_subtitle(ss(match &cfg.digest.send_time {
        Some(t) => format!("Sent automatically at {t} while Inbox Hub is open"),
        None => "Set digest.send_time in inbox-hub.toml to send it automatically".into(),
    }));
    app.set_digest_note(ss("Loading the latest digest from your inbox…"));
    let w = app.as_weak();
    let c = ctx.clone();
    ctx.spawn(async move {
        let r = c.hub.latest_digest().await;
        let c2 = c.clone();
        later(&w, move |app| match r {
            Ok(None) => {
                app.set_digest_title(ss("Latest digest"));
                app.set_digest_note(ss(format!("No “{}” email found yet. Preview today's or send one now.", c2.hub.cfg.digest_subject)));
                app.set_digest_body(SharedString::new());
            }
            Ok(Some(t)) => {
                let tz = c2.tz();
                let label = acct(&c2.st().accounts, &t.account).0;
                if let Some(m) = t.messages.last() {
                    let when = m.sent_at.map(|d| tz.local(d).format("%b %-d, %Y, %-I:%M %p").to_string()).unwrap_or_default();
                    app.set_digest_note(ss(format!("Received {when} · {label}")));
                    app.set_digest_body(ss(text::html_to_text(&m.body)));
                }
                app.set_digest_title(ss(&t.subject));
            }
            Err(e) => app.set_digest_note(ss(format!("{e:#}"))),
        });
    });
}

/// 16852 -> "16,852"
fn thousands(n: u64) -> String {
    let d = n.to_string();
    let mut out = String::new();
    for (i, c) in d.chars().enumerate() {
        if i > 0 && (d.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    out
}

#[cfg(test)]
mod tests {
    #[test]
    fn thousands() {
        assert_eq!(super::thousands(16852), "16,852");
        assert_eq!(super::thousands(999), "999");
        assert_eq!(super::thousands(1_000_000), "1,000,000");
    }
}

/// "Meadowood Premier Dentistry · meadowoodpremierdentistry@gmail.com"
fn sender_line(addr: &str) -> String {
    let name = crate::model::display_name(addr);
    let email = addr.rsplit('<').next().unwrap_or(addr).trim_end_matches('>').trim();
    if name.eq_ignore_ascii_case(email) { name } else { format!("{name}  ·  {email}") }
}

/// Ask and Draft use Superhuman AI (not available with sample data).
fn refresh_ai_state(ctx: &Arc<Ctx>, app: &MainWindow) {
    app.set_ai_enabled(!ctx.hub.demo());
    app.set_model_name(ss(if ctx.hub.demo() { "" } else { "Synced with your phone" }));
}

/// After "Link another account": check every 5 seconds (for up to 10 minutes) and refresh as soon
/// as Superhuman reports the new account.
fn watch_for_new_account(ctx: &Arc<Ctx>, app: &MainWindow) {
    let w = app.as_weak();
    let c = ctx.clone();
    ctx.spawn(async move {
        let before = c.hub.account_count_fresh().await.unwrap_or(0);
        for _ in 0..120 {
            tokio::time::sleep(std::time::Duration::from_secs(5)).await;
            if let Ok(n) = c.hub.account_count_fresh().await {
                if n > before {
                    let c2 = c.clone();
                    later(&w, move |app| {
                        app.set_link_note(SharedString::new());
                        app.set_link_url(SharedString::new());
                        toast(&app, "New account linked — loading its mail");
                        load_view(&c2, &app);
                    });
                    return;
                }
            }
        }
    });
}
