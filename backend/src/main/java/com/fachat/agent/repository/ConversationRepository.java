package com.fachat.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fachat.agent.model.ConversationEntity;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {
    List<ConversationEntity> findByExternalUserIdOrderByUpdatedAtDesc(String externalUserId);
    Optional<ConversationEntity> findByIdAndExternalUserId(String id, String externalUserId);
}
