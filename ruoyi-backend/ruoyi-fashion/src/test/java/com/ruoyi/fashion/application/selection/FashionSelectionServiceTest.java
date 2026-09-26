package com.ruoyi.fashion.application.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.fashion.application.agent.run.RunView;
import com.ruoyi.fashion.application.quote.FashionQuoteDraftService;
import com.ruoyi.fashion.application.quote.port.FashionQuoteRepository;
import com.ruoyi.fashion.application.selection.port.FashionSelectionRepository;
import com.ruoyi.fashion.domain.product.FashionProductImage;
import com.ruoyi.fashion.domain.quote.FashionQuote;
import com.ruoyi.fashion.domain.shared.FashionIdGenerator;
import com.ruoyi.fashion.domain.shared.FashionTimeSource;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

class FashionSelectionServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FashionSelectionRepository repository = mock(FashionSelectionRepository.class);
    private final FashionQuoteRepository quoteRepository = mock(FashionQuoteRepository.class);
    private final FashionQuoteDraftService quotes = mock(FashionQuoteDraftService.class);
    private final FashionTimeSource time = mock(FashionTimeSource.class);
    private final AtomicLong sequence = new AtomicLong(9_000);
    private final FashionIdGenerator ids = sequence::incrementAndGet;
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock lock = mock(com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock.class);
    private FashionSelectionService service;
    private FashionQuote quote;

    @BeforeEach
    void setUp() {
        quote = quote(new BigDecimal("300.00"));
        when(time.now()).thenReturn(Instant.parse("2026-09-13T00:00:00Z"));
        when(quotes.requireAccessible(100L)).thenReturn(quote);
        when(repository.findCandidateFacts(any(), any())).thenReturn(candidateFacts());
        when(repository.findByQuoteId(100L)).thenReturn(List.of());
        when(quoteRepository.findPriceFacts(100L)).thenReturn(List.of());
        when(quoteRepository.touchDraft(anyLong(), anyLong(), anyLong(), any())).thenReturn(true);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transaction).executeWithoutResult(any());
        when(lock.executeLocked(org.mockito.ArgumentMatchers.eq(10), any())).thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        service = new FashionSelectionService(repository, quoteRepository, quotes, ids, time, objectMapper, transaction, lock);
    }

    @Test
    void freezesOneToFourProgressiveTiersAndGroupsSizeSkusWithoutExtraTables() {
        JsonNode preview = service.workspace("100").preview();

        assertEquals(List.of(1, 2, 3, 4), values(preview.path("tiers"), "category_count"));
        assertEquals(4, preview.path("frozen_candidates").size());
        preview.path("frozen_candidates").forEach(candidate -> {
            assertEquals(2, candidate.path("variants").size());
            assertEquals(120, candidate.path("total_available_qty").asInt());
            assertEquals(5_000, candidate.path("conservative_unit_price_minor").asInt());
        });
        assertTrue(preview.path("shortages").isEmpty());
    }

    @Test
    void explainsNoSolutionWhenBudgetRemovesEveryCandidate() {
        FashionQuote lowBudget = quote(new BigDecimal("10.00"));
        when(quotes.requireAccessible(100L)).thenReturn(lowBudget);

        JsonNode preview = service.workspace("100").preview();

        assertTrue(preview.path("frozen_candidates").isEmpty());
        assertEquals(4, preview.path("shortages").size());
    }

    @Test
    void keepsPublishedZeroPriceAsAValidCurrentFact() {
        when(repository.findCandidateFacts(any(), any())).thenReturn(candidateFacts(BigDecimal.ZERO));

        JsonNode preview = service.workspace("100").preview();

        assertEquals(4, preview.path("frozen_candidates").size());
        preview.path("frozen_candidates").forEach(candidate ->
                assertEquals(0, candidate.path("conservative_unit_price_minor").asInt()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void revalidatesFrozenCandidatesAndPersistsSkuSizeAllocation() {
        JsonNode preview = service.workspace("100").preview();
        ObjectNode output = output(preview);
        RunView run = run(preview, output);
        Map<String, String> hashes = new LinkedHashMap<>();
        output.path("tiers").forEach(tier -> tier.path("combinations").forEach(combo ->
                hashes.put(combo.path("combo_key").asText(), combo.path("combo_visual_hash").asText())));

        SelectionApplyResult result = service.applyRun(run, 3, hashes, 1);

        assertEquals(4, result.quoteRowVersion());
        ArgumentCaptor<List<SelectionDetailWrite>> details = ArgumentCaptor.forClass(List.class);
        verify(repository, org.mockito.Mockito.times(4)).insertCombo(any(), details.capture());
        List<List<SelectionDetailWrite>> all = details.getAllValues();
        assertEquals(List.of(100, 200, 300, 400), all.stream()
                .map(rows -> rows.stream().mapToInt(SelectionDetailWrite::qty).sum()).toList());
        assertTrue(all.stream().flatMap(List::stream).allMatch(row -> row.aiRunId() == 700L));
    }

    @Test
    void rejectsApplyWhenAProductLeavesTheFrozenCandidateSet() {
        JsonNode preview = service.workspace("100").preview();
        ObjectNode output = output(preview);
        when(repository.findCandidateFacts(any(), any())).thenReturn(List.of());

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.applyRun(run(preview, output), 3, Map.of(), 1));

        assertEquals(409, conflict.getCode());
        verify(quoteRepository, never()).touchDraft(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void lockMutationBumpsQuoteVersionAndRejectsStaleQuote() {
        String visualHash = "a".repeat(64);
        SelectionDetailView detail = new SelectionDetailView("11", 1, "SLOT-1", "10", "SRC", "SKU-10",
                "STYLE-0", "商品0", "上衣", "C0", "米色", "M", 100,
                new BigDecimal("50.00"), 120, Instant.parse("2026-09-12T00:00:00Z"),
                "fashion/products/10/main.jpg", "b".repeat(64), 1L, 2);
        SelectionComboView combo = new SelectionComboView("701", "100", "C-701", "一品类", 1, 100,
                true, 0, "测试", List.of(), visualHash, 4, List.of(detail));
        when(repository.findCombo(100L, 701L)).thenReturn(java.util.Optional.of(combo));
        when(repository.updateLocks(701L, List.of("SLOT-1"), 4, 1,
                Instant.parse("2026-09-13T00:00:00Z"))).thenReturn(true);

        SelectionWorkspace updated = service.updateLocks("100", "701", List.of("SLOT-1"),
                3, 4, visualHash, 1);

        assertEquals("100", updated.quote().id());
        verify(quoteRepository).touchDraft(100L, 3, 1, Instant.parse("2026-09-13T00:00:00Z"));
        verify(repository).updateLocks(701L, List.of("SLOT-1"), 4, 1,
                Instant.parse("2026-09-13T00:00:00Z"));

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.updateLocks("100", "701", List.of(), 2, 4, visualHash, 1));
        assertEquals(409, conflict.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void manualSelectionPersistsExactSizeQuantitiesWithoutAiRun() {
        JsonNode preview=service.workspace("100").preview();
        List<String> refs=new ArrayList<>();Map<String,Integer> quantities=new LinkedHashMap<>();
        preview.path("tiers").get(3).path("slots").forEach(slot -> {
            for(JsonNode candidate:preview.path("frozen_candidates")) {
                if(slot.path("category").asText().equals(candidate.path("category_code").asText())) {
                    refs.add(candidate.path("candidate_ref").asText());
                    candidate.path("variants").forEach(v->quantities.put(v.path("product_ref").asText(),50));
                }
            }
        });
        service.saveManual("100",3,preview.path("candidate_set_hash").asText(),"人工组合",refs,quantities,1);
        ArgumentCaptor<List<SelectionDetailWrite>> captured=ArgumentCaptor.forClass(List.class);
        verify(repository,org.mockito.Mockito.times(4)).insertCombo(any(),captured.capture());
        assertTrue(captured.getAllValues().stream().flatMap(List::stream).allMatch(d->d.qty()==50 && d.aiRunId()==null));
    }

    @Test
    void manualSelectionRejectsChangedCandidateSetWithoutWriting() {
        assertThrows(ServiceException.class,()->service.saveManual("100",3,"stale","人工组合",List.of("CAND-unknown"),Map.of(),1));
        verify(repository,never()).insertCombo(any(),any());
        verify(quoteRepository,never()).touchDraft(anyLong(),anyLong(),anyLong(),any());
    }

    private ObjectNode output(JsonNode preview) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("fact_scope", "frozen_candidates_only");
        output.put("human_confirmation_required", true);
        output.put("quote_ref", "100");
        output.put("quote_row_version", 3);
        output.put("candidate_set_hash", preview.path("candidate_set_hash").asText());
        Map<String, JsonNode> byCategory = new LinkedHashMap<>();
        preview.path("frozen_candidates").forEach(candidate ->
                byCategory.put(candidate.path("category_code").asText(), candidate));
        ArrayNode tiers = output.putArray("tiers");
        preview.path("tiers").forEach(spec -> {
            ObjectNode tier = tiers.addObject();
            int count = spec.path("category_count").asInt();
            tier.put("category_count", count);
            tier.put("requested_candidate_count", 1);
            ObjectNode combo = tier.putArray("combinations").addObject();
            combo.put("combo_key", "tier-" + count);
            combo.put("name", count + " 品类方案");
            combo.put("reason", "测试冻结候选");
            long price = 0;
            List<String> visualParts = new ArrayList<>();
            ArrayNode items = combo.putArray("items");
            for (int index = 0; index < count; index++) {
                String category = spec.path("slots").get(index).path("category").asText();
                JsonNode candidate = byCategory.get(category);
                ObjectNode item = items.addObject();
                item.put("slot_index", index + 1);
                item.put("category_code", category);
                item.put("candidate_ref", candidate.path("candidate_ref").asText());
                price += candidate.path("conservative_unit_price_minor").asLong();
                visualParts.add((index + 1) + ":" + candidate.path("candidate_ref").asText()
                        + ":" + candidate.path("visual_hash").asText());
            }
            combo.put("conservative_unit_price_minor", price);
            combo.put("combo_visual_hash", FashionHashing.sha256(
                    String.join("|", visualParts).getBytes(StandardCharsets.UTF_8)));
            tier.putArray("shortage_reasons");
        });
        return output;
    }

    private RunView run(JsonNode preview, ObjectNode output) {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("selection_snapshot", preview.deepCopy());
        return new RunView("700", "RUN-700", "701", "702", "100", 3,
                "selection-request-0001", "config", Instant.parse("2026-09-13T00:02:00Z"), context,
                "selection", output, FashionHashing.sha256(output.toString().getBytes(StandardCharsets.UTF_8)),
                objectMapper.createObjectNode(), "not_applied", "succeeded", 1, 1, null, null, 1,
                null, null, Instant.parse("2026-09-13T00:00:00Z"), Instant.parse("2026-09-13T00:00:01Z"),
                null, null, 2);
    }

    private FashionQuote quote(BigDecimal budget) {
        ObjectNode requirement = objectMapper.createObjectNode();
        requirement.putArray("preferred_colors");
        requirement.putArray("exclusions");
        ObjectNode template = objectMapper.createObjectNode();
        ArrayNode groups = template.putArray("groups");
        List<String> categories = List.of("上衣", "裤子", "帽子", "鞋");
        for (int count = 1; count <= 4; count++) {
            ObjectNode group = groups.addObject();
            group.put("count", count);
            group.set("slots", objectMapper.valueToTree(categories.subList(0, count)));
            group.put("candidate_count", 1);
        }
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        return new FashionQuote(100, "FQ-100", 1, null, 200, "客户", "活动服", 1,
                "已确认需求", requirement, true, 100, budget, "per_set", "alternatives", true,
                template, "WH-A", "CNY", "included", objectMapper.createObjectNode(), "draft",
                1, now, 1, now, 3);
    }

    private List<SelectionProductFact> candidateFacts() {
        return candidateFacts(new BigDecimal("50.00"));
    }

    private List<SelectionProductFact> candidateFacts(BigDecimal salePrice) {
        List<SelectionProductFact> result = new ArrayList<>();
        List<String> categories = List.of("上衣", "裤子", "帽子", "鞋");
        long id = 10;
        for (int categoryIndex = 0; categoryIndex < categories.size(); categoryIndex++) {
            for (String size : List.of("M", "L")) {
                long productId = id++;
                String key = "fashion/products/" + productId + "/main.jpg";
                FashionProductImage image = new FashionProductImage("IMG-" + productId, key,
                        FashionHashing.sha256(key.getBytes(StandardCharsets.UTF_8)), "main", "upload",
                        null, null, Instant.parse("2026-09-12T00:00:00Z"), true, true, true, false,
                        "active", 1, Instant.parse("2026-09-12T00:00:00Z"), 800, 800, "main.jpg");
                result.add(new SelectionProductFact(productId, "SRC", "SKU-" + productId,
                        "STYLE-" + categoryIndex, "商品" + categoryIndex, categories.get(categoryIndex),
                        "C" + categoryIndex, "米色", size, "CN", "件", salePrice,
                        Instant.parse("2026-09-12T00:00:00Z"), 301L, "秋季", List.of("休闲"), key,
                        List.of(image), 1, 2, 60, Instant.parse("2026-09-12T00:00:00Z"), 302L, 4));
            }
        }
        return result;
    }

    private static List<Integer> values(JsonNode array, String field) {
        List<Integer> result = new ArrayList<>();
        array.forEach(value -> result.add(value.path(field).asInt()));
        return result;
    }
}
