package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.*;
import com.ruoyi.paiflow.mapper.*;
import com.ruoyi.system.service.ISysUserService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataRuntimeCatalogTest {
    static class Fixture {
        final DataMapper mapper=mock(DataMapper.class);final SpaceMapper spaces=mock(SpaceMapper.class);final ResourceAccessService access=mock(ResourceAccessService.class);final ISysUserService users=mock(ISysUserService.class);final SysPermissionService permissions=mock(SysPermissionService.class);
        final DataRuntimeCatalog catalog=new DataRuntimeCatalog(mapper,spaces,access,users,permissions);final SpaceRecord space=new SpaceRecord();final SpaceMember member=new SpaceMember();final DataRecord db=new DataRecord(),table=new DataRecord();final SysUser user=new SysUser();
        Fixture(){space.setId(2L);space.setDeleted(0);space.setKind("team");space.setOwnerUserId(1L);member.setSpaceId(2L);member.setUserId(1L);member.setRole(2);member.setStatus("active");db.setId(5L);db.setSpaceId(2L);db.setDeleted(0);db.setRevision(2L);table.setId(7L);table.setDbId(5L);table.setName("people");table.setRevision(1L);table.setFieldsJson("[{\"id\":1,\"name\":\"id\",\"type\":\"Integer\"},{\"id\":4,\"name\":\"name\",\"type\":\"String\"}]");user.setUserId(1L);user.setStatus("0");user.setDelFlag("0");when(users.selectUserById(1L)).thenReturn(user);when(spaces.find(2)).thenReturn(space);when(spaces.member(2,1)).thenReturn(member);when(permissions.getMenuPermission(user)).thenReturn(Set.of(DataResourceService.QUERY,DataResourceService.EDIT));when(mapper.database(5)).thenReturn(db);when(mapper.tables(5)).thenReturn(List.of(table));}
    }
    @Test void pinnedResourceExposesOnlyFixedLogicalTablesAndRejectsSchemaDrift(){var f=new Fixture();var fixed=f.catalog.bindCurrent(2,5);assertEquals("data:5:revision:2",fixed.resourceId());assertEquals("center-data",fixed.descriptor().getString("provider"));assertEquals(new JSONArray(List.of("id","name")),fixed.descriptor().getJSONObject("tables").getJSONObject("people").getJSONArray("columns"));assertSame(f.db,f.catalog.resolvePinned(2,1,fixed.resourceId(),fixed.descriptor()).database());f.table.setFieldsJson("[{\"id\":1,\"name\":\"id\",\"type\":\"Integer\"}]");assertThrows(ServiceException.class,()->f.catalog.resolvePinned(2,1,fixed.resourceId(),fixed.descriptor()));}
    @Test void revokedMenuInactiveAccountAndReaderWriteAreRejected(){var f=new Fixture();var fixed=f.catalog.bindCurrent(2,5);when(f.permissions.getMenuPermission(f.user)).thenReturn(Set.of());assertThrows(ServiceException.class,()->f.catalog.resolvePinned(2,1,fixed.resourceId(),fixed.descriptor()));when(f.permissions.getMenuPermission(f.user)).thenReturn(Set.of(DataResourceService.QUERY,DataResourceService.EDIT));f.user.setStatus("1");assertThrows(ServiceException.class,()->f.catalog.requireOwner(2,1,false));f.user.setStatus("0");f.member.setRole(3);assertThrows(ServiceException.class,()->f.catalog.requireOwner(2,1,true));f.catalog.requireOwner(2,1,false);}
    @Test void defaultPrivateSpaceCannotBeSharedByInjectedMember(){var f=new Fixture();f.space.setKind(SpaceRecord.DEFAULT_PERSONAL);f.space.setOwnerUserId(9L);f.member.setRole(1);assertThrows(ServiceException.class,()->f.catalog.requireOwner(2,1,false));}
}
