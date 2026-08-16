package com.rag.eval.pipeline;

import com.rag.eval.service.ChunkData;
import com.rag.eval.service.DocumentParserService;
import com.rag.eval.service.IndexBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class PipelineRunner implements CommandLineRunner {

    private final DocumentParserService parser;
    private final IndexBuilder indexBuilder;

    public PipelineRunner(DocumentParserService parser, IndexBuilder indexBuilder) {
        this.parser = parser;
        this.indexBuilder = indexBuilder;
    }

    @Override
    public void run(String... args) {
        String pipelinePath = getArg(args, "--pipeline");
        if (pipelinePath == null) return;

        System.out.println("=== Starting data pipeline ===");
        System.out.println("Path: " + pipelinePath);

        try {
            Path dir = Paths.get(pipelinePath);
            List<Path> files = Files.list(dir)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        return name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".txt");
                    })
                    .sorted()
                    .toList();

            if (files.isEmpty()) {
                System.err.println("No PDF/DOCX/TXT files found in " + pipelinePath);
                return;
            }

            List<ChunkData> allChunks = new ArrayList<>();
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                System.out.println("Parsing: " + fileName);
                DocumentParserService.ParsedDocument parsed = parser.parse(Files.newInputStream(file), fileName);
                List<ChunkData> chunks = parser.splitAndEnrich(parsed.text(), fileName, parsed.sourceType());
                // Set chunk indices
                for (int i = 0; i < chunks.size(); i++) {
                    chunks.get(i).setChunkIndex(i);
                }
                allChunks.addAll(chunks);
                System.out.println("  -> " + chunks.size() + " chunks, type=" + parsed.sourceType());
            }

            System.out.println("Total chunks: " + allChunks.size());
            indexBuilder.buildIndex(allChunks);
            System.out.println("=== Pipeline complete ===");

            System.exit(0);
        } catch (Exception e) {
            System.err.println("Pipeline failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private String getArg(String[] args, String key) {
        for (String arg : args) {
            if (arg.startsWith(key + "=")) {
                return arg.substring(key.length() + 1);
            }
        }
        return null;
    }
}
