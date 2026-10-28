package org.sterl.llmpeon.mcp;

import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * Tool-facing view of an MCP connection. {@link McpService} is the simple self-owned
 * implementation; the Eclipse workspace pool returns a lease that shares one connection.
 */
public interface McpToolGateway extends AutoCloseable {

    List<ToolSpecification> getToolSpecifications();

    boolean hasTool(String toolName);

    /** @return owning server name, or {@code null} when unknown. */
    default String toolServer(String toolName) {
        return null;
    }

    String executeTool(ToolExecutionRequest request);

    @Override
    default void close() {
    }
}
