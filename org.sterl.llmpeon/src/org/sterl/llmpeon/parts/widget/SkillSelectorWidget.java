package org.sterl.llmpeon.parts.widget;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.sterl.llmpeon.parts.ai.PeonAiService;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.skill.SkillPromptFile;
import org.sterl.llmpeon.skill.SkillSource;

/**
 * Skill selector below the chat input — own checkable menu, applies changes immediately and
 * re-bakes the static skill catalog.
 */
public class SkillSelectorWidget extends Composite {

    private static final int BUTTON_WIDTH = 150;

    private final Button button;
    /** Reused across openings — a new native menu per click would leak handles. */
    private final Menu menu;
    private final PeonAiService aiService;

    public SkillSelectorWidget(Composite parent, PeonAiService aiService) {
        super(parent, SWT.NONE);
        this.aiService = aiService;

        var layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        setLayout(layout);

        button = new Button(this, SWT.PUSH);
        var gd = new GridData();
        gd.widthHint = BUTTON_WIDTH;
        button.setLayoutData(gd);
        button.setToolTipText("Manage skills used by the agent");
        menu = new Menu(button);
        button.addListener(SWT.Selection, e -> showMenu());

        aiService.addAgentChangeListener(() -> EclipseUtil.runInUiThread(button, this::updateText));
        aiService.addConfigChangeListener(() -> EclipseUtil.runInUiThread(button, this::updateText));
        aiService.addProjectChangeListener(() -> EclipseUtil.runInUiThread(button, this::updateText));
        updateText();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (button != null && !button.isDisposed()) button.setEnabled(enabled);
    }

    private void updateText() {
        if (isDisposed() || button.isDisposed()) return;
        var enabled = aiService.getSkillService().getSkills();
        long projectCount = enabled.stream()
                .filter(skill -> skill.getSource() == SkillSource.PROJECT)
                .count();
        var text = enabled.size() + " skill" + (enabled.size() == 1 ? "" : "s");
        if (projectCount > 0) text += " (" + projectCount + " project)";
        button.setText(text);
    }

    private void showMenu() {
        if (isDisposed() || menu.isDisposed()) return;
        for (var item : menu.getItems()) item.dispose();

        List<SkillPromptFile> all = aiService.getSkillService().getAllLoadedSkills();
        if (all.isEmpty()) return;

        var configSkills = new ArrayList<SkillPromptFile>();
        var projectSkills = new ArrayList<SkillPromptFile>();
        for (var skill : all) {
            (skill.getSource() == SkillSource.PROJECT ? projectSkills : configSkills).add(skill);
        }

        var allItem = new MenuItem(menu, SWT.CHECK);
        allItem.setText("All Skills");
        allItem.setSelection(all.stream().allMatch(SkillPromptFile::isEnabled));
        allItem.addListener(SWT.Selection, e -> {
            aiService.getSkillService().setAllSkillsEnabled(allItem.getSelection());
            aiService.refreshStaticContext();
            updateText();
        });

        new MenuItem(menu, SWT.SEPARATOR);
        addSkillItems(menu, configSkills);

        if (!projectSkills.isEmpty()) {
            new MenuItem(menu, SWT.SEPARATOR);
            var header = new MenuItem(menu, SWT.NONE);
            var project = aiService.getProject();
            header.setText("Project skills — " + (project != null ? project.getName() : ""));
            header.setEnabled(false);
            addSkillItems(menu, projectSkills);
        }

        menu.setVisible(true);
    }

    private void addSkillItems(Menu menu, List<SkillPromptFile> skills) {
        for (var skill : skills) {
            var item = new MenuItem(menu, SWT.CHECK);
            item.setText(skill.getName());
            item.setSelection(skill.isEnabled());
            item.addListener(SWT.Selection, e -> {
                aiService.getSkillService().setSkillEnabled(skill.getName(), item.getSelection());
                aiService.refreshStaticContext();
                updateText();
            });
        }
    }
}
