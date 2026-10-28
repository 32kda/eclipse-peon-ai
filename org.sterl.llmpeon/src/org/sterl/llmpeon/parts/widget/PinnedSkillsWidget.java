package org.sterl.llmpeon.parts.widget;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.sterl.llmpeon.parts.ai.PeonAiService;
import org.sterl.llmpeon.session.PinnedSkill;
import org.sterl.llmpeon.session.SessionId;
import org.sterl.llmpeon.skill.SkillPromptFile;

/**
 * Session-scoped skill pins: a compact chip row plus a menu to pin/unpin loaded skills.
 * Pins live in the session event log and are injected into the turn context by PeonAiService.
 */
public class PinnedSkillsWidget extends Composite {

    private final PeonAiService aiService;
    private final SessionId sessionId;
    private final Composite chips;
    private final Button pinButton;
    private final Menu menu;

    public PinnedSkillsWidget(Composite parent, PeonAiService aiService, SessionId sessionId) {
        super(parent, SWT.NONE);
        this.aiService = aiService;
        this.sessionId = sessionId;

        var layout = new RowLayout(SWT.HORIZONTAL);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.spacing = 4;
        setLayout(layout);

        var label = new Label(this, SWT.NONE);
        label.setText("Pinned:");

        chips = new Composite(this, SWT.NONE);
        var chipLayout = new RowLayout(SWT.HORIZONTAL);
        chipLayout.marginWidth = 0;
        chipLayout.marginHeight = 0;
        chipLayout.spacing = 3;
        chips.setLayout(chipLayout);

        pinButton = new Button(this, SWT.PUSH);
        pinButton.setText("Pin skill");
        pinButton.setToolTipText("Pin a skill for this chat session");
        pinButton.addListener(SWT.Selection, e -> showMenu());
        menu = new Menu(pinButton);

        refresh();
    }

    public void refresh() {
        if (isDisposed() || chips.isDisposed()) return;
        for (var child : chips.getChildren()) child.dispose();

        List<PinnedSkill> pins = aiService.getSessionService().get(sessionId).pinnedSkills();
        if (pins.isEmpty()) {
            var empty = new Label(chips, SWT.NONE);
            empty.setText("none");
            empty.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
            layout();
            return;
        }
        for (var pin : pins) {
            var chip = new Button(chips, SWT.PUSH);
            chip.setText(pin.name() + "  ×");
            chip.setToolTipText("Unpin " + pin.name());
            chip.setLayoutData(new RowData());
            chip.addListener(SWT.Selection, e -> {
                aiService.getSessionService().unpinSkill(sessionId, pin);
                refresh();
            });
        }
        layout();
        getParent().layout(new org.eclipse.swt.widgets.Control[] { this });
    }

    private void showMenu() {
        if (menu.isDisposed()) return;
        for (var item : menu.getItems()) item.dispose();

        List<PinnedSkill> pins = aiService.getSessionService().get(sessionId).pinnedSkills();
        List<SkillPromptFile> skills = aiService.getSkillService().getAllLoadedSkills();
        if (skills.isEmpty()) {
            var empty = new MenuItem(menu, SWT.NONE);
            empty.setText("No skills loaded");
            empty.setEnabled(false);
        } else {
            for (var skill : skills) {
                var item = new MenuItem(menu, SWT.CHECK);
                item.setText(skill.getName());
                var pin = new PinnedSkill(skill.getName(), skill.getSource().name());
                item.setSelection(pins.stream().anyMatch(p -> p.key().equals(pin.key())));
                item.addListener(SWT.Selection, e -> {
                    if (item.getSelection()) aiService.getSessionService().pinSkill(sessionId, pin);
                    else aiService.getSessionService().unpinSkill(sessionId, pin);
                    refresh();
                });
            }
        }
        menu.setVisible(true);
    }
}
