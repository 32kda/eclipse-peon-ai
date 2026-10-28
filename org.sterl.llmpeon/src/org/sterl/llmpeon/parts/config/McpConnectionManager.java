package org.sterl.llmpeon.parts.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.sterl.llmpeon.mcp.McpServerConfig;
import org.sterl.llmpeon.mcp.McpService;
import org.sterl.llmpeon.mcp.McpToolGateway;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * Workspace-scoped MCP connection pool. Every session obtains a lease; the underlying clients are
 * shared. The last lease release starts an idle timer before the processes/connections are closed.
 */
public class McpConnectionManager {

    private static final ILog LOG = Platform.getLog(McpConnectionManager.class);
    /** Shared connections are kept for a while after the last session releases them. */
    static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final Object lock = new Object();
    private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

    private List<McpServerConfig> desiredServers = List.of();
    private boolean desiredEnabled;
    private boolean appliedKnown;
    private McpService service;
    private boolean connecting;
    private int leases;
    private long idleGeneration;

    public McpConnectionManager() {
        refreshDesired();
    }

    /** Called on every preference change; reconciles only when the effective config changed. */
    public void applyConfig() {
        synchronized (lock) {
            var beforeServers = desiredServers;
            var beforeEnabled = desiredEnabled;
            refreshDesired();
            if (appliedKnown && beforeServers.equals(desiredServers) && beforeEnabled == desiredEnabled) {
                return;
            }
            appliedKnown = true;
            if (!desiredEnabled || desiredServers.isEmpty()) {
                disconnectNow();
            } else if (leases > 0) {
                connectAsync();
            } else {
                // lazy: connect on the next lease
                disconnectNow();
            }
        }
    }

    public McpToolGateway acquire() {
        synchronized (lock) {
            leases++;
            idleGeneration++;
            refreshDesired();
            if (desiredEnabled && !desiredServers.isEmpty() && service == null && !connecting) {
                connectAsync();
            }
            return new Lease(this);
        }
    }

    private void release(Lease lease) {
        synchronized (lock) {
            if (!lease.active) return;
            lease.active = false;
            leases = Math.max(0, leases - 1);
            if (leases == 0) scheduleIdleDisconnect();
        }
    }

    /** Starts a lazy connection when this scope is enabled but currently disconnected. */
    public void ensureConnected() {
        synchronized (lock) {
            refreshDesired();
            if (desiredEnabled && !desiredServers.isEmpty() && service == null && !connecting && leases > 0) {
                connectAsync();
            }
        }
    }

    public void toggle(boolean enabled) {
        McpPreferenceInitializer.setMcpEnabled(enabled);
        synchronized (lock) {
            desiredEnabled = enabled && !desiredServers.isEmpty();
            appliedKnown = true;
            if (desiredEnabled && leases > 0) connectAsync();
            else if (!desiredEnabled) disconnectNow();
        }
    }

    public boolean isEnabled() {
        synchronized (lock) {
            return desiredEnabled && !desiredServers.isEmpty();
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return service != null;
        }
    }

    public void addListener(Consumer<Boolean> listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Consumer<Boolean> listener) {
        listeners.remove(listener);
    }

    private void refreshDesired() {
        desiredServers = McpPreferenceInitializer.loadServers();
        desiredEnabled = !desiredServers.isEmpty() && McpPreferenceInitializer.isMcpEnabled();
    }

    private void connectAsync() {
        if (connecting || desiredServers.isEmpty() || !desiredEnabled) return;
        connecting = true;
        var servers = desiredServers;
        Job.create("Connecting MCP servers", monitor -> {
            McpService connected = null;
            try {
                connected = new McpService(servers);
                connected.connect();
            } catch (Exception e) {
                LOG.error("MCP failed to connect", e);
            }
            synchronized (lock) {
                connecting = false;
                if (connected != null && desiredEnabled && !desiredServers.isEmpty()
                        && leases > 0) {
                    if (service != null) service.disconnect();
                    service = connected;
                    notifyListeners(true);
                } else {
                    if (connected != null) connected.disconnect();
                    if (leases == 0) scheduleIdleDisconnect();
                    notifyListeners(false);
                }
            }
            return Status.OK_STATUS;
        }).schedule();
    }

    private void scheduleIdleDisconnect() {
        long generation = ++idleGeneration;
        Job.create("Disconnect idle MCP servers", monitor -> {
            synchronized (lock) {
                if (generation != idleGeneration || leases > 0) return Status.OK_STATUS;
                disconnectNow();
            }
            return Status.OK_STATUS;
        }).schedule(IDLE_TIMEOUT_MS);
    }

    private void disconnectNow() {
        if (service != null) {
            service.disconnect();
            service = null;
            notifyListeners(false);
        }
    }

    private void notifyListeners(boolean connected) {
        for (var listener : listeners) {
            try {
                listener.accept(connected);
            } catch (RuntimeException e) {
                LOG.warn("MCP state listener failed", e);
            }
        }
    }

    List<ToolSpecification> toolSpecifications() {
        synchronized (lock) {
            return service == null ? List.of() : service.getToolSpecifications();
        }
    }

    boolean hasTool(String name) {
        synchronized (lock) {
            return service != null && service.hasTool(name);
        }
    }

    String toolServer(String name) {
        synchronized (lock) {
            return service == null ? null : service.toolServer(name);
        }
    }

    String execute(ToolExecutionRequest request) {
        synchronized (lock) {
            if (service == null) return "Error: MCP is not connected";
            return service.executeTool(request);
        }
    }

    /** One session's claim on the shared connections. */
    private static final class Lease implements McpToolGateway {
        private final McpConnectionManager manager;
        private boolean active = true;

        Lease(McpConnectionManager manager) {
            this.manager = manager;
        }

        @Override
        public List<ToolSpecification> getToolSpecifications() {
            return manager.toolSpecifications();
        }

        @Override
        public boolean hasTool(String toolName) {
            return manager.hasTool(toolName);
        }

        @Override
        public String toolServer(String toolName) {
            return manager.toolServer(toolName);
        }

        @Override
        public String executeTool(ToolExecutionRequest request) {
            return manager.execute(request);
        }

        @Override
        public void close() {
            manager.release(this);
        }
    }
}
