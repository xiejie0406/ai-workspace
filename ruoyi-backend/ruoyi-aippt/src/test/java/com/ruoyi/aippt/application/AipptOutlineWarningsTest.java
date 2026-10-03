package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.domain.AipptRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实中心任务租约、MyBatis/H2回执与正式提纲回读；不调用模型或外部服务。 */
class AipptOutlineWarningsTest {
    private AipptPersistenceFixture f;
    private TaskLeaseService leases;
    private JSONObject claimed;
    private JSONArray pages;

    private void setup(int charactersPerSection) throws Exception {
        setup(charactersPerSection, false);
    }

    private void setup(int charactersPerSection, boolean trailingWhitespace) throws Exception {
        f = new AipptPersistenceFixture();
        JSONObject source = f.source();
        JSONArray sections = new JSONArray();
        for (int i = 0; i < 6; i++) {
            String id = UUID.randomUUID().toString();
            // emoji区分Unicode码点与UTF-16长度；无heading消耗，实际两级预算裁剪为12000正文字符。
            String text = trailingWhitespace
                    ? "😀" + "材".repeat(charactersPerSection - 3) + "  "
                    : "😀" + "材".repeat(charactersPerSection - 1);
            sections.add(JSONObject.of("id", id, "ref", id, "heading", null,
                    "level", 0, "locator", "第" + (i + 1) + "节", "text", text));
        }
        source.put("sections", sections);
        source.put("char_count", charactersPerSection * 6);
        f.db.save("source", source, source.getLongValue("revision"));
        JSONObject project = f.project();
        project.put("page_count", 10);
        f.db.save("project", project, project.getLongValue("revision"));
        pages = f.pages(sections.getJSONObject(0).getString("ref"));
        for (int i = 0; i < 5; i++) {
            JSONObject page = AipptRules.copy(pages.getJSONObject(i));
            page.put("id", UUID.randomUUID().toString());
            page.put("title", "后续页面" + (i + 1));
            pages.add(page);
        }
        f.service.generateOutline(f.project, f.owner, "generate",
                JSONObject.of("input_revision", f.project().getLongValue("input_revision")));
        leases = f.leases();
        claimed = leases.claim(JSONObject.of("workerId", "outline-warning-fixture",
                "catalogVersion", f.catalog.version(), "types", JSONArray.of("generate_outline")));
        assertNotNull(claimed);
    }

    private JSONObject clippedOutput() {
        return JSONObject.of("pages", pages,
                "warnings", JSONArray.of("来源模型输入按预算裁剪：24000→12000字"),
                "source_budget", JSONObject.of("original_chars", 24000, "used_chars", 12000));
    }

    private JSONObject receipt(JSONObject output) {
        return JSONObject.of("attempt", claimed.get("attempt"), "leaseToken", claimed.get("leaseToken"),
                "fencingToken", claimed.get("fencingToken"), "output", output, "assets", new JSONArray());
    }

    @Test void completedClippingWarningsAndBudgetSurviveFormalOutlineAndTaskRoundTrips() throws Exception {
        setup(4000);
        JSONObject output = clippedOutput();
        JSONObject accepted = leases.complete(claimed.getString("taskId"), receipt(output));
        assertEquals("succeeded", accepted.getString("status"));
        JSONObject outline = f.service.outline(f.project, f.owner);
        assertEquals(10, outline.getJSONArray("pages").size());
        assertEquals(output.getJSONArray("warnings"), outline.getJSONArray("warnings"));
        assertEquals(output.getJSONObject("source_budget"), outline.getJSONObject("source_budget"));
        JSONObject persisted = f.db.children("outline", f.project).get(0);
        assertEquals(output.getJSONArray("warnings"), persisted.getJSONArray("warnings"));
        JSONObject task = f.service.task(claimed.getString("taskId"), f.owner,
                permission -> "aippt:outline:query".equals(permission));
        assertEquals("succeeded", task.getString("status"));
        assertEquals(output.getJSONArray("warnings"), task.getJSONObject("result").getJSONArray("warnings"));
        assertEquals(output.getJSONObject("source_budget"), task.getJSONObject("output").getJSONObject("source_budget"));
        assertDoesNotThrow(() -> leases.complete(claimed.getString("taskId"), receipt(output)));
        capture("clipped", outline, task);
    }

    @Test void uncutSourcesReturnAnEmptyWarningArrayAndExactBudget() throws Exception {
        setup(20);
        JSONObject excessive = JSONObject.of("pages", pages, "warnings", new JSONArray(),
                "source_budget", JSONObject.of("original_chars", 120, "used_chars", 121));
        assertEquals(422, assertThrows(AipptException.class,
                () -> leases.complete(claimed.getString("taskId"), receipt(excessive))).status());
        JSONObject output = JSONObject.of("pages", pages, "warnings", new JSONArray(),
                "source_budget", JSONObject.of("original_chars", 120, "used_chars", 120));
        leases.complete(claimed.getString("taskId"), receipt(output));
        JSONObject outline = f.service.outline(f.project, f.owner);
        assertTrue(outline.getJSONArray("warnings").isEmpty());
        assertEquals(120, outline.getJSONObject("source_budget").getIntValue("used_chars"));
        assertTrue(f.service.task(claimed.getString("taskId"), f.owner).getJSONObject("result").getJSONArray("warnings").isEmpty());
    }

