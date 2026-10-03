package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** 模型不可授予权限。此确定性检查只能由Runner的真实路径、DNS和隔离执行检查继续收紧。 */
public final class PcToolPolicy {
    public static final Set<String> BUILTIN=Set.of("read_file","write_file","edit_file","list_dir","glob_files","grep_code","execute_command","create_project","search_code","web_search","web_fetch","browser_connect","browser_disconnect","browser_status","load_skill","save_memory","revert_turn");
    public static final Set<String> READONLY=Set.of("read_file","list_dir","glob_files","grep_code","search_code","web_search","web_fetch","load_skill","browser_status");
    public static final Set<String> CONTROL=Set.of("REPOSITORY_LIST","INDEX_BUILD","REPOSITORY_SEARCH","AST_GRAPH","CONTEXT_COMPRESS","SNAPSHOT_PREVIEW","SNAPSHOT_RESTORE","SNAPSHOT_CLEAN_PREVIEW","SNAPSHOT_CLEAN","INIT_PROJECT_MEMORY","PROVIDER_REFRESH","MCP_START","MCP_STOP","MCP_RESTART","MCP_READ_RESOURCE","MCP_READ_PROMPT","SKILLS_RELOAD","BROWSER_CONNECT","BROWSER_PROBE","BROWSER_TABS","BROWSER_DISCONNECT","HARNESS_QUICK","HARNESS_NORMAL","HARNESS_INLINE","MEMORY_VERIFY","MEMORY_DELETE","MEMORY_CLEAR","WECHAT_SETUP","WECHAT_START","WECHAT_PAUSE","WECHAT_RESUME","WECHAT_STOP","WECHAT_RESTART");
    public static final Set<String> CONTROL_READ=Set.of("REPOSITORY_LIST","INDEX_BUILD","REPOSITORY_SEARCH","AST_GRAPH","CONTEXT_COMPRESS","SNAPSHOT_PREVIEW","SNAPSHOT_CLEAN_PREVIEW","PROVIDER_REFRESH","SKILLS_RELOAD","BROWSER_PROBE","BROWSER_TABS","HARNESS_QUICK","HARNESS_NORMAL","HARNESS_INLINE");
    private static final Pattern HARD_COMMAND=Pattern.compile("(?i)(rm\\s+(-[^ ]*r[^ ]*\\s+)*[/~]|format\\s+[a-z]:|mkfs|shutdown|reboot|curl.+\\|\\s*(sh|bash)|wget.+\\|\\s*(sh|bash)|Invoke-Expression|\\bIEX\\b|Set-MpPreference|\\breg\\s+delete|diskpart|Remove-Item.+-Recurse.+[a-z]:[\\\\/]\\s*$)");
    private PcToolPolicy(){}
    public static String deny(String name,Map<String,Object> args,Map<String,Object> runner,Map<String,Object> workspace){
        for(var e:args.entrySet())if(Set.of("path","file","file_path","directory","cwd","target","root").contains(e.getKey())&&e.getValue() instanceof String path){
            String normalized=path.replace('\\','/');for(String segment:normalized.split("/"))if(Set.of(".ssh",".aws",".codex","Windows","Program Files").contains(segment))return "路径涉及用户凭据、系统或其他业务安装目录";
            if(normalized.contains("../")||normalized.equals("..")||normalized.contains("\u0000"))return "路径包含越界或非法字符";
            String root=PcJson.text(workspace,"rootPath").replace('\\','/');if(normalized.startsWith("/")||normalized.matches("^[A-Za-z]:/.*")){if(!normalized.equals(root)&&!normalized.startsWith(root.endsWith("/")?root:root+"/"))return "绝对路径不在批准的仓库目录内";}
        }
        if(name.equals("execute_command")||name.equals("create_project")||((name.equals("control__MCP_START")||name.equals("control__MCP_RESTART"))&&"STDIO".equals(PcJson.text(PcJson.object(args,"config"),"transport")))){
            if(!Boolean.TRUE.equals(runner.get("sandboxVerified")))return "Runner命令隔离环境尚未经批准核验，命令执行不可用";
            String command=PcJson.text(args,"command");if(command.length()>8192||HARD_COMMAND.matcher(command).find())return "命令违反不可批准的硬安全规则";
        }
        if(name.equals("web_fetch")||name.equals("browser_connect")){
            String url=PcJson.text(args,"url");if(!url.isBlank())try{URI uri=URI.create(url);String host=uri.getHost();if(!Set.of("http","https").contains(uri.getScheme())||host==null||uri.getUserInfo()!=null||host.equalsIgnoreCase("localhost")||host.equals("::1")||host.matches("(127|10|0|169\\.254|192\\.168)\\..*")||host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*"))return "网页地址涉及非法协议或内网地址";}catch(Exception e){return "网页地址非法";}
        }
        return null;
    }
}
