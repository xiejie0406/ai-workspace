package com.ruoyi.paismart.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.secret.ManagedSecretService;
import java.io.ByteArrayInputStream;
import io.minio.MinioClient;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;

class MinioStorageBoundaryTest {
    @Test void probeNeedsItsOwnSwitchAndDoesNotReadSecretWhenDisabled() {
        ManagedSecretService secrets=mock(ManagedSecretService.class);
        var store=new MinioDocumentObjectStore("https://storage.example.invalid","test","platform.paismart.minio.access-key","platform.paismart.minio.secret-key",true,false,secrets);
        assertEquals("DISABLED",store.probe().get("state"));assertEquals(false,store.probe().get("tested"));
        verifyNoInteractions(secrets);
        var missing=new MinioDocumentObjectStore("","","","",true,false,true,secrets);
        assertEquals("NOT_CONFIGURED",missing.probe().get("state"));verifyNoInteractions(secrets);
    }
    @Test void authorizedProbeOnlyChecksExistingBucketAndNeverWrites() throws Exception {
        ManagedSecretService secrets=mock(ManagedSecretService.class);MinioClient client=mock(MinioClient.class);
        var store=spy(new MinioDocumentObjectStore("https://storage.example.invalid","test","platform.paismart.minio.access-key","platform.paismart.minio.secret-key",true,false,true,secrets));
        doReturn(client).when(store).client("paismart/probe",false);when(client.bucketExists(any())).thenReturn(true);
        assertEquals("READY",store.probe().get("state"));verify(client).bucketExists(any());verifyNoMoreInteractions(client);
        reset(client);when(client.bucketExists(any())).thenThrow(new IllegalStateException("sensitive-endpoint-example"));
        var failure=store.probe();assertEquals("UNAVAILABLE",failure.get("state"));assertFalse(failure.toString().contains("sensitive-endpoint-example"));
        verify(client).bucketExists(any());verifyNoMoreInteractions(client);verifyNoInteractions(secrets);
    }
    @Test void disabledStorageDoesNotReadSecretsOrConnect() {
        ManagedSecretService secrets=mock(ManagedSecretService.class);
        var store=new MinioDocumentObjectStore("http://127.0.0.1:9","test","platform.paismart.minio.access-key","platform.paismart.minio.secret-key",false,false,secrets);
        assertThrows(ServiceException.class,()->store.open("paismart/documents/id/source.txt"));
        assertThrows(ServiceException.class,()->store.put("paismart/uploads/id/0",new ByteArrayInputStream(new byte[0]),0,"text/plain"));
        verifyNoInteractions(secrets);
    }
    @Test void writesNeedTheirOwnSwitchAndCannotTargetAnotherNamespace() {
        ManagedSecretService secrets=mock(ManagedSecretService.class);
        var store=new MinioDocumentObjectStore("http://127.0.0.1:9","test","platform.paismart.minio.access-key","platform.paismart.minio.secret-key",true,false,secrets);
        assertThrows(ServiceException.class,()->store.remove("paismart/documents/id/source.txt"));
        assertThrows(ServiceException.class,()->store.open("fashion/private/source.txt"));
        assertThrows(ServiceException.class,()->store.open("paismart/../other/source.txt"));
        verifyNoInteractions(secrets);
    }
    @Test void cannotBorrowOtherProjectsCredentialAliases() {
        ManagedSecretService secrets=mock(ManagedSecretService.class);
        var store=new MinioDocumentObjectStore("http://127.0.0.1:9","test","platform.fashion.access-key","platform.paismart.minio.secret-key",true,true,secrets);
        assertThrows(ServiceException.class,()->store.open("paismart/documents/id/source.txt"));verifyNoInteractions(secrets);
    }
    @Test void legacySourceIsDisabledUnconfiguredAndCannotReadArbitraryPaths() {
        ManagedSecretService secrets=mock(ManagedSecretService.class);
        var disabled=new LegacyMinioObjectSource(false,"http://127.0.0.1:9","uploads","platform.paismart.minio.access-key","platform.paismart.minio.secret-key",secrets);
        assertThrows(ServiceException.class,()->disabled.open("merged/"+"a".repeat(32)));verifyNoInteractions(secrets);
        var missing=new LegacyMinioObjectSource(true,"","","","",secrets);assertThrows(ServiceException.class,()->missing.open("merged/file.txt"));verifyNoInteractions(secrets);
        var enabled=new LegacyMinioObjectSource(true,"http://127.0.0.1:9","uploads","platform.paismart.minio.access-key","platform.paismart.minio.secret-key",secrets);
        assertThrows(ServiceException.class,()->enabled.open("fashion/private.txt"));assertThrows(ServiceException.class,()->enabled.open("merged/../secret.txt"));assertThrows(ServiceException.class,()->enabled.open("chunks/"+"a".repeat(32)+"/0/other"));verifyNoInteractions(secrets);
    }
}
