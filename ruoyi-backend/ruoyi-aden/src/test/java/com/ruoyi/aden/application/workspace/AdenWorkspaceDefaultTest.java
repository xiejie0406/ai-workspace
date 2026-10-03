package com.ruoyi.aden.application.workspace;

import com.ruoyi.aden.application.error.*;
import com.ruoyi.aden.application.security.AdenOperatorPrincipal;
import com.ruoyi.aden.domain.workspace.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdenWorkspaceDefaultTest {
    private final AdenWorkspaceRepository repository=mock(AdenWorkspaceRepository.class);
    private final AdenOperatorPrincipal reader=new AdenOperatorPrincipal(42,"viewer",Set.of("aden:collection:read"));
    private static final String WS="11111111-1111-4111-8111-111111111111";
    private AdenWorkspaceService service(String configured){return new AdenWorkspaceService(repository,()->UUID.randomUUID().toString(),Clock.systemUTC(),configured);}
    private static AdenWorkspaceMembership member(){
        Instant now=Instant.parse("2026-10-01T01:00:00Z");
        return new AdenWorkspaceMembership(new AdenWorkspace(new AdenWorkspaceId(WS),"已授权范围",AdenWorkspaceStatus.ACTIVE,1,42,now,now),42,AdenWorkspaceRole.VIEWER,AdenWorkspaceMemberStatus.ACTIVE,now);
    }
    @Test void collectionReaderUsesOnlySoleExistingMembership(){
        when(repository.findActiveByUserId(42,2)).thenReturn(List.of(member()));
        assertEquals(WS,service("").resolveDefault(reader).workspace().id().value());
        verify(repository).findActiveByUserId(42,2);verifyNoMoreInteractions(repository);
    }
    @Test void configuredDefaultMustBeEffectiveAndDoesNotFallBack(){
        when(repository.findActiveMembership(new AdenWorkspaceId(WS),42)).thenReturn(Optional.of(member()));
        assertEquals(WS,service(WS).resolveDefault(reader).workspace().id().value());
        verify(repository,never()).findActiveByUserId(anyLong(),anyInt());
    }
    @Test void invalidConfiguredDefaultDoesNotChooseAnotherMembership(){
        when(repository.findActiveMembership(new AdenWorkspaceId(WS),42)).thenReturn(Optional.empty());
        assertEquals("ADEN_DEFAULT_WORKSPACE_UNAVAILABLE",assertThrows(AdenApplicationException.class,()->service(WS).resolveDefault(reader)).errorCode());
        verify(repository,never()).findActiveByUserId(anyLong(),anyInt());
        assertThrows(AdenApplicationException.class,()->service("bad-id").resolveDefault(reader));
    }
    @Test void zeroOrMultipleMembershipsFailWithoutWrites(){
        for(var memberships:List.of(List.<AdenWorkspaceMembership>of(),List.of(member(),member()))){
            when(repository.findActiveByUserId(42,2)).thenReturn(memberships);
            assertThrows(AdenApplicationException.class,()->service("").resolveDefault(reader));
        }
        verify(repository,never()).insertWorkspace(any());verify(repository,never()).insertInitialOwner(any());
    }
    @Test void authenticatedUserWithoutCollectionOrWorkspaceReadCannotDiscoverMemberships(){
        assertThrows(AdenAccessDeniedException.class,()->service("").resolveDefault(new AdenOperatorPrincipal(43,"no-access",Set.of())));
        verifyNoInteractions(repository);
    }
}
