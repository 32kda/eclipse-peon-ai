package org.sterl.llmpeon.parts.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.sterl.llmpeon.agent.AiAgent;
import org.sterl.llmpeon.ai.AgentConfig;
import org.sterl.llmpeon.ai.EffectiveConnection;
import org.sterl.llmpeon.ai.ModelListCache;
import org.sterl.llmpeon.ai.model.AiModel;
import org.sterl.llmpeon.parts.ai.PeonAiService;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.provider.LlmProviders;
import org.sterl.llmpeon.shared.StringUtil;

/**
 * Model selector below the chat input.
 *
 * <p>The configured/active model is always visible. The provider list is fetched lazily and cached
 * per connection identity. When no model is active the combo shows
 * {@value #NONE} and the send button is disabled until a model is selected.</p>
 */
public class ModelSelectorWidget extends Composite {

    public static final String NONE = "[none - configure before use]";
    private static final int COMBO_WIDTH = 240;

    private final Combo combo;
    private final PeonAiService aiService;
    private final Consumer<Boolean> onModelAvailable;

    public ModelSelectorWidget(Composite parent, PeonAiService aiService,
            Consumer<Boolean> onModelAvailable) {
        super(parent, SWT.NONE);
        this.aiService = aiService;
        this.onModelAvailable = onModelAvailable;

        var layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        setLayout(layout);

        combo = new Combo(this, SWT.READ_ONLY | SWT.DROP_DOWN);
        var gd = new GridData();
        gd.widthHint = COMBO_WIDTH;
        combo.setLayoutData(gd);
        combo.setToolTipText("Model for the active agent");
        combo.addListener(SWT.Selection, e -> applySelection());

        aiService.addAgentChangeListener(() -> refresh(true));
        aiService.addConfigChangeListener(() -> refresh(true));
        refresh(true);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (combo != null && !combo.isDisposed()) combo.setEnabled(enabled);
    }

    private void applySelection() {
        var selected = combo.getText();
        if (StringUtil.hasNoValue(selected) || NONE.equals(selected)) {
            setAvailable(false);
            return;
        }
        // Persisting the model writes AGENT.md or preferences — never on the UI thread.
        Job.create("Save model selection", monitor -> {
            var changed = aiService.setActiveModel(selected);
            EclipseUtil.runInUiThread(this, () -> {
                if (isDisposed()) return;
                setAvailable(changed || StringUtil.hasValue(aiService.getActiveModel()));
            });
            return Status.OK_STATUS;
        }).schedule();
    }

    private void refresh(boolean fetch) {
        if (isDisposed()) return;
        if (getDisplay().getThread() != Thread.currentThread()) {
            EclipseUtil.runInUiThread(this, () -> refresh(fetch));
            return;
        }

        var agent = aiService.getActiveAgent();
        if (agent == null) {
            applyModels(null, null);
            return;
        }
        var current = aiService.getActiveModel();
        var effective = EffectiveConnection.of(aiService.getConfig(), agentConfig(agent));
        var identity = effective.identity();

        applyModels(current, ModelListCache.instance().cached(identity));
        if (!fetch) return;

        var buildConfig = effective.buildConfig();
        Job.create("Loading models", monitor -> {
            var models = ModelListCache.instance().getOrFetch(identity,
                    () -> LlmProviders.of(identity.provider()).listAiModels(buildConfig));
            EclipseUtil.runInUiThread(this, () -> {
                if (isDisposed()) return;
                var now = aiService.getActiveAgent();
                if (now == null) return;
                var nowIdentity = EffectiveConnection.of(aiService.getConfig(), agentConfig(now)).identity();
                if (!nowIdentity.equals(identity)) return; // stale fetch
                applyModels(aiService.getActiveModel(), models);
            });
            return Status.OK_STATUS;
        }).schedule();
    }

    private AgentConfig agentConfig(AiAgent agent) {
        if (agent instanceof org.sterl.llmpeon.agent.AbstractAgent abstractAgent) {
            var config = abstractAgent.getConfig();
            if (config != null) return config;
        }
        return aiService.getConfig().devAgentConfig();
    }

    private void applyModels(String current, List<AiModel> fetched) {
        if (combo.isDisposed()) return;

        boolean hasCurrent = StringUtil.hasValue(current);
        var items = new ArrayList<String>();
        items.add(hasCurrent ? current : NONE);
        if (fetched != null) {
            for (var model : fetched) {
                var id = model.getId();
                if (id != null && items.stream().noneMatch(item -> item.equalsIgnoreCase(id))) {
                    items.add(id);
                }
            }
        }
        combo.setItems(items.toArray(String[]::new));
        combo.select(0);
        setAvailable(hasCurrent);
    }

    private void setAvailable(boolean available) {
        if (onModelAvailable != null) onModelAvailable.accept(available);
    }
}
