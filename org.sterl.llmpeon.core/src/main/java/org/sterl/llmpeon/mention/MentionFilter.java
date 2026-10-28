package org.sterl.llmpeon.mention;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic ranking/filtering for mention candidates — one implementation shared by every
 * provider, so {@code @} paths and future {@code #} symbols behave the same.
 *
 * <p>Ranking: a basename match beats a path match, which beats a substring match. Within one rank
 * the order is alphabetical (case-insensitive), so the popup is stable while typing.
 */
public final class MentionFilter {

    private MentionFilter() {
    }

    public static List<MentionCandidate> find(List<MentionCandidate> candidates, String prefix, int limit) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        var ranked = new ArrayList<Ranked>(candidates.size());

        for (var candidate : candidates) {
            var text = candidate.insertText();
            if (text == null) continue;
            var lower = text.toLowerCase(Locale.ROOT);
            int rank = rank(lower, needle);
            if (rank >= 0) ranked.add(new Ranked(candidate, rank, lower));
        }

        ranked.sort(Comparator
                .comparingInt(Ranked::rank)
                .thenComparing(Ranked::lower));

        int max = limit > 0 ? Math.min(limit, ranked.size()) : ranked.size();
        var result = new ArrayList<MentionCandidate>(max);
        for (int i = 0; i < max; i++) result.add(ranked.get(i).candidate());
        return result;
    }

    /**
     * Rank of an already lower-cased text against an already lower-cased needle, using the same
     * order as {@link #find(List, String, int)}. Providers that filter large sources at the
     * source use this to avoid materializing a candidate per scanned item.
     *
     * @return rank {@code 0..2}, or {@code -1} when the text does not match
     */
    public static int rankOfLower(String lowerText, String lowerNeedle) {
        return rank(lowerText == null ? "" : lowerText, lowerNeedle == null ? "" : lowerNeedle);
    }

    private static int rank(String text, String needle) {
        if (needle.isEmpty()) return 0;
        if (basename(text).startsWith(needle)) return 0;
        if (text.startsWith(needle)) return 1;
        if (text.contains(needle)) return 2;
        return -1;
    }

    private static String basename(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private record Ranked(MentionCandidate candidate, int rank, String lower) {
    }
}
