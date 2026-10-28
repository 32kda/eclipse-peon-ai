package org.sterl.llmpeon.parts.contribution;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.skill.SkillDefinition;
import org.sterl.llmpeon.skill.SkillProvider;

/**
 * Reads {@code org.sterl.llmpeon.skill} contributions.
 *
 * <p>A broken or unavailable provider is logged and skipped; it must never take the skill catalog
 * down. Contributed skills have the lowest precedence (config/project override by name).</p>
 */
public class SkillProviderRegistry {

    public static final String EXTENSION_POINT_ID = PeonConstants.PLUGIN_ID + ".skill";

    private static final ILog LOG = Platform.getLog(SkillProviderRegistry.class);

    public List<SkillDefinition> load() {
        var result = new ArrayList<SkillDefinition>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) return result;

        for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT_ID)) {
            try {
                Object contributed = element.createExecutableExtension("class");
                if (contributed instanceof SkillProvider provider) {
                    var skills = provider.skills();
                    if (skills != null) result.addAll(skills);
                } else {
                    LOG.warn("Ignoring skillProvider " + element.getContributor().getName()
                            + ": class does not implement SkillProvider");
                }
            } catch (CoreException | LinkageError e) {
                LOG.error("Failed to create skillProvider from "
                        + element.getContributor().getName(), e);
            }
        }
        return result;
    }
}
