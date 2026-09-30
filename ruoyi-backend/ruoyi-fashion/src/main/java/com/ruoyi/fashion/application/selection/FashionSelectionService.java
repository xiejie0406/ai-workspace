package com.ruoyi.fashion.application.selection;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.agent.run.RunView;
import com.ruoyi.fashion.application.quote.FashionQuoteDraftService;
import com.ruoyi.fashion.application.quote.QuoteView;
import com.ruoyi.fashion.application.quote.port.FashionQuoteRepository;
import com.ruoyi.fashion.application.selection.port.FashionSelectionRepository;
import com.ruoyi.fashion.domain.product.FashionProductImage;
import com.ruoyi.fashion.domain.quote.FashionQuote;
import com.ruoyi.fashion.domain.shared.FashionId;
import com.ruoyi.fashion.domain.shared.FashionIdGenerator;
import com.ruoyi.fashion.domain.shared.FashionTimeSource;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;

/** Java 侧确定性硬过滤、冻结、复核和组合落库；AI 只能在候选引用内排序。 */
@FashionModuleEnabled
@Service
public class FashionSelectionService {
    private static final int MAX_CANDIDATES_PER_CATEGORY = 50;

    private final FashionSelectionRepository repository;
    private final FashionQuoteRepository quoteRepository;
    private final FashionQuoteDraftService quotes;
    private final FashionIdGenerator ids;
    private final FashionTimeSource time;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock catalogWriteLock;

    public FashionSelectionService(
            FashionSelectionRepository repository,
            FashionQuoteRepository quoteRepository,
            FashionQuoteDraftService quotes,
            FashionIdGenerator ids,
            FashionTimeSource time,
            ObjectMapper objectMapper,
            @Qualifier("fashionTransactionTemplate") TransactionTemplate transaction,
            com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock catalogWriteLock) {
        this.repository = repository;
        this.quoteRepository = quoteRepository;
        this.quotes = quotes;
        this.ids = ids;
        this.time = time;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
        this.catalogWriteLock = catalogWriteLock;
    }

    public SelectionWorkspace workspace(String quoteId) {
        FashionQuote quote = quotes.requireAccessible(FashionId.parse(quoteId).value());
        return new SelectionWorkspace(QuoteView.from(quote, quoteRepository.findPriceFacts(quote.id())),
                freeze(quote, null, null), repository.findByQuoteId(quote.id()));
    }

    public JsonNode freezeForRun(String quoteId, String baseComboId, String comboVisualHash) {
        FashionQuote quote = quotes.requireAccessible(FashionId.parse(quoteId).value());
        if (!"draft".equals(quote.status())) throw new ServiceException("只有草稿方案可发起选品搭配");
        if (!quote.requirementConfirmed()) throw new ServiceException("需求确认后才能发起选品搭配");
        SelectionComboView base = null;
        if (baseComboId != null && !baseComboId.isBlank()) {
            base = repository.findCombo(quote.id(), FashionId.parse(baseComboId).value())
                    .orElseThrow(() -> new ServiceException("基础组合不存在"));
            if (!base.selected()) throw new ServiceException("只能基于当前组合重搭");
            if (comboVisualHash == null || !comboVisualHash.equals(base.visualHash())) {
                throw new ServiceException("基础组合已变化，请刷新后重试", 409);
            }
        }
        JsonNode snapshot = freeze(quote, base, comboVisualHash);
        if (snapshot.path("frozen_candidates").isEmpty()) throw new ServiceException("没有满足硬约束的商品候选");
        return snapshot;
    }

    public SelectionApplyResult applyRun(
            RunView run, long expectedQuoteRowVersion, Map<String, String> expectedVisualHashes, long operatorId) {
        if (run.quoteId() == null || run.output() == null || !run.output().isObject()) {
            throw new ServiceException("选品任务缺少方案或输出");
        }
        FashionQuote quote = quotes.requireAccessible(FashionId.parse(run.quoteId()).value());
        if (!"draft".equals(quote.status()) || !quote.requirementConfirmed()) {
            throw new ServiceException("当前方案不可采用选品结果");
        }
        if (quote.rowVersion() != expectedQuoteRowVersion || run.quoteRowVersion() != quote.rowVersion()) {
            throw new ServiceException("方案已变化，请重新生成候选", 409);
        }
        JsonNode frozen = run.contextSnapshot().path("selection_snapshot");
        JsonNode result = run.output();
        if (!"frozen_candidates_only".equals(result.path("fact_scope").asText())
                || !result.path("human_confirmation_required").asBoolean(false)
                || !run.quoteId().equals(result.path("quote_ref").asText())
                || quote.rowVersion() != result.path("quote_row_version").asLong()
                || !frozen.path("candidate_set_hash").asText().equals(result.path("candidate_set_hash").asText())) {
            throw new ServiceException("选品结果与冻结方案不一致");
        }
        JsonNode current = freeze(quote, null, null);
        Map<String, JsonNode> frozenCandidates = candidates(frozen);
        Map<String, JsonNode> currentCandidates = candidates(current);
        List<PreparedCombo> prepared = validateAndPrepare(result, frozen, frozenCandidates, currentCandidates,
                expectedVisualHashes == null ? Map.of() : expectedVisualHashes, quote, run);
        if (prepared.isEmpty()) throw new ServiceException("选品结果没有可采用组合");

        Instant now = time.now();
        if (!quoteRepository.touchDraft(quote.id(), expectedQuoteRowVersion, operatorId, now)) {
            throw new ServiceException("方案已变化，请刷新后重试", 409);
        }
        repository.deselectCurrent(quote.id(), operatorId, now);
        int sort = 0;
        for (PreparedCombo combo : prepared) {
            long comboId = ids.nextId();
            List<SelectionDetailWrite> details = details(comboId, Long.parseLong(run.id()), combo, quote, operatorId, now);
            repository.insertCombo(new SelectionComboWrite(comboId, quote.id(), "C-" + comboId,
                    combo.name(), combo.categoryCount(), quote.requestedQty(), sort++, combo.reason(),
                    combo.lockedSlots(), combo.visualHash(), operatorId, now), details);
        }
        return new SelectionApplyResult(Long.toString(quote.id()), quote.rowVersion() + 1,
                repository.findByQuoteId(quote.id()));
    }

