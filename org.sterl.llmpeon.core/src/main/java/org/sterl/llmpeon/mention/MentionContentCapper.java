package org.sterl.llmpeon.mention;

import org.sterl.llmpeon.shared.FileUtils;

/**
 * Hard cap for a mention attachment. Keeps the context from being blown up accidentally by a
 * dropped or typed reference. The caller discloses {@code truncated}/{@code totalLines}; nothing is
 * silently dropped.
 */
public final class MentionContentCapper {

    /** @param content the capped text; {@code truncated} = more lines existed */
    public record Capped(String content, boolean truncated, int shownLines, int totalLines) {
    }

    private MentionContentCapper() {
    }

    /**
     * Caps {@code content} to {@code maxLines}. A {@code maxLines <= 0} means "no cap".
     */
    public static Capped cap(String content, int maxLines) {
        if (content == null) return new Capped("", false, 0, 0);

        String lineEnding = FileUtils.dominantLineEnding(content);
        String[] lines = content.split(lineEnding, -1);

        if (maxLines <= 0 || lines.length <= maxLines) {
            return new Capped(content, false, lines.length, lines.length);
        }

        var sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) sb.append(lineEnding);
            sb.append(lines[i]);
        }
        return new Capped(sb.toString(), true, maxLines, lines.length);
    }
}
