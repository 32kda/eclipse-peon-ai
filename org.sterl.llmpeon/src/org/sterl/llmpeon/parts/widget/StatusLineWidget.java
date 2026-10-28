package org.sterl.llmpeon.parts.widget;

import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.sterl.llmpeon.parts.shared.EclipseUiUtil;
import org.sterl.llmpeon.parts.shared.ImageUtil;

/**
 * Status bar below the action bar. Shows project pin, selected file and the MCP toggle.
 * The skill selector lives in {@code ModelSkillBarWidget} below the chat input.
 * Uses a wrapping RowLayout so it reflows naturally on narrow views.
 */
public class StatusLineWidget extends Composite {

    /** Single toggle button — hidden when no project is selected. Text: "📌 ProjectName". */
    private final Button btnPin;
    private final Label fileLabel;
    private final Button btnMcp;

    public StatusLineWidget(Composite parent, int style,
            Consumer<Boolean> onPinChange,
            Consumer<Boolean> onMcpToggle) {
        super(parent, style);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.wrap = true;
        rowLayout.pack = true;
        rowLayout.center = true;
        rowLayout.marginTop = 2;
        rowLayout.marginBottom = 2;
        rowLayout.marginLeft = 4;
        rowLayout.spacing = 4;
        setLayout(rowLayout);

        // --- Pin + project: single toggle button, hidden until a project is selected ---
        final var imgPinned = ImageUtil.loadImage(this, ImageUtil.PIN);
        btnPin = new Button(this, SWT.TOGGLE);
        btnPin.setImage(imgPinned);
        btnPin.setToolTipText("Pin project — navigation won't change the active project");
        RowData pinRd = new RowData();
        pinRd.exclude = true;
        btnPin.setLayoutData(pinRd);
        btnPin.setVisible(false);
        btnPin.addListener(SWT.Selection, e -> onPinChange.accept(btnPin.getSelection()));

        // --- File label ---
        EclipseUiUtil.newSeparator(this);
        fileLabel = new Label(this, SWT.NONE);
        fileLabel.setToolTipText("Automatically included in chat context — the AI sees this file");

        EclipseUiUtil.newSeparator(this);

        // --- MCP toggle ---
        btnMcp = new Button(this, SWT.TOGGLE);
        btnMcp.setText("MCP off");
        btnMcp.setToolTipText("Enable MCP tools (configure via Window > Preferences > AI Peon MCP)");
        btnMcp.addListener(SWT.Selection, e -> onMcpToggle.accept(btnMcp.getSelection()));
    }

    public void update(IProject project, String selected) {
        // --- Pin: show/hide the button with project name ---
        boolean hasProject = project != null;
        RowData pinRd = (RowData) btnPin.getLayoutData();
        if (pinRd.exclude == hasProject) { // visibility changed
            pinRd.exclude = !hasProject;
            btnPin.setVisible(hasProject);
        }
        if (project != null) {
            btnPin.setText(project.getName()); // 📌 ProjectName
        }

        // --- File ---
        fileLabel.setText(selected == null ? "" : selected);

        layout(true);
        getParent().layout(new Control[] { this });
    }

    /** Sync the pin button state without firing the listener. */
    public void setPinned(boolean pinned) {
        if (!btnPin.isDisposed()) btnPin.setSelection(pinned);
    }

    /** Enable or disable the MCP button (disabled when no servers are configured). */
    public void setMcpAvailable(boolean available) {
        btnMcp.setEnabled(available);
        if (!available) btnMcp.setSelection(false);
    }

    /** Set the MCP toggle button state without firing the listener. */
    public void setMcpEnabled(boolean value) {
        btnMcp.setSelection(value);
        btnMcp.setText(value ? "MCP on" : "MCP off");
    }

    /** Returns whether the MCP toggle is currently on. */
    public boolean isMcpEnabled() {
        return btnMcp.getSelection();
    }
}
