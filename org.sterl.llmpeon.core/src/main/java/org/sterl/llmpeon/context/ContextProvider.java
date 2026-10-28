package org.sterl.llmpeon.context;

import java.util.List;

/**
 * Contributes automatic context items (e.g. PDE target-platform info, JDT project problems).
 *
 * <p>{@link Scope#STATIC} is baked into the system prompt and invalidated on refresh — only for
 * stable content (KV-cache friendly). {@link Scope#TURN} is injected into the turn context and
 * deduped by the existing context pipeline. Providers are trusted plugin code but must stay
 * conservative: implementations return an empty list when not applicable. The host caps every
 * provider at five items and discloses any truncation.</p>
 */
public interface ContextProvider {

    enum Scope {
        STATIC,
        TURN
    }

    default Scope scope() {
        return Scope.TURN;
    }

    default String label() {
        return getClass().getSimpleName();
    }

    /** Items to inject; {@code null}/empty = nothing. */
    List<ContextItem> provide(ContextRequest request);
}
