package com.rag.eval.service;

import com.rag.eval.model.ChunkConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class DocumentParserService {

    private static final Pattern CHAPTER_PAT = Pattern.compile("^第([一二三四五六七八九十百]+)章\\s*(.*)");
    private static final Pattern SECTION_PAT = Pattern.compile("^第([一二三四五六七八九十百]+)节\\s*(.*)");

    private final Tika tika = new Tika();
    private final boolean ocrEnabled;
    private final String ocrLanguages;
    private final int ocrDpi;
    private final int ocrTimeoutSeconds;

    public DocumentParserService(@Value("${ocr.enabled:true}") boolean ocrEnabled,
                                 @Value("${ocr.languages:chi_sim+eng}") String ocrLanguages,
                                 @Value("${ocr.dpi:300}") int ocrDpi,
                                 @Value("${ocr.timeout-seconds:120}") int ocrTimeoutSeconds) {
        this.ocrEnabled = ocrEnabled;
        this.ocrLanguages = ocrLanguages;
        this.ocrDpi = ocrDpi;
        this.ocrTimeoutSeconds = ocrTimeoutSeconds;
    }

    public record ParsedDocument(String text, String sourceType) {}

    public ParsedDocument parse(InputStream inputStream, String fileName) throws Exception {
        byte[] bytes = inputStream.readAllBytes();
        String text;
        String sourceType = "digital";

        if (isPdf(fileName)) {
            text = extractPdfText(bytes);
            if (text.isBlank()) {
                sourceType = "scanned";
                if (ocrEnabled) {
                    text = ocrPdf(bytes);
                }
            }
        } else {
            text = tika.parseToString(new ByteArrayInputStream(bytes));
            if (text == null) text = "";
        }
        return new ParsedDocument(text, sourceType);
    }

    private String extractPdfText(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private boolean isPdf(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    private String ocrPdf(byte[] pdfBytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        Path tmpDir = Files.createTempDirectory("ocr-pages");
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = doc.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, ocrDpi);
                Path png = tmpDir.resolve("page-" + i + ".png");
                ImageIO.write(image, "png", png.toFile());
                sb.append(runTesseract(png)).append('\n');
            }
        } finally {
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        return sb.toString();
    }

    private String runTesseract(Path image) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("tesseract", image.toString(), "stdout", "-l", ocrLanguages);
        Process process = pb.start();
        if (!process.waitFor(ocrTimeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new Exception("OCR 超时（tesseract 超过 " + ocrTimeoutSeconds + "s，语言 " + ocrLanguages + "）");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new Exception("tesseract 退出码 " + process.exitValue() + ": " + err.trim());
        }
        return output;
    }

    public List<ChunkData> splitAndEnrich(String text, String fileName, String sourceType) {
        return splitAndEnrich(text, fileName, sourceType, ChunkConfig.defaults());
    }

    public List<ChunkData> splitAndEnrich(String text, String fileName, String sourceType, ChunkConfig config) {
        List<String> rawChunks = config.isDelimiterMode()
            ? splitByDelimiter(text, config.delimiter())
            : splitText(text, config.chunkSize(), config.overlap());
        List<ChunkData> result = new ArrayList<>();

        for (int i = 0; i < rawChunks.size(); i++) {
            String content = rawChunks.get(i);
            if (content.isBlank()) continue;

            String chapter = extractChapter(content);
            String section = extractSection(content);
            String language = detectLanguage(content);

            result.add(ChunkData.builder()
                .chunkId(UUID.randomUUID().toString())
                .fileName(fileName)
                .sourceType(sourceType)
                .language(language)
                .chapter(chapter)
                .section(section)
                .chunkIndex(i)
                .content(content)
                .build());
        }
        return result;
    }

    private List<String> splitByDelimiter(String text, String delimiter) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty() || delimiter == null || delimiter.isEmpty()) return chunks;

        String[] parts = text.split(Pattern.quote(delimiter), -1);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) chunks.add(trimmed);
        }
        return chunks;
    }

    private List<String> splitText(String text, int maxChars, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        if (maxChars <= 0) maxChars = ChunkConfig.DEFAULT_CHUNK_SIZE;
        if (overlap < 0) overlap = 0;
        if (overlap >= maxChars) overlap = maxChars - 1;

        // Simple character-based splitting with paragraph boundary awareness
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());

            // Try to break at paragraph boundary
            if (end < text.length()) {
                int paraBreak = text.lastIndexOf("\n\n", end);
                if (paraBreak > start + maxChars / 2) {
                    end = paraBreak + 2;
                } else {
                    int singleBreak = text.lastIndexOf("\n", end);
                    if (singleBreak > start + maxChars / 2) {
                        end = singleBreak + 1;
                    } else {
                        int period = text.lastIndexOf("。", end);
                        if (period > start + maxChars / 2) end = period + 1;
                    }
                }
            }

            chunks.add(text.substring(start, end).trim());
            if (end >= text.length()) break;
            start = end - overlap;
        }
        return chunks;
    }

    private String extractChapter(String text) {
        var m = CHAPTER_PAT.matcher(text);
        return m.find() ? "第" + m.group(1) + "章 " + m.group(2) : null;
    }

    private String extractSection(String text) {
        var m = SECTION_PAT.matcher(text);
        return m.find() ? "第" + m.group(1) + "节 " + m.group(2) : null;
    }

    private String detectLanguage(String text) {
        boolean hasCN = text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        boolean hasEN = text.chars().anyMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));
        if (hasCN && hasEN) return "mixed";
        if (hasCN) return "zh";
        return "en";
    }
}
