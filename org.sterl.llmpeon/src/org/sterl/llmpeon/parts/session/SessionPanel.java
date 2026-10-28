package org.sterl.llmpeon.parts.session;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkingSet;
import org.sterl.llmpeon.agent.AiAgent;
import org.sterl.llmpeon.agent.AiAgentStatusModel;
import org.sterl.llmpeon.agent.NamedAgent;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.command.SlashCommandResolver;
import org.sterl.llmpeon.command.SlashCommandResolver.SlashResult;
import org.sterl.llmpeon.context.SimpleContextItem;
import org.sterl.llmpeon.exception.ExceptionUtil;
import org.sterl.llmpeon.model.CompactResult;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.parts.ai.PeonAiService;
import org.sterl.llmpeon.parts.config.LlmPreferenceInitializer;
import org.sterl.llmpeon.parts.config.McpConnectionService;
import org.sterl.llmpeon.parts.config.McpPreferenceInitializer;
import org.sterl.llmpeon.parts.config.VoicePreferenceInitializer;
import org.sterl.llmpeon.parts.log.EclipseSlf4jLogger;
import org.sterl.llmpeon.parts.monitor.EclipseAiMonitor;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.parts.shell.ShellApprovalService;
import org.sterl.llmpeon.parts.widget.ActionsBarWidget;
import org.sterl.llmpeon.parts.widget.ChatMarkdownWidget;
import org.sterl.llmpeon.parts.widget.HeaderBarWidget;
import org.sterl.llmpeon.parts.widget.ModelSkillBarWidget;
import org.sterl.llmpeon.parts.widget.PinnedSkillsWidget;
import org.sterl.llmpeon.parts.widget.StatusLineWidget;
import org.sterl.llmpeon.parts.widget.UserInputWidget;
import org.sterl.llmpeon.parts.widget.UserQuestionResponseWidget;
import org.sterl.llmpeon.prompt.model.SimplePromptFile;
import org.sterl.llmpeon.session.SessionId;
import org.sterl.llmpeon.session.SessionService;
import org.sterl.llmpeon.shared.OnPartialAiResponse;
import org.sterl.llmpeon.shared.SimpleDiff;
import org.sterl.llmpeon.shared.StringUtil;
import org.sterl.llmpeon.tool.model.SimpleMessage;
import org.sterl.llmpeon.tool.model.SimpleMessage.Type;
import org.sterl.llmpeon.tool.tools.CompactSessionTool;
import org.sterl.llmpeon.voice.VoiceConfig;
import org.sterl.llmpeon.voice.VoiceInputService;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Nullable;

/**
 * One open session tab: the complete chat UI (header, history, input, model/skill bar, actions and
 * status line) bound to one session. The shell owns tabs; this panel owns the agent loop.
 */
public class SessionPanel extends Composite implements EclipseAiMonitor {

    private static final ILog LOG = Platform.getLog(SessionPanel.class);

    private final SessionService sessions;
    private final SessionId sessionId;
    private final Runnable onStateChanged;
    private final PeonAiService aiService;
    private final ShellApprovalService shellApproval;

    private final AtomicReference<IProgressMonitor> monitorRef = new AtomicReference<>(new NullProgressMonitor());
    private final AtomicInteger inFlightTurns = new AtomicInteger();
    private final VoiceInputService voiceService = new VoiceInputService();

    private volatile boolean recording = false;
    private volatile LlmConfig lastAppliedConfig = null;

    private ActionsBarWidget actionsBar;
    private StatusLineWidget statusLine;
    private HeaderBarWidget headerBar;
    private ChatMarkdownWidget chatHistory;
    private Composite inputBlock;
    private UserInputWidget chatInput;
    private ModelSkillBarWidget modelSkillBar;
    private PinnedSkillsWidget pinnedSkills;
    private UserQuestionResponseWidget questionWidget;

