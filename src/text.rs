//! Turning email HTML and Claude's Markdown into plain text for the native UI.

/// Email HTML as readable plain text. Links are kept as numbered references at the end.
pub fn html_to_text(html: &str) -> String {
    if !html.contains('<') {
        return crate::model::decode_entities(html).trim().to_string();
    }
    // Newsletters and digests lay pages out with nested tables. Read every cell as its own line
    // (no ─ borders), with no link markers or footnotes of long tracking URLs.
    let text = html2text::config::with_decorator(html2text::render::text_renderer::TrivialDecorator::new())
        .raw_mode(true)
        .string_from_read(html.as_bytes(), 10_000)
        .unwrap_or_else(|_| crate::model::strip_tags(html));
    // Drop leftover separator-only lines (─────, -----, =====) and invisible spacer characters.
    let cleaned: Vec<&str> = text
        .lines()
        .map(|l| l.trim_matches(|c: char| c == '\u{200b}' || c == '\u{a0}' || c == '\u{feff}').trim_end())
        .filter(|l| !(l.chars().count() >= 3 && l.chars().all(|c| matches!(c, '─' | '━' | '-' | '=' | '_' | '│' | '┼' | ' '))))
        .collect();
    tidy(&cleaned.join("\n"))
}

/// Collapse runs of blank lines and trailing spaces.
fn tidy(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut blank = 0;
    for line in s.lines() {
        let line = line.trim_end();
        if line.trim().is_empty() {
            blank += 1;
            if blank > 1 {
                continue;
            }
        } else {
            blank = 0;
        }
        out.push_str(line);
        out.push('\n');
    }
    out.trim().to_string()
}

/// Claude's Markdown as tidy plain text: bullets become •, emphasis markers go,
/// links become "text (url)".
pub fn markdown_to_text(md: &str) -> String {
    let mut out = Vec::new();
    for line in md.lines() {
        let t = line.trim_start();
        let indent = &line[..line.len() - t.len()];
        let mut l = if let Some(rest) = t.strip_prefix("- ").or_else(|| t.strip_prefix("* ")).or_else(|| t.strip_prefix("• ")) {
            format!("{indent}• {rest}")
        } else if let Some(h) = t.strip_prefix("#### ").or_else(|| t.strip_prefix("### ")).or_else(|| t.strip_prefix("## ")).or_else(|| t.strip_prefix("# ")) {
            h.to_uppercase()
        } else {
            line.to_string()
        };
        l = l.replace("**", "").replace("__", "").replace('`', "");
        out.push(links(&l));
    }
    tidy(&out.join("\n"))
}

fn links(s: &str) -> String {
    // [text](url) -> text (url)
    let mut out = String::new();
    let mut rest = s;
    while let Some(i) = rest.find('[') {
        let Some(j) = rest[i..].find("](").map(|j| i + j) else { break };
        let Some(k) = rest[j..].find(')').map(|k| j + k) else { break };
        out.push_str(&rest[..i]);
        out.push_str(&rest[i + 1..j]);
        out.push_str(" (");
        out.push_str(&rest[j + 2..k]);
        out.push(')');
        rest = &rest[k + 1..];
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn markdown() {
        let t = markdown_to_text("## Today\n- **Dentist** at 3pm\n- see [link](https://x.y)\n\n\n\nDone");
        assert_eq!(t, "TODAY\n• Dentist at 3pm\n• see link (https://x.y)\n\nDone");
    }

    #[test]
    fn html() {
        let t = html_to_text("<p>Hello <b>there</b></p><p>Bye</p>");
        assert!(t.contains("Hello") && t.contains("Bye"));
        assert!(!t.contains('<'));
    }

    /// Shaped like Superhuman's "emails skipped your inbox" digest: <style>, nested layout
    /// tables, a header row, <wbr> breaks and tracking links. Used to come out as ───── lines.
    #[test]
    fn table_newsletter() {
        let html = r##"<!DOCTYPE html><html><head><title>41 emails skipped your inbox</title>
<style>body{margin:0}.dgs{padding:9px 8px;font-size:12px}</style></head><body>
<table role="presentation" width="100%" style="border:1px solid #ececec"><tbody><tr><td>
<table role="presentation"><tbody><tr><td bgcolor="#714cb6" height="3"> </td></tr>
<tr><td>Superhuman Mail <span>·</span> Auto Archive</td></tr>
<tr><td style="font-size:22px;font-weight:bold">41 emails skipped your inbox</td></tr>
<tr><td>Auto Archive moves routine mail out of your inbox so it stays focused.</td></tr>
<tr><td><a href="https://links.superhuman.com/aa/v1/eyJ2IjoxLCJ1IjoxNTA1?s=ToqdUaeAzQjm">See all 41 in Auto Archive</a></td></tr>
</tbody></table></td></tr>
<tr><td><table class="dggrid" width="100%"><thead><tr><th>Sender</th><th>Subject</th><th>Category</th><td></td></tr></thead>
<tbody><tr><td><div>Academia Mentions</div><div>premium<wbr/><span>@</span>academia-mail<wbr/>.com</div></td>
<td><a href="https://links.superhuman.com/aa/v1/eyJ2Ijox?s=3bIEH"><span>Andrea Omicini mentioned A. McIlraith</span></a></td>
<td>Social likes &amp; vanity</td><td></td></tr></tbody>
<tbody><tr bgcolor="#fbfaf8"><td><div>Canada Post</div><div>donotreply<wbr/><span>@</span>canadapost<wbr/>.ca</div></td>
<td><a href="https://links.superhuman.com/aa/v1/xyz"><span> You have mail on the way </span></a></td>
<td>Order tracking updates</td><td></td></tr></tbody></table></td></tr>
</tbody></table></body></html>"##;
        let t = html_to_text(html);
        for want in ["41 emails skipped your inbox", "See all 41 in Auto Archive", "Academia Mentions",
                     "premium@academia-mail.com", "Andrea Omicini mentioned A. McIlraith", "Social likes & vanity", "You have mail on the way"] {
            assert!(t.contains(want), "missing {want:?} in:\n{t}");
        }
        assert!(!t.contains('─') && !t.contains('│'), "table borders left in:\n{t}");
        assert!(!t.contains("padding") && !t.contains("links.superhuman.com"), "CSS or tracking links left in:\n{t}");
    }
}
