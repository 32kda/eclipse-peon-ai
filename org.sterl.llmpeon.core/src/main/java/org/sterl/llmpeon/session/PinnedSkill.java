package org.sterl.llmpeon.session;

import java.util.Objects;

/** A skill pinned to one session (source tag is e.g. {@code config}, {@code project}, {@code bundle}). */
public record PinnedSkill(String name, String source) {

    public PinnedSkill {
        Objects.requireNonNull(name, "name");
        source = source == null ? "" : source;
    }

    public String key() {
        return source + ":" + name.toLowerCase(java.util.Locale.ROOT);
    }
}
