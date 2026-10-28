package org.sterl.llmpeon.mention;

/**
 * One reference found in an outgoing chat message, e.g. {@code @/project/src/Foo.java} or
 * {@code #org.example.Foo}. Offsets are relative to the message text.
 */
public record MentionReference(char trigger, String target, int start, int length) {
}
