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
import org.sterl.llmpeon.mcp.IMcpServer;
import org.sterl.llmpeon.mcp.IMcpTool;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.parts.ProjectAware;

/**
 * Reads {@code org.sterl.llmpeon.mcpServer} contributions — in-process Java/Eclipse MCP servers.
 *
 * <p>Tools are registered as contributed tools (opt-in unless {@link IMcpServer#defaultEnabled()})
 * and marked as MCP for the header tool popup. A broken server/tool is logged and skipped.</p>
 */
public class McpServerProviderRegistry {

    public static final String EXTENSION_POINT_ID = PeonConstants.PLUGIN_ID + ".mcpServer";

    private static final ILog LOG = Platform.getLog(McpServerProviderRegistry.class);

    public record Registration(IMcpServer server, List<IMcpTool> tools) {
    }

    public List<Registration> load(Supplier<IProject> activeProject) {
        var result = new ArrayList<Registration>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) return result;

        for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT_ID)) {
            try {
                Object contributed = element.createExecutableExtension("class");
                if (!(contributed instanceof IMcpServer server)) {
                    LOG.warn("Ignoring mcpServer " + element.getContributor().getName()
                            + ": class does not implement IMcpServer");
                    continue;
                }
                if (server instanceof ProjectAware aware) {
                    aware.setProjectSupplier(activeProject);
                }
                var tools = new ArrayList<IMcpTool>();
                if (server.tools() != null) {
                    for (var tool : server.tools()) {
                        if (tool == null) continue;
                        if (tool instanceof ProjectAware aware) {
                            aware.setProjectSupplier(activeProject);
                        }
                        tools.add(tool);
                    }
                }
                result.add(new Registration(server, List.copyOf(tools)));
            } catch (CoreException | LinkageError e) {
                LOG.error("Failed to create mcpServer from "
                        + element.getContributor().getName(), e);
            }
        }
        return result;
    }
}
