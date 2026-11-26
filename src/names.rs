//! Turn an email address into a readable sender name.
//!
//! Superhuman often gives only the bare address, so `meadowoodpremierdentistry@gmail.com`
//! would show as one long string. This splits joined words using a ranked English word list
//! (bundled, so it works offline), separates leading initials (`jkolapo` -> "J Kolapo"),
//! and uses the company name for generic mailboxes (`orders@shopify.com` -> "Shopify").

use std::collections::HashMap;
use std::sync::OnceLock;

static WORDS: &str = include_str!("../assets/words-en.txt");

fn ranks() -> &'static HashMap<&'static str, u32> {
    static R: OnceLock<HashMap<&'static str, u32>> = OnceLock::new();
    R.get_or_init(|| WORDS.lines().enumerate().map(|(i, w)| (w, i as u32 + 1)).collect())
}

const SHORT_OK: &[&str] = &[
    "a", "an", "of", "the", "my", "to", "in", "on", "at", "by", "for", "and", "or", "me", "we", "us", "go", "do", "no", "up", "it", "is", "be",
];
const GENERIC: &[&str] = &[
    "info", "contact", "hello", "hi", "support", "help", "noreply", "no-reply", "donotreply", "do-not-reply", "notifications",
    "notification", "orders", "order", "team", "admin", "mail", "email", "office", "billing", "sales", "news", "newsletter",
    "updates", "alerts", "service", "services", "customerservice", "reply", "accounts", "account", "receipts", "marketing",
    "hr", "careers", "jobs", "security", "store", "shop", "bounce", "mailer-daemon", "postmaster", "calendar", "invitations",
];
const ACRONYMS: &[&str] = &["ceo", "cfo", "cto", "hr", "it", "pr", "ai", "api", "dmv", "usa", "uk", "eu", "ca", "rbc", "td", "cibc", "bmo"];
const SLD: &[&str] = &["co", "com", "org", "net", "gov", "ac", "edu"];
const ONSETS: &[&str] = &[
    "bl", "br", "ch", "cl", "cr", "dr", "dw", "fl", "fr", "gh", "gl", "gr", "kh", "kl", "kn", "kr", "ph", "pl", "pr", "qu", "rh", "sc",
    "sh", "sk", "sl", "sm", "sn", "sp", "st", "sw", "th", "tr", "tw", "wh", "wr", "zh",
];

/// Frequency rank of a usable dictionary word (None for unknown or fragment-like words).
fn known(w: &str) -> Option<u32> {
    let r = *ranks().get(w)?;
    if w.len() <= 2 && !SHORT_OK.contains(&w) {
        return None;
    }
    if w.len() <= 4 && r > 15_000 {
        return None; // rare short words are usually pieces of a name
    }
    Some(r)
}

fn cost(w: &str) -> f64 {
    match known(w) {
        Some(r) => ((r + 1) as f64).ln() + 6.0, // +5 per piece: prefer fewer, longer words
        None if w.len() < 4 => f64::INFINITY,  // never invent short unknown pieces
        None => 19.0 + 2.0 * w.len() as f64,   // one longer unknown piece (a surname) is allowed
    }
}

/// Cheapest split of `s` into words, with at most one unknown piece.
fn segment(s: &str) -> (Vec<String>, f64) {
    let n = s.len();
    let mut best = vec![f64::INFINITY; n + 1];
    let mut back = vec![0usize; n + 1];
    let mut unk = vec![false; n + 1];
    best[0] = 0.0;
    for i in 1..=n {
        for j in i.saturating_sub(24)..i {
            if !best[j].is_finite() {
                continue;
            }
            let piece = &s[j..i];
            let is_unk = known(piece).is_none();
            if is_unk && unk[j] {
                continue;
            }
            let c = best[j] + cost(piece);
            if c < best[i] {
                best[i] = c;
                back[i] = j;
                unk[i] = unk[j] || is_unk;
            }
        }
    }
    if !best[n].is_finite() {
        return (vec![s.to_string()], f64::INFINITY);
    }
    let mut out = Vec::new();
    let mut i = n;
    while i > 0 {
        out.push(s[back[i]..i].to_string());
        i = back[i];
    }
    out.reverse();
    (out, best[n])
}

fn is_vowel(c: u8) -> bool {
    matches!(c, b'a' | b'e' | b'i' | b'o' | b'u' | b'y')
}

