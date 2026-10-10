package com.ruoyi.web.controller.common;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.constant.Constants;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本机验收辅助；答案只进入当前用户独占的短期文件，不进入接口或业务日志。 */
@Component
public class LocalCaptchaDebugService
{
    private static final Logger log = LoggerFactory.getLogger(LocalCaptchaDebugService.class);
    private final boolean enabled;
    private final String bindAddress;
    private final Path root;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<Path, Integer> cleanupFailures = new ConcurrentHashMap<>();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private boolean closing;

    @Autowired
    public LocalCaptchaDebugService(@Value("${ruoyi.local-captcha-debug.enabled:false}") boolean enabled,
            @Value("${server.address:}") String bindAddress,
            @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler)
    {
        this(enabled, bindAddress, Path.of(System.getProperty("user.home"), ".ruoyi-local-captcha"),
                Clock.systemUTC(), scheduler);
    }

    LocalCaptchaDebugService(boolean enabled, String bindAddress, Path root, Clock clock,
            ScheduledExecutorService scheduler)
    {
        this.enabled = enabled;
        this.bindAddress = bindAddress;
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public synchronized void record(HttpServletRequest request, String uuid, String code)
    {
        if (!allowed(request) || !validUuid(uuid) || code == null || !code.matches("[A-Za-z0-9+\\-]{1,16}"))
        {
            return;
        }
        Path file = null;
        try
        {
            ensurePrivateDirectory(root);
            Path channel = root.resolve(Integer.toString(request.getLocalPort()));
            ensurePrivateDirectory(channel);
            pruneExpired(channel);
            // 本机也限制待清理记录数，避免无限生成文件或清理任务。
            if (pending.size() >= 128)
            {
                return;
            }
            Instant created = clock.instant();
            Instant expires = created.plusSeconds(Constants.CAPTCHA_EXPIRATION * 60L);
            file = channel.resolve(uuid + ".json");
            Files.createFile(file);
            restrictNewFile(file);
            verifyPrivate(file, false);
            String content = JSON.toJSONString(Map.of("uuid", uuid, "code", code,
                    "backendPort", request.getLocalPort(), "createdAt", created.toString(),
                    "expiresAt", expires.toString()));
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
            Path saved = file;
            ScheduledFuture<?> cleanup = scheduler.schedule(() -> deleteRecord(saved),
                    Constants.CAPTCHA_EXPIRATION, TimeUnit.MINUTES);
            pending.put(file, cleanup);
        }
        catch (Exception failure)
        {
            if (file != null)
            {
                deleteRecord(file);
            }
            reportFailure();
        }
    }

    /** 登录尝试后，无论成功还是失败，都不再保留该验证码的辅助答案。 */
    public void remove(HttpServletRequest request, String uuid)
    {
        if (allowed(request) && validUuid(uuid))
        {
            deleteRecord(root.resolve(Integer.toString(request.getLocalPort())).resolve(uuid + ".json"));
        }
    }

    private boolean allowed(HttpServletRequest request)
    {
        return enabled && request != null && loopback(bindAddress)
                && loopback(request.getLocalAddr()) && loopback(request.getRemoteAddr())
                && request.getLocalPort() > 0 && request.getLocalPort() <= 65535
                // 不将代理头声明的来源当作本机授权；带转发来源的请求只走正常验证码。
                && request.getHeader("Forwarded") == null && request.getHeader("X-Forwarded-For") == null
                && request.getHeader("X-Real-IP") == null;
    }

    private static boolean loopback(String address)
    {
        if (address == null || !(address.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
                || address.matches("[0-9a-fA-F:]+")))
        {
            return false;
        }
        try
        {
            return InetAddress.getByName(address).isLoopbackAddress();
        }
        catch (Exception invalidAddress)
        {
            return false;
        }
    }

    private static boolean validUuid(String uuid)
    {
        return uuid != null && uuid.matches("[a-fA-F0-9]{32}");
    }

    private void ensurePrivateDirectory(Path directory) throws IOException
    {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
        {
            Files.createDirectory(directory);
            PosixFileAttributeView posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null)
            {
                posix.setPermissions(PosixFilePermissions.fromString("rwx------"));
            }
            else
            {
                AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (acl == null)
                {
                    throw new IOException("不支持用户独占权限");
                }
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(currentUser())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
                acl.setOwner(currentUser());
            }
        }
        verifyPrivate(directory, true);
    }

    private static UserPrincipal currentUser() throws IOException
    {
        return Path.of(System.getProperty("user.home")).getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
    }

    private static void restrictNewFile(Path file) throws IOException
    {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null)
        {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
        }
        else
        {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (acl == null) { throw new IOException("不支持用户独占权限"); }
            acl.setOwner(currentUser());
        }
    }

    private static void verifyPrivate(Path path, boolean directory) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())
                || !path.toRealPath().equals(path.toAbsolutePath().normalize())
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(currentUser()))
        {
            throw new IOException("路径或所有者不符");
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null)
        {
            if (!posix.readAttributes().permissions().equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")))
            {
                throw new IOException("目录权限不符");
            }
        }
        else
        {
            UserPrincipal owner = currentUser();
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null || acl.getAcl().stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW
                    && !entry.principal().equals(owner)))
            {
                throw new IOException("存在其他用户授权");
            }
        }
    }

    private void pruneExpired(Path channel) throws IOException
    {
        try (var files = Files.newDirectoryStream(channel, "*.json"))
        {
            for (Path file : files)
            {
                if (!file.getFileName().toString().matches("[a-fA-F0-9]{32}\\.json")) { continue; }
                verifyPrivate(file, false);
                if (Files.size(file) > 2048) { throw new IOException("辅助文件格式不符"); }
                var data = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                if (!Instant.parse(data.getString("expiresAt")).isAfter(clock.instant())) { deleteRecord(file); }
            }
        }
    }

    private synchronized void deleteRecord(Path file)
    {
        try
        {
            // 只删除当前辅助目录的精确文件，不跟随链接或递归清理。
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            {
                verifyPrivate(root, true);
                verifyPrivate(file.getParent(), true);
                verifyPrivate(file, false);
                Files.delete(file);
            }
            ScheduledFuture<?> task = pending.remove(file);
            if (task != null) { task.cancel(false); }
            cleanupFailures.remove(file);
        }
        catch (Exception failure)
        {
            // Windows 读取脚本可能短暂占用文件；保留记录并有限补试，不丢失退出清理对象。
            if (!closing && pending.containsKey(file) && cleanupFailures.merge(file, 1, Integer::sum) <= 3)
            {
                try
                {
                    ScheduledFuture<?> retry = scheduler.schedule(() -> deleteRecord(file), 1, TimeUnit.SECONDS);
                    ScheduledFuture<?> previous = pending.put(file, retry);
                    if (previous != null) { previous.cancel(false); }
                }
                catch (Exception schedulingFailure) { reportFailure(); }
            }
            else { reportFailure(); }
        }
    }

    private void reportFailure()
    {
        if (failureReported.compareAndSet(false, true))
        {
            log.warn("本机验证码辅助记录不可用，请核对用户独占目录；正常登录不受影响。");
        }
    }

    @PreDestroy
    public synchronized void close()
    {
        closing = true;
        for (Path file : List.copyOf(pending.keySet())) { deleteRecord(file); }
        pending.values().forEach(task -> task.cancel(false));
    }
}
