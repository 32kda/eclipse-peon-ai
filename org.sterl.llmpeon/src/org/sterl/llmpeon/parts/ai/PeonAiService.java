package org.sterl.llmpeon.parts.ai;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.sterl.llmpeon.AgentService;
import org.sterl.llmpeon.agent.AiAgent;
import org.sterl.llmpeon.agent.AiPlanAgent;
import org.sterl.llmpeon.agent.AiReviewAgent;
import org.sterl.llmpeon.agent.CustomAgent;
import org.sterl.llmpeon.agent.NamedAgent;
import org.sterl.llmpeon.ai.AgentModelConfig;
import org.sterl.llmpeon.ai.ConfiguredChatModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.ai.LlmConfigSaver;
import org.sterl.llmpeon.command.CommandService;
import org.sterl.llmpeon.context.ContextItem;
import org.sterl.llmpeon.context.UserContext;
import org.sterl.llmpeon.mention.MentionProvider;
import org.sterl.llmpeon.mention.MentionService;
import org.sterl.llmpeon.memory.StateMigration;
import org.sterl.llmpeon.parts.AIChatView;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.parts.ai.component.AgentContextComponent;
import org.sterl.llmpeon.parts.ai.component.BuildPoAgentComponent;
import org.sterl.llmpeon.parts.config.EclipseLlmConfigStore;
import org.sterl.llmpeon.parts.ai.component.SharedToolsComponent;
import org.sterl.llmpeon.parts.config.LlmPreferenceInitializer;
import org.sterl.llmpeon.parts.config.McpConnectionService;
import org.sterl.llmpeon.parts.contribution.AgentProviderRegistry;
import org.sterl.llmpeon.parts.contribution.ContextProviderRegistry;
import org.sterl.llmpeon.parts.contribution.McpServerProviderRegistry;
import org.sterl.llmpeon.parts.contribution.SkillProviderRegistry;
import org.sterl.llmpeon.parts.contribution.ToolProviderRegistry;
import org.sterl.llmpeon.parts.mention.DialogMentionProvider;
import org.sterl.llmpeon.parts.mention.MentionContextResolver;
import org.sterl.llmpeon.parts.mention.MentionProviderRegistry;
import org.sterl.llmpeon.parts.mention.WorkspaceFileIndex;
import org.sterl.llmpeon.parts.mention.WorkspaceFileMentionProvider;
import org.sterl.llmpeon.parts.session.SessionServices;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.parts.shared.JdtUtil;
import org.sterl.llmpeon.parts.tools.AskUserTool;
import org.sterl.llmpeon.parts.tools.PlanTool;
import org.sterl.llmpeon.parts.tools.memory.WorkspaceMemoryTool;
import org.sterl.llmpeon.poagent.AiPoAgent;
import org.sterl.llmpeon.scaffold.AiScaffoldAgent;
import org.sterl.llmpeon.session.SessionId;
import org.sterl.llmpeon.session.SessionService;
import org.sterl.llmpeon.scaffold.ReloadConfigTool;
import org.sterl.llmpeon.shared.AiMonitor;
import org.sterl.llmpeon.shared.StringUtil;
import org.sterl.llmpeon.skill.SkillService;
import org.sterl.llmpeon.tool.ToolService;
import org.sterl.llmpeon.tool.tools.ShellTool;
import org.sterl.llmpeon.tool.tools.SkillTool;
import org.sterl.llmpeon.tool.tools.StartAgentTool;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Bundles all AI services into a single eagerly-initialized object.
 *
 * <p>Declaring this as a {@code final} field in {@link AIChatView} guarantees that all
 * services are non-null from the moment the view object is constructed — before Eclipse DI
 * has a chance to call any {@code @Inject} methods.</p>
 *
 */
public class PeonAiService {

    private static final ILog LOG = Platform.getLog(PeonAiService.class);

    private final UserContext userContext = new UserContext();

