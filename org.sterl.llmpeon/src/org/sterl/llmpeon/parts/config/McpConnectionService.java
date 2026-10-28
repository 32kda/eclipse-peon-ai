package org.sterl.llmpeon.parts.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.core.runtime.jobs.Job;
import org.sterl.llmpeon.mcp.McpServerConfig;
import org.sterl.llmpeon.mcp.McpToolGateway;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.tool.ToolService;

/**
 * Manages the MCP server connection lifecycle for the view.
 * Reads configuration from preferences, schedules background jobs for connecting,
 * and notifies the caller of state changes via a callback.
 */
public class McpConnectionService {

    private final ToolService toolService;
    private final Consumer<Boolean> onStateChange;
    /** Non-null when this view uses the workspace-shared connection pool. */
    private final McpConnectionManager manager;
    private McpToolGateway lease;
    private final Consumer<Boolean> managerListener;

    /** Last applied MCP state (server list + effective enabled flag). UI-thread confined. */
    private volatile McpApplied lastApplied;

    private record McpApplied(List<McpServerConfig> servers, boolean enabled) {}

    /** The MCP state the current preferences would produce. */
    private McpApplied desiredState() {
        var servers = McpPreferenceInitializer.loadServers();
        return new McpApplied(servers, !servers.isEmpty() && McpPreferenceInitializer.isMcpEnabled());
    }

    /**
     * @param toolService   the tool registry to connect/disconnect MCP tools on
     * @param onStateChange called on the UI thread with {@code true} when connected,
     *                      {@code false} when disconnected or on failure
     */
    public McpConnectionService(ToolService toolService, Consumer<Boolean> onStateChange) {
        this(toolService, onStateChange, null);
    }

    public McpConnectionService(ToolService toolService, Consumer<Boolean> onStateChange,
            McpConnectionManager manager) {
        this.toolService = toolService;
        this.onStateChange = onStateChange;
        this.manager = manager;
        this.managerListener = connected -> onStateChange.accept(connected);
        if (manager != null) manager.addListener(managerListener);
    }

    /**
     * Reads current preferences and connects or disconnects MCP as needed.
     * Should be called whenever the preference page is saved.
     */
    public void applyConfig() {
        if (manager != null) {
            manager.applyConfig();
            syncLease();
            return;
        }
        var desired = desiredState();
        if (desired.equals(lastApplied)) return;   // R-MCP1b: unchanged config → no reconnect
        lastApplied = desired;
        if (desired.enabled()) {
            connect();
        } else {
            disconnect();
        }
    }

    /**
     * Persists the enabled state and connects or disconnects accordingly.
     * Called by the MCP toggle button.
     */
    public void toggle(boolean enabled) {
        if (manager != null) {
            manager.toggle(enabled);
            syncLease();
            return;
        }
        McpPreferenceInitializer.setMcpEnabled(enabled);
        var desired = desiredState();
        lastApplied = desired;
        if (desired.enabled()) {
            connect();
        } else {
            disconnect();
        }
    }

    private void syncLease() {
        if (manager == null) return;
        if (manager.isEnabled()) {
            ensureLease();
            manager.ensureConnected();
        } else {
            releaseLease();
        }
        onStateChange.accept(manager.isConnected());
    }

    private void ensureLease() {
        if (lease == null) {
            lease = manager.acquire();
            toolService.setMcpGateway(lease);
        }
    }

    private void releaseLease() {
        if (lease != null) {
            lease.close();
            lease = null;
        }
        toolService.disconnectMcp();
    }

    /** Releases the lease/listener; safe to call more than once. */
    public void dispose() {
        if (manager != null) {
            manager.removeListener(managerListener);
            releaseLease();
        } else {
            disconnect();
        }
    }

    /** Connects to all configured MCP servers in a background Job. */
    public void connect() {
        if (manager != null) {
            syncLease();
            return;
        }
        Job.create("Connecting MCP servers", monitor -> {
            final AtomicBoolean connected = new AtomicBoolean(false);
            try {
                var servers = McpPreferenceInitializer.loadServers();
                if (!servers.isEmpty()) {
                    toolService.connectMcp(servers);
                    connected.set(true);
                }
                return PeonConstants.okStatus("MCP connected to " + servers.size() + " server(s)");
            } catch (Exception e) {
                return PeonConstants.errorStatus("MCP failed to connect: " + e.getMessage(), e);
            } finally {
                boolean state = connected.get();
                McpPreferenceInitializer.setMcpEnabled(state);
                onStateChange.accept(state);
            }
        }).schedule();
    }

    /** Synchronously disconnects all MCP servers and removes their tools. */
    public void disconnect() {
        if (manager != null) {
            releaseLease();
            return;
        }
        toolService.disconnectMcp();
    }

    /**
     * Returns whether any MCP servers are configured in preferences.
     */
    public static boolean hasConfiguredServers() {
        return !McpPreferenceInitializer.loadServers().isEmpty();
    }

    /**
     * Returns whether MCP is both enabled and configured.
     */
    public static boolean isEnabled() {
        List<McpServerConfig> servers = McpPreferenceInitializer.loadServers();
        return !servers.isEmpty() && McpPreferenceInitializer.isMcpEnabled();
    }
}