fn split_token(t: &str, allow_prefix: bool) -> Vec<String> {
    if t.len() <= 5 || known(t).is_some() || !t.bytes().all(|b| b.is_ascii_lowercase()) {
        return vec![t.to_string()];
    }
    let b = t.as_bytes();
    // Initials glued to a word: "mbhydro" -> MB Hydro, "jkolapo" -> J Kolapo.
    if allow_prefix && !is_vowel(b[0]) && !is_vowel(b[1]) && !ONSETS.contains(&&t[..2]) {
        if known(&t[2..]).is_some() {
            return vec![t[..2].to_string(), t[2..].to_string()];
        }
        let mut v = vec![t[..1].to_string()];
        v.extend(split_token(&t[1..], false));
        return v;
    }
    let (parts, c) = segment(t);
    if parts.len() > 1 && parts.len() <= 5 && c < cost(t) {
        parts
    } else {
        vec![t.to_string()]
    }
}

fn cap(w: &str) -> String {
    if ACRONYMS.contains(&w) || (w.len() <= 2 && known(w).is_none()) {
        return w.to_uppercase();
    }
    if w.starts_with("mc") && w.len() > 4 {
        return format!("Mc{}{}", w[2..3].to_uppercase(), &w[3..]);
    }
    let mut c = w.chars();
    c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default()
}

/// "meadowoodpremierdentistry@gmail.com" -> "Meadowood Premier Dentistry".
pub fn display_name(addr: &str) -> String {
    let mut a = addr.trim();
    if let Some(i) = a.find('<') {
        let n = a[..i].trim().trim_matches('"').trim();
        if !n.is_empty() {
            return n.to_string();
        }
        a = &a[i + 1..];
    }
    let a = a.trim_matches(|c| c == '<' || c == '>').to_lowercase();
    let (local, domain) = a.split_once('@').unwrap_or((a.as_str(), ""));
    let tokens: Vec<&str> = local.split(|c: char| c == '.' || c == '_' || c == '-' || c == '+' || c.is_ascii_digit()).filter(|t| !t.is_empty()).collect();
    let compact = local.replace(['-', '.'], "");
    let generic = tokens.is_empty() || GENERIC.contains(&local) || GENERIC.contains(&compact.as_str()) || tokens.iter().all(|t| GENERIC.contains(t));
    let words: Vec<String> = if generic && !domain.is_empty() {
        let mut labels: Vec<&str> = domain.split('.').filter(|l| !l.is_empty()).collect();
        labels.pop(); // top-level domain
        while labels.len() > 1 && labels.last().is_some_and(|l| SLD.contains(l) || l.len() <= 2) {
            labels.pop();
        }
        split_token(labels.last().copied().unwrap_or(local), true)
    } else {
        tokens.iter().flat_map(|t| split_token(t, true)).collect()
    };
    let name = words.iter().map(|w| cap(w)).collect::<Vec<_>>().join(" ");
    if name.is_empty() { addr.to_string() } else { name }
}

#[cfg(test)]
mod tests {
    use super::display_name as d;

    #[test]
    fn splits_joined_words() {
        assert_eq!(d("meadowoodpremierdentistry@gmail.com"), "Meadowood Premier Dentistry");
        assert_eq!(d("oneboyforchange@gmail.com"), "One Boy For Change");
        assert_eq!(d("kingstonhealthclinic@gmail.com"), "Kingston Health Clinic");
        assert_eq!(d("johnsmith@x.com"), "John Smith");
        assert_eq!(d("davidchen92@gmail.com"), "David Chen");
        assert_eq!(d("sunrisemedicalcentre@gmail.com"), "Sunrise Medical Centre");
    }

    #[test]
    fn initials_and_names() {
        assert_eq!(d("Jkolapo@sarariel.ca"), "J Kolapo");
        assert_eq!(d("mbhydro@hydro.mb.ca"), "MB Hydro");
        assert_eq!(d("nora.blake@gmail.com"), "Nora Blake");
        assert_eq!(d("r.mcdonald@x.com"), "R McDonald");
        assert_eq!(d("priya@bigclient.example"), "Priya");
        assert_eq!(d("jira@company.example"), "Jira");
        assert_eq!(d("ceo@company.example"), "CEO");
    }

    #[test]
    fn generic_mailboxes_use_the_company() {
        assert_eq!(d("orders@shopify.com"), "Shopify");
        assert_eq!(d("info@premierdentistry.ca"), "Premier Dentistry");
        assert_eq!(d("noreply@accounts.google.com"), "Google");
        assert_eq!(d("support@winnipegtransit.ca"), "Winnipeg Transit");
    }

    #[test]
    fn real_display_names_win() {
        assert_eq!(d("\"Jane Doe\" <jane@x.com>"), "Jane Doe");
    }
}
