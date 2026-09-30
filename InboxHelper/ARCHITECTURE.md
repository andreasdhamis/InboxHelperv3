# Inbox Helper 3.0 — Architecture & decisions

A personal "AI operations assistant for communication" on Android. It reads the phone's message
notifications (SMS, email, chat apps), uses Claude to understand each conversation, and turns
them into a prioritised, explained, time-tracked dashboard.

**Central idea:** don't make me read everything. Tell me what matters, why, what is unanswered,
how long it has waited, and what to do next.

## Pipeline

```
Notification (Messages, Gmail, Outlook, WhatsApp, Viber, Teams, …)
  → NotifListener: identify app/contact/thread, extract messages (MessagingStyle history incl. my own replies)
  → Repo.ingest: merge into conversation (dedupe by message id), mark analysis pending
  → Analyzer queue (2 concurrent, background): skip if content unchanged (cost control)
  → AiService.analyze: clean (strip quotes/signatures), cap size, wrap as untrusted data, strict JSON schema
  → validate & clamp every field → store Analysis (+ audit entry)
  → IntelEngine (local, deterministic): status, unanswered since, waiting time (business hours),
    priority score & level with factors, SLA state, deadline state, attention, review flag, learned rules
  → UI (Today, Inbox, Detail, Assistant, More) + Notifier (critical, escalation)
MonitorWorker every 15 min: retries failed AI work, SLA/deadline/reminder alerts, morning briefing, retention cleanup
```

## Modules

| Package | Files | Responsibility |
|---|---|---|
| `data` | Models, Repo, Settings, Demo | Domain model, JSON persistence, encrypted settings, demo data |
| `ai` | Provider, AiService | Provider abstraction (`AiProvider` → `ClaudeProvider`), prompts, schemas, validation |
| `engine` | Intel, Stats, Filter | Priority/SLA/business-hours engine, dashboard counts, analytics, filters & sorting |
| `work` | Background | Analysis queue with retry, reply sending, notifications, WorkManager jobs |
| `ui` | AppRoot, Dashboard, Inbox, Detail, Assistant, More, SettingsScreen, Common, Theme | Compose screens |

## AI design

- **Structured output**: one JSON object per conversation (summary, status, expected responder, message type,
  importance 0–100, urgency 0–100, priority, confidence, deadline + text + confidence, category, sentiment,
  escalation + reason, questions with status, next action, suggested assignee, commitments, reasons,
  uncertainty, suggested reply, language). Every field is whitelisted/clamped; invalid JSON → retry.
- **Importance ≠ urgency**: both scored separately; the prompt forbids scoring by keywords like "urgent".
- **Status from the whole thread**: unanswered / partially answered / answered / waiting for them /
  waiting for third party / no action / completed — decided by reading the thread, not by who spoke last.
  If new messages arrive after an analysis, the local engine marks it unanswered immediately until re-analysis.
- **Priority** (local, transparent): base = ½ importance + ½ urgency, + waiting time (≤ +15),
  + deadline today (+10) / overdue (+15), + escalation (+10) → Critical ≥ 85, High ≥ 65, Medium ≥ 40, else Low;
  Informational for FYI/automated items not waiting on you. Never below the AI's own rating while waiting on you.
  Every factor is shown under "Why?".
- **Models per task** (cost control): fast model (default `claude-haiku-4-5`) for analysis and search parsing;
  smart model (default `claude-sonnet-4-5`) for drafts, detailed summaries, assistant and briefings. Both configurable.
- **Cost control**: analyse only new/changed conversations (content hash), last 14 messages, 1,500 chars/message,
  9,000 chars/conversation, quoted history and signatures removed, bounded concurrency, exponential backoff.
- **Assistant & briefings** receive a compact list of summaries/status/timers — not raw message text (minimum data).
- **Natural-language search**: Claude converts the query into the app's `Filter` JSON; the app validates and applies it locally.


## Microsoft 365 layer (3.0)

```
Microsoft identity platform (OAuth 2.0 auth code + PKCE, Custom Tab, refresh token encrypted with Android Keystore)
  ↓
Graph client (m365/Graph.kt): bearer + silent refresh, 429/503/504 Retry-After + exponential back-off, ≤4 concurrent, 410 → resync
  ↓
Sync engine (m365/Sync.kt)
  ├─ Mail: delta on Inbox + Sent Items, $filter receivedDateTime ≥ history window, checkpoint (nextLink/deltaLink) saved after EVERY page,
  │        @removed → deleted locally, $count in window vs indexed → "missing" shown on Sync health
  ├─ Teams chats: /me/chats (+members), per-chat lastModifiedDateTime checkpoints, deleted messages removed
  ├─ Teams channels (optional, admin consent): per-channel delta links + replies of changed threads
  └─ Calendar (optional): rolling −14/+30 days
  ↓
Normalisation → unified Item (index/Index.kt): id, source, thread, participants, subject, text, attachments, web link
  ↓
Knowledge index (SQLite + FTS4 on accent-stripped text, entities table, sync_state, sync_failures)
  ↓
Conversation builder: threads active in the last N days → live conversations (keeps AI analysis & overrides)
  ↓
Intelligence engine (unchanged: status, waiting time, SLA, priority…) + AI with retrieved context
```

