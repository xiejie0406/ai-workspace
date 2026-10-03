package com.ruoyi.fashion.infrastructure.storage;

import java.util.Map;
import java.util.Set;
import java.util.Objects;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** 短链接只代表本次请求，商品用途许可撤销后不能继续向Provider提供图片。 */
@Component @FashionModuleEnabled
public class FashionProviderMediaAccess {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FashionProductRepository products;
    public FashionProviderMediaAccess(@Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, FashionProductRepository products) { this.jdbc = jdbc; this.mapper = mapper; this.products = products; }

    public void requireCurrentPermission(String key, String taskId) {
        var rows = jdbc.queryForList("select owner_id,input_json,status from fq_proposal_image_task where id=:id", Map.of("id", taskId));
        if (rows.size() != 1 || !Set.of("submitting","queued","running","unknown","cancel_requested").contains(Objects.toString(rows.get(0).get("status"))))
            throw new ServiceException("试衣任务不存在或不再允许读取输入素材", 403);
        var inputs = mapper.readTree(Objects.toString(rows.get(0).get("input_json")));
        if (key.startsWith("proposal-models/")) {
            if (!Objects.equals(key, inputs.path("model").path("objectKey").asText()) || !key.startsWith("proposal-models/" + rows.get(0).get("owner_id") + "/"))
                throw new ServiceException("模特素材不属于本次任务", 403);
            return;
        }
        int matches = 0;
        for (var input : inputs.path("products")) {
            if (!Objects.equals(key,input.path("object_key").asText()) || !Set.of("top","bottom","dress").contains(input.path("garment_position").asText())) continue;
            matches++;
            long id;
            try { id = Long.parseLong(input.path("productId").asText()); } catch (NumberFormatException invalid) { throw new ServiceException("任务商品身份无效",403); }
            var product = products.findById(id).orElseThrow(() -> new ServiceException("试衣商品已不存在",403));
            if (product.visualVersion() != input.path("visualVersion").asLong() || product.images().stream().noneMatch(image -> image.allowAi() && "active".equals(image.status()) && Objects.equals(key,image.objectKey()) && Objects.equals(input.path("sha256").asText(),image.sha256())))
                throw new ServiceException("所选商品图片AI许可或视觉版本已变更",403);
        }
        if (matches == 0) throw new ServiceException("素材不属于本次试衣任务实际服装",403);
    }

    public void verifyBytes(String key, byte[] bytes) {
        String expected = key.startsWith("materials/") ? key.split("/")[1] : key.substring(key.lastIndexOf('/') + 1).split("\\.")[0];
        if (!expected.equals(FashionHashing.sha256(bytes))) throw new ServiceException("试衣素材摘要不一致，访问已阻止", 409);
    }
}
