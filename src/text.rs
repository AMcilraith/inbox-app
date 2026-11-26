//! Turning email HTML and Claude's Markdown into plain text for the native UI.

/// Email HTML as readable plain text. Links are kept as numbered references at the end.
pub fn html_to_text(html: &str) -> String {
    if !html.contains('<') {
        return crate::model::decode_entities(html).trim().to_string();
    }
    let text = html2text::from_read(html.as_bytes(), 10_000);
    tidy(&text)
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
}
