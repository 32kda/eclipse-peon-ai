package org.sterl.llmpeon.parts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.sterl.llmpeon.parts.session.SessionPanel;
import org.sterl.llmpeon.parts.session.SessionServices;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.session.SessionId;
import org.sterl.llmpeon.session.SessionService;
import org.sterl.llmpeon.session.SessionTabController;
import org.sterl.llmpeon.session.SessionSummary;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Session shell: one Eclipse view with an internal tab per chat session.
 *
 * <p>The editor stays in the Eclipse editor area. Editor selection is forwarded only to the
 * active session tab; model/skill/status controls live inside each {@link SessionPanel}.</p>
 */
public class AIChatView {

    private static final ILog LOG = Platform.getLog(AIChatView.class);

    private static final String PREF_OPEN_SESSIONS = PeonConstants.PLUGIN_ID + ".sessions.open";
    private static final String PREF_ACTIVE_SESSION = PeonConstants.PLUGIN_ID + ".sessions.active";

    private final SessionService sessions = SessionServices.INSTANCE.sessions();
    private final SessionTabController tabsModel = new SessionTabController();
    private final Map<SessionId, SessionPanel> panels = new ConcurrentHashMap<>();
    private final Map<SessionId, CTabItem> items = new ConcurrentHashMap<>();

    private Composite parent;
    private CTabFolder tabs;
    private Menu historyMenu;
    private Button historyButton;

    private volatile boolean disposing;

    private ISelection lastSelection;
    private ITextSelection lastTextSelection;
    private Object lastSelectedObject;

    private final Runnable sessionListener = () -> {
        if (parent == null || parent.isDisposed()) return;
        EclipseUtil.runInUiThread(parent, this::refreshTabs);
    };

    private final IPreferenceChangeListener prefListener = event -> {
        if (parent == null || parent.isDisposed()) return;
        EclipseUtil.runInUiThread(parent, () -> panels.values().forEach(SessionPanel::applyConfig));
    };

    @PostConstruct
    public void createPartControl(Composite parent) {
        this.parent = parent;
        var layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        parent.setLayout(layout);

        tabs = new CTabFolder(parent, SWT.CLOSE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabs.setSimple(false);
        tabs.setUnselectedCloseVisible(true);

        createTopRight();
        tabs.addListener(SWT.Selection, e -> onTabActivated());
        tabs.addCTabFolder2Listener(new CTabFolder2Adapter() {
            @Override
            public void close(CTabFolderEvent event) {
                if (event.item instanceof CTabItem item) {
                    event.doit = false; // we own the close flow
                    closeTab((SessionId) item.getData());
                }
            }
        });

        tabsModel.setListener(this::onTabsModelChanged);
        sessions.addChangeListener(sessionListener);
        InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID)
                .addPreferenceChangeListener(prefListener);

        restoreOpenTabs();
    }

    private void createTopRight() {
        var topRight = new Composite(tabs, SWT.NONE);
        var layout = new RowLayout(SWT.HORIZONTAL);
        layout.marginTop = 0;
        layout.marginBottom = 0;
        layout.marginLeft = 4;
        layout.marginRight = 4;
        layout.spacing = 4;
        topRight.setLayout(layout);

        var newButton = new Button(topRight, SWT.PUSH);
        newButton.setImage(PlatformUI.getWorkbench().getSharedImages()
                .getImage(ISharedImages.IMG_OBJ_ADD));
        newButton.setToolTipText("New chat session");
        newButton.addListener(SWT.Selection, e -> newSession());

        historyButton = new Button(topRight, SWT.PUSH);
        historyButton.setText("History");
        historyButton.setToolTipText("Open a previous chat session");
        historyButton.addListener(SWT.Selection, e -> showHistoryMenu());
        historyMenu = new Menu(historyButton);

        tabs.setTopRight(topRight);
    }

    private void restoreOpenTabs() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        var open = parseIds(prefs.get(PREF_OPEN_SESSIONS, ""));
        var active = parseId(prefs.get(PREF_ACTIVE_SESSION, null));

        var valid = new ArrayList<SessionId>();
        for (var id : open) {
            if (sessions.exists(id)) valid.add(id);
        }
        tabsModel.restore(valid, active);

