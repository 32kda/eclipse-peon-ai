package org.sterl.llmpeon.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.List;

import org.junit.Test;
import org.sterl.llmpeon.agent.AiAgent;
import org.sterl.llmpeon.ai.ConfiguredChatModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.command.CommandService;
import org.sterl.llmpeon.context.ContextItem;
import org.sterl.llmpeon.parts.ai.component.AgentContextComponent;
import org.sterl.llmpeon.parts.ai.component.SharedToolsComponent;
import org.sterl.llmpeon.parts.tools.PlanTool;
import org.sterl.llmpeon.parts.tools.memory.WorkspaceMemoryTool;
import org.sterl.llmpeon.skill.SkillService;
import org.sterl.llmpeon.scaffold.AiScaffoldAgent;
import org.sterl.llmpeon.shared.StringUtil;

/**
 * Inc 2 (PeonAiService-Struktur-Aufräumen): AgentContextComponent — turn-context assembly,
 * Env-only static bake, handoff-line one-shot consumption and plan reference — without LLM.
 */
public class AgentContextComponentTest extends AbstractIntegrationTest {

    private WorkspaceMemoryTool wmt;
    private AgentContextComponent sut;
    private AiAgent activeAgent;

    @org.junit.Before
    public void beforeEach() {
        doSetup();
    }

