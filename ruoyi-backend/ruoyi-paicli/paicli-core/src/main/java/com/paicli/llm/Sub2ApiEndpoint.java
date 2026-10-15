package com.paicli.llm;

import okhttp3.HttpUrl;

/** 明确 API 根地址和协议，不能从厂商默认值或失败响应猜测。 */
public final class Sub2ApiEndpoint {
    private Sub2ApiEndpoint() {}

    public static String wireApi(String value) {
        if (!"responses".equals(value) && !"chat_completions".equals(value))
            throw new IllegalArgumentException("Sub2API 必须选择 responses 或 chat_completions");
        return value;
    }

    public static HttpUrl root(String value) {
        return root(value, null);
    }
    public static HttpUrl root(String value, String approvedHttpRoot) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Sub2API 缺少 API 根地址");
        HttpUrl url;
        try { url = HttpUrl.get(value.trim()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Sub2API API 根地址非法"); }
        boolean loopback = url.host().equals("localhost") || url.host().equals("127.0.0.1") || url.host().equals("::1");
        if (!url.isHttps() && !loopback && !approvedHttp(value,approvedHttpRoot)) throw new IllegalArgumentException("Sub2API 托管凭据要求 HTTPS 或中心明确批准的 HTTP 根地址");
        if (!url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null)
            throw new IllegalArgumentException("Sub2API 根地址不能包含认证信息、查询串或 fragment");
        String p = url.encodedPath().replaceAll("/+$", "");
        if (p.endsWith("/responses") || p.endsWith("/chat/completions") || p.endsWith("/models"))
            throw new IllegalArgumentException("Sub2API 请填写 API 根地址，不能填写完整业务端点");
        return url.newBuilder().encodedPath(p.isEmpty() ? "/" : p + "/").build();
    }

    public static String endpoint(String root, String wireApi) {
        return endpoint(root,wireApi,null);
    }
    public static String endpoint(String root, String wireApi,String approvedHttpRoot) {
        return root(root,approvedHttpRoot).newBuilder().addPathSegments(wireApi(wireApi).equals("responses") ? "responses" : "chat/completions").build().toString();
    }
    private static boolean approvedHttp(String value,String approved) {
        if(approved==null)return false;
        try{return canonicalHttpRoot(value).equals(canonicalHttpRoot(approved));}catch(IllegalArgumentException invalid){return false;}
    }
    private static String canonicalHttpRoot(String value) {
        java.net.URI u=java.net.URI.create(value);String path=u.getRawPath();
        if(!"http".equals(u.getScheme())||u.getHost()==null||u.getPort()<1||u.getPort()>65535||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null
            ||path==null||path.isEmpty()||path.contains("%")||path.contains("//")||!u.normalize().getRawPath().equals(path)
            ||java.util.Arrays.asList(path.split("/",-1)).contains("."))throw new IllegalArgumentException();
        return "http://"+u.getHost().toLowerCase(java.util.Locale.ROOT)+":"+u.getPort()+path;
    }
}
