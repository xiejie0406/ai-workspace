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
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.application.image.port.FashionImageProviderPort;
import com.ruoyi.fashion.infrastructure.files.FashionDeliveryMedia;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import com.ruoyi.fashion.infrastructure.storage.FashionUploadPolicy;
import static com.ruoyi.fashion.application.proposal.ProposalModels.*;

/** 草稿试衣独立于正式报价；无 Provider 时明确拒绝，不制造成功图。 */
@Service @FashionModuleEnabled
public class ProposalImageService {
    private final FashionPoolService pools; private final FashionProductRepository products;
    private final ProposalImageRepository repository; private final FashionImageProviderPort provider;
    private final FashionObjectStoragePort storage; private final FashionDeliveryMedia media; private final ObjectMapper mapper;
    public ProposalImageService(FashionPoolService pools,FashionProductRepository products,ProposalImageRepository repository,
            FashionImageProviderPort provider,FashionObjectStoragePort storage,FashionDeliveryMedia media,ObjectMapper mapper){
        this.pools=pools;this.products=products;this.repository=repository;this.provider=provider;this.storage=storage;this.media=media;this.mapper=mapper;
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
        return Map.of("providerAvailable",provider.available(),"blockedReason",provider.available()?"":"模特试衣服务尚未接通，可以继续导出商品原图提案", "tasks",repository.list(pool,owner).stream().map(this::view).toList());
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
        var task=new ProposalImageTask(UUID.randomUUID().toString(),pool,owner,request.rowVersion(),request.combinationId(),hash,inputs,request.requestKey(),"submitting",null,mapper.createArrayNode(),null,mapper.createObjectNode(),1);
        try { repository.insert(task); }
        catch(org.springframework.dao.DuplicateKeyException ex){return view(repository.request(request.requestKey(),owner).filter(t->t.inputHash().equals(hash)).orElseThrow(()->new ServiceException("重复请求输入冲突",409)));}
        try { task=complete(task,provider.submit(task.id(),"model",inputs,mapper.createObjectNode(),1)); }
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
        var array=root.putArray("products");var choices=selected.selection().variantSelections().getOrDefault(combo.id(),Map.of());
        for(var main:combo.items()) {
            String id=choices.get(main.key());
            // 颜色必须明确，禁止悄悄选取任一颜色用于试衣。
            if(id==null) throw new ServiceException("请先为 "+main.name()+" 指定试衣颜色");
            var variant=main.variants().stream().filter(v->v.productId().equals(id)).findFirst().orElseThrow(()->new ServiceException("试衣颜色不属于当前方案"));
            var current=products.findById(Long.parseLong(id)).orElseThrow(()->new ServiceException("试衣商品已不存在"));
            if(current.visualVersion()!=variant.visualVersion()) throw new ServiceException("商品图片版本已更新，请重新筛选并确认试衣输入",409);
            var image=variant.images().stream().filter(i->Objects.equals(i.objectKey(),variant.mainImageKey())&&i.allowAi()&&"active".equals(i.status())).findFirst().orElseThrow(()->new ServiceException(main.name()+"没有允许试衣的颜色图片"));
            if(current.images().stream().noneMatch(i->i.allowAi()&&"active".equals(i.status())&&Objects.equals(i.objectKey(),image.objectKey())&&Objects.equals(i.sha256(),image.sha256()))) throw new ServiceException("商品图片授权已变更",409);
            media.readVerified(image.objectKey(),image.sha256());
            var node=array.addObject();node.put("mainSku",main.mainSkuCode());node.put("productId",id);node.put("color",variant.colorName());node.put("object_key",image.objectKey());node.put("sha256",image.sha256());node.put("visualVersion",variant.visualVersion());
        }
        return root;
    }
    public Map<String,Object> get(String pool,long owner,String id) {
        var task=require(pool,owner,id);
        if(java.util.Set.of("running","queued","unknown","cancel_requested").contains(task.status()) && task.providerTaskId()!=null && provider.available()) {
            try { task=complete(task,provider.query(task.providerTaskId())); }
            catch(ServiceException ex){throw ex;}
            catch(Exception ex){throw new ServiceException("试衣状态查询失败，可稍后重试",503);}
        }
        return view(task);
    }
    public Map<String,Object> cancel(String pool,long owner,String id,long version) {
        var task=require(pool,owner,id);checkVersion(task,version);
        if(!java.util.Set.of("queued","running","unknown").contains(task.status())) throw new ServiceException("当前状态不能取消",409);
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
        var review=mapper.createObjectNode();review.put("resultNo",request.resultNo());review.put("adopted",request.adopt());review.put("inputHash",task.inputHash());review.put("reason",request.reason()==null?"":request.reason());review.set("checks",mapper.valueToTree(request.checks()));
        if(request.adopt()) media.readVerified(result.path("object_key").asText(),result.path("sha256").asText());
        return view(save(task,request.adopt()?"adopted":"rejected",task.providerTaskId(),task.results(),null,review));
    }
    public Image adoptedImage(String pool,long owner,String id,long version,String combo) {
        if(id==null) throw new ServiceException("请选择已采用的模特图");
        var task=require(pool,owner,id);
        if(task.draftVersion()!=version || !task.combinationId().equals(combo) || stale(task) || !"adopted".equals(task.status()) || !task.inputHash().equals(task.review().path("inputHash").asText())) throw new ServiceException("模特图未采用或已过期",409);
        var result=result(task,task.review().path("resultNo").asInt());return new Image(result.path("object_key").asText(),result.path("sha256").asText());
    }
    public byte[] resultContent(String pool,long owner,String id,int resultNo) {var task=require(pool,owner,id);var result=result(task,resultNo);return media.readVerified(result.path("object_key").asText(),result.path("sha256").asText());}
    private JsonNode result(ProposalImageTask task,int no) {if(no<0||no>=task.results().size())throw new ServiceException("试衣结果不存在");var result=task.results().get(no);if(!"success".equals(result.path("status").asText()))throw new ServiceException("结果尚未成功");return result;}
    private ProposalImageTask require(String pool,long owner,String id){if(id==null)throw new ServiceException("任务不存在",404);return repository.find(id,owner).filter(t->t.poolId().equals(pool)).orElseThrow(()->new ServiceException("任务不存在或无权访问",404));}
    private void checkVersion(ProposalImageTask task,long version){if(task.rowVersion()!=version)throw new ServiceException("任务已变更，请刷新",409);}
    private boolean stale(ProposalImageTask task) {
        try {var model=mapper.treeToValue(task.inputs().path("model"),Image.class);var current=inputs(task.poolId(),task.ownerId(),new TryOnRequest(task.draftVersion(),task.combinationId(),model,task.requestKey()));return !FashionHashing.sha256(mapper.writeValueAsString(current).getBytes(StandardCharsets.UTF_8)).equals(task.inputHash());}
        catch(Exception ex){return true;}
    }
    private Map<String,Object> view(ProposalImageTask task){var map=new LinkedHashMap<String,Object>();map.put("id",task.id());map.put("status",task.status());map.put("rowVersion",task.rowVersion());map.put("draftVersion",task.draftVersion());map.put("combinationId",task.combinationId());map.put("inputHash",task.inputHash());map.put("results",task.results());map.put("errorMessage",task.errorMessage());map.put("review",task.review());map.put("stale",stale(task));return map;}
    private ProposalImageTask complete(ProposalImageTask task,FashionImageProviderPort.ProviderResult response){
        var results=response.results()==null?mapper.createArrayNode():response.results();
        String state=switch(response.status()){case "success","succeeded","completed","partial_success"->"review";case "failed"->"failed";case "cancelled"->"cancelled";case "queued"->"queued";default->"running";};
        if("review".equals(state)) {if(!results.isArray()||results.isEmpty())throw new ServiceException("生成服务未返回图片");for(var result:results)if("success".equals(result.path("status").asText()))media.readVerified(result.path("object_key").asText(),result.path("sha256").asText());}
        return save(task,state,response.providerTaskId(),results,response.errorMessage(),task.review());
    }
    private ProposalImageTask save(ProposalImageTask t,String state,String providerId,JsonNode results,String error,JsonNode review){var value=new ProposalImageTask(t.id(),t.poolId(),t.ownerId(),t.draftVersion(),t.combinationId(),t.inputHash(),t.inputs(),t.requestKey(),state,providerId,results,error,review,t.rowVersion()+1);if(!repository.update(value,t.rowVersion()))throw new ServiceException("任务已被其他操作更新，请刷新",409);return value;}
}
