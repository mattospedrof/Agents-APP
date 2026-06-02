package com.fachat.agent;

import com.fachat.agent.dto.ChatMessage;
import com.fachat.agent.dto.PlannerOutput;
import com.fachat.agent.dto.ReasoningMode;
import com.fachat.agent.service.ModelCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlannerService {
    private static final Logger logger = LoggerFactory.getLogger(PlannerService.class);

    public record PlannerExecution(PlannerOutput output, String modelId) {
    }

    private final OpenRouterClient client;
    private final ObjectMapper mapper;
    private final ModelCatalogService modelCatalog;

    public PlannerService(OpenRouterClient client, ModelCatalogService modelCatalog) {
        this.client = client;
        this.mapper = new ObjectMapper();
        this.modelCatalog = modelCatalog;
    }

    public PlannerOutput plan(
        List<ChatMessage> messages,
        List<String> selectedModelIds,
        ReasoningMode reasoningMode,
        boolean hasFile
    ) {
        return planWithTrace(messages, selectedModelIds, reasoningMode, hasFile).output();
    }

    public PlannerExecution planWithTrace(
        List<ChatMessage> messages,
        List<String> selectedModelIds,
        ReasoningMode reasoningMode,
        boolean hasFile
    ) {
        String latestUserInput = latestUserInput(messages);
        String prompt = """
            You are the planning layer of a production-minded multi-agent assistant.
            Your only job is to inspect the conversation and decide the safest, cheapest effective execution strategy.
            Return ONLY valid JSON.

            Hard rules:
            - Never answer the user directly.
            - Prefer the simplest path that still preserves quality.
            - Use CODE_EXECUTOR only when the user explicitly asks for implementation, code, script, debugging, query or executable artifact.
            - If the user asks conceptual knowledge, scientific explanation, summary, definition, comparison or normal chat -> GENERAL_EXECUTOR.
            - If there is any doubt between conceptual vs code, choose GENERAL_EXECUTOR.
            - If the task is broad comparison, synthesis or deep analysis -> RESEARCH_EXECUTOR.
            - When reasoning mode is FAST, bias toward concise execution and fewer steps.
            - When reasoning mode is THOUGHTFUL, bias toward completeness and stronger review.
            - conversation_title must summarize the request in 4 to 5 words when possible.
            - conversation_title must start with uppercase and never be "Nova conversa".

            Return this exact JSON shape:
            {
              "intent": "<one-sentence summary>",
              "conversation_title": "<4 to 5 words, starts uppercase>",
              "task_type": "<code|conversation|research|explanation>",
              "complexity": "<low|medium|high>",
              "executor": "<CODE_EXECUTOR|GENERAL_EXECUTOR|RESEARCH_EXECUTOR>",
              "needs_tools": false,
              "response_style": "<concise|detailed|step-by-step|conversational>",
              "execution_plan": ["step 1", "step 2", "step 3"]
            }

            Reasoning mode: %s
            File attached: %s

            Recent transcript:
            %s
            """.formatted(
            reasoningMode.name(),
            hasFile,
            transcript(messages, reasoningMode.isThoughtful() ? 10 : 6)
        );

        List<String> models = modelCatalog.resolvePreferredModels(selectedModelIds, hasFile);
        OpenRouterClient.ModelCallResult result = client.callModelWithFallbackResult(models, prompt);
        return new PlannerExecution(parseJson(result.content(), latestUserInput), result.modelId());
    }

    private PlannerOutput parseJson(String raw, String fallbackIntent) {
        try {
            String cleaned = OpenRouterClient.cleanJsonEnvelope(raw);
            return mapper.readValue(cleaned, PlannerOutput.class);
        } catch (Exception exception) {
            logger.warn(
                "planner_json_parse_failed reason={} rawLength={}",
                safeMessage(exception),
                raw == null ? 0 : raw.length()
            );
            return PlannerOutput.fallback(fallbackIntent);
        }
    }

    private String safeMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "unknown_error";
        }
        String compact = exception.getMessage().replaceAll("\\s+", " ").trim();
        return compact.length() > 160 ? compact.substring(0, 160) : compact;
    }

    private String latestUserInput(List<ChatMessage> messages) {
        return messages.stream()
            .filter(message -> "user".equalsIgnoreCase(message.role()))
            .reduce((first, second) -> second)
            .map(ChatMessage::content)
            .orElse("Responder ao pedido atual do usuário");
    }

    private String transcript(List<ChatMessage> messages, int maxMessages) {
        int start = Math.max(messages.size() - maxMessages, 0);
        StringBuilder builder = new StringBuilder();
        for (int index = start; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            builder.append("- ").append(message.role()).append(": ").append(message.content()).append("\n");
            if (message.attachmentSummary() != null && !message.attachmentSummary().isBlank()) {
                builder.append("  attachment: ").append(message.attachmentSummary()).append("\n");
            }
        }
        return builder.toString().trim();
    }
}
