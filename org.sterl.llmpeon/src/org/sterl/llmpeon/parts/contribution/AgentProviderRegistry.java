package org.sterl.llmpeon.parts.contribution;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.sterl.llmpeon.agent.AgentDefinition;
import org.sterl.llmpeon.agent.AgentProvider;
import org.sterl.llmpeon.parts.PeonConstants;

/**
 * Reads {@code org.sterl.llmpeon.agent} contributions.
 *
 * <p>Contributed agents are materialized as {@code CustomAgent}s by the core {@code AgentService};
 * file agents and persistent built-ins win a name collision. A broken provider is logged and
 * skipped — a missing optional dependency must not break the agent list.</p>
 */
public class AgentProviderRegistry {

    public static final String EXTENSION_POINT_ID = PeonConstants.PLUGIN_ID + ".agent";

    private static final ILog LOG = Platform.getLog(AgentProviderRegistry.class);

    public List<AgentDefinition> load() {
        var result = new ArrayList<AgentDefinition>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) return result;

        for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT_ID)) {
            try {
                Object contributed = element.createExecutableExtension("class");
                if (contributed instanceof AgentProvider provider) {
                    var agents = provider.agents();
                    if (agents != null) result.addAll(agents);
                } else {
                    LOG.warn("Ignoring agentProvider " + element.getContributor().getName()
                            + ": class does not implement AgentProvider");
                }
            } catch (CoreException | LinkageError e) {
                LOG.error("Failed to create agentProvider from "
                        + element.getContributor().getName(), e);
            }
        }
        return result;
    }
}
