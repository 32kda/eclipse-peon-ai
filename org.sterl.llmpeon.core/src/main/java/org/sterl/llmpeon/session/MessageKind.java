package org.sterl.llmpeon.session;

/**
 * Display/persistence kind of a transcript message.
 *
 * <p>{@link #REMINDER} is still a regular {@code UserMessage} for the LLM — only the UI and the
 * session transcript render it differently. The model never sees this enum.</p>
 */
public enum MessageKind {
    CHAT,
    REMINDER
}
