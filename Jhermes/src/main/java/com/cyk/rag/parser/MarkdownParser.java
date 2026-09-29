package com.cyk.rag.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Markdown 解析器。
 * 剥离 YAML front matter，提取标题层级作为 section 元数据。
 */
public class MarkdownParser implements DocumentParser {

    @Override
    public String parse(Path filePath) throws IOException {
        String content = Files.readString(filePath);

        // 剥离 YAML front matter（--- ... ---）
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                content = content.substring(end + 3).trim();
            }
        }

        return content;
    }

    @Override
    public Map<String, Object> extractMetadata(Path filePath, String fullText) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("file_name", filePath.getFileName().toString());

        // 提取第一个 H1 标题作为文档标题
        for (String line : fullText.lines().toList()) {
            if (line.startsWith("# ") && !line.startsWith("## ")) {
                meta.put("title", line.substring(2).trim());
                break;
            }
        }

        return meta;
    }
}
