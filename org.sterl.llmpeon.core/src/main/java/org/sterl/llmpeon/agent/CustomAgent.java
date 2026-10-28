package org.sterl.llmpeon.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.sterl.llmpeon.ai.ConfiguredChatModel;
import org.sterl.llmpeon.ai.ThinkResolver;
import org.sterl.llmpeon.memory.AgentHistoryStore;
import org.sterl.llmpeon.memory.FileAgentHistoryStore;
import org.sterl.llmpeon.memory.ThreadSafeMemory;
import org.sterl.llmpeon.prompt.PromptLoader;
import org.sterl.llmpeon.prompt.PromptYmlParser;
import org.sterl.llmpeon.prompt.model.SimplePromptFile;
import org.sterl.llmpeon.tool.ToolPolicy;
import org.sterl.llmpeon.tool.ToolService;
import org.sterl.llmpeon.tool.component.SmartToolExecutor;
import org.sterl.llmpeon.tool.tools.StartAgentTool;

import lombok.Getter;
import lombok.Setter;

/**
 * Chat service backed by a user-defined {@link AgentPromptFile} ({@code AGENT.md}).
 *
 * <p>System prompt = shared default prompt + the agent's markdown body. Tools are restricted by the
 * agent's {@code tools} allowlist (absent = all) and, when {@code isReadOnly} is set, to non-edit
 * tools. An optional {@code model} in the frontmatter overrides the active model.</p>
 *
 * <p>The {@link AgentPromptFile} snapshot is replaceable so a config refresh can pick up edits;
 * within a single tool loop the filters stay constant, preserving the KV cache.</p>
 */
public class CustomAgent extends AbstractAgent {
    public static final String MODEL = "model";
    public static final String URL = "url";
    public static final String API_KEY = "api_key";
    public static final String EXTRA_BODY = "extra_body";
    public static final String THINK = "think";                       // canonical: one think level string (blank = unset)
    /** Legacy frontmatter key — read-compatible, migrated to {@link #THINK} on write.
     * @deprecated legacy alias, removed in a future major version — new files use {@link #THINK} (migrate-on-write). */
    @Deprecated
    public static final String THINK_ENABLED = "think_enabled";
    /** Legacy frontmatter key — read-compatible, migrated to {@link #THINK} on write.
     * @deprecated legacy alias, removed in a future major version — new files use {@link #THINK} (migrate-on-write). */
    @Deprecated
    public static final String THINK_SUPPORTED = "think_supported";
    /** Legacy frontmatter key — read-compatible, migrated to {@link #THINK} on write.
     * @deprecated legacy alias, removed in a future major version — new files use {@link #THINK} (migrate-on-write). */
    @Deprecated
    public static final String THINK_ON = "think_on_string";
    /** Legacy frontmatter key — read-compatible, migrated to {@link #THINK} on write.
     * @deprecated legacy alias, removed in a future major version — new files use {@link #THINK} (migrate-on-write). */
    @Deprecated
    public static final String THINK_OFF = "think_off_string";
    public static final String INCLUDE_DEFAULT = "include-default";
    public static final String TEMPERATURE = "temperature";
    public static final String TOOLS = "tools";
    public static final String MCP_SERVERS = "mcp-servers";
    public static final String READ_ONLY = "read-only";
    /** if an agent has a handover it gets the -> handover to button */
    public static final String HANDOVER = "handover";
    /** Marks the agent as a delegatable sub-agent (catalog + {@code startAgent}). */
    public static final String AS_TOOL = "as-tool";

    @Getter @Setter
    private volatile SimplePromptFile promptFile;

    public CustomAgent(SimplePromptFile promptFile,
            ConfiguredChatModel configuredModel,
            ToolService toolService) {
        super(configuredModel, toolService);
        this.promptFile = promptFile;
    }

    public CustomAgent(SimplePromptFile promptFile,
            ConfiguredChatModel configuredModel,
            ToolService toolService,
            Path historyStateDir) {
        this(promptFile, configuredModel, toolService, historyStateDir == null ? null
                : new FileAgentHistoryStore(historyFile(historyStateDir, promptFile.getName())));
    }

    public CustomAgent(SimplePromptFile promptFile,
            ConfiguredChatModel configuredModel,
            ToolService toolService,
            AgentHistoryStore historyStore) {
        super(configuredModel, toolService, new ThreadSafeMemory(historyStore));
        this.promptFile = promptFile;
    }

    /** The agent's single think value: canonical {@code think} wins; the legacy triple is
     *  read-compatible (an on-string is the more specific user intent and wins over a supported
     *  flag). {@code null} = unset. */
    private String resolveThink() {
        var think = promptFile.firstOrDefault(THINK, null);
        if (think != null) return think.trim();
        var on = promptFile.firstOrDefault(THINK_ON, null);
        var off = promptFile.firstOrDefault(THINK_OFF, null);
        var sup = promptFile.firstOrDefault(THINK_SUPPORTED, promptFile.firstOrDefault(THINK_ENABLED, null));
        if (on != null) return on.trim();
        if (off != null) return off.trim();
        if (sup != null) return promptFile.isTrue(THINK_SUPPORTED) || promptFile.isTrue(THINK_ENABLED)
                ? "true" : "false";
        return null;
    }

