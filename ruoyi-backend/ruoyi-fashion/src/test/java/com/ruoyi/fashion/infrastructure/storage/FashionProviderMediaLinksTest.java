package com.ruoyi.fashion.infrastructure.storage;

import java.net.URI;
import java.time.*;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FashionProviderMediaLinksTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    private final String key = "materials/" + "a".repeat(64) + "/original.png";
    private FashionProviderMediaLinks links(Clock value) { return new FashionProviderMediaLinks("https://media.example.test/prod-api", "s".repeat(32), value); }
    @Test void disabledWithoutConfiguredSecret() {
        assertFalse(new FashionProviderMediaLinks("", "", clock).available());
        assertThrows(ServiceException.class, () -> new FashionProviderMediaLinks("", "", clock).sign(key, Duration.ofMinutes(15)));
    }
    @Test void signedObjectCannotBeReplacedOrReadAfterExpiry() {
        var uri = URI.create(links(clock).sign(key, Duration.ofMinutes(15)));
        assertEquals("/prod-api/fashion/provider-media", uri.getPath());
        String signature = uri.getQuery().split("signature=")[1];
        long expires = clock.instant().getEpochSecond() + 900;
        assertDoesNotThrow(() -> links(clock).verify(key, expires, signature));
        assertThrows(ServiceException.class, () -> links(clock).verify("materials/" + "b".repeat(64) + "/original.png", expires, signature));
        assertThrows(ServiceException.class, () -> links(Clock.offset(clock, Duration.ofMinutes(15))).verify(key, expires, signature));
        assertThrows(ServiceException.class, () -> links(clock).sign("../../secret.png", Duration.ofMinutes(15)));
        assertThrows(ServiceException.class, () -> links(clock).sign(key, Duration.ofHours(1)));
    }
    @Test void signatureCannotBeReusedForAnotherTask() {
        String task = "00000000-0000-0000-0000-000000000001";
        var uri = URI.create(links(clock).sign(key,Duration.ofMinutes(15),task));
        assertTrue(uri.getQuery().contains("task="+task));
        String signature = uri.getQuery().split("signature=")[1];long expires = clock.instant().getEpochSecond()+900;
        assertDoesNotThrow(() -> links(clock).verify(key,expires,signature,task));
        assertThrows(ServiceException.class, () -> links(clock).verify(key,expires,signature,"00000000-0000-0000-0000-000000000002"));
        assertThrows(ServiceException.class, () -> links(clock).verify(key,expires,signature));
    }
}
