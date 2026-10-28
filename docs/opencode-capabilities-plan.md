# OpenCode-like Capabilities — Investigation & Enhancement Plan

> **Status:** 📝 Study / Roadmap (2026-10-15, revised 2026-10-15) — brainstorm + phased story
> candidates. **Purpose:** (A) capture how the harness actually works today, (B) turn the requested
> opencode-style capabilities into an ordered, increment-sized roadmap. Each item is an independent
> story candidate — details are written when the story starts.
> Related study: [verbesserungen.md](verbesserungen.md) (provider/catalog/credentials candidates).

## Design principles (mandatory constraints — user, 2026-10-15)

These apply to **every** story below and are not negotiable:

1. **Do not break the original Peon architecture. Extend, don't rewrite.**
   - Pure-Java capabilities → **interface in `llmpeon-core`** + implementation(s) wired in by the
     service layer (constructor/`Service` injection). Same pattern as `AiProvider`, `WriteValidator`,
     `SkillComponent`.
   - Eclipse-dependent capabilities → **OSGi extension points + extensions** (schema + `plugin.xml`),
     with the consuming logic behind an interface so core stays Eclipse-free.
   - Everything is wired via **DI** (constructor injection / suppliers) — no static singletons, no
     hidden lookups. Follow the existing `PeonAiService` façade + `*Component` wiring.
2. **SOLID / DRY.** One behaviour, one implementation (the `eclipse*`/`disk*` rule is the local
   example). Reuse `CompletionPopup`, `ContextItem`, `ToolPolicy`; do not fork logic per trigger
   character or per agent family.
3. **Buffered IO everywhere** — reuse `parts/shared/IoUtils` (and `Files.newBufferedReader` etc.);
   no raw `FileInputStream`/`Reader` in new code.
4. **Honor the documented architecture** ([architecture.md](architecture.md)): `*Service` public,
   `*Component` private + unit-testable, `*View` one page, `*Widget` one function.

---

# Part A — How it works today (investigation)

## A1 — The agentic loop

Single turn, top to bottom:

```
AIChatView.doSendMessage()                    parts/AIChatView.java
  → PeonAiService.call(msg, monitor)          parts/ai/PeonAiService.java:520
    → AiAgent.call()                          agent/AbstractAgent.java:181
      → doCall(message, monitor)              agent/AbstractAgent.java:264
        → ToolService.executeLoop(req)        tool/ToolService.java:122
```

`ToolService.executeLoop` (`tool/ToolService.java:122`) is the loop itself:

1. Build message list = merged `SystemMessage` + `memory` snapshot (`toOneSystemMessage`, line 254).
2. `ChatRequest` via `StreamingBridge` (langchain4j) with per-agent `AgentConfig` params.
3. Branch on the AI message:
   - **tool requests** → `runAllTools` → `memory.addResult` → loop again (`stuck=0`).
   - **plain text** → add + `break`.
   - **thinking-only, no text** → `stuck++`; after `MAX_STUCK_ITERATIONS` (10) the loop stops
     (a "stuck" nudge `UserMessage` is injected before that).
4. `compactSession` / near-limit handling: `addCompactHintIfNeeded` (line 199) + post-compact
   static-message rebuild (line 162).

Concurrency contract:
- `AbstractAgent.working` (CAS, `AbstractAgent.java:185`) is the hard guard: **one loop per agent
  instance**. A second `call()` while working is not rejected — it is `queueMessage`d
  (`UserMessageQueue`) and drained after the current turn.
- Cancellation is via the `IProgressMonitor` on the request (`AiMonitor`); `onAbortAndDrain` pushes
  queued text into memory.

**Implication for parallelism:** parallel *loops* are safe today because each loop owns its own
agent + memory. Parallel *tool side-effects* are the risk (see A3).

## A2 — Where the system prompt is configured

One abstract method, `AiAgent.getSystemPrompt()`. `AbstractAgent.buildStaticMessages()` →
`buildSystemPrompt()` (`AbstractAgent.java:403`) assembles and **caches** (`systemMessage`):

```
SystemMessage = getSystemPrompt()
              + for each staticContext item: item.render()      // persistent, baked once
```

Invalidated on `setStaticContext`, `compact`, `clear`.

