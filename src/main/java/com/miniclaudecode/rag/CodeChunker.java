package com.miniclaudecode.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码分块器：将代码文件切分为适合 Embedding 的粒度
 * <p>
 * 策略：
 * - 非 Java 文件：整个文件作为一个 chunk
 * - Java 文件：类级别 + 方法级别分块（大方法单独成块）
 */
public class CodeChunker {
    // 2000 字符约占 4000–6000 个中文 token，适配最小上下文模型
    private static final int MAX_CHUNK_CHARS = 2000;
    // JavaParser 与项目语言级别保持 Java 17 一致
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    /**
     * 对单个文件进行分块
     */
    public List<CodeChunk> chunkFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String relativePath = filePath.toString();

        if (!relativePath.endsWith(".java")) {
            return chunkLargeText(relativePath, content);
        }

        return chunkJavaFile(filePath, content);
    }

    private List<CodeChunk> chunkLargeText(String filePath, String content) {
        if (content.length() <= MAX_CHUNK_CHARS) {
            return List.of(CodeChunk.fileChunk(filePath, content));
        }

        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\r?\n");
        StringBuilder segment = new StringBuilder();
        int segIndex = 1;
        int startLine = 1;

        for (int i = 0; i < lines.length; i++) {
            if (segment.length() + lines[i].length() + 1 > MAX_CHUNK_CHARS && !segment.isEmpty()) {
                chunks.add(new CodeChunk(filePath, "file",
                        filePath + "#" + segIndex, segment.toString().trim(), startLine, i));
                segment.setLength(0);
                segIndex++;
                startLine = i + 1;
            }
            segment.append(lines[i]).append("\n");
        }

        if (!segment.isEmpty()) {
            chunks.add(new CodeChunk(filePath, "file",
                    filePath + "#" + segIndex, segment.toString().trim(), startLine, lines.length));
        }

        return chunks;
    }

    private List<CodeChunk> chunkJavaFile(Path filePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        ParseResult<CompilationUnit> result = parser.parse(content);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            // AST 不可用时保留文本检索能力
            return chunkLargeText(filePath.toString(), content);
        }

        CompilationUnit cu = result.getResult().get();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            int classStart = clazz.getBegin().map(p -> p.line).orElse(0);
            int classEnd = clazz.getEnd().map(p -> p.line).orElse(0);
            String className = clazz.getNameAsString();

            String classHeader = extractLines(content, classStart, Math.min(classStart + 5, classEnd));

            chunks.add(CodeChunk.classChunk(
                    filePath.toString(), className,
                    classHeader, classStart, classEnd));

            clazz.getMethods().forEach(method -> {
                int methodStart = method.getBegin().map(p -> p.line).orElse(0);
                int methodEnd = method.getEnd().map(p -> p.line).orElse(0);
                String methodSignature = method.getDeclarationAsString(false, false, false);
                String methodContent = extractLines(content, methodStart, methodEnd);

                chunks.add(CodeChunk.methodChunk(
                        filePath.toString(),
                        className + "." + methodSignature,
                        methodContent, methodStart, methodEnd));
            });
        });

        // AST 无类型声明时保留文本检索能力
        if (chunks.isEmpty()) {
            return chunkLargeText(filePath.toString(), content);
        }

        return chunks;
    }

    private String extractLines(String content, int startLine, int endLine) {
        String[] lines = content.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < Math.min(endLine, lines.length); i++) {
            if (i >= 0) {
                sb.append(lines[i]).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
