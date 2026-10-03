package com.ruoyi.paismart.application.billing;

import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.system.secret.ManagedSecretService;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 微信原协议适配；证书客户端仅在显式启用后按请求延迟构造。 */
@Service
@PaismartEnabled
public class WechatPaymentGateway {
    public record Payment(String eventId,String tradeNo,String transactionId,String state,long amountFen,String merchantId,String appId,String currency,String paidAt) { }
    private final ManagedSecretService secrets;
    private final boolean enabled;
    private final String merchantId,appId,serial,notifyUrl,privateKeyAlias,apiV3Alias;
    private volatile RSAAutoCertificateConfig config;
    private volatile NativePayService service;
    private volatile String credentialVersion;
    public WechatPaymentGateway(ManagedSecretService secrets,
            @Value("${paismart.payment.enabled:false}")boolean enabled,
            @Value("${paismart.payment.merchant-id:}")String merchantId,
            @Value("${paismart.payment.app-id:}")String appId,
            @Value("${paismart.payment.serial:}")String serial,
            @Value("${paismart.payment.notify-url:}")String notifyUrl,
            @Value("${paismart.payment.private-key-alias:}")String privateKeyAlias,
            @Value("${paismart.payment.api-v3-alias:}")String apiV3Alias) {
        this.secrets=secrets;this.enabled=enabled;this.merchantId=merchantId;this.appId=appId;this.serial=serial;this.notifyUrl=notifyUrl;this.privateKeyAlias=privateKeyAlias;this.apiV3Alias=apiV3Alias;
    }
    public boolean enabled() {return enabled;}
    public String prepay(String trade,String description,long fen,Instant expiry) {
        requireEnabled();
        if(fen<1 || fen>Integer.MAX_VALUE) throw new IllegalArgumentException("支付金额超出范围");
        PrepayRequest req=new PrepayRequest();req.setAppid(appId);req.setMchid(merchantId);req.setOutTradeNo(trade);req.setDescription(description);req.setNotifyUrl(notifyUrl);
        req.setTimeExpire(expiry.atOffset(ZoneOffset.ofHours(8)).toString());
        Amount amount=new Amount();amount.setTotal((int)fen);amount.setCurrency("CNY");req.setAmount(amount);
        String url=client().prepay(req).getCodeUrl();
        if(url==null || !url.startsWith("weixin://")) throw new IllegalStateException("支付平台未返回有效扫码地址");
        return url;
    }
    public Payment query(String trade) {
        requireEnabled();QueryOrderByOutTradeNoRequest req=new QueryOrderByOutTradeNoRequest();req.setMchid(merchantId);req.setOutTradeNo(trade);
        try{Transaction value=client().queryOrderByOutTradeNo(req);return payment("query:"+value.getTransactionId(),value);}catch(RuntimeException failure){throw new IllegalStateException("支付查单响应未确认，请保留原订单稍后核对");}
    }
    public Payment callback(String serialNo,String nonce,String timestamp,String signature,String body) {
        requireEnabled();
        try {if(Math.abs(Instant.now().getEpochSecond()-Long.parseLong(timestamp))>300) throw new IllegalArgumentException("支付通知已过期");}
        catch(NumberFormatException ex) {throw new IllegalArgumentException("支付通知时间无效");}
        if(body==null || body.length()>64_000) throw new IllegalArgumentException("支付通知长度无效");
        RequestParam request=new RequestParam.Builder().serialNumber(serialNo).nonce(nonce).timestamp(timestamp).signature(signature).body(body).build();
        Transaction value;
        try{client();value=new NotificationParser(config).parse(request,Transaction.class);}catch(RuntimeException failure){throw new IllegalArgumentException("支付通知验签、解密或凭证核对未通过");}
        // SDK 已验签解密；去重依据微信交易号，主动查询与回调共用最终入账。
        return payment("callback:"+value.getTransactionId(),value);
    }
    public void validate(Payment p) {
        if(!merchantId.equals(p.merchantId()) || !appId.equals(p.appId()) || !"CNY".equals(p.currency())) throw new IllegalArgumentException("支付商户、应用或币种不匹配");
    }
    private Payment payment(String event,Transaction t) { return new Payment(event,t.getOutTradeNo(),t.getTransactionId(),t.getTradeState().toString(),t.getAmount().getTotal(),t.getMchid(),t.getAppid(),t.getAmount().getCurrency(),t.getSuccessTime()); }
    private void requireEnabled() {if(!enabled) throw new IllegalStateException("真实支付尚未启用");}
    private NativePayService client() {
        requireEnabled();var key=resolved(privateKeyAlias);var apiKey=resolved(apiV3Alias);String version=key.metadata().activeVersion()+":"+apiKey.metadata().activeVersion();
        synchronized(this) {
            if(service==null || !version.equals(credentialVersion)) {
                requireEnabled();
                if(merchantId.isBlank() || appId.isBlank() || serial.isBlank() || !notifyUrl.startsWith("https://")) throw new IllegalStateException("支付配置未就绪");
                config=new RSAAutoCertificateConfig.Builder().merchantId(merchantId).privateKey(key.value()).merchantSerialNumber(serial).apiV3Key(apiKey.value()).build();
                service=new NativePayService.Builder().config(config).build();
                credentialVersion=version;
            }
            return service;
        }
    }
    private ManagedSecretService.Resolved resolved(String alias) {
        if(alias==null || !alias.startsWith("platform.paismart.payment.")) throw new IllegalStateException("支付密钥用途未配置");
        ManagedSecretService.Resolved r=secrets.resolve("PLATFORM",alias);
        if(!"paismart".equals(r.metadata().projectCode())) throw new IllegalStateException("支付密钥不属于知识库项目");
        return r;
    }
}
