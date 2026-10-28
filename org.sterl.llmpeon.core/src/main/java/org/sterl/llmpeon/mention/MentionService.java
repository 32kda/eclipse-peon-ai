package org.sterl.llmpeon.mention;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Routes a trigger character to its {@link MentionProvider}s.
 *
 * <p>Built-in providers are registered first and win on a trigger collision; extension-contributed
 * providers follow in registry order. This keeps the default behaviour stable while still allowing
 * third parties to add triggers.
 */
public class MentionService {

    private final List<MentionProvider> providers;

    public MentionService(List<MentionProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /** All trigger characters that currently have a provider. */
    public Set<Character> triggers() {
        var result = new LinkedHashSet<Character>();
        for (var provider : providers) result.add(provider.trigger());
        return result;
    }

    public Optional<MentionProvider> providerFor(char trigger) {
        for (var provider : providers) {
            if (provider.trigger() == trigger) return Optional.of(provider);
        }
        return Optional.empty();
    }

    /**
     * Candidates for the trigger/prefix, or an empty list when no provider handles the trigger.
     */
    public List<MentionCandidate> find(char trigger, String prefix, int limit) {
        return providerFor(trigger)
                .map(provider -> provider.find(prefix == null ? "" : prefix, limit))
                .orElse(List.of());
    }

    public List<MentionProvider> providers() {
        return providers;
    }
}
