use anyhow::{bail, Context, Result};
use serde::Deserialize;
use std::path::{Path, PathBuf};

pub const SUPERHUMAN_MCP_URL: &str = "https://mcp.mail.superhuman.com/mcp";

#[derive(Debug, Clone, Deserialize)]
pub struct CategoryRule {
    pub name: String,
    /// Match if the sender contains any of these (case-insensitive): addresses, domains, names.
    #[serde(default)]
    pub from: Vec<String>,
    /// Match if the subject contains any of these (case-insensitive).
    #[serde(default)]
    pub subject: Vec<String>,
}

/// Per-account personalisation. Accounts come from Superhuman; this only adds your preferences.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct AccountPrefs {
    pub email: String,
    #[serde(default)]
    pub label: Option<String>,
    #[serde(default)]
    pub color: Option<String>,
    /// Senders (addresses, domains or names) that are always top priority.
    #[serde(default)]
    pub vip: Vec<String>,
    /// Words in a subject/snippet that make an email urgent for this account.
    #[serde(default)]
    pub urgent_keywords: Vec<String>,
    /// How replies from this account should sound. Used by Claude when drafting and
    /// pushed to Superhuman's own writer with "Sync style".
    #[serde(default)]
    pub style: Option<String>,
    /// Your own categories, checked in order before the built-in ones.
    #[serde(default)]
    pub categories: Vec<CategoryRule>,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct DigestCfg {
    /// Where the digest is emailed. Defaults to the primary Superhuman account.
    #[serde(default)]
    pub to: Option<String>,
    /// Account that sends it. Defaults to the primary Superhuman account.
    #[serde(default)]
    pub from_account: Option<String>,
    /// "HH:MM" local time. While the app is running it sends the digest once a day at this time.
    #[serde(default)]
    pub send_time: Option<String>,
}

fn default_model() -> String {
    "claude-opus-5-5".into()
}
fn default_effort() -> String {
    "medium".into()
}

#[derive(Debug, Clone, Deserialize)]
pub struct ClaudeCfg {
    #[serde(default = "default_model")]
    pub model: String,
    /// low | medium | high | xhigh | max
    #[serde(default = "default_effort")]
    pub effort: String,
    /// Optional; the ANTHROPIC_API_KEY environment variable takes precedence.
    #[serde(default)]
    pub api_key: Option<String>,
}

impl Default for ClaudeCfg {
    fn default() -> Self {
        Self { model: default_model(), effort: default_effort(), api_key: None }
    }
}

fn default_theme() -> String {
    "system".into()
}
fn default_view() -> String {
    "week".into()
}
fn default_week_start() -> String {
    "sunday".into()
}
fn default_day_start() -> u32 {
    7
}
fn default_day_end() -> u32 {
    21
}
fn default_duration() -> u32 {
    30
}

#[derive(Debug, Clone, serde::Serialize, Deserialize)]
pub struct CalendarCfg {
    /// day | week | month | agenda
    #[serde(default = "default_view")]
    pub default_view: String,
    /// sunday | monday
    #[serde(default = "default_week_start")]
    pub week_starts_on: String,
    /// First and last hour shown in day/week view (events outside still appear).
    #[serde(default = "default_day_start")]
    pub day_start_hour: u32,
    #[serde(default = "default_day_end")]
    pub day_end_hour: u32,
    /// Length of new events.
    #[serde(default = "default_duration")]
    pub default_duration_minutes: u32,
    /// Shade outside these hours, "HH:MM-HH:MM".
    #[serde(default)]
    pub working_hours: Option<String>,
    /// Add a video meeting link to new events by default.
    #[serde(default)]
    pub add_video_link: bool,
}

impl Default for CalendarCfg {
    fn default() -> Self {
        toml::from_str("").expect("empty calendar parses")
    }
}

fn default_port() -> u16 {
    7878
}
fn default_digest_subject() -> String {
    "Daily Digest".into()
}
fn default_tz() -> String {
    "America/Winnipeg".into()
}
fn default_mcp_url() -> String {
    SUPERHUMAN_MCP_URL.into()
}