    public SelectionWorkspace updateLocks(
            String quoteId, String comboId, List<String> slots, long expectedQuoteRowVersion,
            long expectedComboRowVersion,
            String expectedVisualHash, long operatorId) {
        FashionQuote quote = quotes.requireAccessible(FashionId.parse(quoteId).value());
        if (!"draft".equals(quote.status()) || quote.rowVersion() != expectedQuoteRowVersion) {
            throw new ServiceException("方案已变化，请刷新后重试", 409);
        }
        long id = FashionId.parse(comboId).value();
        SelectionComboView combo = repository.findCombo(quote.id(), id)
                .orElseThrow(() -> new ServiceException("组合不存在"));
        if (!combo.visualHash().equals(expectedVisualHash)) throw new ServiceException("组合已变化，请刷新", 409);
        Set<String> available = combo.details().stream().map(SelectionDetailView::slotCode)
                .collect(java.util.stream.Collectors.toSet());
        List<String> normalized = slots == null ? List.of() : slots.stream().distinct().sorted().toList();
        if (!available.containsAll(normalized) || normalized.size() > 4) throw new ServiceException("锁定槽位无效");
        transaction.executeWithoutResult(status -> {
            Instant now = time.now();
            if (!quoteRepository.touchDraft(quote.id(), expectedQuoteRowVersion, operatorId, now)
                    || !repository.updateLocks(id, normalized, expectedComboRowVersion, operatorId, now)) {
                throw new ServiceException("方案或组合锁定状态已变化，请刷新", 409);
            }
        });
        return workspace(quoteId);
    }

