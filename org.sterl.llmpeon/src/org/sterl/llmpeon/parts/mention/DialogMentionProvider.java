package org.sterl.llmpeon.parts.mention;

import org.eclipse.swt.widgets.Shell;

/**
 * A mention trigger that opens a native picker instead of the inline completion popup.
 *
 * <p>Used by the {@code #} Java-symbol provider: the JDT type dialog is the picker the Eclipse user
 * already knows. Implementations run on the UI thread and are contributed through the
 * {@code org.sterl.llmpeon.mentionProvider} extension point ({@code <dialogProvider>}).</p>
 */
public interface DialogMentionProvider {

    /** Trigger character, e.g. {@code '#'}. */
    char trigger();

    default String id() {
        return getClass().getName();
    }

    /**
     * Opens the picker. Runs on the UI thread.
     *
     * @param initialPrefix text already typed after the trigger (may be empty)
     * @return the text to insert after the trigger (e.g. a fully qualified type name), or
     *         {@code null} when the user cancels
     */
    String pick(Shell parent, String initialPrefix);
}