**Triggers (near real time without a server):** Graph webhooks need a public HTTPS endpoint, which a phone can't host.
Instead: Outlook/Teams phone notifications trigger an immediate delta sync; the app syncs on open and every 3 minutes while
visible; WorkManager every ~15 minutes in the background; after sending. A server-side webhook receiver is the upgrade path.

**Source of truth:** Microsoft 365. The index is a cache of metadata/text for search and AI; full re-sync re-reads the window.
Sent mail goes through Graph (createReply/createReplyAll/createForward/new → attachments → send), so it lands in Sent Items and
syncs back; an optimistic local copy bridges the few seconds until it arrives.

**Retrieval (RAG):** `Retriever` scores candidates from FTS (idf-weighted, prefix matching for Greek inflection), same
participants, same company domain, subject similarity, shared entities and recency; AI query expansion adds Greek/English
synonyms for search. Only top items (≤ 8–16, ≤ 500–700 chars each) are sent, numbered [S#]; the model must cite them and the
app drops citations to unknown sources. Semantic embeddings can be plugged in behind `Retriever` later.

**Voice:** Android speech recognition (el-GR / en-US) → Claude converts speech to a structured command (JSON) →
`VoiceAgent` executes. Send/confirm is two-step: the agent prepares the message and asks; only an explicit "yes"/tap sends.
Read-aloud uses Android TextToSpeech with a segment queue (summary / full thread / draft / search summary).

**Permissions:** User.Read + offline_access at sign-in; Mail.Read, Mail.Send, Chat.Read, ChatMessage.Send, Calendars.Read,
channel scopes only when their switch is on. Each source degrades independently.

**Not included:** Outlook signature via API (Graph doesn't expose it → pasted once in settings), Microsoft To Do sync,
manual merging of conversations, multi-user/team features.

## Security & privacy

- API key encrypted with an Android Keystore AES-GCM key; never displayed; sent only to api.anthropic.com over HTTPS.
- Prompt-injection defence: message content only inside `<untrusted_conversation>` / `<inbox_data>` blocks,
  angle brackets neutralised, explicit rules that content is data; the model has no tools and cannot act —
  nothing is sent to anyone unless you press Send. Demo includes an injection attempt to show this.
- Data stays in the app's private storage (backups disabled). Retention setting auto-deletes inactive conversations.
- Audit log: AI analyses (model, scores), overrides (AI value → your value), replies sent/copied, tasks, deletions.

## Human override & learning

- Priority, status and category can be overridden; overrides are stored separately and never overwritten by AI.
- Priority corrections are recorded. After 2 identical consecutive corrections for a contact, that priority is applied
  automatically and labelled "learned", with the reason shown; it can be turned off globally or forgotten per contact.

## Decisions (sensible defaults)

| Topic | Decision | Why |
|---|---|---|
| Storage | JSON files via one `Repo` class (atomic writes) | A phone inbox holds hundreds of conversations; no DB dependency; swap to Room inside `Repo` if needed |
| Ingestion | Notification listener | The only way to read messages from all apps without each app's API; replies use the app's own reply action |
| Threading | app + contact (+ normalised subject for email: strips RE/FW/ΑΠ/ΣΧΕΤ) | Groups follow-ups into one conversation |
| Background | WorkManager 15-min periodic + in-process queue | Android's minimum periodic interval; immediate analysis while the app process is alive |
| SLA defaults | Critical 15m, High 1h, Medium 4h, Low 8h (≈1 business day) | From the spec |
| UI language | Bilingual labels (EL/EN); AI output language configurable | Greek + English user |
| Auto-send | Never | AI drafts only; sending always requires your tap |

## Not applicable to a single-user phone app (future work)

Multi-user/teams, roles & permissions, assignment queues, server-side auth, tenant isolation,
CRM/helpdesk/ticketing integrations, merging conversations manually, direct Gmail/Outlook API sync
(would give full history; currently only messages that arrive as notifications are seen).
The provider abstraction, `Repo` boundary and `Filter`/`IntelEngine` are the seams a server version would reuse.
