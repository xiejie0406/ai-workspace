package com.ruoyi.fashion.infrastructure.files.pptx;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.poi.xslf.usermodel.*;
import com.ruoyi.fashion.application.delivery.DeliveryTestFixtures;
import com.ruoyi.fashion.application.proposal.ProposalModels.*;
import com.ruoyi.fashion.infrastructure.files.FashionDeliveryMedia;

class FashionProposalPptxRendererTest {
    @Test void exportsSelectedOrderWithEmbeddedPicturesAndEditableNamesWithoutQuote()throws Exception {
        var media=mock(FashionDeliveryMedia.class);when(media.readVerified("image","hash")).thenReturn(DeliveryTestFixtures.png());
        var renderer=new FashionProposalPptxRenderer(media);var item=new Item("秋季运动外套","MAIN-001","ITEM-01","ARTICLE-01","藏蓝",new BigDecimal("99.50"),new Image("image","hash"));
        var snapshot=new Snapshot("pool",7,"团建选品","2026-09-26","group",List.of(new com.ruoyi.fashion.application.proposal.ProposalModels.Set("b","方案 B",List.of(item),null,"CNY","included"),new com.ruoyi.fashion.application.proposal.ProposalModels.Set("a","方案 A",List.of(item),null,"CNY","included")));
        byte[] bytes=renderer.render(snapshot);var dir=Path.of("target/proposal-validation");Files.createDirectories(dir);Files.write(dir.resolve("选品提案.pptx"),bytes);
        try(var ppt=new XMLSlideShow(new ByteArrayInputStream(bytes))){
            assertThat(ppt.getSlides()).hasSize(2);assertThat(ppt.getPictureData()).isNotEmpty();
            var text=ppt.getSlides().get(0).getShapes().stream().filter(XSLFTextShape.class::isInstance).map(XSLFTextShape.class::cast).map(XSLFTextShape::getText).reduce("",String::concat);
            assertThat(text).contains("方案 B","秋季运动外套","MAIN-001","ITEM-01","ARTICLE-01","99.50","参考价格").doesNotContain("成本","内部备注");
            var editable=(XSLFTextShape)ppt.getSlides().get(0).getShapes().get(0);editable.setText("客户可编辑标题");var saved=new ByteArrayOutputStream();ppt.write(saved);
            try(var reopened=new XMLSlideShow(new ByteArrayInputStream(saved.toByteArray()))){assertThat(((XSLFTextShape)reopened.getSlides().get(0).getShapes().get(0)).getText()).isEqualTo("客户可编辑标题");}
        }
    }
    @Test void refusesMissingImagesAndMissingChosenPricesRatherThanSilentlyDowngrading(){
        var media=mock(FashionDeliveryMedia.class);var renderer=new FashionProposalPptxRenderer(media);
        var noImage=new Item("无图","M",null,null,"黑",null,null);
        var set=new com.ruoyi.fashion.application.proposal.ProposalModels.Set("c","方案",List.of(noImage),null,"CNY","included");
        assertThatThrownBy(()->renderer.render(new Snapshot("p",1,"t","now","none",List.of(set)))).hasMessageContaining("图片");
        when(media.readVerified("i","h")).thenReturn(DeliveryTestFixtures.png());
        var noPrice=new Item("无价","M",null,null,"黑",null,new Image("i","h"));
        assertThatThrownBy(()->renderer.render(new Snapshot("p",1,"t","now","vip",List.of(new com.ruoyi.fashion.application.proposal.ProposalModels.Set("c","方案",List.of(noPrice),null,"CNY","included"))))).hasMessageContaining("价格");
    }
}
