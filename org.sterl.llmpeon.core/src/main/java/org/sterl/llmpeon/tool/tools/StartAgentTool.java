package org.sterl.llmpeon.tool.tools;

import java.util.List;
import java.util.function.Supplier;

import org.sterl.llmpeon.AgentService;
import org.sterl.llmpeon.agent.AiAgent;
import org.sterl.llmpeon.context.ContextItem;
import org.sterl.llmpeon.shared.ArgsUtil;
import org.sterl.llmpeon.shared.CallStats;
import org.sterl.llmpeon.shared.StringUtil;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Starts a specialized sub-agent by name — the generic counterpart to Jon's hardwired delegate
 * tools.
 *
 * <p>Only agents marked delegatable ({@link AiAgent#isTool()} / in the agent catalog) can be
 * started. The call is synchronous (same proven pattern as
 * {@link org.sterl.llmpeon.poagent.tools.PoDelegateTool}); async is an optimization, not a
 * prerequisite. A sub-agent cannot start another sub-agent — that keeps the delegation graph a
 * tree and prevents tool/context recursion.</p>
 */
public class StartAgentTool extends AbstractTool {

    public static final String NAME = "startAgent";

    private final AgentService agentService;
    private final Supplier<List<ContextItem>> turnContext;

    public StartAgentTool(AgentService agentService, Supplier<List<ContextItem>> turnContext) {
        this.agentService = agentService;
        this.turnContext = turnContext;
    }

    @Tool(name = NAME, value = "Start a specialized sub-agent by name (see the agent catalog in your context). "
            + "The sub-agent works in its own context with its own curated tools and returns its final answer. "
            + "Use it to delegate focused Java or PDE work instead of loading every tool into one agent.")
    public String startAgent(
            @P(name = "agent", description = "agent name from the agent catalog") String agentName,
            @P(name = "prompt", description = "self-contained task for the sub-agent") String prompt) {
        ArgsUtil.requireNonBlank(agentName, "agent");
        ArgsUtil.requireNonBlank(prompt, "prompt");

        var current = request == null ? null : request.getAgent();
        if (current != null && current.isTool()) {
            return "Sub-agents cannot start other sub-agents. Do the work yourself or return to the parent.";
        }

        var target = agentService.getAgents().stream()
                .filter(AiAgent::isTool)
                .filter(agent -> agent.getName().equalsIgnoreCase(agentName))
                .findFirst();
        if (target.isEmpty()) {
            return "No delegatable agent '" + agentName + "'. Available: " + availableAgents();
        }

        AiAgent agent = target.get();
        if (current == agent) {
            return "An agent cannot start itself. Use a different agent or answer directly.";
        }

        agent.setTurnContextSupplier(turnContext);
        onTool(agent.getName() + " start:" + System.lineSeparator() + prompt);
        var stats = CallStats.start();
        try {
            var response = agent.call(prompt, monitor);
            var statsLine = agent.getName() + " done. " + stats.suffix();
            var answer = response == null ? null : response.aiMessage().text();
            onTool(statsLine);
            return StringUtil.hasValue(answer)
                    ? answer + System.lineSeparator() + statsLine
                    : agent.getName() + " returned no result " + statsLine;
        } catch (Exception e) {
            onProblem(agent.getName() + " failed: " + e.getMessage());
            return "Failed to start " + agent.getName() + ": " + e.getMessage();
        }
    }

    private String availableAgents() {
        var names = agentService.getAgents().stream()
                .filter(AiAgent::isTool)
                .map(AiAgent::getName)
                .toList();
        return names.isEmpty() ? "(none contributed)" : String.join(", ", names);
    }
}