#[derive(Debug, Clone, Deserialize)]
pub struct Config {
    #[serde(default = "default_tz")]
    pub timezone: String,
    #[serde(default = "default_port")]
    pub port: u16,
    /// system | light | dark
    #[serde(default = "default_theme")]
    pub theme: String,
    #[serde(default)]
    pub calendar: CalendarCfg,
    #[serde(default = "default_mcp_url")]
    pub mcp_url: String,
    /// Subject prefix used for digest emails (sending and finding them).
    #[serde(default = "default_digest_subject")]
    pub digest_subject: String,
    #[serde(default)]
    pub digest: DigestCfg,
    #[serde(default)]
    pub claude: ClaudeCfg,
    #[serde(default)]
    pub accounts: Vec<AccountPrefs>,
}

impl Default for Config {
    fn default() -> Self {
        toml::from_str("").expect("empty config parses")
    }
}

impl Config {
    pub fn load(path: &Path) -> Result<Self> {
        let raw = std::fs::read_to_string(path)
            .with_context(|| format!("reading config {}", path.display()))?;
        let cfg: Config = toml::from_str(&raw).context("parsing config")?;
        cfg.validate()?;
        Ok(cfg)
    }

    pub fn validate(&self) -> Result<()> {
        self.tz()?;
        if let Some(t) = &self.digest.send_time {
            parse_hhmm(t).with_context(|| format!("digest.send_time '{t}' must be HH:MM"))?;
        }
        if !["system", "light", "dark"].contains(&self.theme.as_str()) {
            bail!("theme must be system, light or dark");
        }
        let c = &self.calendar;
        if !["day", "week", "month", "agenda"].contains(&c.default_view.as_str()) {
            bail!("calendar.default_view must be day, week, month or agenda");
        }
        if !["sunday", "monday"].contains(&c.week_starts_on.as_str()) {
            bail!("calendar.week_starts_on must be sunday or monday");
        }
        if c.day_start_hour >= c.day_end_hour || c.day_end_hour > 24 {
            bail!("calendar.day_start_hour must be before day_end_hour (0-24)");
        }
        if let Some(w) = &c.working_hours {
            let (a, b) = w.split_once('-').context("calendar.working_hours must look like 09:00-17:00")?;
            parse_hhmm(a)?;
            parse_hhmm(b)?;
        }
        if !["low", "medium", "high", "xhigh", "max"].contains(&self.claude.effort.as_str()) {
            bail!("claude.effort must be low, medium, high, xhigh or max");
        }
        Ok(())
    }

    pub fn tz(&self) -> Result<crate::time::Tz> {
        crate::time::Tz::get(&self.timezone)
    }

    pub fn prefs(&self, email: &str) -> AccountPrefs {
        self.accounts
            .iter()
            .find(|a| a.email.eq_ignore_ascii_case(email))
            .cloned()
            .unwrap_or_else(|| AccountPrefs { email: email.to_string(), ..Default::default() })
    }

    pub fn claude_key(&self) -> Option<String> {
        std::env::var("ANTHROPIC_API_KEY")
            .ok()
            .filter(|k| !k.trim().is_empty())
            .or_else(|| self.claude.api_key.clone().filter(|k| !k.trim().is_empty()))
    }

    /// Example config used by `--demo`.
    pub fn demo() -> Self {
        toml::from_str(include_str!("../inbox-hub.example.toml")).expect("example config parses")
    }
}

pub fn parse_hhmm(s: &str) -> Result<(u32, u32)> {
    let (h, m) = s.split_once(':').context("missing ':'")?;
    let (h, m): (u32, u32) = (h.trim().parse()?, m.trim().parse()?);
    if h > 23 || m > 59 {
        bail!("out of range");
    }
    Ok((h, m))
}

pub fn find_config(explicit: Option<PathBuf>) -> Option<PathBuf> {
    if let Some(p) = explicit {
        return Some(p);
    }
    let local = PathBuf::from("inbox-hub.toml");
    if local.exists() {
        return Some(local);
    }
    // Next to the .exe, so double-clicking it or a shortcut finds the config.
    if let Some(p) = std::env::current_exe().ok().and_then(|e| e.parent().map(|d| d.join("inbox-hub.toml"))) {
        if p.exists() {
            return Some(p);
        }
    }
    let dirs = directories::ProjectDirs::from("", "", "inbox-hub")?;
    let p = dirs.config_dir().join("inbox-hub.toml");
    p.exists().then_some(p)
}

pub fn data_dir() -> PathBuf {
    directories::ProjectDirs::from("", "", "inbox-hub")
        .map(|d| d.data_dir().to_path_buf())
        .unwrap_or_else(|| PathBuf::from(".inbox-hub"))
}
