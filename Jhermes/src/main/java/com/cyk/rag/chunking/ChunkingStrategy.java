package com.cyk.rag.chunking;

import java.util.List;
import java.util.Map;

/**
 * 文本切分策略接口。
 */
public interface ChunkingStrategy {
    /**
     * 将文本切分为块。
     * @param text 解析后的纯文本
     * @param sourcePath 源文件路径
     * @param baseMetadata 解析器提供的元数据（合并到每个 chunk）
     * @return 文本块列表，按原始顺序排列
     */
    List<Chunk> chunk(String text, String sourcePath, Map<String, Object> baseMetadata);
}
