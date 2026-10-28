package org.sterl.llmpeon.parts.mention;

import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.SelectionDialog;
import org.sterl.llmpeon.parts.ProjectAware;
import org.sterl.llmpeon.parts.shared.JdtUtil;

/**
 * {@code #} Java-symbol provider backed by the native JDT type dialog.
 *
 * <p>JDT is an <b>optional</b> dependency of this plugin: the package is imported with
 * {@code resolution:=optional} and the extension contribution is skipped when
 * {@code org.eclipse.jdt.ui} is absent. That keeps the picker disable-able without touching the
 * core mention API.</p>
 */
public class JavaSymbolMentionProvider implements DialogMentionProvider, ProjectAware {

    public static final char TRIGGER = '#';

    private static final ILog LOG = Platform.getLog(JavaSymbolMentionProvider.class);

    private Supplier<IProject> activeProject;

    /** Required by the extension registry. */
    public JavaSymbolMentionProvider() {
    }

    @Override
    public void setProjectSupplier(Supplier<IProject> project) {
        this.activeProject = project;
    }

    @Override
    public char trigger() {
        return TRIGGER;
    }

    @Override
    public String id() {
        return "javaSymbols";
    }

    @Override
    public String pick(Shell parent, String initialPrefix) {
        IProject project = activeProject == null ? null : activeProject.get();
        if (project == null) return null;
        IJavaProject javaProject = JavaCore.create(project);
        if (javaProject == null || !javaProject.exists()) return null;

        SelectionDialog dialog;
        try {
            dialog = JavaUI.createTypeDialog(
                    parent,
                    new ProgressMonitorDialog(parent),
                    JdtUtil.resolveScope(project.getName()),
                    IJavaSearchConstants.TYPE,
                    false,
                    initialPrefix == null ? "" : initialPrefix);
        } catch (JavaModelException e) {
            LOG.error("Failed to open the JDT type dialog for project " + project.getName(), e);
            return null;
        }
        dialog.setTitle("Add Java type to chat");
        dialog.setMessage("Select a type to reference.");

        if (dialog.open() != Window.OK) return null;
        Object[] result = dialog.getResult();
        if (result == null || result.length == 0 || !(result[0] instanceof IType type)) return null;
        return type.getFullyQualifiedName();
    }
}
