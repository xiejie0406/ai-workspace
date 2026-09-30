package com.ruoyi.fashion.infrastructure.files.pptx;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.application.proposal.ProposalModels;
import com.ruoyi.fashion.infrastructure.files.FashionDeliveryMedia;

/** 原生可编辑文本与嵌入图片；提案不要求正式报价或尺码数量。 */
@Component @FashionModuleEnabled
public final class FashionProposalPptxRenderer {
    private final FashionDeliveryMedia media;
    public FashionProposalPptxRenderer(FashionDeliveryMedia media) { this.media=media; }
    public byte[] render(ProposalModels.Snapshot snapshot) {
        if(snapshot.sets().isEmpty() || snapshot.sets().size()>100) throw new ServiceException("每个 PPT 文件请选择 1～100 套方案，请分批导出");
        try(XMLSlideShow ppt=new XMLSlideShow(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(960,540));
            long[] imageBytes={0};
            for(var set:snapshot.sets()) {
                if(set.items().isEmpty() || set.items().size()>4) throw new ServiceException("提案每套必须有 1～4 个品类");
                var slide=ppt.createSlide();
                text(slide,30,18,900,48,set.name(),25,true);
                double left=set.modelImage()==null?30:335;
                if(set.modelImage()!=null) embed(ppt,slide,set.modelImage(),30,85,280,360,imageBytes);
                double width=(930-left)/set.items().size();
                for(int i=0;i<set.items().size();i++) {
                    var item=set.items().get(i); double x=left+i*width;
                    embed(ppt,slide,item.image(),x+8,85,width-16,230,imageBytes);
                    text(slide,x+8,325,width-16,80,item.name(),set.items().size()>2?13:17,true);
                    text(slide,x+8,407,width-16,61,"主 SKU："+item.mainSku()+"\n品号："+display(item.itemNo())+"\n货号："+display(item.articleNo())+"\n颜色："+display(item.color()),10,false);
                    if(!"none".equals(snapshot.priceMode())) {
                        if(item.price()==null) throw new ServiceException("商品缺少所选价格，不能导出");
                        text(slide,x+8,468,width-16,25,("vip".equals(snapshot.priceMode())?"VIP价：":"团购价：")+item.price().toPlainString()+" "+set.currency(),12,true);
                    }
                }
                text(slide,30,510,900,20,"选品提案 · "+("none".equals(snapshot.priceMode())?"未展示价格": "参考价格 / "+set.taxMode())+" · 数据日期 "+snapshot.capturedAt()+" · 草稿版本 "+snapshot.version(),9,false);
            }
            ppt.write(out); return out.toByteArray();
        } catch(ServiceException ex) { throw ex; }
          catch(Exception ex) { throw new ServiceException("PPT 生成失败：图片或文档数据不可用"); }
    }
    private void embed(XMLSlideShow ppt,XSLFSlide slide,ProposalModels.Image ref,double x,double y,double w,double h,long[] imageBytes) throws Exception {
        if(ref==null) throw new ServiceException("商品缺少可用于提案的图片");
        byte[] data=media.readVerified(ref.objectKey(),ref.sha256());
        imageBytes[0]+=data.length;
        if(imageBytes[0]>50L*1024*1024) throw new ServiceException("本批图片总量超过 50MB，请减少方案数量后分批导出");
        var img=ImageIO.read(new ByteArrayInputStream(data));
        if(img==null) throw new ServiceException("商品图片格式不可解析");
        PictureType type;
        if(data.length>8 && data[0]==(byte)0x89 && data[1]==0x50) type=PictureType.PNG;
        else if(data.length>3 && data[0]==(byte)0xff && data[1]==(byte)0xd8) type=PictureType.JPEG;
        else throw new ServiceException("提案图片仅支持 PNG/JPEG，请先转换素材");
        var shape=slide.createPicture(ppt.addPicture(data,type));
        double scale=Math.min(w/img.getWidth(),h/img.getHeight());
        double rw=img.getWidth()*scale,rh=img.getHeight()*scale;
        shape.setAnchor(new Rectangle2D.Double(x+(w-rw)/2,y+(h-rh)/2,rw,rh));
    }
    private static void text(XSLFSlide slide,double x,double y,double w,double h,String value,double size,boolean bold) {
        var box=slide.createTextBox(); box.setAnchor(new Rectangle2D.Double(x,y,w,h)); box.setText(value==null?"":value);
        for(var p:box.getTextParagraphs()) for(var run:p.getTextRuns()) {run.setFontFamily("Microsoft YaHei");run.setFontSize(size);run.setBold(bold);run.setFontColor(new Color(31,48,67));}
    }
    private static String display(String value) {return value==null||value.isBlank()?"未提供":value;}
}