Sources of the base prompt:
| Kind | File | Loader |
|---|---|---|
| Shared default | `core/.../prompts/default.md` | `PromptLoader.withDefault` |
| Built-in agents | `developer.md`, `planner.md`, `po.md`, `review-agent.md`, `scaffold-agent.md`, `search-agent.md`, … | `PromptLoader.load` |
| Delegation playbooks | `po-delegation.md`, `dev-build-loop.md`, `plan-write-loop.md` | loaded by `PoDelegateTool` |
| Custom agents | `<configDir>/agents/<name>/AGENT.md` — frontmatter + body | `AgentService` + `PromptYmlParser`; body is the prompt, optionally prefixed with `default.md` (`include-default`) |

Two context channels (see [context-architecture.md](context-architecture.md)):
- **Static context** (`setStaticContext`) → rendered into the system prompt, cached. Currently the
  environment bake (`AgentContextComponent.initStaticContext`: project info, AGENTS.md, docs map).
- **Turn context** (`setTurnContextSupplier`) → injected as content into the *user* message every
  turn, deduped by `ContextItem.dedupKey()`; re-seeded after compact (`renderTurnContext`,
  `AbstractAgent.java:427`). Carries the workspace memory snapshot + user selection.

Prompt caching note: **any change to the system prompt or message list kills the provider KV
cache** (`ToolService.java:117`). Filters and static messages are deliberately held constant inside
one loop.

## A3 — Architectural decisions that shape this plan

1. **`eclipse*` and `disk*` are one behaviour, two transports.** Shared logic lives in core
   (`FileLines`, `SearchQuery`, `TextFileTypes`, `LogExcerpt`, `AiReponseBuilder`). Never let a new
   capability (e.g. `#` symbols) create a second, softer path.
2. **Plugin = UI + wiring only; core = logic + tests.** Core is plain Maven/JUnit/AssertJ/Lombok,
   no Eclipse types. Anything testable without a workbench belongs in core.
3. **Component architecture** ([architecture.md](architecture.md)): `*Service` public, `*Component`
   private + unit-testable, `*View` = one page, `*Widget` = one UI function, composed by the view.
   One `AIChatView`, one `PeonAiService` per view.
4. **Thread safety is non-negotiable.** Heavy work in `Job.create`, UI only via
   `EclipseUtil.runInUiThread`. Per-agent `working`, per-view `inFlightTurns` + `monitorRef`
   (see [chat-job-lifecycle.md](chat-job-lifecycle.md)).
5. **Write safety is per-agent, not per-tool.** `WriteValidator` (`WriteValidator.DOCS` for Jon,
   `ALLOW_ALL` otherwise) is applied at the write choke point; not the same as a tool allowlist.
6. **Tool exposure = `getToolFilter()` (built-in) + `getToolNameFilter()` (MCP).** Used both at
   runtime and for UI introspection (`isToolActive`) — one source of truth.
7. **Sub-agents already exist as a template, synchronously.** `PoDelegateTool` drives Jon's eager
   RAM-only slaves (`NamedAgent`: Da Thinka/Da Mek/Da Dok); `SearchAgentTool` is a one-shot
   research sub-agent. Nested loops use `ToolLoopRequest.toBuilder()` and **must** override
   `agent` + `memory` (R-CC-15, `ToolLoopRequest.java:31`).
8. **No extension points beyond preferences + the e4 fragment today.** Agents/skills/commands are
   directory-discovered, not contributed via OSGi. `plugin.xml` declares only preference pages,
   preference initializers and theme CSS; the view is an e4 `PartDescriptor` in `fragment.e4xmi`.
9. **Clean break over migration; empty means unset.** No aliases, no migration chains.
10. **"A tool must never lie about a limit."** Every cap/filter is named in the output.

## A4 — What already exists (so we don't rebuild it)

