package com.fachat.agent.service;

import com.fachat.agent.dto.ActiveFileResponse;
import com.fachat.agent.dto.AuthenticatedUser;
import com.fachat.agent.model.UploadedFileEntity;
import com.fachat.agent.repository.UploadedFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class UploadedFileService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REPLACED = "REPLACED";
    private static final String STATUS_REMOVED = "REMOVED";

    private final UploadedFileRepository uploadedFileRepository;
    private final FileContextService fileContextService;

    public UploadedFileService(
        UploadedFileRepository uploadedFileRepository,
        FileContextService fileContextService
    ) {
        this.uploadedFileRepository = uploadedFileRepository;
        this.fileContextService = fileContextService;
    }

    @Transactional(readOnly = true)
    public FileContextService.FileContext resolveContext(
        AuthenticatedUser user,
        String conversationId,
        String requestedFileContextId
    ) {
        if (user == null || conversationId == null || conversationId.isBlank()) {
            return emptyContext();
        }

        Optional<UploadedFileEntity> entity = requestedFileContextId == null || requestedFileContextId.isBlank()
            ? uploadedFileRepository.findFirstByUserIdAndConversationIdAndStatusOrderByCreatedAtDesc(
                user.id(),
                conversationId,
                STATUS_ACTIVE
            )
            : uploadedFileRepository.findByIdAndUserIdAndConversationIdAndStatus(
                requestedFileContextId,
                user.id(),
                conversationId,
                STATUS_ACTIVE
            );

        return entity
            .map(file -> fileContextService.fromStored(
                file.getSafeFilename(),
                file.getContentType(),
                file.getSizeBytes(),
                file.getExtractedText()
            ))
            .orElseGet(this::emptyContext);
    }

    @Transactional(readOnly = true)
    public ActiveFileResponse activeFileFor(AuthenticatedUser user, String conversationId) {
        if (user == null || conversationId == null || conversationId.isBlank()) {
            return null;
        }

        return uploadedFileRepository.findFirstByUserIdAndConversationIdAndStatusOrderByCreatedAtDesc(
                user.id(),
                conversationId,
                STATUS_ACTIVE
            )
            .map(this::toResponse)
            .orElse(null);
    }

    @Transactional
    public ActiveFileResponse saveActive(
        AuthenticatedUser user,
        String conversationId,
        FileContextService.FileContext fileContext
    ) {
        if (user == null || conversationId == null || conversationId.isBlank() || !fileContext.hasExtractedText()) {
            return activeFileFor(user, conversationId);
        }

        uploadedFileRepository.findByUserIdAndConversationIdAndStatus(user.id(), conversationId, STATUS_ACTIVE)
            .forEach(file -> {
                file.setStatus(STATUS_REPLACED);
                uploadedFileRepository.save(file);
            });

        UploadedFileEntity entity = new UploadedFileEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(user.id());
        entity.setConversationId(conversationId);
        entity.setOriginalFilename(safeFilename(fileContext.fileName()));
        entity.setSafeFilename(safeFilename(fileContext.fileName()));
        entity.setContentType(fileContext.contentType());
        entity.setSizeBytes(fileContext.size());
        entity.setExtractedText(fileContext.extractedText());
        entity.setCreatedAt(Instant.now());
        entity.setStatus(STATUS_ACTIVE);

        return toResponse(uploadedFileRepository.save(entity));
    }

    @Transactional
    public void removeActive(AuthenticatedUser user, String conversationId) {
        if (user == null || conversationId == null || conversationId.isBlank()) {
            return;
        }

        uploadedFileRepository.findByUserIdAndConversationIdAndStatus(user.id(), conversationId, STATUS_ACTIVE)
            .forEach(file -> {
                file.setStatus(STATUS_REMOVED);
                uploadedFileRepository.save(file);
            });
    }

    private ActiveFileResponse toResponse(UploadedFileEntity entity) {
        return new ActiveFileResponse(
            entity.getId(),
            entity.getSafeFilename(),
            entity.getContentType(),
            entity.getSizeBytes()
        );
    }

    private FileContextService.FileContext emptyContext() {
        return fileContextService.fromStored(null, null, 0, null);
    }

    private String safeFilename(String value) {
        String normalized = Normalizer.normalize(value == null ? "arquivo.txt" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .replaceAll("[^a-zA-Z0-9._-]+", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-+|-+$", "")
            .toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "arquivo.txt";
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }
}