    /** View-title controls register here; the service fires after the state actually changed. */
    private final List<Runnable> agentListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> configListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> projectListeners = new CopyOnWriteArrayList<>();

    private final AgentService agentService;
    private final ConfiguredChatModel configuredModel;

    /** Shared tool service used by dev/plan/custom agents (MCP, AskUserTool, ShellTool, workspace disk tools). */
    private ToolService sharedToolService;
    private AiScaffoldAgent scaffoldAgent;

    private SkillService skillService;
    private CommandService commandService;

    /** Extension contributions, loaded once (inline + dialog providers). */
    private final MentionProviderRegistry.Contributions mentionContributions =
            new MentionProviderRegistry().load(this::getProject);
    /** Workspace-file snapshot for the {@code @} picker; shared by all session tabs. */
    private final WorkspaceFileIndex workspaceFileIndex;
    /** Inline mention triggers: built-in providers first, extension contributions after. */
    private final MentionService mentionService;
    /** Resolves @path/#Type references into capped context items for a turn. */
    private final MentionContextResolver mentionContextResolver;

    /** Session this service belongs to; null for headless/test single-agent services. */
    private final SessionService sessionService;
    private final SessionId sessionId;

    private McpConnectionService mcpConnectionService;

    private PlanTool planTool;

    private ReloadConfigTool reloadConfigTool;

    private SharedToolsComponent sharedTools;
    private WorkspaceMemoryTool workspaceMemoryTool;
    private AgentContextComponent agentContext;

    /**
     * Creates all AI services with defaults from the current Eclipse preferences.
     *
     * @param sendTrigger         callback to re-trigger the send loop (agent autonomous mode)
     * @param openInEditorCallback callback to open a file in the Eclipse editor
     * @param mcpStateChange      callback notified when MCP connected/disconnected
     * @param onAgentReload       callback invoked after agents are reloaded (e.g. to refresh the UI)
     */
    public PeonAiService(Runnable sendTrigger,
                         Consumer<IFile> openInEditorCallback,
                         Consumer<Boolean> mcpStateChange,
                         Runnable onAgentReload) {

        this(sendTrigger, openInEditorCallback, mcpStateChange, onAgentReload, LlmPreferenceInitializer.buildWithDefaults().build(), null);
    }

    public PeonAiService(Runnable sendTrigger,
            Consumer<IFile> openInEditorCallback,
            Consumer<Boolean> mcpStateChange,
            Runnable onAgentReload,
            ConfiguredChatModel configuredModel) {

        this(sendTrigger, openInEditorCallback, mcpStateChange, onAgentReload, configuredModel, null);
    }

    /**
     * @param questionPresenter presents the agent's questions in the UI (the view's question widget).
     *        Null in headless builds → no agent gets the {@link org.sterl.llmpeon.parts.tools.AskUserTool}
     *        (empty = unset).
     */
    public PeonAiService(Runnable sendTrigger,
            Consumer<IFile> openInEditorCallback,
            Consumer<Boolean> mcpStateChange,
            Runnable onAgentReload,
            ConfiguredChatModel configuredModel,
            AskUserTool.QuestionPresenter questionPresenter) {
        this(sendTrigger, openInEditorCallback, mcpStateChange, onAgentReload,
                configuredModel, questionPresenter, null, null);
    }

