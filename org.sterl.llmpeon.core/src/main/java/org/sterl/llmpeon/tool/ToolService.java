package org.sterl.llmpeon.tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;
import org.sterl.llmpeon.compact.CompactConstants;
import org.sterl.llmpeon.exception.ExceptionUtil;
import org.sterl.llmpeon.mcp.McpServerConfig;
import org.sterl.llmpeon.mcp.McpService;
import org.sterl.llmpeon.mcp.McpToolGateway;
import org.sterl.llmpeon.session.UserReminder;
import org.sterl.llmpeon.shared.AiMonitor;
import org.sterl.llmpeon.shared.StringUtil;
import org.sterl.llmpeon.tool.component.SmartToolExecutor;
import org.sterl.llmpeon.tool.model.SimpleMessage;
import org.sterl.llmpeon.tool.model.ToSimpleMessage;
import org.sterl.llmpeon.tool.tools.AbstractTool;
import org.sterl.llmpeon.tool.tools.CompactSessionTool;
import org.sterl.llmpeon.tool.tools.SearchAgentTool;
import org.sterl.llmpeon.tool.tools.ShellTool;
import org.sterl.llmpeon.tool.tools.WebFetchTool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns all tools and the tool registry.
 * Tools are stable instances — only the context changes when the user selects a different file/project.
 *
 * https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/tools.md
 */
@Slf4j
public class ToolService {

    private static final int MAX_STUCK_ITERATIONS = 10;
    private final Map<String, SmartToolExecutor> toolExecutors = new ConcurrentHashMap<>();
    /** Makes the check-and-register of tools atomic (built-in and contributed alike). */
    private final Object toolRegistrationLock = new Object();

    /** Public: the compact stage-1 reduction references it as a named constant (ADR-0056.4). */
    public static final String COMPACT_HINT =
            "CONTEXT LIMIT WARNING: Call '" + CompactSessionTool.NAME + "' as your first tool call. " +
            "In the 'preserve' field, summarize the critical next steps and any findings needed to continue. " +
            "Include hard-won facts that are expensive to rediscover — exact file:line locations and key decisions with their rationale (the why) — and what to do next.";

    private static final String STUCK_MESSAGE = """
            Your last response contained only internal reasoning with no output.
            Stop thinking and take action now: either call a tool, ask a clarifying question,
            or provide your answer directly.""";

    private McpToolGateway mcpService;

    public ToolService() {
        this(true);
    }

    /**
     * @param withDefaults if true, auto-registers WebFetchTool, SearchAgentTool, ShellTool, CompactSessionTool
     */
    public ToolService(boolean withDefaults) {
        if (withDefaults) {
            addTool(new WebFetchTool());
            addTool(new SearchAgentTool(this));
            addTool(new ShellTool());
            addTool(new CompactSessionTool());
        }
    }

    public List<ToolSpecification> toolSpecifications() {
        return toolExecutors.values().stream()
                .map(SmartToolExecutor::getSpec)
                .toList();
    }

    /** All registered built-in tool executors (UI introspection). */
    public java.util.Collection<SmartToolExecutor> getExecutors() {
        return toolExecutors.values();
    }

    /** Names of the currently connected MCP tools (empty when no MCP server is connected). */
    public List<String> mcpToolNames() {
        return mcpToolSpecs().stream().map(ToolSpecification::name).toList();
    }

    private List<ToolSpecification> mcpToolSpecs() {
        var service = mcpService;
        return service == null ? List.of() : service.getToolSpecifications();
    }

    List<ToolSpecification> toolSpecifications(ToolLoopRequest req) {
        var result = new ArrayList<ToolSpecification>();
        toolExecutors.values().stream()
                .filter(req.toolFilter)
                .map(SmartToolExecutor::getSpec)
                .forEach(result::add);

        mcpToolSpecs().stream()
                .filter(spec -> req.toolNameFilter.test(spec.name()))
                .filter(spec -> req.getAgent() == null
                        || req.getAgent().isMcpServerActive(mcpServerOf(spec.name())))
                .forEach(result::add);
        return result;
    }

    private String mcpServerOf(String toolName) {
        var service = mcpService;
        return service == null ? null : service.toolServer(toolName);
    }

