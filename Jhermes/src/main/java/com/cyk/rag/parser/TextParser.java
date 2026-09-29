package com.cyk.rag.parser;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 纯文本解析器。自动检测 UTF-8 / GBK 编码。
 */
public class TextParser implements DocumentParser {

    @Override
    public String parse(Path filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        // 简单 UTF-8 有效性检测：无 BOM 且无乱码特征即视为 UTF-8
        if (isValidUtf8(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        // 回退到 GBK（中文 Windows 常见编码）
        return new String(bytes, Charset.forName("GBK"));
    }

    private boolean isValidUtf8(byte[] bytes) {
        try {
            String s = new String(bytes, StandardCharsets.UTF_8);
            // 来回编码一致 → UTF-8 有效
            return java.util.Arrays.equals(s.getBytes(StandardCharsets.UTF_8), bytes);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> extractMetadata(Path filePath, String fullText) {
        return Map.of("file_name", filePath.getFileName().toString());
    }
}
