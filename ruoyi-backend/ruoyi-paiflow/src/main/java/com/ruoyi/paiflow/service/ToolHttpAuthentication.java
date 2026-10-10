package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 原HttpRun的MD5/HMAC字节协议。只消费中心托管材料，不读取旧进程环境或公开材料。 */
final class ToolHttpAuthentication {
 record Request(URI target,JSONObject headers,Set<String> privateValues){}
 static String mode(JSONObject config){return config.getString("authenticationMode",config.containsKey("credentialRef")?"apiKey":"none");}
 static Request assemble(ToolRuntimeProperties.Profile profile,JSONObject config,String method,JSONObject headers,JSONObject query,JSONObject path,JSONObject body,String material,Instant now){
  String mode=mode(config);var values=new LinkedHashSet<String>();if("none".equals(mode))return new Request(ManagedToolProducer.target(profile,path,query),headers,values);
  if(material==null)throw error("工具鉴权材料未装配",503);
  if("apiKey".equals(mode)){values.add(material);("header".equals(profile.getAuthenticationIn())?headers:query).put(profile.getAuthenticationName(),material);return new Request(ManagedToolProducer.target(profile,path,query),headers,values);}
  JSONObject secret;try{secret=JSON.parseObject(material);}catch(RuntimeException e){throw error("工具私有复合材料无效",503);}String app=text(secret,"appId");values.add(app);
  String url=ManagedToolProducer.rawTarget(profile,path);URI base=URI.create(ManagedToolProducer.quote(url));String originalPath=url.substring(url.indexOf('/',url.indexOf("://")+3));
  try{if("md5".equals(mode)){String key=text(secret,"appKey"),time=Long.toString(now.toEpochMilli());values.add(key);String token=HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest((app+key+time).getBytes(StandardCharsets.UTF_8)));values.add(token);url+="?appId="+app+"&token="+token+"&timestamp="+time;if(!query.isEmpty()){var pairs=new ArrayList<String>();query.forEach((k,v)->pairs.add(k+"="+ModelMessageSources.pythonString(v,false)));url+="&"+String.join("&",pairs);}return new Request(URI.create(ManagedToolProducer.quote(url)),headers,values);}
   if(!"hmac".equals(mode))throw error("工具鉴权方式无效",503);String key=text(secret,"apiKey"),signing=text(secret,"apiSecret");values.add(key);values.add(signing);JSONObject policy=config.getJSONObject("hmacConfiguration");if(policy==null)throw error("工具HMAC固定配置缺失",503);String host=base.getRawAuthority(),requestPath=originalPath;String date=DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US).withZone(ZoneOffset.UTC).format(now);
   boolean digest=policy.getBooleanValue("is_digest");String digestValue=digest?"SHA-256="+Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(ToolSchema.pythonJson(body).getBytes(StandardCharsets.UTF_8))):null;String origin="host: "+host+"\ndate: "+date+"\n"+method.toUpperCase(Locale.ROOT)+" "+requestPath+" HTTP/1.1"+(digest?"\ndigest: "+digestValue:"");var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(signing.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));String signature=Base64.getEncoder().encodeToString(mac.doFinal(origin.getBytes(StandardCharsets.UTF_8)));
   String part=policy.containsKey("authorization_input_part")?ModelMessageSources.pythonString(policy.get("authorization_input_part"),false):"None";String authorization=part+"=\""+key+"\", algorithm=\"hmac-sha256\", headers=\"host date request-line"+(digest?" digest":"")+"\", signature=\""+signature+"\"";values.add(authorization);values.add(signature);
   var output=digest?JSONObject.of("Content-Type","application/json","Accept","application/json","Method",method,"Host",host,"Date",date,"Digest",digestValue,"Authorization",authorization):JSONObject.of("content-Type","application/json","host",host,"app_id",app);
   if(policy.getBooleanValue("is_url_join")){String encoded=Base64.getEncoder().encodeToString(authorization.getBytes(StandardCharsets.UTF_8));values.add(encoded);url+="?host="+encode(host)+"&date="+encode(date)+"&authorization="+encode(encoded);}return new Request(URI.create(ManagedToolProducer.quote(url)),output,values);
  }catch(java.security.GeneralSecurityException e){throw error("工具私有签名失败",503);}
 }
 private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
 private static String text(JSONObject secret,String key){Object v=secret.get(key);if(!(v instanceof String s)||s.isEmpty()||s.chars().anyMatch(Character::isISOControl))throw error("工具私有复合材料字段无效",503);return s;}
 static void configure(JSONObject config,JSONObject body){String explicit=body.getString("authenticationMode");String inferred=null;JSONObject hmac=null;var api=config.getJSONObject("openApiSchema");for(var op:ToolSchema.operations(api).values()){var params=op.schema().getJSONArray("parameters");if(params==null)continue;for(Object raw:params){var param=(JSONObject)raw;if(!"header".equals(param.get("in"))||!"Authorization".equals(param.get("name")))continue;String value=param.getJSONObject("schema").getString("default");String current="GET".equals(op.method())&&"MD5".equals(value)?"md5":value!=null&&value.startsWith("HMAC:")?"hmac":null;if(current==null)continue;if(inferred!=null&&!inferred.equals(current))throw error("单一工具认证模式冲突",422);inferred=current;if("hmac".equals(current)){try{var parsed=JSON.parseObject(value.substring(5));if(hmac!=null&&!ToolSchema.same(hmac,parsed))throw error("单一工具HMAC配置冲突",422);hmac=parsed;}catch(RuntimeException e){throw error("原HMAC认证配置无效",422);}}}}
  String mode=explicit==null?(inferred!=null?inferred:config.containsKey("credentialRef")?"apiKey":"none"):explicit;if(!Set.of("none","apiKey","md5","hmac").contains(mode)||inferred!=null&&!inferred.equals(mode))throw error("工具认证模式与固定Schema不一致",422);if(!"none".equals(mode)&&!config.containsKey("credentialRef"))throw error("工具原认证需要中心托管凭据引用",422);config.put("authenticationMode",mode);
  if("hmac".equals(mode)){if(hmac==null)hmac=body.getJSONObject("hmacConfiguration");if(hmac==null||!hmac.keySet().stream().allMatch(k->Set.of("is_digest","is_url_join","authorization_input_part").contains(k))||hmac.containsKey("is_digest")&&!(hmac.get("is_digest") instanceof Boolean)||hmac.containsKey("is_url_join")&&!(hmac.get("is_url_join") instanceof Boolean)||hmac.containsKey("authorization_input_part")&&!(hmac.get("authorization_input_part") instanceof String))throw error("工具HMAC固定声明无效",422);config.put("hmacConfiguration",hmac);}
 }
}
