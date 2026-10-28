package org.sterl.llmpeon.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.tool.ToolService;

class AgentDefinitionTest {

    private static org.sterl.llmpeon.ai.ConfiguredChatModel model() {
        return LlmConfig.builder().model("test").url("http://localhost:0").build().build();
    }

    @Test
    void materializesFrontmatterAndBody() {
        var definition = AgentDefinition.builder()
                .name("Java Developer")
                .description("JDT specialist")
                .prompt("PROMPT BODY")
                .tools(List.of("eclipseRead", "readTypeSource"))
                .mcpServers(List.of("docs"))
                .readOnly(true)
                .model("qwen")
                .think("high")
                .delegatable(true)
                .metadata(Map.of("custom", "value"))
                .build();

        var file = definition.toPromptFile();
        var agent = new CustomAgent(file, model(), new ToolService());

        assertThat(file.getName()).isEqualTo("Java Developer");
        assertThat(file.getDescription()).isEqualTo("JDT specialist");
        assertThat(file.getBody()).isEqualTo("PROMPT BODY");
        assertThat(file.get("custom")).containsExactly("value");
        assertThat(file.get(CustomAgent.TOOLS)).containsExactly("eclipseRead", "readTypeSource");
        assertThat(agent.getMcpServers()).containsExactly("docs");
        assertThat(agent.isReadOnly()).isTrue();
        assertThat(agent.isTool()).isTrue();
        assertThat(agent.getDescription()).isEqualTo("JDT specialist");
        assertThat(agent.getTools()).containsExactly("eclipseRead", "readTypeSource");
    }

    @Test
    void defaultsToNonDelegatableAndAllTools() {
        var definition = AgentDefinition.builder()
                .name("Plain")
                .prompt("body")
                .build();

        var file = definition.toPromptFile();

        assertThat(file.isTrue(CustomAgent.AS_TOOL)).isFalse();
        assertThat(file.get(CustomAgent.TOOLS)).isNull();
        assertThat(file.get(CustomAgent.MCP_SERVERS)).isNull();
        assertThat(new CustomAgent(file, model(), new ToolService()).getMcpServers()).isNull();
    }
}