    /** Session-backed service: all agent histories belong to one chat session/tab. */
    public PeonAiService(Runnable sendTrigger,
            Consumer<IFile> openInEditorCallback,
            Consumer<Boolean> mcpStateChange,
            Runnable onAgentReload,
            ConfiguredChatModel configuredModel,
            AskUserTool.QuestionPresenter questionPresenter,
            SessionService sessionService,
            SessionId sessionId) {

        var config              = configuredModel.getConfig();
        this.configuredModel    = configuredModel;
        this.sessionService     = sessionService;
        this.sessionId          = sessionId;
        this.workspaceFileIndex = sessionService == null
                ? new WorkspaceFileIndex()
                : SessionServices.INSTANCE.workspaceFileIndex();
        this.mentionService     = new MentionService(mentionProviders(mentionContributions));
        this.mentionContextResolver = new MentionContextResolver(this::getProject);
        skillService            = new SkillService();
        skillService.setContributedSkills(new SkillProviderRegistry().load());
        commandService          = new CommandService();
        sharedTools             = new SharedToolsComponent(skillService, commandService);
        sharedToolService       = sharedTools.toolService();
        // Contributed tools are opt-in unless the contribution sets defaultEnabled="true".
        for (var contributedTool : new ToolProviderRegistry().load(this::getProject)) {
            try {
                sharedToolService.addContributedTool(
                        contributedTool.tool(), contributedTool.defaultEnabled());
            } catch (RuntimeException e) {
                LOG.warn("Skipping contributed tool " + contributedTool.tool().getClass().getName()
                        + ": " + e.getMessage());
            }
        }
        // In-process Java/Eclipse MCP servers — opt-in unless the server sets defaultEnabled.
        for (var registration : new McpServerProviderRegistry().load(this::getProject)) {
            for (var tool : registration.tools()) {
                try {
                    sharedToolService.addContributedTool(
                            tool, registration.server().defaultEnabled(), true);
                } catch (RuntimeException e) {
                    LOG.warn("Skipping MCP tool " + tool.getClass().getName()
                            + " from " + registration.server().name() + ": " + e.getMessage());
                }
            }
        }
        // R3 (shell-tool.md): default shell working dir = active project's disk path, evaluated at
        // call time — project switch (or unpinned selection change) takes effect without any refresh.
        sharedToolService.getTool(ShellTool.class)
                .ifPresent(t -> t.setDefaultWorkingDir(this::projectWorkDir));
        workspaceMemoryTool     = sharedTools.workspaceMemoryTool();

        planTool = new PlanTool(this);
        sharedToolService.addTool(planTool);

        // askUser (R17): one tool instance for ALL agents — the view's question widget is the
        // presenter, so Jon, dev/plan agents and custom agents share the same widget + queue-safety.
        // Null in headless builds → no agent gets the tool (empty = unset).
        if (questionPresenter != null) {
            sharedToolService.addTool(new AskUserTool(questionPresenter));
        }

        // ADR-0041 R2: agent history lives in the workspace metadata state (not ~/.peon/state).
        var stateDir = Platform.getStateLocation(Platform.getBundle(PeonConstants.PLUGIN_ID))
                .append("state").toFile().toPath();

        // ADR-0041 R3: one-shot migration of any legacy ~/.peon/state/** into the metadata state.
        // Runs before AgentService so the freshly-migrated histories are the ones the stores load.
        StateMigration.migrate(config.stateDirectory(), stateDir);
        if (sessionService != null) {
            // Import after the migration so freshly moved legacy files are included.
            SessionServices.INSTANCE.ensureLegacyImported();
        }

        agentService  = sessionService == null
                ? new AgentService(true,
                        config.getConfigDir().resolve(LlmConfig.AGENT_DIRECTORY), sharedToolService,
                        configuredModel, stateDir)
                : new AgentService(true,
                        config.getConfigDir().resolve(LlmConfig.AGENT_DIRECTORY), sharedToolService,
                        configuredModel, sessionService, sessionId);
        agentService.setContributedAgents(new AgentProviderRegistry().load());

        // R15: a successful scaffold disk write deterministically refreshes the skill view.
        scaffoldAgent = new AiScaffoldAgent(configuredModel, skillService);
        // ADR-0043: the scaffold may write into the .agents/skills dir of every open project
        // (read at validate time, R10) — in addition to the config dir.
        scaffoldAgent.setProjectSkillsRootsSupplier(this::projectSkillsRoots);
        scaffoldAgent.addTool(new SkillTool(skillService));
        // ReloadConfigTool needs agentService (already created) + skillService + commandService + config.
        // Its callback fires after the reloadAgents() inside the tool, so wrap it: re-bake the Env
        // static context first (new custom agents included), then hand off to the original UI
        // callback — the reload path never goes through updateConfig, so without this the freshly
        // loaded custom agents would have no system-prompt context at all.
        // (ADR-0032 Rev: memory is dynamic only — the re-bake carries Env exclusively.)
        reloadConfigTool = new ReloadConfigTool(agentService, skillService, commandService, config, () -> {
            initStaticContext();
            if (onAgentReload != null) onAgentReload.run();
        });
        scaffoldAgent.addTool(reloadConfigTool);

        // Add scaffold as persistent agent (survives clearAgents on reload)
        agentService.addPersistentAgent(scaffoldAgent);

        var poAgent = sessionService == null
                ? new BuildPoAgentComponent(configuredModel, this::getProject, sharedTools, stateDir).build()
                : new BuildPoAgentComponent(configuredModel, this::getProject, sharedTools,
                        sessionService, sessionId).build();

        agentService.addPersistentAgent(poAgent);

        mcpConnectionService = sessionService == null
                ? new McpConnectionService(sharedToolService, mcpStateChange)
                : new McpConnectionService(sharedToolService, mcpStateChange,
                        SessionServices.INSTANCE.mcpManager());

        // Context assembly (turn context, static bake, plan/handoff state) — pure component,
        // wired with values + lazy suppliers only (no service dependency).
        agentContext = new AgentContextComponent(this::getProject, workspaceMemoryTool, userContext,
                scaffoldAgent, sharedToolService,
                this::getActiveAgent, configuredModel::getConfig, this::getAgents,
                this::skillCatalog, this::agentCatalog, new ContextProviderRegistry().load());

        // Generic delegation: one tool, not one per specialist — the catalog tells the model which
        // delegatable agents exist. Sub-agents cannot start further agents (runtime guard + filter).
        sharedToolService.addTool(new StartAgentTool(agentService, this::get));
        // Jon's curated service is separate; give him the same generic delegation capability.
        if (poAgent.getToolService() != null) {
            poAgent.getToolService().addTool(new StartAgentTool(agentService, this::get));
        }

        updateConfig(configuredModel.getConfig());
        // Default turn-context supplier for every agent present at construction (including
        // contributed specialists). Later-created agents get it in call()/startAgent; updateConfig
        // must NOT re-wire all agents — that would clobber a custom supplier (e.g. a sub-agent).
        for (var agent : this.getAgents()) {
            agent.setTurnContextSupplier(() -> get());
        }
    }
    
    
    private List<MentionProvider> mentionProviders(MentionProviderRegistry.Contributions contributions) {
        var providers = new ArrayList<MentionProvider>();
        providers.add(new WorkspaceFileMentionProvider(workspaceFileIndex,
                () -> {
                    var project = getProject();
                    return project == null ? null : project.getName();
                }));
        providers.addAll(contributions.inline());
        return providers;
    }

