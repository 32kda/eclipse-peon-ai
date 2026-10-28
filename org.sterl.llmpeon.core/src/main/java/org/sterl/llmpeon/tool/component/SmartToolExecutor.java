package org.sterl.llmpeon.tool.component;

import java.lang.reflect.Method;

import org.sterl.llmpeon.exception.ExceptionUtil;
import org.sterl.llmpeon.tool.SmartTool;
import org.sterl.llmpeon.tool.ToolLoopRequest;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.service.tool.DefaultToolExecutor;

public class SmartToolExecutor {
    private final DefaultToolExecutor executor;
    private final SmartTool tool;
    private final ToolSpecification spec;
    /** Bundle-contributed tool — opt-in unless {@link #defaultEnabled}. */
    private final boolean contributed;
    /** Contributed tool explicitly included in the default agent tool list. */
    private final boolean defaultEnabled;
    /** Comes from an MCP server (external or in-process). */
    private final boolean mcp;

    public SmartToolExecutor(SmartTool tool, Method method, ToolSpecification spec) {
        this(tool, method, spec, false, false, false);
    }

    public SmartToolExecutor(SmartTool tool, Method method, ToolSpecification spec, boolean contributed) {
        this(tool, method, spec, contributed, false, false);
    }

    public SmartToolExecutor(SmartTool tool, Method method, ToolSpecification spec,
            boolean contributed, boolean defaultEnabled, boolean mcp) {
        this.executor = DefaultToolExecutor.builder()
                .object(tool)
                .originalMethod(method)
                .methodToInvoke(method)
                .propagateToolExecutionExceptions(true)
                .build();

        this.tool = tool;
        this.spec = spec;
        this.contributed = contributed;
        this.defaultEnabled = defaultEnabled;
        this.mcp = mcp;
    }
    public SmartTool getTool() {
        return tool;
    }
    public ToolSpecification getSpec() {
        return spec;
    }

    public boolean isContributed() {
        return contributed;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public boolean isMcp() {
        return mcp;
    }
    
    public String run(ToolExecutionRequest request, ToolLoopRequest req) {
        try {
            tool.withToolRequest(req);
            return executor.execute(request, request.id());
        } catch (IllegalArgumentException e) {
            var msg = e.getMessage();
            reportProblem(request, req, msg);
            return msg;
        } catch (ToolExecutionException e) {
            if (ExceptionUtil.isCanceled(e)) throw e; // cancellation bubbles up — not an error
            if (e.getCause() instanceof IllegalArgumentException ex) {
                reportProblem(request, req, ex.getMessage());
                return ex.getMessage();
            }
            throw e;
        } catch (Exception e) {
            if (ExceptionUtil.isCanceled(e)) throw e; // cancellation wrapped in another exception
            if (e instanceof RuntimeException ex) throw ex;
            throw new RuntimeException(e);
        } finally {
            tool.withToolRequest(null);
        }
    }
    private void reportProblem(ToolExecutionRequest request,
            ToolLoopRequest req, String msg) {
        if (msg != null && msg.length() > 200) msg = msg.substring(0, 180) + "...";
        req.getMonitor().onProblem(request.name() + ": " + msg);
    }
}