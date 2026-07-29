package com.miniclaudecode.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeChunkerTest {

    private final CodeChunker chunker = new CodeChunker();

    @Test
    void testNonJavaFile() throws Exception {
        Path path = Paths.get("src/test/resources/rag/SampleService.java").toAbsolutePath();
        List<CodeChunk> chunks = chunker.chunkFile(path);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(c -> c.chunkType().equals("class")));
    }

    @Test
    void testJavaFileChunking() throws Exception {
        Path path = Paths.get("src/test/resources/rag/SampleService.java").toAbsolutePath();
        List<CodeChunk> chunks = chunker.chunkFile(path);

        assertFalse(chunks.isEmpty());

        assertTrue(chunks.stream().anyMatch(c ->
                c.chunkType().equals("class") && c.name().equals("SampleService")));

        assertTrue(chunks.stream().anyMatch(c ->
                c.chunkType().equals("method") && c.name().contains("findUserById")));
        assertTrue(chunks.stream().anyMatch(c ->
                c.chunkType().equals("method") && c.name().contains("initialize")));
    }

    @Test
    void testEmbeddingTextFormat() {
        CodeChunk chunk = CodeChunk.classChunk("Test.java", "TestClass",
                "content here", 1, 1);
        String text = chunk.toEmbeddingText();
        assertTrue(text.contains("[class:TestClass]"));
        assertTrue(text.contains("content here"));
    }
}
