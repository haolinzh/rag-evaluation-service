package com.rag.eval.service;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class DocumentParserService {

    private static final Pattern CHAPTER_PAT = Pattern.compile("^第([一二三四五六七八九十百]+)章\\s*(.*)");
    private static final Pattern SECTION_PAT = Pattern.compile("^第([一二三四五六七八九十百]+)节\\s*(.*)");
    private static final int CHUNK_SIZE = 500;
    private static final int OVERLAP = 50;

    private final Tika tika = new Tika();

    public String parse(InputStream inputStream) throws Exception {
        return tika.parseToString(inputStream);
    }

    public List<ChunkData> splitAndEnrich(String text, String fileName, String sourceType) {
        List<String> rawChunks = splitText(text, CHUNK_SIZE, OVERLAP);
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

    private List<String> splitText(String text, int maxChars, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

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
            start = end - overlap;
            if (start < 0) start = 0;
            if (start >= end) start = end; // prevent infinite loop
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