        for (var id : tabsModel.openIds()) createTabItem(id);
        var activeId = tabsModel.active();
        if (activeId == null) {
            newSession();
        } else {
            var item = items.get(activeId);
            if (item != null) tabs.setSelection(item);
            ensurePanel(activeId);
            applyLastSelection(activeId);
        }
    }

    private void newSession() {
        var id = sessions.create("New chat");
        openTab(id, true);
    }

    private void openTab(SessionId id, boolean activate) {
        if (id == null) return;
        if (panels.containsKey(id) && activate) {
            var existing = items.get(id);
            if (existing != null) {
                tabs.setSelection(existing);
                tabsModel.activate(id);
                ensurePanel(id);
                applyLastSelection(id);
            }
            return;
        }
        tabsModel.open(id);
        createTabItem(id);
        if (activate) {
            var item = items.get(id);
            if (item != null) tabs.setSelection(item);
            tabsModel.activate(id);
            ensurePanel(id);
            applyLastSelection(id);
        }
    }

    private void createTabItem(SessionId id) {
        if (items.containsKey(id)) return;
        var item = new CTabItem(tabs, SWT.CLOSE);
        item.setData(id);
        item.setText(sessions.get(id).title());
        item.setToolTipText(sessions.get(id).title());
        items.put(id, item);
    }

    private void ensurePanel(SessionId id) {
        if (id == null || disposing) return;
        if (panels.containsKey(id)) return;
        var item = items.get(id);
        if (item == null) createTabItem(id);
        item = items.get(id);
        if (item == null || item.isDisposed()) return;

        var panel = new SessionPanel(tabs, sessions, id,
                () -> EclipseUtil.runInUiThread(parent, this::refreshTabs));
        item.setControl(panel);
        panels.put(id, panel);
        refreshTabs();
    }

    private SessionPanel activePanel() {
        var id = tabsModel.active();
        if (id == null) return null;
        ensurePanel(id);
        return panels.get(id);
    }

    private void onTabActivated() {
        var item = tabs.getSelection();
        if (item == null || !(item.getData() instanceof SessionId id)) return;
        tabsModel.activate(id);
        ensurePanel(id);
        var panel = panels.get(id);
        if (panel != null && !panel.isDisposed()) panel.focusInput();
        applyLastSelection(id);
        saveOpenState();
    }

    private void applyLastSelection(SessionId id) {
        var panel = panels.get(id);
        if (panel == null || panel.isDisposed()) return;
        if (lastTextSelection != null) panel.applyTextSelection(lastTextSelection);
        else if (lastSelectedObject != null) panel.applySelection(lastSelectedObject);
        else if (lastSelection != null) panel.applySelection(lastSelection);
    }

    private void closeTab(SessionId id) {
        if (id == null) return;
        var panel = panels.remove(id);
        if (panel != null && !panel.isDisposed()) panel.dispose();
        var item = items.remove(id);
        if (item != null && !item.isDisposed()) item.dispose();
        tabsModel.close(id);
        if (tabsModel.openIds().isEmpty()) {
            newSession();
        }
        saveOpenState();
        refreshTabs();
    }

    private void onTabsModelChanged() {
        saveOpenState();
        refreshTabs();
    }

    private void saveOpenState() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        prefs.put(PREF_OPEN_SESSIONS, tabsModel.openIds().stream()
                .map(SessionId::toString).reduce((a, b) -> a + "," + b).orElse(""));
        var active = tabsModel.active();
        if (active == null) prefs.remove(PREF_ACTIVE_SESSION);
        else prefs.put(PREF_ACTIVE_SESSION, active.toString());
    }

    private void refreshTabs() {
        if (disposing || parent == null || parent.isDisposed()) return;
        for (var id : tabsModel.openIds()) {
            var item = items.get(id);
            if (item == null || item.isDisposed()) continue;
            var panel = panels.get(id);
            var title = sessions.get(id).title();
            String prefix = "";
            if (panel != null) {
                if (panel.hasQuestion()) prefix = "? ";
                else if (panel.isWorking()) prefix = "● ";
            }
            var text = prefix + title;
            if (!text.equals(item.getText())) item.setText(text);
            if (!title.equals(item.getToolTipText())) item.setToolTipText(title);
        }
        if (historyButton != null && !historyButton.isDisposed()) {
            historyButton.setEnabled(true);
        }
    }

    private void showHistoryMenu() {
        if (historyMenu == null || historyMenu.isDisposed()) return;
        for (var item : historyMenu.getItems()) item.dispose();

        List<SessionSummary> all = sessions.list();
        if (all.isEmpty()) {
            var empty = new MenuItem(historyMenu, SWT.NONE);
            empty.setText("No saved sessions");
            empty.setEnabled(false);
        } else {
            for (var summary : all) {
                var item = new MenuItem(historyMenu, SWT.NONE);
                item.setText(summary.title());
                item.setToolTipText(summary.updatedAt() == null ? summary.title()
                        : summary.title() + " — " + summary.updatedAt());
                item.addListener(SWT.Selection, e -> openTab(summary.id(), true));
            }
        }
        historyMenu.setVisible(true);
    }

    private static List<SessionId> parseIds(String value) {
        var result = new ArrayList<SessionId>();
        if (value == null || value.isBlank()) return result;
        for (var token : value.split(",")) {
            var id = parseId(token);
            if (id != null) result.add(id);
        }
        return result;
    }

    private static SessionId parseId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return SessionId.parse(value.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @PreDestroy
    public void dispose() {
        disposing = true;
        saveOpenState();
        InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID)
                .removePreferenceChangeListener(prefListener);
        sessions.removeChangeListener(sessionListener);
        for (var panel : new ArrayList<>(panels.values())) {
            if (panel != null && !panel.isDisposed()) panel.dispose();
        }
        panels.clear();
        items.clear();
    }

    @Focus
    public void setFocus() {
        var panel = activePanel();
        if (panel != null && !panel.isDisposed()) panel.focusInput();
    }

    // -------------------------------------------------------------------------
    // Eclipse selection injection: only the active session gets it.
    // -------------------------------------------------------------------------

    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void onSelection(@Named(IServiceConstants.ACTIVE_SELECTION) ISelection s) {
        lastSelection = s;
        var panel = activePanel();
        if (panel != null) panel.applySelection(s);
    }

    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void onTextSelection(@Named(IServiceConstants.ACTIVE_SELECTION) ITextSelection ts) {
        lastTextSelection = ts;
        var panel = activePanel();
        if (panel != null) panel.applyTextSelection(ts);
    }

    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void onSelection(@Named(IServiceConstants.ACTIVE_SELECTION) Object o) {
        lastSelectedObject = o;
        var panel = activePanel();
        if (panel != null) panel.applySelection(o);
    }

    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void onSelection(@Named(IServiceConstants.ACTIVE_SELECTION) Object[] selectedObjects) {
        if (selectedObjects != null && selectedObjects.length > 0) onSelection(selectedObjects[0]);
    }
}