    /**
     * Bakes the Env info into the static context (system prompt) of every agent — the Workspace-Memory
     * is NOT part of it anymore (ADR-0032 Rev: dynamic turn-context only).
     * Runs on every {@link #updateConfig(LlmConfig)} and after a scaffold-agent reload (wrapped
     * {@code onAgentReload} callback) — after the agent refresh — so new custom
     * agents receive it too and existing agents get a fresh list (invalidating their cached
     * system prompt). Jon's slaves share his list via {@link AiPoAgent#setStaticContext}.
     * Deliberately does NOT touch the turn-context supplier (that would clobber a custom one);
     * the default supplier is wired once in the constructor.
     */
    private void initStaticContext() {
        agentContext.initStaticContext();
    }

    /**
     * Propagates a new {@link LlmConfig} to all chat services and refreshes skills.
     * Safe to call from any thread.
     */
    public void updateConfig(LlmConfig config) {
        configuredModel.updateConfig(config);
        reloadConfigTool.updateConfig(config);
        updateActiveDiskTools(config);
        
        var dir = config.getConfigDir().resolve(LlmConfig.SKILL_DIRECTORY);
        try {
            skillService.refresh(dir);    // config slot: set + refresh (compat semantics)
            skillService.refreshAll();    // R3: the project slot is refreshed as well
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skills from " + dir, e);
        }
        dir = config.getConfigDir().resolve(LlmConfig.COMMAND_DIRECTORY);
        try {
            commandService.refresh(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load commands from " + dir, e);
        }
        dir = config.getConfigDir().resolve(LlmConfig.AGENT_DIRECTORY);
        agentService.refresh(dir);
        // After the refresh: (re-)bake the Env static context — new custom agents are included,
        // and existing agents get a fresh list, which invalidates their cached system prompt so an
        // edited AGENT.md base prompt is picked up on the next turn.
        initStaticContext();
        notifyConfigChanged();
    }

    // -------------------------------------------------------------------------
    // View-title listeners (side-effect free callbacks, fired after a change)
    // -------------------------------------------------------------------------

    public void addAgentChangeListener(Runnable listener) {
        if (listener != null) agentListeners.add(listener);
    }

    public void addConfigChangeListener(Runnable listener) {
        if (listener != null) configListeners.add(listener);
    }

    public void addProjectChangeListener(Runnable listener) {
        if (listener != null) projectListeners.add(listener);
    }

    public void notifyProjectChanged() {
        fire(projectListeners);
    }

    private void notifyConfigChanged() {
        fire(configListeners);
    }

    private void notifyAgentChanged() {
        fire(agentListeners);
    }

    private static void fire(List<Runnable> listeners) {
        for (var listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.error("View-title listener failed", e);
            }
        }
    }