    public SelectionWorkspace replace(
            String quoteId, String comboId, SelectionReplaceCommand command, long operatorId) {
        if (command == null || command.slotCode() == null || !command.slotCode().matches("SLOT-[1-4]")) {
            throw new ServiceException("替换槽位无效");
        }
        FashionQuote quote = quotes.requireAccessible(FashionId.parse(quoteId).value());
        if (!"draft".equals(quote.status()) || quote.rowVersion() != command.quoteRowVersion()) {
            throw new ServiceException("方案已变化，请刷新后重试", 409);
        }
        long oldComboId = FashionId.parse(comboId).value();
        SelectionComboView old = repository.findCombo(quote.id(), oldComboId)
                .orElseThrow(() -> new ServiceException("组合不存在"));
        if (!old.selected() || old.rowVersion() != command.comboRowVersion()
                || !old.visualHash().equals(command.comboVisualHash())) {
            throw new ServiceException("组合已变化，请刷新后重试", 409);
        }
        if (old.lockedSlots().contains(command.slotCode())) throw new ServiceException("锁定槽位不能直接替换");
        JsonNode current = freeze(quote, null, null);
        Map<String, JsonNode> candidates = candidates(current);
        JsonNode replacement = candidates.get(command.candidateRef());
        SelectionDetailView replaced = old.details().stream()
                .filter(detail -> command.slotCode().equals(detail.slotCode())).findFirst()
                .orElseThrow(() -> new ServiceException("组合中不存在该槽位"));
        if (replacement == null
                || !replaced.categoryCode().equals(replacement.path("category_code").asText())) {
            throw new ServiceException("替换候选不存在或品类不一致");
        }
        Map<String, JsonNode> byBusinessKey = new HashMap<>();
        candidates.values().forEach(candidate -> byBusinessKey.put(candidate.path("source_ref").asText() + "\u001f"
                + candidate.path("style_ref").asText() + "\u001f" + candidate.path("color_code").asText(), candidate));
        Map<Integer, String> chosen = new LinkedHashMap<>();
        List<JsonNode> chosenCandidates = new ArrayList<>();
        List<String> slotCodes = old.details().stream().map(SelectionDetailView::slotCode).distinct()
                .sorted().toList();
        for (String slotCode : slotCodes) {
            int slot = Integer.parseInt(slotCode.substring("SLOT-".length()));
            JsonNode candidate;
            if (slotCode.equals(command.slotCode())) candidate = replacement;
            else {
                SelectionDetailView detail = old.details().stream().filter(item -> slotCode.equals(item.slotCode()))
                        .findFirst().orElseThrow();
                candidate = byBusinessKey.get(detail.sourceCode() + "\u001f" + detail.styleCode()
                        + "\u001f" + detail.colorCode());
            }
            if (candidate == null) throw new ServiceException("原组合商品已不满足当前硬约束", 409);
            chosen.put(slot, candidate.path("candidate_ref").asText());
            chosenCandidates.add(candidate);
        }
        String visualHash = comboVisualHash(chosen, candidates);
        long price = chosenCandidates.stream()
                .mapToLong(candidate -> candidate.path("conservative_unit_price_minor").asLong()).sum();
        long budget = current.path("budget_maximum_per_set_minor").asLong(0);
        if (budget > 0 && price > budget) throw new ServiceException("替换后组合超过当前每套预算");
        PreparedCombo prepared = new PreparedCombo(old.name(), "人工替换 " + command.slotCode(),
                old.categoryCount(), chosenCandidates, visualHash, old.lockedSlots());
        transaction.executeWithoutResult(status -> {
            Instant now = time.now();
            if (!quoteRepository.touchDraft(quote.id(), command.quoteRowVersion(), operatorId, now)
                    || !repository.deselectCombo(oldComboId, command.comboRowVersion(), operatorId, now)) {
                throw new ServiceException("方案或组合已变化，请刷新后重试", 409);
            }
            long newComboId = ids.nextId();
            repository.insertCombo(new SelectionComboWrite(newComboId, quote.id(), "C-" + newComboId,
                    prepared.name(), prepared.categoryCount(), quote.requestedQty(), old.sortNo(),
                    prepared.reason(), prepared.lockedSlots(), prepared.visualHash(), operatorId, now),
                    details(newComboId, null, prepared, quote, operatorId, now));
        });
        return workspace(quoteId);
    }

    /** 人工选择只接受当前已过滤候选与显式尺码数量，不依赖 AI Run。 */
    public SelectionWorkspace saveManual(String quoteId, long version, String candidateSetHash,
            String name, List<String> references, Map<String, Integer> quantities, long operatorId) {
        if (references == null || references.isEmpty() || references.size()>4 || new HashSet<>(references).size()!=references.size())
            throw new ServiceException("请选择 1～4 个不同颜色款");
        if (name==null || name.isBlank() || name.length()>100) throw new ServiceException("组合名称需为 1～100 字");
        if (quantities==null || quantities.size()>800) throw new ServiceException("请填写尺码数量");
        transaction.executeWithoutResult(status -> catalogWriteLock.executeLocked(10, () -> {
            FashionQuote quote=quotes.requireAccessible(FashionId.parse(quoteId).value());
            if (!"draft".equals(quote.status()) || !quote.requirementConfirmed() || quote.rowVersion()!=version)
                throw new ServiceException("请先确认需求，或刷新已变化的方案",409);
            JsonNode snapshot=freeze(quote,null,null);
            if(!snapshot.path("candidate_set_hash").asText().equals(candidateSetHash)) throw new ServiceException("商品价格或库存已变化，请刷新后重新选品",409);
            Map<String,JsonNode> candidates=candidates(snapshot);
            List<JsonNode> chosen=new ArrayList<>();
            for(String ref:references){JsonNode item=candidates.get(ref);if(item==null)throw new ServiceException("所选商品已不满足当前条件");chosen.add(item);}
            List<Tier> specifications=tiers(quote.comboTemplateJson());
            Tier selected=specifications.stream().filter(t->t.count()==chosen.size()).findFirst().orElseThrow(()->new ServiceException("所选品类数量不在方案档位中"));
            if(quote.progressive() && selected.count()!=specifications.stream().mapToInt(Tier::count).max().orElse(0)) throw new ServiceException("递进方案请选齐最高档品类，保存时同步生成各档组合");
            for(int i=0;i<chosen.size();i++) if(!selected.slots().get(i).equals(chosen.get(i).path("category_code").asText())) throw new ServiceException("请选择与方案槽位顺序一致的品类");
            long price=chosen.stream().mapToLong(c->c.path("conservative_unit_price_minor").asLong()).sum();
            long budget=snapshot.path("budget_maximum_per_set_minor").asLong();
            if(budget>0 && price>budget)throw new ServiceException("所选组合超过每套预算");
            Map<Long,Integer> allocations=new LinkedHashMap<>();
            quantities.forEach((id,qty)->{if(qty==null||qty<0)throw new ServiceException("尺码数量必须为非负整数");allocations.put(FashionId.parse(id).value(),qty);});
            Set<Long> validIds=new HashSet<>();
            for(JsonNode candidate:chosen){
                int sum=0;
                for(SelectionProductFact fact:factsForCandidate(candidate,quote)){
                    validIds.add(fact.productId());int qty=allocations.getOrDefault(fact.productId(),0);
                    if(qty>fact.availableQty())throw new ServiceException(fact.skuCode()+" 库存不足");
                    sum=Math.addExact(sum,qty);
                }
                if(sum!=quote.requestedQty())throw new ServiceException("每个颜色款尺码数量之和必须等于采购套数");
            }
            if(!validIds.containsAll(allocations.keySet()))throw new ServiceException("包含未选商品的尺码分配");
            Instant now=time.now();
            if(!quoteRepository.touchDraft(quote.id(),version,operatorId,now))throw new ServiceException("方案已变化，请刷新",409);
            if(quote.progressive())repository.deselectCurrent(quote.id(),operatorId,now);
            List<Tier> targetTiers=quote.progressive()?specifications:List.of(selected);
            int sort=repository.findByQuoteId(quote.id()).size();
            for(Tier tier:targetTiers){
                List<JsonNode> subset=chosen.subList(0,tier.count());
                for(int i=0;i<subset.size();i++)if(!tier.slots().get(i).equals(subset.get(i).path("category_code").asText()))throw new ServiceException("递进档位品类顺序不一致");
                Map<Integer,String> refs=new LinkedHashMap<>();for(int i=0;i<subset.size();i++)refs.put(i+1,subset.get(i).path("candidate_ref").asText());
                PreparedCombo combo=new PreparedCombo(name.trim()+(quote.progressive()?" · "+tier.count()+"品类":""),"人工选品",tier.count(),List.copyOf(subset),comboVisualHash(refs,candidates),List.of());
                long id=ids.nextId();
                repository.insertCombo(new SelectionComboWrite(id,quote.id(),"C-"+id,combo.name(),combo.categoryCount(),quote.requestedQty(),sort++,combo.reason(),List.of(),combo.visualHash(),operatorId,now),
                        details(id,null,combo,quote,operatorId,now,allocations));
            }
            return null;
        }));
        return workspace(quoteId);
    }

