package org.sterl.llmpeon.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sterl.llmpeon.AgentService;
import org.sterl.llmpeon.StreamMock;
import org.sterl.llmpeon.agent.AgentDefinition;
import org.sterl.llmpeon.ai.ConfiguredChatModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.memory.ThreadSafeMemory;
import org.sterl.llmpeon.tool.tools.StartAgentTool;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

class StartAgentToolTest {

    private static AgentDefinition delegatable(String name) {
        return AgentDefinition.builder()
                .name(name)
                .description("specialist")
                .prompt("You are " + name)
                .delegatable(true)
                .build();
    }

    private static ConfiguredChatModel model(StreamMock stream) {
        var config = LlmConfig.builder().model("test").url("http://localhost:0").build();
        return new ConfiguredChatModel(config, stream.buildMock(request ->
                ChatResponse.builder().aiMessage(AiMessage.from("sub answer")).build()));
    }

    @Test
    void startsDelegatableAgent() {
        var stream = new StreamMock();
        var service = new AgentService(false, null, new ToolService(), model(stream));
        service.setContributedAgents(List.of(delegatable("Java Dev")));
        var tool = new StartAgentTool(service, () -> List.of());

        var result = tool.startAgent("Java Dev", "fix the bug");

        assertThat(result).contains("sub answer");
        assertThat(stream.getCallCount()).isEqualTo(1);
    }

    @Test
    void unknownAgentListsAvailableAgents() {
        var service = new AgentService(false, null, new ToolService(), model(new StreamMock()));
        service.setContributedAgents(List.of(delegatable("Java Dev")));
        var tool = new StartAgentTool(service, () -> List.of());

        var result = tool.startAgent("Nope", "do it");

        assertThat(result).contains("No delegatable agent 'Nope'").contains("Java Dev");
    }

    @Test
    void nonDelegatableAgentIsRejected() {
        var service = new AgentService(false, null, new ToolService(), model(new StreamMock()));
        service.setContributedAgents(List.of(AgentDefinition.builder().name("Chatty").prompt("x").build()));
        var tool = new StartAgentTool(service, () -> List.of());

        assertThat(tool.startAgent("Chatty", "do it")).contains("No delegatable agent 'Chatty'");
    }

    @Test
    void subAgentCannotStartAnotherSubAgent() {
        var stream = new StreamMock();
        var service = new AgentService(false, null, new ToolService(), model(stream));
        service.setContributedAgents(List.of(delegatable("Java Dev")));
        var tool = new StartAgentTool(service, () -> List.of());

        var parent = service.get("Java Dev").orElseThrow();
        var request = ToolLoopRequest.builder()
                .memory(new ThreadSafeMemory())
                .chatModel(model(new StreamMock()))
                .agent(parent)
                .build();
        tool.withToolRequest(request);

        assertThat(tool.startAgent("Java Dev", "do it"))
                .contains("Sub-agents cannot start other sub-agents");
        assertThat(stream.getCallCount()).isZero();
    }
}
