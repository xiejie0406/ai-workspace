package com.paicli.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Map;

public class PromptRepository {
    private static final String RESOURCE_PREFIX = "prompts/";

    private final Path userPromptsDir;
    private final Path projectPromptsDir;
    private final ClassLoader classLoader;
    private final Map<String, String> personalOverrides;
    private final Map<String, String> projectOverrides;
    private static final InheritableThreadLocal<PromptRepository> CENTRAL = new InheritableThreadLocal<>();

    public PromptRepository(Path userPromptsDir, Path projectPromptsDir) {
        this(userPromptsDir, projectPromptsDir, PromptRepository.class.getClassLoader());
    }

    PromptRepository(Path userPromptsDir, Path projectPromptsDir, ClassLoader classLoader) {
        this.userPromptsDir = userPromptsDir;
        this.projectPromptsDir = projectPromptsDir;
        this.classLoader = Objects.requireNonNull(classLoader);
        this.personalOverrides = Map.of(); this.projectOverrides = Map.of();
    }

    private PromptRepository(Map<String, String> personal, Map<String, String> project, boolean central) {
        userPromptsDir = null; projectPromptsDir = null; classLoader = PromptRepository.class.getClassLoader();
        personalOverrides = normalizedOverrides(personal); projectOverrides = normalizedOverrides(project);
    }

    /** 中心模板只从已核对的内存全文覆盖内置资源，绝不扫描本机用户目录。 */
    public static PromptRepository central(Map<String, String> personal, Map<String, String> project) {
        return new PromptRepository(personal, project, true);
    }

    private static Map<String, String> normalizedOverrides(Map<String, String> values) {
        var result = new java.util.LinkedHashMap<String, String>();
        values.forEach((path, content) -> {
            String name = normalize(path);
            if (content == null || content.isBlank() || result.putIfAbsent(name, content) != null)
                throw new IllegalArgumentException("中心模板为空或路径重复");
        });
        return Map.copyOf(result);
    }

    public interface Scope extends AutoCloseable { @Override void close(); }
    public static Scope bindCentral(PromptRepository repository) {
        PromptRepository previous = CENTRAL.get(); CENTRAL.set(Objects.requireNonNull(repository));
        Thread owner = Thread.currentThread();
        return () -> {
            if (Thread.currentThread() != owner) throw new IllegalStateException("模板上下文必须由创建线程关闭");
            if (previous == null) CENTRAL.remove(); else CENTRAL.set(previous);
        };
    }

    public static PromptRepository centralOrBuiltin() {
        PromptRepository bound = CENTRAL.get();
        return bound == null ? new PromptRepository(null, null) : bound;
    }

    public static PromptRepository createDefault() {
        if (CENTRAL.get() != null) return CENTRAL.get();
        Path home = Path.of(System.getProperty("user.home"), ".paicli", "prompts");
        Path project = Path.of(".paicli", "prompts").toAbsolutePath().normalize();
        return new PromptRepository(home, project);
    }

    public String loadRequired(String relativePath) {
        String normalized = normalize(relativePath);
        String content = loadBuiltin(normalized);
        content = overrideIfPresent(userPromptsDir, normalized, content);
        content = overrideIfPresent(projectPromptsDir, normalized, content);
        content = personalOverrides.getOrDefault(normalized, content);
        content = projectOverrides.getOrDefault(normalized, content);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Prompt resource missing: " + normalized);
        }
        return content.trim();
    }

    private String loadBuiltin(String relativePath) {
        try (InputStream in = classLoader.getResourceAsStream(RESOURCE_PREFIX + relativePath)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + relativePath, e);
        }
    }

    private String overrideIfPresent(Path root, String relativePath, String fallback) {
        if (root == null) {
            return fallback;
        }
        Path override = root.resolve(relativePath).normalize();
        if (!override.startsWith(root.normalize()) || !Files.isRegularFile(override)) {
            return fallback;
        }
        try {
            return Files.readString(override, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt override: " + override, e);
        }
    }

    private static String normalize(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath is blank");
        }
        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid prompt path: " + relativePath);
        }
        return normalized;
    }
}