    private void doSetup() {
        // GIVEN: a component wired with the real shared tools (no LLM involved)
        var ccm = new ConfiguredChatModel(LlmConfig.builder()
                .model("test").url("http://localhost:0")
                .configDir(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), ".peon-test")).build());
        var skillService = new SkillService();
        var sharedTools = new SharedToolsComponent(skillService, new CommandService());
        wmt = sharedTools.workspaceMemoryTool();
        wmt.memoryReset();

        var userContext = new org.sterl.llmpeon.context.UserContext();
        userContext.setCurrentProject(project);
        var scaffold = new AiScaffoldAgent(ccm);
        activeAgent = new org.sterl.llmpeon.agent.AiDevAgent(ccm, sharedTools.toolService());

        sut = new AgentContextComponent(() -> project, wmt, userContext, scaffold,
                sharedTools.toolService(), () -> activeAgent,
                ccm::getConfig, List::of, () -> "", () -> "", List.of());
    }

    /** GIVEN skill/agent catalogs WHEN the static context is baked THEN both appear as items. */
    @Test
    public void test_initStaticContext_addsCatalogs() {
        assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());

        var ccm = new ConfiguredChatModel(LlmConfig.builder()
                .model("test").url("http://localhost:0")
                .configDir(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), ".peon-test")).build());
        var sharedTools = new SharedToolsComponent(new SkillService(), new CommandService());
        var capturing = new org.sterl.llmpeon.agent.AiDevAgent(ccm, sharedTools.toolService());
        var sutWithCatalogs = new AgentContextComponent(() -> project, sharedTools.workspaceMemoryTool(),
                new org.sterl.llmpeon.context.UserContext(), null, null,
                () -> capturing, ccm::getConfig, () -> List.of(capturing),
                () -> "- review [project]: reviews code",
                () -> "- Java Developer: JDT specialist", List.of());

        sutWithCatalogs.initStaticContext();

        assertTrue(capturing.getStaticContext().stream()
                .anyMatch(item -> "Skill catalog".equals(item.label())));
        assertTrue(capturing.getStaticContext().stream()
                .anyMatch(item -> "Agent catalog".equals(item.label())));
    }

    /** GIVEN contributed context providers WHEN static/turn contexts are built THEN they are included,
     *  capped, and a failure is visible instead of breaking the turn. */
    @Test
    public void test_contextProviders_staticTurnCappedAndFailureVisible() {
        assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());

        var ccm = new ConfiguredChatModel(LlmConfig.builder()
                .model("test").url("http://localhost:0")
                .configDir(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), ".peon-test")).build());
        var sharedTools = new SharedToolsComponent(new SkillService(), new CommandService());
        var capturing = new org.sterl.llmpeon.agent.AiDevAgent(ccm, sharedTools.toolService());

        var turnProvider = new org.sterl.llmpeon.context.ContextProvider() {
            @Override
            public java.util.List<org.sterl.llmpeon.context.ContextItem> provide(
                    org.sterl.llmpeon.context.ContextRequest request) {
                return java.util.List.of(new org.sterl.llmpeon.context.SimpleContextItem(
                        "turn item", "PROJECT=" + request.projectName()));
            }
        };
        var staticProvider = new org.sterl.llmpeon.context.ContextProvider() {
            @Override
            public Scope scope() {
                return org.sterl.llmpeon.context.ContextProvider.Scope.STATIC;
            }

            @Override
            public java.util.List<org.sterl.llmpeon.context.ContextItem> provide(
                    org.sterl.llmpeon.context.ContextRequest request) {
                return java.util.List.of(new org.sterl.llmpeon.context.SimpleContextItem(
                        "static item", "STATIC=" + request.agentName()));
            }
        };
        var failingProvider = new org.sterl.llmpeon.context.ContextProvider() {
            @Override
            public String label() {
                return "broken";
            }

            @Override
            public java.util.List<org.sterl.llmpeon.context.ContextItem> provide(
                    org.sterl.llmpeon.context.ContextRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        var tooManyProvider = new org.sterl.llmpeon.context.ContextProvider() {
            @Override
            public String label() {
                return "many";
            }

            @Override
            public java.util.List<org.sterl.llmpeon.context.ContextItem> provide(
                    org.sterl.llmpeon.context.ContextRequest request) {
                var items = new java.util.ArrayList<org.sterl.llmpeon.context.ContextItem>();
                for (int i = 0; i < 7; i++) {
                    items.add(new org.sterl.llmpeon.context.SimpleContextItem("many-" + i, "x"));
                }
                return items;
            }
        };

        var sutWithProviders = new AgentContextComponent(() -> project, sharedTools.workspaceMemoryTool(),
                new org.sterl.llmpeon.context.UserContext(), null, null,
                () -> capturing, ccm::getConfig, () -> List.of(capturing),
                () -> "", () -> "",
                List.of(turnProvider, staticProvider, failingProvider, tooManyProvider));

        sutWithProviders.initStaticContext();
        var staticRendered = capturing.getStaticContext().stream()
                .map(org.sterl.llmpeon.context.ContextItem::render).toList();
        assertTrue(staticRendered.stream().anyMatch(r -> r != null && r.contains("STATIC=" + capturing.getName())));

        var turnContext = sutWithProviders.turnContext();
        var rendered = turnContext.stream().map(org.sterl.llmpeon.context.ContextItem::render).toList();
        assertTrue(rendered.stream().anyMatch(r -> r != null && r.contains("PROJECT=" + project.getName())));
        assertTrue(turnContext.stream()
                .anyMatch(item -> "Context provider failed".equals(item.label())));
        assertTrue(turnContext.stream()
                .anyMatch(item -> "Context provider limit".equals(item.label())));
    }

    /** GIVEN a set handoff line WHEN turnContext() twice THEN first call contains it, second does not. */
    @Test
    public void test_turnContext_handoffLine_consumedOnce() {
        assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());

        // GIVEN
        sut.armHandoffLine("Peon-Plan", "/some/plan.md");

        // WHEN
        var first = renderAll(sut.turnContext());
        var second = renderAll(sut.turnContext());

        // THEN
        assertTrue(first.stream().anyMatch(t -> t.contains("Handover from Peon-Plan")));
        assertFalse("handoff line must be consumed once",
                second.stream().anyMatch(t -> t.contains("Handover from Peon-Plan")));
    }

    /** GIVEN an existing plan file WHEN turnContext() THEN an item with dedupKey = path is present; without plan none. */
    @Test
    public void test_turnContext_planReference_onlyWhenPlanExists() {
        assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());
        eclipseWriteFile("/test_project/" + PlanTool.OVERVIEW_FILE, "# A Plan");

        // WHEN a plan exists on disk
        var items = sut.turnContext();

        // THEN the plan-reference item carries the path as dedupKey
        var planItem = items.stream().filter(i -> StringUtil.hasValue(i.dedupKey())
                && i.dedupKey().contains(PlanTool.OVERVIEW_FILE)).findAny();
        assertTrue("plan reference item expected", planItem.isPresent());
        assertNotNull(planItem.get().dedupKey());

        // AND: without a plan file there is no item
        eclipseDeleteResource("/test_project/" + PlanTool.OVERVIEW_FILE);
        var withoutPlan = sut.turnContext();
        assertFalse(withoutPlan.stream().anyMatch(i -> i.dedupKey() != null
                && i.dedupKey().contains(PlanTool.OVERVIEW_FILE)));
    }

    /** GIVEN agents WHEN initStaticContext THEN static context is exactly Env — never the memory. */
    @Test
    public void test_initStaticContext_envOnly() {
        assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());

        // GIVEN: memory has content AND a capturing agent is in the agent list
        wmt.memoryAdd("must not appear in static context");
        var capturingAgent = new org.sterl.llmpeon.agent.AiDevAgent(
                new ConfiguredChatModel(LlmConfig.builder()
                        .model("test").url("http://localhost:0").build()),
                new org.sterl.llmpeon.tool.ToolService());
        var sutWithAgents = new AgentContextComponent(() -> project, wmt,
                new org.sterl.llmpeon.context.UserContext(), null, null,
                () -> activeAgent, () -> LlmConfig.builder().model("t").url("http://x").build(),
                () -> List.of(capturingAgent), () -> "", () -> "", List.of());

        // WHEN
        sutWithAgents.initStaticContext();

        // THEN: static context exists and is Env-only
        var ctx = capturingAgent.getStaticContext();
        assertEquals(1, ctx.size());
        assertNotNull(ctx.get(0).render());
        assertFalse("memory snapshot must not be baked into the static context",
                ctx.get(0).render().contains("must not appear in static context"));
    }

    /** GIVEN empty memory WHEN turnContext() THEN no workspace-memory item is injected. */
    @Test
    public void test_turnContext_emptyMemory_noMemoryItem() {
        assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());

        // GIVEN empty memory (reset in setup)
        // WHEN
        var rendered = renderAll(sut.turnContext());

        // THEN
        assertFalse(rendered.stream().anyMatch(t -> t.contains("workspace-memory")));
    }

    /**
     * GIVEN a custom agent whose {@code tools:} allowlist hides {@code startAgent}
     * WHEN the static context is baked
     * THEN it gets no agent catalog — the model must never be pointed at a tool it cannot call.
     */
    @Test
    public void test_initStaticContext_agentCatalogOnlyWhenStartAgentAllowed() {
        assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());

        var ccm = new ConfiguredChatModel(LlmConfig.builder()
                .model("test").url("http://localhost:0")
                .configDir(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), ".peon-test")).build());
        var sharedTools = new SharedToolsComponent(new SkillService(), new CommandService());
        var allowed = customAgent("AllowedAgent", List.of("*"), ccm, sharedTools.toolService());
        var restricted = customAgent("RestrictedAgent", List.of("read_file"), ccm,
                sharedTools.toolService());

        var sutWithCatalog = new AgentContextComponent(() -> project,
                sharedTools.workspaceMemoryTool(), new org.sterl.llmpeon.context.UserContext(),
                null, null, () -> allowed, ccm::getConfig, () -> List.of(allowed, restricted),
                () -> "", () -> "- DelegatableAgent: helps", List.of());

        sutWithCatalog.initStaticContext();

        assertTrue(allowed.getStaticContext().stream()
                .anyMatch(item -> "Agent catalog".equals(item.label())));
        assertFalse("an agent without startAgent in its allowlist must not get the catalog",
                restricted.getStaticContext().stream()
                        .anyMatch(item -> "Agent catalog".equals(item.label())));
    }

    private static org.sterl.llmpeon.agent.CustomAgent customAgent(String name, List<String> tools,
            ConfiguredChatModel ccm, org.sterl.llmpeon.tool.ToolService toolService) {
        var frontmatter = new java.util.LinkedHashMap<String, List<String>>();
        frontmatter.put("name", List.of(name));
        frontmatter.put("tools", tools);
        var prompt = new org.sterl.llmpeon.prompt.model.SimplePromptFile(frontmatter, "body", null);
        return new org.sterl.llmpeon.agent.CustomAgent(prompt, ccm, toolService);
    }

    private List<String> renderAll(List<ContextItem> items) {
        return items.stream()
                .map(i -> {
                    String r = i.render();
                    return r == null ? "" : i.dedupKey() == null ? r : i.dedupKey() + "\n" + r;
                })
                .toList();
    }

    @Override
    public void after() {
        if (wmt != null) wmt.memoryReset();
        super.after();
    }
}
