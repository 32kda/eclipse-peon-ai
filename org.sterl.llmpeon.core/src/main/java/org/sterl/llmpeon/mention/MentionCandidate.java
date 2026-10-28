package org.sterl.llmpeon.mention;

import java.util.Objects;

/**
 * One selectable mention completion.
 *
 * @param insertText what is inserted after the trigger character (e.g. the workspace path for
 *        {@code @}); never null
 * @param detail     short right-column hint (e.g. the project name or candidate kind); may be null
 */
public record MentionCandidate(String insertText, String detail) {

    public MentionCandidate {
        Objects.requireNonNull(insertText, "insertText");
    }

    public static MentionCandidate of(String insertText) {
        return new MentionCandidate(insertText, null);
    }
}
