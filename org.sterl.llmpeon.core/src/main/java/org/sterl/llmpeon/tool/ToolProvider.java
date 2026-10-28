package org.sterl.llmpeon.tool;

import java.util.List;

/**
 * Contributes tools from an Eclipse extension.
 *
 * <p>Contributed tools are <b>opt-in</b>: an agent must name them in its {@code tools:} allowlist
 * (an absent allowlist means the built-in set only). A name collision with a built-in tool is
 * rejected — a contribution never shadows core behaviour.</p>
 */
public interface ToolProvider {

    /** All tools this provider contributes; may be empty, never null. */
    List<SmartTool> tools();
}
