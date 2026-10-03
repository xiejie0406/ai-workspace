package com.paicli.prompt;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CentralPromptRepositoryTest {
    @Test void projectOverridesPersonalAndMissingTemplateUsesBundledResource() {
        var repository = PromptRepository.central(Map.of("personalities/calm.md", "personal"), Map.of("personalities/calm.md", "project"));
        assertEquals("project", repository.loadRequired("personalities/calm.md"));
        assertFalse(repository.loadRequired("base.md").isBlank());
    }
    @Test void scopedRepositoryIsInheritedByWorkersAndRestoresAfterClose() throws Exception {
        var repository = PromptRepository.central(Map.of(), Map.of("personalities/calm.md", "approved center"));
        AtomicReference<String> worker = new AtomicReference<>();
        try (var scope = PromptRepository.bindCentral(repository)) {
            assertSame(repository, PromptRepository.createDefault());
            Thread child = new Thread(() -> worker.set(PromptRepository.createDefault().loadRequired("personalities/calm.md")));
            child.start(); child.join();
        }
        assertEquals("approved center", worker.get()); assertNotSame(repository, PromptRepository.createDefault());
    }
    @Test void traversalAndBlankOverridesAreRejectedBeforeUse() {
        assertThrows(IllegalArgumentException.class, () -> PromptRepository.central(Map.of("../base.md", "bad"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> PromptRepository.central(Map.of(), Map.of("base.md", " ")));
    }
}