    /** Owning MCP server of a tool, or {@code null} for unknown/non-MCP tools (UI introspection). */
    public String mcpServerForTool(String toolName) {
        return mcpServerOf(toolName);
    }

    public SmartToolExecutor getExecutor(String toolName) {
        return toolExecutors.get(toolName);
    }

    /**
     * Runs the full tool loop: calls the model via streaming, executes any tools, repeats until
     * the model produces a plain text response.
     * 
     * TODO: https://github.com/sterlp/eclipse-peon-ai/issues/55
     * Keep in mind any change to the message history may kill the kv cache!!
     * https://github.com/sterlp/eclipse-peon-ai/issues/60
     */
    @NonNull
    public ChatResponse executeLoop(@NonNull ToolLoopRequest req) {
        ChatResponse response = null;

        int iterations = 0;
        boolean shouldLoop = true;
        int stuck = 0;

        do {
            var messages = new ArrayList<ChatMessage>(toOneSystemMessage(req.staticMessages));
            req.getMemory().addMemoryTo(messages);

            var agentConfig = req.getAgentConfig();
            var builder = ChatRequest.builder()
                    .messages(messages)
                    .parameters(agentConfig.newRequestParameters(toolSpecifications(req)));

            req.getMonitor().onChatMessage(++iterations, builder);
            response = req.call(builder.build());

            if (response == null) {
                throw new IllegalStateException("AI call returned null — streaming failed without a response");
            }

            ToSimpleMessage.INSTANCE.convert(response.aiMessage()).forEach(req.monitor::onChatResponse);

            if (req.getMonitor().isCanceled()) break; // TODO re-think this

            var isToolrequest = response.aiMessage().hasToolExecutionRequests();
            var hasResponseMessage = StringUtil.hasValue(response.aiMessage().text());
            var hasThink = StringUtil.hasValue(response.aiMessage().thinking());
            shouldLoop = isToolrequest || (hasThink && !hasResponseMessage);

            // we always add the AI messages later
            // 1. we may cancel and have tool request with no response -> error in next cycle
            // 2. tools may clear the memory
            if (isToolrequest) {
                stuck = 0; // reset on productive tool use
                var tR = runAllTools(response, req);
                req.getMemory().addResult(response, tR);
                if (ranTool(response, CompactSessionTool.NAME)) {
                    if (req.isCompactedThisTurn()) {
                        // The compact-request's provider usage describes the PRE-compact context —
                        // re-derive the counter from the new (small) memory
                        req.getMemory().reevaluateTokens();
                        var agent = req.getAgent();
                        if (agent != null) {
                            req.staticMessages(agent.buildStaticMessages(req.getMonitor()));
                        }
                    }
                    // SKIPPED_SMALL / FAILED_EMPTY: the real counter value stays (R-CC-2)
                } else addCompactHintIfNeeded(req, response, false);

            } else if (hasResponseMessage) {
                stuck = 0; // reset on productive response
                req.getMemory().addResult(response);
                break; // done
            } else if (hasThink) {
                // https://github.com/langchain4j/langchain4j/issues/4786
                ++stuck;
                // we cannot just add the message in this case as is
                // -> "Assistant message must contain either 'content' or 'tool_calls'!","type":"invalid_request_error"
                req.getMemory().add(AiMessage.from(response.aiMessage().thinking()));
                req.monitor.onProblem("AI hangs - only thinking returned times: " + stuck);
                if (stuck > MAX_STUCK_ITERATIONS) break;
                if (stuck > MAX_STUCK_ITERATIONS - 2) addCompactHintIfNeeded(req, response, true);
                else addReminder(req, STUCK_MESSAGE);
            }
        } while (shouldLoop && !req.monitor.isCanceled());

        return response;
    }

    private static boolean ranTool(ChatResponse response, String toolName) {
        return response.aiMessage().toolExecutionRequests().stream()
                .anyMatch(tr -> toolName.equals(tr.name()));
    }

    /**
     * Adds a programmatic reminder to the LLM context and shows it in the chat as a reminder
     * (not as a user message). The model itself sees a normal {@code UserMessage}.
     */
    private static void addReminder(ToolLoopRequest req, String text) {
        req.addMessage(UserReminder.create(text));
        AiMonitor.nullSafety(req.monitor).onChatResponse(new SimpleMessage(SimpleMessage.Type.REMINDER, text));
    }