| Requested | Already there | Gap |
|---|---|---|
| (a) multiple chats | one `AIChatView`, e4 `PartDescriptor` | descriptor not multi-instance; history-file collision |
| (b) sub-agents | `PoDelegateTool`, `SearchAgentTool`, per-agent `tools:` allowlist | OSGi extension point; user-defined named sub-agents |
| (c) dynamic skills | `SkillService` (config+project slots), toggle menu, `/skill`, `SkillTool` (list/read) | user-picked per-message skill chips; catalog in prompt |
| (d) chat rendering | SWT `Browser` + bundled markdown-it / highlight.js / diff2html (`resources/chat/`) | **kept as-is** (decision); only UX polish |
| (e) `@` files | **no picker** — only DnD writes a literal `@path` (`FileDropSupport`); editor selection is auto-attached via `UserContext` | `@` completion popup + file→context wiring |
| (f) `#` symbols | **nothing** (only `JdtUtil.findBySearch/findType` exist as building blocks) | picker UI + provider |
| (g) DnD/copy-paste | `FileDropSupport` (resource/local/OS file transfer) into input | hard size cap + context preview |
| (h) token + mode | `TokenHeaderWidget`, `onTokenUsage`, `ActionsBarWidget.updateCompact`; agent combo (Plan/Dev) | explicit Plan/Build segmented selector + per-turn cost |

> **Answer to the `@` question (2026-10-15):** there is **no** existing `@` (or `#`) picker under
> another key. Drag-and-drop *writes* the literal text `@<path>` into the input, but nothing
> completes or resolves it into a context item, and `#` has no equivalent at all. Both are new.

---

# Part B — Roadmap

Phasing principle: **foundations → input/context → agents → parallelism.** Each phase ships
independently.

## Phase 0 — Foundations (enablers)

### S0.1 — Session identity (prerequisite for multi-window + history)
**Problem:** agent history is `<stateDir>/<agent>-history.jsonl` (`AbstractAgent.historyFile`),
keyed by agent name only. Because multi-window will be **independent** (decision below), two
windows sharing an agent name must not collide.
**Design:** introduce a core interface `SessionIdProvider` (default impl = stable single id for
headless/tests); the Eclipse side supplies a per-`AIChatView` id. Thread it to `AgentService` /
`ThreadSafeMemory` / `FileAgentHistoryStore`; file becomes `<agent>-<session>.jsonl`. Old name stays
readable as the default session (clean-read, no migration chain).
**Touch:** `AbstractAgent`, `AgentService`, `FileAgentHistoryStore`, `PeonAiService`.
**Risk:** low. **Principle:** interface in core, provider injected (no Eclipse type in core).

### S0.2 — Core agent descriptor abstraction
**Problem:** `CustomAgent` is built directly from files; extension-contributed agents need a
uniform factory input.
**Design:** extract an immutable `AgentDefinition` (name, prompt body, model/think, tools allowlist,
read-only, handover) that both `PromptYmlParser` and the extension point produce. `CustomAgent`
consumes it. Additive — no behaviour change.
**Touch:** core `agent/`, `prompt/`. **Risk:** low.

## Phase 1 — Input & context (e, f, g, c-chips)

### S1.0 — Extension point: context/mention providers (the DI backbone for e/f) ✅ done (2026-10-15)
**Design:** core interface `org.sterl.llmpeon.mention.MentionProvider` (trigger char + `find`)
and `MentionService` (routing, first provider wins on a trigger collision). The plugin declares
`org.sterl.llmpeon.mentionProvider` (schema + `plugin.xml`) and reads contributions in
`MentionProviderRegistry`; a broken contribution is logged and skipped. The input widget's generic
`CompletionPopup<T>` is shared by slash commands and mentions — one implementation, no per-trigger
fork.
**Touch:** core `mention/`, `parts/mention/`, `parts/widget/CompletionPopup`, `plugin.xml`,
`schema/mentionProvider.exsd`.

Contribute a provider from another bundle:
```xml
<extension point="org.sterl.llmpeon.mentionProvider">
  <provider class="com.example.MyMentionProvider" name="My symbols"/>
</extension>
```
The class implements `MentionProvider`; its `trigger()` decides the character. Built-in providers
are registered first and win a trigger collision; a broken contribution is logged and skipped.

