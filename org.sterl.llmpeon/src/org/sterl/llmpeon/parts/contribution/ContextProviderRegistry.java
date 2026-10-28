package org.sterl.llmpeon.parts.contribution;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.sterl.llmpeon.context.ContextProvider;
import org.sterl.llmpeon.parts.PeonConstants;

/**
 * Reads {@code org.sterl.llmpeon.contextProvider} contributions.
 *
 * <p>Providers are trusted plugin code; a broken one is logged and skipped, never allowed to break
 * the turn. The host caps each provider's output and discloses truncation.</p>
 */
public class ContextProviderRegistry {

    public static final String EXTENSION_POINT_ID = PeonConstants.PLUGIN_ID + ".contextProvider";

    private static final ILog LOG = Platform.getLog(ContextProviderRegistry.class);

    public List<ContextProvider> load() {
        var result = new ArrayList<ContextProvider>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) return result;

        for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT_ID)) {
            try {
                Object contributed = element.createExecutableExtension("class");
                if (contributed instanceof ContextProvider provider) {
                    result.add(provider);
                } else {
                    LOG.warn("Ignoring contextProvider " + element.getContributor().getName()
                            + ": class does not implement ContextProvider");
                }
            } catch (CoreException | LinkageError e) {
                LOG.error("Failed to create contextProvider from "
                        + element.getContributor().getName(), e);
            }
        }
        return result;
    }
}
