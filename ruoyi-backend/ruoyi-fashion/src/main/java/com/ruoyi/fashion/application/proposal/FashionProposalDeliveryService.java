package com.ruoyi.fashion.application.proposal;
import java.util.*;
import java.time.Instant;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.pool.FashionPoolService;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import static com.ruoyi.fashion.application.proposal.ProposalDeliveryRepository.*;
import static com.ruoyi.fashion.application.proposal.ProposalModels.*;

/** 同步生成的文件交付记录；无队列和第二套方案状态。 */
@Service @FashionModuleEnabled
public class FashionProposalDeliveryService {
    public record Create(String poolId,String title,String requestKey,ExportRequest export){}
    public record Download(String title,byte[] bytes){}
    private final ProposalDeliveryRepository repository;private final FashionPoolService pools;private final FashionProposalService proposals;
    private final FashionObjectStoragePort storage;private final FashionProductRepository products;private final ProposalImageService images;private final ObjectMapper json;
    public FashionProposalDeliveryService(ProposalDeliveryRepository repository,FashionPoolService pools,FashionProposalService proposals,FashionObjectStoragePort storage,FashionProductRepository products,ProposalImageService images,ObjectMapper json){this.repository=repository;this.pools=pools;this.proposals=proposals;this.storage=storage;this.products=products;this.images=images;this.json=json;}
    public Map<String,Object> list(long owner,int page,int pageSize){if(page<1||pageSize<1||pageSize>100)throw new ServiceException("交付分页参数无效");var entries=repository.list(owner,page,pageSize);var contexts=repository.listContext(entries.stream().map(Entry::id).collect(java.util.stream.Collectors.toSet()),owner);return Map.of("items",entries.stream().map(e->{var row=view(e);row.putAll(contexts.getOrDefault(e.id(),Map.of()));return row;}).toList(),"total",repository.count(owner),"page",page,"pageSize",pageSize);}
    public Map<String,Object> create(long owner,Create request){
        if(request==null||request.export()==null||request.poolId()==null||request.requestKey()==null||!request.requestKey().matches("[A-Za-z0-9_-]{8,100}")||request.title()==null||request.title().isBlank()||request.title().length()>200||request.title().chars().anyMatch(Character::isISOControl))throw new ServiceException("提案标题、请求编号或导出配置无效");
        String requestHash=FashionHashing.sha256(json.writer().with(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(request));
        var prior=repository.request(request.requestKey(),owner);if(prior.isPresent())return sameRequest(prior.get(),requestHash);
        pools.requireExport(request.poolId(),owner,request.export().rowVersion());
        var state=json.createObjectNode();state.put("requestHash",requestHash);state.set("request",json.valueToTree(request));
        var entry=new Entry(UUID.randomUUID().toString(),owner,request.poolId(),request.export().rowVersion(),request.requestKey(),request.title().trim(),"generating",state,null,null,null,0,0,Instant.now(),null);
        try{repository.insert(entry);}catch(DuplicateKeyException ex){return repository.request(request.requestKey(),owner).map(e->sameRequest(e,requestHash)).orElseThrow(()->new ServiceException("重复生成请求竞争，请重新查询"));}
        Entry finished;
        try{var generated=proposals.generate(request.poolId(),owner,request.export());byte[] content=generated.content();int pages=pages(content);String hash=FashionHashing.sha256(content);String file="proposal-deliveries/"+owner+"/"+entry.id()+"/"+hash+".pptx";storage.putIfAbsent(file,content,"application/vnd.openxmlformats-officedocument.presentationml.presentation");state.set("snapshot",json.valueToTree(generated.snapshot()));finished=new Entry(entry.id(),owner,entry.poolId(),entry.draftVersion(),entry.requestKey(),entry.title(),"success",state,file,hash,(long)content.length,pages,0,entry.createdAt(),null);}
        catch(RuntimeException ex){String message=ex instanceof ServiceException?ex.getMessage():"提案生成或私有文件保存失败，请检查后重新生成";finished=new Entry(entry.id(),owner,entry.poolId(),entry.draftVersion(),entry.requestKey(),entry.title(),"failed",state,null,null,null,0,0,entry.createdAt(),message);}
        if(!repository.finish(finished))throw new ServiceException("生成结果未能写入交付记录，请查询原请求，不要重复生成",409);return view(finished);
    }
    private Map<String,Object> sameRequest(Entry e,String hash){if(!hash.equals(e.snapshot().path("requestHash").asText()))throw new ServiceException("请求编号已用于其他提案输入",409);return view(e);}
    private static int pages(byte[] content){try(var ppt=new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(content))){return ppt.getSlides().size();}catch(java.io.IOException ex){throw new ServiceException("生成文件不是可读取的PPTX");}}
    public Download download(String id,long owner){var entry=repository.find(id,owner).orElseThrow(()->new ServiceException("交付记录不存在或无权访问",404));if(!"success".equals(entry.status())||entry.fileKey()==null||entry.fileHash()==null)throw new ServiceException("该记录没有成功生成的文件");
        var snapshot=json.treeToValue(entry.snapshot().path("snapshot"),Snapshot.class);
        var request=json.treeToValue(entry.snapshot().path("request"),Create.class);
        for(var set:snapshot.sets()){
            if(set.modelImage()!=null){String taskId=request.export().adoptedTaskIds()==null?null:request.export().adoptedTaskIds().get(set.id());var current=images.frozenAdoptedImage(entry.poolId(),owner,taskId,entry.draftVersion(),set.id());if(!Objects.equals(current,set.modelImage()))throw new ServiceException("模特图采用已更改，不能下载原文件",409);}
            for(var item:set.items()){if(item.productId()==null||item.image()==null)throw new ServiceException("提案缺少素材追溯身份，请重新生成");var product=products.findById(Long.parseLong(item.productId())).orElseThrow(()->new ServiceException("提案商品已不存在"));if(product.images().stream().noneMatch(i->"active".equals(i.status())&&i.allowProposal()&&Objects.equals(i.objectKey(),item.image().objectKey())&&Objects.equals(i.sha256(),item.image().sha256())))throw new ServiceException("提案素材授权已撤销，不能下载该文件",409);}
        }
        byte[] bytes=storage.read(entry.fileKey());if(!FashionHashing.sha256(bytes).equals(entry.fileHash())||entry.fileSize()==null||entry.fileSize()!=bytes.length)throw new ServiceException("提案文件摘要或大小不一致，下载已阻止");if(!repository.incrementDownload(id,owner))throw new ServiceException("交付记录状态已变化，请刷新",409);return new Download(entry.title(),bytes);}
    private Map<String,Object> view(Entry e){var row=new LinkedHashMap<String,Object>();row.put("ownerId",Long.toString(e.owner()));row.put("requestKey",e.requestKey());row.put("id",e.id());row.put("title",e.title());row.put("poolId",e.poolId());row.put("draftVersion",e.draftVersion());row.put("status",e.status());row.put("combinationCount",e.snapshot().path("snapshot").path("sets").size());row.put("pageCount",e.pageCount());row.put("fileSize",e.fileSize());row.put("downloadCount",e.downloadCount());row.put("createdAt",e.createdAt().toString());row.put("errorMessage",e.errorMessage());row.put("priceMode",e.snapshot().path("request").path("export").path("priceMode").asText("none"));row.put("imageMode",e.snapshot().path("request").path("export").path("imageMode").asText("original"));return row;}
}
