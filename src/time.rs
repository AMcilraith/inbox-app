//! Time zones from the operating system's tz database (via jiff), so rule changes such as
//! Manitoba's 2026 move to permanent UTC−5 are picked up without rebuilding the app.

use anyhow::{anyhow, Result};
use chrono::{DateTime, Datelike, Duration, NaiveDateTime, TimeZone, Timelike, Utc};

#[derive(Clone, Debug)]
pub struct Tz(jiff::tz::TimeZone);

impl Tz {
    /// An IANA name such as "America/Winnipeg", or "local" for this computer's zone.
    pub fn get(name: &str) -> Result<Self> {
        if name.eq_ignore_ascii_case("local") {
            return Ok(Tz(jiff::tz::TimeZone::system()));
        }
        jiff::tz::TimeZone::get(name).map(Tz).map_err(|e| anyhow!("unknown timezone '{name}': {e}"))
    }

    pub fn utc() -> Self {
        Tz(jiff::tz::TimeZone::UTC)
    }

    /// Wall-clock time in this zone.
    pub fn local(&self, t: DateTime<Utc>) -> NaiveDateTime {
        let off = jiff::Timestamp::from_second(t.timestamp())
            .map(|ts| self.0.to_offset(ts).seconds())
            .unwrap_or(0);
        t.naive_utc() + Duration::seconds(off as i64)
    }

    pub fn now(&self) -> NaiveDateTime {
        self.local(Utc::now())
    }

    /// The instant a wall-clock time in this zone refers to (earlier one if ambiguous).
    pub fn to_utc(&self, n: NaiveDateTime) -> DateTime<Utc> {
        let civil = jiff::civil::DateTime::new(
            n.year() as i16,
            n.month() as i8,
            n.day() as i8,
            n.hour() as i8,
            n.minute() as i8,
            n.second() as i8,
            0,
        );
        civil
            .ok()
            .and_then(|c| self.0.to_ambiguous_timestamp(c).compatible().ok())
            .and_then(|ts| Utc.timestamp_opt(ts.as_second(), 0).single())
            .unwrap_or_else(|| Utc.from_utc_datetime(&n))
    }
}

impl Tz {
    /// RFC 3339 timestamp with this zone's offset, e.g. 2026-12-01T13:30:00-05:00.
    pub fn rfc3339(&self, n: NaiveDateTime) -> String {
        let utc = self.to_utc(n);
        let off = (self.local(utc) - utc.naive_utc()).num_seconds() as i32;
        let fixed = chrono::FixedOffset::east_opt(off).unwrap_or_else(|| chrono::FixedOffset::east_opt(0).unwrap());
        utc.with_timezone(&fixed).to_rfc3339()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn round_trip() {
        let tz = Tz::get("America/Toronto").unwrap();
        let t = Utc.with_ymd_and_hms(2026, 1, 15, 17, 0, 0).unwrap();
        assert_eq!(tz.local(t).hour(), 12);
        assert_eq!(tz.to_utc(tz.local(t)), t);
    }
}
