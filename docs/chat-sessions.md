# Chat Sessions (multi-session tabs)

**Status:** implemented (2026-10-27)

One Eclipse view (`AIChatView`) hosts a `CTabFolder` with one tab per chat session. The editor stays
in the normal editor area; editor selection is forwarded only to the active session tab.

## Storage

- `SessionService` (workspace-scoped) owns a bounded in-memory cache (default 30 materialized
  sessions; open tabs are pinned, only closed sessions are LRU-evicted).
- Each session is an **append-only event log** under
  `workspace metadata/state/sessions/<sessionId>/events.jsonl`.
- Events: `SessionCreated`, `MessageAdded`, `AgentContextReplaced`, `SessionCleared`,
  `SessionRetitled`, `SkillPinned/Unpinned`.
- The transcript is never rewritten. LLM context compaction appends an `AgentContextReplaced`
  event; the visible history stays complete.
- `SessionRepository` is the persistence port; `FileSessionRepository` is the simple adapter. A
  KV/relational implementation can replace it without changing the domain.
- Legacy `<agent>-history.jsonl` files are imported once into one session per file (deterministic
  IDs, original files untouched).

## Reminders

Programmatic reminders (loop breakers, guardrails, compact hints) are normal `UserMessage`s with the
`peon.reminder` attribute. The model never sees the attribute; the transcript marks them as
`MessageKind.REMINDER` and the chat renders them as a separate **Reminder** note.
`ChatMessageUtil.join` preserves attributes, and reminders are not joined with a following user
message.

## UI

- `+` creates a session; `History` lists all saved sessions.
- The active tab owns model/skill selection and selection context; other tabs keep their own.
- Tabs show a working marker and a `?` marker when an agent waits for an answer.
- Pinned skills are session-scoped: pinned via the input bar, persisted as events, injected into
  the turn context with a dedup key, and inherited by subagents subject to their `skills:`/MCP scope.

## MCP

`McpConnectionManager` is one workspace-scoped pool. Session panels hold leases; the underlying
clients are shared and disconnected after an idle timeout once the last lease is released.
Agents can restrict the visible MCP servers with the `mcp-servers:` allowlist
(absent = all workspace-enabled servers, empty = none).
