package com.ruoyi.fashion.application.proposal;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.fashion.application.pool.*;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.application.image.port.FashionImageProviderPort;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.domain.product.*;
import com.ruoyi.fashion.infrastructure.files.FashionDeliveryMedia;
import static com.ruoyi.fashion.application.proposal.ProposalModels.*;

class ProposalImageServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final FashionPoolService pools=mock(FashionPoolService.class);
    final FashionProductRepository products=mock(FashionProductRepository.class);
    final ProposalImageRepository repo=mock(ProposalImageRepository.class);
    final FashionImageProviderPort provider=mock(FashionImageProviderPort.class);
    final FashionObjectStoragePort storage=mock(FashionObjectStoragePort.class);
    final FashionDeliveryMedia media=mock(FashionDeliveryMedia.class);
    final ProposalImageService service=new ProposalImageService(pools,products,repo,provider,storage,media,mapper);
    void fixture(){
        var img=new FashionProductImage("i","product.png","hash","main","upload",null,null,null,true,true,true,false,"active",7,null,200,200,"p.png");
        var variant=new PoolModels.Variant("42","sku","blue","藏蓝","M",null,null,null,null,"product.png",List.of(img),3);
        var main=mock(PoolModels.MainSku.class);when(main.key()).thenReturn("main");when(main.mainSkuCode()).thenReturn("MAIN");when(main.name()).thenReturn("外套");when(main.variants()).thenReturn(List.of(variant));
        var combo=mock(PoolModels.Combination.class);when(combo.id()).thenReturn("combo");when(combo.items()).thenReturn(List.of(main));
        var selection=new PoolModels.Selection("pool",2,false,List.of("combo"),List.of(),Map.of(),List.of("combo"),Map.of("combo",Map.of("main","42")));
        when(pools.requireExport("pool",7,2)).thenReturn(new PoolModels.ExportSelection(null,selection,List.of(combo)));
        var product=mock(FashionProduct.class);when(product.images()).thenReturn(List.of(img));when(product.visualVersion()).thenReturn(3L);when(products.findById(42)).thenReturn(Optional.of(product));
        when(repo.request(anyString(),eq(7L))).thenReturn(Optional.empty());
    }
    TryOnRequest request(){String hash="a".repeat(64);return new TryOnRequest(2,"combo",new Image("proposal-models/7/"+hash+".png",hash),"request-key-1");}
    @Test void disabledProviderRejectsWithoutCreatingFakeTask(){fixture();when(provider.available()).thenReturn(false);assertThatThrownBy(()->service.create("pool",7,request())).hasMessageContaining("尚未接通");verify(repo,never()).insert(any());verify(provider,never()).submit(any(),any(),any(),any(),anyInt());}
    @Test void refusesAnotherOwnersModelBeforeProviderCall(){fixture();assertThatThrownBy(()->service.create("pool",7,new TryOnRequest(2,"combo",new Image("proposal-models/8/private.png","x"),"request-key-1"))).hasMessageContaining("当前账号");verifyNoInteractions(provider);}
    @Test void createsRealProviderTaskAndPersistsReviewableResult(){
        fixture();when(provider.available()).thenReturn(true);when(repo.update(any(),anyLong())).thenReturn(true);
        var results=mapper.createArrayNode();results.addObject().put("status","success").put("object_key","result.png").put("sha256","result-hash");
        when(provider.submit(anyString(),eq("model"),any(),any(),eq(1))).thenReturn(new FashionImageProviderPort.ProviderResult("success","test","external-id",results,null,"CNY",null,null,false));
        var out=service.create("pool",7,request());assertThat(out.get("status")).isEqualTo("review");assertThat(out.get("stale")).isEqualTo(false);verify(repo).insert(argThat(t->t.draftVersion()==2&&t.status().equals("submitting")));verify(media).readVerified("result.png","result-hash");
    }
    @Test void changedProductVisualVersionPreventsStaleTryOn(){
        fixture();when(products.findById(42).orElseThrow().visualVersion()).thenReturn(4L);
        assertThatThrownBy(()->service.create("pool",7,request())).hasMessageContaining("图片版本已更新");
        verify(repo,never()).insert(any());verifyNoInteractions(provider);
    }
    @Test void cancellationRequestCanReachProviderConfirmedCancellation(){
        fixture();when(provider.available()).thenReturn(true);when(repo.update(any(),eq(2L))).thenReturn(true);
        var inputs=mapper.createObjectNode();inputs.set("model",mapper.valueToTree(request().model()));
        var task=new ProposalImageTask("task","pool",7,2,"combo","hash",inputs,"key","cancel_requested","external",mapper.createArrayNode(),null,mapper.createObjectNode(),2);
        when(repo.find("task",7)).thenReturn(Optional.of(task));
        when(provider.query("external")).thenReturn(new FashionImageProviderPort.ProviderResult("cancelled","test","external",mapper.createArrayNode(),null,null,null,null,false));
        assertThat(service.get("pool",7,"task").get("status")).isEqualTo("cancelled");
        verify(repo).update(argThat(t->t.status().equals("cancelled")),eq(2L));
    }
}
