package org.sterl.llmpeon.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.sterl.llmpeon.prompt.model.SimplePromptFile;

import lombok.Builder;

/**
 * An agent contributed by an extension (see {@link AgentProvider}).
 *
 * <p>Materialized as the existing {@link CustomAgent} via a synthetic {@link SimplePromptFile}, so
 * per-agent tool allowlists, read-only mode, model overrides and prompt caching stay unchanged.</p>
 *
 * <p>{@code delegatable = true} marks the agent as a sub-agent: it appears in the agent catalog and
 * can be started with {@code startAgent}. The description is the only text injected into the
 * parent's prompt.</p>
 */
@Builder
public record AgentDefinition(
        String name,
        String description,
        String prompt,
        List<String> tools,
        List<String> mcpServers,
        boolean readOnly,
        boolean includeDefault,
        String model,
        String think,
        String handover,
        boolean delegatable,
        Map<String, String> metadata) {

    public AgentDefinition {
        Objects.requireNonNull(name, "name");
        prompt = prompt == null ? "" : prompt;
        tools = tools == null ? List.of() : List.copyOf(tools);
        mcpServers = mcpServers == null ? null : List.copyOf(mcpServers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Adapts to the file-based model — no disk file, all behaviour via frontmatter keys. */
    public SimplePromptFile toPromptFile() {
        var frontmatter = new LinkedHashMap<String, List<String>>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) frontmatter.put(key, List.of(value));
        });
        frontmatter.put("name", List.of(name));
        if (description != null && !description.isBlank()) {
            frontmatter.put("description", List.of(description));
        }
        if (!tools.isEmpty()) frontmatter.put("tools", tools);
        if (mcpServers != null) frontmatter.put(CustomAgent.MCP_SERVERS, mcpServers);
        if (readOnly) frontmatter.put("read-only", List.of("true"));
        if (includeDefault) frontmatter.put(CustomAgent.INCLUDE_DEFAULT, List.of("true"));
        if (model != null && !model.isBlank()) frontmatter.put("model", List.of(model));
        if (think != null && !think.isBlank()) frontmatter.put("think", List.of(think));
        if (handover != null && !handover.isBlank()) frontmatter.put("handover", List.of(handover));
        if (delegatable) frontmatter.put(CustomAgent.AS_TOOL, List.of("true"));
        return new SimplePromptFile(frontmatter, prompt, null);
    }
}
