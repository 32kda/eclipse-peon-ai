package org.sterl.llmpeon.context;

/**
 * Project/agent information handed to a {@link ContextProvider}. Pure data (strings) so core stays
 * Eclipse-free; the plugin fills it from the active project.
 */
public record ContextRequest(String projectName, String projectPath, String agentName) {
}