    private void addCompactHintIfNeeded(ToolLoopRequest req, ChatResponse response, boolean force) {
        var memory = req.getMemory();
        // R-CC-8: the hint only makes sense with a real history — at ≤ MIN_COMPACT_MESSAGES the
        // compact would skip anyway (old gate: < 10)
        if (memory.size() <= CompactConstants.MIN_COMPACT_MESSAGES) return;
        var compactLimit = req.getConfig().getAutoCompactAfter();
        if (compactLimit <= 0 && !force) return;
        var shouldCompact = force || memory.getTotalTokenUsed() > compactLimit * 0.95;
        if (!shouldCompact) return;

        // R-CC-15: per-request check — the registry is parent-wide, but the hint may only fire
        // when THIS loop's tool set actually offers compactSession; otherwise the honest fallback
        var hasCompactTool = toolSpecifications(req).stream()
                .anyMatch(spec -> CompactSessionTool.NAME.equals(spec.name()));
        var agent = req.getAgent();
        if (agent == null || !hasCompactTool) {
            // avoid that a search agent or any other agent without a compact tools get stuck
            final var msg = "Your context window is almost full and cannot be compacted. " +
                    "Stop calling tools now and give your best final answer with what you have so far.";
            if (!memory.containsMessage(msg)) {
                addReminder(req, msg);
                AiMonitor.nullSafety(req.monitor).onTool("Info for search agent added to return now a result, context full: " + memory.getTotalTokenUsed());
            }
        } else {
            // R-CC-4: one hint per memory — a cleared memory (successful compact) re-arms it
            if (memory.containsMessage(COMPACT_HINT)) return;
            var used = memory.getTotalTokenUsed() + " tokens of " + compactLimit + " used.";
            String agentName = agent.getName();
            // R-CC-10: the diagnosis rides on the onTool LOG line only — the UserMessage below must
            // stay clean (it becomes LLM context; the diagnosis is not for the model).
            AiMonitor.nullSafety(req.monitor).onTool("🗜 Compact hint for " + agentName + " added! " + used + memory.tokenDiagnosis());
            addReminder(req, COMPACT_HINT + System.lineSeparator() + used);
        }
    }

    private List<ToolExecutionResultMessage> runAllTools(ChatResponse response, ToolLoopRequest req) {
        var toolResults = new ArrayList<ToolExecutionResultMessage>();

        for (var tr : response.aiMessage().toolExecutionRequests()) {
            try {
                var trResult = execute(tr, req);
                toolResults.add(trResult);
            } catch (Exception e) {
                if (ExceptionUtil.isCanceled(e)) throw e;
                log.error("Tool {} with args {} failed", tr.name(), tr.arguments(), e);
                req.getMonitor().onProblem(tr.name() + " failed: " + e.getMessage() + " details logged.");
                toolResults.add(ToolExecutionResultMessage.from(tr.id(), tr.name(),
                        "Tool failed - check why and inform user " + StringUtil.getStackTrace(e)));
            }
        }

        return toolResults;
    }
    
    /** Merges all SystemMessages into one at the front (compatibility with local LLMs). */
    private static List<ChatMessage> toOneSystemMessage(List<ChatMessage> messages) {
        var result = new ArrayList<ChatMessage>();
        var systemText = new StringBuilder();
        for (var m : messages) {
            if (m instanceof SystemMessage sm) systemText.append(sm.text()).append("\n");
            else result.add(m);
        }
        if (systemText.length() > 0) result.addFirst(SystemMessage.from(systemText.toString()));
        return result;
    }

    public static record ToolResult(boolean clearMemory, ToolExecutionResultMessage message) {}

    public ToolExecutionResultMessage execute(ToolExecutionRequest tr, ToolLoopRequest req) {
        var executor = toolExecutors.get(tr.name());
        var monitor = req.getMonitor();
        String result;
        var isMcpTool = executor == null && mcpService != null && mcpService.hasTool(tr.name());
        if (isMcpTool) {
            monitor.onTool("Running MCP: " + tr.name() + " - " + tr.arguments());
            result = mcpService.executeTool(tr);
            log.debug("MCP Tool {} result size: {}", tr.name(), result == null ? "null" : result.length());
            return ToolExecutionResultMessage.from(tr, result);
        } else if (executor == null) {
            result = "Error: unknown tool '" + tr.name() + "' check spelling";
            monitor.onProblem(result);
        } else {
            result = executor.run(tr, req);
        }
        return ToolExecutionResultMessage.from(tr, result);
    }

