package org.sterl.llmpeon.parts.mention;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.sterl.llmpeon.mention.MentionProvider;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.parts.ProjectAware;

/**
 * Reads {@code org.sterl.llmpeon.mentionProvider} contributions.
 *
 * <p>Inline providers ({@code <provider>}) are appended after the built-in ones, so a built-in
 * trigger (e.g. {@code @}) always wins a collision. Dialog providers ({@code <dialogProvider>}) are
 * separate — they open a native picker (e.g. the JDT type dialog) instead of the inline popup.
 * A broken contribution is logged and skipped; it must never take the picker down.</p>
 */
public class MentionProviderRegistry {

    public static final String EXTENSION_POINT_ID = PeonConstants.PLUGIN_ID + ".mentionProvider";

    private static final ILog LOG = Platform.getLog(MentionProviderRegistry.class);

    /** Contributions split by interaction model. */
    public record Contributions(List<MentionProvider> inline, List<DialogMentionProvider> dialogs) {
        public static Contributions empty() {
            return new Contributions(List.of(), List.of());
        }
    }

    public Contributions load(java.util.function.Supplier<org.eclipse.core.resources.IProject> activeProject) {
        var inline = new ArrayList<MentionProvider>();
        var dialogs = new ArrayList<DialogMentionProvider>();

        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) return Contributions.empty();

        for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT_ID)) {
            try {
                Object contributed = element.createExecutableExtension("class");
                if (contributed instanceof ProjectAware aware) {
                    aware.setProjectSupplier(activeProject);
                }
                switch (element.getName()) {
                case "provider" -> {
                    if (contributed instanceof MentionProvider provider) inline.add(provider);
                    else logWrongType(element, "MentionProvider");
                }
                case "dialogProvider" -> {
                    if (contributed instanceof DialogMentionProvider provider) dialogs.add(provider);
                    else logWrongType(element, "DialogMentionProvider");
                }
                default -> LOG.warn("Ignoring unknown mentionProvider element '" + element.getName()
                        + "' from " + element.getContributor().getName());
                }
            } catch (CoreException | LinkageError e) {
                // LinkageError covers an optional dependency (org.eclipse.jdt.ui) that is not installed.
                LOG.error("Failed to create mentionProvider from "
                        + element.getContributor().getName(), e);
            }
        }
        return new Contributions(List.copyOf(inline), List.copyOf(dialogs));
    }

    private static void logWrongType(IConfigurationElement element, String expected) {
        LOG.warn("Ignoring mentionProvider contribution " + element.getContributor().getName()
                + ": class does not implement " + expected);
    }
}
