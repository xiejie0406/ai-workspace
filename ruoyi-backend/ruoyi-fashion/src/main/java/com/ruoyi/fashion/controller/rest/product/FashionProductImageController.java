package com.ruoyi.fashion.controller.rest.product;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.domain.shared.FashionId;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@FashionModuleEnabled
@RestController
@RequestMapping("/fashion/products")
public class FashionProductImageController {
    private final FashionProductRepository products;private final FashionObjectStoragePort storage;
    public FashionProductImageController(FashionProductRepository products,FashionObjectStoragePort storage){this.products=products;this.storage=storage;}
    @GetMapping("/{id}/main-image")
    @PreAuthorize("@ss.hasPermi('fashion:product:query') or @ss.hasPermi('fashion:quote:list')")
    public ResponseEntity<byte[]> image(@PathVariable String id){
        var product=products.findById(FashionId.parse(id).value()).orElseThrow(()->new ServiceException("商品不存在"));
        var image=product.images().stream().filter(i->i.objectKey().equals(product.mainImageKey())&&"active".equals(i.status())&&(i.allowInternal()||i.allowProposal())).findFirst().orElseThrow(()->new ServiceException("商品没有可查看的主图"));
        return content(image);
    }
    @GetMapping("/{id}/images/{imageId}/content")
    @PreAuthorize("@ss.hasPermi('fashion:product:query') or @ss.hasPermi('fashion:quote:list')")
    public ResponseEntity<byte[]> imageContent(@PathVariable String id,@PathVariable String imageId){
        var product=products.findById(FashionId.parse(id).value()).orElseThrow(()->new ServiceException("商品不存在"));
        var image=product.images().stream().filter(i->imageId.equals(i.imageId())&&"active".equals(i.status())&&(i.allowInternal()||i.allowProposal())).findFirst().orElseThrow(()->new ServiceException("图片不属于当前商品或没有查看许可"));
        return content(image);
    }
    private ResponseEntity<byte[]> content(com.ruoyi.fashion.domain.product.FashionProductImage image){
        byte[] bytes=storage.read(image.objectKey());
        if(!FashionHashing.sha256(bytes).equals(image.sha256()))throw new ServiceException("商品图片摘要不一致");
        String type;
        if(bytes.length>8&&bytes[0]==(byte)0x89&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G')type="image/png";
        else if(bytes.length>3&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8&&bytes[2]==(byte)0xff)type="image/jpeg";
        else if(bytes.length>12&&new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))type="image/webp";
        else throw new ServiceException("图片格式不支持预览");
        return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(type)).body(bytes);
    }
}
