package com.ruoyi.fashion.application.proposal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.fashion.application.proposal.ProposalModels.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.pool.FashionPoolService;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.domain.product.FashionProduct;
import com.ruoyi.fashion.domain.product.FashionProductImage;

class FashionProposalDeliveryServiceTest {
    final ProposalDeliveryRepository repository=mock(ProposalDeliveryRepository.class);
    final FashionPoolService pools=mock(FashionPoolService.class);
    final FashionProposalService proposals=mock(FashionProposalService.class);
    final FashionObjectStoragePort storage=mock(FashionObjectStoragePort.class);
    final FashionProductRepository products=mock(FashionProductRepository.class);
    final ProposalImageService images=mock(ProposalImageService.class);
    final ObjectMapper json=new ObjectMapper();
    final FashionProposalDeliveryService service=new FashionProposalDeliveryService(repository,pools,proposals,storage,products,images,json);
    final AtomicReference<ProposalDeliveryRepository.Entry> saved=new AtomicReference<>();
    final Image source=new Image("product.png","source-hash");
    final Image model=new Image("model.png","model-hash");
    byte[] content;
    FashionProposalDeliveryService.Create request(boolean useModel){return new FashionProposalDeliveryService.Create("pool","客户提案","request-key",new ExportRequest(2,List.of("combo"),"none",useModel?"model":"original",useModel?Map.of("combo","task"):Map.of()));}
    void fixture(boolean useModel) throws Exception {
        try(var ppt=new org.apache.poi.xslf.usermodel.XMLSlideShow();var output=new ByteArrayOutputStream()){ppt.createSlide();ppt.createSlide();ppt.write(output);content=output.toByteArray();}
        var item=new Item("上装","MAIN","品号","货号","蓝",null,source,"42");
        var snapshot=new Snapshot("pool",2,"方案","2026-10-01T00:00:00Z","none",List.of(new ProposalModels.Set("combo","搭配",List.of(item),useModel?model:null,"CNY","included")));
        when(proposals.generate(eq("pool"),eq(7L),any())).thenReturn(new FashionProposalService.Generated(snapshot,content));
        doAnswer(call->{saved.set(call.getArgument(0));return null;}).when(repository).insert(any());
        when(repository.finish(any())).thenAnswer(call->{saved.set(call.getArgument(0));return true;});
        when(repository.request(any(),eq(7L))).thenAnswer(call->Optional.ofNullable(saved.get()));
        when(repository.find(any(),eq(7L))).thenAnswer(call->Optional.ofNullable(saved.get()));
        when(storage.read(any())).thenReturn(content);when(repository.incrementDownload(any(),eq(7L))).thenReturn(true);
        var image=new FashionProductImage("i",source.objectKey(),source.sha256(),"main","upload",null,null,null,true,true,true,false,"active",7,null,200,200,"p.png");
        var product=mock(FashionProduct.class);when(product.images()).thenReturn(List.of(image));when(products.findById(42)).thenReturn(Optional.of(product));
        when(images.frozenAdoptedImage("pool",7,"task",2,"combo")).thenReturn(model);
    }
    @Test void countsActualSlidesSeparatelyFromFrozenCombinations() throws Exception {
        fixture(false);var result=service.create(7,request(false));assertThat(result.get("status")).isEqualTo("success");assertThat(result.get("pageCount")).isEqualTo(2);assertThat(result.get("combinationCount")).isEqualTo(1);
        assertThat(saved.get().fileSize()).isEqualTo((long)content.length);assertThat(saved.get().snapshot().path("snapshot").path("sets").size()).isEqualTo(1);
    }
    @Test void failedGenerationIsRecordedWithoutFakeFileAndSameKeyDoesNotRegenerate() throws Exception {
        fixture(false);when(proposals.generate(any(),anyLong(),any())).thenThrow(new ServiceException("素材许可已撤销"));assertThat(service.create(7,request(false)).get("status")).isEqualTo("failed");assertThat(saved.get().fileKey()).isNull();assertThat(saved.get().fileHash()).isNull();
        assertThat(service.create(7,request(false)).get("status")).isEqualTo("failed");verify(proposals,times(1)).generate(any(),anyLong(),any());verify(storage,never()).putIfAbsent(any(),any(),any());
    }
    @Test void sameRequestReturnsFrozenRecordAfterCurrentDraftChangesAndDifferentInputConflicts() throws Exception {
        fixture(false);service.create(7,request(false));clearInvocations(pools);when(pools.requireExport(any(),anyLong(),anyLong())).thenThrow(new ServiceException("草稿已变更"));
        assertThat(service.create(7,request(false)).get("status")).isEqualTo("success");verifyNoInteractions(pools);
        var changed=new FashionProposalDeliveryService.Create("pool","不同标题","request-key",request(false).export());assertThatThrownBy(()->service.create(7,changed)).hasMessageContaining("其他提案输入");
    }
    @Test void validatedFileDownloadCountsAndCorruptionDoesNotCount() throws Exception {
        fixture(false);service.create(7,request(false));assertThat(service.download(saved.get().id(),7).bytes()).isEqualTo(content);verify(repository).incrementDownload(saved.get().id(),7);
        clearInvocations(repository);when(storage.read(any())).thenReturn(new byte[]{1,2,3});assertThatThrownBy(()->service.download(saved.get().id(),7)).hasMessageContaining("摘要或大小");verify(repository,never()).incrementDownload(any(),anyLong());
    }
    @Test void revokedProductPermissionBlocksFrozenDownloadBeforeReadingFile() throws Exception {
        fixture(false);service.create(7,request(false));when(products.findById(42).orElseThrow().images()).thenReturn(List.of());assertThatThrownBy(()->service.download(saved.get().id(),7)).hasMessageContaining("授权已撤销");verify(storage,never()).read(any());verify(repository,never()).incrementDownload(any(),anyLong());
    }
    @Test void revokedAdoptionOrChangedResultBlocksFrozenModelDownload() throws Exception {
        fixture(true);service.create(7,request(true));when(images.frozenAdoptedImage("pool",7,"task",2,"combo")).thenThrow(new ServiceException("采用已撤回"));assertThatThrownBy(()->service.download(saved.get().id(),7)).hasMessageContaining("撤回");
        doReturn(new Image("changed","hash")).when(images).frozenAdoptedImage("pool",7,"task",2,"combo");assertThatThrownBy(()->service.download(saved.get().id(),7)).hasMessageContaining("采用已更改");verify(repository,never()).incrementDownload(any(),anyLong());
    }
}
