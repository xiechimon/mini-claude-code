package com.miniclaudecode.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAnalyzerTest {

    private final CodeAnalyzer analyzer = new CodeAnalyzer();

    @Test
    void testAnalyzeSampleService() throws Exception {
        Path path = Paths.get("src/test/resources/rag/SampleService.java").toAbsolutePath();
        List<CodeRelation> relations = analyzer.analyzeFile(path);

        assertFalse(relations.isEmpty());

        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("extends") && r.fromName().equals("SampleService")
                        && r.toName().equals("BaseService")));

        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("implements") && r.fromName().equals("SampleService")
                        && r.toName().equals("ServiceInterface")));

        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("contains") && r.fromName().equals("SampleService")));

        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("imports") && r.fromName().equals("file")));
    }
}
