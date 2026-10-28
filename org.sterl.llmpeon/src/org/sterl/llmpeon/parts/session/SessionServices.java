package org.sterl.llmpeon.parts.session;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.Platform;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.parts.config.McpConnectionManager;
import org.sterl.llmpeon.parts.mention.WorkspaceFileIndex;
import org.sterl.llmpeon.session.FileSessionRepository;
import org.sterl.llmpeon.session.SessionService;

/**
 * Workspace-scoped services shared by all session tabs in one Eclipse workspace.
 *
 * <p>Kept deliberately small and lazy: session cache, shared workspace-file index and the one-shot
 * legacy history import. MCP pooling is added separately.</p>
 */
public final class SessionServices {

    public static final SessionServices INSTANCE = new SessionServices();

    private final Path stateDir;
    private final SessionService sessionService;
    private final WorkspaceFileIndex workspaceFileIndex = new WorkspaceFileIndex();
    private final McpConnectionManager mcpManager = new McpConnectionManager();
    private final AtomicBoolean legacyImportStarted = new AtomicBoolean();

    private SessionServices() {
        stateDir = Platform.getStateLocation(Platform.getBundle(PeonConstants.PLUGIN_ID))
                .append("state").toFile().toPath();
        sessionService = new SessionService(new FileSessionRepository(stateDir.resolve("sessions")));
    }

    /** Runs after the legacy {@code ~/.peon/state} migration, once per workspace. */
    public void ensureLegacyImported() {
        if (legacyImportStarted.compareAndSet(false, true)) {
            SessionImportService.importLegacyAsync(sessionService, stateDir);
        }
    }

    public SessionService sessions() {
        return sessionService;
    }

    /** One snapshot for every session tab — a 50k-file index must not be rebuilt per tab. */
    public WorkspaceFileIndex workspaceFileIndex() {
        return workspaceFileIndex;
    }

    /** One MCP pool per Eclipse workspace; all session panels share its connections. */
    public McpConnectionManager mcpManager() {
        return mcpManager;
    }

    public Path stateDir() {
        return stateDir;
    }
}
