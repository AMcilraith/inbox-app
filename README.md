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

## Download & run (Windows)

1. Open the repository's **Releases** page and download the newest
   `InboxHub-…-windows-x64.zip`. A new release is built automatically for every change.
2. Unzip it anywhere, e.g. `D:\InboxHub`, and double-click **`InboxHub.exe`**. It's a normal
   desktop app: no console window and no browser tab.

```
InboxHub/
  InboxHub.exe                 the app
  config/
    inbox-hub.toml             your settings (created on first run, kept across updates)
    inbox-hub.example.toml     every option, with examples
  data/                        sign-in tokens, digest marker, error log
  README.md
```

3. Click **Sign in with Superhuman**. Superhuman's sign-in page opens in your browser once;
   after you approve, the app window updates by itself. **Try with sample data** opens a demo
   instead, and **Use my real accounts** in the demo banner switches back.
4. To add more accounts: **Accounts & style → Link another Superhuman account**, approve in the
   browser, then press Refresh.
5. **Accounts & style → Open settings file** opens `config\inbox-hub.toml`. Save it and
   restart the app to apply changes.

To turn on the Claude features, set `ANTHROPIC_API_KEY` (System Properties → Environment
Variables) or `claude.api_key` in `config\inbox-hub.toml`, then restart the app.

To update, unzip the new release over the old folder: `config\` and `data\` are kept. Sign-in
tokens stay in `data\` on your computer and are refreshed automatically. A sign-in from an
older version that used `%APPDATA%\inbox-hub\` is copied over on first start.

## Android app

`InboxHub-…-android.apk` on the Releases page is the phone companion (Android 8.0+), built from
`android/` (Kotlin, Jetpack Compose) on top of the same Superhuman MCP server.

- **Install:** open the APK on your phone (send it to yourself, or download it from the release
  page in the phone's browser) and allow *Install unknown apps* for that app when Android asks.
  New versions install over old ones: every APK is signed with the same key.
- **Sign in** with Superhuman once. Every linked account shows up. **Try with sample data**
  shows the app with four example accounts first.
- **Opens instantly:** the last inbox is kept on the phone and refreshed in the background, and
  the top conversations are fetched ahead so they open at once.
- **Inbox:** Important / Other / All, filter by account, search what's loaded, pull to refresh.
  Swipe right for **Done** (archive), swipe left to mark read/unread. Done, reminders and moves
  show **Undo** for a few seconds. Open a thread to read it (links are tappable, older messages
  fold), star it, set a reminder, or reply from the right account with or without Superhuman AI
  (asks before sending).
- **Ask:** questions across every account, answered by Superhuman AI, in the same conversation
  as the desktop app.
- **Calendar:** the next 7 days across all calendars, with a "now" line, clashes and RSVPs
  flagged and Join buttons. Tap an event to answer Yes/Maybe/No or join the call.
- **Notifications:** new unread mail in each account's Important inbox, grouped per account,
  with **Done** and **Mark read** buttons. Android lets apps check at most every 15 minutes in
  the background; *Settings → Check for new mail now* checks immediately.

Build it yourself (JDK 17+, Android SDK 35):

```bash
cd android
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # tests, and screenshots of every screen in app/build/screenshots/
```

Screenshots: [phone](docs/screenshots/android/) · [desktop](docs/screenshots/desktop/).

## Daily digest

- Set `send_time = "07:45"` under `[digest]`. While Inbox Hub is open it emails the digest once
  a day. If you open the app after that time, it sends straight away.
- Or schedule it with Windows, with the app closed:

  ```powershell
  schtasks /Create /SC DAILY /ST 07:45 /TN "Inbox Hub digest" /TR "D:\InboxHub\InboxHub.exe digest --send"
  ```

- `InboxHub.exe digest` (without `--send`) prints the digest without sending it.

Without a Claude key the digest uses a built-in template. With a key, Claude writes it.

## Configuration (`config\inbox-hub.toml`)

The app looks for its settings in `config\inbox-hub.toml` next to `InboxHub.exe`, then
`inbox-hub.toml` in the current folder or next to the exe (older layout), then
`%APPDATA%\inbox-hub\config\`. If none exists, it creates `config\inbox-hub.toml`.

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

Every push to `main` runs `.github/workflows/release.yml` on Windows: tests, release build,
the folder above zipped, and a new GitHub release. Tag `v1.2.3` for a versioned release.


Needs Rust (stable). On Windows the GNU toolchain works without Visual Studio:

```powershell
rustup default stable-x86_64-pc-windows-gnu
cargo run -- --demo                  # debug build with sample data (keeps a console for logs)
cargo test
.\scripts\release.ps1                # release build, packaged in dist\InboxHub (same layout as a release)
.\scripts\release.ps1 -InstallTo D:\InboxHub
```

| Path | What |
|---|---|
| `ui/app.slint` | The whole UI: layout, theme, components |
| `src/app.rs` | Connects the UI to the Hub (callbacks, models, calendar layout) |
| `src/hub.rs`, `src/model.rs` | Accounts, inbox, to-dos, calendar, digest, drafting |
| `src/auth.rs`, `src/oauth.rs` | Superhuman OAuth (PKCE) and the one-shot sign-in redirect |
| `src/mcp.rs`, `src/claude.rs` | Superhuman MCP client and Claude Messages API |
| `assets/logo.svg` | The logo. Regenerate icons with `cargo run --release --manifest-path tools/make-icon/Cargo.toml` |
