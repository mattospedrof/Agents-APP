package com.fachat.agent.repository;

import com.fachat.agent.model.UploadedFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UploadedFileRepository extends JpaRepository<UploadedFileEntity, String> {
    Optional<UploadedFileEntity> findByIdAndUserIdAndConversationIdAndStatus(
        String id,
        String userId,
        String conversationId,
        String status
    );

    Optional<UploadedFileEntity> findFirstByUserIdAndConversationIdAndStatusOrderByCreatedAtDesc(
        String userId,
        String conversationId,
        String status
    );

    List<UploadedFileEntity> findByUserIdAndConversationIdAndStatus(
        String userId,
        String conversationId,
        String status
    );
}