### S1.1 — `@` file picker popup ✅ done (2026-10-15)
**Goal:** type `@` → fuzzy workspace-file popup; select inserts `@<workspace-path>` (the same token
DnD already produces, so both paths behave identically).
**Current:** no picker before this increment (DnD only wrote literal text — see A4).
**Design:** `WorkspaceFileMentionProvider` (`@`) over a cached `WorkspaceFileIndex`; the index is
built on a background `Job` (TTL 10 s, derived/team-private excluded, 50k cap) so typing never
blocks. Filtering/ranking is the shared core `MentionFilter` (basename match > path start > contains),
active project first. `MentionToken` does the caret/token parsing (pure core, unit-tested). **No**
full disk walk.
**Deferred:** automatic attachment as a context item + removable chip (the token is a path reference
for now, identical to DnD). That belongs with S1.3's cap/disclosure work.

### S1.2 — `#` Java symbol picker (JDT-native via extension) ✅ done (2026-10-15)
**Goal:** type `#` → JDT-native type picker; the selected FQN is inserted as `#<FQN>` and attached
on send as the type's source.
**Decision (user, 2026-10-15):** JDT-native dialog, contributed as a **disable-able** extension.
**Implementation:**
- Core stays Eclipse-free; `DialogMentionProvider` is a plugin interface (native-picker variant of
  the inline provider).
- `JavaSymbolMentionProvider` uses `JavaUI.createTypeDialog` and is contributed from `plugin.xml`
  as `<dialogProvider>`; `org.eclipse.jdt.ui` is an **optional** `Import-Package`, and a missing
  bundle (or a broken contribution) is logged and skipped by the registry.
- `ProjectAware` injects the active-project supplier after the registry creates the no-arg provider
  (the extension contract), keeping DI without static state.
- `UserInputWidget` opens a dialog provider once per token (cancel ≠ reopen on every keystroke).
**Verification:** `PeonAiServiceTest.extensionContributesJavaSymbolDialogProvider` asserts the `#`
provider is contributed and the built-in `@` provider is present.

### S1.3 — DnD / paste of arbitrary system files with a hard cap ✅ done for mentions (2026-10-15)
**Current:** `FileDropSupport.formatDroppedPaths` writes `@path` tokens — exactly the syntax the
mention resolver now consumes, so DnD and the `@` picker share one attachment path.
**Implementation:** `MentionContextResolver` turns `@path` / `#Type` references into turn context:
- per attachment **200 lines** (`showing N of M lines` + `truncated: K more line(s)`),
- at most **5 attachments** and **64 000 chars** per turn (`Mention limits` item names what was not
  attached and why: limit / budget / not found).
- `@` reads a workspace file first, then an **absolute system file** (DnD/paste of files outside the
  workspace), buffered and size-capped (2 MB); `#` reads the JDT type's source range; binary types
  are attached as an honest reference. `@Override`-style Java annotations are ignored (a file
  reference needs a separator or a file extension) so pasted code produces no bogus notes.
- `UserContext.get()` now drains one-time context even without a project/selection (prevents silent
  attachment loss).
**Deferred:** removable **chips** above the input (the path token itself is editable today) — own UI
increment.

### S1.4 — User-picked skills as chips (c)
**Goal:** user pins one or more skills for the next turn(s), visible and removable.
**Current:** skills auto-listed; `/skill` injects the body as a one-time order; `SkillTool` fetches
on demand.
**Design:** a `SkillMentionProvider` for S1.0 (trigger `/` already exists — extend it to multi-select
chips) + chip row. A pinned skill adds its body as a *turn context* item with a dedup key so it
survives compact only while pinned. Optional per-agent `skills:` allowlist.
**Open Q (PO):** pinned skill scope — one turn, until unpinned, or per-agent default?

## Phase 2 — Rendering (d) — kept as-is

### S2.1 — Rendering strategy ✅ decided (user, 2026-10-15)
**Decision: keep the current SWT `Browser` renderer.** It works, and markdown-it / highlight.js /
diff2html are bundled local assets — no Node.js, no new dependency. **No** pure-`StyledText`
virtual renderer is pursued (its cost/risk outweighs the benefit). Document this decision as an ADR
so it is not re-litigated: "Chat rendering = embedded Browser with bundled JS assets."
**Note for the ADR:** `Browser` is still the OS-native engine (WebKit2/WebView2), not Node — state
that explicitly, plus the fallback behavior when no engine is available.

### S2.2 — (cancelled) pure-StretchedText virtual renderer
Cancelled by S2.1. `flexmark` remains available in core for any future non-UI need.

