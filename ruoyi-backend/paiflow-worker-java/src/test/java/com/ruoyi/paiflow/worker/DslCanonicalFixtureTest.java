package com.ruoyi.paiflow.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.paiflow.worker.service.DslSnapshotVerifier;
import com.ruoyi.paiflow.worker.service.WorkerException;
import com.ruoyi.paiflow.worker.service.JsonDslSnapshotVerifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DslCanonicalFixtureTest {
    @Test
    void originalCenterBytesAndJsonObjectAgreeAcrossNumberAndUnicodeBoundaries() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path basedir = Path.of(System.getProperty("basedir", "."));
        JsonNode fixture = mapper.readTree(basedir.resolve("../../contracts/paiflow/fixtures/dsl-canonical.json").toFile());
        byte[] canonical = DslSnapshotVerifier.verify(fixture.path("dslCanonicalBase64").asText(), fixture.path("dslHash").asText());
        assertEquals(fixture.get("dsl"), mapper.readTree(canonical));
        JsonDslSnapshotVerifier.verify(fixture.path("dslCanonicalBase64").asText(), fixture.path("dslHash").asText(), fixture.get("dsl"), mapper);
        JsonNode tamperedObject = fixture.get("dsl").deepCopy();
        ((ObjectNode) tamperedObject.get("nodes").get(0).get("data")).put("tiny", 2.0E-7);
        assertFalse(tamperedObject.equals(mapper.readTree(canonical)));
        assertThrows(WorkerException.class, () -> JsonDslSnapshotVerifier.verify(fixture.path("dslCanonicalBase64").asText(),
                fixture.path("dslHash").asText(), tamperedObject, mapper));
        assertThrows(WorkerException.class, () -> DslSnapshotVerifier.verify(fixture.path("dslCanonicalBase64").asText(), "0".repeat(64)));
    }
}
