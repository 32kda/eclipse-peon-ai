package org.sterl.llmpeon.parts.contribution;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.parts.ProjectAware;
import org.sterl.llmpeon.tool.SmartTool;
import org.sterl.llmpeon.tool.ToolProvider;

/**
 * Reads {@code org.sterl.llmpeon.tool} contributions.
 *
 * <p>Contributed tools are opt-in (agents must allowlist them) and a name collision is rejected by
 * {@code ToolService.addContributedTool}. A broken provider is logged and skipped.</p>
 */
public class ToolProviderRegistry {

    public static final String EXTENSION_POINT_ID = PeonConstants.PLUGIN_ID + ".tool";

    private static final ILog LOG = Platform.getLog(ToolProviderRegistry.class);

    /** One contributed tool plus its opt-in policy. */
    public record ContributedTool(SmartTool tool, boolean defaultEnabled) {
    }

    public List<ContributedTool> load(Supplier<IProject> activeProject) {
        var result = new ArrayList<ContributedTool>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) return result;

        for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT_ID)) {
            try {
                Object contributed = element.createExecutableExtension("class");
                if (!(contributed instanceof ToolProvider provider)) {
                    LOG.warn("Ignoring toolProvider " + element.getContributor().getName()
                            + ": class does not implement ToolProvider");
                    continue;
                }
                if (contributed instanceof ProjectAware aware) {
                    aware.setProjectSupplier(activeProject);
                }
                boolean defaultEnabled = Boolean.parseBoolean(element.getAttribute("defaultEnabled"));
                var tools = provider.tools();
                if (tools == null) continue;
                for (var tool : tools) {
                    if (tool == null) continue;
                    if (tool instanceof ProjectAware aware) {
                        aware.setProjectSupplier(activeProject);
                    }
                    result.add(new ContributedTool(tool, defaultEnabled));
                }
            } catch (CoreException | LinkageError e) {
                LOG.error("Failed to create toolProvider from "
                        + element.getContributor().getName(), e);
            }
        }
        return result;
    }
}
