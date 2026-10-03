package com.ruoyi.aden.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 直接消费 contracts/aden 的 Schema 与 example，防止 Java 边界映射静默漂移。 */
class AdenConsumerContractTest {
    private static final Path CONTRACT_ROOT = locateContractRoot();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void canonicalInt64AgreesWithSchemaAndKeepsPrecision() throws Exception {
        Map<String, Object> commonSchema = readObject("schemas/current/common.schema.json");
        Map<String, Object> definitions = object(commonSchema.get("$defs"));
        Map<String, Object> int64Schema = object(definitions.get("CanonicalInt64String"));
        Pattern schemaPattern = Pattern.compile((String) int64Schema.get("pattern"));

        List<String> accepted = List.of(
                "0",
                "9007199254740991",
                "9007199254740992",
                "9223372036854775807");
        for (String wireValue : accepted) {
            assertTrue(schemaPattern.matcher(wireValue).matches(), () -> "Schema 应接受 " + wireValue);
            CanonicalInt64 parsed = CanonicalInt64.parseWireValue(wireValue);
            assertEquals(wireValue, parsed.toWireValue());
        }

        List<String> rejected = List.of(
                "-1",
                "+1",
                "01",
                "1.0",
                "1e3",
                "9223372036854775808");
        for (String wireValue : rejected) {
            assertTrue(!schemaPattern.matcher(wireValue).matches(), () -> "Schema 应拒绝 " + wireValue);
            assertThrows(IllegalArgumentException.class, () -> CanonicalInt64.parseWireValue(wireValue));
        }

        Map<String, Object> numberExample = readObject("examples/invalid/event-int64-as-number.json");
        Object lossyJsonNumber = numberExample.get("aggregateVersion");
        assertInstanceOf(Number.class, lossyJsonNumber);
        assertThrows(IllegalArgumentException.class, () -> CanonicalInt64.parseWireValue(lossyJsonNumber));

        CanonicalInt64 belowBoundary = CanonicalInt64.parse("9007199254740991");
        CanonicalInt64 boundary = CanonicalInt64.parse("9007199254740992");
        CanonicalInt64 max = CanonicalInt64.parse("9223372036854775807");
        assertTrue(belowBoundary.compareTo(boundary) < 0);
        assertTrue(boundary.compareTo(max) < 0);
        assertEquals(Long.MAX_VALUE, max.longValue());
    }

    @Test
    void currentExamplesExposeWorkspaceAndStringWatermarks() throws Exception {
        Map<String, Object> event = readObject("examples/current/operator/event-2pow53.json");
        String workspaceId = assertInstanceOf(String.class, event.get("workspaceId"));
        assertDoesNotThrow(() -> UUID.fromString(workspaceId));
        assertEquals("11111111-1111-4111-8111-111111111111", workspaceId);
        assertEquals("9007199254740992",
                CanonicalInt64.parseWireValue(event.get("aggregateVersion")).toWireValue());
        assertEquals("9007199254740992",
                CanonicalInt64.parseWireValue(event.get("sequence")).toWireValue());

        Map<String, Object> bootstrap = readObject(
                "examples/current/operator/bootstrap-long-max-watermark.json");
        assertEquals("9223372036854775807",
                CanonicalInt64.parseWireValue(bootstrap.get("streamWatermark")).toWireValue());
        Map<String, Object> workspace = object(bootstrap.get("workspace"));
        assertEquals("9007199254740991",
                CanonicalInt64.parseWireValue(workspace.get("version")).toWireValue());
    }

