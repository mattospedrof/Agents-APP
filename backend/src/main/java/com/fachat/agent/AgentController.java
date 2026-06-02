package com.fachat.agent;

import com.fachat.agent.config.AgentProperties;
import com.fachat.agent.dto.AppConfigResponse;
import com.fachat.agent.dto.AssistantDocument;
import com.fachat.agent.dto.ActiveFileResponse;
import com.fachat.agent.dto.AuthenticatedUser;
import com.fachat.agent.dto.ChatMessage;
import com.fachat.agent.dto.ChatPayload;
import com.fachat.agent.dto.ChatResponse;
import com.fachat.agent.dto.ConversationDetailResponse;
import com.fachat.agent.dto.ConversationSummaryResponse;
import com.fachat.agent.dto.ConversationTitleUpdateRequest;
import com.fachat.agent.dto.FeedbackRequest;
import com.fachat.agent.dto.FeedbackType;
import com.fachat.agent.dto.PlannerOutput;
import com.fachat.agent.dto.ReasoningMode;
import com.fachat.agent.dto.ResponseRenderMeta;
import com.fachat.agent.dto.ReviewerOutput;
import com.fachat.agent.service.ConversationService;
import com.fachat.agent.service.FileContextService;
import com.fachat.agent.service.ModelCatalogService;
import com.fachat.agent.service.ResponseDocumentService;
import com.fachat.agent.service.UploadedFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.text.Normalizer;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class AgentController {
    private static final Logger logger = LoggerFactory.getLogger(AgentController.class);
    private static final Pattern SAFE_CONVERSATION_ID = Pattern.compile("^[a-zA-Z0-9-]{8,80}$");

    private final PlannerService planner;
    private final AgentExecutorService executor;
    private final ReviewerService reviewer;
    private final ModelCatalogService modelCatalog;
    private final ConversationService conversationService;
    private final FileContextService fileContextService;
    private final UploadedFileService uploadedFileService;
    private final ResponseDocumentService responseDocumentService;
    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final Validator validator;

    public AgentController(
        PlannerService planner,
        AgentExecutorService executor,
        ReviewerService reviewer,
        ModelCatalogService modelCatalog,
        ConversationService conversationService,
        FileContextService fileContextService,
        UploadedFileService uploadedFileService,
        ResponseDocumentService responseDocumentService,
        AgentProperties properties
    ) {
        this.planner = planner;
        this.executor = executor;
        this.reviewer = reviewer;
        this.modelCatalog = modelCatalog;
        this.conversationService = conversationService;
        this.fileContextService = fileContextService;
        this.uploadedFileService = uploadedFileService;
        this.responseDocumentService = responseDocumentService;
        this.properties = properties;
        this.mapper = new ObjectMapper();
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    @GetMapping("/config")
    public AppConfigResponse config(HttpServletRequest request) {
        logger.info("[Config] Request from IP {}", request.getRemoteAddr());
        return new AppConfigResponse(
            properties.getVersion(),
            modelCatalog.listOptions(),
            modelCatalog.defaultSelection(),
            modelCatalog.getFileUploadModelId()
        );
    }

    @GetMapping("/conversations")
    public List<ConversationSummaryResponse> conversations(
        @RequestHeader(value = "X-Session-User-Id", required = false) String sessionUserId,
        @RequestHeader(value = "X-Session-User-Name", required = false) String sessionUserName,
        @RequestHeader(value = "X-Session-User-Email", required = false) String sessionUserEmail
    ) {
        logger.info("[Conversations] List request. Has NextAuth user id: {}", sessionUserId != null);
        return conversationService.listByUser(requireUser(sessionUserId, sessionUserName, sessionUserEmail));
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationDetailResponse conversation(
        @PathVariable String conversationId,
        @RequestHeader(value = "X-Session-User-Id", required = false) String sessionUserId,
        @RequestHeader(value = "X-Session-User-Name", required = false) String sessionUserName,
        @RequestHeader(value = "X-Session-User-Email", required = false) String sessionUserEmail
    ) {
        logger.info("[Conversations] Details request for {}", conversationId);
        return conversationService.getConversation(
            requireUser(sessionUserId, sessionUserName, sessionUserEmail),
            conversationId
        );
    }

    @PatchMapping("/conversations/{conversationId}/title")
    public void renameConversation(
        @PathVariable String conversationId,
        @Valid @RequestBody ConversationTitleUpdateRequest body,
        @RequestHeader(value = "X-Session-User-Id", required = false) String sessionUserId,
        @RequestHeader(value = "X-Session-User-Name", required = false) String sessionUserName,
        @RequestHeader(value = "X-Session-User-Email", required = false) String sessionUserEmail
    ) {
        conversationService.renameConversation(
            requireUser(sessionUserId, sessionUserName, sessionUserEmail),
            conversationId,
            body.title()
        );
    }

    @DeleteMapping("/conversations/{conversationId}")
    public void deleteConversation(
        @PathVariable String conversationId,
        @RequestHeader(value = "X-Session-User-Id", required = false) String sessionUserId,
        @RequestHeader(value = "X-Session-User-Name", required = false) String sessionUserName,
        @RequestHeader(value = "X-Session-User-Email", required = false) String sessionUserEmail
    ) {
        conversationService.deleteConversation(
            requireUser(sessionUserId, sessionUserName, sessionUserEmail),
            conversationId
        );
    }

    @DeleteMapping("/conversations/{conversationId}/active-file")
    public void removeActiveFile(
        @PathVariable String conversationId,
        @RequestHeader(value = "X-Session-User-Id", required = false) String sessionUserId,
        @RequestHeader(value = "X-Session-User-Name", required = false) String sessionUserName,
        @RequestHeader(value = "X-Session-User-Email", required = false) String sessionUserEmail
    ) {
        uploadedFileService.removeActive(
            requireUser(sessionUserId, sessionUserName, sessionUserEmail),
            conversationId
        );
    }

    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatResponse chat(
        @RequestPart("payload") String payloadRaw,
        @RequestPart(value = "file", required = false) MultipartFile file,
        @RequestHeader(value = "X-Session-User-Id", required = false) String sessionUserId,
        @RequestHeader(value = "X-Session-User-Name", required = false) String sessionUserName,
        @RequestHeader(value = "X-Session-User-Email", required = false) String sessionUserEmail
    ) throws Exception {
        long totalStarted = System.nanoTime();
        long plannerMs = 0L;
        long executorMs = 0L;
        long reviewerMs = 0L;
        long titleMs = 0L;
        long documentMs = 0L;
        long repairMs = 0L;
        long saveMs = 0L;
        ChatPayload payload = mapper.readValue(payloadRaw, ChatPayload.class);
        validate(payload);
        logger.info(
            "[Chat] Received request with {} messages, selected models {} and role selection {}",
            payload.messages().size(),
            payload.selectedModelIds(),
            payload.modelSelection()
        );

        AuthenticatedUser user = resolveOptionalUser(sessionUserId, sessionUserName, sessionUserEmail);
        FileContextService.FileContext uploadedFileContext = fileContextService.extract(file);
        FileContextService.FileContext persistedFileContext = uploadedFileContext.hasFile()
            ? emptyFileContext()
            : uploadedFileService.resolveContext(user, payload.conversationId(), payload.activeFileContextId());
        FileContextService.FileContext fileContext = uploadedFileContext.hasFile()
            ? uploadedFileContext
            : persistedFileContext;
        ReasoningMode reasoningMode = payload.reasoningMode() == null ? ReasoningMode.FAST : payload.reasoningMode();
        List<String> plannerModels = modelsForRole(
            payload.modelSelection() == null ? null : payload.modelSelection().plannerModelId(),
            payload.selectedModelIds()
        );
        List<String> executorModels = modelsForRole(
            payload.modelSelection() == null ? null : payload.modelSelection().executorModelId(),
            payload.selectedModelIds()
        );
        List<String> reviewerModels = modelsForRole(
            payload.modelSelection() == null ? null : payload.modelSelection().reviewerModelId(),
            payload.selectedModelIds()
        );

        List<ChatMessage> requestMessages = enrichLatestUserMessage(
            payload.messages(),
            fileContext,
            uploadedFileContext.hasFile() ? null : payload.activeFileContextId()
        );
        PlannerOutput plan;
        String plannerModelUsed;
        long plannerStarted = System.nanoTime();
        if (shouldUseFastLane(requestMessages, reasoningMode, fileContext.hasFile())) {
            plan = buildFastLanePlan(requestMessages);
            plannerModelUsed = "fast-lane";
        } else {
            PlannerService.PlannerExecution planExecution =
                planner.planWithTrace(requestMessages, plannerModels, reasoningMode, fileContext.hasFile());
            plan = planExecution.output();
            plannerModelUsed = planExecution.modelId();
        }
        plannerMs = elapsedMs(plannerStarted);

        long executorStarted = System.nanoTime();
        AgentExecutorService.ExecutorExecution executorExecution =
            executor.executeWithTrace(plan, requestMessages, executorModels, reasoningMode, fileContext);
        String draft = executorExecution.response();
        String executorModelUsed = executorExecution.modelId();
        executorMs = elapsedMs(executorStarted);

        boolean shouldReview = reasoningMode.isThoughtful()
            || "CODE_EXECUTOR".equals(plan.executor())
            || fileContext.hasFile();

        String finalResponse = draft;
        String reviewerModelUsed = null;
        if (shouldReview) {
            long reviewerStarted = System.nanoTime();
            try {
                ReviewerService.ReviewExecution reviewExecution = reviewer.reviewWithTrace(
                    draft,
                    requestMessages,
                    reviewerModels,
                    reasoningMode,
                    fileContext.hasFile()
                );
                ReviewerOutput review = reviewExecution.output();
                finalResponse = review.final_response();
                reviewerModelUsed = reviewExecution.modelId();
            } catch (Exception exception) {
                logger.warn("[Chat] Reviewer failed. Returning draft. Reason: {}", exception.getMessage());
            } finally {
                reviewerMs = elapsedMs(reviewerStarted);
            }
        }

        boolean creatingConversation = isCreatingConversation(payload, user);
        String generatedTitle = null;
        ResponseDocumentService.DocumentBuildResult documentResult =
            responseDocumentService.toDocumentWithMetrics(finalResponse);
        AssistantDocument document = documentResult.document();
        documentMs = documentResult.documentMs();
        repairMs = documentResult.repairMs();
        if (creatingConversation) {
            long titleStarted = System.nanoTime();
            try {
                List<ChatMessage> titleMessages = new ArrayList<>(requestMessages);
                titleMessages.add(new ChatMessage("assistant", finalResponse, null, null, document));
                generatedTitle = executor.suggestConversationTitle(
                    titleMessages,
                    executorModels,
                    fileContext.hasFile()
                );
            } catch (Exception exception) {
                generatedTitle = titleFromPlan(plan, requestMessages);
            } finally {
                titleMs = elapsedMs(titleStarted);
            }
            generatedTitle = resolveConversationTitle(generatedTitle, finalResponse, plan, requestMessages);
        }

        String conversationId = null;
        ActiveFileResponse activeFile = null;
        if (user != null) {
            long saveStarted = System.nanoTime();
            List<ChatMessage> snapshot = new ArrayList<>(requestMessages);
            snapshot.add(new ChatMessage("assistant", finalResponse, null, null, document));
            conversationId = conversationService.saveSnapshot(
                user,
                payload.conversationId(),
                snapshot,
                generatedTitle
            );
            if (uploadedFileContext.hasExtractedText()) {
                activeFile = uploadedFileService.saveActive(user, conversationId, uploadedFileContext);
                if (activeFile != null) {
                    conversationService.attachFileToLatestUserMessage(user, conversationId, activeFile.id());
                }
            } else {
                activeFile = uploadedFileService.activeFileFor(user, conversationId);
            }
            saveMs = elapsedMs(saveStarted);
        }

        logger.info(
            "{\"event\":\"chat_response_ready\",\"conversationId\":\"{}\",\"plannerModel\":\"{}\",\"executorModel\":\"{}\",\"reviewerModel\":\"{}\",\"responseLength\":{}}",
            conversationId == null ? "" : conversationId,
            plannerModelUsed == null ? "" : plannerModelUsed,
            executorModelUsed == null ? "" : executorModelUsed,
            reviewerModelUsed == null ? "" : reviewerModelUsed,
            finalResponse == null ? 0 : finalResponse.length()
        );
        logger.info(
            "{\"event\":\"chat_timing\",\"plannerMs\":{},\"executorMs\":{},\"reviewerMs\":{},\"titleMs\":{},\"documentMs\":{},\"repairMs\":{},\"saveMs\":{},\"totalMs\":{},\"reasoningMode\":\"{}\",\"creatingConversation\":{},\"documentCached\":{}}",
            plannerMs,
            executorMs,
            reviewerMs,
            titleMs,
            documentMs,
            repairMs,
            saveMs,
            elapsedMs(totalStarted),
            reasoningMode,
            creatingConversation,
            documentResult.cached()
        );
        return new ChatResponse(
            finalResponse,
            conversationId,
            properties.getVersion(),
            generatedTitle,
            plannerModelUsed,
            executorModelUsed,
            reviewerModelUsed,
            document,
            activeFile,
            new ResponseRenderMeta(documentMs, repairMs, saveMs, documentResult.cached())
        );
    }

    @PostMapping(
        value = "/chat/stream",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chatStream(
        @RequestPart("payload") String payloadRaw,
        @RequestPart(value = "file", required = false) MultipartFile file,
        @RequestHeader(value = "X-Session-User-Id", required = false) String sessionUserId,
        @RequestHeader(value = "X-Session-User-Name", required = false) String sessionUserName,
        @RequestHeader(value = "X-Session-User-Email", required = false) String sessionUserEmail
    ) throws Exception {
        ChatPayload payload = mapper.readValue(payloadRaw, ChatPayload.class);
        validate(payload);
        logger.info(
            "[Chat/Stream] Received request with {} messages, selected models {} and role selection {}",
            payload.messages().size(),
            payload.selectedModelIds(),
            payload.modelSelection()
        );

        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                long totalStarted = System.nanoTime();
                long plannerMs = 0L;
                long executorMs = 0L;
                long reviewerMs = 0L;
                long titleMs = 0L;
                long documentMs = 0L;
                long repairMs = 0L;
                long saveMs = 0L;
                AuthenticatedUser user = resolveOptionalUser(sessionUserId, sessionUserName, sessionUserEmail);
                FileContextService.FileContext uploadedFileContext = fileContextService.extract(file);
                FileContextService.FileContext persistedFileContext = uploadedFileContext.hasFile()
                    ? emptyFileContext()
                    : uploadedFileService.resolveContext(user, payload.conversationId(), payload.activeFileContextId());
                FileContextService.FileContext fileContext = uploadedFileContext.hasFile()
                    ? uploadedFileContext
                    : persistedFileContext;
                ReasoningMode reasoningMode = payload.reasoningMode() == null ? ReasoningMode.FAST : payload.reasoningMode();
                List<String> plannerModels = modelsForRole(
                    payload.modelSelection() == null ? null : payload.modelSelection().plannerModelId(),
                    payload.selectedModelIds()
                );
                List<String> executorModels = modelsForRole(
                    payload.modelSelection() == null ? null : payload.modelSelection().executorModelId(),
                    payload.selectedModelIds()
                );
                List<String> reviewerModels = modelsForRole(
                    payload.modelSelection() == null ? null : payload.modelSelection().reviewerModelId(),
                    payload.selectedModelIds()
                );

                List<ChatMessage> requestMessages = enrichLatestUserMessage(
                    payload.messages(),
                    fileContext,
                    uploadedFileContext.hasFile() ? null : payload.activeFileContextId()
                );
                PlannerOutput plan;
                String plannerModelUsed;
                long plannerStarted = System.nanoTime();
                if (shouldUseFastLane(requestMessages, reasoningMode, fileContext.hasFile())) {
                    plan = buildFastLanePlan(requestMessages);
                    plannerModelUsed = "fast-lane";
                } else {
                    PlannerService.PlannerExecution planExecution =
                        planner.planWithTrace(requestMessages, plannerModels, reasoningMode, fileContext.hasFile());
                    plan = planExecution.output();
                    plannerModelUsed = planExecution.modelId();
                }
                plannerMs = elapsedMs(plannerStarted);

                long executorStarted = System.nanoTime();
                AgentExecutorService.ExecutorExecution executorExecution = executor.executeStreamWithTrace(
                    plan,
                    requestMessages,
                    executorModels,
                    reasoningMode,
                    fileContext,
                    delta -> sendSse(emitter, "delta", Map.of("content", delta))
                );

                String finalResponse = executorExecution.response();
                String executorModelUsed = executorExecution.modelId();
                executorMs = elapsedMs(executorStarted);
                String reviewerModelUsed = null;
                boolean shouldReview = reasoningMode.isThoughtful()
                    || "CODE_EXECUTOR".equals(plan.executor())
                    || fileContext.hasFile();
                if (shouldReview) {
                    long reviewerStarted = System.nanoTime();
                    try {
                        ReviewerService.ReviewExecution reviewExecution = reviewer.reviewWithTrace(
                            finalResponse,
                            requestMessages,
                            reviewerModels,
                            reasoningMode,
                            fileContext.hasFile()
                        );
                        finalResponse = reviewExecution.output().final_response();
                        reviewerModelUsed = reviewExecution.modelId();
                    } catch (Exception exception) {
                        logger.warn("[Chat/Stream] Reviewer failed. Keeping streamed draft. Reason: {}", exception.getMessage());
                    } finally {
                        reviewerMs = elapsedMs(reviewerStarted);
                    }
                }
                boolean creatingConversation = isCreatingConversation(payload, user);
                String generatedTitle = null;
                ResponseDocumentService.DocumentBuildResult documentResult =
                    responseDocumentService.toDocumentWithMetrics(finalResponse);
                AssistantDocument document = documentResult.document();
                documentMs = documentResult.documentMs();
                repairMs = documentResult.repairMs();
                if (creatingConversation) {
                    long titleStarted = System.nanoTime();
                    try {
                        List<ChatMessage> titleMessages = new ArrayList<>(requestMessages);
                        titleMessages.add(new ChatMessage("assistant", finalResponse, null, null, document));
                        generatedTitle = executor.suggestConversationTitle(
                            titleMessages,
                            executorModels,
                            fileContext.hasFile()
                        );
                    } catch (Exception exception) {
                        generatedTitle = titleFromPlan(plan, requestMessages);
                    } finally {
                        titleMs = elapsedMs(titleStarted);
                    }
                    generatedTitle = resolveConversationTitle(generatedTitle, finalResponse, plan, requestMessages);
                }

                String conversationId = null;
                ActiveFileResponse activeFile = null;
                if (user != null) {
                    long saveStarted = System.nanoTime();
                    List<ChatMessage> snapshot = new ArrayList<>(requestMessages);
                    snapshot.add(new ChatMessage("assistant", finalResponse, null, null, document));
                    conversationId = conversationService.saveSnapshot(
                        user,
                        payload.conversationId(),
                        snapshot,
                        generatedTitle
                    );
                    if (uploadedFileContext.hasExtractedText()) {
                        activeFile = uploadedFileService.saveActive(user, conversationId, uploadedFileContext);
                        if (activeFile != null) {
                            conversationService.attachFileToLatestUserMessage(user, conversationId, activeFile.id());
                        }
                    } else {
                        activeFile = uploadedFileService.activeFileFor(user, conversationId);
                    }
                    saveMs = elapsedMs(saveStarted);
                }

                ChatResponse response = new ChatResponse(
                    finalResponse,
                    conversationId,
                    properties.getVersion(),
                    generatedTitle,
                    plannerModelUsed,
                    executorModelUsed,
                    reviewerModelUsed,
                    document,
                    activeFile,
                    new ResponseRenderMeta(documentMs, repairMs, saveMs, documentResult.cached())
                );
                logger.info(
                    "{\"event\":\"chat_stream_done\",\"conversationId\":\"{}\",\"plannerModel\":\"{}\",\"executorModel\":\"{}\",\"reviewerModel\":\"{}\",\"responseLength\":{}}",
                    conversationId == null ? "" : conversationId,
                    plannerModelUsed == null ? "" : plannerModelUsed,
                    executorModelUsed == null ? "" : executorModelUsed,
                    reviewerModelUsed == null ? "" : reviewerModelUsed,
                    finalResponse == null ? 0 : finalResponse.length()
                );
                logger.info(
                    "{\"event\":\"chat_stream_timing\",\"plannerMs\":{},\"executorMs\":{},\"reviewerMs\":{},\"titleMs\":{},\"documentMs\":{},\"repairMs\":{},\"saveMs\":{},\"totalMs\":{},\"reasoningMode\":\"{}\",\"creatingConversation\":{},\"documentCached\":{}}",
                    plannerMs,
                    executorMs,
                    reviewerMs,
                    titleMs,
                    documentMs,
                    repairMs,
                    saveMs,
                    elapsedMs(totalStarted),
                    reasoningMode,
                    creatingConversation,
                    documentResult.cached()
                );
                sendSse(emitter, "done", response);
                emitter.complete();
            } catch (Exception exception) {
                logger.error("[Chat/Stream] Failed: {}", exception.getMessage(), exception);
                sendSse(
                    emitter,
                    "error",
                    Map.of("message", "Falha interna ao processar o stream.")
                );
                emitter.complete();
            }
        });

        return emitter;
    }

    @PostMapping("/feedback")
    public void feedback(
        @Valid @RequestBody FeedbackRequest request,
        @RequestHeader(value = "X-Session-User-Id", required = false) String sessionUserId,
        @RequestHeader(value = "X-Session-User-Name", required = false) String sessionUserName,
        @RequestHeader(value = "X-Session-User-Email", required = false) String sessionUserEmail
    ) {
        if (request.userPrompt().length() > properties.getMaxMessageChars()
            || request.responseContent().length() > (properties.getMaxMessageChars() * 3)) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Feedback payload is too large."
            );
        }

        AuthenticatedUser user = resolveOptionalUser(sessionUserId, sessionUserName, sessionUserEmail);
        List<String> feedbackModels = modelIdsForFeedback(request);
        String theme;
        try {
            theme = executor.suggestFeedbackTheme(
                request.userPrompt(),
                request.responseContent(),
                feedbackModels,
                false
            );
        } catch (Exception exception) {
            theme = titleFromText(request.userPrompt());
        }

        String actor = user == null ? "guest" : user.id();
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (request.type() == FeedbackType.DISLIKE && reason.isBlank()) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Motivo do dislike é obrigatório."
            );
        }

        logger.info(
            "[Feedback] type={} theme=\"{}\" actor={} conversationId={} models={} reasonLength={}",
            request.type(),
            theme,
            actor,
            request.conversationId(),
            feedbackModels,
            reason.length()
        );
    }

    private boolean isCreatingConversation(ChatPayload payload, AuthenticatedUser user) {
        if (user != null) {
            return payload.conversationId() == null || payload.conversationId().isBlank();
        }

        long assistantMessages = payload.messages().stream()
            .filter(message -> "assistant".equalsIgnoreCase(message.role()))
            .count();
        return assistantMessages == 0;
    }

    private String titleFromPlan(PlannerOutput plan, List<ChatMessage> messages) {
        String source = plan == null ? "" : pickFirstNonBlank(plan.conversation_title(), plan.intent());
        String normalized = smartTitleFromText(source);
        if (!normalized.isBlank() && !"Nova conversa".equalsIgnoreCase(normalized)) {
            return normalized;
        }

        String lastUserMessage = latestUserMessage(messages);
        String fallback = smartTitleFromText(lastUserMessage);
        return fallback.isBlank() ? "Nova conversa" : fallback;
    }

    private String resolveConversationTitle(
        String generatedTitle,
        String finalResponse,
        PlannerOutput plan,
        List<ChatMessage> messages
    ) {
        String fromGenerator = smartTitleFromText(generatedTitle);
        if (!fromGenerator.isBlank() && !"Nova conversa".equalsIgnoreCase(fromGenerator)) {
            return fromGenerator;
        }

        String fromPlan = titleFromPlan(plan, messages);
        if (!fromPlan.isBlank() && !"Nova conversa".equalsIgnoreCase(fromPlan)) {
            return fromPlan;
        }

        String fromUser = smartTitleFromText(latestUserMessage(messages));
        if (!fromUser.isBlank() && !"Nova conversa".equalsIgnoreCase(fromUser)) {
            return fromUser;
        }

        return "Nova conversa";
    }

    private String titleFromText(String source) {
        if (source == null || source.isBlank()) {
            return "Nova conversa";
        }

        String cleaned = source
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "")
            .trim();

        if (cleaned.isBlank()) {
            return "Nova conversa";
        }

        String withoutGreeting = cleaned
            .replaceAll("^(oi|ola|olá|e ai|e aí|bom dia|boa tarde|boa noite)\\b[\\s,!:.-]*", "")
            .trim();
        String base = withoutGreeting.isBlank() ? cleaned : withoutGreeting;
        String fiveWords = String.join(" ", List.of(base.split("\\s+")).stream().limit(5).toList());
        if (fiveWords.isBlank()) {
            return "Nova conversa";
        }

        return fiveWords.substring(0, 1).toUpperCase(Locale.ROOT) + fiveWords.substring(1);
    }

    private String smartTitleFromText(String source) {
        if (source == null || source.isBlank()) {
            return "Nova conversa";
        }

        String cleaned = source
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "")
            .trim();
        cleaned = stripTitleNoise(cleaned);
        if (cleaned.isBlank()) {
            return "Nova conversa";
        }

        String comparisonTitle = comparisonTitleFromText(cleaned);
        if (!comparisonTitle.isBlank()) {
            return comparisonTitle;
        }

        List<String> meaningfulWords = extractMeaningfulTitleWordsForTitle(cleaned, 5);
        if (meaningfulWords.isEmpty()) {
            return "Nova conversa";
        }
        List<String> baseWords = meaningfulWords;

        List<String> deduplicated = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String word : baseWords) {
            String normalizedWord = simplifyForTitle(word);
            if (normalizedWord.isBlank() || seen.contains(normalizedWord)) {
                continue;
            }
            seen.add(normalizedWord);
            deduplicated.add(word);
        }

        if (!deduplicated.isEmpty()) {
            baseWords = deduplicated;
        }

        String title = String.join(" ", baseWords.stream().limit(5).toList()).trim();
        if (title.isBlank()) {
            return "Nova conversa";
        }
        String titled = sanitizeTitleForDisplay(title);
        if (isForbiddenTitleShape(titled) || isWeakOrGenericTitle(titled)) {
            return "Nova conversa";
        }
        return titled;
    }

    private List<String> extractMeaningfulTitleWordsForTitle(String source, int limit) {
        String cleanedSource = source == null ? "" : source;
        String withoutGreeting = stripTitleNoise(cleanedSource);
        String base = withoutGreeting.isBlank() ? cleanedSource : withoutGreeting;
        if (base.isBlank()) {
            return List.of();
        }

        Set<String> stopWords = Set.of(
            "a", "o", "as", "os", "de", "da", "do", "das", "dos", "e",
            "em", "para", "por", "com", "sem", "um", "uma", "uns", "umas",
            "que", "como", "qual", "quais", "sobre", "me", "mim", "favor",
            "pode", "ser", "sao", "rapido", "rapida", "rapidamente",
            "explique", "fale", "diga", "mostre", "segue", "aqui", "esta",
            "compare", "opcao", "parte", "partes", "geral", "resumo", "topicos",
            "tabela", "simples", "passos", "numerados", "definicao", "vantagens", "cuidados",
            "vs", "versus"
        );
        Set<String> shortAllowedWords = Set.of("el");

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        for (String rawWord : base.split("\\s+")) {
            String cleanWord = rawWord.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
            if (cleanWord.isBlank()) {
                continue;
            }
            String normalizedWord = normalizeTitleKeyForMatching(cleanWord);
            if (normalizedWord.length() <= 2 && !shortAllowedWords.contains(normalizedWord)) {
                continue;
            }
            if (stopWords.contains(normalizedWord)) {
                continue;
            }
            if (seenKeys.add(normalizedWord)) {
                selected.add(formatTitleWord(cleanWord));
            }
            if (selected.size() >= limit) {
                break;
            }
        }

        return List.copyOf(selected);
    }

    private String simplifyForTitle(String value) {
        return normalizeTitleKeyForMatching(value);
    }

    private String normalizeTitleKeyForMatching(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "")
            .trim();
    }

    private String sanitizeTitleForDisplay(String value) {
        String clean = value == null ? "" : value
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "")
            .trim();
        if (clean.isBlank()) {
            return "";
        }

        return String.join(
            " ",
            List.of(clean.split("\\s+")).stream()
                .map(this::formatTitleWord)
                .filter(word -> !word.isBlank())
                .limit(5)
                .toList()
        );
    }

    private String formatTitleWord(String word) {
        if (word == null || word.isBlank()) {
            return "";
        }

        String clean = word.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
        if (clean.isBlank()) {
            return "";
        }

        String key = normalizeTitleKeyForMatching(clean);
        Map<String, String> canonical = Map.ofEntries(
            Map.entry("api", "API"),
            Map.entry("sql", "SQL"),
            Map.entry("mhc", "MHC"),
            Map.entry("ia", "IA"),
            Map.entry("ui", "UI"),
            Map.entry("ux", "UX"),
            Map.entry("docker", "Docker"),
            Map.entry("supabase", "Supabase"),
            Map.entry("neon", "Neon"),
            Map.entry("aiven", "Aiven"),
            Map.entry("el", "El"),
            Map.entry("nino", "Niño"),
            Map.entry("fenomeno", "Fenômeno"),
            Map.entry("comparacao", "Comparação"),
            Map.entry("definicao", "Definição"),
            Map.entry("autenticacao", "Autenticação"),
            Map.entry("integracao", "Integração")
        );

        if (canonical.containsKey(key)) {
            return canonical.get(key);
        }
        if (clean.equals(clean.toUpperCase(Locale.ROOT)) && clean.length() <= 5) {
            return clean;
        }
        return clean.substring(0, 1).toUpperCase(Locale.ROOT) + clean.substring(1).toLowerCase(Locale.ROOT);
    }

    private String comparisonTitleFromText(String source) {
        String normalized = Normalizer.normalize(source == null ? "" : source, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
        if (!normalized.matches(".*\\b(compare|comparar|comparacao|diferenca|versus|vs)\\b.*")) {
            return "";
        }

        List<String> entities = extractMeaningfulTitleWordsForTitle(source, 6).stream()
            .map(this::formatTitleWord)
            .filter(word -> !word.isBlank())
            .distinct()
            .limit(3)
            .toList();

        return entities.size() >= 2 ? String.join(" vs ", entities) : "";
    }

    private String stripTitleNoise(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }

        return source
            .replaceAll("(?i)^(oi|ola|olá|bom dia|boa tarde|boa noite)\\b[\\s,!:.-]*", "")
            .replaceAll(
                "(?i)^(me\\s+)?(fale|explique|compare|diga|mostre|me\\s+explique|me\\s+fale)\\b[\\s,!:.-]*(sobre\\s+)?",
                ""
            )
            .replaceAll("(?i)\\bem\\s+t[oó]picos?\\b", " ")
            .replaceAll("(?i)\\bem\\s+\\d+\\s+passos?(\\s+numerados?)?\\b", " ")
            .replaceAll("(?i)\\bem\\s+uma\\s+tabela\\s+(simples|resumida)\\b", " ")
            .replaceAll("(?i)\\bdefini[cç][aã]o\\b", " ")
            .replaceAll("(?i)\\bvantagens\\b", " ")
            .replaceAll("(?i)\\bcuidados\\b", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private boolean isForbiddenTitleShape(String title) {
        String simplified = simplifyForTitle(title).replaceAll("(\\d+)$", " $1").trim();
        if (simplified.isBlank()) {
            return true;
        }

        return simplified.startsWith("mefale")
            || simplified.startsWith("explique")
            || simplified.startsWith("compare")
            || simplified.startsWith("diga")
            || simplified.contains("emtopicos")
            || simplified.contains("emumatabela")
            || simplified.contains("tabelasimples")
            || simplified.contains("passosnumerados");
    }

    private boolean isWeakOrGenericTitle(String title) {
        String simplified = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}\\s]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (simplified.isBlank()) {
            return true;
        }

        String[] words = simplified.split("\\s+");
        if (words.length == 1) {
            return Set.of("fenomeno", "assunto", "tema", "resumo", "topico", "topicos", "explicacao")
                .contains(words[0]);
        }
        return false;
    }

    private String pickFirstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String latestUserMessage(List<ChatMessage> messages) {
        return messages.stream()
            .filter(message -> "user".equalsIgnoreCase(message.role()))
            .reduce((first, second) -> second)
            .map(ChatMessage::content)
            .orElse("");
    }

    private boolean shouldUseFastLane(List<ChatMessage> messages, ReasoningMode mode, boolean hasFile) {
        if (mode != ReasoningMode.FAST || hasFile) {
            return false;
        }

        String latestUser = latestUserMessage(messages);
        if (latestUser.isBlank()) {
            return false;
        }

        if (latestUser.length() > 260) {
            return false;
        }

        return !isLikelyCodeRequest(latestUser);
    }

    private PlannerOutput buildFastLanePlan(List<ChatMessage> messages) {
        String latestUser = latestUserMessage(messages);
        return new PlannerOutput(
            latestUser,
            smartTitleFromText(latestUser),
            "conversation",
            "low",
            "GENERAL_EXECUTOR",
            false,
            "concise",
            List.of("Responder de forma clara e objetiva.")
        );
    }

    private boolean isLikelyCodeRequest(String input) {
        String lower = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
        return lower.contains("codigo")
            || lower.contains("código")
            || lower.contains("funcao")
            || lower.contains("função")
            || lower.contains("classe")
            || lower.contains("bug")
            || lower.contains("debug")
            || lower.contains("java")
            || lower.contains("python")
            || lower.contains("javascript")
            || lower.contains("golang")
            || lower.contains("lua")
            || lower.contains("rust")
            || lower.contains("typescript")
            || lower.contains("sql")
            || lower.contains("regex")
            || lower.contains("algoritmo")
            || lower.contains("compile")
            || lower.contains("erro");
    }

    private List<String> modelIdsForFeedback(FeedbackRequest request) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (request.plannerModelId() != null && !request.plannerModelId().isBlank()) {
            ordered.add(request.plannerModelId());
        }
        if (request.executorModelId() != null && !request.executorModelId().isBlank()) {
            ordered.add(request.executorModelId());
        }
        if (request.reviewerModelId() != null && !request.reviewerModelId().isBlank()) {
            ordered.add(request.reviewerModelId());
        }
        if (request.selectedModelIds() != null) {
            request.selectedModelIds().stream()
                .filter(modelId -> modelId != null && !modelId.isBlank())
                .forEach(ordered::add);
        }

        List<String> known = ordered.stream().filter(modelCatalog::isKnownModel).toList();
        return known.isEmpty() ? modelCatalog.defaultSelection() : known;
    }

    private AuthenticatedUser requireUser(
        String sessionUserId,
        String sessionUserName,
        String sessionUserEmail
    ) {
        AuthenticatedUser user = resolveOptionalUser(sessionUserId, sessionUserName, sessionUserEmail);
        if (user == null) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "Login required to access conversations."
            );
        }
        return user;
    }

    private AuthenticatedUser resolveOptionalUser(
        String sessionUserId,
        String sessionUserName,
        String sessionUserEmail
    ) {
        if (sessionUserId == null || sessionUserId.isBlank()) {
            return null;
        }

        String safeName = (sessionUserName == null || sessionUserName.isBlank()) ? "User" : sessionUserName;
        String safeEmail = (sessionUserEmail == null) ? "" : sessionUserEmail;
        return new AuthenticatedUser(sessionUserId, safeName, safeEmail);
    }

    private void validate(ChatPayload payload) {
        if (!validator.validate(payload).isEmpty()) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Invalid chat payload."
            );
        }

        if (payload.messages() == null || payload.messages().isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Messages are required.");
        }

        if (payload.messages().size() > properties.getMaxMessagesPerRequest()) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Too many messages in a single request."
            );
        }

        if (payload.conversationId() != null
            && !payload.conversationId().isBlank()
            && !SAFE_CONVERSATION_ID.matcher(payload.conversationId()).matches()) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Invalid conversation id."
            );
        }

        for (ChatMessage message : payload.messages()) {
            if (message == null) {
                throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Message cannot be null."
                );
            }
            String role = message.role();
            if (!"user".equalsIgnoreCase(role) && !"assistant".equalsIgnoreCase(role)) {
                throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Invalid message role."
                );
            }
            String content = message.content() == null ? "" : message.content();
            if (content.length() > properties.getMaxMessageChars()) {
                throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Message is too long."
                );
            }
        }

        if (payload.selectedModelIds() != null) {
            for (String modelId : payload.selectedModelIds()) {
                if (modelId == null || modelId.isBlank()) {
                    continue;
                }
                if (!modelCatalog.isKnownModel(modelId)) {
                    throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Model is not allowed."
                    );
                }
            }
        }

        if (payload.modelSelection() != null) {
            validateRoleModel(payload.modelSelection().plannerModelId());
            validateRoleModel(payload.modelSelection().executorModelId());
            validateRoleModel(payload.modelSelection().reviewerModelId());
        }
    }

    private void validateRoleModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        if (!modelCatalog.isKnownModel(modelId)) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Role model is not allowed."
            );
        }
    }

    private List<String> modelsForRole(String preferredModelId, List<String> selectedModelIds) {
        Set<String> ordered = new LinkedHashSet<>();

        if (preferredModelId != null && !preferredModelId.isBlank() && modelCatalog.isKnownModel(preferredModelId)) {
            ordered.add(preferredModelId);
        }

        if (selectedModelIds != null) {
            selectedModelIds.stream()
                .filter(modelId -> modelId != null && !modelId.isBlank())
                .filter(modelCatalog::isKnownModel)
                .forEach(ordered::add);
        }

        return List.copyOf(ordered);
    }

    private List<ChatMessage> enrichLatestUserMessage(
        List<ChatMessage> messages,
        FileContextService.FileContext fileContext,
        String fileContextId
    ) {
        if (!fileContext.hasFile() || messages.isEmpty()) {
            return List.copyOf(messages);
        }

        List<ChatMessage> enriched = new ArrayList<>(messages);
        for (int index = enriched.size() - 1; index >= 0; index--) {
            ChatMessage message = enriched.get(index);
            if ("user".equalsIgnoreCase(message.role())) {
                ChatMessage withAttachment = message.withAttachment(
                    fileContext.fileName(),
                    buildAttachmentSummary(fileContext)
                );
                enriched.set(index, fileContextId == null || fileContextId.isBlank()
                    ? withAttachment
                    : withAttachment.withFileContextId(fileContextId));
                break;
            }
        }
        return List.copyOf(enriched);
    }

    private FileContextService.FileContext emptyFileContext() {
        return fileContextService.fromStored(null, null, 0, null);
    }

    private String buildAttachmentSummary(FileContextService.FileContext fileContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("Attached file: ").append(fileContext.fileName());
        builder.append(" (").append(fileContext.size()).append(" bytes)");

        if (fileContext.hasExtractedText()) {
            builder.append("\n\nExtracted preview:\n").append(fileContext.extractedText());
        } else {
            builder.append(
                "\n\nAutomatic extraction failed. Use file name and user prompt to decide if another format is needed."
            );
        }

        return builder.toString();
    }

    private void sendSse(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(
                SseEmitter.event()
                    .name(eventName)
                    .data(mapper.writeValueAsString(payload))
            );
        } catch (Exception ignored) {
            // Client can disconnect while streaming; no-op keeps backend stable.
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