### S2.3 — Improve the current Browser chat view ✅ done (2026-10-15)
Implemented in `resources/chat/chat.html` (+ harness `test-chat.html`), no Java change:
- **Copy button on every fenced block** — the button existed only for language-tagged blocks;
  now also on unlabeled code blocks (path blocks get the editor link instead).
- **Long tool output collapse with disclosure** — replaced the silent "keep last 10 messages" cap:
  consecutive `TOOL` lines are grouped, the last 10 stay visible, and a toggle states the exact
  number of hidden lines (`⋯ N earlier lines — show all`). The full list stays in the DOM.
- **Clickable fenced workspace paths** — a fenced block containing exactly one path
  (`src/main/java/Foo.java`) becomes an `open-in-editor:` link through the existing
  `ChatMarkdownWidget` `LocationListener`; URLs and real code stay code.
Docs: [chat-markdown-links.md](chat-markdown-links.md) R5/R6, homepage feature list.
Verification: JS syntax + `isWorkspacePath` cases checked with Node; visual check via
`test-chat.html` (new buttons: long tool output, fenced path).

## Phase 3 — Agents & sub-agents (b) — anti "tool pollution"

> **Key target (user, 2026-10-15):** avoid **tool pollution** and a single "god agent" with dozens
> of tools. Split into several **specialized agents**, each with a curated tool set. Go for the
> **simplest and most reliable** mechanism.

### S3.0 — Skill/agent extension points, catalogs and generic delegation ✅ done (2026-10-15)
This is the "minimal path" (catalog + expand/start) plus the two Tier-1 extension points:
- **`skill` extension point** — `SkillProvider` returns `SkillDefinition(name, description, body,
  metadata)`. `SkillSource.EXTENSION` (`[bundle]`); precedence **extension < config < project**.
  `SkillService.catalog()` renders descriptions only (cap 100 with disclosure); the body is loaded
  by `skillRead` on demand. Name-keyed enable/disable works unchanged.
- **`agent` extension point** — `AgentProvider` returns `AgentDefinition(name, description, prompt,
  tools, readOnly, includeDefault, model, think, handover, delegatable, metadata)`. Materialized as
  the existing `CustomAgent` via a synthetic `SimplePromptFile`; file agents and persistent
  built-ins win name collisions; contributed agents are disable-able by name.
- **Catalogs injected into the prompt** — `AgentContextComponent` adds a `Skill catalog` (name +
  description) and an `Agent catalog` (delegatable agents only, and only for agents that can call
  `startAgent`) to the static context. Bodies never enter the catalog; toggling a skill re-bakes it.
- **`startAgent(agent, prompt)`** — core tool, synchronous, same dispatch pattern as
  `PoDelegateTool`; only `isTool()`/delegatable agents are startable; a sub-agent cannot start
  another; `SearchAgentTool` excludes it. Jon's curated service gets the tool too.
- A sub-agent's context is the parent turn context (set by `startAgent`), and its curated tool
  allowlist keeps the "no god agent" property.

### S3.1 — OSGi extension point `org.sterl.llmpeon.agent` ✅ done (2026-10-15)
Implemented as described in S3.0: `schema/agent.exsd`, `AgentProviderRegistry`, `AgentService`
contributed-agent reload, file/persistent precedence, name-keyed enabled state. MD-file agents remain
the no-code path.

### S3.2 — Specialized agents with curated tools ✅ done (2026-10-15)
- `BuiltInSpecialistAgents` (contributed via `plugin.xml`) adds **Java Developer** (JDT read/
  navigate/edit/test/build/debug prefixes) and **PDE Developer** (PDE info + read/edit/build/test).
  Both are delegatable and appear in the agent catalog.
- New read-only `eclipsePdeInfo` tool: natures, MANIFEST headers, plugin/fragment/feature paths and
  `*.target` definitions — no shell needed.
- The specialists can be selected directly in the agent combo or started by `startAgent`; users can
  disable a contributed agent by name (API; no dedicated UI toggle yet).

