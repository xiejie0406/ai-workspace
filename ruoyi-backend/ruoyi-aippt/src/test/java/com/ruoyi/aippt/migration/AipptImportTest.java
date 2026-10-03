package com.ruoyi.aippt.migration;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.application.*;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AipptImportTest  {
    @TempDir Path temp;
    private final CatalogService catalog=new CatalogService(new AipptProperties());
    private final JSONObject owners=JSONObject.of("legacy-user",7);
    private JSONObject project(String batch)  {
        return JSONObject.of("id",UUID.randomUUID().toString(),"owner_id",7,"legacy_owner_id","legacy-user","import_batch",batch,"title","旧演示文稿","page_count",10,"theme_id","ivory","sources",new JSONArray(),"slides",new JSONArray(),"outline",new JSONObject());
    }
    private JSONObject manifest()  {
        String batch=UUID.randomUUID().toString();
        return JSONObject.of("schema_version","aippt-import-1","complete",true,"import_batch",batch,"source_sha256","0".repeat(64),"catalog_version",catalog.version(),"projects",JSONArray.of(JSONObject.of("project",project(batch),"assets",new JSONArray(),"audit",new JSONObject())));
    }
    @Test void explicitOwnerMappingAndImportCheckpointArePreserved()throws Exception  {
        var plan=AipptLegacyImportCli.prepare(manifest(),temp,catalog,owners);
        JSONObject project=plan.projects().get(0).rows().stream().filter(r->r.table().equals("project")).findFirst().orElseThrow().body();
        assertEquals(7,project.getLongValue("owner_user_id"));
        assertEquals(plan.batch(),project.getString("import_batch"));
        assertEquals(project.getLongValue("revision"),project.getLongValue("import_revision"));
    }
    @Test void fractionalOrCoercedOwnersCannotBeTruncatedIntoAnotherUser()  {
        for(Object owner:List.of(7.5,"7",true))  {
            JSONObject manifest=manifest();
            manifest.getJSONArray("projects").getJSONObject(0).getJSONObject("project").put("owner_id",owner);
            assertThrows(AipptException.class,()->AipptLegacyImportCli.prepare(manifest,temp,catalog,JSONObject.of("legacy-user",owner)));
        }
    }
    @Test void ownerMismatchAndSecretsAreRejectedBeforeDatabase()  {
        JSONObject manifest=manifest();
        assertThrows(AipptException.class,()->AipptLegacyImportCli.prepare(manifest,temp,catalog,JSONObject.of("legacy-user",8)));
        manifest.getJSONArray("projects").getJSONObject(0).getJSONObject("project").put("api_key","do-not-import");
        assertThrows(AipptException.class,()->AipptLegacyImportCli.prepare(manifest,temp,catalog,owners));
    }
    @Test void auditAndOtherManifestCredentialsCannotEnterOfficialRows()  {
        JSONObject manifest=manifest();
        manifest.getJSONArray("projects").getJSONObject(0).put("audit",JSONObject.of("api_key","do-not-import"));
        assertThrows(AipptException.class,()->AipptLegacyImportCli.prepare(manifest,temp,catalog,owners));
        manifest.getJSONArray("projects").getJSONObject(0).put("audit",new JSONObject());
        manifest.put("token","do-not-import");
        assertThrows(AipptException.class,()->AipptLegacyImportCli.prepare(manifest,temp,catalog,owners));
    }
    @Test void importedOutlineUsesStableCurrentFingerprintAndRemainsDraft() throws Exception  {
        JSONObject manifest=manifest(),entry=manifest.getJSONArray("projects").getJSONObject(0),project=entry.getJSONObject("project");
        String sectionId=UUID.randomUUID().toString();
        project.getJSONArray("sources").add(JSONObject.of("id",UUID.randomUUID().toString(),"kind","text","sections",JSONArray.of(JSONObject.of("id",sectionId,"ref",sectionId,"heading","旧材料","level",1,"text","明确材料正文"))));
        project.put("outline",JSONObject.of("id",UUID.randomUUID().toString(),"legacy_input_signature","old","status","draft","pages",new JSONArray()));
        var plan=AipptLegacyImportCli.prepare(manifest,temp,catalog,owners);
        var rows=plan.projects().get(0).rows();
        JSONObject importedProject=rows.stream().filter(r->r.table().equals("project")).findFirst().orElseThrow().body(),outline=rows.stream().filter(r->r.table().equals("outline")).findFirst().orElseThrow().body();
        var sources=rows.stream().filter(r->r.table().equals("source")).map(AipptLegacyImportCli.Row::body).toList();
        assertEquals(AipptRules.fingerprint(importedProject,sources),outline.getString("input_signature"));
        assertEquals("old",outline.getString("legacy_input_signature"));
        assertEquals("draft",outline.getString("status"));
        assertTrue(outline.getBooleanValue("requires_reconfirmation"));
        assertTrue(sources.get(0).getIntValue("char_count")>0);
    }
    @Test void readyPagesKeepLockedContentNotesAndBecomeOneBased()throws Exception  {
        JSONObject manifest=manifest(),project=manifest.getJSONArray("projects").getJSONObject(0).getJSONObject("project");
        JSONObject slide=AipptRules.blank();
        slide.put("id",UUID.randomUUID().toString());
        slide.put("revision",9);
        slide.put("position",0);
        slide.put("speaker_notes","保留备注");
        project.getJSONArray("slides").add(slide);
        var plan=AipptLegacyImportCli.prepare(manifest,temp,catalog,owners);
        JSONObject imported=plan.projects().get(0).rows().stream().filter(r->r.table().equals("slide")).findFirst().orElseThrow().body();
        assertEquals(1,imported.getIntValue("position"));
        assertEquals(9,imported.getLongValue("revision"));
        assertEquals("保留备注",imported.getString("speaker_notes"));
        assertTrue(imported.getJSONArray("blocks").getJSONObject(0).getBooleanValue("locked"));
    }
    @Test void sharedLegacyImageGetsDistinctPrivateProjectObjects()throws Exception  {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        Path image=temp.resolve("image.png");
        Files.write(image,bytes.toByteArray());
        JSONObject manifest=manifest();
        String batch=manifest.getString("import_batch"),legacyAsset=UUID.randomUUID().toString();
        manifest.getJSONArray("projects").add(JSONObject.of("project",project(batch),"assets",new JSONArray(),"audit",new JSONObject()));
        for(Object raw:manifest.getJSONArray("projects"))((JSONObject)raw).getJSONArray("assets").add(JSONObject.of("asset_id",legacyAsset,"path",image.toString(),"size",bytes.size(),"sha256",AssetService.bytesHash(bytes.toByteArray()),"mime","image/png"));
        var plan=AipptLegacyImportCli.prepare(manifest,temp,catalog,owners);
        assertNotEquals(plan.projects().get(0).objects().get(0).key(),plan.projects().get(1).objects().get(0).key());
        manifest.getJSONArray("projects").getJSONObject(0).getJSONArray("assets").getJSONObject(0).put("sha256","f".repeat(64));
        assertThrows(AipptException.class,()->AipptLegacyImportCli.prepare(manifest,temp,catalog,owners));
    }
    @Test void residualSourceTablePreventsAutomaticSchemaAdoption()throws Exception  {
        Connection connection=mock(Connection.class);
        DatabaseMetaData metadata=mock(DatabaseMetaData.class);
        ResultSet tables=mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("approved");
        when(metadata.getTables(eq("approved"),isNull(),eq("aippt_%"),any())).thenReturn(tables);
        when(tables.next()).thenReturn(true,false);
        when(tables.getString("TABLE_NAME")).thenReturn("aippt_source");
        assertTrue(AipptSchemaCli.hasManagedTables(connection));
    }
}