    public SessionPanel(Composite parent, SessionService sessions, SessionId sessionId,
            Runnable onStateChanged) {
        super(parent, SWT.NONE);
        this.sessions = sessions;
        this.sessionId = sessionId;
        this.onStateChanged = onStateChanged == null ? () -> { } : onStateChanged;

        // Materialize the session before agents restore their working contexts from it.
        sessions.open(sessionId);

        this.aiService = new PeonAiService(
            this::doSendMessage,
            file -> EclipseUtil.runInUiThread(this, () -> EclipseUtil.openInEditor(file)),
            enabled -> EclipseUtil.runInUiThread(this, () -> {
                if (statusLine != null && !statusLine.isDisposed()) statusLine.setMcpEnabled(enabled);
            }),
            () -> EclipseUtil.runInUiThread(this, this::refreshAgentUI),
            LlmPreferenceInitializer.buildWithDefaults().build(),
            this::showQuestion,
            sessions, sessionId
        );
        this.shellApproval = new ShellApprovalService(
                aiService.getSharedToolService(), this::showQuestion, aiService::getActiveAgent);

        createUi();
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public String sessionTitle() {
        return sessions.get(sessionId).title();
    }

    public boolean isWorking() {
        return inFlightTurns.get() > 0;
    }

    public boolean hasQuestion() {
        return questionWidget != null && !questionWidget.isDisposed() && questionWidget.isVisible();
    }

    private void createUi() {
        // No gap between header and chat history — they read as one surface.
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.verticalSpacing = 0;
        setLayout(rootLayout);

        headerBar = new HeaderBarWidget(this, SWT.NONE,
                () -> aiService.getActiveAgent().getName(),
                aiService::getToolStatus,
                aiService::getStatusAgents,
                this::doCompressAgent,
                () -> inFlightTurns.get() > 0);
        headerBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Borderless — a border's top edge would read as a divider against the flush header.
        chatHistory = new ChatMarkdownWidget(this, SWT.NONE);
        chatHistory.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // inputBlock carries the single outer border for the entire input area (sections 2+3+4).
        inputBlock = new Composite(this, SWT.BORDER);
        GridLayout inputBlockLayout = new GridLayout(1, false);
        inputBlockLayout.marginWidth = 0;
        inputBlockLayout.marginHeight = 0;
        inputBlockLayout.verticalSpacing = 0;
        inputBlock.setLayout(inputBlockLayout);
        GridData inputBlockData = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        inputBlockData.verticalIndent = 5;
        inputBlock.setLayoutData(inputBlockData);

        chatInput = new UserInputWidget(inputBlock, SWT.NONE,
            this::doSendMessage,
            () -> getIProgressMonitor().setCanceled(true),
            this::onMicClick);
        chatInput.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        questionWidget = new UserQuestionResponseWidget(inputBlock, SWT.NONE, this::hideQuestion);
        GridData qgd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        qgd.exclude = true;
        questionWidget.setLayoutData(qgd);
        questionWidget.setVisible(false);

        modelSkillBar = new ModelSkillBarWidget(inputBlock, SWT.NONE, aiService,
                enabled -> chatInput.setSendEnabled(enabled));
        modelSkillBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        pinnedSkills = new PinnedSkillsWidget(inputBlock, aiService, sessionId);
        pinnedSkills.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        actionsBar = new ActionsBarWidget(inputBlock, SWT.NONE,
            this::onClear,
            this::onHandoff,
            this::onAgentChange,
            this::doCompressContext
        );

        statusLine = new StatusLineWidget(inputBlock, SWT.NONE,
            this::onPinChange,
            enabled -> aiService.getMcpConnectionService().toggle(enabled)
        );

        applyConfig();
        refreshChat();
        updateSelectedProject(EclipseUtil.firstOpenOrSelectedProject());

        chatInput.enableSlashCommands(() -> {
            var result = new ArrayList<SimplePromptFile>();
            result.addAll(aiService.getCommandService().getCommands());
            result.addAll(aiService.getSkillService().getSkills());
            return result;
        });
        chatInput.enableMentions(aiService.getMentionService(), aiService.getDialogMentionProviders());
    }

    private void onClear() {
        sessions.clear(sessionId);
        aiService.clearAll();
        chatHistory.clear();
        actionsBar.updateCompact(0, aiService.getConfig().getAutoCompactAfter());
        headerBar.refreshRoster();
        notifyStateChanged();
    }

    @Override
    public void dispose() {
        if (isDisposed()) return;
        try {
            getIProgressMonitor().setCanceled(true);
            if (questionWidget != null && !questionWidget.isDisposed()) questionWidget.cancelSilently();
            aiService.clearListeners();
            aiService.disconnectMcp();
            voiceService.close();
            sessions.close(sessionId);
        } finally {
            super.dispose();
        }
    }

    public void focusInput() {
        if (questionWidget != null && questionWidget.isVisible()) questionWidget.setFocus();
        else if (chatInput != null) chatInput.setFocus();
    }

    // -------------------------------------------------------------------------
    // Eclipse selection (the shell delegates only to the active panel)
    // -------------------------------------------------------------------------

    public void applySelection(ISelection s) {
        if (s == null || s.isEmpty()) applySelection((Object)null);
        else if (s instanceof IStructuredSelection iss) {
            if (iss.size() == 1) applySelection(iss.getFirstElement());
            else applySelection(iss.toArray());
        }
    }

    public void applyTextSelection(ITextSelection ts) {
        var uc = aiService.getUserContext();
        boolean changed = false;
        if (!isDisposed()) {
            changed |= uc.setSelectedResource(EclipseUtil.getOpenFile().orElse(null));
        }
        changed |= uc.setTextSelection(ts);
        if (changed) EclipseUtil.runInUiThread(this, this::refreshStatusLine);
    }

    public void applySelection(Object o) {
        if (o instanceof ITextSelection ts) {
            applyTextSelection(ts);
            return;
        }
        if (isDisposed()) return;

        var selectionElement = EclipseUtil.selectionElement(o).orElse(null);
        if (selectionElement instanceof IType || selectionElement instanceof IClassFile) {
            if (aiService.getUserContext().setJavaType((IJavaElement) selectionElement))
                EclipseUtil.runInUiThread(this, this::refreshStatusLine);
            return;
        }
        var selection = EclipseUtil.resolveResource(selectionElement).orElse(null);
        if (selection == null && selectionElement != null && !(selectionElement instanceof IWorkingSet)
                && !selectionElement.getClass().getName().equals("org.eclipse.ui.internal.views.log.LogEntry")
                && aiService.getConfig().isDebugMode()) {
            LOG.info("Unknown resource type selected " + selectionElement.getClass());
        }
        if (aiService.getUserContext().setSelectedResource(selection)) {
            EclipseUtil.runInUiThread(this, this::refreshStatusLine);
        }
        updateSelectedProject(EclipseUtil.resolveProject(selection));
    }

    public void applySelection(Object[] selectedObjects) {
        if (selectedObjects != null && selectedObjects.length > 0) {
            applySelection(selectedObjects[0]);
        }
    }

    private void updateSelectedProject(IProject project) {
        if (project != null && !aiService.getUserContext().isProjectPinned()) {
            var changed = aiService.setProject(project);
            if (changed) refreshStatusLine();
        }
    }

    // -------------------------------------------------------------------------
    // EclipseAiMonitor
    // -------------------------------------------------------------------------

    @Override
    public void onChatMessage(int iteration, ChatRequest.Builder request) {
        chatHistory.updateLiveResponseInUIThread("waiting for AI...", 0, null);
        EclipseUtil.runInUiThread(this, () -> {
            if (!isDisposed()) headerBar.refreshRoster();
        });
    }

    @Override
    public void onChatResponse(SimpleMessage m) {
        EclipseUtil.runInUiThread(this, () -> {
            if (isDisposed()) return;
            var ai = aiService.getActiveAgent();
            if (m.role() == Type.TOOL) {
                if (m.message().startsWith(CompactSessionTool.TOOL_MESSAGE_PREFIX)
                        && m.message().contains(ai.getName())) {
                    refreshChat();
                }
                chatHistory.updateLiveResponse(m.message(), 0, "");
            }
            chatHistory.appendMessage(m);
            actionsBar.updateCompact(ai.getMemory().getTotalTokenUsed(), aiService.getConfig().getAutoCompactAfter());
            headerBar.refreshRoster();
            notifyStateChanged();
        });
    }

    @Override
    public void onCallCompleted(ChatResponse response, Duration duration) {
        EclipseUtil.runInUiThread(this, () -> {
            if (!isDisposed()) refreshStatusLine();
        });
    }

    @Override
    public void onTokenUsage(dev.langchain4j.model.output.TokenUsage usage) {
        EclipseUtil.runInUiThread(this, () -> {
            if (isDisposed()) return;
            headerBar.addTokenUsage(usage);
            headerBar.refreshRoster();
        });
    }

    @Override
    public void onStreamingChunk(OnPartialAiResponse r) {
        if (isDisposed()) return;
        EclipseUtil.runInUiThread(this, () -> {
            if (!isDisposed()) chatHistory.onStreamingChunk(r);
        });
    }

    @Override
    public void onFileUpdate(AiFileUpdate update) {
        if (isDisposed()) return;
        var diff = SimpleDiff.unifiedDiff(update.file(), update.oldContent(), update.newContent());
        EclipseUtil.runInUiThread(this, () -> {
            if (isDisposed()) return;
            if (diff.startsWith("--- a/")) chatHistory.showDiff(diff);
            else if (!diff.isEmpty()) chatHistory.appendMessage(new SimpleMessage(Type.TOOL, diff));
        });
    }

    @Override
    public IProgressMonitor getIProgressMonitor() {
        return IProgressMonitor.nullSafe(monitorRef.get());
    }

    @Override
    public boolean isCanceled() {
        return getIProgressMonitor().isCanceled();
    }

    // -------------------------------------------------------------------------
    // UI refresh
    // -------------------------------------------------------------------------

    public void refreshStatusLine() {
        if (statusLine == null || statusLine.isDisposed()) return;
        if (actionsBar == null || actionsBar.isDisposed()) return;
        var ai = aiService.getActiveAgent();
        statusLine.update(aiService.getProject(), aiService.getUserContext().getSelectedFile());
        actionsBar.updateCompact(ai.getMemory().getTotalTokenUsed(), aiService.getConfig().getAutoCompactAfter());
    }

    /** Replays the session transcript (all agents/subagents), not just the active agent memory. */
    private void refreshChat() {
        chatHistory.clear();
        sessions.get(sessionId).transcript().forEach(message -> chatHistory.appendMessage(message.message()));
        refreshStatusLine();
    }

    private void refreshAgentUI() {
        actionsBar.setAgents(aiService.getAgents());
        actionsBar.updateModeUI(aiService.getActiveAgent());
        refreshChat();
        notifyStateChanged();
    }

    /** Called by the shell's single preference listener. */
    public void applyConfig() {
        var config = LlmPreferenceInitializer.buildWithDefaults();
        EclipseSlf4jLogger.setDebug(config.isDebugMode());
        LOG.info("Set new config " + config);
        chatInput.setVoiceInputVisible(VoicePreferenceInitializer.buildWithDefaults().enabled());
        chatHistory.setShowRealtimeAiResponse(config.isShowRealtimeAiResponse());

        applyMcpConfig();
        shellApproval.applyConfiguration();

        if (lastAppliedConfig != null && lastAppliedConfig.equals(config)) return;
        lastAppliedConfig = config;
        aiService.updateConfig(config);

        actionsBar.setAgents(aiService.getAgents());
        actionsBar.updateModeUI(aiService.getActiveAgent());
        refreshStatusLine();
    }

    private void applyMcpConfig() {
        var servers = McpPreferenceInitializer.loadServers();
        statusLine.setMcpAvailable(!servers.isEmpty());
        statusLine.setMcpEnabled(McpConnectionService.isEnabled());
        aiService.applyMcpConfig();
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void onAgentChange(AiAgent mode) {
        aiService.setActiveAgent(mode);
        headerBar.refreshRoster();
        var tutorial = aiService.getScaffoldTutorial();
        if (tutorial != null) onChatResponse(new SimpleMessage(Type.AI, tutorial));
        var poTutorial = aiService.getPoTutorial();
        if (poTutorial != null) onChatResponse(new SimpleMessage(Type.AI, poTutorial));
        refreshChat();
        notifyStateChanged();
    }

    private void onHandoff() {
        if (aiService.onHandoff()) {
            AiAgent agent = aiService.getActiveAgent();
            actionsBar.updateModeUI(agent);
            refreshChat();
            doSendMessage();
        } else {
            onChatResponse(new SimpleMessage(Type.PROBLEM,
                    "Plan or Agent '" + aiService.getActiveAgent().handoverTo() + "' missing ..."));
        }
    }

    private void doCompressContext() {
        var active = aiService.getActiveAgent();
        if (active.getMemory().size() < 3) return;
        inFlightTurns.incrementAndGet();
        LOG.info("turn submit (compress): agent=" + active.getName() + " in-flight=" + inFlightTurns.get());
        lockWhileWorking(true);
        Job.create("Compact " + active.getName(), monitor -> {
            monitorRef.set(monitor);
            Exception ex = null;
            CompactResult result = null;
            try {
                result = active.compact(this);
                if (result.status() == CompactResult.Status.COMPACTED) {
                    EclipseUtil.runInUiThread(this, this::refreshChat);
                }
            } catch (Exception e) {
                ex = handleChatException(e);
            } finally {
                handleDoneChatResponse(active, active.getName(), null, monitor, ex);
            }
            return PeonConstants.status(AiAgentStatusModel.compactResult(result, active.getName()), ex);
        }).schedule();
    }

    private void doCompressAgent(NamedAgent slave) {
        var agent = slave.agent();
        if (agent.isWorking() || inFlightTurns.get() > 0) return;
        inFlightTurns.incrementAndGet();
        LOG.info("turn submit (slave compact): agent=" + slave.uiName() + " in-flight=" + inFlightTurns.get());
        lockWhileWorking(true);
        Job.create("Compact " + slave.uiName(), monitor -> {
            monitorRef.set(monitor);
            Exception ex = null;
            CompactResult result = null;
            try {
                result = agent.compact(this);
                EclipseUtil.runInUiThread(this, headerBar::refreshRoster);
            } catch (Exception e) {
                ex = handleChatException(e);
            } finally {
                handleDoneChatResponse(agent, slave.uiName(), null, monitor, ex);
            }
            return PeonConstants.status(AiAgentStatusModel.compactResult(result, slave.uiName()), ex);
        }).schedule();
    }

    private sealed interface SendDecision {
        record Skip() implements SendDecision {}
        record Submit(String messageOrNull) implements SendDecision {}
    }

    private void doSendMessage() {
        if (StringUtil.hasNoValue(aiService.getActiveModel())) {
            chatHistory.appendMessage(new SimpleMessage(Type.PROBLEM,
                    "No model configured — open Window > Preferences > Peon AI"));
            return;
        }

        var active = aiService.getActiveAgent();
        final var text = StringUtil.strip(chatInput.getText().trim());
        if (StringUtil.hasNoValue(text) && active.getMemory().size() == 0) return;

        String messageToSend = null;
        if (StringUtil.hasValue(text)) {
            var decision = resolveOutgoingMessage(text, active);
            if (decision instanceof SendDecision.Skip) return;
            messageToSend = ((SendDecision.Submit) decision).messageOrNull();
        } else if (active.isWorking()) {
            return;
        }
        submitAiJob(messageToSend);
    }

    private SendDecision resolveOutgoingMessage(String text, AiAgent active) {
        var resolver = new SlashCommandResolver();
        var result = resolver.resolve(text, aiService.getCommandService(), aiService.getSkillService());

        String trailing;
        if (result.isPresent()) {
            SlashResult r = result.get();
            var label = r.isSkill() ? "Using 🧩: " + r.name() : "Using 🪄: " + r.name();
            aiService.getUserContext().addOneTimeOrder(new SimpleContextItem(label, r.body()));
            chatHistory.appendMessage(new SimpleMessage(Type.TOOL, label));
            trailing = StringUtil.hasValue(r.trailingText()) ? r.trailingText() : null;
            if (trailing != null) chatHistory.appendMessage(new SimpleMessage(Type.USER, trailing));
            chatInput.dismissSlashMenu();
        } else {
            trailing = text;
            chatHistory.appendMessage(new SimpleMessage(Type.USER, text));
        }
        chatInput.clearText();
        sessions.suggestTitle(sessionId, text);

        if (active.isWorking()) {
            if (trailing != null) {
                boolean isNewEntry = active.queueMessage(trailing);
                if (isNewEntry) {
                    chatHistory.appendMessage(new SimpleMessage(Type.AI,
                            "Noted, I will respond as soon as I finished..."));
                }
                chatHistory.updateLiveResponse("Noted user message ...", 0, "");
            }
            return new SendDecision.Skip();
        }
        return new SendDecision.Submit(trailing);
    }

    private void submitAiJob(String messageToSend) {
        final var agent = aiService.getActiveAgent();
        inFlightTurns.incrementAndGet();
        LOG.info("turn submit: agent=" + agent.getName() + " in-flight=" + inFlightTurns.get());
        lockWhileWorking(true);
        Job.create("Peon AI request", monitor -> {
            monitor.beginTask("Arbeit, Arbeit!", 100);
            monitorRef.set(monitor);
            Exception ex = null;
            ChatResponse cr = null;
            try {
                cr = aiService.call(messageToSend, this);
            } catch (Exception e) {
                ex = handleChatException(e);
            } finally {
                handleDoneChatResponse(null, agent.getName(), cr, monitor, ex);
            }
            return PeonConstants.status("Peon AI\n" + aiService.getConfig(), ex);
        }).schedule();
    }

    private void handleDoneChatResponse(@Nullable AiAgent compactedAgent, String agentName,
            @Nullable ChatResponse cr, IProgressMonitor monitor, Exception ex) {
        if (aiService.getConfig().isDebugMode()) {
            LOG.info("Chatreponse for " + agentName + ": " + (cr == null ? "null" : cr.aiMessage()));
        }
        monitor.done();
        EclipseUtil.runInUiThread(this, () -> {
            if (isDisposed()) return;
            int remaining = inFlightTurns.decrementAndGet();
            if (remaining < 0) {
                LOG.error("unbalanced in-flight turn counter: " + remaining + " — cleaning up");
                inFlightTurns.set(0);
            }
            if (remaining <= 0) {
                monitorRef.set(new NullProgressMonitor());
                lockWhileWorking(false);
                actionsBar.updateCompact(
                        aiService.getActiveAgent().getMemory().getTotalTokenUsed(),
                        aiService.getConfig().getAutoCompactAfter());
                if (compactedAgent != null
                        && compactedAgent == aiService.getActiveAgent()
                        && compactedAgent.getQueuedMessageCount() > 0) {
                    submitAiJob(null);
                }
            }
            notifyStateChanged();
        });
    }

    private Exception handleChatException(Exception e) {
        if (e == null) return null;
        if (isCanceled()) return null;
        if (ExceptionUtil.isCanceled(e)) return null;
        if (ExceptionUtil.isRateLimit(e)) {
            onChatResponse(new SimpleMessage(Type.PROBLEM, "API rate limit! " + e.getMessage()));
            return null;
        }
        LOG.warn("Failed to call LLM " + aiService.getConfig(), e);
        if (aiService.getConfig().isDebugMode()) {
            aiService.getActiveAgent().getMemory().printMessages();
        }
        onChatResponse(new SimpleMessage(Type.PROBLEM, e.getMessage()));
        return e;
    }

    private void onPinChange(boolean pinned) {
        aiService.getUserContext().setProjectPinned(pinned);
        if (!pinned && aiService.getUserContext().getSelectedResource() != null) {
            var project = EclipseUtil.resolveProject(aiService.getUserContext().getSelectedResource());
            if (project != null) aiService.setProject(project);
        }
        statusLine.setPinned(pinned);
        aiService.notifyProjectChanged();
        refreshStatusLine();
    }

    private void lockWhileWorking(boolean value) {
        if (isDisposed()) return;
        actionsBar.lockWhileWorking(value);
        chatInput.isWorking(value);
        if (modelSkillBar != null) modelSkillBar.setEnabled(!value);
        headerBar.refreshRoster();
        if (!value) chatHistory.hideLiveStatus();
        if (!value && questionWidget != null && questionWidget.isVisible()) {
            questionWidget.cancel();
        }
        notifyStateChanged();
    }

    private void showQuestion(String question, List<String> answers,
            java.util.function.Consumer<String> onAnswer) {
        EclipseUtil.runInUiThread(this, () -> {
            if (isDisposed()) return;
            chatHistory.hideLiveStatus();
            ((GridData) chatInput.getLayoutData()).exclude = true;
            chatInput.setVisible(false);
            ((GridData) questionWidget.getLayoutData()).exclude = false;
            questionWidget.setVisible(true);
            chatHistory.appendMessage(new SimpleMessage(Type.QUESTION, question));
            questionWidget.showQuestion(answers, a -> {
                chatHistory.appendMessage(new SimpleMessage(Type.USER, a));
                onAnswer.accept(a);
            });
            inputBlock.layout(true, true);
            inputBlock.getParent().layout(new Control[]{ inputBlock });
            chatHistory.scrollToBottom();
            notifyStateChanged();
        });
    }

    private void hideQuestion() {
        ((GridData) chatInput.getLayoutData()).exclude = false;
        chatInput.setVisible(true);
        ((GridData) questionWidget.getLayoutData()).exclude = true;
        questionWidget.setVisible(false);
        questionWidget.hideQuestion();
        inputBlock.layout(true, true);
        inputBlock.getParent().layout(new Control[]{ inputBlock });
        chatHistory.updateLiveResponseInUIThread("waiting for AI...", 0, null);
        notifyStateChanged();
    }

    private void onMicClick() {
        if (!recording) {
            recording = true;
            chatInput.setRecording(true);
            try {
                VoiceConfig voice = VoicePreferenceInitializer.buildWithDefaults()
                        .resolve(aiService.getConfig());
                voiceService.startRecording(voice);
            } catch (Exception e) {
                recording = false;
                chatInput.setRecording(false);
                onChatResponse(new SimpleMessage(Type.PROBLEM, "Cannot open microphone: " + e.getMessage()));
            }
        } else {
            recording = false;
            chatInput.setRecording(false);
            Job.create("Transcribing audio", monitor -> {
                try {
                    String text = voiceService.stopAndTranscribe();
                    EclipseUtil.runInUiThread(this, () -> {
                        chatInput.setText(text);
                        doSendMessage();
                    });
                } catch (Exception e) {
                    return PeonConstants.errorStatus("Transcription failed", e);
                }
                return PeonConstants.okStatus("Transcription finished.");
            }).schedule();
        }
    }

    private void notifyStateChanged() {
        try {
            onStateChanged.run();
        } catch (RuntimeException e) {
            LOG.warn("Session tab state listener failed", e);
        }
    }
}
