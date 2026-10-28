package org.sterl.llmpeon.mcp;

import java.util.List;

/**
 * An in-process MCP server contributed by an Eclipse extension.
 *
 * <p>This is the Java/Eclipse counterpart to the external STDIO/HTTP MCP servers: no process and
 * no socket, the tools run in the Eclipse JVM with full access to the workspace. Tool classes use
 * the existing {@code @Tool}/{@code @P} markup; the server only groups them and decides the opt-in
 * policy.</p>
 *
 * <p>{@link #defaultEnabled()} is <b>false</b> by default: the tools are not added to the default
 * agent tool list. An agent opts in by naming them in its {@code tools:} allowlist (or the server
 * sets this flag to true deliberately). This keeps MCP tool pollution under control.</p>
 */
public interface IMcpServer {

    String name();

    /** Include this server's tools in the default agent tool list? Default false (opt-in). */
    default boolean defaultEnabled() {
        return false;
    }

    List<IMcpTool> tools();
}
