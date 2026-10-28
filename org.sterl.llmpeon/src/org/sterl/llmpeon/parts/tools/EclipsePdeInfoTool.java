package org.sterl.llmpeon.parts.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.parts.shared.JdtUtil;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Read-only PDE/RCP metadata for a project: natures, MANIFEST headers, plugin/fragment/feature
 * descriptors and target definitions.
 *
 * <p>This is what makes the PDE/RCP specialist agent actually PDE-aware without shelling out. It
 * never writes; the normal read/edit tools handle code changes.</p>
 */
public class EclipsePdeInfoTool extends AbstractEclipseTool {

    public static final String NAME = "eclipsePdeInfo";

    @Tool(name = NAME, value = "PDE: show plugin/feature metadata for a project - natures, "
            + "MANIFEST.MF headers (Bundle-SymbolicName, Require-Bundle, Import-Package, ...), "
            + "plugin.xml/fragment.xml/feature.xml paths and *.target definitions. Read-only.")
    public String eclipsePdeInfo(@P(name = "projectName", required = false) String projectName) {
        var project = resolveProject(projectName);
        if (project == null) {
            return "No open project found"
                    + (projectName == null || projectName.isBlank() ? "" : " for '" + projectName + "'")
                    + ". Open projects: " + EclipseUtil.openProjectsNames();
        }

        var manifest = EclipseUtil.findMember(project, "META-INF/MANIFEST.MF");
        var pluginXml = EclipseUtil.findMember(project, "plugin.xml");
        var fragmentXml = EclipseUtil.findMember(project, "fragment.xml");
        var featureXml = EclipseUtil.findMember(project, "feature.xml");

        var result = new StringBuilder()
                .append("Project: ").append(project.getName()).append(System.lineSeparator())
                .append("Natures: ").append(EclipseUtil.projectNatures(project)).append(System.lineSeparator());

        boolean pde = manifest.isPresent() || pluginXml.isPresent()
                || fragmentXml.isPresent() || featureXml.isPresent();
        if (!pde) {
            return result.append("No PDE metadata (MANIFEST.MF/plugin.xml/fragment.xml/feature.xml) found — "
                    + "this is not a PDE plugin/feature project.").toString();
        }

        appendPath(result, "MANIFEST.MF", manifest.orElse(null));
        appendPath(result, "plugin.xml", pluginXml.orElse(null));
        appendPath(result, "fragment.xml", fragmentXml.orElse(null));
        appendPath(result, "feature.xml", featureXml.orElse(null));

        manifest.ifPresent(file -> appendManifestHeaders(result, file));

        var targets = targetDefinitions(project);
        if (!targets.isEmpty()) {
            result.append("Target definitions:").append(System.lineSeparator());
            result.append(targets.stream().map(path -> "  " + path)
                    .collect(Collectors.joining(System.lineSeparator())));
        }
        return result.toString();
    }

    private static IProject resolveProject(String projectName) {
        if (projectName != null && !projectName.isBlank()) {
            return EclipseUtil.findOpenProject(projectName).orElse(null);
        }
        return EclipseUtil.openProjects().stream().findFirst().orElse(null);
    }

    private static void appendPath(StringBuilder result, String label, IFile file) {
        if (file != null) {
            result.append(label).append(": ").append(JdtUtil.pathOf(file)).append(System.lineSeparator());
        }
    }

    private static void appendManifestHeaders(StringBuilder result, IFile manifestFile) {
        try (var input = manifestFile.getContents()) {
            var manifest = new Manifest(input);
            var main = manifest.getMainAttributes();
            result.append("MANIFEST headers:").append(System.lineSeparator());
            for (String key : List.of(
                    Attributes.Name.MANIFEST_VERSION.toString(),
                    "Bundle-SymbolicName", "Bundle-Version", "Bundle-Name",
                    "Bundle-RequiredExecutionEnvironment", "Bundle-Activator",
                    "Require-Bundle", "Import-Package", "Export-Package", "Fragment-Host")) {
                String value = main.getValue(key);
                if (value != null && !value.isBlank()) {
                    result.append("  ").append(key).append(": ")
                            .append(truncate(value, 800)).append(System.lineSeparator());
                }
            }
        } catch (Exception e) {
            result.append("MANIFEST headers: unreadable (").append(e.getMessage()).append(')')
                    .append(System.lineSeparator());
        }
    }

    private static List<String> targetDefinitions(IProject project) {
        var targets = new ArrayList<String>();
        try {
            project.accept(resource -> {
                if (targets.size() >= 20) return false;
                if (resource.getType() == IResource.FILE && resource.getName().endsWith(".target")) {
                    var path = JdtUtil.pathOf(resource);
                    if (path != null) targets.add(path);
                }
                return true;
            });
        } catch (CoreException e) {
            // keep the metadata we already have; the caller sees the target list (possibly empty)
        }
        return targets;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "… (truncated)";
    }
}
