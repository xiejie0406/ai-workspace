package com.ruoyi.web.controller.common;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginBody;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.web.controller.system.SysLoginController;
import com.google.code.kaptcha.Producer;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅内存/临时目录样本，不访问账号、数据库或真实验证码。 */
class LocalCaptchaDebugServiceTest
{
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    @TempDir Path temp;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final List<LocalCaptchaDebugService> services = new ArrayList<>();

    private LocalCaptchaDebugService service(boolean enabled, String address)
    {
        var service = new LocalCaptchaDebugService(enabled, address, temp.resolve("private"),
                Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC), executor);
        services.add(service);
        return service;
    }

    private MockHttpServletRequest request(int port)
    {
        var request = new MockHttpServletRequest();
        request.setLocalAddr("127.0.0.1");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalPort(port);
        return request;
    }

    private Path file(int port)
    {
        return temp.resolve("private").resolve(Integer.toString(port)).resolve(UUID + ".json");
    }

    @AfterEach void cleanup()
    {
        services.forEach(LocalCaptchaDebugService::close);
        executor.shutdownNow();
    }

    @Test void disabledDoesNotCreateAnyDirectory()
    {
        service(false, "127.0.0.1").record(request(18095), UUID, "sample");
        assertFalse(Files.exists(temp.resolve("private")));
    }

    @Test void unspecifiedOrPublicBindNeverExportsAnswers()
    {
        for (String address : List.of("", "0.0.0.0", "::", "localhost", "192.168.1.2"))
        {
            service(true, address).record(request(18095), UUID, "sample");
        }
        assertFalse(Files.exists(temp.resolve("private")));
    }

    @Test void nonlocalOrForwardedRequestNeverExportsAnswers()
    {
        var service = service(true, "127.0.0.1");
        var remote = request(18095); remote.setRemoteAddr("192.168.1.2");
        service.record(remote, UUID, "sample");
        var local = request(18095); local.setLocalAddr("192.168.1.2");
        service.record(local, UUID, "sample");
        for (String header : List.of("Forwarded", "X-Forwarded-For", "X-Real-IP"))
        {
            var forwarded = request(18095); forwarded.addHeader(header, "127.0.0.1");
            service.record(forwarded, UUID, "sample");
        }
        assertFalse(Files.exists(temp.resolve("private")));
    }

    @Test void invalidUuidCodeAndPortCannotCreateOrTraversePaths()
    {
        var service = service(true, "127.0.0.1");
        service.record(request(18095), "../outside", "sample");
        service.record(request(18095), UUID, "bad\ncode");
        service.record(request(0), UUID, "sample");
        service.record(null, UUID, "sample");
        assertFalse(Files.exists(temp.resolve("private")));
    }

    @Test void answersArePrivateTimedAndIsolatedByActualBackendPort() throws Exception
    {
        var service = service(true, "127.0.0.1");
        service.record(request(18095), UUID, "sampleA");
        service.record(request(18096), UUID, "sampleB");
        assertTrue(Files.exists(file(18095)));
        var data = JSON.parseObject(Files.readString(file(18095)));
        assertEquals(UUID, data.getString("uuid"));
        assertEquals(18095, data.getIntValue("backendPort"));
        assertEquals("sampleA", data.getString("code"));
        assertEquals("2026-10-05T00:02:00Z", data.getString("expiresAt"));
        assertEquals("sampleB", JSON.parseObject(Files.readString(file(18096))).getString("code"));
        assertPrivate(file(18095));
        service.remove(request(18095), UUID);
        assertFalse(Files.exists(file(18095)));
        assertTrue(Files.exists(file(18096)));
        service.close();
        assertFalse(Files.exists(file(18096)));
    }

    @Test void unsafeExistingDirectoryIsNotRepermissioned() throws Exception
    {
        Path root = Files.createDirectory(temp.resolve("private"));
        var posix = Files.getFileAttributeView(root, PosixFileAttributeView.class);
        if (posix != null) { posix.setPermissions(PosixFilePermissions.fromString("rwxr-xr-x")); }
        else
        {
            var acl = Files.getFileAttributeView(root, AclFileAttributeView.class);
            var entries = new ArrayList<>(acl.getAcl());
            var everyone = root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
            entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                    .setPermissions(AclEntryPermission.READ_DATA).build());
            acl.setAcl(entries);
        }
        service(true, "127.0.0.1").record(request(18095), UUID, "sample");
        assertFalse(Files.exists(file(18095)));
        if (posix != null) { assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), posix.readAttributes().permissions()); }
    }

    @Test void cleanupIsScheduledForTheOriginalCaptchaLifetime() throws Exception
    {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doReturn(mock(java.util.concurrent.ScheduledFuture.class)).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        var service = new LocalCaptchaDebugService(true, "127.0.0.1", temp.resolve("private"), Clock.systemUTC(), scheduler);
        services.add(service);
        service.record(request(18095), UUID, "sample");
        var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(2L), eq(TimeUnit.MINUTES));
        assertTrue(Files.exists(file(18095)));
        task.getValue().run();
        assertFalse(Files.exists(file(18095)));
    }

    @Test void captchaResponseKeepsOnlyOriginalImageAndUuid() throws Exception
    {
        var service = service(true, "127.0.0.1");
        var controller = new CaptchaController();
        var config = mock(ISysConfigService.class);
        when(config.selectCaptchaEnabled()).thenReturn(true);
        var producer = mock(Producer.class);
        when(producer.createText()).thenReturn("sample");
        when(producer.createImage(anyString())).thenReturn(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB));
        var redis = mock(RedisCache.class);
        ReflectionTestUtils.setField(controller, "configService", config);
        ReflectionTestUtils.setField(controller, "captchaProducer", producer);
        ReflectionTestUtils.setField(controller, "captchaProducerMath", producer);
        ReflectionTestUtils.setField(controller, "redisCache", redis);
        ReflectionTestUtils.setField(controller, "localCaptchaDebug", service);
        ReflectionTestUtils.setField(com.ruoyi.common.config.RuoYiConfig.class, "captchaType", "char");
        try
        {
            AjaxResult response = controller.getCode(new MockHttpServletResponse(), request(18095));
            assertEquals(java.util.Set.of("code", "msg", "captchaEnabled", "uuid", "img"), response.keySet());
            assertFalse(JSON.toJSONString(response).contains("sample"));
            String uuid = response.get("uuid").toString();
            assertEquals("sample", JSON.parseObject(Files.readString(file(18095).resolveSibling(uuid + ".json"))).getString("code"));
            verify(redis).setCacheObject(contains(uuid), eq("sample"), eq(2), eq(TimeUnit.MINUTES));
            when(config.selectCaptchaEnabled()).thenReturn(false);
            assertFalse(controller.getCode(new MockHttpServletResponse(), request(18095)).containsKey("uuid"));
        }
        finally { ReflectionTestUtils.setField(com.ruoyi.common.config.RuoYiConfig.class, "captchaType", "math"); }
    }

    @Test void loginAttemptAlwaysRemovesTheAuxiliaryRecordWithoutChangingTheResult() throws Exception
    {
        var service = service(true, "127.0.0.1");
        var controller = new SysLoginController();
        var login = mock(SysLoginService.class);
        ReflectionTestUtils.setField(controller, "loginService", login);
        ReflectionTestUtils.setField(controller, "localCaptchaDebug", service);
        var body = new LoginBody();
        body.setUsername("test-user"); body.setPassword("synthetic-password"); body.setCode("sample"); body.setUuid(UUID);
        when(login.login(anyString(), anyString(), anyString(), anyString())).thenReturn("synthetic-token");
        service.record(request(18095), UUID, "sample");
        assertEquals("synthetic-token", controller.login(body, request(18095)).get("token"));
        assertFalse(Files.exists(file(18095)));
        when(login.login(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalArgumentException("synthetic rejection"));
        service.record(request(18095), UUID, "sample");
        assertThrows(IllegalArgumentException.class, () -> controller.login(body, request(18095)));
        assertFalse(Files.exists(file(18095)));
    }

    @Test void windowsReadLockIsRetriedAfterTheReaderCloses() throws Exception
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var service = service(true, "127.0.0.1");
        service.record(request(18095), UUID, "sample");
        try (var lock = new java.io.RandomAccessFile(file(18095).toFile(), "r"))
        {
            service.remove(request(18095), UUID);
            assertTrue(Files.exists(file(18095)), "读锁占用时保留待清理对象");
        }
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            while (Files.exists(file(18095))) { Thread.sleep(50); }
        });
    }

    @Test void javaPrivateRecordCanBeReadByBothWindowsPowerShellVersions() throws Exception
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        String script = System.getProperty("captcha.reader.script");
        org.junit.jupiter.api.Assumptions.assumeTrue(script != null && Files.exists(Path.of(script)));
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        String uuid = java.util.UUID.randomUUID().toString().replace("-", "");
        var service = new LocalCaptchaDebugService(true, "127.0.0.1", executor);
        services.add(service);
        Path channel = Path.of(System.getProperty("user.home"), ".ruoyi-local-captcha", Integer.toString(port));
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.exists(channel), "不覆盖已有通道目录");
        try
        {
            service.record(request(port), uuid, "sample");
            assertTrue(Files.exists(channel.resolve(uuid + ".json")), "Java端必须生成当前UUID文件");
            assertPrivate(channel.resolve(uuid + ".json"));
            for (String shell : List.of("powershell.exe", "pwsh.exe"))
            {
                var process = new ProcessBuilder(shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script,
                        "-BackendPort", Integer.toString(port), "-Uuid", uuid, "-MetadataOnly")
                        .redirectErrorStream(true).start();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), shell + " 仅元信息读取应通过：" + output);
                assertTrue(output.contains("\"valid\":true"));
                assertFalse(output.contains("sample"));
            }
        }
        finally
        {
            service.remove(request(port), uuid);
            service.close();
            if (Files.exists(channel)) { Files.delete(channel); }
        }
    }

    private void assertPrivate(Path file) throws Exception
    {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) { assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions()); }
        else
        {
            var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            assertTrue(acl.getAcl().stream().filter(a -> a.type() == AclEntryType.ALLOW).allMatch(a -> a.principal().equals(aclOwner(file))));
        }
    }

    private java.nio.file.attribute.UserPrincipal aclOwner(Path file)
    {
        try { return Files.getOwner(file); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
