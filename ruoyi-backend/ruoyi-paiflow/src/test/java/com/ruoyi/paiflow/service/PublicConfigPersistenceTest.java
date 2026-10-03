package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际公开表/Mapper和平台主体；种子是来源基线，读结果不是编译时catalog缓存。 */
class PublicConfigPersistenceTest {
    private PublicConfigTestFixture fixture;private ConfigInfoService configs;
    @BeforeEach void setup() throws Exception {fixture=new PublicConfigTestFixture();configs=fixture.service("prod");}
    @AfterEach void close() throws Exception {if(fixture!=null)fixture.close();}
    @Test void allReviewedSourceRowsKeepOriginalNineFieldsAndInactiveRowsNeverAppear() throws Exception {
        assertEquals(147,fixture.count());int total=0;Set<String> categories=new HashSet<>();
        for(var raw:fixture.catalog.getJSONArray("records"))categories.add(((JSONObject)raw).getString("category"));
        for(String category:categories) {
            var actual=configs.list(category);total+=actual.size();
            var expected=new ArrayList<JSONObject>();for(var raw:fixture.catalog.getJSONArray("records")){var row=(JSONObject)raw;if(category.equals(row.getString("category"))&&row.getIntValue("isValid")==1)expected.add(row);}
            assertEquals(expected.size(),actual.size());
            for(var view:actual) {
                var source=expected.stream().filter(row->row.getLong("id").equals(view.id())).findFirst().orElseThrow();
                assertEquals(source.getString("value"),view.value());assertEquals(source.getString("remarks"),view.remarks());assertEquals(source.getString("name"),view.name());assertEquals(source.getString("code"),view.code());
                assertEquals(Set.of("id","category","code","name","value","isValid","remarks","createTime","updateTime"),JSON.parseObject(tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build().writeValueAsString(view)).keySet());
            }
        }
        assertEquals(141,total);
    }
    @Test void actualDatabaseUpdatesNewRowsAndInactiveSwitchAreVisibleWithoutRebuildingCatalog() throws Exception {
        var old=configs.list("COLOR").get(0);fixture.update("UPDATE paiflow_public_config SET value=?, update_time=? WHERE id=?","#fixture-current","2026-10-03 09:00:00",old.id());
        assertEquals("#fixture-current",configs.list("COLOR").stream().filter(r->r.id().equals(old.id())).findFirst().orElseThrow().value());
        fixture.update("INSERT INTO paiflow_public_config(id,category,code,name,value,is_valid) VALUES(?,?,?,?,?,1)",99999L,"COLOR","new","fixture-new","#new");
        assertEquals("#new",configs.first("COLOR","new").value());assertEquals(9,configs.list("COLOR").size());
        fixture.update("UPDATE paiflow_public_config SET is_valid=0 WHERE id=?",99999L);assertEquals(8,configs.list("COLOR").size());assertEquals(503,assertThrows(ServiceException.class,()->configs.first("COLOR","new")).getCode());
    }
    @Test void exactCategoryCodeAndOriginalListWithoutCodeFilterKeepAllMatchingRows() {
        assertEquals(92,configs.list("ICON").size());assertTrue(configs.list("ICON","common").size()>1);
        var first=configs.first("ICON","common");assertEquals("ICON",first.category());assertEquals("common",first.code());
        assertEquals(configs.list("COLOR").size(),configs.list("color").size());assertEquals("TB",configs.first("db_table_template","tb").code());
        for(String unsafe:new String[]{"MODEL_SECRET_KEY","NODE_API_K_S","AI_CODE","LLM","not-configured","ICON' OR 1=1"})assertEquals(503,assertThrows(ServiceException.class,()->configs.list(unsafe)).getCode());
        assertEquals(503,assertThrows(ServiceException.class,()->configs.first("TEMPLATE","prompt-enhance")).getCode());
    }
    @Test void tagSortUsesActualUpdateTimeAndDevTestRemapUsesOriginalRemarksOnly() throws Exception {
        var raw=fixture.mapper.tags("TOOL_TAGS");assertFalse(raw.isEmpty());var moved=raw.get(raw.size()-1);fixture.update("UPDATE paiflow_public_config SET update_time=? WHERE id=?","2099-01-01 00:00:00",moved.getId());
        assertEquals(moved.getId(),configs.tags("tool").get(0).id());
        var v2=fixture.mapper.tags("TOOL_TAGS_V2");assertFalse(v2.isEmpty());
        for(String profile:new String[]{"dev","test","prod","dev,test",""}) {
            var actual=fixture.service(profile).tags("tool_v2");assertEquals(v2.size(),actual.size());
            for(int i=0;i<v2.size();i++){var row=v2.get(i);Long expected=(profile.equals("dev")||profile.equals("test"))&&row.getRemarks()!=null&&!row.getRemarks().isBlank()?Long.valueOf(row.getRemarks()):row.getId();assertEquals(expected,actual.get(i).id());}
        }
        for(String flag:new String[]{"unknown","TOOL","","modelCategory","tool,bot"})assertTrue(configs.tags(flag).isEmpty());
        assertFalse(configs.tags("bot").isEmpty());
    }
    @Test void missingWorkflowCategoryIsNotInventedAndFutureFormalRowsFollowOriginalSplit() throws Exception {
        assertEquals(503,assertThrows(ServiceException.class,configs::workflowCategories).getCode());assertEquals(503,assertThrows(ServiceException.class,()->configs.list("WORKFLOW_CATEGORY")).getCode());
        fixture.update("INSERT INTO paiflow_public_config(id,category,code,value,is_valid) VALUES(?,?,?,?,1)",90001L,"WORKFLOW_CATEGORY","source-fixture","A, B,,C,");
        assertEquals(List.of("A"," B","","C"),configs.workflowCategories());
        fixture.update("INSERT INTO paiflow_public_config(id,category,code,value,is_valid) VALUES(?,?,?,?,1)",90002L,"WORKFLOW_CATEGORY","another","D");assertEquals(503,assertThrows(ServiceException.class,configs::workflowCategories).getCode());
    }
    @Test void publicTableCheckRejectsSecretCategoriesAndUnreviewedPairsRatherThanServingThem() throws Exception {
        int old=fixture.count();
        for(String[] pair:new String[][]{{"MODEL_SECRET_KEY","x"},{"NODE_API_K_S","x"},{"TEMPLATE","prompt-enhance"},{"WORKFLOW","private-app"}}) {
            var error=assertThrows(java.sql.SQLException.class,()->fixture.update("INSERT INTO paiflow_public_config(id,category,code,is_valid) VALUES(?,?,?,1)",99900L,pair[0],pair[1]));
            assertTrue(error.getMessage().toLowerCase().contains("ck_paiflow_public_config_public"));
        }
        assertEquals(old,fixture.count());
    }
    @Test void changedPublicValueCannotLeakStructuredSecretsOrPrivateAndSignedTargets() throws Exception {
        Long id=configs.list("COLOR").get(0).id();
        for(String value:new String[]{"{\"nested\":{\"apiSecret\":\"private-fixture-value\"}}","{\"encoded\":\"{\\\"credentialRef\\\":\\\"private-fixture-value\\\"}\"}","http://127.0.0.1/private","http://user:private-fixture-value@public.invalid/path","https://public.invalid/path?signature=private-fixture-value","{\"url\":\"http:\\/\\/10.0.0.1\\/private\"}"}) {
            fixture.update("UPDATE paiflow_public_config SET value=? WHERE id=?",value,id);var error=assertThrows(ServiceException.class,()->configs.list("COLOR"));assertEquals(503,error.getCode());assertFalse(error.getMessage().contains("private-fixture-value"));
        }
        fixture.update("UPDATE paiflow_public_config SET value=? WHERE id=?","[\"authorization\",\"password\"]",id);assertEquals("[\"authorization\",\"password\"]",configs.list("COLOR").stream().filter(r->r.id().equals(id)).findFirst().orElseThrow().value());
    }
    @Test void platformCurrentAccountIsCheckedBeforeAndAfterActualReadWithoutSpaceInitialization() {
        fixture.currentUser.setStatus("1");assertEquals(401,assertThrows(ServiceException.class,()->configs.list("COLOR")).getCode());
        fixture.currentUser=PublicConfigTestFixture.user(42);fixture.accountReads.set(0);
        when(fixture.users.selectUserById(42L)).thenAnswer(call->{var user=PublicConfigTestFixture.user(42);if(fixture.accountReads.incrementAndGet()>1)user.setStatus("1");return user;});
        assertEquals(401,assertThrows(ServiceException.class,()->configs.list("COLOR")).getCode());
        SecurityContextHolder.clearContext();assertThrows(RuntimeException.class,()->configs.tags("unknown"));
    }
    @Test void topLevelEncodedJsonStringMustNotBypassPrivateUrlOrStructuredCredentials() throws Exception {
        Long id=configs.list("COLOR").get(0).id();
        for(String value:new String[]{"\"http:\\/\\/127.0.0.1\\/private\"","\"{\\\"apiKey\\\":\\\"private-fixture-value\\\"}\""}) {
            fixture.update("UPDATE paiflow_public_config SET value=? WHERE id=?",value,id);
            var error=assertThrows(ServiceException.class,()->configs.list("COLOR"));assertEquals(503,error.getCode());assertFalse(error.getMessage().contains("private-fixture-value"));
        }
    }
    @Test void encodedQueryKeysAreCheckedAfterStrictDecodingAndNeverReturnedAsPublicUrls() throws Exception {
        Long id=configs.list("COLOR").get(0).id();
        for(String key:new String[]{"%74oken","api%5fkey","X-Amz-%53ignature","%C3%28"}) {
            fixture.update("UPDATE paiflow_public_config SET value=? WHERE id=?","https://public.invalid/path?"+key+"=private-fixture-value",id);
            assertEquals(503,assertThrows(ServiceException.class,()->configs.list("COLOR")).getCode());
        }
    }
    @Test void browserNumericIpv4CannotExposePrivateTargetsButCanonicalPublicUrlsStayUnchanged() throws Exception {
        fixture.update("DELETE FROM paiflow_public_config WHERE category='COLOR'");
        fixture.update("INSERT INTO paiflow_public_config(id,category,code,name,value,is_valid) VALUES(?,?,?,?,?,1)",90003L,"COLOR","url-fixture","fixture","#initial");
        for(String value:new String[]{"http://2130706433/private-fixture","http://0x7f000001/private-fixture","http://0177.0.0.1/private-fixture","http://127.1/private-fixture","http://127.0.0.1./private-fixture","http://localhost./private-fixture"}) {
            fixture.update("UPDATE paiflow_public_config SET value=? WHERE id=?",value,90003L);
            assertEquals(503,assertThrows(ServiceException.class,()->configs.list("COLOR")).getCode());
            assertEquals(503,assertThrows(ServiceException.class,()->configs.first("COLOR","url-fixture")).getCode());
            assertEquals(503,assertThrows(ServiceException.class,()->configs.list("COLOR","url-fixture")).getCode());
        }
        for(String value:new String[]{"https://8.8.8.8/public-fixture","https://public.invalid/public-fixture"}) {
            fixture.update("UPDATE paiflow_public_config SET value=? WHERE id=?",value,90003L);
            assertEquals(value,configs.first("COLOR","url-fixture").value());
        }
    }
    @Test void oversizedRowsAndResultBytesFailInsteadOfReturningTruncatedSuccess() throws Exception {
        fixture.update("DELETE FROM paiflow_public_config WHERE category='COLOR'");
        for(int i=0;i<1001;i++)fixture.update("INSERT INTO paiflow_public_config(id,category,code,value,is_valid) VALUES(?,?,?,?,1)",100000L+i,"COLOR","fixture","#x");
        assertEquals(413,assertThrows(ServiceException.class,()->configs.list("COLOR")).getCode());
        fixture.update("DELETE FROM paiflow_public_config WHERE category='COLOR'");
        for(int i=0;i<400;i++)fixture.update("INSERT INTO paiflow_public_config(id,category,code,value,is_valid) VALUES(?,?,?,?,1)",100000L+i,"COLOR","fixture","x".repeat(3000));
        assertEquals(413,assertThrows(ServiceException.class,()->configs.list("COLOR")).getCode());
    }
    @Test void malformedParametersCannotWidenOrSelectAnUnreviewedDirectory() {
        for(String category:new String[]{null,"ICON\n","x".repeat(65)})assertEquals(400,assertThrows(ServiceException.class,()->configs.list(category)).getCode());
        assertEquals(400,assertThrows(ServiceException.class,()->configs.first("ICON","x".repeat(129))).getCode());
        assertEquals(503,assertThrows(ServiceException.class,()->configs.list("TEMPLATE","anything")).getCode());
    }
}
