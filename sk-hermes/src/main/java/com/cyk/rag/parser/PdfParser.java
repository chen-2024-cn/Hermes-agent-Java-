package com.cyk.rag.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * PDF 解析器。使用 Apache PDFBox 逐页提取文本。
 */
public class PdfParser implements DocumentParser {

    private static final Logger logger = LoggerFactory.getLogger(PdfParser.class);

    @Override
    public String parse(Path filePath) throws IOException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException e) {
            logger.error("Failed to parse PDF: {}", filePath, e);
            throw e;
        }
    }

    @Override
    public Map<String, Object> extractMetadata(Path filePath, String fullText) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("file_name", filePath.getFileName().toString());

        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            meta.put("pages", document.getNumberOfPages());
            String title = document.getDocumentInformation().getTitle();
            if (title != null && !title.isBlank()) {
                meta.put("title", title);
            }
        } catch (IOException e) {
            logger.warn("Failed to extract PDF metadata: {}", filePath);
        }

        return meta;
    }
}
