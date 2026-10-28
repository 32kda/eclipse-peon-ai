package org.sterl.llmpeon.agent;

import java.nio.file.Path;

import org.sterl.llmpeon.ai.ConfiguredChatModel;
import org.sterl.llmpeon.memory.AgentHistoryStore;
import org.sterl.llmpeon.memory.FileAgentHistoryStore;
import org.sterl.llmpeon.memory.ThreadSafeMemory;
import org.sterl.llmpeon.prompt.PromptLoader;
import org.sterl.llmpeon.tool.ToolService;

public class AiDevAgent extends AbstractAgent {

    public static final String NAME = "Peon-Dev";
    private static final String BASE_PROMPT = PromptLoader.loadWithDefault("developer.md");

    public AiDevAgent(ConfiguredChatModel configuredModel,
            ToolService toolService) {
        super(configuredModel, toolService);
    }

    /** RAM-only Dev slave with an earlier compaction budget (see {@link AbstractAgent#compactAfterTokens()}). */
    public AiDevAgent(ConfiguredChatModel configuredModel, ToolService toolService, double compactFactor) {
        super(configuredModel, toolService, new ThreadSafeMemory(), compactFactor);
    }

    public AiDevAgent(ConfiguredChatModel configuredModel,
            ToolService toolService,
            Path historyStateDir) {
        this(configuredModel, toolService, historyStateDir == null ? null
                : new FileAgentHistoryStore(historyFile(historyStateDir, NAME)));
    }

    public AiDevAgent(ConfiguredChatModel configuredModel, ToolService toolService,
            AgentHistoryStore historyStore) {
        super(configuredModel, toolService, new ThreadSafeMemory(historyStore));
    }

    /** Durable Dev slave with an earlier compaction budget. */
    public AiDevAgent(ConfiguredChatModel configuredModel, ToolService toolService,
            AgentHistoryStore historyStore, double compactFactor) {
        super(configuredModel, toolService, new ThreadSafeMemory(historyStore), compactFactor);
    }

    @Override
    public String getSystemPrompt() {
        return BASE_PROMPT;
    }

    @Override
    public String getAgentModelName() {
        return configuredModel.getConfig().getModel();
    }
    
    @Override
    public boolean setAgentModelName(String modelName) {
        return this.configuredModel.withModel(modelName);
    }

    @Override
    public String getName() {
        return NAME;
    }
}
