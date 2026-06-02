package com.fachat.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@Service
public class FileContextService {

    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_CHARACTERS = 12_000;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "txt", "md", "csv", "tsv", "json", "xml", "yml", "yaml",
        "java", "kt", "py", "js", "jsx", "ts", "tsx", "sql",
        "html", "css", "scss", "log", "properties"
    );

    public record FileContext(
        String fileName,
        String contentType,
        long size,
        String extractedText
    ) {
        public boolean hasFile() {
            return fileName != null && !fileName.isBlank();
        }

        public boolean hasExtractedText() {
            return extractedText != null && !extractedText.isBlank();
        }
    }

    public FileContext fromStored(String fileName, String contentType, long size, String extractedText) {
        return new FileContext(fileName, contentType, size, extractedText);
    }

    public FileContext extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new FileContext(null, null, 0, null);
        }

        try {
            if (file.getSize() > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arquivo excede o limite de 256 KB.");
            }
            byte[] source = file.getBytes();
            int length = Math.min(source.length, MAX_BYTES);
            byte[] previewBytes = new byte[length];
            System.arraycopy(source, 0, previewBytes, 0, length);

            String extension = extensionOf(file.getOriginalFilename());
            boolean probablyText = TEXT_EXTENSIONS.contains(extension) || isProbablyText(previewBytes);
            if (!probablyText) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Apenas arquivos de texto ou codigo sao aceitos.");
            }

            String text = new String(previewBytes, StandardCharsets.UTF_8);
            String sanitized = text.length() > MAX_CHARACTERS
                ? text.substring(0, MAX_CHARACTERS) + "\n\n[Arquivo truncado para economizar contexto.]"
                : text;

            return new FileContext(file.getOriginalFilename(), file.getContentType(), file.getSize(), sanitized);
        } catch (Exception exception) {
            if (exception instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            return new FileContext(file.getOriginalFilename(), file.getContentType(), file.getSize(), null);
        }
    }

    private boolean isProbablyText(byte[] content) {
        int suspicious = 0;
        int inspected = Math.min(content.length, 2_048);
        for (int index = 0; index < inspected; index++) {
            int value = content[index] & 0xFF;
            if (value == 0) {
                return false;
            }
            if ((value < 9 || (value > 13 && value < 32)) && value != 27) {
                suspicious++;
            }
        }
        return suspicious < inspected * 0.08;
    }

    private String extensionOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
