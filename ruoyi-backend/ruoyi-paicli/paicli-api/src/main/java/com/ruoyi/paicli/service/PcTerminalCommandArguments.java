package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.PcException;
import java.util.*;

/** 原终端参数的中心适配；只解析语法，权限、配置保存和业务结果仍由现有服务决定。 */
final class PcTerminalCommandArguments {
    record Save(String fact,String scope,boolean force){}
    private PcTerminalCommandArguments(){}
    static Save save(String payload){
        String rest=payload==null?"":payload.strip(),scope="PROJECT";boolean force=false;
        while(rest.startsWith("--")){
            int space=-1;for(int i=0;i<rest.length();i++)if(Character.isWhitespace(rest.charAt(i))){space=i;break;}
            String flag=(space<0?rest:rest.substring(0,space)).toLowerCase(Locale.ROOT);
            if(!Set.of("--global","--project","--force").contains(flag))break;
            if(flag.equals("--global"))scope="PERSONAL";else if(flag.equals("--project"))scope="PROJECT";else force=true;
            rest=space<0?"":rest.substring(space).strip();
        }
        if(rest.isBlank())throw PcException.invalid("save需要事实正文");return new Save(rest,scope,force);
    }
    static Map<String,String> modelOptions(String[] words){
        Map<String,String> result=new LinkedHashMap<>();Set<String> allowed=Set.of("like","context","vision","max-output","thinking","effort","reasoning-history","dsml");
        for(int i=3;i<words.length;i++){
            String item=words[i];if(!item.startsWith("--"))throw PcException.invalid("模型选项必须以--开头");
            String key=item.substring(2),value;int equals=key.indexOf('=');
            if(equals>=0){value=key.substring(equals+1);key=key.substring(0,equals);}
            else{
                // 保留先前中心的裸--vision扩展，同时完整接受原--vision true|false。
                if(key.equals("vision")&&(i+1>=words.length||words[i+1].startsWith("--")))value="true";
                else{if(++i>=words.length)throw PcException.invalid("模型选项缺少值：--"+key);value=words[i];}
            }
            if(!allowed.contains(key)||value.isBlank())throw PcException.invalid("未知或空模型选项：--"+key);
            if(result.putIfAbsent(key,value)!=null)throw PcException.invalid("重复模型选项：--"+key);
        }
        return result;
    }
    static boolean bool(String value){if(!Set.of("true","false","on","off").contains(value))throw PcException.invalid("布尔选项须为true/false或on/off");return value.equals("true")||value.equals("on");}
    static long positive(String value){try{long n=Long.parseLong(value);if(n<1)throw new NumberFormatException();return n;}catch(NumberFormatException invalid){throw PcException.invalid("模型数值必须为正整数");}}
    static String provider(String raw){String provider=raw==null?"":raw.strip().toLowerCase(Locale.ROOT);return switch(provider){
        case "stepfun","step-fun"->"step";
        case "hy4","hy4-preview","tokenhub","tencent-hunyuan","tencent_hunyuan"->"hunyuan";
        case "moonshot","moonshotai","moonshot-ai"->"kimi";
        case "free-llm-api","free_llm_api","freellm","free-llm"->"freellmapi";
        case "xfyun-maas","xfyun_maas","iflytek","iflytek-maas","iflytek_maas","maas"->"xfyun";
        case "agnes-ai","agnes_ai","sapiens","sapiens-ai","sapiens_ai"->"agnes";
        default->provider;
    };}
    static List<String> configWords(String value){
        List<String> result=new ArrayList<>();StringBuilder current=new StringBuilder();char quote=0;
        for(char ch:value.toCharArray()){
            if(quote!=0){if(ch==quote)quote=0;else current.append(ch);}
            else if(ch=='\''||ch=='"')quote=ch;
            else if(Character.isWhitespace(ch)){if(!current.isEmpty()){result.add(current.toString());current.setLength(0);}}
            else current.append(ch);
        }
        if(!current.isEmpty())result.add(current.toString());return result;
    }
    static String configKey(String raw){String key=raw.toLowerCase(Locale.ROOT);while(key.startsWith("-"))key=key.substring(1);return switch(key){
        case "baseurl","base_url","url"->"base-url";
        case "loraid","lora_id","resourceid","resource_id"->"lora-id";
        case "apikey","api_key","key"->"api-key";
        default->key;
    };}
    static String model(String provider,String value){return provider.equals("deepseek")&&Set.of("deepseek-v4-flash","deepseek-v4-flash-vision-exp").contains(value)?"deepseek-flash":value;}
}