    /**
     * Connects to all given MCP servers and makes their tools available in the tool loop.
     * Disconnects any previously active MCP connection first.
     * Throws if any server fails to connect.
     */
    public void connectMcp(List<McpServerConfig> servers) {
        disconnectMcp();
        if (servers == null || servers.isEmpty()) return;
        var service = new McpService(servers);
        service.connect(); // throws on failure
        this.mcpService = service;
    }

    /** Uses a shared/pooled MCP gateway; the gateway owns its lease. */
    public void setMcpGateway(McpToolGateway gateway) {
        this.mcpService = gateway;
    }

    /** Disconnects the active MCP service and removes its tools from the tool loop. */
    public void disconnectMcp() {
        if (mcpService != null) {
            mcpService.close();
            mcpService = null;
        }
    }

    /**
     * Registers any object that has methods annotated with {@link Tool}.
     * Existing tools with the same name trigger an error.
     */
    public void addTool(SmartTool toolObject) {
        var old = replaceTool(toolObject);
        if (old != null) throw new RuntimeException("Tool " + old.getSpec().name() + " already registered ...");
    }
    
    public SmartToolExecutor replaceTool(SmartTool toolObject) {
        SmartToolExecutor result = null;
        synchronized (toolRegistrationLock) {
            for (Method method : toolObject.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    var spec = ToolSpecifications.toolSpecificationFrom(method);
                    var old = toolExecutors.put(spec.name(), new SmartToolExecutor(toolObject, method, spec, false));
                    if (old != null) {
                        result = old;
                        log.info("replaced tool " + spec);
                    } else {
                        log.debug("added tool   " + spec);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Registers a bundle-contributed tool. Contributed tools are <b>opt-in</b> unless
     * {@code defaultEnabled} is set: agents only see them when their {@code tools:} allowlist
     * names them (or the contribution opts into the default list). A name collision is rejected
     * atomically — a contribution must never shadow a built-in.
     */
    public void addContributedTool(SmartTool toolObject) {
        addContributedTool(toolObject, false, false);
    }

    public void addContributedTool(SmartTool toolObject, boolean defaultEnabled) {
        addContributedTool(toolObject, defaultEnabled, false);
    }

    public void addContributedTool(SmartTool toolObject, boolean defaultEnabled, boolean mcp) {
        var methods = new ArrayList<Method>();
        var specs = new ArrayList<ToolSpecification>();
        for (Method method : toolObject.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                methods.add(method);
                specs.add(ToolSpecifications.toolSpecificationFrom(method));
            }
        }
        synchronized (toolRegistrationLock) {
            for (var spec : specs) {
                if (toolExecutors.containsKey(spec.name())) {
                    throw new RuntimeException("Contributed tool '" + spec.name()
                            + "' collides with an existing tool — contribution skipped");
                }
            }
            for (int i = 0; i < methods.size(); i++) {
                var spec = specs.get(i);
                var old = toolExecutors.putIfAbsent(spec.name(), new SmartToolExecutor(
                        toolObject, methods.get(i), spec, true, defaultEnabled, mcp));
                if (old != null) {
                    throw new RuntimeException("Contributed tool '" + spec.name()
                            + "' collides with an existing tool — contribution skipped");
                }
            }
        }
    }

    /** Removes all tools registered from the given tool object. */
    public void removeTool(SmartTool toolObject) {
        removeTool(toolObject.getClass());
    }
    
    /** Removes all tools registered from the given tool object. */
    public void removeTool(Class<? extends SmartTool> toolClass) {
        for (Method method : toolClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                var spec = ToolSpecifications.toolSpecificationFrom(method);
                toolExecutors.remove(spec.name());
                log.debug("removed tool " + spec.name());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractTool> Optional<T> getTool(Class<T> clazz) {
        return toolExecutors.values().stream()
            .map(SmartToolExecutor::getTool)
            .filter(t -> t != null && clazz.isInstance(t))
            .map(t -> (T)t)
            .findFirst();
    }
}