    @Test void trimmingWarningsAreValidBelowTheTotalBudgetAndCountCodePoints() throws Exception {
        setup(20, true);
        JSONObject output = JSONObject.of("pages", pages,
                "warnings", JSONArray.of("来源模型输入按预算裁剪：120→108字"),
                "source_budget", JSONObject.of("original_chars", 120, "used_chars", 108));
        leases.complete(claimed.getString("taskId"), receipt(output));
        assertEquals(output.getJSONArray("warnings"), f.service.outline(f.project, f.owner).getJSONArray("warnings"));
        assertEquals(120, f.service.outline(f.project, f.owner).getJSONObject("source_budget").getIntValue("original_chars"));
    }

    @Test void oldPagesOnlyReceiptsAndLegacyReadsRemainCompatibleWithoutInventingBudget() throws Exception {
        setup(4000);
        assertTrue(f.service.outline(f.project, f.owner).getJSONArray("warnings").isEmpty());
        leases.complete(claimed.getString("taskId"), receipt(JSONObject.of("pages", pages)));
        JSONObject stored = f.db.children("outline", f.project).get(0);
        assertTrue(stored.getJSONArray("warnings").isEmpty());
        assertFalse(stored.containsKey("source_budget"));
        JSONObject task = f.service.task(claimed.getString("taskId"), f.owner);
        assertTrue(task.getJSONObject("result").getJSONArray("warnings").isEmpty());
        assertFalse(task.getJSONObject("result").containsKey("source_budget"));
        stored.remove("warnings");
        f.db.save("outline", stored, stored.getLongValue("revision"));
        assertTrue(f.service.outline(f.project, f.owner).getJSONArray("warnings").isEmpty());
        JSONObject emptyProject = f.service.create(f.owner, 1, "empty-outline", JSONObject.of("title", "无提纲项目"));
        assertTrue(f.service.outline(emptyProject.getString("id"), f.owner).getJSONArray("warnings").isEmpty());
    }

    @Test void invalidMetadataRollsBackTheReceiptAndCannotPersistBodyOrCredentials() throws Exception {
        setup(4000);
        List<JSONObject> invalid = new ArrayList<>();
        for (Object warnings : List.of("不是数组", JSONArray.of(1),
                JSONArray.of("来源正文：API_KEY=不得持久化"),
                JSONArray.of("x".repeat(1001)),
                JSONArray.of("来源模型输入按预算裁剪：24000→12000字", "重复提示"), new JSONArray())) {
            JSONObject output = clippedOutput();
            output.put("warnings", warnings);
            invalid.add(output);
        }
        JSONObject nullWarnings = clippedOutput(); nullWarnings.put("warnings", null); invalid.add(nullWarnings);
        JSONObject missingBudget = clippedOutput(); missingBudget.remove("source_budget"); invalid.add(missingBudget);
        JSONObject missingWarnings = clippedOutput(); missingWarnings.remove("warnings"); invalid.add(missingWarnings);
        JSONObject unknown = clippedOutput(); unknown.getJSONObject("source_budget").put("api_key", "不得持久化"); invalid.add(unknown);
        JSONObject wrongBudgetType = clippedOutput(); wrongBudgetType.put("source_budget", "不是对象"); invalid.add(wrongBudgetType);
        for (Object used : List.of(true, "12000", 12000.0, -1, 0, 12001)) {
            JSONObject output = clippedOutput(); output.getJSONObject("source_budget").put("used_chars", used); invalid.add(output);
        }
        for (Object original : List.of(false, "24000", 24000.0, -1, 0, 23999, 24001, 2147483648L)) {
            JSONObject output = clippedOutput(); output.getJSONObject("source_budget").put("original_chars", original); invalid.add(output);
        }
        for (JSONObject output : invalid) {
            assertEquals(422, assertThrows(AipptException.class,
                    () -> leases.complete(claimed.getString("taskId"), receipt(output))).status());
            assertEquals("running", f.db.get("task", claimed.getString("taskId")).getString("status"));
            JSONObject outline = f.db.children("outline", f.project).get(0);
            assertEquals("generating", outline.getString("status"));
            assertFalse(outline.containsKey("warnings"));
            assertFalse(outline.containsKey("source_budget"));
        }
        assertEquals("succeeded", leases.complete(claimed.getString("taskId"), receipt(clippedOutput())).getString("status"));
    }

    @Test void newCompletionReplacesOldWarningMetadataWithoutChangingInputFingerprint() throws Exception {
        setup(4000);
        leases.complete(claimed.getString("taskId"), receipt(clippedOutput()));
        String signature = f.service.outline(f.project, f.owner).getString("input_signature");
        f.service.generateOutline(f.project, f.owner, "regenerate",
                JSONObject.of("input_revision", f.project().getLongValue("input_revision")));
        claimed = leases.claim(JSONObject.of("workerId", "outline-warning-fixture",
                "catalogVersion", f.catalog.version(), "types", JSONArray.of("generate_outline")));
        leases.complete(claimed.getString("taskId"), receipt(JSONObject.of("pages", pages)));
        JSONObject outline = f.service.outline(f.project, f.owner);
        assertEquals(signature, outline.getString("input_signature"));
        assertTrue(outline.getJSONArray("warnings").isEmpty());
        assertFalse(outline.containsKey("source_budget"));
    }

    private void capture(String name, JSONObject outline, JSONObject task) throws Exception {
        String location = System.getProperty("aippt.outline.evidence.dir");
        if (location == null || location.isBlank()) return;
        Path directory = Path.of(location);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("outline-source-" + name + ".json"),
                JSON.toJSONString(JSONObject.of("scope", "隔离H2/MyBatis实际service回读；Worker输出合成，不代表真实Provider",
                        "outline_data", outline, "task_data", task), JSONWriter.Feature.WriteMapNullValue));
    }
}
