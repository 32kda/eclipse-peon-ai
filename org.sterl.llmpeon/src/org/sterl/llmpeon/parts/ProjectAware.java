package org.sterl.llmpeon.parts;

import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;

/**
 * Optional contract for extension contributions that need the active project at call time.
 * The registry injects the supplier after creating the provider/tool, so the contribution still
 * has the no-arg constructor the extension registry requires.
 */
public interface ProjectAware {

    void setProjectSupplier(Supplier<IProject> project);
}