    /** Drops all listeners when the owning view is disposed. */
    public void clearListeners() {
        agentListeners.clear();
        configListeners.clear();
        projectListeners.clear();
    }

    /** Re-bakes the static context (skill/agent catalogs) — e.g. after a skill enable/disable. */
    public void refreshStaticContext() {
        initStaticContext();
    }

    /** Short description catalog; bodies stay out of the prompt until skillRead. */
    private String skillCatalog() {
        return skillService.catalog();
    }

    /** Delegatable agents only — startAgent resolves the same list. */
    private String agentCatalog() {
        return getAgents().stream()
                .filter(AiAgent::isTool)
                .map(agent -> "- " + agent.getName()
                        + (StringUtil.hasValue(agent.getDescription())
                                ? ": " + agent.getDescription() : ""))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }


    private void updateActiveDiskTools(LlmConfig config) {
        sharedTools.updateActiveDiskTools(config);
    }

    public AiAgent getActiveAgent() {
        return this.agentService.getActiveAgent();
    }

    public String getActiveModel() {
        return StringUtil.hasValue(getActiveAgent().getAgentModelName())
                ? getActiveAgent().getAgentModelName()
                : getConfig().getModel();
    }

    /**
     * Switches the model of the active agent. Custom agents persist it in their {@code AGENT.md};
     * built-in agents write the matching preference slot (dev/plan/po) so the choice survives a
     * restart.
     *
     * @return {@code true} if the model changed
     */
    public boolean setActiveModel(String model) {
        var agent = getActiveAgent();
        if (agent == null || StringUtil.hasNoValue(model)) return false;
        if (agent instanceof CustomAgent) {
            return agent.setAgentModelName(model); // AGENT.md is the persistence
        }

        boolean changed;
        if (agent instanceof AiScaffoldAgent) {
            // The scaffold agent inherits the base model; its setter is a no-op by design.
            changed = configuredModel.withModel(model);
        } else {
            changed = agent.setAgentModelName(model);
        }
        if (!changed) return false;

        var slot = agent instanceof AiPoAgent ? AgentModelConfig.PO
                : (agent instanceof AiPlanAgent || agent instanceof AiReviewAgent)
                        ? AgentModelConfig.PLAN
                        : AgentModelConfig.DEV;
        var record = configuredModel.getConfig().modelConfigFor(slot).withModel(model);
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        LlmConfigSaver.saveAgentModelConfig(new EclipseLlmConfigStore(prefs), slot, record);
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            LOG.error("Failed to persist model " + model + " for " + agent.getName(), e);
        }
        return true;
    }

    /**
     * Updates the active project across all project-aware services.
     * Safe to call from any thread — each downstream setter manages its own state.
     * @return <code>true</code> if changed
     */
    public boolean setProject(IProject project) {
        agentContext.clearPlan(); // stale reference — restore on next agent activation if needed

        var projectPath = JdtUtil.pathOf(project);

        sharedTools.workspaceWriteFilesTool().setCurrentProject(project);
        sharedTools.workspaceReadFilesTool().setCurrentProject(project);
        sharedTools.eclipseGrepTool().setCurrentProject(project);
        sharedTools.javaDebugTool().setCurrentProject(project);
        // disk tools work with the disk path not eclipse path
        projectPath = JdtUtil.diskPathOf(project);
        if (projectPath != null) {
            sharedTools.diskFileWriteTool().setWorkingDir(projectPath);
            sharedTools.diskFileReadTool().setWorkingDir(projectPath);
            sharedTools.diskGrepTool().setWorkingDir(projectPath);
            sharedTools.docsLinterTool().setWorkingDir(projectPath);
        }
        // ADR-0042: project skill slot — this is the single choke point that replaces it;
        // null project = empty slot, the config slot stays untouched (R2b)
        try {
            skillService.setProjectSkillsDir(projectPath == null ? null : Path.of(projectPath).resolve(SkillService.PROJECT_SKILLS_DIR));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load project skills from " + projectPath, e);
        }
        var changed = this.userContext.setCurrentProject(project);
        if (changed) notifyProjectChanged();
        return changed;
    }

    /** ADR-0043: the {@code .agents/skills} dir of every open project, read at validate time (R10). */
    private List<Path> projectSkillsRoots() {
        return EclipseUtil.openProjects().stream()
                .map(JdtUtil::diskPathOf)
                .filter(Objects::nonNull)
                .map(diskPath -> Path.of(diskPath).resolve(SkillService.PROJECT_SKILLS_DIR))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Business logic
    // -------------------------------------------------------------------------

    /**
     * Performs the PLAN → DEV handoff: clears the developer service, adds the
     * "Start Implementation" trigger, and appends the last planner message (the
     * self-contained implementation plan) if one exists.
     * @return <code>true</code> if plan is found
     */
    public boolean onHandoff() {
        if (getActiveAgent() == null) return false;
        var toAgent = agentService.get(getActiveAgent().handoverTo());
        if (toAgent.isEmpty()) return false;

        String planText;
        String planPath;
        var plan = agentContext.planRef();
        if (plan != null) { // workspace plan ref — not disk, avoids stale project reference
            planText = agentContext.readPlan();
            planPath = JdtUtil.pathOf(plan);

            agentContext.armHandoffLine(getActiveAgent().getName(), planPath);
        } else {
            var chatPlan = getActiveAgent().getMemory().getLastOf(AiMessage.class);
            if (chatPlan == null) planText = null;
            else planText = chatPlan.text();
            
            planPath = "Handover from " + getActiveAgent().getName() + ":";
        }

        if (planText != null) {
            // and directly add the plan too ...
            toAgent.get().clear();
            toAgent.get().getMemory().add(UserMessage.from(planPath + System.lineSeparator() + planText));
            this.agentService.setActiveAgent(toAgent.get());
        }
        
        return planText != null;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void applyMcpConfig() {
        mcpConnectionService.applyConfig();
    }

    public void disconnectMcp() {
        mcpConnectionService.dispose();
    }

    // -------------------------------------------------------------------------
    // Accessors — all non-null after construction
    // -------------------------------------------------------------------------

    public IProject getProject() {
        return userContext.getCurrentProject();
    }

    /** R3: disk path of the active project (pin-aware) at call time; null = no project selected. */
    private Path projectWorkDir() {
        var diskPath = JdtUtil.diskPathOf(getProject());
        return diskPath == null ? null : Path.of(diskPath);
    }

    public LlmConfig getConfig() {
        if (configuredModel == null) {
            throw new IllegalStateException("configuredModel is not initialized");
        }
        return configuredModel.getConfig();
    }

    public PlanTool getAgentModeTools() {
        return planTool;
    }

    public ToolService getToolService() {
        var active = getActiveAgent();
        if (active != null && active.getToolService() != null) {
            return active.getToolService();
        }
        return sharedToolService;
    }

    /** Returns the shared tool service used by dev/plan/custom agents (MCP, AskUserTool, ShellTool). */
    public ToolService getSharedToolService() {
        return sharedToolService;
    }

    /** One tool with its active state for the currently selected agent/mode. */
    public record ToolStatus(String name, boolean active, boolean mcp) {}

    /**
     * The agents shown in the header status widget: when Jon (Peon-PO) is active, his
     * {@link AiPoAgent#getTeam() team} (Da Boss + the orks Da Thinka/Da Mek/Da Dok); for every other
     * agent an empty list — they show nothing. This is the <b>single</b> {@code instanceof AiPoAgent}
     * choke-point (ADR-0025): the widget pulls this list and stays type-agnostic, the {@code AiAgent}
     * interface stays lean.
     */
    public List<NamedAgent> getStatusAgents() {
        return getActiveAgent() instanceof AiPoAgent po ? po.getTeam() : List.of();
    }

    /**
     * Lists every registered tool (built-in + connected MCP) with whether it is active for the
     * currently active service. Sorted: active first, then by name. For UI introspection.
     */
    public List<ToolStatus> getToolStatus() {
        var svc = getActiveAgent();
        var result = new java.util.ArrayList<ToolStatus>();
        var ts = getToolService();
        for (var exec : ts.getExecutors()) {
            result.add(new ToolStatus(exec.getSpec().name(), svc.isToolActive(exec), exec.isMcp()));
        }
        for (var name : ts.mcpToolNames()) {
            var active = svc.isMcpToolActive(name) && svc.isMcpServerActive(ts.mcpServerForTool(name));
            result.add(new ToolStatus(name, active, true));
        }
        result.sort(java.util.Comparator
                .comparing(ToolStatus::active).reversed()
                .thenComparing(ToolStatus::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public SkillService getSkillService() {
        return skillService;
    }

    /** Trigger-based completion providers ({@code @} files, later {@code #} symbols). */
    public MentionService getMentionService() {
        return mentionService;
    }

    /** Native-picker providers (e.g. the JDT type dialog for {@code #}). */
    public List<DialogMentionProvider> getDialogMentionProviders() {
        return mentionContributions.dialogs();
    }

    public CommandService getCommandService() {
        return commandService;
    }

    public AgentService getAgentService() {
        return agentService;
    }

    public McpConnectionService getMcpConnectionService() {
        return mcpConnectionService;
    }

    public Optional<AiAgent> getAgent(String agent) {
        return this.agentService.get(agent);
    }
    
    public boolean setActiveAgent(String agent) {
        var a = this.agentService.get(agent);
        if (a.isPresent()) {
            setActiveAgent(a.get());
        }
        return a.isPresent();
    }

    public void setActiveAgent(AiAgent agent) {
        this.agentService.setActiveAgent(agent);
        preloadPlanIfNeeded();
        notifyAgentChanged();
    }

    /** Returns the tutorial text for the scaffold agent (null if already shown in this session). */
    public String getScaffoldTutorial() {
        var agent = getActiveAgent();
        if (!(agent instanceof AiScaffoldAgent)) return null;
        if (agent.getMemory().size() > 0) return null;
        return org.sterl.llmpeon.prompt.PromptLoader.load("scaffold-tutorial.md");
    }

    /**
     * A one-time Jon intro shown as a chat message when there is nothing to onboard from yet — Jon
     * active, empty memory, and no {@code docs/index.md}: if there is a map, Jon navigates it (it
     * lands in his turn context, ADR-0029); if there is none, we greet the user and explain how Jon
     * works. Returns {@code null} otherwise. Shown on activation like {@link #getScaffoldTutorial()},
     * not sent to the LLM.
     */
    public String getPoTutorial() {
        var agent = getActiveAgent();
        if (!(agent instanceof AiPoAgent)) return null;
        if (agent.getMemory().size() > 0) return null;
        var project = getProject();
        if (project == null) return null;

        var index = project.getFile("docs/index.md");
        if (index != null && index.exists()) return null; // there is a map -> Jon navigates it

        return org.sterl.llmpeon.prompt.PromptLoader.load("po-tutorial.md");
    }

    private void preloadPlanIfNeeded() {
        if (!planTool.hasPlan()) return;

        var agent = getActiveAgent();
        if (agent instanceof AiPlanAgent) {
            if (agentContext.planRef() == null) {
                agentContext.setPlan(getProject().getFile(PlanTool.OVERVIEW_FILE));
            }
        }
    }

    public void onPlanSaved(IFile planFile) {
        agentContext.setPlan(planFile);
    }

    public void clear() {
        agentContext.clearPlan();
        getActiveAgent().clear();
    }

    public void clearAll() {
        agentContext.clearPlan();
        this.agentService.getAgents().forEach(AiAgent::clear);
    }

    public List<AiAgent> getAgents() {
        return this.agentService.getAgents();
    }

    private List<ContextItem> get() {
        var result = new ArrayList<>(agentContext.turnContext());
        result.addAll(pinnedSkillContext());
        return result;
    }

    /**
     * Pinned skills ride in the turn context with a stable dedup key (body hash) so they survive
     * compaction without being re-injected every turn. Missing/disabled skills are skipped.
     */
    private List<ContextItem> pinnedSkillContext() {
        if (sessionService == null || sessionId == null || !sessionService.exists(sessionId)) {
            return List.of();
        }
        var result = new ArrayList<ContextItem>();
        for (var pinned : sessionService.get(sessionId).pinnedSkills()) {
            var skill = skillService.get(pinned.name()).orElse(null);
            if (skill == null || !skill.isEnabled()) continue;
            var body = skill.renderBody();
            var dedup = "pinned-skill:" + pinned.key() + ":" + Integer.toHexString(body.hashCode());
            result.add(new ContextItem() {
                @Override public String label() { return "Pinned skill " + pinned.name(); }
                @Override public String dedupKey() { return dedup; }
                @Override public String render() { return "Pinned skill for this session:"
                        + System.lineSeparator() + body; }
            });
        }
        return result;
    }

    public UserContext getUserContext() {
        return userContext;
    }

    public SessionService getSessionService() {
        return sessionService;
    }

    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * Calls the active agent of the current context set
     */
    public ChatResponse call(String messageToSend, AiMonitor monitor) {
        var agent = getActiveAgent();
        attachMentionContext(messageToSend);
        agent.setTurnContextSupplier(() -> get());
        return agent.call(messageToSend, monitor);
    }

    /**
     * Reads {@code @path}/{@code #Type} references from the outgoing message and adds them as
     * one-time turn-context items. Bounded and disclosed by {@link MentionContextResolver}.
     */
    private void attachMentionContext(String message) {
        if (StringUtil.hasNoValue(message)) return;
        for (var item : mentionContextResolver.resolve(message)) {
            userContext.addOneTimeOrder(item);
        }
    }
}
