package org.sterl.llmpeon.parts.mention;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.sterl.llmpeon.mention.MentionCandidate;
import org.sterl.llmpeon.mention.MentionProvider;

/**
 * {@code @} mention provider: workspace files, selected project first.
 *
 * <p>The snapshot scan, prefix filter and bounded selection live in {@link WorkspaceFileIndex};
 * ranking order is the shared core {@code MentionFilter}. That keeps {@code @} behaviour identical
 * to any future provider and unit-testable without Eclipse, while avoiding a full candidate list
 * per keystroke on large workspaces.</p>
 */
public class WorkspaceFileMentionProvider implements MentionProvider {

    public static final char TRIGGER = '@';

    private final WorkspaceFileIndex index;
    /** Active project name at call time (pin-aware); null = no preference. */
    private final Supplier<String> activeProject;

    public WorkspaceFileMentionProvider(WorkspaceFileIndex index, Supplier<String> activeProject) {
        this.index = Objects.requireNonNull(index, "index");
        this.activeProject = activeProject;
    }

    @Override
    public char trigger() {
        return TRIGGER;
    }

    @Override
    public String id() {
        return "workspaceFiles";
    }

    @Override
    public List<MentionCandidate> find(String prefix, int limit) {
        String project = activeProject == null ? null : activeProject.get();
        return index.find(prefix, project, limit);
    }
}
