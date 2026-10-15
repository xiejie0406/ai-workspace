package com.paicli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import com.paicli.image.ImageReferenceParser;
import com.paicli.llm.LlmClient;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.RendererFactory;
import com.paicli.render.StatusInfo;
import com.paicli.runtime.center.CenterCommandGateway;
import com.paicli.tui.LanternaRenderer;
import com.paicli.tui.LanternaWindow;
import com.paicli.tui.TuiBootstrap;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** 中心联机CLI：所有正式写入与工具执行经Gateway，保留三种Renderer和原命令补全。 */
public final class CenterCli {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> COMMANDS = Main.slashCommandHints().stream()
            .map(hint -> hint.insertText().split("\\s+", 2)[0].substring(1))
            .collect(Collectors.toSet());
    private final CenterCommandGateway gateway;
    private final CenterCommandGateway.Session session;
    private final AtomicBoolean cancellation = new AtomicBoolean();
    private long cursor;
    private String mode;
    private boolean hitlEnabled;
    private volatile String activeTurn;
    private CenterCompletionData completion;
    private org.jline.reader.History inputHistory = new PaiCliHistory();
    private String model;
    private long contextWindow;
    private boolean exitRequested;
    private String pendingExecution;
    private Terminal interactiveTerminal;
    private LineReader interactiveReader;
    private boolean interactiveApprovals;
    private String restoredTurn;
    private String retryInput;
    private volatile boolean terminalClosing;
    private String estimatedCost;
    private String runtimeMcpSummary,runtimeSkillSummary;
    private long turnStartedNanos;
    private long contextTokens, inputTokens, outputTokens, cachedTokens;

    CenterCli(CenterCommandGateway gateway, CenterCommandGateway.Session session) {
        this.gateway = gateway;
        gateway.cancellationSignal(cancellation::get);
        this.session = session;
        this.cursor = session.lastSequence();
        this.mode = session.mode() == null ? "auto" : session.mode();
        this.hitlEnabled = "ask".equalsIgnoreCase(mode);
        this.model = session.model();
    }

