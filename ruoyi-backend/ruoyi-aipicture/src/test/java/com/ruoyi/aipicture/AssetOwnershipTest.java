package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.service.*;
import com.ruoyi.aipicture.storage.*;

class AssetOwnershipTest {
    @Test void onlyReadyOwnerBoundAssetsCanBecomePublicImagesOrFormalInputs() throws Exception {
        var db=mock(AiPictureRepository.class);var storage=mock(AiPictureStorage.class);var service=new AssetService(db,storage);String id=Json.id();
        for(String state:List.of("staged","exported")) {
            when(db.owned("aip_asset",id,8,false)).thenReturn(Json.of("id",id,"status",state,"object_key","private/object.png"));
            assertAll(state,()->assertThrows(AiPictureException.class,()->service.record(8,id)),()->assertThrows(AiPictureException.class,()->service.get(8,id)),()->assertThrows(AiPictureException.class,()->service.download(8,id)));
        }
        verifyNoInteractions(storage);when(db.owned("aip_asset",id,8,false)).thenReturn(Json.of("id",id,"status","ready","format","png","object_key","private/object.png"));when(storage.read("private/object.png")).thenReturn(new byte[]{1,2});
        assertEquals("ready",service.record(8,id).get("status"));assertEquals(id,service.get(8,id).get("id"));assertArrayEquals(new byte[]{1,2},service.download(8,id));
    }
    @Test void privateDownloadAlwaysReadsOwnerBoundRecordBeforeObject() throws Exception {
        var db=mock(AiPictureRepository.class);var storage=mock(AiPictureStorage.class);var service=new AssetService(db,storage);String id=Json.id();
        when(db.owned("aip_asset",id,8,false)).thenThrow(AiPictureException.missing());assertThrows(AiPictureException.class,()->service.download(8,id));verifyNoInteractions(storage);
    }
    @Test void dtoPreservesSourceKindsFormatAndAuthenticatedRouteWithoutObjectKey() {
        var service=new AssetService(mock(AiPictureRepository.class),mock(AiPictureStorage.class));String id=Json.id();var dto=service.dto(Json.of("id",id,"kind","marketing","source","tool","format","png","created_at",0,"user_id",1,"object_key","private/secret.png"));
        assertEquals("marketing",dto.get("kind"));assertEquals("PNG",dto.get("image_format"));assertEquals("1970-01-01T00:00:00Z",dto.get("created_at"));assertFalse(dto.containsKey("object_key"));assertFalse(dto.containsKey("user_id"));assertEquals("/aipicture/assets/"+id+"/download",dto.get("url"));
    }
    @Test void repositoryRejectsNonOwnedQueryAndUnknownTableName() {
        var db=new AiPictureRepository(mock(NamedParameterJdbcTemplate.class));assertThrows(IllegalArgumentException.class,()->db.owned("sys_user",Json.id(),1,false));
    }
    @Test void libraryHidesWorkingLayersWhileSessionWallKeepsAllAssets() {
        var db=mock(AiPictureRepository.class);var service=new AssetService(db,mock(AiPictureStorage.class));String session=Json.id(),cover=Json.id();var assets=new ArrayList<Map<String,Object>>();for(String kind:List.of("original","generated","marketing","export","mask","subject","background")) assets.add(Json.of("id",kind.equals("original")?cover:Json.id(),"kind",kind,"format","png","status","ready"));
        when(db.query(startsWith("SELECT id,title,current_asset_id,updated_at"),anyMap())).thenReturn(new ArrayList<>(List.of(Json.of("id",session,"current_asset_id",cover))));when(db.query(startsWith("SELECT a.* FROM aip_session_asset"),anyMap())).thenReturn(assets);when(db.owned("aip_asset",cover,8,false)).thenReturn(assets.get(0));
        var library=service.library(8);assertEquals(List.of("original","generated","marketing","export"),Json.list(library.get(0).get("assets")).stream().map(a->Json.map(a).get("kind")).toList());assertEquals(7,service.wall(8,session).size());verify(db).query(contains("a.kind NOT IN ('mask','subject','background')"),anyMap());
    }
}
