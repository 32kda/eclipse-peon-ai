package org.sterl.llmpeon.mention;

import java.util.List;

/**
 * Contributes completions for one trigger character (e.g. {@code @} for workspace files,
 * {@code #} for Java symbols).
 *
 * <p>Implementations live where their data lives: a pure-Java provider may sit in core, an
 * Eclipse provider in the plugin (registered via the {@code org.sterl.llmpeon.mentionProvider}
 * extension point). The interface itself is Eclipse-free so the input widget stays type-agnostic.
 *
 * <p>Contract: {@link #find(String, int)} returns candidates already matching {@code prefix}
 * (case-insensitive expected), ranked by usefulness, at most {@code limit} entries. A
 * {@code limit <= 0} means "no limit".
 */
public interface MentionProvider {

    /** The trigger character this provider handles, e.g. {@code '@'}. */
    char trigger();

    /** Stable id for diagnostics; defaults to the implementation class name. */
    default String id() {
        return getClass().getName();
    }

    /**
     * @param prefix the text typed after the trigger (may be empty, never null)
     * @param limit  maximum number of candidates; {@code <= 0} = no limit
     */
    List<MentionCandidate> find(String prefix, int limit);
}