    public static int run(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help) {
                System.out.println("PaiCLI 个人智能编码终端\n用法: paicli [--session <id>] [--directory <目录ID>] [--prompt <文本>|--command <命令>]\n未选目录和模型时沿用中心本人默认设置，执行宿主由中心路由。登录凭证沿用若依；中心不可用时不会回退本机执行。\n原斜杠命令、@image:<路径> 和 @clipboard 均保留。PAICLI_RENDERER=inline|lanterna|plain。");
                return 0;
            }
            CenterCommandGateway gateway = ServiceLoader.load(CenterCommandGateway.class).findFirst()
                    .orElseThrow(() -> new IOException("未安装中心Gateway实现，请使用完整CLI/Runner分发包"));
            try (gateway) {
                return run(options, gateway);
            }
        } catch (java.io.UncheckedIOException historyFailure) {
            System.err.println("PaiCLI 私有输入历史保存失败，当前输入尚未提交中心；请核对私有权限或支持限额。");return 2;
        } catch (IOException | IllegalArgumentException | java.util.ServiceConfigurationError e) {
            System.err.println("PaiCLI 联机失败: " + e.getMessage());
            return 2;
        }
    }

    static int run(Options options, CenterCommandGateway gateway) throws IOException {
        CenterCommandGateway.Session session = gateway.open(new CenterCommandGateway.OpenSession(
                options.directoryContext, options.runner, options.session));
        CenterCli cli = new CenterCli(gateway, session);
        if(gateway.browserHosted()){cli.inputHistory=HostedInputHistory.fromEnvironment();cli.inputHistory.load();}
        var presentation = gateway.command(session.id(), "status", "");
        if (presentation.successful()) {
            cli.updatePresentation(presentation.data());
            Object active=presentation.data().get("activeTaskId");
            if(active!=null&&!String.valueOf(active).isBlank())cli.restoredTurn=String.valueOf(active);
        }
        if (options.input != null) {
            try (Renderer renderer = new PlainRenderer()) {
                renderer.start();
                return cli.dispatch(options.input, renderer) ? 0 : 1;
            }
        }
        try (Terminal terminal = openInteractiveTerminal(gateway.browserHosted())) {
            RendererFactory.Mode rendering = RendererFactory.resolveMode();
            if (rendering == RendererFactory.Mode.LANTERNA && TuiBootstrap.shouldUseTui(terminal)) {
                cli.lanterna();
                return 0;
            }
            cli.interactive(terminal, rendering);
            return 0;
        }
    }
    /** 网页已经核验真实PTY，JNI加载失败须明确失败；普通CLI保留原有非TTY降级。 */
    static Terminal openInteractiveTerminal(boolean browserHosted)throws IOException{
        TerminalBuilder builder=TerminalBuilder.builder().system(true).dumb(!browserHosted);
        if(browserHosted)builder.nativeSignals(true).encoding(java.nio.charset.StandardCharsets.UTF_8);
        return builder.build();
    }

    private void interactive(Terminal terminal, RendererFactory.Mode rendering) throws IOException {
        completion = new CenterCompletionData();
        completion.refresh(gateway, session.id());
        LineReader reader = LineReaderBuilder.builder().terminal(terminal)
                .completer(completion)
                .history(inputHistory).highlighter(new PaiCliHighlighter()).build();
        if(inputHistory instanceof HostedInputHistory)inputHistory.load();
        interactiveTerminal = terminal; interactiveReader = reader; interactiveApprovals = true;
        reader.option(LineReader.Option.BRACKETED_PASTE, true);
        Main.configureJLineInteractiveWidgets(reader);
        Main.configureSlashCommandHint(reader);
        if(gateway.browserHosted()) {
            reader.getWidgets().put("paicli-browser-image",()->{reader.getBuffer().write(" @clipboard ");reader.callWidget(LineReader.REDRAW_LINE);return true;});
            for(String keyMap:List.of(LineReader.MAIN,LineReader.EMACS,LineReader.VIINS)) {
                KeyMap<org.jline.reader.Binding> keys=reader.getKeyMaps().get(keyMap);
                if(keys!=null)keys.bind(new Reference("paicli-browser-image"),KeyMap.ctrl('V'));
            }
        } else Main.bindCtrlVToClipboardImage(reader);
        Main.bindEscToClearInput(reader);
        org.jline.reader.Widget clearInput = reader.getWidgets().get("paicli-clear-input");
        reader.getWidgets().put("paicli-clear-input", () -> { pendingExecution = null; return clearInput.apply(); });
        try (Renderer renderer = RendererFactory.create(rendering, terminal)) {
            if (renderer instanceof com.paicli.render.inline.InlineRenderer inline) {
                inline.bindLineReader(reader); Main.bindCtrlOToFoldableBlocks(reader, inline);
            }
            renderer.start();
            interactiveApprovals = true;
            Main.bindSessionModeCycle(reader, () -> {
                try { cycleMode(renderer); } catch (IOException e) { renderer.stream().println("模式切换失败: " + e.getMessage()); }
            });
            terminal.handle(Terminal.Signal.INT, signal -> cancellation.set(true));
            renderer.stream().println("PaiCLI 已联机，会话 " + session.id() + "；文件目录 " + session.directoryContextId());
            followRestoredTurn(renderer);
            while (true) {
                renderer.updateStatus(status());
                renderer.beforeInput();
                String input;
                try { input = reader.readLine(renderer.inputPrompt(),renderer.inputRightPrompt(),(Character)null,retryInput); retryInput=null; }
                catch (EndOfFileException e) { return; }
                catch (UserInterruptException e) { cancellation.set(false); retryInput=null; pendingExecution=null; continue; }
                finally { renderer.afterInput(); }
                if (input == null) return;
                try { if(!dispatch(input, renderer)&&gateway.browserHosted())retryInput=input; }
                catch (IOException | IllegalArgumentException e) { renderer.stream().println("操作失败: " + e.getMessage()); if(gateway.browserHosted())retryInput=input; }
                if (exitRequested) return;
            }
        }
    }

    private void lanterna() throws IOException {
        LlmClient metadata = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { throw new IOException("TUI禁止直接调用供应商"); }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException { return chat(messages, tools); }
            public String getModelName() { return model; }
            public String getProviderName() { return session.provider(); }
        };
        LanternaWindow window = new LanternaWindow(new PaiCliConfig(), metadata, true);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> { Thread t = new Thread(runnable, "paicli-center-tui"); t.setDaemon(true); return t; });
        try (Renderer renderer = new LanternaRenderer(window)) {
            completion=new CenterCompletionData();completion.refresh(gateway,session.id());
            renderer.start();
            interactiveApprovals = true;
            window.getRootPane().setCentralPresentation(true);
            window.getRootPane().setModeChangeHandler(() -> enqueue(executor, renderer, () -> cycleMode(renderer)));
            window.getRootPane().setCancelHandler(() -> cancellation.set(true));
            window.getRootPane().setHistoryHandler(() -> enqueue(executor,renderer,()->showCenterHistory(renderer)));
            window.getRootPane().setMessageHandler(input -> {
                if (Set.of("/quit", "/exit").contains(input.trim())) { window.close(); return; }
                enqueue(executor, renderer, () -> {
                    try { if(!dispatch(input,renderer)&&gateway.browserHosted())window.runOnGuiThread(()->window.getRootPane().getInputBar().restoreFailedInput(input)); }
                    catch(IOException|IllegalArgumentException failure){if(gateway.browserHosted())window.runOnGuiThread(()->window.getRootPane().getInputBar().restoreFailedInput(input));throw failure;}
                    if (exitRequested) window.close();
                });
            });
            window.setCloseHook(() -> {
                terminalClosing=true;
                if(!gateway.browserHosted())cancellation.set(true);
                if (activeTurn != null && !gateway.browserHosted()) {
                    try { gateway.cancel(session.id(), activeTurn); } catch (IOException e) { /* 中心租约回收仍生效。 */ }
                }
                executor.shutdownNow();
            });
            renderer.updateStatus(status());
            enqueue(executor, renderer, () -> { followRestoredTurn(renderer); dispatch("/files .", renderer); });
            window.start();
        } finally { executor.shutdownNow(); }
    }

    @FunctionalInterface private interface IoTask { void run() throws IOException; }

    private void followRestoredTurn(Renderer renderer)throws IOException {
        if(restoredTurn==null)return;
        String existing=restoredTurn;restoredTurn=null;cursor=0;
        renderer.stream().println("连接已有中心任务 "+existing+"，仅续接输出，不重复提交。");
        awaitTurn(existing,renderer);
    }

    private void showCenterHistory(Renderer renderer)throws IOException {
        var result=gateway.command(session.id(),"export","");
        if(!result.successful())throw new IOException(result.message());
        Map<String,Object> data=result.data();long end=data.get("endSequence") instanceof Number n?n.longValue():0;
        long after=0;
        do {
            var page=gateway.readLedger(session.id(),after,end,100);
            if(!page.successful())throw new IOException(page.message());
            Object entries=page.data().get("entries");
            renderer.stream().println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(entries));
            long next=page.data().get("cursor") instanceof Number n?n.longValue():after;
            if(!Boolean.TRUE.equals(page.data().get("hasMore")))return;
            if(next<=after)throw new IOException("历史账本游标未推进");
            after=next;
            if(renderer.openPalette("中心历史",List.of("继续读取下一页","返回终端"))!=0)return;
        }while(true);
    }

    private void enqueue(ThreadPoolExecutor executor, Renderer renderer, IoTask task) {
        try {
            executor.execute(() -> {
                try { task.run(); } catch (IOException | IllegalArgumentException e) { renderer.stream().println("操作失败: " + e.getMessage()); }
            });
        } catch (RejectedExecutionException e) { renderer.stream().println("终端队列已满，请等待当前操作完成或取消"); }
    }

    private void cycleMode(Renderer renderer) throws IOException {
        String next = SessionMode.parse(mode).orElse(SessionMode.AUTO).next().id();
        CenterCommandGateway.CommandResult result = gateway.command(session.id(), "mode", next);
        if (result.successful()) { mode = next; hitlEnabled="ask".equals(mode); updatePresentation(result.data()); }
        renderer.stream().println(result.message());
        renderer.updateStatus(status());
    }

    boolean dispatch(String raw, Renderer renderer) throws IOException {
        return dispatch(raw,renderer,true);
    }

    private boolean dispatch(String raw, Renderer renderer, boolean mayRequestRestorePreview) throws IOException {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) return true;
        if(inputHistory instanceof HostedInputHistory&&interactiveReader==null)inputHistory.add(java.time.Instant.now(),raw);
        CliCommandParser.ParsedCommand parsed = CliCommandParser.parse(input);
        if (parsed.type() == CliCommandParser.CommandType.EXIT) {
            exitRequested = true; renderer.stream().println("已退出终端，中心任务和账本保留。"); return true;
        }
        if (parsed.type() == CliCommandParser.CommandType.HISTORY_CLEAR) {
            inputHistory.purge(); renderer.stream().println("本机输入历史已清空，中心上下文和原始账本保留。"); return true;
        }
        if (!input.startsWith("/") && parsed.type() == CliCommandParser.CommandType.CANCEL) input = "/cancel";
        if (!input.startsWith("/") && parsed.type() == CliCommandParser.CommandType.CLEAR) input = "/clear";
        if (input.startsWith("/")) {
            String[] split = input.substring(1).split("\\s+", 2);
            String name = split[0].toLowerCase(java.util.Locale.ROOT);
            String arguments = split.length == 2 ? split[1] : "";
            if (!COMMANDS.contains(name) && !Set.of("help", "cancel", "mem", "ctx", "status", "files", "output", "approval", "approve", "reject").contains(name)) {
                renderer.stream().println("未知命令: /" + name + "；使用 /help 查看完整命令。");
                return false;
            }
            if ("output".equals(name)) { CenterArtifactText.show(gateway, session.id(), arguments, renderer.stream()); return true; }
            if (arguments.isBlank() && Set.of("plan", "team").contains(name)) {
                pendingExecution = name;
                renderer.stream().println("下一条任务将使用 " + name + " 执行；输入前按 ESC 可取消，结束后恢复 " + mode + " 模式。");
                return true;
            }
            if (arguments.isBlank() && Set.of("mode", "hitl").contains(name)) {
                CenterCommandGateway.CommandResult result = gateway.command(session.id(), "status", "");
                if (result.successful()) updatePresentation(result.data());
                renderer.stream().println(result.message());
                renderer.stream().println("当前模式: " + mode + "；审批: " + (hitlEnabled ? "需确认" : "按中心策略"));
                renderer.updateStatus(status());
                return result.successful();
            }
            cancellation.set(false);
            CenterCommandGateway.CommandResult result = gateway.command(session.id(), name, arguments);
            renderer.stream().println(result.message());
            if (result.successful()) updatePresentation(result.data());
            if (result.successful() && !result.data().isEmpty() && queuedTask(result.data()) == null)
                renderCommandData(name, arguments, result.data(), renderer);
            if (result.successful() && completion != null && Set.of("model", "mcp", "skill", "config").contains(name)) completion.refresh(gateway, session.id());
            if (result.successful() && "mode".equals(name) && SessionMode.parse(arguments).isPresent()) { mode = arguments; hitlEnabled="ask".equals(mode); }
            renderer.updateStatus(status());
            if (result.successful() && result.data().get("tasks") instanceof List<?> tasks) {
                boolean complete = awaitCommandTasks(tasks, result.data().get("blocked"), renderer);
                boolean preview = tasks.stream().anyMatch(value -> value instanceof Map<?,?> row && Boolean.TRUE.equals(row.get("cleanPreviewRequired")));
                if (complete && preview) {
                    if (!mayRequestRestorePreview) { renderer.stream().println("清理预览尚未被中心接受；请核对缓存变化后重新操作，清理未执行。"); return false; }
                    return dispatch(input,renderer,false);
                }
                return complete;
            }
            String task = queuedTask(result.data());
            if (result.successful() && task != null) {
                boolean finished=awaitTurn(task, renderer);
                if(finished&&"restore".equals(name)&&Boolean.TRUE.equals(result.data().get("previewRequired"))) {
                    if (!mayRequestRestorePreview || result.data().get("snapshotMetadataId")==null) {
                        renderer.stream().println("中心尚未接受本次快照预览，恢复未执行；请核对预览冲突后重新操作。");
                        return false;
                    }
                    return dispatch("/restore id:"+result.data().get("snapshotMetadataId"),renderer,false);
                }
                if(finished&&Boolean.TRUE.equals(result.data().get("cleanPreviewRequired"))) {
                    if(!mayRequestRestorePreview){renderer.stream().println("清理预览尚未被中心接受；请核对缓存变化后重新操作，清理未执行。");return false;}
                    return dispatch(input,renderer,false);
                }
                return finished;
            }
            return result.successful();
        }
        List<CenterCommandGateway.Attachment> attachments = new ArrayList<>();
        boolean browserClipboard=gateway.browserHosted()&&java.util.regex.Pattern.compile("@clipboard(?![\\p{L}\\p{N}_])").matcher(input).find();
        String imageInput=input;
        if(browserClipboard) {
            cancellation.set(false);
            renderer.stream().println("请在网页主动确认剪贴板/上传图片；等待期间保留输入，取消不会提交任务。");
            attachments.addAll(gateway.browserAttachments(session.id(),cancellation::get));
            imageInput=input.replaceAll("@clipboard(?![\\p{L}\\p{N}_])","").trim();
            if(imageInput.isBlank())imageInput="请分析以下图片。";
        }
        LlmClient.Message message = ImageReferenceParser.userMessage(imageInput, Path.of(System.getProperty("user.dir")),gateway.browserHosted());
        if (message.contentParts() != null) {
            int index = 1;
            for (LlmClient.ContentPart part : message.contentParts()) {
                if (part.isImage()) attachments.add(new CenterCommandGateway.Attachment("image-" + index++, part.mimeType(), part.imageBase64()));
            }
        }
        if ((input.contains("@image:") || input.contains("@clipboard")) && (attachments.isEmpty()
                ||gateway.browserHosted()&&message.content()!=null&&message.content().contains("[图片引用无效:"))) {
            renderer.stream().println(message.content());
            return false;
        }
        String execution = pendingExecution == null ? mode : pendingExecution;
        String submitText=message.content();
        if(browserClipboard)submitText+="\n[图片已作为本轮用户确认附件附加；请直接观察图片，不要从路径或历史猜测图片内容。]";
        CenterCommandGateway.Turn turn = gateway.submit(session.id(), submitText, execution, List.copyOf(attachments));
        pendingExecution = null;
        return awaitTurn(turn.id(), renderer);
    }

    private boolean awaitTurn(String turnId, Renderer renderer) throws IOException {
        return awaitTask(turnId,session.id(),false,renderer);
    }

    private boolean awaitCommandTasks(List<?> tasks,Object blocked,Renderer renderer)throws IOException {
        boolean complete=true;
        if(blocked instanceof List<?> failures&&!failures.isEmpty()){renderer.stream().println("部分文件目录未能排队: "+JSON.writeValueAsString(failures));complete=false;}
        if(tasks.isEmpty()){renderer.stream().println("没有可确认完成的清理任务。");return false;}
        for(Object value:tasks) {
            if(!(value instanceof Map<?,?> row))throw new IOException("中心多任务响应缺少任务对象");
            Object id=row.get("taskId")==null?row.get("id"):row.get("taskId"),scope=row.get("sessionId");
            if(id==null||scope==null)throw new IOException("中心多任务响应缺少任务和会话归属");
            renderer.stream().println("文件目录 "+row.get("directoryContextId")+"，任务 "+id+"；等待该任务实际终态，副作用仍须中心批准。");
            complete=awaitTask(String.valueOf(id),String.valueOf(scope),true,renderer)&&complete;
        }
        return complete;
    }

    private boolean awaitTask(String turnId,String taskSession,boolean taskScoped, Renderer renderer) throws IOException {
        activeTurn = turnId;
        turnStartedNanos=System.nanoTime();
        cancellation.set(false);
        long taskCursor=0;
        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
        renderer.beginTurn();
        org.jline.terminal.Attributes attributes = interactiveTerminal == null ? null : interactiveTerminal.enterRawMode();
        try {
            while (System.nanoTime() < deadline) {
                if(terminalClosing&&gateway.browserHosted())throw new IOException("终端已分离，后台任务保留");
                if (Main.readEscOrInterruptCancel(interactiveTerminal)) cancellation.set(true);
                if (cancellation.compareAndSet(true, false)) gateway.cancel(taskSession, turnId);
                List<CenterCommandGateway.Event> events = taskScoped?gateway.taskEvents(turnId,taskCursor,200):gateway.events(session.id(), cursor, 200);
                for (CenterCommandGateway.Event event : events) {
                    if (event.sequence() <= (taskScoped?taskCursor:cursor)) continue;
                    Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
                    Object eventTask = payload.get("taskId") == null ? payload.get("turnId") : payload.get("taskId");
                    boolean sameTurn = eventTask != null && turnId.equals(String.valueOf(eventTask));
                    if(taskScoped)taskCursor=event.sequence();else cursor=event.sequence();
                    if (!sameTurn) continue;
                    if("TASK_RECONCILE_REQUIRED".equals(event.type())){renderer.stream().println("任务已暂停待核对，请在中心核对已执行工具和副作用。任务ID: "+turnId+"；不会自动重交。");return false;}
                    if (presentEvent(event.type(), payload, renderer)) continue;
                    Object delta = payload.get("delta");
                    Object content = payload.get("content");
                    if (delta != null) renderer.stream().print(String.valueOf(delta));
                    else if (content != null) renderer.stream().println(String.valueOf(content));
                    else if (payload.get("message") != null) renderer.stream().println(String.valueOf(payload.get("message")));
                    if ("APPROVAL_REQUIRED".equalsIgnoreCase(event.type()) && !interactiveApprovals) {
                        renderer.stream().println("此操作等待中心审批；请在若依审批抽屉核对完整影响后批准或拒绝。审批ID: " + payload.get("approvalId"));
                    }
                    if (event.terminal() && sameTurn) {
                        renderer.stream().println();
                        return !Set.of("TASK_FAILED", "TASK_CANCELLED").contains(event.type())
                                && !Set.of("failed", "cancelled", "rejected").contains(String.valueOf(payload.get("status")).toLowerCase(java.util.Locale.ROOT));
                    }
                }
                try { Thread.sleep(200L); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if(!gateway.browserHosted())gateway.cancel(taskSession, turnId);
                    throw new IOException(gateway.browserHosted()?"终端等待已中断，后台任务保留":"已请求取消当前任务", e);
                }
            }
            throw new IOException("等待事件超时，会话可按ID重新连接；不自动重交任务");
        } finally {
            if (attributes != null) interactiveTerminal.setAttributes(attributes);
            activeTurn = null;turnStartedNanos=0; renderer.updateStatus(status());
        }
    }

    StatusInfo status() {
        return StatusInfo.tokens(model, contextWindow, contextTokens, inputTokens, outputTokens, cachedTokens, estimatedCost,
                hitlEnabled, turnStartedNanos==0?0:TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-turnStartedNanos), activeTurn == null ? "idle" : "running")
                .withEnvironment(runtimeMcpSummary!=null?runtimeMcpSummary:completion==null?"MCP 未读取":completion.mcpSummary(),runtimeSkillSummary!=null?runtimeSkillSummary:completion==null?"Skill 未读取":completion.skillSummary())
                .withSessionMode(mode.toUpperCase(java.util.Locale.ROOT) + " shift+tab to cycle");
    }

    boolean presentEvent(String type, Map<String, Object> payload, Renderer renderer) throws IOException {
        if ("DIRECTORY_RESULT".equals(type) && renderer instanceof LanternaRenderer tui) {
            List<com.paicli.tool.ToolRegistry.DirectoryEntry> entries = JSON.convertValue(payload.get("entries"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            tui.window().runOnGuiThread(() -> tui.window().getRootPane().getFileTreePane().showCenterEntries(entries));
            return true;
        }
        Object presentation = payload.get("presentation");
        if (presentation != null) {
            switch (String.valueOf(presentation)) {
                case "THINKING_BEGIN" -> { if (renderer.supportsThinkingPanel()) renderer.beginThinking(String.valueOf(payload.getOrDefault("label", "Thinking"))); else if (renderer.rendersReasoning()) renderer.stream().println("Thinking..."); }
                case "THINKING_DELTA" -> { if (renderer.supportsThinkingPanel()) renderer.appendThinking(String.valueOf(payload.getOrDefault("delta", ""))); else if (renderer.rendersReasoning()) renderer.stream().print(String.valueOf(payload.getOrDefault("delta", ""))); }
                case "THINKING_END" -> { if (renderer.supportsThinkingPanel()) renderer.endThinking(); else if (renderer.rendersReasoning()) renderer.stream().println(); }
                case "ASSISTANT_DELTA", "ASSISTANT_END" -> { /* 正文已从原stream输出；独立事件供微信等远程文本通道。 */ }
                case "TOOL_CALLS" -> renderer.appendToolCalls(JSON.convertValue(payload.get("toolCalls"), new com.fasterxml.jackson.core.type.TypeReference<List<LlmClient.ToolCall>>() {}));
                case "DIFF" -> renderer.appendDiff((String) payload.get("path"), (String) payload.get("before"), (String) payload.get("after"));
                case "STATUS" -> {
                    StatusInfo current=JSON.convertValue(payload.get("status"),StatusInfo.class);
                    estimatedCost=current.estimatedCost();runtimeMcpSummary=current.mcpSummary();runtimeSkillSummary=current.skillSummary();
                    contextTokens=current.totalTokens();inputTokens=current.inputTokens();outputTokens=current.outputTokens();cachedTokens=current.cachedInputTokens();
                    renderer.updateStatus(current);
                }
                default -> throw new IOException("未知终端呈现事件: " + presentation);
            }
            return true;
        }
        if ("USAGE".equals(type)) { updatePresentation(Map.of("usage", payload)); renderer.updateStatus(status()); return true; }
        if (Set.of("DIRECTORY_RESULT", "SEARCH_RESULT", "GRAPH_RESULT", "INDEX_MANIFEST", "SNAPSHOT", "CONNECTION_RESULT", "HARNESS_RESULT", "PROJECT_MEMORY").contains(type)) {
            renderer.stream().println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
            if (payload.get("eventArtifactId") != null) renderer.stream().println("完整结果产物: " + payload.get("eventArtifactId") + "；SHA-256: " + payload.get("eventArtifactSha256") + "；用 /output " + payload.get("eventArtifactId") + " 查看全部正文。");
            return true;
        }
        if ("APPROVAL_REQUIRED".equalsIgnoreCase(type) && interactiveApprovals) {
            String id = String.valueOf(payload.get("approvalId"));
            var shown = gateway.command(session.id(), "approval", "show " + id);
            if (!shown.successful()) throw new IOException(shown.message());
            Map<String, Object> data = shown.data();
            Object arguments = data.get("arguments"); if (arguments == null) throw new IOException("中心审批未提供完整冻结参数");
            String args = arguments instanceof String text ? text : JSON.writeValueAsString(arguments);
            var request = new com.paicli.hitl.ApprovalRequest(String.valueOf(data.getOrDefault("toolName", payload.get("toolName"))), args,
                    String.valueOf(data.getOrDefault("dangerLevel", "中心审批")), String.valueOf(data.getOrDefault("riskDescription", "请核对完整影响")),
                    String.valueOf(data.getOrDefault("reason", "")), String.valueOf(data.getOrDefault("requesterName", "中心绑定用户")),
                    "argsHash=" + data.get("argsHash") + "；resourceHash=" + data.get("resourceHash"));
            var choice = renderer.promptApproval(request);
            String decision = switch (choice.decision()) { case APPROVED, APPROVED_ALL, APPROVED_ALL_BY_SERVER -> "APPROVE"; case REJECTED -> "REJECT"; case SKIPPED -> "SKIP"; case MODIFIED -> "REVISE"; };
            Map<String, Object> feedback = new java.util.LinkedHashMap<>();
            if (choice.reason() != null) feedback.put("feedback", choice.reason());
            if (choice.decision() == com.paicli.hitl.ApprovalResult.Decision.MODIFIED) { feedback.put("feedback", "用户在终端修改参数，请重新提案审批"); feedback.put("revisedArguments", JSON.readTree(choice.modifiedArguments())); }
            var decided = gateway.command(session.id(), "approval", "decide " + id + " " + decision + (feedback.isEmpty() ? "" : " " + JSON.writeValueAsString(feedback)));
            if (!decided.successful()) throw new IOException(decided.message()); renderer.stream().println(decided.message()); return true;
        }
        if ("PLAN_CREATED".equals(type) && (renderer instanceof LanternaRenderer ||interactiveTerminal != null && interactiveReader != null)) {
            var plan = new com.paicli.plan.ExecutionPlan(String.valueOf(payload.get("planId")), String.valueOf(payload.getOrDefault("goal", "中心计划")));
            List<Map<String, Object>> steps = JSON.convertValue(payload.get("steps"), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            for (var step : steps) plan.addTask(new com.paicli.plan.Task(String.valueOf(step.get("id")), String.valueOf(step.get("description")), com.paicli.plan.Task.TaskType.ANALYSIS,
                    JSON.convertValue(step.getOrDefault("dependencies", List.of()), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})));
            var choice = renderer instanceof LanternaRenderer
                    ? reviewLanternaPlan(plan,(LanternaRenderer)renderer)
                    : Main.reviewPlan(interactiveTerminal, interactiveReader, renderer.stream(), plan);
            if (choice.action() == com.paicli.agent.PlanExecuteAgent.PlanReviewAction.CANCEL) { gateway.cancel(session.id(), activeTurn); return true; }
            String argument = choice.action() == com.paicli.agent.PlanExecuteAgent.PlanReviewAction.EXECUTE ? "execute " + payload.get("planId")
                    : "revise " + payload.get("planId") + " " + choice.feedback();
            var result = gateway.command(session.id(), "plan", argument); if (!result.successful()) throw new IOException(result.message()); return true;
        }
        return false;
    }

    private com.paicli.agent.PlanExecuteAgent.PlanReviewDecision reviewLanternaPlan(com.paicli.plan.ExecutionPlan plan,LanternaRenderer renderer)throws IOException {
        renderer.stream().println(plan.visualize());
        int choice=renderer.openPalette("审阅中心计划",List.of("执行当前计划","补充要求并重新规划","取消计划"));
        if(choice==0)return com.paicli.agent.PlanExecuteAgent.PlanReviewDecision.execute();
        if(choice!=1)return com.paicli.agent.PlanExecuteAgent.PlanReviewDecision.cancel();
        String feedback=renderer.inputText("补充计划要求","反馈会生成新计划版本；取消返回终端","");
        return feedback==null||feedback.isBlank()?com.paicli.agent.PlanExecuteAgent.PlanReviewDecision.cancel():com.paicli.agent.PlanExecuteAgent.PlanReviewDecision.supplement(feedback);
    }

    private void renderCommandData(String name, String arguments, Map<String, Object> data, Renderer renderer) throws IOException {
        if (Set.of("mode", "hitl", "cancel", "approve", "reject", "approval").contains(name)) return;
        if("task".equals(name)&&arguments.trim().matches("log\\s+[1-9][0-9]*")){
            renderer.stream().println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data));
            String taskId=arguments.trim().split("\\s+")[1];long end=Long.parseLong(String.valueOf(data.get("endSequence")));
            long after=Long.parseLong(String.valueOf(data.getOrDefault("eventsCursor",0)));boolean more=Boolean.TRUE.equals(data.get("eventsHasMore"));
            while(more){
                var page=gateway.readTaskLog(taskId,after,end,200);if(!page.successful())throw new IOException(page.message());
                long next=Long.parseLong(String.valueOf(page.data().get("cursor")));
                if(next<=after||next>end||end!=Long.parseLong(String.valueOf(page.data().get("endSequence"))))throw new IOException("任务日志分页未推进或截止改变");
                renderer.stream().println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(page.data().get("events")));
                after=next;more=Boolean.TRUE.equals(page.data().get("hasMore"));
            }
            if(data.get("artifacts") instanceof List<?> artifacts)for(Object value:artifacts)if(value instanceof Map<?,?> artifact&&"TOOL_OUTPUT".equals(artifact.get("kind")))
                CenterArtifactText.showTask(gateway,taskId,String.valueOf(data.get("sessionId")),String.valueOf(artifact.get("id")),renderer.stream());
            renderer.stream().println("任务日志已读取至冻结截止序列 "+end+"；之后的新输出另行查询。");return;
        }
        if ("export".equals(name)) {
            var result = CenterSessionExporter.export(gateway, session.id(), data, Path.of(System.getProperty("user.home"), ".paicli", "exports"));
            if(gateway.browserHosted()){
                List<Map<String,Object>> files=new ArrayList<>();
                for(Path path:List.of(result.markdown(),result.bundle()))files.add(Map.of("name",path.getFileName().toString(),"mimeType",path.equals(result.markdown())?"text/markdown":"application/json","base64Data",java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(path))));
                var published=gateway.publishExport(session.id(),files);if(!published.successful())throw new IOException(published.message());
                if(!(published.data().get("artifacts") instanceof List<?> artifacts)||artifacts.size()!=2)throw new IOException("中心未确认两份原导出文件，保留本机文件等待核对");
                for(Path path:List.of(result.markdown(),result.bundle())){
                    String hash=com.paicli.runner.CanonicalJson.sha256(java.nio.file.Files.readAllBytes(path));long size=java.nio.file.Files.size(path);
                    boolean matched=artifacts.stream().anyMatch(value->value instanceof Map<?,?> artifact&&path.getFileName().toString().equals(artifact.get("fileName"))&&hash.equals(artifact.get("sha256"))&&artifact.get("size") instanceof Number n&&n.longValue()==size);
                    if(!matched)throw new IOException("中心导出摘要与本机原文件不符，保留原文件等待核对");
                }
                // 中心已持久化且摘要一致，删除仅本次创建的私有中间文件，避免长期TTY反复导出堆积。
                for(Path path:List.of(result.markdown(),result.bundle())){com.paicli.runner.RunnerPrivateDirectory.requireRegularFile(path);java.nio.file.Files.delete(path);}
                renderer.stream().println("导出完成：在网页终端下载区保存Markdown和完整证据包；消息 "+result.messages()+" 条，账本 "+result.ledgerEntries()+" 条");return;
            }
            renderer.stream().println("对话已导出: " + result.markdown() + "；完整证据包: " + result.bundle() + "；消息 " + result.messages() + " 条，账本 " + result.ledgerEntries() + " 条"); return;
        }
        if ("config".equals(name) && arguments.isBlank() && interactiveApprovals) {
            int selected = renderer.openPalette("中心配置", List.of("模型: " + model, "中心Provider登记", "HITL: " + mode, "中心Skill登记", "渲染器: " + renderer.getClass().getSimpleName(), "配置与凭证由若依托管"));
            if (selected < 0) return;
            renderer.stream().println(switch (selected) { case 0 -> "/model list；/model <中心模型ID>"; case 1 -> "/config provider <provider> --secret-ref <中心托管引用> [--model <模型>] [--base-url <地址>] [--default]"; case 2 -> "/mode auto|plan|ask"; case 3 -> "/skill list；/skill on|off <名称>"; case 4 -> "PAICLI_RENDERER=inline|lanterna|plain，重新连接后生效"; default -> "中心登记和批准版本决定生效配置"; });
        }
        renderer.stream().println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data));
        if(Set.of("memory","mem").contains(name))for(String id:staleMemoryIds(data,java.time.Instant.now()))
            renderer.stream().println("[可能已过时] 记忆 "+JSON.writeValueAsString(id)+" 已超过30天未核实，使用前请先核实；中心正式状态保持不变。");
    }

    static List<String> staleMemoryIds(Map<String,Object> data,java.time.Instant now) {
        List<String> ids=new ArrayList<>();Object rows=data.get("entries");if(!(rows instanceof List<?> entries))return ids;
        for(Object value:entries)if(value instanceof Map<?,?> entry&&entry.get("metadata") instanceof Map<?,?> metadata
                &&"VERIFIED".equals(metadata.get("status"))) {
            Object timestamp=entry.get("lastVerifiedAt");
            try {
                java.time.Instant verified=timestamp instanceof Number number?java.time.Instant.ofEpochMilli(number.longValue()):java.time.Instant.parse(String.valueOf(timestamp));
                if(verified.plus(java.time.Duration.ofDays(30)).isBefore(now))ids.add(String.valueOf(entry.get("id")));
            }catch(java.time.DateTimeException invalid){ /* 缺少真实日期时不制造过时结论。 */ }
        }
        return List.copyOf(ids);
    }

    static String queuedTask(Map<String, Object> data) {
        if (data == null) return null;
        for (String key : List.of("taskId", "turnId")) if (data.get(key) != null) return String.valueOf(data.get(key));
        if ("CONTROL".equals(data.get("kind")) && data.get("id") != null) return String.valueOf(data.get("id"));
        return null;
    }

    void updatePresentation(Map<String, Object> data) {
        if (data == null) return;
        Object value = data.get("model"); if (value instanceof String selected && !selected.isBlank()) {if(!selected.equals(model))estimatedCost=null;model = selected;}
        value = data.get("contextWindow"); if (value instanceof Number window && window.longValue() > 0) contextWindow = window.longValue();
        value=data.get("approvalMode");if(value instanceof String approval)hitlEnabled="ASK".equalsIgnoreCase(approval);
        value = data.get("mode");
        if (value instanceof String selected && Set.of("PLAN","TEAM").contains(selected.toUpperCase(java.util.Locale.ROOT))) mode = selected.toLowerCase(java.util.Locale.ROOT);
        else {
            value = data.get("approvalMode");
            if (value instanceof String selected && SessionMode.parse(selected).isPresent()) mode = selected.toLowerCase(java.util.Locale.ROOT);
        }
        if (data.get("usage") instanceof Map<?, ?> usage) {
            value = usage.get("inputTokens"); if (value instanceof Number n) inputTokens = n.longValue();
            value = usage.get("outputTokens"); if (value instanceof Number n) outputTokens = n.longValue();
            value = usage.get("cachedInputTokens"); if (value instanceof Number n) cachedTokens = n.longValue();
            value=usage.get("estimatedCost");if(value instanceof String cost&&!cost.isBlank())estimatedCost=cost;
        }
        value = data.get("contextTokens"); if (value instanceof Number n) contextTokens = n.longValue();
    }

    static final class Options {
        String directoryContext = setting("paicli.directory", "PAICLI_DIRECTORY_CONTEXT_ID");
        String runner = setting("paicli.runner", "PAICLI_RUNNER_ID");
        String session;
        String input;
        boolean help;
        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String value = args[i];
                if ("--help".equals(value) || "-h".equals(value)) { options.help = true; continue; }
                if (Set.of("--directory", "--runner", "--session", "--prompt", "--command").contains(value)) {
                    if (++i >= args.length) throw new IllegalArgumentException(value + " 缺少参数");
                    switch (value) {
                        case "--directory" -> options.directoryContext = args[i];
                        case "--runner" -> options.runner = args[i];
                        case "--session" -> options.session = args[i];
                        case "--prompt" -> options.input = args[i];
                        case "--command" -> options.input = args[i].startsWith("/") ? args[i] : "/" + args[i];
                        default -> throw new IllegalArgumentException(value);
                    }
                } else if ("wechat".equals(value)) {
                    options.input = "/wechat " + String.join(" ", java.util.Arrays.copyOfRange(args, i + 1, args.length));
                    break;
                } else if ("serve".equals(value)) {
                    throw new IllegalArgumentException("旧独立serve不作为生产入口；Runtime兼容API由中心受控提供");
                } else throw new IllegalArgumentException("未知启动参数: " + value);
            }
            return options;
        }
        private static String setting(String property, String environment) {
            String value = System.getProperty(property);
            return value == null || value.isBlank() ? System.getenv(environment) : value;
        }
    }
}