    @Test
    void collectionMissingConfirmationIsScopedToEachSection() throws Exception {
        Map<String, Object> document = readObject("collection-v1.openapi.json");
        Map<String, Object> schemas = object(object(document.get("components")).get("schemas"));
        Map<String, Object> aggregateProperties = object(object(schemas.get("Extraction")).get("properties"));
        assertTrue(!aggregateProperties.containsKey("missingConfirmed"), "总载荷不能替代逐区确认");
        Map<String, Object> section = object(schemas.get("ExtractionSection"));
        Map<String, Object> properties = object(section.get("properties"));
        assertEquals("boolean", object(properties.get("missingConfirmed")).get("type"));
        Map<String, Object> condition = object(list(section.get("allOf")).get(0));
        assertEquals(List.of("missingReason"), list(object(condition.get("if")).get("required")));
        Map<String, Object> confirmed = object(condition.get("then"));
        assertEquals(List.of("missingConfirmed"), list(confirmed.get("required")));
        assertEquals(true, object(object(confirmed.get("properties")).get("missingConfirmed")).get("const"));
        assertEquals(false, object(object(object(condition.get("else")).get("properties")).get("missingConfirmed")).get("const"));
    }

    @Test
    void publicCommandConsumerMatchesSchemaAndRejectsInternalCommands() throws Exception {
        Set<String> schemaCommands = operatorCommandsFromSchema();
        Set<String> consumerCommands = Arrays.stream(OperatorTaskCommand.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(Set.of("SUBMIT_FOR_VALIDATION", "REQUEST_CANCEL"), schemaCommands);
        assertEquals(schemaCommands, consumerCommands);

        Map<String, Object> submit = readObject("examples/current/operator/submit-command-request.json");
        Map<String, Object> cancel = readObject("examples/current/operator/cancel-command-request.json");
        assertEquals(OperatorTaskCommand.SUBMIT_FOR_VALIDATION,
                OperatorTaskCommand.parseWireValue(submit.get("command")));
        assertEquals(OperatorTaskCommand.REQUEST_CANCEL,
                OperatorTaskCommand.parseWireValue(cancel.get("command")));

        Map<String, Object> internal = readObject("examples/invalid/operator-internal-command.json");
        assertThrows(IllegalArgumentException.class,
                () -> OperatorTaskCommand.parseWireValue(internal.get("command")));
        for (String internalCommand : List.of(
                "VALIDATION_PASSED", "VALIDATION_FAILED", "START", "COMPLETE", "FAIL",
                "WAIT_FOR_USER", "WAIT_FOR_EXTERNAL", "RESUME", "CONFIRM_CANCELED")) {
            assertThrows(IllegalArgumentException.class,
                    () -> OperatorTaskCommand.parseWireValue(internalCommand));
        }
    }

    private static Set<String> operatorCommandsFromSchema() throws Exception {
        Map<String, Object> operatorSchema = readObject("schemas/current/operator.schema.json");
        Map<String, Object> definitions = object(operatorSchema.get("$defs"));
        Map<String, Object> commandRequest = object(definitions.get("OperatorTaskCommandRequest"));
        List<?> alternatives = list(commandRequest.get("oneOf"));
        LinkedHashSet<String> commands = new LinkedHashSet<>();
        for (Object alternative : alternatives) {
            Map<String, Object> properties = object(object(alternative).get("properties"));
            Map<String, Object> command = object(properties.get("command"));
            commands.add(assertInstanceOf(String.class, command.get("const")));
        }
        return Set.copyOf(commands);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("预期 JSON object，实际为 " + value);
        }
        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?> result)) {
            throw new IllegalArgumentException("预期 JSON array，实际为 " + value);
        }
        return result;
    }

    private static Map<String, Object> readObject(String relativePath) throws IOException, JacksonException {
        byte[] bytes = Files.readAllBytes(CONTRACT_ROOT.resolve(relativePath));
        return object(OBJECT_MAPPER.readValue(bytes, Map.class));
    }

    private static Path locateContractRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            Path contractRoot = candidate.resolve("contracts/aden");
            if (Files.isRegularFile(contractRoot.resolve("schemas/current/common.schema.json"))) {
                return contractRoot;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("找不到 contracts/aden 契约目录");
    }
}