### S3.3 — Async/delegated sub-agent execution — **NOT required for S3.2**
**Answer (user Q4):** async is **not** needed for the anti-tool-pollution goal. Synchronous
delegation (the existing `PoDelegateTool.dispatch` template) already delivers specialized agents
with curated tools. Async only becomes valuable if we later want the orchestrator to keep working
*while* a sub-agent runs — it is an optimization, not a prerequisite.
**If/when pursued:** follow [async-agent-tools-proposal.md](async-agent-tools-proposal.md) Option C
(queue-based). Hard constraint: nested **parallel** calls must get their **own**
`StreamingBridge`/`ApiRetry` instance, not the one inherited by `ToolLoopRequest.toBuilder()`
([open-points.md](open-points.md), R-CC-15). Deferred until a concrete need exists.

### S3.4 — `tool` and `contextProvider` extension points ✅ done (2026-10-15)
- **`tool`** — `ToolProvider` (core) returns `SmartTool`s. `ToolService.addContributedTool`
  registers them with `SmartToolExecutor.isContributed = true`; collisions with built-ins are
  rejected atomically. **Opt-in:** an absent `tools:` allowlist now means *built-in tools only*;
  a custom agent names a contributed tool (exact/prefix) to enable it. Built-in agents and the
  search sub-agent exclude contributed tools automatically. Providers and tools may implement
  `ProjectAware` for the active project.
- **`contextProvider`** — `ContextProvider`/`ContextRequest` (core) with `STATIC` and `TURN`
  scopes. Static items join the system prompt; turn items join the turn context. The host caps
  each provider at **5 items** (disclosed) and turns a provider exception into a visible
  `Context provider failed` item — never silent. Providers are trusted plugin code and must
  return empty when not applicable.
- New schemas `schema/tool.exsd` / `schema/contextProvider.exsd`; exported `org.sterl.llmpeon.tool`
  and `org.sterl.llmpeon.context` so third-party bundles can implement both.
- Tests: `ToolContributedTest` (opt-in, built-in registration, collision) and the extended
  `AgentContextComponentTest` (static/turn, cap, failure visibility).

### S3.5 — In-process MCP server extension + `defaultEnabled` ✅ done (2026-10-15)
- External MCP was already supported (STDIO `command`/`args`/`envVars`, Streamable HTTP `url`/`apiKey`,
  protocol auto-detect) — no change needed.
- New core `IMcpServer` / `IMcpTool`; extension point `org.sterl.llmpeon.mcpServer`; tools use the
  existing `@Tool`/`@P` markup (no second annotation set).
- `McpServerProviderRegistry` registers the server's tools as **contributed tools**, marked MCP
  (`SmartToolExecutor.isMcp()`), so the header popup shows `(MCP)`.
- **`defaultEnabled` is false by default** for both `IMcpServer` and the `tool` extension point:
  contributed tools are opt-in via the agent's `tools:` allowlist. `true` opts them into the default
  tool list. Built-in filters (`AbstractAgent`, `CustomAgent` absent allowlist) respect the flag.
- `SearchAgentTool` still excludes all contributed tools (minimal research mode). A broken
  contribution is logged and skipped; a name collision is rejected.
- Schemas `tool.exsd` (new attribute) and `mcpServer.exsd`; exported `org.sterl.llmpeon.mcp`.

## Phase 4 — Multiple chat windows (a) — independent, like OpenCode

**Decision (user, 2026-10-15): fully independent sessions.** Each window is its own conversation,
its own agents, its own memory — no shared conversation across windows.

### S4.1 — Multi-instance view
**Design:** set `multiple="true"` (+ `allowMultiple`) on the e4 `PartDescriptor`
(`fragment.e4xmi`); add a command/menu ("New AI Chat") using `EPartService.showPart` against the
descriptor. Each instance already constructs its own `PeonAiService` → own `AgentService` +
`ToolService`.
**Touch:** `fragment.e4xmi`, `plugin.xml` (menu/command), `AIChatView`.

### S4.2 — Per-window isolation audit
- **History files** → fixed by S0.1 (independent session ids).
- **Tools** → each view owns its `SharedToolsComponent`, so `workingDir`/`currentProject` state is
  per-view — document this as the isolation guarantee.
- **MCP connections:** each `PeonAiService` builds its own `McpConnectionService` → duplicate server
  processes. With independent windows, decide: shared broker service, or accept per-window.
- **Preferences:** global; every view re-applies config on change. Confirm `updateConfig` is
  idempotent/cheap (consider debounce).
