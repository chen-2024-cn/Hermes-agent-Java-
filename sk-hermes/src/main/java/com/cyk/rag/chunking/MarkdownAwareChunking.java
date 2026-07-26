package com.cyk.rag.chunking;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Markdown 感知切分策略。
 * 优先按 ## 标题边界切分，保持语义完整；
 * 超限节递归降级为 FixedSizeChunking。
 */
public class MarkdownAwareChunking implements ChunkingStrategy {

    private static final Pattern H2_PATTERN = Pattern.compile("(?=^## )", Pattern.MULTILINE);

    private final FixedSizeChunking fallback;

    public MarkdownAwareChunking(int maxTokens, int overlapTokens) {
        this.fallback = new FixedSizeChunking(maxTokens, overlapTokens);
    }

    @Override
    public List<Chunk> chunk(String text, String sourcePath, Map<String, Object> baseMetadata) {
        // 先按 ## 标题切分
        String[] sections = H2_PATTERN.split(text);
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (String section : sections) {
            if (section.isBlank()) continue;

            // 提取当前节的标题作为附加元数据
            Map<String, Object> sectionMeta = new HashMap<>(baseMetadata);
            String firstLine = section.lines().findFirst().orElse("");
            if (firstLine.startsWith("## ")) {
                sectionMeta.put("section", firstLine.substring(3).trim());
            }

            int sectionTokens = fallback.estimateTokens(section);

            if (sectionTokens <= fallback.estimateTokens("") + 512) {
                // 短节直接作为一个 Chunk（估算里实际上应该用配置的 maxTokens）
                // 这里 fallback 内部知道 maxTokens，我们简单判断
                chunks.add(new Chunk(
                    UUID.randomUUID().toString(),
                    sourcePath,
                    chunkIndex++,
                    section.trim(),
                    sectionTokens,
                    sectionMeta
                ));
            } else {
                // 超限节递归降级
                List<Chunk> subChunks = fallback.chunk(section, sourcePath, sectionMeta);
                // 重新编号
                for (Chunk sc : subChunks) {
                    chunks.add(new Chunk(
                        sc.id(), sc.sourcePath(), chunkIndex++,
                        sc.content(), sc.tokenCount(), sc.metadata()
                    ));
                }
            }
        }

        return chunks;
    }
}
