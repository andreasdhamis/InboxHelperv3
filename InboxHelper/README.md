# Inbox Helper 3.0 — AI Communication Command Center (Android)

*What is happening across my business communication, what requires my attention, what have I promised,
who is waiting for me, and what should I say next?*

Sources: **Outlook + Teams** (Microsoft 365, via Microsoft Graph), Calendar, plus SMS/WhatsApp/Viber/Gmail
through phone notifications. Claude analyses every conversation using **retrieved history as evidence**.

## New in 3.0

- **Sign in with Microsoft 365** (OAuth 2.0 + PKCE in a secure browser tab; no passwords; per-feature permissions).
- **Outlook Inbox + Sent history** and **Teams chats** (optional channels, calendar) synced in the background with
  checkpoints, throttling back-off, resume after restarts, and a **Sync health** screen (discovered vs indexed, failures, retry).
- **Knowledge index on the phone** (SQLite full-text + entities) with **hybrid retrieval**: only the most relevant
  5–15 historical items are sent to Claude — never the whole mailbox.
- **Unified conversations across Outlook, Teams and Calendar**, **cross-channel search** with a cited summary, **topics/projects**.
- **Evidence-based replies**: "Why AI suggested this" with **View source**; says what it couldn't confirm instead of guessing.
- **Send via Outlook** (Reply / Reply all / Forward / New, attachments, your signature) and **Teams replies**, always behind a
  confirmation screen; the sent message syncs back and the conversation, SLA and dashboard update.
- **Voice**: 🎤 on every screen, natural Greek/English commands, read-aloud with summary/full, ⏪ ▶ ⏸ ⏹ ⏩ and 0.75–2× speed.
  "Send it" always asks "Send this to …?" first.

## Microsoft 365 setup (one time, ~10 minutes)

You need an app registration in **your** Microsoft Entra ID (this is how every app gets permission to sign in to Microsoft 365).

1. Go to **portal.azure.com → Microsoft Entra ID → App registrations → New registration**.
   - Name: `Inbox Helper`
   - Supported account types: **Accounts in this organizational directory only** (single tenant).
   - Redirect URI: platform **Public client/native (mobile & desktop)**, value **`gr.ipexpert.inboxhelper://auth`**
   - Click **Register**.
2. On the app's **Overview**, copy the **Application (client) ID** and the **Directory (tenant) ID**.
3. **API permissions → Add a permission → Microsoft Graph → Delegated permissions**, add:
   `User.Read`, `offline_access`, `Mail.Read`, `Mail.Send`, `Chat.Read`, `ChatMessage.Send`
   and optionally `Calendars.Read`, `Team.ReadBasic.All`, `Channel.ReadBasic.All`, `ChannelMessage.Read.All`, `ChannelMessage.Send`.
   If you are an admin, click **Grant admin consent** (required for `ChannelMessage.Read.All`; otherwise optional).
4. (Authentication page: nothing else to change. "Allow public client flows" can stay **No**.)
5. In the app: **More → Microsoft 365** → paste the **client ID**, set **Tenant** to your **tenant ID** (or `ipexpert.gr`)
   → **Sign in with Microsoft** → approve → you're back in the app and the first sync starts.

Permissions are requested per feature (switches on the Microsoft 365 screen). If Teams isn't granted, Outlook still works.
To revoke: Disconnect in the app, and remove the app at **myapps.microsoft.com** (or in Entra → Enterprise applications).

## Upgrading from 2.0

Version 3.0 is signed with a fixed key (`app/inboxhelper.keystore`) so future builds install over each other.
**Uninstall the 2.0 test app once** before installing 3.0 (Android refuses an update signed with a different key).

## Features from 2.0

- **Today dashboard** — "You have 23 open conversations, but only 6 need you…", plus live counts:
  requires attention, unanswered, SLA at risk / breached, critical, high, waiting for others,
  due today / this week, overdue, escalations, waiting > 48h. Tap any tile to open that list.
- **Priority inbox** — sorted by AI priority (or importance, urgency, longest unanswered, SLA, deadline…),
  combinable filters, and **natural-language search** ("unanswered customer requests older than 24 hours").
- **Conversation intelligence** — summary (and detailed summary on demand), status (unanswered /
  partially answered / answered / waiting for them / waiting for third party), who must respond,
  importance and urgency 0–100, priority with **"Why?"** explanation, confidence and "needs review",
  deadline detection, SLA with remaining time, escalation warning, sentiment, category,
  open questions table, commitments, recommended next action, full timeline.
- **Unanswered timer** — "Unanswered for 2d 7h · Received 22 Sep 2026, 09:42", live; optional business hours
  (working days, hours, holidays).
- **Replies** — AI draft with tone (Professional, Friendly, Short, Detailed, Formal, Direct), "Improve",
  ready answers (ΕΛ/EN), sent through the original app's reply button. Never sent automatically.
- **Your decisions win** — override priority, status or category; the app can learn from repeated corrections (transparent, switchable).
- **Assistant** — ask "Who is waiting for me?", "What are the 5 most urgent things?"; morning briefing and end-of-day summary.
- **Alerts** — critical, SLA at risk/breached, deadline soon/passed, escalation, follow-up reminders, daily briefing.
- **Contacts, analytics** (response times, SLA compliance, time waiting on you vs others, workload), **audit log**.
- **Demo data** — 13 realistic scenarios (outage, quotation, escalating customer, partial answer, deadline,
  prompt-injection attempt…) analysed by the real AI. Settings → Privacy & data → Load demo data.

See `ARCHITECTURE.md` for the pipeline, AI schema, priority formula, security and design decisions.

## Get the APK (no coding tools needed)

1. Create a free account at github.com and make a new **private** repository.
2. Upload all files from this folder, keeping the structure (including the `.github` folder).
3. Open **Actions** → "Build APK" runs automatically (or press **Run workflow**). Takes about 5–8 minutes.
4. Open the finished run → download **InboxHelper-apk** → unzip → `app-debug.apk`.

Or open the folder in **Android Studio** and press Run with the phone connected.

## Set up on the phone

1. Install `app-debug.apk` (allow "Install unknown apps").
2. Allow **notifications** when asked (for the app's own alerts).
3. Today screen → **Open settings** → enable notification access for Inbox Helper.
   Android 13+: if greyed out, Settings → Apps → Inbox Helper → ⋮ → **Allow restricted settings**, then retry.
4. More → Settings → paste your **Claude API key** (console.anthropic.com → API Keys) → Save key.
   Fill **About you** (e.g. "Network engineer; hotel clients and outages are top priority").
5. Samsung/Xiaomi: set the app's battery usage to **Unrestricted** so background checks run.
6. Optional: load the demo data to see everything working immediately.

## Limits

- Only messages that arrive as notifications after setup (or still in the shade) are seen — no old SMS/email history.
- Replying needs the original notification's reply button; otherwise use **Copy & open**.
- Background checks run about every 15 minutes (Android minimum).
- AI costs: each new/changed conversation is analysed once with the fast model (Haiku by default);
  drafts, briefings and assistant answers use the smart model. You can change both in Settings.
