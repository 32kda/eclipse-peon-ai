package org.sterl.llmpeon.parts.mention;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.sterl.llmpeon.mention.MentionCandidate;
import org.sterl.llmpeon.mention.MentionFilter;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.parts.shared.JdtUtil;

/**
 * Cached list of workspace files for the {@code @} picker.
 *
 * <p>Runs on a background {@link Job} so typing in the chat never blocks on a workspace walk. The
 * picker always reads the last completed snapshot; the next keystroke sees a fresh one. A TTL
 * throttles rebuilds. Derived and team-private resources are excluded — the same visibility rule
 * the read tools use.</p>
 *
 * <p>This is an Eclipse-side component (workspace API). Matching during the scan and the ranking
 * order live in core ({@code MentionFilter}) so it stays testable without a workbench. Only the
 * candidates that can make it into the result are materialized — a large workspace must not
 * allocate one candidate per indexed file on every keystroke.</p>
 */
public class WorkspaceFileIndex {

    /** One indexed file: workspace path ({@code /project/src/Foo.java}) + owning project. */
    public record FileEntry(String workspacePath, String project, String lowerPath) {
    }

    /** Upper bound — a picker is a convenience, not a guarantee; the agent can always read a path. */
    static final int MAX_FILES = 50_000;
    private static final long REFRESH_INTERVAL_MS = 10_000;

    private static final ILog LOG = Platform.getLog(WorkspaceFileIndex.class);

    private final Object refreshLock = new Object();
    private volatile List<FileEntry> snapshot = List.of();
    private volatile long lastRefreshStart = 0;
    private volatile boolean refreshScheduled = false;

    public WorkspaceFileIndex() {
        refreshAsync(); // warm up before the user types '@'
    }

    /** Last completed snapshot; schedules a background refresh when stale. */
    public List<FileEntry> snapshot() {
        refreshAsync();
        return snapshot;
    }

    /**
     * Finds at most {@code limit} mention candidates matching {@code prefix}, preferring files of
     * {@code preferredProject}. Matching happens during the single snapshot scan, so only the
     * best {@code limit} candidates per group are allocated. Ranking uses the shared
     * {@link MentionFilter} order.
     */
    public List<MentionCandidate> find(String prefix, String preferredProject, int limit) {
        var snap = snapshot();
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        int max = limit > 0 ? limit : Integer.MAX_VALUE;

        var preferred = new TopCandidates(max);
        var rest = new TopCandidates(max);
        for (var entry : snap) {
            int rank = MentionFilter.rankOfLower(entry.lowerPath(), needle);
            if (rank < 0) continue;
            if (preferredProject != null && preferredProject.equals(entry.project())) {
                preferred.offer(rank, entry);
            } else {
                rest.offer(rank, entry);
            }
        }

        var result = new ArrayList<MentionCandidate>();
        addUpTo(result, preferred.sorted(), max);
        addUpTo(result, rest.sorted(), max);
        return result;
    }

    private static void addUpTo(List<MentionCandidate> target, List<MentionCandidate> source, int max) {
        for (var candidate : source) {
            if (target.size() >= max) return;
            target.add(candidate);
        }
    }

    /** Test seam: rebuild synchronously on the calling thread and publish the result. */
    List<FileEntry> buildNow() {
        var built = build();
        snapshot = built;
        return built;
    }

    private void refreshAsync() {
        long now = System.currentTimeMillis();
        if (refreshScheduled) return;
        if (now - lastRefreshStart < REFRESH_INTERVAL_MS) return;
        synchronized (refreshLock) {
            if (refreshScheduled) return;
            if (System.currentTimeMillis() - lastRefreshStart < REFRESH_INTERVAL_MS) return;
            refreshScheduled = true;
            lastRefreshStart = System.currentTimeMillis();
        }
        Job.create("Index workspace files for @ mentions", monitor -> {
            try {
                snapshot = build();
            } catch (RuntimeException e) {
                LOG.error("Failed to index workspace files for @ mentions", e);
            } finally {
                synchronized (refreshLock) {
                    refreshScheduled = false;
                }
            }
            return Status.OK_STATUS;
        }).schedule();
    }

    private List<FileEntry> build() {
        var result = new ArrayList<FileEntry>();
        for (IProject project : EclipseUtil.openProjects()) {
            if (result.size() >= MAX_FILES) break;
            try {
                project.accept(resource -> {
                    if (result.size() >= MAX_FILES) return false;
                    if (resource.getType() == IResource.FILE) {
                        if (!resource.isDerived() && !resource.isTeamPrivateMember()) {
                            var path = JdtUtil.pathOf(resource);
                            if (path != null) {
                                result.add(new FileEntry(path, project.getName(),
                                        path.toLowerCase(Locale.ROOT)));
                            }
                        }
                    }
                    return true;
                });
            } catch (CoreException e) {
                LOG.error("Failed to index workspace project " + project.getName(), e);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Bounded top-N selection: keeps the best {@code max} entries by (rank, lower path) without
     * allocating a candidate for every match. The heap holds the worst candidate on top so a new
     * candidate only replaces it when strictly better.
     */
    private static final class TopCandidates {
        private static final Comparator<Ranked> WORST_FIRST = Comparator
                .comparingInt(Ranked::rank)
                .thenComparing(Ranked::lower)
                .reversed();

        private final int max;
        private final PriorityQueue<Ranked> heap;

        TopCandidates(int max) {
            this.max = max;
            this.heap = max == Integer.MAX_VALUE ? new PriorityQueue<>(WORST_FIRST)
                    : new PriorityQueue<>(Math.min(Math.max(1, max), 1024), WORST_FIRST);
        }

        void offer(int rank, FileEntry entry) {
            if (max <= 0) return;
            if (heap.size() < max) {
                heap.add(new Ranked(entry, rank));
                return;
            }
            var worst = heap.peek();
            if (rank < worst.rank()
                    || (rank == worst.rank() && entry.lowerPath().compareTo(worst.lower()) < 0)) {
                heap.poll();
                heap.add(new Ranked(entry, rank));
            }
        }

        List<MentionCandidate> sorted() {
            if (heap.isEmpty()) return List.of();
            var ranked = new ArrayList<Ranked>(heap);
            ranked.sort(Comparator.comparingInt(Ranked::rank).thenComparing(Ranked::lower));
            var result = new ArrayList<MentionCandidate>(ranked.size());
            for (var item : ranked) {
                result.add(new MentionCandidate(item.entry().workspacePath(), item.entry().project()));
            }
            return result;
        }

        private record Ranked(FileEntry entry, int rank) {
            String lower() {
                return entry.lowerPath();
            }
        }
    }
}
