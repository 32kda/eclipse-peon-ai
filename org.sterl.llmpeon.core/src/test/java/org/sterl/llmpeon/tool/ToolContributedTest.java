package org.sterl.llmpeon.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sterl.llmpeon.agent.AiDevAgent;
import org.sterl.llmpeon.agent.CustomAgent;
import org.sterl.llmpeon.ai.ConfiguredChatModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.prompt.model.SimplePromptFile;

import dev.langchain4j.agent.tool.Tool;

class ToolContributedTest {

    /** Minimal contributed tool — one @Tool method. */
    static class ProbeTool implements SmartTool {
        @Tool(name = "contributedProbe", value = "Probe tool")
        public String probe() {
            return "ok";
        }

        @Override
        public void withToolRequest(ToolLoopRequest request) {
        }
    }

    private static ConfiguredChatModel model() {
        return LlmConfig.builder().model("test").url("http://localhost:0").build().build();
    }

    private static CustomAgent agent(ToolService service, List<String> tools) {
        var frontmatter = new java.util.LinkedHashMap<String, List<String>>();
        frontmatter.put("name", List.of("ProbeAgent"));
        if (tools != null) frontmatter.put("tools", tools);
        var promptFile = new SimplePromptFile(frontmatter, "body", null);
        return new CustomAgent(promptFile, model(), service);
    }

    @Test
    void contributedToolsAreOptIn() {
        var service = new ToolService(false);
        service.addContributedTool(new ProbeTool());
        var executor = service.getExecutor("contributedProbe");

        assertThat(executor).isNotNull();
        assertThat(executor.isContributed()).isTrue();

        // absent tools: = built-ins only -> contributed tool hidden
        assertThat(agent(service, null).isToolActive(executor)).isFalse();
        // explicit allowlist opts in
        assertThat(agent(service, List.of("contributedProbe")).isToolActive(executor)).isTrue();
        // built-in agents do not auto-include contributed tools
        assertThat(new AiDevAgent(model(), service).isToolActive(executor)).isFalse();
    }

    /** Minimal in-process MCP tool. */
    static class ProbeMcpTool implements org.sterl.llmpeon.mcp.IMcpTool {
        @Tool(name = "probeMcp", value = "Probe MCP tool")
        public String probe() {
            return "mcp-ok";
        }

        @Override
        public void withToolRequest(ToolLoopRequest request) {
        }
    }

    static class ProbeMcpServer implements org.sterl.llmpeon.mcp.IMcpServer {
        @Override
        public String name() {
            return "probe-server";
        }

        @Override
        public List<org.sterl.llmpeon.mcp.IMcpTool> tools() {
            return List.of(new ProbeMcpTool());
        }
    }

    @Test
    void defaultEnabledContributionIsIncludedButStillMarked() {
        var service = new ToolService(false);
        service.addContributedTool(new ProbeTool(), true, true);
        var executor = service.getExecutor("contributedProbe");

        assertThat(executor.isDefaultEnabled()).isTrue();
        assertThat(executor.isMcp()).isTrue();
        // default-enabled contributed tool is in the normal agent list
        assertThat(new AiDevAgent(model(), service).isToolActive(executor)).isTrue();
        assertThat(agent(service, null).isToolActive(executor)).isTrue();
    }

    @Test
    void inProcessMcpServerToolsAreOptInByDefault() {
        var service = new ToolService(false);
        var server = new ProbeMcpServer();
        for (var tool : server.tools()) {
            service.addContributedTool(tool, server.defaultEnabled(), true);
        }
        var executor = service.getExecutor("probeMcp");

        assertThat(executor.isMcp()).isTrue();
        assertThat(executor.isDefaultEnabled()).isFalse();
        assertThat(new AiDevAgent(model(), service).isToolActive(executor)).isFalse();
        assertThat(agent(service, List.of("probeMcp")).isToolActive(executor)).isTrue();
    }

    @Test
    void builtInToolsAreStillAllowedForAbsentAllowlist() {
        var service = new ToolService(false);
        service.addTool(new ProbeTool()); // built-in registration
        var executor = service.getExecutor("contributedProbe");

        assertThat(executor.isContributed()).isFalse();
        assertThat(agent(service, null).isToolActive(executor)).isTrue();
    }

    @Test
    void contributedToolCollisionIsRejected() {
        var service = new ToolService(false);
        service.addContributedTool(new ProbeTool());

        assertThatThrownBy(() -> service.addContributedTool(new ProbeTool()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("collides");
    }
}
