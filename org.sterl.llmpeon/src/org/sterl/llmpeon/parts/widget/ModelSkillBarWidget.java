package org.sterl.llmpeon.parts.widget;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.sterl.llmpeon.parts.ai.PeonAiService;
import org.sterl.llmpeon.parts.shared.EclipseUiUtil;

/**
 * Row below the chat input: model selector + skill selector. The model availability callback
 * drives the send-button enablement (no model = no send).
 */
public class ModelSkillBarWidget extends Composite {

    private final ModelSelectorWidget modelSelector;
    private final SkillSelectorWidget skillSelector;

    public ModelSkillBarWidget(Composite parent, int style, PeonAiService aiService,
            Consumer<Boolean> onModelAvailable) {
        super(parent, style);

        var layout = new RowLayout(SWT.HORIZONTAL);
        layout.marginTop = 2;
        layout.marginBottom = 2;
        layout.marginLeft = 4;
        layout.marginRight = 4;
        layout.spacing = 6;
        layout.center = true;
        setLayout(layout);

        var label = new Label(this, SWT.NONE);
        label.setText("Model:");

        modelSelector = new ModelSelectorWidget(this, aiService, onModelAvailable);

        EclipseUiUtil.newSeparator(this);

        skillSelector = new SkillSelectorWidget(this, aiService);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        modelSelector.setEnabled(enabled);
        skillSelector.setEnabled(enabled);
    }
}
