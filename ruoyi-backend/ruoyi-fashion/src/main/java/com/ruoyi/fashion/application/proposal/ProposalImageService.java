package com.ruoyi.fashion.application.proposal;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.application.pool.FashionPoolService;
import com.ruoyi.fashion.application.pool.PoolModels;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.application.image.port.FashionImageProviderPort;
import com.ruoyi.fashion.infrastructure.files.FashionDeliveryMedia;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import com.ruoyi.fashion.infrastructure.storage.FashionUploadPolicy;
import static com.ruoyi.fashion.application.proposal.ProposalModels.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock;

/** 草稿试衣独立于正式报价；无 Provider 时明确拒绝，不制造成功图。 */
@Service @FashionModuleEnabled
public class ProposalImageService {
    private final FashionPoolService pools; private final FashionProductRepository products;
    private final ProposalImageRepository repository; private final FashionImageProviderPort provider;
    private final FashionObjectStoragePort storage; private final FashionDeliveryMedia media; private final ObjectMapper mapper;
    private final ISysConfigService config; private final TransactionTemplate transaction; private final FashionCatalogWriteLock lock;
    public ProposalImageService(FashionPoolService pools,FashionProductRepository products,ProposalImageRepository repository,
            FashionImageProviderPort provider,FashionObjectStoragePort storage,FashionDeliveryMedia media,ObjectMapper mapper){
        this(pools,products,repository,provider,storage,media,mapper,null,null,null);
    }
    @Autowired
    public ProposalImageService(FashionPoolService pools,FashionProductRepository products,ProposalImageRepository repository,
            FashionImageProviderPort provider,FashionObjectStoragePort storage,FashionDeliveryMedia media,ObjectMapper mapper,
            ISysConfigService config,@Qualifier("fashionTransactionTemplate") TransactionTemplate transaction,FashionCatalogWriteLock lock){
        this.pools=pools;this.products=products;this.repository=repository;this.provider=provider;this.storage=storage;this.media=media;this.mapper=mapper;
        this.config=config;this.transaction=transaction;this.lock=lock;
    }
    public Image uploadModel(String pool,long owner,long version,String filename,byte[] bytes) {
        pools.requireExport(pool,owner,version);
        var info=FashionUploadPolicy.validateImage(filename,bytes);
        var hash=FashionHashing.sha256(bytes);
        var key="proposal-models/"+owner+"/"+hash+"."+info.extension();
        storage.putIfAbsent(key,bytes,info.contentType());return new Image(key,hash);
    }
    public Map<String,Object> workspace(String pool,long owner,long version) {
        pools.requireExport(pool,owner,version);
        var cap=capabilities();String reason=blockedReason(cap);var data=new LinkedHashMap<String,Object>();data.put("providerAvailable",reason==null);data.put("blockedReason",reason==null?"":reason);data.put("capabilities",cap);data.put("monthlyBudget",monthlyBudget());data.put("tasks",repository.list(pool,owner).stream().map(this::view).toList());return data;
    }
    public Map<String,Object> create(String pool,long owner,TryOnRequest request) {
        if(request==null || request.requestKey()==null || !request.requestKey().matches("[A-Za-z0-9_-]{8,100}")) throw new ServiceException("试衣请求编号无效");
        var inputs=inputs(pool,owner,request);String hash=FashionHashing.sha256(mapper.writeValueAsString(inputs).getBytes(StandardCharsets.UTF_8));
        var prior=repository.request(request.requestKey(),owner);
        if(prior.isPresent()) {
            if(!prior.get().poolId().equals(pool) || !prior.get().inputHash().equals(hash)) throw new ServiceException("请求编号已用于其他试衣输入",409);
            return view(prior.get());
        }
        if(!provider.available()) throw new ServiceException("模特试衣服务尚未接通；本次未创建生成任务，请使用商品原图提案",503);
        var cap=capabilities();String blocked=blockedReason(cap);if(blocked!=null)throw new ServiceException(blocked,503);
        if(!Boolean.TRUE.equals(request.costConfirmed())||request.maxCost()==null||request.maxCost().signum()<0||request.maxCost().compareTo(cap.estimatedUnitCost())<0)throw new ServiceException("请明确确认试衣费用上限，且不得低于本次费用估计");
        var cost=new ProposalImageTask.Costs(cap.providerCode(),cap.model(),cap.estimatedUnitCost(),null,cap.currency(),"pending",Instant.now(),null,null,null,0);
        var task=new ProposalImageTask(UUID.randomUUID().toString(),pool,owner,request.rowVersion(),request.combinationId(),hash,inputs,request.requestKey(),"submitting",null,mapper.createArrayNode(),null,mapper.createObjectNode(),1,cost);
        try { reserve(task); }
        catch(org.springframework.dao.DuplicateKeyException ex){return view(repository.request(request.requestKey(),owner).filter(t->t.inputHash().equals(hash)).orElseThrow(()->new ServiceException("重复请求输入冲突",409)));}
        try {var response=provider.submit(task.id(),"model",inputs,mapper.createObjectNode(),1);
            if(response.providerTaskId()!=null){task=new ProposalImageTask(task.id(),task.poolId(),task.ownerId(),task.draftVersion(),task.combinationId(),task.inputHash(),task.inputs(),task.requestKey(),task.status(),response.providerTaskId(),task.results(),task.errorMessage(),task.review(),task.rowVersion(),task.costs());task=save(task,"running",response.providerTaskId(),task.results(),null,task.review());}
            task=complete(task,response);}
        catch(Exception ex){task=save(task,"unknown",task.providerTaskId(),task.results(),"生成服务响应未确认，请查询状态后再决定重试",task.review());}
        return view(task);
    }
    private ObjectNode inputs(String pool,long owner,TryOnRequest request) {
        var selected=pools.requireExport(pool,owner,request.rowVersion());
        var combo=selected.combinations().stream().filter(c->c.id().equals(request.combinationId())).findFirst().orElseThrow(()->new ServiceException("试衣只能使用已选方案"));
        if(request.model()==null || request.model().objectKey()==null || request.model().sha256()==null
                || !request.model().sha256().matches("[a-f0-9]{64}")
                || !request.model().objectKey().matches("proposal-models/"+owner+"/"+request.model().sha256()+"\\.(png|jpg|jpeg|webp)")) throw new ServiceException("请选择当前账号上传的模特素材");
        media.readVerified(request.model().objectKey(),request.model().sha256());
        var root=mapper.createObjectNode();root.put("poolId",pool);root.put("draftVersion",request.rowVersion());root.put("combinationId",combo.id());root.set("model",mapper.valueToTree(request.model()));
        var positions=request.garmentPositions()==null?Map.<String,String>of():request.garmentPositions();
        if(!positions.isEmpty()&&(positions.size()!=combo.items().size()||!positions.keySet().equals(new HashSet<>(combo.items().stream().map(PoolModels.MainSku::key).toList()))||positions.values().stream().anyMatch(p->!java.util.Set.of("top","bottom","dress","original").contains(p==null?"":p))))throw new ServiceException("请为每个选中商品明确服装位置，鞋帽使用原图");
        root.set("garmentPositions",mapper.valueToTree(positions));root.put("maxCost",request.maxCost());root.put("costConfirmed",Boolean.TRUE.equals(request.costConfirmed()));
        var coverage=root.putObject("coverage");var supported=coverage.putArray("supported");var original=coverage.putArray("original");coverage.put("message","原图位置未参与试衣，生成图不代表已覆盖整套商品");
        var array=root.putArray("products");var choices=selected.selection().variantSelections().getOrDefault(combo.id(),Map.of());
        for(var main:combo.items()) {
            String id=choices.get(main.key());
            // 颜色必须明确，禁止悄悄选取任一颜色用于试衣。
            if(id==null) throw new ServiceException("请先为 "+main.name()+" 指定试衣颜色");
            var variant=main.variants().stream().filter(v->v.productId().equals(id)).findFirst().orElseThrow(()->new ServiceException("试衣颜色不属于当前方案"));
            var current=products.findById(Long.parseLong(id)).orElseThrow(()->new ServiceException("试衣商品已不存在"));
            if(current.visualVersion()!=variant.visualVersion()) throw new ServiceException("商品图片版本已更新，请重新筛选并确认试衣输入",409);
            String position=positions.getOrDefault(main.key(),"original");boolean sent=!"original".equals(position);
            var image=variant.images().stream().filter(i->Objects.equals(i.objectKey(),variant.mainImageKey())&&(sent?i.allowAi():i.allowProposal())&&"active".equals(i.status())).findFirst().orElseThrow(()->new ServiceException(main.name()+"没有允许所选用途的颜色图片"));
            if(current.images().stream().noneMatch(i->(sent?i.allowAi():i.allowProposal())&&"active".equals(i.status())&&Objects.equals(i.objectKey(),image.objectKey())&&Objects.equals(i.sha256(),image.sha256()))) throw new ServiceException("商品图片授权已变更",409);
            media.readVerified(image.objectKey(),image.sha256());
            var node=array.addObject();node.put("mainSkuKey",main.key());node.put("mainSku",main.mainSkuCode());node.put("productId",id);node.put("color",variant.colorName());node.put("object_key",image.objectKey());node.put("sha256",image.sha256());node.put("visualVersion",variant.visualVersion());node.put("garment_position",position);if(sent)supported.add(main.key());else original.add(main.key());
        }
        if(provider.available())validatePositions(positions,capabilities());
        return root;
    }
    public Map<String,Object> get(String pool,long owner,String id) {
        var task=require(pool,owner,id);
        if(java.util.Set.of("running","queued","unknown","cancel_requested").contains(task.status()) && task.providerTaskId()!=null && task.costs().retryCount()<8 && !expired(task) && provider.available()) {
            try { task=complete(task,provider.query(task.providerTaskId())); }
            catch(ServiceException ex){throw ex;}
            catch(Exception ex){throw new ServiceException("试衣状态查询失败，可稍后重试",503);}
        }
        return view(task);
    }
    public Map<String,Object> cancel(String pool,long owner,String id,long version) {
        var task=require(pool,owner,id);checkVersion(task,version);
        if(!java.util.Set.of("queued","running","unknown").contains(task.status())) throw new ServiceException("当前状态不能取消",409);
        if(task.providerTaskId()==null)throw new ServiceException("没有外部任务ID，请人工对账，无法确认取消",409);
        if(!provider.available()||!capabilities().supportsCancellation())throw new ServiceException("当前试衣服务不支持取消，请等待结果或人工对账",409);
        // 先写取消请求；外部取消不保证撤销已开始的生成或费用。
        task=save(task,"cancel_requested",task.providerTaskId(),task.results(),"已请求取消，已开始的生成仍可能产生结果或费用",task.review());
        if(task.providerTaskId()!=null) provider.cancel(task.providerTaskId());
        return view(task);
    }
    public Map<String,Object> review(String pool,long owner,String id,ReviewRequest request) {
        var task=require(pool,owner,id);checkVersion(task,request.rowVersion());
        if(stale(task)) throw new ServiceException("试衣输入版本已过期，请重新生成",409);
        if(!"review".equals(task.status()) && !"adopted".equals(task.status()) && !"rejected".equals(task.status())) throw new ServiceException("仅完成的试衣图片可以复核",409);
        var result=result(task,request.resultNo());
        if(request.adopt() && (request.checks()==null || !List.of("style","color","completeness","quality").stream().allMatch(k->Boolean.TRUE.equals(request.checks().get(k))))) throw new ServiceException("请逐项确认款式、颜色、完整性和图片质量");
        if(!request.adopt() && (request.reason()==null || request.reason().isBlank())) throw new ServiceException("请填写不采用原因");
        var review=mapper.createObjectNode();review.put("resultNo",request.resultNo());review.put("adopted",request.adopt());review.put("inputHash",task.inputHash());review.put("reason",request.reason()==null?"":request.reason());review.set("checks",mapper.valueToTree(request.checks()));review.put("reviewer",owner);review.put("reviewedAt",Instant.now().toString());review.set("coverage",task.inputs().path("coverage"));
        if(request.adopt()) media.readVerified(result.path("object_key").asText(),result.path("sha256").asText());
        return view(save(task,request.adopt()?"adopted":"rejected",task.providerTaskId(),task.results(),null,review));
    }
    public Image adoptedImage(String pool,long owner,String id,long version,String combo) {
        var image=frozenAdoptedImage(pool,owner,id,version,combo);
        if(stale(require(pool,owner,id)))throw new ServiceException("模特图已过期",409);
        return image;
    }
    /** 历史交付使用冻结稿；后续草稿变化不改旧稿，但撤回采用立即阻止下载。 */
    public Image frozenAdoptedImage(String pool,long owner,String id,long version,String combo) {
        if(id==null) throw new ServiceException("请选择已采用的模特图");
        var task=require(pool,owner,id);
        if(task.draftVersion()!=version || !task.combinationId().equals(combo) || !"adopted".equals(task.status()) || !task.inputHash().equals(task.review().path("inputHash").asText())) throw new ServiceException("模特图未采用或采用已撤回",409);
        for(var input:task.inputs().path("products")){if("original".equals(input.path("garment_position").asText()))continue;var product=products.findById(input.path("productId").asLong()).orElseThrow(()->new ServiceException("试衣源商品已不存在",409));if(product.images().stream().noneMatch(i->"active".equals(i.status())&&i.allowAi()&&Objects.equals(i.objectKey(),input.path("object_key").asText())&&Objects.equals(i.sha256(),input.path("sha256").asText())))throw new ServiceException("试衣源图AI许可已撤销，不能下载该模特提案",409);}
        var result=result(task,task.review().path("resultNo").asInt());return new Image(result.path("object_key").asText(),result.path("sha256").asText());
    }
    public byte[] resultContent(String pool,long owner,String id,int resultNo) {var task=require(pool,owner,id);var result=result(task,resultNo);return media.readVerified(result.path("object_key").asText(),result.path("sha256").asText());}
    private JsonNode result(ProposalImageTask task,int no) {if(no<0||no>=task.results().size())throw new ServiceException("试衣结果不存在");var result=task.results().get(no);if(!"success".equals(result.path("status").asText()))throw new ServiceException("结果尚未成功");return result;}
    private ProposalImageTask require(String pool,long owner,String id){if(id==null)throw new ServiceException("任务不存在",404);return repository.find(id,owner).filter(t->t.poolId().equals(pool)).orElseThrow(()->new ServiceException("任务不存在或无权访问",404));}
    private void checkVersion(ProposalImageTask task,long version){if(task.rowVersion()!=version)throw new ServiceException("任务已变更，请刷新",409);}
    private boolean stale(ProposalImageTask task) {
        try {var model=mapper.treeToValue(task.inputs().path("model"),Image.class);Map<String,String> positions=mapper.convertValue(task.inputs().path("garmentPositions"),new tools.jackson.core.type.TypeReference<Map<String,String>>(){});BigDecimal max=task.inputs().path("maxCost").isNumber()?task.inputs().path("maxCost").decimalValue():null;var current=inputs(task.poolId(),task.ownerId(),new TryOnRequest(task.draftVersion(),task.combinationId(),model,task.requestKey(),positions,max,task.inputs().path("costConfirmed").asBoolean(false)));return !FashionHashing.sha256(mapper.writeValueAsString(current).getBytes(StandardCharsets.UTF_8)).equals(task.inputHash());}
        catch(Exception ex){return true;}
    }
    /** 页面仅取得冻结业务身份，不返回Provider提示词、签名URL或私有对象键。 */
    private JsonNode publicInputs(ProposalImageTask task) {
        var output=mapper.createObjectNode();output.put("poolId",task.poolId());output.put("draftVersion",task.draftVersion());output.put("combinationId",task.combinationId());
        var items=output.putArray("products");
        for(var product:task.inputs().path("products")){var item=items.addObject();for(String field:List.of("productId","mainSku","mainSkuKey","color","visualVersion","garment_position"))if(product.has(field))item.set(field,product.get(field));}
        return output;
    }
    private Map<String,Object> view(ProposalImageTask task){var map=new LinkedHashMap<String,Object>();map.put("id",task.id());map.put("status",task.status());map.put("rowVersion",task.rowVersion());map.put("draftVersion",task.draftVersion());map.put("combinationId",task.combinationId());map.put("inputHash",task.inputHash());map.put("results",task.results());map.put("errorMessage",task.errorMessage());map.put("review",task.review());map.put("coverage",task.inputs().path("coverage"));map.put("stale",stale(task));map.put("estimatedCost",task.costs().estimatedCost());map.put("actualCost",task.costs().actualCost());map.put("currency",task.costs().currency());map.put("billingStatus",task.costs().billingStatus());map.put("finishedAt",task.costs().finishedAt());map.put("ownerId",Long.toString(task.ownerId()));map.put("submittedAt",task.costs().submittedAt());map.put("providerCode",task.costs().providerCode());map.put("model",task.costs().model());map.put("retryCount",task.costs().retryCount());map.put("cancelRequestedAt",task.costs().cancelRequestedAt());map.put("resultCount",task.results().isArray()?task.results().size():0);map.put("frozenInputs",publicInputs(task));return map;}
    private ProposalImageTask complete(ProposalImageTask task,FashionImageProviderPort.ProviderResult response){
        var results=response.results()==null?mapper.createArrayNode():response.results();
        String state=switch(response.status()){case "success","succeeded","completed","partial_success"->"review";case "failed"->"failed";case "cancelled"->"cancelled";case "queued"->"queued";case "unknown"->"unknown";default->"running";};
        if("review".equals(state)) {if(!results.isArray()||results.isEmpty()||!java.util.stream.StreamSupport.stream(results.spliterator(),false).anyMatch(r->"success".equals(r.path("status").asText())))throw new ServiceException("生成服务未返回成功图片");for(var result:results)if("success".equals(result.path("status").asText()))media.readVerified(result.path("object_key").asText(),result.path("sha256").asText());}
        BigDecimal actual=response.actualCost();if(actual!=null&&(actual.signum()<0||!Objects.equals(response.currency(),task.costs().currency())))actual=null;
        var old=task.costs();boolean finished=java.util.Set.of("review","failed","cancelled").contains(state);var costs=new ProposalImageTask.Costs(response.providerCode()==null?old.providerCode():response.providerCode(),old.model(),old.estimatedCost(),actual==null?old.actualCost():actual,old.currency(),actual==null?"unknown":"settled",old.submittedAt(),finished?Instant.now():old.finishedAt(),old.cancelRequestedAt(),finished?null:Instant.now().plusSeconds(15),old.retryCount());
        return save(new ProposalImageTask(task.id(),task.poolId(),task.ownerId(),task.draftVersion(),task.combinationId(),task.inputHash(),task.inputs(),task.requestKey(),task.status(),task.providerTaskId(),task.results(),task.errorMessage(),task.review(),task.rowVersion(),costs),state,response.providerTaskId()==null?task.providerTaskId():response.providerTaskId(),results,response.errorMessage(),task.review());
    }
    private ProposalImageTask save(ProposalImageTask t,String state,String providerId,JsonNode results,String error,JsonNode review){var c=t.costs();if("unknown".equals(state))c=new ProposalImageTask.Costs(c.providerCode(),c.model(),c.estimatedCost(),c.actualCost(),c.currency(),"unknown",c.submittedAt(),c.finishedAt(),c.cancelRequestedAt(),providerId==null||c.retryCount()>=8?null:Instant.now().plusSeconds(30),c.retryCount());if("cancel_requested".equals(state))c=new ProposalImageTask.Costs(c.providerCode(),c.model(),c.estimatedCost(),c.actualCost(),c.currency(),c.billingStatus(),c.submittedAt(),c.finishedAt(),Instant.now(),Instant.now().plusSeconds(15),c.retryCount());var value=new ProposalImageTask(t.id(),t.poolId(),t.ownerId(),t.draftVersion(),t.combinationId(),t.inputHash(),t.inputs(),t.requestKey(),state,providerId,results,error,review,t.rowVersion()+1,c);if(!repository.update(value,t.rowVersion()))throw new ServiceException("任务已被其他操作更新，请刷新",409);return value;}
    private FashionImageProviderPort.Capabilities capabilities(){var c=provider.capabilities();return c==null?new FashionImageProviderPort.Capabilities("disabled","",List.of(),false,false,null,"CNY"):c;}
    private String blockedReason(FashionImageProviderPort.Capabilities c){if(!provider.available())return "模特试衣服务尚未接通，可以继续导出商品原图提案";if(c.requiresPublicUrls()&&!storage.supportsTemporaryReadUrl())return "试衣素材临时访问地址尚未配置";if(c.estimatedUnitCost()==null||c.estimatedUnitCost().signum()<0||!"CNY".equals(c.currency()))return "本次费用估计或人民币计费口径未确认，暂不提交付费试衣";return null;}
    private BigDecimal monthlyBudget(){if(config==null)return BigDecimal.ZERO;try{return new BigDecimal(config.selectConfigByKey("fashion.ai.monthlyBudgetCny"));}catch(RuntimeException ex){return BigDecimal.ZERO;}}
    private void reserve(ProposalImageTask task){Runnable action=()->{if(task.costs().estimatedCost().signum()>0&&repository.hasUnpricedAcceptedTasks())throw new ServiceException("存量已受理任务费用未记录，请先人工对账，暂不允许新增付费试衣");BigDecimal used=repository.monthlyUsage(task.costs().currency());if(used==null)used=BigDecimal.ZERO;if(used.add(task.costs().estimatedCost()).compareTo(monthlyBudget())>0)throw new ServiceException("AI月度预算不足；未知或待对账任务仍保留预算预留");repository.insert(task);};if(transaction==null){action.run();}else transaction.executeWithoutResult(s->lock.executeLocked(10,()->{action.run();return true;}));}
    private static void validatePositions(Map<String,String> positions,FashionImageProviderPort.Capabilities c){if(positions.isEmpty())throw new ServiceException("请明确选择上装、下装、连体服或仅用原图");for(String p:positions.values())if(!"original".equals(p)&&!c.supportedPositions().contains(p))throw new ServiceException("试衣服务不支持所选服装位置："+p);long tops=positions.values().stream().filter("top"::equals).count(),bottoms=positions.values().stream().filter("bottom"::equals).count(),dresses=positions.values().stream().filter("dress"::equals).count();if(tops>1||bottoms>1||dresses>1||dresses>0&&(tops>0||bottoms>0)||tops+bottoms+dresses==0)throw new ServiceException("一次试衣最多一件上装及一件下装，或一件连体服；鞋帽仅用原图");}
    private static boolean expired(ProposalImageTask task){return task.costs().submittedAt()!=null&&task.costs().submittedAt().isBefore(Instant.now().minusSeconds(86400));}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=15000)
    public void recoverPending(){if(!provider.available())return;for(var task:repository.pending(30)){try{if(task.costs().submittedAt()!=null&&task.costs().submittedAt().isBefore(Instant.now().minusSeconds(86400))){var c=task.costs();var expired=new ProposalImageTask(task.id(),task.poolId(),task.ownerId(),task.draftVersion(),task.combinationId(),task.inputHash(),task.inputs(),task.requestKey(),task.status(),task.providerTaskId(),task.results(),task.errorMessage(),task.review(),task.rowVersion(),new ProposalImageTask.Costs(c.providerCode(),c.model(),c.estimatedCost(),c.actualCost(),c.currency(),"unknown",c.submittedAt(),c.finishedAt(),c.cancelRequestedAt(),null,8));save(expired,"unknown",task.providerTaskId(),task.results(),"外部任务超过24小时恢复期限，请人工对账；自动查询已停止",task.review());}else if(task.providerTaskId()!=null)get(task.poolId(),task.ownerId(),task.id());else if("submitting".equals(task.status())&&task.costs().submittedAt()!=null&&task.costs().submittedAt().isBefore(Instant.now().minusSeconds(300)))save(task,"unknown",null,task.results(),"提交状态未确认且没有外部任务ID，请人工对账；禁止重复付费提交",task.review());}catch(RuntimeException failure){var c=task.costs();var failed=new ProposalImageTask(task.id(),task.poolId(),task.ownerId(),task.draftVersion(),task.combinationId(),task.inputHash(),task.inputs(),task.requestKey(),task.status(),task.providerTaskId(),task.results(),task.errorMessage(),task.review(),task.rowVersion(),new ProposalImageTask.Costs(c.providerCode(),c.model(),c.estimatedCost(),c.actualCost(),c.currency(),"unknown",c.submittedAt(),c.finishedAt(),c.cancelRequestedAt(),Instant.now().plusSeconds(30),c.retryCount()+1));try{save(failed,"unknown",task.providerTaskId(),task.results(),"外部状态查询失败；达到查询上限后请人工对账",task.review());}catch(ServiceException conflict){/* CAS竞争保留先完成的真实状态，不重复提交。 */}}}}
}
