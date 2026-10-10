package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;

/** 部署绑定本人身份，中心目录上下文只选择该身份已有的真实目录，不授予新的文件系统权限。 */
public final class PersonalDirectoryContext {
    private final String ownerUserId;
    private final Path home;
    private final Path runtime;
    private final String filesFailure;

    public PersonalDirectoryContext(Path personalRoot, String ownerUserId) throws IOException {
        this.ownerUserId = id(ownerUserId);
        Path deployment = RunnerPrivateDirectory.createDirectories(personalRoot).toRealPath();
        runtime=RunnerPrivateDirectory.createDirectories(deployment.resolve("users").resolve(this.ownerUserId).resolve("runtime")).toRealPath();
        home = deployment.resolve("users").resolve(this.ownerUserId).resolve("files");
        String failure=null;
        try {RunnerPrivateDirectory.createDirectories(home).toRealPath();}
        catch(IOException unavailable){failure=unavailable.getMessage();}
        filesFailure=failure;
        if (!home.startsWith(deployment)) throw new IOException("个人默认目录越过部署允许范围");
    }

    public Path home() { return home; }
    public String ownerUserId() { return ownerUserId; }
    public Path runtime(){return runtime;}
    public boolean filesReady(){try{RunnerPrivateDirectory.requireDirectory(home);return true;}catch(IOException unavailable){return false;}}
    public String filesFailure(){return filesFailure;}
    static boolean textOnly(JsonNode envelope){return envelope.path("textOnly").asBoolean(envelope.path("payload").path("textOnly").asBoolean(false));}
    public Path resolveRuntime(JsonNode envelope)throws IOException{
        if(!textOnly(envelope)||!ownerUserId.equals(envelope.path("ownerUserId").asText())
            ||envelope.hasNonNull("directoryContextId")||envelope.hasNonNull("directoryContext"))throw new IOException("纯问答身份或无目录边界不符");
        RunnerPrivateDirectory.requireDirectory(runtime);
        if(!new ResourceFingerprint(runtime).rootFingerprint().equals(envelope.path("rootFingerprint").asText()))throw new IOException("纯问答运行目录证明不符");
        return runtime;
    }

    public ObjectNode proof() throws IOException {
        ResourceFingerprint resource = new ResourceFingerprint(home);
        return CanonicalJson.MAPPER.createObjectNode().put("canonicalPath", home.toString()).put("rootFingerprint", resource.rootFingerprint());
    }

    public ObjectNode proof(Path requested)throws IOException{
        Path canonical=verifyPath(requested);
        return CanonicalJson.MAPPER.createObjectNode().put("canonicalPath",canonical.toString())
            .put("rootFingerprint",new ResourceFingerprint(canonical).rootFingerprint());
    }

    /** 校验任务或终端固定的中心上下文。普通用户和模型都不能自行提供另一人的owner。 */
    public Path resolve(JsonNode envelope) throws IOException {
        JsonNode context = envelope.path("directoryContext");
        if (!context.isObject() || !id(context.path("id").asText()).equals(id(envelope.path("directoryContextId").asText()))
            || context.path("version").asLong() < 1 || !"VERIFIED".equals(context.path("state").asText())) throw new IOException("中心未提供有效个人目录上下文");
        if (!ownerUserId.equals(context.path("ownerUserId").asText())
            || !ownerUserId.equals(envelope.path("ownerUserId").asText())) throw new IOException("目录上下文不属于当前宿主本人身份");
        Path requested;
        try { requested = Path.of(context.path("path").asText()); }
        catch (RuntimeException invalid) { throw new IOException("目录上下文路径无效", invalid); }
        Path canonical = verifyPath(requested);
        ResourceFingerprint resource = new ResourceFingerprint(canonical);
        if (!resource.rootFingerprint().equals(context.path("rootFingerprint").asText())
            || !resource.rootFingerprint().equals(envelope.path("rootFingerprint").asText())) throw new IOException("个人目录本机证明与中心冻结摘要不符");
        if (context.hasNonNull("runnerId") && !context.path("runnerId").asText().equals(envelope.path("runnerId").asText()))
            throw new IOException("目录上下文执行宿主不符");
        return canonical;
    }

    private Path verifyPath(Path requested)throws IOException{
        if (!requested.isAbsolute() || !requested.normalize().startsWith(home)) throw new IOException("目录上下文越过本人文件目录");
        Path canonical = requested.toRealPath();
        if (!canonical.equals(requested.normalize()) || !canonical.startsWith(home)) throw new IOException("目录上下文包含链接或重解析跳转");
        for (Path node = canonical; node != null && node.startsWith(home); node = node.getParent())
            if (Files.isSymbolicLink(node) || !Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) throw new IOException("目录上下文节点不是实际目录");
        RunnerPrivateDirectory.requireDirectory(home);
        return canonical;
    }

    static String id(String value) throws IOException {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IOException("个人目录身份标识无效");
        return value;
    }
}
