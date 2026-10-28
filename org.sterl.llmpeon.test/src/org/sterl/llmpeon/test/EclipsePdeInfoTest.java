package org.sterl.llmpeon.test;

import org.junit.Test;
import org.sterl.llmpeon.parts.tools.EclipsePdeInfoTool;

/**
 * Read-only PDE metadata used by the PDE Developer specialist.
 */
public class EclipsePdeInfoTest extends AbstractIntegrationTest {

    private final EclipsePdeInfoTool tool = new EclipsePdeInfoTool();

    @Test
    public void reportsNonPdeProject() {
        var result = tool.eclipsePdeInfo(project.getName());

        assertContains(result, "Project: " + project.getName());
        assertContains(result, "Natures:");
        assertContains(result, "not a PDE");
    }

    @Test
    public void readsManifestAndPluginDescriptor() {
        eclipseWriteFile("META-INF/MANIFEST.MF", """
                Manifest-Version: 1.0
                Bundle-SymbolicName: test.plugin
                Bundle-Version: 1.0.0
                Require-Bundle: org.eclipse.core.runtime
                """);
        eclipseWriteFile("plugin.xml", "<plugin/>");

        var result = tool.eclipsePdeInfo(project.getName());

        assertContains(result, "MANIFEST.MF: /test_project/META-INF/MANIFEST.MF");
        assertContains(result, "Bundle-SymbolicName: test.plugin");
        assertContains(result, "Require-Bundle: org.eclipse.core.runtime");
        assertContains(result, "plugin.xml: /test_project/plugin.xml");
    }
}
