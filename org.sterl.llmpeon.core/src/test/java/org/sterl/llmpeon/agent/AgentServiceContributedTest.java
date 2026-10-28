package org.sterl.llmpeon.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sterl.llmpeon.AgentService;
import org.sterl.llmpeon.ai.ConfiguredChatModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.tool.ToolService;

class AgentServiceContributedTest {

    private static ConfiguredChatModel model() {
        return LlmConfig.builder().model("test").url("http://localhost:0").build().build();
    }

    private static AgentDefinition definition(String name, String prompt) {
        return AgentDefinition.builder()
                .name(name)
                .description("contributed " + name)
                .prompt(prompt)
                .delegatable(true)
                .build();
    }

    @Test
    void contributedAgentIsLoaded() {
        var service = new AgentService(false, null, new ToolService(), model());
        service.setContributedAgents(List.of(definition("Java Dev", "BUNDLE BODY")));

        assertThat(service.getAgents()).extracting(AiAgent::getName).contains("Java Dev");
        assertThat(service.get("Java Dev")).get().satisfies(agent -> {
            assertThat(agent.isTool()).isTrue();
            assertThat(agent.getDescription()).isEqualTo("contributed Java Dev");
            assertThat(agent.getSystemPrompt()).contains("BUNDLE BODY");
        });
    }

    @Test
    void fileAgentOverridesContributedByName(@TempDir Path dir) throws Exception {
        var agentDir = Files.createDirectories(dir.resolve("Java Dev"));
        Files.writeString(agentDir.resolve("AGENT.md"), """
                ---
                name: Java Dev
                description: file version
                ---
                FILE BODY
                """);

        var service = new AgentService(false, dir, new ToolService(), model());
        service.setContributedAgents(List.of(definition("Java Dev", "BUNDLE BODY")));

        assertThat(service.get("Java Dev")).get()
                .satisfies(agent -> {
                    assertThat(agent.getSystemPrompt()).contains("FILE BODY");
                    assertThat(agent.getSystemPrompt()).doesNotContain("BUNDLE BODY");
                });
    }

    @Test
    void persistentBuiltInWinsOverContributed() {
        var service = new AgentService(true, null, new ToolService(), model());
        service.setContributedAgents(List.of(definition(AiDevAgent.NAME, "BUNDLE BODY")));

        assertThat(service.get(AiDevAgent.NAME)).get().isInstanceOf(AiDevAgent.class);
    }

    @Test
    void disabledAgentIsHiddenButStillFindable() {
        var service = new AgentService(false, null, new ToolService(), model());
        service.setContributedAgents(List.of(definition("Java Dev", "BODY")));

        service.setAgentEnabled("Java Dev", false);

        assertThat(service.getAgents()).extracting(AiAgent::getName).doesNotContain("Java Dev");
        assertThat(service.get("Java Dev")).isPresent();
    }

    @Test
    void contributedDefinitionsAreExposed() {
        var service = new AgentService(false, null, new ToolService(), model());
        service.setContributedAgents(List.of(definition("Java Dev", "BODY")));

        assertThat(service.getContributedAgentDefinitions()).hasSize(1);
    }
}
