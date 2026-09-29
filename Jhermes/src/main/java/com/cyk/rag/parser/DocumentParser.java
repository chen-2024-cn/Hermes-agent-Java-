package com.cyk.rag.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文档解析器接口。
 * 将不同格式的文档转为纯文本，并提取元数据。
 */
public interface DocumentParser {

    /**
     * 解析文件为纯文本。
     * @return 解析后的文本；失败抛 IOException
     */
    String parse(Path filePath) throws IOException;

    /**
     * 从解析后的文本中提取元数据。
     * 默认返回空 Map。
     */
    default Map<String, Object> extractMetadata(Path filePath, String fullText) {
        return Map.of();
    }
}
