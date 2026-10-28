package org.sterl.llmpeon.mention;

import java.util.Optional;
import java.util.Set;

/**
 * The active mention token at the caret: {@code @src/Fo} → trigger {@code @}, prefix
 * {@code src/Fo}, start offset and length in the input text.
 *
 * <p>Pure text logic — no SWT, no Eclipse — so the parsing rules are unit-testable.
 */
public record MentionToken(char trigger, String prefix, int start, int length) {

    /**
     * Finds the mention token that ends at {@code caret}. The trigger must be the first character
     * of the current whitespace-delimited token, so {@code mail@example.com} does not trigger.
     *
     * @return empty when the caret is not inside a recognized mention token
     */
    public static Optional<MentionToken> at(String text, int caret, Set<Character> triggers) {
        if (text == null || triggers == null || triggers.isEmpty()) return Optional.empty();
        if (caret < 0 || caret > text.length()) return Optional.empty();

        int start = caret;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        if (start == caret) return Optional.empty();

        char trigger = text.charAt(start);
        if (!triggers.contains(trigger)) return Optional.empty();

        return Optional.of(new MentionToken(
                trigger,
                text.substring(start + 1, caret),
                start,
                caret - start));
    }
}
