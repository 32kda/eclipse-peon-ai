package org.sterl.llmpeon.mcp;

import org.sterl.llmpeon.tool.SmartTool;

/**
 * A tool exposed by an in-process (Java/Eclipse) MCP server contribution.
 *
 * <p>Tool methods use the existing repo mechanism — langchain4j {@code @Tool}/{@code @P} — so
 * schema generation, parameter names and descriptions behave exactly like built-in tools. The
 * interface only marks MCP provenance (UI label, server name) and keeps the extension point
 * explicit.</p>
 */
public interface IMcpTool extends SmartTool {

    /** Server name for diagnostics/UI; {@code null} when unknown. */
    default String serverName() {
        return null;
    }
}
