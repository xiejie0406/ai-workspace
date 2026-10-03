package com.ruoyi.fashion.configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅北京地域AI试衣Plus；密钥从运行环境绑定，默认不产生外部调用。 */
@ConfigurationProperties(prefix = "fashion.image.aliyun")
public class FashionAliyunImageProperties {
    private String apiKey = "";
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1";
    private String model = "aitryon-plus";
    private BigDecimal estimatedUnitCost = new BigDecimal("0.50");
    private Duration timeout = Duration.ofSeconds(30);
    private List<String> resultHosts = List.of("dashscope-result-bj.oss-cn-beijing.aliyuncs.com",
            "dashscope-result-hz.oss-cn-hangzhou.aliyuncs.com", "dashscope-result-sh.oss-cn-shanghai.aliyuncs.com");
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public BigDecimal getEstimatedUnitCost() { return estimatedUnitCost; }
    public void setEstimatedUnitCost(BigDecimal estimatedUnitCost) { this.estimatedUnitCost = estimatedUnitCost; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public List<String> getResultHosts() { return resultHosts; }
    public void setResultHosts(List<String> resultHosts) { this.resultHosts = resultHosts; }
}
