package com.rag.eval.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Path uploadDir;

    public FileStorageService(@Value("${storage.upload-dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir);
    }

    public String store(String originalName, byte[] bytes) throws IOException {
        Files.createDirectories(uploadDir);
        String storedName = UUID.randomUUID() + sanitizeExtension(originalName);
        Files.write(resolve(storedName), bytes);
        return storedName;
    }

    public byte[] load(String storedName) throws IOException {
        if (storedName == null || storedName.isBlank()) return null;
        Path path = resolve(storedName);
        if (!Files.exists(path)) return null;
        return Files.readAllBytes(path);
    }

    public void delete(String storedName) {
        if (storedName == null || storedName.isBlank()) return;
        try {
            Files.deleteIfExists(resolve(storedName));
        } catch (IOException ignored) {
        }
    }

    private Path resolve(String storedName) {
        Path path = Path.of(storedName);
        if (path.getParent() != null) {
            throw new IllegalArgumentException("Invalid stored file name: " + storedName);
        }
        return uploadDir.resolve(path);
    }

    private String sanitizeExtension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i < 0) return "";
        String ext = name.substring(i).toLowerCase().replaceAll("[^a-z0-9.]", "");
        return ext.length() > 10 ? ext.substring(0, 10) : ext;
    }
}
