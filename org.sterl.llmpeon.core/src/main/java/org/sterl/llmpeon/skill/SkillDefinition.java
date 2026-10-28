package org.sterl.llmpeon.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A skill contributed by an extension (see {@link SkillProvider}).
 *
 * <p>The description is the catalog entry injected into the prompt; the body is only loaded when
 * the model calls {@code skillRead}. That keeps skill bodies (tokens) out of every request.</p>
 */
public record SkillDefinition(String name, String description, String body, Map<String, String> metadata) {

    public SkillDefinition {
        Objects.requireNonNull(name, "name");
        body = body == null ? "" : body;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static SkillDefinition of(String name, String description, String body) {
        return new SkillDefinition(name, description, body, Map.of());
    }

    /** Adapts the definition to the existing skill model (no disk dir → body-only skill). */
    public SkillPromptFile toPromptFile() {
        var frontmatter = new LinkedHashMap<String, List<String>>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) frontmatter.put(key, List.of(value));
        });
        frontmatter.put("name", List.of(name));
        if (description != null && !description.isBlank()) {
            frontmatter.put("description", List.of(description));
        }
        var skill = new SkillPromptFile(frontmatter, body, null);
        skill.setSource(SkillSource.EXTENSION);
        return skill;
    }
}
