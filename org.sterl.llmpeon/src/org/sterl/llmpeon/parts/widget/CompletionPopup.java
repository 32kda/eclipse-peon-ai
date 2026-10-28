package org.sterl.llmpeon.parts.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * Lightweight, typed completion popup shared by every trigger ({@code /} commands, {@code @}
 * files, later {@code #} symbols).
 *
 * <p>The popup is a borderless on-top {@link Shell} anchored to the chat input. It never takes
 * focus; the host widget keeps focus on its text control and forwards arrow / Enter / Escape
 * keys via {@link #moveSelection(int)}, {@link #commitSelection()} and {@link #hide()}.</p>
 *
 * <p>The caller filters and ranks the items (see {@code MentionFilter}); this class only renders,
 * navigates and reports the selected value. That keeps command-specific and file-specific rules
 * out of the widget.</p>
 */
public final class CompletionPopup<T> {

    private static final int NAME_COL_WIDTH = 250;
    /** Both columns keep a positive width — shrink the name column instead of hiding the detail. */
    private static final int MIN_NAME_COL_WIDTH = 120;
    private static final int MIN_DETAIL_COL_WIDTH = 40;
    /** Small gap after the columns (matches the old hard-coded 4px border allowance). */
    private static final int COLUMN_GAP = 4;

    /** Maximum rows shown without scrolling. */
    private static final int MAX_VISIBLE_ROWS = 8;
    /** Minimum popup width in pixels — must stay above both column minima plus the gap. */
    private static final int MIN_WIDTH = 240;
    /** Maximum popup width in pixels. */
    private static final int MAX_WIDTH = 520;

    /** One row: bold label, muted detail, and the value handed back on selection. */
    public record Item<T>(String label, String detail, T value) {
        public Item {
            Objects.requireNonNull(label, "label");
        }

        public static <T> Item<T> of(String label, T value) {
            return new Item<>(label, null, value);
        }
    }

    private final Composite anchor;
    private final Consumer<T> onSelect;
    private final Listener anchorShellMoveListener;
    private final Shell anchorShell;

    private Shell popup;
    private Table table;
    /** Larger, bold font for the label column. Owned by this popup, disposed with it. */
    private Font nameFont;
    private List<Item<T>> filtered = List.of();

    public CompletionPopup(Composite anchor, Consumer<T> onSelect) {
        this.anchor = anchor;
        this.onSelect = onSelect;
        this.anchorShell = anchor.getShell();
        this.anchorShellMoveListener = event -> {
            if (isOpen()) hide();
        };
        anchorShell.addListener(SWT.Move, anchorShellMoveListener);
        anchorShell.addListener(SWT.Resize, anchorShellMoveListener);

        anchor.addDisposeListener(e -> {
            if (!anchorShell.isDisposed()) {
                anchorShell.removeListener(SWT.Move, anchorShellMoveListener);
                anchorShell.removeListener(SWT.Resize, anchorShellMoveListener);
            }
            hide();
        });
    }

    public boolean isOpen() {
        return popup != null && !popup.isDisposed() && popup.isVisible();
    }

    /**
     * Shows (or refreshes) the popup with the given, already filtered items.
     * Hides when the list is empty.
     *
     * @param anchorScreen anchor position in display coordinates (typically the caret location)
     */
    public void show(List<Item<T>> items, Point anchorScreen) {
        if (items == null || items.isEmpty()) {
            hide();
            return;
        }
        this.filtered = new ArrayList<>(items);
        ensurePopup();
        rebuildItems();
        layoutAt(anchorScreen);
        popup.setVisible(true);
    }

    /** Moves selection by {@code delta} rows; wraps at boundaries. */
    public void moveSelection(int delta) {
        if (!isOpen() || filtered.isEmpty()) return;
        int idx = table.getSelectionIndex();
        if (idx < 0) idx = 0;
        int next = (idx + delta) % filtered.size();
        if (next < 0) next += filtered.size();
        table.setSelection(next);
        table.showSelection();
    }

    /** Triggers the consumer with the currently selected item, then hides the popup. */
    public boolean commitSelection() {
        if (!isOpen() || filtered.isEmpty()) return false;
        int idx = table.getSelectionIndex();
        if (idx < 0) idx = 0;
        var selected = filtered.get(idx);
        hide();
        onSelect.accept(selected.value());
        return true;
    }

    public void hide() {
        if (popup != null && !popup.isDisposed()) {
            popup.setVisible(false);
            popup.dispose();
        }
        if (nameFont != null && !nameFont.isDisposed()) {
            nameFont.dispose();
        }
        nameFont = null;
        popup = null;
        table = null;
        filtered = List.of();
    }

    private void ensurePopup() {
        if (popup != null && !popup.isDisposed()) return;

        popup = new Shell(anchorShell, SWT.NO_TRIM | SWT.ON_TOP | SWT.TOOL);
        popup.setLayout(new FillLayout());

        table = new Table(popup, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(false);
        table.setLinesVisible(false);

        // Label should stand out over the short COMMAND/SKILL/path tag: derive a larger, bold font.
        var nameData = table.getFont().getFontData();
        for (var fd : nameData) {
            fd.setHeight(fd.getHeight() + 1);
            fd.setStyle(fd.getStyle() | SWT.BOLD);
        }
        nameFont = new Font(table.getDisplay(), nameData);

        var colName = new TableColumn(table, SWT.LEFT);
        colName.setWidth(NAME_COL_WIDTH);

        var colDetail = new TableColumn(table, SWT.LEFT);
        colDetail.setWidth(MAX_WIDTH - 114);

        table.addListener(SWT.MouseDoubleClick, e -> commitSelection());
        table.addListener(SWT.DefaultSelection, e -> commitSelection());

        PaintListener border = e -> {
            var size = popup.getSize();
            e.gc.setForeground(Display.getDefault().getSystemColor(SWT.COLOR_WIDGET_BORDER));
            e.gc.drawRectangle(0, 0, size.x - 1, size.y - 1);
        };
        popup.addPaintListener(border);
    }

    private void rebuildItems() {
        table.removeAll();
        for (var item : filtered) {
            var row = new TableItem(table, SWT.NONE);
            row.setText(0, item.label());
            if (nameFont != null) row.setFont(0, nameFont);
            row.setText(1, item.detail() == null ? "" : item.detail());
            // mute the short detail so the label stands out:
            row.setForeground(1, table.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        }
        if (!filtered.isEmpty()) table.setSelection(0);
    }

    private void layoutAt(Point anchorScreen) {
        int rows = Math.min(filtered.size(), MAX_VISIBLE_ROWS);
        int rowHeight = table.getItemHeight();
        if (rowHeight <= 0) rowHeight = 18;
        int height = rowHeight * rows + 6;

        int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, anchor.getSize().x));
        // Share the available width: the detail column gets its minimum first, the name column
        // may shrink but never disappears (a zero-width column is as broken as a negative one).
        int available = Math.max(MIN_NAME_COL_WIDTH + MIN_DETAIL_COL_WIDTH, width - COLUMN_GAP);
        int nameWidth = Math.min(NAME_COL_WIDTH, available - MIN_DETAIL_COL_WIDTH);
        table.getColumn(0).setWidth(nameWidth);
        table.getColumn(1).setWidth(available - nameWidth);

        var bounds = new Rectangle(0, 0, width, height);
        if (anchorScreen != null) {
            bounds.x = anchorScreen.x;
            bounds.y = anchorScreen.y - height; // above the caret
            if (bounds.y < 0) bounds.y = anchorScreen.y + 16; // fall back below
        }
        // Clamp to the monitor that contains the anchor.
        var monitorBounds = anchorShell.getMonitor().getClientArea();
        if (bounds.x + bounds.width > monitorBounds.x + monitorBounds.width) {
            bounds.x = monitorBounds.x + monitorBounds.width - bounds.width;
        }
        if (bounds.x < monitorBounds.x) bounds.x = monitorBounds.x;
        if (bounds.y < monitorBounds.y) bounds.y = monitorBounds.y;
        popup.setBounds(bounds);
    }

    /** Detach the anchor-shell listeners and dispose the popup if alive. */
    public void dispose() {
        if (!anchorShell.isDisposed()) {
            anchorShell.removeListener(SWT.Move, anchorShellMoveListener);
            anchorShell.removeListener(SWT.Resize, anchorShellMoveListener);
        }
        hide();
    }

    Control getPopupShellForTests() {
        return popup;
    }
}