    /** Migrates legacy frontmatter keys to the canonical {@link #THINK}. Called before any write to
     * avoid eager file mutation on load — the file is only modified when a write operation occurs.
     * After a write the file contains only {@code think} (legacy keys removed). */
    public void migrateIfNeeded() {
        try {
            var legacyKeys = List.of(THINK_SUPPORTED, THINK_ENABLED, THINK_ON, THINK_OFF);
            boolean changed = false;
            var think = promptFile.firstOrDefault(THINK, null);
            if (think != null) {
                // canonical think wins — shadowed legacy keys are dropped
                for (var key : legacyKeys) {
                    if (promptFile.firstOrDefault(key, null) != null) {
                        promptFile.remove(key);
                        changed = true;
                    }
                }
            } else if (legacyKeys.stream().anyMatch(key -> promptFile.firstOrDefault(key, null) != null)) {
                var resolved = resolveThink();
                if (resolved != null) {
                    promptFile.setValue(THINK, resolved);
                }
                for (var key : legacyKeys) {
                    promptFile.remove(key);
                }
                changed = true;
            }
            if (changed) {
                promptFile.save();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to migrate legacy frontmatter for agent " + getName(), e);
        }
    }

    public SimplePromptFile getAgentFile() {
        return promptFile;
    }
    
    @Override
    public String getName() {
        return promptFile.getName();
    }

    @Override
    public String getDescription() {
        return promptFile.getDescription();
    }

    @Override
    public boolean isTool() {
        return promptFile.isTrue(AS_TOOL);
    }

    @Override
    public String getSystemPrompt() {
        if (promptFile.isTrue(INCLUDE_DEFAULT)) {
            return PromptLoader.withDefault(promptFile.getBody());
        }
        return promptFile.getBody();
    }

    @Override
    public boolean isThinkSupported() {
        return !ThinkResolver.isOff(resolveThink());
    }

    @Override
    public org.sterl.llmpeon.ai.AgentConfig getConfig() {
        var rec = new org.sterl.llmpeon.ai.AgentModelConfig(
                promptFile.firstOrDefault(URL, null),
                promptFile.firstOrDefault(API_KEY, null),
                promptFile.firstOrDefault(MODEL, null),
                resolveThink(),
                promptFile.firstOrDefault(EXTRA_BODY, null),
                promptFile.firstOrDefault(TEMPERATURE, null));
        return configuredModel.getConfig().customAgentConfig(rec, getName());
    }

    @Override
    public String getAgentModelName() {
        return promptFile.firstOrDefault(MODEL, null);
    }
    /**
     * Pins the model to this agent only (not the shared config). {@link PeonAiService} persists the
     * change back into the {@code AGENT.md} frontmatter.
     *
     * @return <code>true</code> if changed
     */
    @Override
    public boolean setAgentModelName(String modelName) {
        if (modelName == null) return false;
        if (Objects.equals(modelName, getAgentModelName())) return false;
        migrateIfNeeded();
        promptFile.setValue(MODEL, modelName);
        try {
            promptFile.save();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save " + modelName + " for agent " + getName(), e);
        }
        return true;
    }
    
    public String handoverTo() {
        return promptFile.firstOrDefault(HANDOVER, null);
    }
    
    @Override
    public boolean isReadOnly() {
        return promptFile.isTrue(READ_ONLY);
    }
    
    @Override
    public List<String> getTools() {
        return promptFile.get(TOOLS);
    }

    @Override
    public List<String> getMcpServers() {
        return promptFile.get(MCP_SERVERS);
    }
    public void setTools(List<String> values) {
        this.promptFile.set(TOOLS, values);
    }

    @Override
    protected Predicate<SmartToolExecutor> getToolFilter() {
        var allowlist = PromptYmlParser.toolAllowlist(getTools());
        return exec -> {
            // absent tools: = built-ins plus contributed tools that opted into the default list
            boolean nameAllowed = allowlist == null
                    ? (!exec.isContributed() || exec.isDefaultEnabled())
                    : ToolPolicy.enables(allowlist, exec.getSpec().name());
            return nameAllowed
                    && (!isReadOnly() || !exec.getTool().isEditTool())
                    // a sub-agent must not start further sub-agents (no recursion, no tool pollution)
                    && !(isTool() && exec.getTool() instanceof StartAgentTool);
        };
    }

    @Override
    protected Predicate<String> getToolNameFilter() {
        return this::allowed;
    }

    /** @return {@code true} if the tool name passes this agent's {@code tools} allowlist.
     * An absent {@code tools:} field (null) means all tools; an empty list means none. */
    private boolean allowed(String toolName) {
        var allowlist = PromptYmlParser.toolAllowlist(getTools());
        return allowlist == null || ToolPolicy.enables(allowlist, toolName);
    }
    
    @Override
    public String toString() {
        return getClass().getSimpleName() + "[name=" + getName() + ", model: " + getAgentModelName() + "]";
    }

}
