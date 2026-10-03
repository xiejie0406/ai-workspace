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
        when(provider.capabilities()).thenReturn(new FashionImageProviderPort.Capabilities("test","test",List.of("top","bottom","dress"),false,false,java.math.BigDecimal.ZERO,"CNY"));
        var img=new FashionProductImage("i","product.png","hash","main","upload",null,null,null,true,true,true,false,"active",7,null,200,200,"p.png");
        var variant=new PoolModels.Variant("42","sku","blue","藏蓝","M",null,null,null,null,"product.png",List.of(img),3);
        var main=mock(PoolModels.MainSku.class);when(main.key()).thenReturn("main");when(main.mainSkuCode()).thenReturn("MAIN");when(main.name()).thenReturn("外套");when(main.variants()).thenReturn(List.of(variant));
        var combo=mock(PoolModels.Combination.class);when(combo.id()).thenReturn("combo");when(combo.items()).thenReturn(List.of(main));
        var selection=new PoolModels.Selection("pool",2,false,List.of("combo"),List.of(),Map.of(),List.of("combo"),Map.of("combo",Map.of("main","42")));
        when(pools.requireExport("pool",7,2)).thenReturn(new PoolModels.ExportSelection(null,selection,List.of(combo)));
        var product=mock(FashionProduct.class);when(product.images()).thenReturn(List.of(img));when(product.visualVersion()).thenReturn(3L);when(products.findById(42)).thenReturn(Optional.of(product));
        when(repo.request(anyString(),eq(7L))).thenReturn(Optional.empty());
    }
    TryOnRequest request(){String hash="a".repeat(64);return new TryOnRequest(2,"combo",new Image("proposal-models/7/"+hash+".png",hash),"request-key-1",Map.of("main","top"),java.math.BigDecimal.ZERO,true);}
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
    @Test void acceptedExternalIdSurvivesInvalidResultAndSameRequestDoesNotSubmitAgain(){
        fixture();when(provider.available()).thenReturn(true);when(repo.update(any(),anyLong())).thenReturn(true);
        when(provider.submit(any(),any(),any(),any(),anyInt())).thenReturn(new FashionImageProviderPort.ProviderResult("success","test","accepted-id",mapper.createArrayNode(),null,"CNY",null,null,false));
        var output=service.create("pool",7,request());assertThat(output.get("status")).isEqualTo("unknown");
        var captured=org.mockito.ArgumentCaptor.forClass(ProposalImageTask.class);verify(repo,times(2)).update(captured.capture(),anyLong());
        assertThat(captured.getAllValues()).extracting(ProposalImageTask::providerTaskId).containsExactly("accepted-id","accepted-id");
        when(repo.request(request().requestKey(),7)).thenReturn(Optional.of(captured.getValue()));
        assertThat(service.create("pool",7,request()).get("status")).isEqualTo("unknown");
        verify(provider,times(1)).submit(any(),any(),any(),any(),anyInt());
    }
    @Test void successfulProviderResultDoesNotInventActualCost(){
        fixture();when(provider.available()).thenReturn(true);when(repo.update(any(),anyLong())).thenReturn(true);
        var results=mapper.createArrayNode();results.addObject().put("status","success").put("object_key","result.png").put("sha256","hash");
        when(provider.submit(any(),any(),any(),any(),anyInt())).thenReturn(new FashionImageProviderPort.ProviderResult("success","test","external",results,null,"CNY",null,null,false));
        var output=service.create("pool",7,request());assertThat(output.get("actualCost")).isNull();assertThat(output.get("billingStatus")).isEqualTo("unknown");
    }
    @Test void expiredExternalTaskStopsAutomaticAndManualRefreshQueries(){
        fixture();when(provider.available()).thenReturn(true);when(repo.update(any(),anyLong())).thenReturn(true);
        var task=recoveryTask("running","external",java.time.Instant.now().minusSeconds(86401));when(repo.pending(30)).thenReturn(List.of(task));when(repo.find("task",7)).thenReturn(Optional.of(task));
        service.recoverPending();verify(repo).update(argThat(t->t.status().equals("unknown")&&t.costs().retryCount()==8&&t.costs().nextQueryAt()==null),eq(1L));
        service.get("pool",7,"task");verify(provider,never()).query(any());verify(provider,never()).submit(any(),any(),any(),any(),anyInt());
    }
    @Test void interruptedSubmissionWithoutExternalIdNeedsManualReconciliation(){
        fixture();when(provider.available()).thenReturn(true);when(repo.update(any(),anyLong())).thenReturn(true);
        var task=recoveryTask("submitting",null,java.time.Instant.now().minusSeconds(301));when(repo.pending(30)).thenReturn(List.of(task));
        service.recoverPending();verify(repo).update(argThat(t->t.status().equals("unknown")&&t.providerTaskId()==null&&t.costs().nextQueryAt()==null),eq(1L));
        verify(provider,never()).query(any());verify(provider,never()).submit(any(),any(),any(),any(),anyInt());
    }
    @Test void explicitCostConsentIsRequiredBeforeBudgetReservation(){
        fixture();when(provider.available()).thenReturn(true);var request=request();var noConsent=new TryOnRequest(request.rowVersion(),request.combinationId(),request.model(),request.requestKey(),request.garmentPositions(),request.maxCost(),false);
        assertThatThrownBy(()->service.create("pool",7,noConsent)).hasMessageContaining("费用上限");verify(repo,never()).insert(any());verify(provider,never()).submit(any(),any(),any(),any(),anyInt());
    }
    @Test void frozenAdoptedResultSurvivesDraftChangesButCannotSurviveWithdrawal(){
        var results=mapper.createArrayNode();results.addObject().put("status","success").put("object_key","result").put("sha256","hash");var review=mapper.createObjectNode().put("inputHash","frozen-input").put("resultNo",0);
        var adopted=new ProposalImageTask("task","pool",7,2,"combo","frozen-input",mapper.createObjectNode(),"key","adopted","external",results,null,review,2);
        when(repo.find("task",7)).thenReturn(Optional.of(adopted));assertThat(service.frozenAdoptedImage("pool",7,"task",2,"combo")).isEqualTo(new Image("result","hash"));verifyNoInteractions(pools);
        var rejected=new ProposalImageTask("task","pool",7,2,"combo","frozen-input",mapper.createObjectNode(),"key","rejected","external",results,null,review,3);when(repo.find("task",7)).thenReturn(Optional.of(rejected));
        assertThatThrownBy(()->service.frozenAdoptedImage("pool",7,"task",2,"combo")).hasMessageContaining("撤回");
    }
    @Test void acceptedLegacyTaskWithUnrecordedCostBlocksNewPaidSubmission(){
        fixture();when(provider.available()).thenReturn(true);when(provider.capabilities()).thenReturn(new FashionImageProviderPort.Capabilities("test","test",List.of("top"),false,false,new java.math.BigDecimal("0.50"),"CNY"));when(repo.hasUnpricedAcceptedTasks()).thenReturn(true);
        var old=request();var paid=new TryOnRequest(old.rowVersion(),old.combinationId(),old.model(),old.requestKey(),old.garmentPositions(),new java.math.BigDecimal("0.50"),true);
        assertThatThrownBy(()->service.create("pool",7,paid)).hasMessageContaining("存量已受理任务费用未记录");verify(repo,never()).insert(any());verify(provider,never()).submit(any(),any(),any(),any(),anyInt());
    }
    @Test void revokedAiSourcePermissionBlocksFrozenAdoptedImage(){
        fixture();var inputs=mapper.createObjectNode();inputs.putArray("products").addObject().put("productId","42").put("garment_position","top").put("object_key","product.png").put("sha256","hash");var results=mapper.createArrayNode();results.addObject().put("status","success").put("object_key","result").put("sha256","result-hash");var review=mapper.createObjectNode().put("inputHash","frozen").put("resultNo",0);
        when(repo.find("task",7)).thenReturn(Optional.of(new ProposalImageTask("task","pool",7,2,"combo","frozen",inputs,"key","adopted","external",results,null,review,2)));
        assertThat(service.frozenAdoptedImage("pool",7,"task",2,"combo")).isEqualTo(new Image("result","result-hash"));when(products.findById(42).orElseThrow().images()).thenReturn(List.of());assertThatThrownBy(()->service.frozenAdoptedImage("pool",7,"task",2,"combo")).hasMessageContaining("AI许可已撤销");
    }
    private ProposalImageTask recoveryTask(String state,String external,java.time.Instant submitted){return new ProposalImageTask("task","pool",7,2,"combo","hash",mapper.createObjectNode(),"key",state,external,mapper.createArrayNode(),null,mapper.createObjectNode(),1,new ProposalImageTask.Costs("test","test",java.math.BigDecimal.ZERO,null,"CNY","unknown",submitted,null,null,null,0));}
}
