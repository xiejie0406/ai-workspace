package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 项目创建拒绝必须在落盘前发生，不能覆盖用户已有模板。 */
class CreateProjectValidationTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    private ToolOutput create(String name, String type) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        return registry.executeToolOutput("create_project", json.writeValueAsString(Map.of("name", name, "type", type)));
    }

    @Test
    void unsupportedTypeLeavesNoDirectory() throws Exception {
        ToolOutput result = create("unknown-project", "unknown");
        assertFalse(result.successful(), result.text());
        assertFalse(Files.exists(root.resolve("unknown-project")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{\"name\":\"safe\"}", "{\"type\":\"java\"}",
            "{\"name\":null,\"type\":\"java\"}", "{\"name\":1,\"type\":\"java\"}",
            "{\"name\":true,\"type\":\"java\"}", "{\"name\":[],\"type\":\"java\"}",
            "{\"name\":{},\"type\":\"java\"}", "{\"name\":\"safe\",\"type\":null}",
            "{\"name\":\"safe\",\"type\":1}", "{\"name\":\"safe\",\"type\":false}",
            "{\"name\":\"safe\",\"type\":[]}", "{\"name\":\"safe\",\"type\":{}}"
    })
    void missingAndNonTextParametersLeaveNoDirectory(String arguments) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        assertFalse(registry.executeToolOutput("create_project", arguments).successful());
        try (var children = Files.list(root)) { assertEquals(0, children.count()); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "../outside", "nested/project", "nested\\project", ".", "..", "invalid\"name", "invalid<name"})
    void invalidNamesLeaveRootUnchanged(String name) throws Exception {
        ToolOutput result = create(name, "java");
        assertFalse(result.successful(), result.text());
        try (var children = Files.list(root)) { assertEquals(0, children.count()); }
    }

    @Test
    void absolutePathIsRejected() throws Exception {
        assertFalse(create(root.resolve("absolute").toString(), "java").successful());
        assertFalse(Files.exists(root.resolve("absolute")));
    }

    @Test
    void existingDirectoryAndTemplatesAreNeverOverwritten() throws Exception {
        Path existing = Files.createDirectory(root.resolve("existing"));
        Path template = existing.resolve("pom.xml");
        byte[] sentinel = "USER_TEMPLATE\r\n中文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(template, sentinel);
        assertFalse(create("existing", "java").successful());
        assertArrayEquals(sentinel, Files.readAllBytes(template));
        assertFalse(Files.exists(existing.resolve("src")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "python", "node"})
    void supportedTemplatesContainExpectedFiles(String type) throws Exception {
        assertTrue(create("sample", type).successful());
        Path project = root.resolve("sample");
        switch (type) {
            case "java" -> {
                assertTrue(Files.isDirectory(project.resolve("src/main/java")));
                assertTrue(Files.isDirectory(project.resolve("src/main/resources")));
                assertTrue(Files.readString(project.resolve("pom.xml")).contains("<artifactId>sample</artifactId>"));
            }
            case "python" -> {
                assertTrue(Files.isDirectory(project.resolve("sample")));
                assertEquals("# 主程序入口\n", Files.readString(project.resolve("main.py")));
                assertEquals("# 依赖列表\n", Files.readString(project.resolve("requirements.txt")));
            }
            case "node" -> assertEquals("sample", json.readTree(Files.readString(project.resolve("package.json"))).path("name").asText());
        }
    }
}
