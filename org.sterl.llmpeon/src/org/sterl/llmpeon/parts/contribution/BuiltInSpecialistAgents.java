package org.sterl.llmpeon.parts.contribution;

import java.util.List;

import org.sterl.llmpeon.agent.AgentDefinition;
import org.sterl.llmpeon.agent.AgentProvider;

/**
 * Built-in specialist sub-agents contributed through the {@code agent} extension point.
 *
 * <p>They exist to avoid the "one agent with every tool" problem: each declares a curated tool
 * allowlist and a focused prompt. Both are delegatable, so the active agent starts them by their
 * catalog description via {@code startAgent}.</p>
 */
public class BuiltInSpecialistAgents implements AgentProvider {

    public static final String JAVA_DEVELOPER = "Java Developer";
    public static final String PDE_DEVELOPER = "PDE Developer";

    private static final List<String> JAVA_TOOLS = List.of(
            "eclipseRead", "eclipseSearch", "eclipseList", "eclipseGrep",
            "eclipseFind", "readTypeSource",
            "eclipseWrite", "eclipseEdit", "eclipseReplace", "eclipseInsert", "eclipseUpdate",
            "eclipseRename", "eclipseCopy",
            "eclipseBuild", "eclipseRefresh", "eclipseRunJavaTests",
            "debugJava", "compactSession");

    private static final List<String> PDE_TOOLS = List.of(
            "eclipsePdeInfo",
            "eclipseRead", "eclipseSearch", "eclipseList", "eclipseGrep",
            "eclipseFind", "readTypeSource",
            "eclipseWrite", "eclipseEdit", "eclipseReplace", "eclipseInsert", "eclipseUpdate",
            "eclipseRename", "eclipseCopy",
            "eclipseBuild", "eclipseRefresh", "eclipseRunJavaTests",
            "compactSession");

    @Override
    public List<AgentDefinition> agents() {
        return List.of(javaDeveloper(), pdeDeveloper());
    }

    private static AgentDefinition javaDeveloper() {
        return AgentDefinition.builder()
                .name(JAVA_DEVELOPER)
                .description("JDT-focused implementation agent: inspect and navigate Java, edit, "
                        + "run tests and build. Curated Java-only tool set.")
                .delegatable(true)
                .includeDefault(true)
                .tools(JAVA_TOOLS)
                .prompt("""
                        Java Developer — focused JDT implementation agent.

                        Workflow
                        - Read the relevant code first; use eclipseFindJavaType / readTypeSource /
                          eclipseFindReferences to understand the type and its usages before editing.
                        - Use eclipseGrep / eclipseSearch only when the symbol tools do not answer the question.
                        - Make the smallest change that satisfies the task; do not refactor adjacent code.
                        - Edit with the eclipse* edit tools. Read the changed range back after a line-based edit.
                        - Verify with eclipseRunJavaTests and eclipseBuildProject. Fix failures in the
                          implementation — never weaken test assertions to get green.
                        - Report concrete results as file_path:line_number. If blocked, return the
                          blocker and what you tried.

                        Boundaries
                        - Do not delegate to other agents; you are the leaf.
                        - Do not touch plans, memory, shell, MCP or files outside the workspace.
                        - If the task is PDE/RCP specific (MANIFEST, plugin.xml, target platform),
                          say so and ask the parent to use the PDE Developer instead.
                        """)
                .build();
    }

    private static AgentDefinition pdeDeveloper() {
        return AgentDefinition.builder()
                .name(PDE_DEVELOPER)
                .description("PDE/RCP implementation agent: plugin/feature metadata, MANIFEST, "
                        + "target platform and OSGi-aware build/test.")
                .delegatable(true)
                .includeDefault(true)
                .tools(PDE_TOOLS)
                .prompt("""
                        PDE Developer — focused Eclipse plugin/feature implementation agent.

                        Workflow
                        - Start with eclipsePdeInfo to see natures, MANIFEST headers, plugin.xml/
                          fragment.xml/feature.xml and *.target definitions.
                        - Read MANIFEST.MF/plugin.xml before changing dependencies or extensions; keep
                          Require-Bundle/Import-Package consistent with the code you add.
                        - Target platform and build configuration are workspace state: change them only
                          when the task requires it, and state the reason.
                        - Edit with the eclipse* edit tools; read the changed range back after a
                          line-based edit.
                        - Verify with eclipseBuildProject; for plugin tests use eclipseRunJavaTests
                          with usePluginTest=true. Report the OSGi test result honestly.
                        - Report concrete results as file_path:line_number.

                        Boundaries
                        - Do not delegate to other agents; you are the leaf.
                        - Do not touch plans, memory, shell or MCP.
                        - If the task is plain Java without PDE metadata, say so and ask the parent
                          to use the Java Developer instead.
                        """)
                .build();
    }
}
