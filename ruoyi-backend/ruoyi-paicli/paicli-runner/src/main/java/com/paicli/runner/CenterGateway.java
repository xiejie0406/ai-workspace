package com.paicli.runner;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Files;

/** 同一分发包提供联机终端与设备进程；两个身份分别使用若依JWT和设备凭证。 */
public final class CenterGateway {
    private CenterGateway() { }
    public static int run(String[] args) {
        try {
            if (args.length > 0 && "runner".equals(args[0])) return runRunner(args);
            if(args.length>0&&"export-legacy".equals(args[0]))return (int)Class.forName("com.paicli.cli.LegacyBundleExporter").getMethod("run",String[].class).invoke(null,(Object)args);
            if (args.length > 0 && "serve".equals(args[0])) return (int) Class.forName("com.paicli.runner.CenterRuntimeApiServer")
                .getMethod("run",String[].class).invoke(null,(Object)args);
            return (int) Class.forName("com.paicli.cli.CenterCli").getMethod("run", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException wrapped) {
            System.err.println("中心终端启动失败：" + wrapped.getCause().getMessage()); return 2;
        } catch (Exception failure) { System.err.println("PaiCLI 启动失败：" + failure.getMessage()); return 2; }
    }
    private static int runRunner(String[] args) throws Exception {
        if (args.length == 2 && ("--help".equals(args[1]) || "-h".equals(args[1]))) {
            System.out.println("用法：paicli runner [--once]");
            System.out.println("必需环境：PAICLI_CENTER_URL、PAICLI_WORKSPACE_ROOT、PAICLI_RUNNER_STATE；首次绑定使用 PAICLI_RUNNER_TOKEN。");
            System.out.println("--once：连接中心后只处理一轮；状态目录必须位于工作区外。");
            return 0;
        }
        if (args.length > 2 || args.length == 2 && !"--once".equals(args[1])) throw new IllegalArgumentException("用法：paicli runner [--once]");
        String centerUrl = HttpCenterCommandGateway.requiredEnv("PAICLI_CENTER_URL");
        Path root = Path.of(HttpCenterCommandGateway.requiredEnv("PAICLI_WORKSPACE_ROOT"));
        // 指定缓存位置，不能隐式写原项目 ~/.paicli 正式数据。
        Path state = Path.of(HttpCenterCommandGateway.requiredEnv("PAICLI_RUNNER_STATE"));
        ResourceFingerprint resources=new ResourceFingerprint(root);
        if(state.toAbsolutePath().normalize().startsWith(resources.root()))throw new IOException("Runner缓存必须位于工作区外");
        state=RunnerPrivateDirectory.createDirectories(state).toRealPath();
        if(state.toRealPath().startsWith(resources.root()))throw new IOException("Runner缓存必须位于工作区外");
        DeviceCredentialCache credentials=new DeviceCredentialCache(state,centerUrl,resources.rootFingerprint());
        String deviceToken=credentials.select(System.getenv("PAICLI_RUNNER_TOKEN"));
        CenterHttpClient client = new CenterHttpClient(centerUrl, deviceToken, true);
        RunnerEngine.TaskExecutor executor = (RunnerEngine.TaskExecutor) Class.forName("com.paicli.runner.NativeTaskExecutor")
            .getConstructor(CenterHttpClient.class,Path.class).newInstance(client,state);
        try (RunnerEngine engine = new RunnerEngine(client, root, state, executor,deviceToken,credentials)) {
            engine.connect();
            do {
                boolean claimed = engine.runOnce();
                if (args.length == 2) return 0;
                if (!claimed) Thread.sleep(1000);
            } while (!Thread.currentThread().isInterrupted());
        }
        return 0;
    }
}
