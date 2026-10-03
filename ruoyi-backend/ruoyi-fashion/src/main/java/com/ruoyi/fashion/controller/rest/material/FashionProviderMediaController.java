package com.ruoyi.fashion.controller.rest.material;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.infrastructure.storage.FashionProviderMediaLinks;
import com.ruoyi.fashion.infrastructure.storage.FashionProviderMediaAccess;
import com.ruoyi.fashion.infrastructure.storage.FashionUploadPolicy;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Provider凭短期签名读取单张私有素材；无列举、签发或任意对象下载接口。 */
@RestController @FashionModuleEnabled
public class FashionProviderMediaController {
    private final FashionProviderMediaLinks links;
    private final FashionObjectStoragePort storage;
    private final FashionProviderMediaAccess access;
    public FashionProviderMediaController(FashionProviderMediaLinks links, FashionObjectStoragePort storage, FashionProviderMediaAccess access) {
        this.links = links; this.storage = storage; this.access = access;
    }
    @Anonymous @GetMapping("/fashion/provider-media")
    public ResponseEntity<byte[]> get(@RequestParam String key, @RequestParam long expires, @RequestParam String signature, @RequestParam String task) {
        links.verify(key, expires, signature, task);
        access.requireCurrentPermission(key, task);
        byte[] bytes = storage.read(key);
        access.verifyBytes(key, bytes);
        var image = FashionUploadPolicy.validateImage(key.substring(key.lastIndexOf('/') + 1), bytes);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer").contentType(MediaType.parseMediaType(image.contentType())).body(bytes);
    }
}
