package com.fachat.agent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fachat.agent.config.AgentProperties;
import com.fachat.agent.dto.AssistantDocument;
import com.fachat.agent.dto.AuthenticatedUser;
import com.fachat.agent.dto.ChatMessage;
import com.fachat.agent.dto.ConversationDetailResponse;
import com.fachat.agent.dto.ConversationSummaryResponse;
import com.fachat.agent.model.ConversationEntity;
import com.fachat.agent.model.ConversationMessageEntity;
import com.fachat.agent.repository.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final UploadedFileService uploadedFileService;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    public ConversationService(
        ConversationRepository conversationRepository,
        UploadedFileService uploadedFileService,
        AgentProperties properties
    ) {
        this.conversationRepository = conversationRepository;
        this.uploadedFileService = uploadedFileService;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryResponse> listByUser(AuthenticatedUser user) {
        return conversationRepository.findByExternalUserIdOrderByUpdatedAtDesc(user.id()).stream()
            .map(conversation -> new ConversationSummaryResponse(
                conversation.getId(),
                conversation.getTitle(),
                previewOf(conversation),
                conversation.getUpdatedAt()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailResponse getConversation(AuthenticatedUser user, String conversationId) {
        ConversationEntity conversation = requireConversation(user, conversationId);
        return new ConversationDetailResponse(
            conversation.getId(),
            conversation.getTitle(),
            toMessages(conversation),
            conversation.getUpdatedAt(),
            uploadedFileService.activeFileFor(user, conversation.getId())
        );
    }

    @Transactional
    public String saveSnapshot(
        AuthenticatedUser user,
        String conversationId,
        List<ChatMessage> messages,
        String suggestedTitle
    ) {
        boolean creating = conversationId == null || conversationId.isBlank();
        ConversationEntity conversation = creating
            ? createConversation(user)
            : requireConversation(user, conversationId);

        if (creating) {
            conversation.setTitle(normalizeTitle(suggestedTitle));
        }
        conversation.setUpdatedAt(Instant.now());
        conversation.getMessages().clear();

        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            ConversationMessageEntity entity = new ConversationMessageEntity();
            entity.setConversation(conversation);
            entity.setOrderIndex(index);
            entity.setRole(message.role());
            entity.setContent(message.content());
            entity.setAttachmentName(message.attachmentName());
            entity.setAttachmentSummary(message.attachmentSummary());
            entity.setDocumentJson(toDocumentJson(message.document()));
            entity.setFileContextId(message.fileContextId());
            entity.setCreatedAt(Instant.now());
            conversation.getMessages().add(entity);
        }

        conversationRepository.save(conversation);
        return conversation.getId();
    }

    private ConversationEntity createConversation(AuthenticatedUser user) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setExternalUserId(user.id());
        conversation.setCreatedAt(Instant.now());
        conversation.setUpdatedAt(Instant.now());
        return conversation;
    }

    private ConversationEntity requireConversation(AuthenticatedUser user, String conversationId) {
        return conversationRepository.findByIdAndExternalUserId(conversationId, user.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversa não encontrada."));
    }

    private List<ChatMessage> toMessages(ConversationEntity conversation) {
        return conversation.getMessages().stream()
            .map(message -> new ChatMessage(
                message.getRole(),
                message.getContent(),
                message.getAttachmentName(),
                message.getAttachmentSummary(),
                fromDocumentJson(message.getDocumentJson()),
                message.getFileContextId()
            ))
            .toList();
    }

    @Transactional
    public void attachFileToLatestUserMessage(
        AuthenticatedUser user,
        String conversationId,
        String fileContextId
    ) {
        if (fileContextId == null || fileContextId.isBlank()) {
            return;
        }

        ConversationEntity conversation = requireConversation(user, conversationId);
        for (int index = conversation.getMessages().size() - 1; index >= 0; index--) {
            ConversationMessageEntity message = conversation.getMessages().get(index);
            if ("user".equalsIgnoreCase(message.getRole())) {
                message.setFileContextId(fileContextId);
                conversation.setUpdatedAt(Instant.now());
                conversationRepository.save(conversation);
                return;
            }
        }
    }

    private String toDocumentJson(AssistantDocument document) {
        if (document == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(document);
        } catch (Exception ignored) {
            return null;
        }
    }

    private AssistantDocument fromDocumentJson(String documentJson) {
        if (documentJson == null || documentJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(documentJson, AssistantDocument.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Transactional
    public void renameConversation(AuthenticatedUser user, String conversationId, String requestedTitle) {
        ConversationEntity conversation = requireConversation(user, conversationId);
        conversation.setTitle(normalizeTitle(requestedTitle));
        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
    }

    @Transactional
    public void deleteConversation(AuthenticatedUser user, String conversationId) {
        ConversationEntity conversation = requireConversation(user, conversationId);
        conversationRepository.delete(conversation);
    }

    private String normalizeTitle(String title) {
        String base = (title == null ? "" : title).trim();
        if (base.isBlank()) {
            return "Nova conversa";
        }

        String cleaned = base
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "")
            .trim();

        if (cleaned.isBlank()) {
            return "Nova conversa";
        }

        String fiveWords = Arrays.stream(cleaned.split("\\s+"))
            .limit(5)
            .collect(Collectors.joining(" "));

        if (fiveWords.isBlank()) {
            return "Nova conversa";
        }

        String lowerRest = fiveWords.substring(0, 1).toUpperCase(Locale.ROOT)
            + fiveWords.substring(1);

        int safeMax = Math.max(8, properties.getMaxTitleChars());
        return lowerRest.length() > safeMax
            ? lowerRest.substring(0, safeMax).trim()
            : lowerRest;
    }

    private String previewOf(ConversationEntity conversation) {
        if (conversation.getMessages().isEmpty()) {
            return "Sem mensagens ainda";
        }
        String lastContent = conversation.getMessages()
            .get(conversation.getMessages().size() - 1)
            .getContent();

        if (lastContent == null || lastContent.isBlank()) {
            return "Sem prévia";
        }
        return lastContent.length() > 72 ? lastContent.substring(0, 72).trim() + "..." : lastContent.trim();
    }
}
