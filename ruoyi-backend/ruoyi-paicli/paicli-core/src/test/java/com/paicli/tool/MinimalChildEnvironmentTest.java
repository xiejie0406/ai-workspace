package com.paicli.tool;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MinimalChildEnvironmentTest {
    @Test void javaChildReceivesOnlySystemPathsAndExplicitApprovedEnvironment() throws Exception {
        Map<String, String> inherited = new java.util.HashMap<>(System.getenv());
        inherited.put("PAICLI_RUNNER_TOKEN", "runner-secret"); inherited.put("USER_TOKEN", "user-secret");
        inherited.put("RUNTIME_API_KEY", "runtime-secret"); inherited.put("DEEPSEEK_API_KEY", "model-secret");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(), "-cp", System.getProperty("java.class.path"), MinimalChildEnvironmentTest.class.getName());
        builder.environment().clear(); builder.environment().putAll(ToolRegistry.minimalChildEnvironment(inherited, Map.of("APPROVED_VALUE", "explicit")));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), new String(process.getErrorStream().readAllBytes()));
            assertTrue(output.contains("APPROVED_VALUE=explicit"));
            assertFalse(output.contains("secret")); assertFalse(output.contains("PAICLI_RUNNER_TOKEN"));
        } finally { com.paicli.runtime.ProcessTree.terminate(process); }
        assertThrows(com.paicli.policy.PolicyException.class, () -> ToolRegistry.minimalChildEnvironment(inherited, Map.of("PAICLI_RUNNER_TOKEN", "explicit-leak")));
    }
    public static void main(String[] args) { System.getenv().forEach((key, value) -> System.out.println(key + "=" + value)); }
}
