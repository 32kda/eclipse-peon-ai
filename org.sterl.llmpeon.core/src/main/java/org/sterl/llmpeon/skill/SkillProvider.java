package org.sterl.llmpeon.skill;

import java.util.List;

/**
 * Contributes skills from an Eclipse extension (no files needed).
 *
 * <p>Implementations live at the plugin edge (they may load the body from their own bundle); the
 * interface is Eclipse-free so {@link SkillService} stays testable in core.</p>
 */
public interface SkillProvider {

    /** All skills this provider contributes; may be empty, never null. */
    List<SkillDefinition> skills();
}