- **State migration** (`StateMigration.migrate`) runs per service construction — confirm idempotent.
**Open Q (PO):** a new window starts on the same project as the active view, or blank?

### S4.3 — Cross-window status (optional)
Only if wanted: a shared `AgentActivityBus` (OSGi service) so one window can show that another
window's agent is working. Not required by the "independent" model — mark optional.

## Phase 5 — Header polish (h)

### S5.1 — Plan/Build mode selector
**Current:** the agent combo switches Peon-Plan / Peon-Dev; handover moves plan→dev.
**Design:** a segmented `Plan | Build` toggle mapping to the existing `setActiveAgent` + handover,
keeping the combo for custom agents. No new agent machinery.

### S5.2 — Token usage indicator v2
**Current:** cumulative `↑/↓/⇄` in the header; compact button shows `used/max`.
**Enhance:** per-turn + per-window usage, a context-fill bar, honest estimate-vs-provider labeling
(the `~N (estimate)` convention exists).

## Phase 6 — Quick wins (i)

See Part C — ship one per small cycle.

---

# Part C — Quick wins

1. **`@` completion popup** (S1.1) — biggest usability gap; via the S1.0 provider.
2. **Browser chat improvements** (S2.3) — copy-code, long-output collapse, open-in-editor.
3. **Editor context menu "Add to Peon chat"** — right-click file/selection → chip.
4. **Plan/Build segmented toggle** (S5.1).
5. **Skill catalog in the system prompt** — one static context item listing `skillNames()` so the
   model reliably calls `skillRead` (today discovery depends on the model calling `skillList`).
6. **Recent models + env-var provider detection** — [verbesserungen.md](verbesserungen.md) 1–2.
7. **Stop affordance in the live status line** — surface the existing stop button in "working since…".
8. **Per-agent provider/URL override** — open point [open-points.md](open-points.md); unblocks
   specialized sub-agents on different APIs.

---

# Part D — Decisions & remaining questions

**Decided (user, 2026-10-15)**
1. **Multi-window model:** fully **independent** sessions (S4.1). ✅
2. **Rendering:** keep the current `Browser` renderer; no StyledText rewrite (S2.1). ✅
3. **Extension-agent precedence:** contributed agents are **lower priority** than file agents and
   **disable-able** like skills (S3.1). ✅
4. **`#` picker:** **JDT-native** dialog, injected as a disable-able extension provider (S1.2). ✅
5. **Async sub-agents:** **not needed** for the anti-tool-pollution goal (S3.3). ✅
6. **`@` picker exists?** No — both `@` and `#` pickers are new (A4). ✅

**Remaining questions**
1. `#` picker scope — **type-only implemented**; member-level (`#Type#method`) still open.
2. Pinned-skill scope — one turn / until unpinned / per-agent default?
3. MCP per independent window — shared broker, or accept duplicate connections?
4. New window initial state — same project as the active view, or blank?

**Decided during implementation**
- **Extension-point shape:** one `mentionProvider` point with two elements — `<provider>` (inline
  list) and `<dialogProvider>` (native picker). Built-ins win trigger collisions; a missing optional
  dependency (JDT UI) is logged and skipped.
- **Attachment defaults:** 200 lines / 5 references / 64 000 chars per turn, always disclosed.

**Suggested ordering**
```
P0 (S0.1, S0.2) → P1 (S1.0, S1.1, S1.3, S1.2, S1.4) + S2.3 + quick wins
→ P3 (S3.1, S3.2) → P4 (S4.1…) → P5 (S5.1, S5.2)
```
Phase 1, S2.3 and the quick wins deliver the most visible value at the least architectural risk.
Phase 3 (specialized agents) is the strategic core and its simplest path is synchronous delegation.

**Implementation update (2026-10-27):** Phase 4 shipped as a **single view with internal session
tabs** (not multi-instance views). S0.1 is implemented as an append-only session event log with a
30-session LRU cache; reminders carry a `peon.reminder` attribute; legacy histories are imported;
MCP connections are workspace-pooled with per-session leases and `mcp-servers:` agent scoping.
Session-scoped skill pins are implemented. See [chat-sessions.md](chat-sessions.md).
