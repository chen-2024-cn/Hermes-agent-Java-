package com.cyk.rag.chunking;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 固定大小切分策略。
 * 优先按句子边界（。！？\\n\\n）断句，超长句按字符硬断。
 */
public class FixedSizeChunking implements ChunkingStrategy {

    private static final double CHINESE_CHARS_PER_TOKEN = 1.5;
    private static final double ENGLISH_CHARS_PER_TOKEN = 4.0;

    private final int maxTokens;
    private final int overlapTokens;

    public FixedSizeChunking(int maxTokens, int overlapTokens) {
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    @Override
    public List<Chunk> chunk(String text, String sourcePath, Map<String, Object> baseMetadata) {
        List<String> sentences = splitSentences(text);
        List<Chunk> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentTokens = 0;
        int chunkIndex = 0;

        for (String sentence : sentences) {
            int sentenceTokens = estimateTokens(sentence);

            // 超长单句按字符硬断
            if (sentenceTokens > maxTokens) {
                // 先 flush 当前 buffer
                if (!current.isEmpty()) {
                    chunks.add(buildChunk(current, sourcePath, chunkIndex++, baseMetadata));
                    current.clear();
                    currentTokens = 0;
                }
                // 将句子按字符切分为不超过 maxTokens 的段
                for (String part : splitByCharCount(sentence, maxTokens)) {
                    chunks.add(buildChunk(List.of(part), sourcePath, chunkIndex++, baseMetadata));
                }
                continue;
            }

            if (currentTokens + sentenceTokens > maxTokens && !current.isEmpty()) {
                // 当前 Chunk 已满，生成一个
                chunks.add(buildChunk(current, sourcePath, chunkIndex++, baseMetadata));

                // 保留 overlap：从当前句子开始回退
                List<String> overlap = buildOverlap(current, sentence);
                current = new ArrayList<>(overlap);
                currentTokens = estimateTokens(String.join("", overlap));
            }

            current.add(sentence);
            currentTokens += sentenceTokens;
        }

        // 最后一个 Chunk
        if (!current.isEmpty()) {
            chunks.add(buildChunk(current, sourcePath, chunkIndex, baseMetadata));
        }

        return chunks;
    }

    private List<String> buildOverlap(List<String> previousChunk, String currentSentence) {
        List<String> overlap = new ArrayList<>();
        int targetTokens = overlapTokens;
        int accumulated = 0;
        for (int i = previousChunk.size() - 1; i >= 0 && accumulated < targetTokens; i--) {
            String s = previousChunk.get(i);
            overlap.add(0, s);
            accumulated += estimateTokens(s);
        }
        overlap.add(currentSentence);
        return overlap;
    }

    /**
     * 将超长单句按字符数切分为多个子段，使单段不超过 maxTokens。
     */
    private List<String> splitByCharCount(String text, int targetTokens) {
        int charsPerChunk = Math.max(1, (int) (targetTokens * ENGLISH_CHARS_PER_TOKEN));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += charsPerChunk) {
            parts.add(text.substring(i, Math.min(i + charsPerChunk, text.length())));
        }
        return parts;
    }

    private Chunk buildChunk(List<String> sentences, String sourcePath, int chunkIndex,
                             Map<String, Object> baseMetadata) {
        String content = String.join("", sentences);
        int tokenCount = estimateTokens(content);
        Map<String, Object> meta = new HashMap<>(baseMetadata);
        return new Chunk(
            UUID.randomUUID().toString(),
            sourcePath,
            chunkIndex,
            content,
            tokenCount,
            meta
        );
    }

    /**
     * 按句子边界切分：。！？换行
     */
    List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("(?<=[。！？\\n])(?=\\S)");
        String[] parts = pattern.split(text);
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        if (result.isEmpty() && !text.isBlank()) {
            result.add(text);
        }
        return result;
    }

    /**
     * 估算 token 数：中文 ~1.5 字/token，英文 ~4 字符/token。
     */
    int estimateTokens(String text) {
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (int) Math.ceil(chineseChars / CHINESE_CHARS_PER_TOKEN + otherChars / ENGLISH_CHARS_PER_TOKEN);
    }
}