    public SelectionWorkspace removeManual(String quoteId,String comboId,long quoteVersion,long comboVersion,long operatorId){
        transaction.executeWithoutResult(status->{
            FashionQuote quote=quotes.requireAccessible(FashionId.parse(quoteId).value());
            if(!"draft".equals(quote.status()))throw new ServiceException("只能修改草稿方案");
            long id=FashionId.parse(comboId).value();
            SelectionComboView combo=repository.findCombo(quote.id(),id).orElseThrow(()->new ServiceException("组合不存在"));
            if(!combo.selected() || !combo.lockedSlots().isEmpty())throw new ServiceException("请先解除组合锁定");
            Instant now=time.now();
            if(!quoteRepository.touchDraft(quote.id(),quoteVersion,operatorId,now)||!repository.deselectCombo(id,comboVersion,operatorId,now))throw new ServiceException("方案或组合已变化",409);
        });
        return workspace(quoteId);
    }

    private JsonNode freeze(FashionQuote quote, SelectionComboView base, String expectedVisualHash) {
        List<Tier> tiers = tiers(quote.comboTemplateJson());
        Set<String> categories = new LinkedHashSet<>();
        tiers.forEach(tier -> categories.addAll(tier.slots()));
        List<String> exclusions = strings(quote.requirementJson().path("exclusions"));
        long budgetMinor = budgetPerSetMinor(quote);
        Map<String, List<SelectionProductFact>> grouped = new LinkedHashMap<>();
        for (SelectionProductFact fact : repository.findCandidateFacts(categories, quote.warehouseCode())) {
            if (!usableImage(fact) || excluded(fact, exclusions)) continue;
            String key = fact.sourceCode() + "\u001f" + fact.categoryCode() + "\u001f"
                    + fact.styleCode() + "\u001f" + fact.colorCode();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact);
        }
        List<ObjectNode> candidateNodes = new ArrayList<>();
        Map<String, Integer> perCategory = new HashMap<>();
        grouped.values().stream().map(this::candidateNode)
                .filter(node -> node.path("total_available_qty").asInt() >= quote.requestedQty())
                .filter(node -> budgetMinor == 0 || node.path("conservative_unit_price_minor").asLong() <= budgetMinor)
                .sorted(Comparator.comparing((ObjectNode node) -> node.path("category_code").asText())
                        .thenComparing(node -> node.path("product_name").asText())
                        .thenComparing(node -> node.path("candidate_ref").asText()))
                .forEach(node -> {
                    String category = node.path("category_code").asText();
                    int count = perCategory.getOrDefault(category, 0);
                    if (count < MAX_CANDIDATES_PER_CATEGORY) {
                        candidateNodes.add(node);
                        perCategory.put(category, count + 1);
                    }
                });
        ObjectNode root = objectMapper.createObjectNode();
        root.put("quote_ref", Long.toString(quote.id()));
        root.put("quote_row_version", quote.rowVersion());
        root.put("selection_mode", quote.progressive() ? "progressive" : "independent");
        root.put("requested_qty", quote.requestedQty());
        if (budgetMinor == 0) root.putNull("budget_maximum_per_set_minor");
        else root.put("budget_maximum_per_set_minor", budgetMinor);
        root.set("requirements", safeRequirements(quote));
        ArrayNode tierNodes = root.putArray("tiers");
        tiers.forEach(tier -> tierNodes.add(tierNode(tier)));
        ArrayNode candidates = root.putArray("frozen_candidates");
        candidateNodes.forEach(candidates::add);
        ArrayNode shortages = root.putArray("shortages");
        categories.forEach(category -> {
            if (perCategory.getOrDefault(category, 0) == 0) {
                ObjectNode shortage = shortages.addObject();
                shortage.put("category_code", category);
                shortage.put("reason", "没有同时满足上架、属性确认、图片、价格、库存、预算和排除项的候选");
            }
        });
        ArrayNode locks = root.putArray("locks");
        if (base != null) addLocks(base, candidateNodes, locks);
        root.put("candidate_set_hash", hashCandidateSet(root));
        return root;
    }

    private ObjectNode candidateNode(List<SelectionProductFact> facts) {
        facts.sort(Comparator.comparing(SelectionProductFact::sizeCode).thenComparingLong(SelectionProductFact::productId));
        SelectionProductFact first = facts.get(0);
        String candidateRef = "CAND-" + FashionHashing.sha256((first.sourceCode() + "\u001f"
                + first.categoryCode() + "\u001f" + first.styleCode() + "\u001f" + first.colorCode())
                .getBytes(StandardCharsets.UTF_8)).substring(0, 32);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("candidate_ref", candidateRef);
        node.put("category_code", first.categoryCode());
        node.put("source_ref", first.sourceCode());
        node.put("style_ref", first.styleCode());
        node.put("color_code", first.colorCode());
        node.put("color_name", first.colorName());
        node.put("product_name", first.productName());
        putNullable(node, "season", first.season());
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        facts.forEach(fact -> tags.addAll(fact.tags()));
        node.set("tags", objectMapper.valueToTree(tags.stream().limit(50).toList()));
        long maxPrice = facts.stream().mapToLong(fact -> minor(fact.salePrice())).max().orElseThrow();
        node.put("conservative_unit_price_minor", maxPrice);
        node.put("total_available_qty", facts.stream().mapToInt(SelectionProductFact::availableQty).sum());
        ArrayNode variants = node.putArray("variants");
        facts.forEach(fact -> {
            ObjectNode variant = variants.addObject();
            variant.put("product_ref", Long.toString(fact.productId()));
            variant.put("product_row_version", fact.productRowVersion());
            variant.put("sku_ref", fact.skuCode());
            variant.put("size_code", fact.sizeCode());
            variant.put("unit_price_minor", minor(fact.salePrice()));
            variant.put("available_qty", fact.availableQty());
            variant.put("stock_row_version", fact.stockRowVersion());
            variant.put("price_batch_ref", Long.toString(fact.priceBatchId()));
            variant.put("stock_batch_ref", Long.toString(fact.stockBatchId()));
        });
        node.put("visual_hash", candidateVisualHash(candidateRef, facts));
        node.put("fact_hash", factHash(node));
        return node;
    }

    private List<PreparedCombo> validateAndPrepare(
            JsonNode result, JsonNode frozen, Map<String, JsonNode> frozenCandidates,
            Map<String, JsonNode> currentCandidates, Map<String, String> expectedHashes,
            FashionQuote quote, RunView run) {
        List<Tier> specs = tiers(quote.comboTemplateJson());
        JsonNode outputTiers = result.path("tiers");
        if (!outputTiers.isArray() || outputTiers.size() != specs.size()) {
            throw new ServiceException("选品结果档位与方案不一致");
        }
        List<PreparedCombo> prepared = new ArrayList<>();
        Set<String> comboKeys = new HashSet<>();
        Set<String> signatures = new HashSet<>();
        List<Map<Integer, String>> previous = List.of();
        for (int tierIndex = 0; tierIndex < specs.size(); tierIndex++) {
            Tier spec = specs.get(tierIndex);
            JsonNode outputTier = outputTiers.get(tierIndex);
            if (outputTier.path("category_count").asInt() != spec.count()
                    || outputTier.path("requested_candidate_count").asInt() != spec.candidateCount()) {
                throw new ServiceException("选品结果档位参数被修改");
            }
            JsonNode combinations = outputTier.path("combinations");
            if (!combinations.isArray() || combinations.size() > spec.candidateCount()) {
                throw new ServiceException("选品结果候选数量无效");
            }
            if (combinations.size() < spec.candidateCount() && outputTier.path("shortage_reasons").isEmpty()) {
                throw new ServiceException("候选不足时必须说明原因");
            }
            List<Map<Integer, String>> currentTier = new ArrayList<>();
            for (JsonNode combo : combinations) {
                String comboKey = combo.path("combo_key").asText();
                if (comboKey.isBlank() || !comboKeys.add(comboKey)) throw new ServiceException("组合标识重复");
                JsonNode items = combo.path("items");
                if (!items.isArray() || items.size() != spec.count()) throw new ServiceException("组合槽位数无效");
                Map<Integer, String> chosen = new LinkedHashMap<>();
                List<JsonNode> chosenCandidates = new ArrayList<>();
                for (int slot = 1; slot <= spec.count(); slot++) {
                    JsonNode item = items.get(slot - 1);
                    String ref = item.path("candidate_ref").asText();
                    if (item.path("slot_index").asInt() != slot
                            || !spec.slots().get(slot - 1).equals(item.path("category_code").asText())) {
                        throw new ServiceException("组合槽位与品类不一致");
                    }
                    JsonNode frozenCandidate = frozenCandidates.get(ref);
                    JsonNode currentCandidate = currentCandidates.get(ref);
                    if (frozenCandidate == null || currentCandidate == null
                            || !spec.slots().get(slot - 1).equals(frozenCandidate.path("category_code").asText())
                            || !frozenCandidate.path("fact_hash").asText().equals(currentCandidate.path("fact_hash").asText())) {
                        throw new ServiceException("候选商品、价格、库存或图片已变化，请重新生成", 409);
                    }
                    chosen.put(slot, ref);
                    chosenCandidates.add(currentCandidate);
                }
                String signature = String.join("|", chosen.values());
                if (!signatures.add(signature)) throw new ServiceException("选品结果包含重复组合");
                validateLocks(frozen.path("locks"), chosen);
                if (quote.progressive() && !previous.isEmpty()) {
                    Map<Integer, String> base = previous.get(Math.min(currentTier.size(), previous.size() - 1));
                    base.forEach((slot, ref) -> {
                        if (!ref.equals(chosen.get(slot))) throw new ServiceException("递进档位未继承上一档候选");
                    });
                }
                String visualHash = comboVisualHash(chosen, currentCandidates);
                if (!visualHash.equals(combo.path("combo_visual_hash").asText())
                        || !visualHash.equals(expectedHashes.get(comboKey))) {
                    throw new ServiceException("组合视觉摘要不一致，请刷新后重试", 409);
                }
                long price = chosenCandidates.stream()
                        .mapToLong(candidate -> candidate.path("conservative_unit_price_minor").asLong()).sum();
                if (price != combo.path("conservative_unit_price_minor").asLong()) {
                    throw new ServiceException("组合预算摘要不一致");
                }
                long budget = frozen.path("budget_maximum_per_set_minor").asLong(0);
                if (budget > 0 && price > budget) throw new ServiceException("组合超过当前每套预算");
                prepared.add(new PreparedCombo(combo.path("name").asText(), combo.path("reason").asText(),
                        spec.count(), chosenCandidates, visualHash, lockedSlots(frozen.path("locks"))));
                currentTier.add(chosen);
            }
            previous = currentTier;
        }
        return prepared;
    }

    private List<SelectionDetailWrite> details(
            long comboId, Long aiRunId, PreparedCombo combo, FashionQuote quote, long operatorId, Instant now) {
        return details(comboId, aiRunId, combo, quote, operatorId, now, null);
    }

    private List<SelectionDetailWrite> details(long comboId, Long aiRunId, PreparedCombo combo,
            FashionQuote quote, long operatorId, Instant now, Map<Long,Integer> explicitAllocation) {
        List<SelectionDetailWrite> details = new ArrayList<>();
        int line = 1;
        for (int slot = 1; slot <= combo.candidates().size(); slot++) {
            JsonNode candidate = combo.candidates().get(slot - 1);
            List<SelectionProductFact> currentFacts = factsForCandidate(candidate, quote);
            Map<Long, Integer> allocation = explicitAllocation == null ? allocate(currentFacts, quote, candidate.path("category_code").asText()) : explicitAllocation;
            for (SelectionProductFact fact : currentFacts) {
                int qty = allocation.getOrDefault(fact.productId(), 0);
                if (qty == 0) continue;
                FashionProductImage image = mainImage(fact);
                details.add(new SelectionDetailWrite(ids.nextId(), comboId, aiRunId, line++,
                        "SLOT-" + slot, fact.productId(), fact.sourceCode(), fact.skuCode(), fact.styleCode(),
                        fact.productName(), fact.categoryCode(), fact.colorCode(), fact.colorName(), fact.sizeCode(),
                        fact.sizeSystem(), fact.unit(), qty, fact.salePrice(), fact.priceBatchId(), fact.stockBatchId(),
                        fact.availableQty(), fact.stockAsOf(), fact.mainImageKey(), image.sha256(),
                        fact.visualVersion(), operatorId, now));
            }
        }
        return details;
    }

    private List<SelectionProductFact> factsForCandidate(JsonNode candidate, FashionQuote quote) {
        Set<Long> ids = new HashSet<>();
        candidate.path("variants").forEach(variant -> ids.add(Long.parseLong(variant.path("product_ref").asText())));
        return repository.findCandidateFacts(Set.of(candidate.path("category_code").asText()), quote.warehouseCode())
                .stream().filter(fact -> ids.contains(fact.productId())).toList();
    }

    private Map<Long, Integer> allocate(List<SelectionProductFact> facts, FashionQuote quote, String category) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        int remaining = quote.requestedQty();
        Map<String, Integer> requestedSizes = new LinkedHashMap<>();
        quote.requirementJson().path("size_requirements").forEach(item -> {
            String itemCategory = item.path("category").asText("");
            if (itemCategory.isBlank() || itemCategory.equals(category)) {
                requestedSizes.merge(item.path("size_label").asText(), item.path("quantity").asInt(), Integer::sum);
            }
        });
        if (!requestedSizes.isEmpty()) {
            int total = requestedSizes.values().stream().mapToInt(Integer::intValue).sum();
            if (total != quote.requestedQty()) throw new ServiceException("尺码数量之和必须等于采购套数");
            for (Map.Entry<String, Integer> request : requestedSizes.entrySet()) {
                int need = request.getValue();
                for (SelectionProductFact fact : facts) {
                    if (!fact.sizeCode().equals(request.getKey()) || need == 0) continue;
                    int qty = Math.min(need, fact.availableQty());
                    result.put(fact.productId(), qty);
                    need -= qty;
                }
                if (need > 0) throw new ServiceException(category + " 的 " + request.getKey() + " 尺码库存不足");
            }
            return result;
        }
        int base = quote.requestedQty() / facts.size();
        int extra = quote.requestedQty() % facts.size();
        for (int index = 0; index < facts.size(); index++) {
            SelectionProductFact fact = facts.get(index);
            int target = base + (index < extra ? 1 : 0);
            int qty = Math.min(target, fact.availableQty());
            if (qty > 0) result.put(fact.productId(), qty);
            remaining -= qty;
        }
        for (SelectionProductFact fact : facts) {
            if (remaining == 0) break;
            int existing = result.getOrDefault(fact.productId(), 0);
            int qty = Math.min(remaining, fact.availableQty() - existing);
            if (qty > 0) result.put(fact.productId(), existing + qty);
            remaining -= qty;
        }
        if (remaining > 0) throw new ServiceException(category + " 当前库存不足");
        return result;
    }

    private static void validateLocks(JsonNode locks, Map<Integer, String> chosen) {
        if (!locks.isArray()) return;
        locks.forEach(lock -> {
            int slot = lock.path("slot_index").asInt();
            if (chosen.containsKey(slot) && !lock.path("candidate_ref").asText().equals(chosen.get(slot))) {
                throw new ServiceException("选品结果未保持锁定项");
            }
        });
    }

    private static List<String> lockedSlots(JsonNode locks) {
        if (!locks.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        locks.forEach(lock -> result.add("SLOT-" + lock.path("slot_index").asInt()));
        return List.copyOf(result);
    }

    private void addLocks(SelectionComboView base, List<ObjectNode> candidates, ArrayNode locks) {
        Map<String, ObjectNode> byBusinessKey = new HashMap<>();
        candidates.forEach(candidate -> byBusinessKey.put(candidate.path("source_ref").asText() + "\u001f"
                + candidate.path("style_ref").asText() + "\u001f" + candidate.path("color_code").asText(), candidate));
        for (String slotCode : base.lockedSlots()) {
            SelectionDetailView detail = base.details().stream().filter(item -> slotCode.equals(item.slotCode()))
                    .findFirst().orElseThrow(() -> new ServiceException("锁定槽位缺少商品明细"));
            ObjectNode candidate = byBusinessKey.get(detail.sourceCode() + "\u001f" + detail.styleCode()
                    + "\u001f" + detail.colorCode());
            if (candidate == null) throw new ServiceException("锁定商品已不满足当前硬约束");
            int slot = Integer.parseInt(slotCode.substring("SLOT-".length()));
            ObjectNode lock = locks.addObject();
            lock.put("slot_index", slot);
            lock.put("category_code", detail.categoryCode());
            lock.put("candidate_ref", candidate.path("candidate_ref").asText());
            lock.put("candidate_visual_hash", candidate.path("visual_hash").asText());
        }
    }

    private ObjectNode safeRequirements(FashionQuote quote) {
        ObjectNode node = objectMapper.createObjectNode();
        for (String name : List.of("scheme_name", "audience", "scene", "season", "style", "delivery_date")) {
            putNullable(node, name, quote.requirementJson().path(name).isTextual()
                    ? quote.requirementJson().path(name).asText() : null);
        }
        node.set("preferred_colors", array(quote.requirementJson().path("preferred_colors")));
        node.set("exclusions", array(quote.requirementJson().path("exclusions")));
        node.put("people_count", quote.requestedQty());
        node.put("set_count", quote.requestedQty());
        node.set("size_requirements", array(quote.requirementJson().path("size_requirements")));
        node.put("selection_mode", quote.progressive() ? "progressive" : "independent");
        node.putArray("category_tiers");
        node.putArray("budget_constraints");
        return node;
    }

    private ObjectNode tierNode(Tier tier) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("category_count", tier.count());
        node.put("candidate_count", tier.candidateCount());
        ArrayNode slots = node.putArray("slots");
        for (int index = 0; index < tier.slots().size(); index++) {
            ObjectNode slot = slots.addObject();
            slot.put("slot_index", index + 1);
            slot.put("category", tier.slots().get(index));
            slot.put("required", true);
        }
        return node;
    }

    private List<Tier> tiers(JsonNode template) {
        List<Tier> result = new ArrayList<>();
        template.path("groups").forEach(group -> result.add(new Tier(group.path("count").asInt(),
                strings(group.path("slots")), group.path("candidate_count").asInt())));
        if (result.isEmpty()) throw new ServiceException("方案没有品类档位");
        return List.copyOf(result);
    }

    private static Map<String, JsonNode> candidates(JsonNode snapshot) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        snapshot.path("frozen_candidates").forEach(candidate ->
                result.put(candidate.path("candidate_ref").asText(), candidate));
        return result;
    }

    private static boolean excluded(SelectionProductFact fact, List<String> exclusions) {
        String haystack = String.join(" ", fact.skuCode(), fact.styleCode(), fact.productName(),
                fact.categoryCode(), fact.colorCode(), fact.colorName(), String.join(" ", fact.tags()))
                .toLowerCase(Locale.ROOT);
        return exclusions.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(haystack::contains);
    }

    private static boolean usableImage(SelectionProductFact fact) {
        return fact.images().stream().anyMatch(image -> fact.mainImageKey().equals(image.objectKey())
                && "active".equals(image.status()) && image.allowProposal());
    }

    private static FashionProductImage mainImage(SelectionProductFact fact) {
        return fact.images().stream().filter(image -> fact.mainImageKey().equals(image.objectKey())
                && "active".equals(image.status()) && image.allowProposal()).findFirst()
                .orElseThrow(() -> new ServiceException("商品主图已不可用于方案"));
    }

    private static String candidateVisualHash(String candidateRef, List<SelectionProductFact> facts) {
        StringBuilder source = new StringBuilder(candidateRef);
        facts.forEach(fact -> source.append('|').append(fact.productId()).append(':')
                .append(fact.productRowVersion()).append(':').append(fact.visualVersion()).append(':')
                .append(mainImage(fact).sha256()));
        return FashionHashing.sha256(source.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String comboVisualHash(Map<Integer, String> chosen, Map<String, JsonNode> candidates) {
        String source = chosen.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue() + ":"
                + candidates.get(entry.getValue()).path("visual_hash").asText())
                .collect(java.util.stream.Collectors.joining("|"));
        return FashionHashing.sha256(source.getBytes(StandardCharsets.UTF_8));
    }

    private String factHash(ObjectNode node) {
        ObjectNode copy = node.deepCopy();
        copy.remove("fact_hash");
        try {
            return FashionHashing.sha256(objectMapper.writeValueAsBytes(copy));
        } catch (Exception exception) {
            throw new IllegalStateException("候选快照无法编码", exception);
        }
    }

    private String hashCandidateSet(ObjectNode root) {
        List<String> factHashes = new ArrayList<>();
        root.path("frozen_candidates").forEach(candidate -> factHashes.add(candidate.path("fact_hash").asText()));
        String source = String.join("|", factHashes);
        return FashionHashing.sha256(source.getBytes(StandardCharsets.UTF_8));
    }

    private static long budgetPerSetMinor(FashionQuote quote) {
        if (quote.budget() == null) return 0;
        BigDecimal perSet = "total".equals(quote.budgetBasis())
                ? quote.budget().divide(BigDecimal.valueOf(quote.requestedQty()), 2, RoundingMode.DOWN)
                : quote.budget();
        return minor(perSet);
    }

    private static long minor(BigDecimal value) {
        return value.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private ArrayNode array(JsonNode source) {
        ArrayNode result = objectMapper.createArrayNode();
        if (source.isArray()) source.forEach(result::add);
        return result;
    }

    private static List<String> strings(JsonNode source) {
        List<String> result = new ArrayList<>();
        if (source.isArray()) source.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null || value.isBlank()) node.putNull(name); else node.put(name, value);
    }

    private record Tier(int count, List<String> slots, int candidateCount) {
    }

    private record PreparedCombo(
            String name,
            String reason,
            int categoryCount,
            List<JsonNode> candidates,
            String visualHash,
            List<String> lockedSlots) {
    }
}
