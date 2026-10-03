package com.ruoyi.paicli.service;

import com.ruoyi.system.secret.ManagedSecretService;
import com.ruoyi.paicli.domain.PcException;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** 复用平台受控密钥；幂等响应中的设备和permit凭证仅以AES-GCM密文落库。 */
public class PcCrypto {
    private final ManagedSecretService secrets;
    private final PcJson json;
    private final SecureRandom random=new SecureRandom();
    public PcCrypto(ManagedSecretService secrets,PcJson json){this.secrets=secrets;this.json=json;}
    protected byte[] key(){
        String raw=secrets.require("PLATFORM","platform.paicli.control-key");
        if(raw.stripLeading().startsWith("{"))raw=PcJson.text(json.read(raw),"keyBase64");
        byte[] key=Base64.getDecoder().decode(raw);if(key.length!=32)throw new IllegalStateException("PaiCLI控制密钥须为32字节");return key;
    }
    public String token(String prefix){byte[] bytes=new byte[32];random.nextBytes(bytes);return prefix+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public String seal(String context,String value){
        try{byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,nonce));c.updateAAD(context.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("无法安全保存凭证响应；停止签发",e);}
    }
    public String open(String context,String value){
        try{String[] p=value.split("\\.",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));c.updateAAD(context.getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);}
        catch(Exception e){throw new PcException(503,"PC_CREDENTIAL_RECOVERY_UNAVAILABLE","凭证响应无法解密，请核对控制密钥版本");}
    }
}
