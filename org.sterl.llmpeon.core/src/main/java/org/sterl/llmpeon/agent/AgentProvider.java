package org.sterl.llmpeon.agent;

import java.util.List;

/**
 * Contributes agents from an Eclipse extension. Implementations live at the plugin edge (they may
 * load prompt resources from their own bundle); the interface is Eclipse-free.
 */
public interface AgentProvider {

    /** All agents this provider contributes; may be empty, never null. */
    List<AgentDefinition> agents();
}
