package org.sterl.llmpeon.mention;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds {@code @path} / {@code #Type} references in a chat message.
 *
 * <p>A trigger only starts a reference at the beginning of a whitespace-delimited token, so
 * {@code mail@example.com} is not a reference. References are deduplicated by trigger+target —
 * the same file mentioned twice is attached once.</p>
 *
 * <p>Pure text logic, no Eclipse types; the resolver that actually reads the file/type lives in the
 * plugin.</p>
 */
public final class MentionReferenceParser {

    private MentionReferenceParser() {
    }

    public static List<MentionReference> parse(String text, Set<Character> triggers) {
        if (text == null || text.isEmpty() || triggers == null || triggers.isEmpty()) return List.of();

        var result = new ArrayList<MentionReference>();
        var seen = new HashSet<String>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean startsToken = i == 0 || Character.isWhitespace(text.charAt(i - 1));
            if (!triggers.contains(c) || !startsToken) {
                i++;
                continue;
            }

            int start = i;
            int end = i + 1;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            String target = cleanTarget(c, text.substring(i + 1, end));
            if (!target.isEmpty() && seen.add(c + ":" + target)) {
                result.add(new MentionReference(c, target, start, end - start));
            }
            i = end;
        }
        return result;
    }

    private static String cleanTarget(char trigger, String raw) {
        if (raw == null || raw.isEmpty()) return "";

        if (trigger == '#') {
            // Java FQN: letters/digits/_/$ and dots. Stop at punctuation like ')' or ','.
            int end = 0;
            while (end < raw.length()) {
                char c = raw.charAt(end);
                if (Character.isJavaIdentifierPart(c) || c == '$' || c == '.') end++;
                else break;
            }
            var value = raw.substring(0, end);
            while (value.endsWith(".")) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        }

        // Path: strip trailing sentence punctuation. A trailing dot is punctuation; the dot of a
        // file extension is never trailing because letters follow it.
        int end = raw.length();
        while (end > 0 && ".,;!?)]}\"'".indexOf(raw.charAt(end - 1)) >= 0) {
            end--;
        }
        return raw.substring(0, end);
    }
}
