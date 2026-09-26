package com.ruoyi.fashion.application.proposal;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.application.pool.FashionPoolService;
import com.ruoyi.fashion.application.pool.PoolModels;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.infrastructure.files.pptx.FashionProposalPptxRenderer;
import com.ruoyi.fashion.infrastructure.files.FashionDeliveryMedia;
import static com.ruoyi.fashion.application.proposal.ProposalModels.*;

@Service @FashionModuleEnabled
public class FashionProposalService {
    private final FashionPoolService pools;
    private final FashionProductRepository products;
    private final FashionProposalPptxRenderer renderer;
    private final FashionDeliveryMedia media;
    private final ProposalImageService images;
    public FashionProposalService(FashionPoolService pools,FashionProductRepository products,
            FashionProposalPptxRenderer renderer,FashionDeliveryMedia media,ProposalImageService images) {
        this.pools=pools;this.products=products;this.renderer=renderer;this.media=media;this.images=images;
    }
    public Preflight preflight(String poolId,long owner,ExportRequest request) {
        // 权限与版本异常必须是请求失败，不能泄漏为预检消息。
        var selected=pools.requireExport(poolId,owner,request.rowVersion());
        try { var snapshot=snapshot(selected,owner,request);return new Preflight(true,List.of(),snapshot.sets().size(),100); }
        catch(ServiceException ex) { return new Preflight(false,List.of(ex.getMessage()),0,100); }
    }
    public byte[] export(String poolId,long owner,ExportRequest request) {
        var selected=pools.requireExport(poolId,owner,request.rowVersion());
        return renderer.render(snapshot(selected,owner,request));
    }
    private Snapshot snapshot(PoolModels.ExportSelection selected,long owner,ExportRequest request) {
        String mode=request.priceMode()==null?"none":request.priceMode();
        if(!java.util.Set.of("none","group","vip").contains(mode)) throw new ServiceException("价格类型无效");
        String imageMode=request.imageMode()==null?"original":request.imageMode();
        if(!java.util.Set.of("original","model").contains(imageMode)) throw new ServiceException("图片模式无效");
        var combinations=selected.combinations();
        if(request.combinationIds()!=null && !request.combinationIds().isEmpty()) {
            var ids=new LinkedHashSet<>(request.combinationIds());
            if(ids.size()!=request.combinationIds().size()) throw new ServiceException("导出范围有重复方案");
            var selectedIds=new HashSet<>(combinations.stream().map(PoolModels.Combination::id).toList());
            if(!selectedIds.containsAll(ids)) throw new ServiceException("只能导出当前已选方案",409);
            combinations=combinations.stream().filter(c->ids.contains(c.id())).toList();
        }
        if(combinations.isEmpty() || combinations.size()>100) throw new ServiceException("每个 PPT 文件请选择 1～100 套方案，请分批导出");
        List<ProposalModels.Set> sets=new ArrayList<>();
        long imageBytes=0;
        for(var combination:combinations) {
            var items=new ArrayList<Item>();
            var choices=selected.selection().variantSelections().getOrDefault(combination.id(),Map.of());
            for(var main:combination.items()) {
                String choice=choices.get(main.key());
                var variant=choice==null?main.variants().get(0):main.variants().stream().filter(v->v.productId().equals(choice)).findFirst().orElseThrow(()->new ServiceException("颜色选项不属于方案商品",409));
                var current=products.findById(Long.parseLong(variant.productId())).orElseThrow(()->new ServiceException("方案商品已不存在",409));
                // 固定池图片快照，同时检查当前素材许可是否被撤销。
                var image=variant.images().stream().filter(i->Objects.equals(i.objectKey(),variant.mainImageKey()) && "active".equals(i.status()) && i.allowProposal()).findFirst().orElseThrow(()->new ServiceException(main.name()+"缺少已授权的提案主图"));
                boolean stillAllowed=current.images().stream().anyMatch(i->Objects.equals(i.objectKey(),image.objectKey()) && Objects.equals(i.sha256(),image.sha256()) && "active".equals(i.status()) && i.allowProposal());
                if(!stillAllowed) throw new ServiceException(main.name()+"的素材授权已变更，请重新选择图片",409);
                var ref=new Image(image.objectKey(),image.sha256());imageBytes+=media.readVerified(ref.objectKey(),ref.sha256()).length;
                if(imageBytes>50L*1024*1024) throw new ServiceException("本批图片总量超过 50MB，请减少方案数量后分批导出");
                var price="group".equals(mode)?variant.groupPrice():"vip".equals(mode)?variant.vipPrice():null;
                if(!"none".equals(mode) && price==null) throw new ServiceException(main.name()+"缺少所选价格，请补齐或选择不显示价格");
                items.add(new Item(main.name(),main.mainSkuCode(),main.itemNo(),main.articleNo(),variant.colorName(),price,ref));
            }
            Image model=null;
            if("model".equals(imageMode)) {
                String taskId=request.adoptedTaskIds()==null?null:request.adoptedTaskIds().get(combination.id());
                model=images.adoptedImage(selected.pool().id(),owner,taskId,selected.selection().rowVersion(),combination.id());
                imageBytes+=media.readVerified(model.objectKey(),model.sha256()).length;
                if(imageBytes>50L*1024*1024) throw new ServiceException("本批图片总量超过 50MB，请减少方案数量后分批导出");
            }
            sets.add(new ProposalModels.Set(combination.id(),selected.selection().names().getOrDefault(combination.id(),"套装方案 "+(sets.size()+1)),items,model,combination.currency(),combination.taxMode()));
        }
        return new Snapshot(selected.pool().id(),selected.selection().rowVersion(),selected.pool().title(),selected.pool().createdAt(),mode,sets);
    }
}
