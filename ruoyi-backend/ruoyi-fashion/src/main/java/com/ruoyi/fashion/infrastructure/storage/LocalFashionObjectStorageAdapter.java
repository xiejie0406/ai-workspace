package com.ruoyi.fashion.infrastructure.storage;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.application.material.port.StoredFashionObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 私有本地对象存储适配器；对象键严格受限且不覆盖已有内容。 */
@FashionModuleEnabled
@Component
public final class LocalFashionObjectStorageAdapter implements FashionObjectStoragePort {
    private final Path root;
    private final FashionProviderMediaLinks mediaLinks;

    @Autowired
    public LocalFashionObjectStorageAdapter(@Value("${ruoyi.profile}") String profile, FashionProviderMediaLinks mediaLinks) {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("ruoyi.profile 不能为空");
        }
        this.root = Path.of(profile).toAbsolutePath().normalize().resolve("fashion-private");
        this.mediaLinks = mediaLinks;
    }

    public LocalFashionObjectStorageAdapter(String profile) {
        this(profile, new FashionProviderMediaLinks("", "", java.time.Clock.systemUTC()));
    }

    @Override public boolean supportsTemporaryReadUrl() { return mediaLinks.available(); }

    @Override public String temporaryReadUrl(String objectKey, Duration validity) {
        throw new ServiceException("试衣素材链接必须绑定具体任务");
    }

    @Override public String temporaryReadUrl(String objectKey, Duration validity, String taskId) {
        if (!Files.isRegularFile(resolve(objectKey))) throw new ServiceException("私有素材不存在");
        return mediaLinks.sign(objectKey, validity, taskId);
    }

    @Override
    public StoredFashionObject putIfAbsent(String objectKey, byte[] content, String contentType) {
        Path target = resolve(objectKey);
        String sha256 = FashionHashing.sha256(content);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                byte[] existing = Files.readAllBytes(target);
                if (!FashionHashing.sha256(existing).equals(sha256)) {
                    throw new ServiceException("对象键已存在但内容摘要不同");
                }
            } else {
                Path temporary = Files.createTempFile(target.getParent(), ".fashion-upload-", ".tmp");
                try {
                    Files.write(temporary, content);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException exception) {
                        Files.move(temporary, target);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            return new StoredFashionObject(objectKey, sha256, content.length, contentType);
        } catch (IOException exception) {
            throw new ServiceException("Fashion 私有文件保存失败");
        }
    }

    @Override
    public byte[] read(String objectKey) {
        Path target = resolve(objectKey);
        try {
            if (!Files.isRegularFile(target)) {
                throw new ServiceException("私有对象不存在");
            }
            return Files.readAllBytes(target);
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("Fashion 私有文件读取失败");
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || !objectKey.matches("[a-z0-9][a-z0-9/_.-]{0,510}")) {
            throw new ServiceException("对象键不符合 Fashion 白名单");
        }
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new ServiceException("对象键越界");
        }
        return target;
    }
}
