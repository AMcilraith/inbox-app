# Inbox Hub

One desktop app for all your email accounts and calendars, built in Rust with a native
[Slint](https://slint.dev) window on top of **Superhuman's MCP server**
(`https://mcp.mail.superhuman.com/mcp`), with **Claude** built in.

- **Overview**: unread counts, a ranked *What needs doing* list across every account, today's
  merged schedule with clashes and pending RSVPs, and threads you're waiting on.
- **Unified inbox**: all accounts in one list, colour-coded, filtered by your own categories.
  Read any thread and reply from the right account.
- **Calendar**: day, week, month and agenda views across all calendars. Create, edit, RSVP to and
  delete events. Click an empty slot to add an event there.
- **Claude**:
  - **Ask Claude** (button in the sidebar): questions across all accounts and calendars. Claude
    connects to Superhuman through the MCP connector and can use only *read* tools.
  - **Draft with Claude**: writes replies in each account's own style.
  - **AI daily digest**: Claude writes the digest from all your accounts.
- **Daily digest**: emailed to you every morning, and the latest one is shown in the app.
- **Per-account personalisation**: label, colour, VIP senders, urgent words, categories and
  reply style. *Sync style* pushes the style into Superhuman's own AI writer too.

## Using it (Windows)

1. Put `inbox-hub.exe` and `inbox-hub.toml` in the same folder (e.g. `D:\inbox-hub`).
2. Double-click `inbox-hub.exe`. `Try-Demo.bat` opens it with sample data instead.
3. Click **Sign in with Superhuman**. Superhuman's sign-in page opens in your browser once;
   after you approve, the app window updates by itself. Everything else happens in the app.
4. To add more accounts: **Accounts & style → Link another Superhuman account**, approve in the
   browser, then press Refresh.

To turn on the Claude features, set `ANTHROPIC_API_KEY` (System Properties → Environment
Variables) or `claude.api_key` in `inbox-hub.toml`, then restart the app.

Sign-in tokens are stored only on your computer (`%APPDATA%\inbox-hub\`) and are refreshed
automatically. Signing in from an older version of Inbox Hub carries over.

## Daily digest

- Set `send_time = "07:45"` under `[digest]`. While Inbox Hub is open it emails the digest once
  a day. If you open the app after that time, it sends straight away.
- Or schedule it with Windows, with the app closed:

  ```powershell
  schtasks /Create /SC DAILY /ST 07:45 /TN "Inbox Hub digest" /TR "D:\inbox-hub\inbox-hub.exe digest --send --config D:\inbox-hub\inbox-hub.toml"
  ```

- `inbox-hub digest` (without `--send`) prints the digest without sending it.

Without a Claude key the digest uses a built-in template. With a key, Claude writes it.

## Configuration (`inbox-hub.toml`)

The app looks for `inbox-hub.toml` in the current folder, then next to `inbox-hub.exe`, then in
`%APPDATA%\inbox-hub\config\`.

| Key | Meaning |
|---|---|
| `timezone` | IANA name (e.g. `America/Winnipeg`) or `local`. Read from your OS's tz database, so rule changes (like Manitoba's 2026 switch to permanent UTC−5) apply without a rebuild. |
| `port` | Local port used only for the sign-in redirect (default 7878). |
| `theme` | `system`, `light` or `dark`. The switch in the sidebar overrides it and is remembered. |
| `calendar.*` | `default_view`, `week_starts_on`, `day_start_hour`, `day_end_hour`, `default_duration_minutes`, `working_hours`, `add_video_link`. |
| `digest.to` / `digest.from_account` / `digest.send_time` | Digest recipient, sending account, daily time. Recipient and sender default to your primary Superhuman account. |
| `claude.model` / `claude.effort` | Default `claude-opus-5-5` / `medium`. |
| `[[accounts]]` | `email`, `label`, `color`, `vip`, `urgent_keywords`, `style`, `[[accounts.categories]]` (`name`, `from`, `subject`). |

Categories are applied in this order: your rules, then Superhuman's labels (`[1. Respond]`,
`[3. Waiting]`, `[News]`, `[Promotions]`…), then sender heuristics.

## How it works

```
Inbox Hub window (Slint, native)
   └── Hub (src/hub.rs) ──┬── MCP client ──► Superhuman MCP  (mail + calendar, all linked accounts via acting_email)
                          └── Claude API ──► Messages API + MCP connector ──► Superhuman MCP (read-only tool allowlist)
```

- There is no web server. During sign-in only, the app listens on `127.0.0.1:<port>` for
  Superhuman's single redirect, then stops listening.
- Emails are shown as plain text (converted from HTML), so nothing in an email can run code or
  load trackers.
- Claude's own Superhuman access is limited to read tools. Sending and drafting happen only when
  you click a button, and sending asks you to confirm first.
- Claude requests enable server-side refusal fallbacks (`fallbacks: "default"`).

## Development

Needs Rust (stable). On Windows the GNU toolchain works without Visual Studio:

```powershell
rustup default stable-x86_64-pc-windows-gnu
cargo run -- --demo                  # debug build with sample data (keeps a console for logs)
cargo test
.\scripts\release.ps1                # release build + icon, packaged in dist\InboxHub
.\scripts\release.ps1 -InstallTo D:\inbox-hub
```

| Path | What |
|---|---|
| `ui/app.slint` | The whole UI: layout, theme, components |
| `src/app.rs` | Connects the UI to the Hub (callbacks, models, calendar layout) |
| `src/hub.rs`, `src/model.rs` | Accounts, inbox, to-dos, calendar, digest, drafting |
| `src/auth.rs`, `src/oauth.rs` | Superhuman OAuth (PKCE) and the one-shot sign-in redirect |
| `src/mcp.rs`, `src/claude.rs` | Superhuman MCP client and Claude Messages API |
| `assets/logo.svg` | The logo. Regenerate icons with `cargo run --release --manifest-path tools/make-icon/Cargo.toml` |
