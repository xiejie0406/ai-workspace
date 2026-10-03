package com.paicli.runner;

import com.paicli.mcp.mention.AtMentionParser;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.resources.McpResourceTool;
import com.paicli.policy.PathGuard;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolResultBoundary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.*;

/** 显式引用仍经过中心许可；正文保持资料边界，拒绝不生成第二份用户真相。 */
public final class NativeMentionExpander {
    private static final Pattern PATH = Pattern.compile("(^|\\s)@(<[^>]+>|[^\\s<>:]+)");
    private NativeMentionExpander() { }
    public static String expand(String input, CenterToolRegistry tools) throws IOException {
        StringBuilder expanded = new StringBuilder(input);
        record Replacement(int start, int end, String text) { }
        var replacements = new java.util.ArrayList<Replacement>();
        var resources = AtMentionParser.parse(input);
        if (resources.size() > 32) throw new IOException("一次最多32个资源引用");
        for (int i = resources.size() - 1; i >= 0; i--) {
            var token = resources.get(i);
            String name = McpToolDescriptor.namespaced(token.serverName(), McpResourceTool.READ_RESOURCE);
            String args = CanonicalJson.MAPPER.createObjectNode().put("uri", token.uri()).toString();
            replacements.add(new Replacement(token.start(), token.end(), token.raw() + "\n" + invoke(tools, name, args)));
        }
        Matcher matcher = PATH.matcher(input); int count = 0;
        PathGuard guard = new PathGuard(tools.getProjectPath());
        while (matcher.find()) {
            String token = matcher.group(2); String value = token.startsWith("<") ? token.substring(1, token.length() - 1) : token;
            if (value.equals("clipboard") || value.contains(":") || (matcher.end() < input.length() && input.charAt(matcher.end()) == ':')) continue;
            if (++count > 32) throw new IOException("一次最多32个文件引用");
            String replacement = "@" + token;
            try {
                Path path = guard.resolveSafe(value);
                if (Files.exists(path)) {
                    String tool = Files.isDirectory(path) ? "list_dir" : "read_file";
                    String args = CanonicalJson.MAPPER.createObjectNode().put("path", value).toString();
                    replacement += "\n" + invoke(tools, tool, args);
                }
            } catch (com.paicli.policy.PolicyException rejected) { replacement += "\n[引用拒绝: " + rejected.getMessage() + "]"; }
            replacements.add(new Replacement(matcher.start(2) - 1, matcher.end(2), replacement));
        }
        replacements.sort(java.util.Comparator.comparingInt(Replacement::start).reversed());
        for (Replacement replacement : replacements) expanded.replace(replacement.start(), replacement.end(), replacement.text());
        return expanded.toString();
    }
    private static String invoke(CenterToolRegistry tools, String name, String args) throws IOException {
        ToolOutput output = tools.executeToolOutput(name, args);
        if (!output.successful()) return ToolResultBoundary.wrap(name, "引用读取未完成: " + output.text());
        return ToolResultBoundary.wrap(name, output.text());
    }
}
