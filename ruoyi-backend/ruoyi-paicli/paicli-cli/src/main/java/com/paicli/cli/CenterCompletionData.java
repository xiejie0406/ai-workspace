package com.paicli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.resources.McpResourceDescriptor;
import com.paicli.runtime.center.CenterCommandGateway;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 仅缓存中心可见的补全元数据；Tab不联网，不扫描配置或取得供应商凭证。 */
final class CenterCompletionData implements Completer {
    private record Choice(String value, String description) {}
    private record Snapshot(List<Choice> models, List<Choice> providers, List<Choice> servers,
                            List<Choice> skills, List<McpResourceDescriptor> resources) {
        static Snapshot empty() { return new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of()); }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private volatile Snapshot snapshot = Snapshot.empty();
    private final PaiCliCompleter base = new PaiCliCompleter(() -> snapshot.resources());

    void refresh(CenterCommandGateway gateway, String sessionId) {
        List<Choice> models = new ArrayList<>(), providers = new ArrayList<>(), servers = new ArrayList<>(), skills = new ArrayList<>();
        List<McpResourceDescriptor> resources = new ArrayList<>();
        for (JsonNode model : read(gateway, sessionId, "model", "list", "models")) {
            String id = model.path("id").asText();
            if (id.matches("[1-9][0-9]*") && model.path("enabled").asBoolean(false))
                models.add(new Choice(id, printable(label(model, "provider", "providerId") + " / " + label(model, "modelId", "model"))));
        }
        for (JsonNode provider : read(gateway, sessionId, "config", "", "connections")) {
            String name = label(provider, "provider", "name"); if (safe(name)) providers.add(new Choice(name, "中心登记供应商"));
        }
        for (JsonNode server : read(gateway, sessionId, "mcp", "list", "connections")) {
            String name = server.path("name").asText(); if (!safe(name)) continue;
            servers.add(new Choice(name, "中心登记MCP连接"));
            if (!"READY".equalsIgnoreCase(server.path("status").asText())
                    || server.path("configHash").asText().isBlank()
                    || !server.path("configHash").asText().equals(server.path("trustedHash").asText())) continue;
            for (JsonNode resource : server.path("resources")) {
                McpResourceDescriptor descriptor = McpResourceDescriptor.fromJson(name, resource);
                if (descriptor != null && safe(descriptor.uri())) resources.add(new McpResourceDescriptor(name, descriptor.uri(), printable(descriptor.name()), printable(descriptor.title()), printable(descriptor.description()), printable(descriptor.mimeType()), descriptor.size()));
            }
        }
        for (JsonNode skill : read(gateway, sessionId, "skill", "list", "connections")) {
            String name = skill.path("name").asText(); if (safe(name)) skills.add(new Choice(name, "中心登记Skill"));
        }
        snapshot = new Snapshot(List.copyOf(models), List.copyOf(providers), List.copyOf(servers), List.copyOf(skills), List.copyOf(resources));
    }
    private JsonNode read(CenterCommandGateway gateway, String sessionId, String command, String args, String field) {
        try {
            var result = gateway.command(sessionId, command, args);
            if (!result.successful() || result.data() == null) return JSON.createArrayNode();
            JsonNode values = JSON.valueToTree(result.data()).path(field);
            return values.isArray() ? values : JSON.createArrayNode();
        } catch (IOException | RuntimeException denied) { return JSON.createArrayNode(); }
    }
    private static boolean safe(String value) { return value != null && !value.isBlank() && value.length() <= 2048 && value.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)); }
    private static String label(JsonNode node, String field, String fallback) { String value = node.path(field).asText(); return value.isBlank() ? node.path(fallback).asText() : value; }
    private static String printable(String value) { if (value == null) return ""; StringBuilder clean=new StringBuilder(); value.codePoints().limit(512).forEach(c -> clean.appendCodePoint(Character.isISOControl(c) ? ' ' : c)); return clean.toString(); }
    @Override public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null || candidates == null) return;
        String input = line.line() == null ? "" : line.line();
        int cursor = Math.max(0, Math.min(line.cursor(), input.length()));
        String prefix = input.substring(0, cursor);
        if (prefix.matches("/(?i:model|mcp|skill|config)(?:\\s.*)?")) {
            String[] parts = prefix.substring(1).split("\\s+", -1);
            String command = parts[0].toLowerCase(java.util.Locale.ROOT);
            if (parts.length == 1) { candidates.add(new Candidate("/" + command, "/" + command, "中心命令", "读取中心当前登记信息", null, null, true)); return; }
            String word = parts[parts.length - 1]; Snapshot data = snapshot;
            if (command.equals("model")) {
                if (parts.length == 2) { add(candidates, word, choices("list", "info", "refresh", "add")); add(candidates, word, data.models()); }
                else if (parts.length == 3 && parts[1].equals("info")) add(candidates, word, data.models());
                else if (parts.length == 3 && List.of("refresh", "add").contains(parts[1])) add(candidates, word, data.providers());
            } else if (command.equals("config")) {
                if (parts.length == 2) add(candidates, word, choices("provider"));
                else if (parts.length == 3 && parts[1].equals("provider")) add(candidates, word, data.providers());
            } else if (command.equals("mcp")) {
                if (parts.length == 2) add(candidates, word, choices("list", "status", "start", "stop", "restart", "logs", "disable", "enable", "resources", "prompts"));
                else if (parts.length == 3) add(candidates, word, data.servers());
                else if (parts.length == 4 && parts[1].equals("resources"))
                    add(candidates, word, data.resources().stream().filter(r -> r.serverName().equals(parts[2])).map(r -> new Choice(r.uri(), r.displayName())).toList());
            } else if (command.equals("skill")) {
                if (parts.length == 2) add(candidates, word, choices("list", "show", "on", "off", "reload"));
                else if (parts.length == 3 && List.of("show", "on", "off").contains(parts[1])) add(candidates, word, data.skills());
            }
            return;
        }
        base.complete(reader, line, candidates);
        // 首词尚未输完时，原命令目录中的本机模型别名/扫描提示也不能进入中心候选。
        candidates.removeIf(candidate -> candidate.value().matches("/(?i:model|config|mcp|skill)\\s.*"));
    }
    private static List<Choice> choices(String... values) { return java.util.Arrays.stream(values).map(v -> new Choice(v, "中心命令")).toList(); }
    private static void add(List<Candidate> target, String prefix, List<Choice> values) { for (Choice value : values) if (value.value().startsWith(prefix)) target.add(new Candidate(value.value(), value.value(), "中心元数据", value.description(), null, null, true)); }
}
