package com.fachat.agent;

import com.fachat.agent.dto.ChatMessage;
import com.fachat.agent.dto.ReasoningMode;
import com.fachat.agent.dto.ReviewerOutput;
import com.fachat.agent.service.ModelCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Service
public class ReviewerService {
    private static final Logger logger = LoggerFactory.getLogger(ReviewerService.class);

    public record ReviewExecution(ReviewerOutput output, String modelId) {
    }

    private final OpenRouterClient client;
    private final ObjectMapper mapper;
    private final ModelCatalogService modelCatalog;

    public ReviewerService(OpenRouterClient client, ModelCatalogService modelCatalog) {
        this.client = client;
        this.mapper = new ObjectMapper();
        this.modelCatalog = modelCatalog;
    }

    public ReviewerOutput review(
        String draft,
        List<ChatMessage> messages,
        List<String> selectedModelIds,
        ReasoningMode reasoningMode,
        boolean hasFile
    ) {
        return reviewWithTrace(draft, messages, selectedModelIds, reasoningMode, hasFile).output();
    }

    public ReviewExecution reviewWithTrace(
        String draft,
        List<ChatMessage> messages,
        List<String> selectedModelIds,
        ReasoningMode reasoningMode,
        boolean hasFile
    ) {
        String latestUserMessage = latestUserMessage(messages);
        boolean explicitCodeRequest = isExplicitCodeRequest(latestUserMessage);
        boolean explicitConceptualRequest = isExplicitConceptualRequest(latestUserMessage) && !explicitCodeRequest;
        boolean mustPreserveCode = mustPreserveCode(messages);
        boolean hasAssistantHistory = messages.stream()
            .anyMatch(message -> "assistant".equalsIgnoreCase(message.role()));

        String prompt = """
            You are the final reviewer for a user-facing assistant.
            Review the draft for:
            1. factual consistency with the conversation
            2. completeness
            3. clarity and structure
            4. safe, production-minded code quality when code exists
            5. correct Brazilian Portuguese outside code

            Keep the response economical. Improve only what matters.
            Return ONLY valid JSON:
            {
              "approved": true,
              "issues_found": [],
              "final_response": "<ready response>"
            }

            Additional rules for this draft:
            - Must preserve code blocks when the user asked for implementation or when the draft already contains useful code: %s
            - User explicitly requested code: %s
            - User explicitly requested conceptual answer: %s
            - If code is required, do not replace it with a summary, critique or explanation-only answer.
            - If the draft is missing code even though the user explicitly asked for it, rewrite final_response to include a concrete code solution.
            - If the user asked a conceptual response, ensure final_response has no code blocks, scripts or pseudo-code.
            - If the user asked for table, comparison or textual analysis, answer in prose/markdown; never use code to generate a table unless code was explicitly requested.
            - If the draft contains improper code for a textual task, rewrite it as textual markdown.
            - Keep a conversational tone in Portuguese.
            - If conversation already has assistant turns: %s, do not restart with greeting like "Oi, tudo bem?". Continue from existing context.
            - If final_response includes code, ensure it also includes a short "Como usar" section.
            - Enforce clear markdown structure with section titles and lists (ordered for steps, unordered for key points).
            - Corrija markdown invalido antes de retornar: "###1." -> "### 1.", headings em linha propria, listas em linha propria e separadores "---" isolados.
            - Remova headings vazios ("#", "##", "###") e headings duplicados sem conteudo.
            - Nao misture tabela com texto de resumo na mesma grade.
            - Se houver tabela, ela deve ter cabecalho, linha separadora e numero de colunas consistente em todas as linhas.
            - Nunca use "|" dentro de celula de tabela. Para separar itens dentro da celula, use virgula, ponto e virgula ou <br>.
            - Nao gere linhas de tabela iniciando com bullet ("- |" ou "• |").
            - Em comparacao de Supabase, Neon e Aiven, use exatamente 4 colunas: Caracteristica | Supabase | Neon | Aiven.
            - Se nao conseguir manter tabela valida, converta para lista por produto em vez de tabela quebrada.
            - Se houver "Resumo rapido", coloque fora da tabela em secao propria e use apenas quando a resposta for extensa.
            - Evite listas coladas em uma unica linha; cada item deve ficar em linha propria.
            - Evite texto cru de tabela com pipes se nao houver comparacao tabular real.
            - Avoid long unstructured paragraphs when the answer contains guidance.

            Reasoning mode: %s
            Recent conversation:
            %s

            Draft:
            %s
            """.formatted(
            mustPreserveCode,
            explicitCodeRequest,
            explicitConceptualRequest,
            hasAssistantHistory,
            reasoningMode.name(),
            transcript(messages),
            draft
        );

        OpenRouterClient.ModelCallResult result = client.callModelWithFallbackResult(
            modelCatalog.resolvePreferredModels(selectedModelIds, hasFile),
            prompt
        );
        ReviewerOutput parsed = parseJson(result.content(), draft);

        if (needsFormattingRepair(parsed.final_response())) {
            String repairPrompt = """
                Reescreva a resposta abaixo mantendo o mesmo conteudo factual, porem com markdown limpo e legivel.
                Regras obrigatorias:
                - Remova headings vazios ("#", "##", "###").
                - Corrija listas para um item por linha.
                - Corrija numeracao quebrada.
                - Se houver tabela, mantenha colunas consistentes e mova "Resumo rapido" para fora da tabela.
                - Nao use "|" dentro de celula de tabela e remova linhas de tabela iniciando com bullet ("- |" ou "• |").
                - Se a tabela continuar inconsistente, converta para lista organizada por produto.
                - Preserve tom humano e colaborativo em portugues do Brasil.
                - Nao adicione codigo se nao existir no texto original.

                Resposta:
                %s
                """.formatted(parsed.final_response());
            OpenRouterClient.ModelCallResult repairResult = client.callModelWithFallbackResult(
                modelCatalog.resolvePreferredModels(selectedModelIds, hasFile),
                repairPrompt
            );
            parsed = new ReviewerOutput(
                parsed.approved(),
                parsed.issues_found(),
                repairResult.content()
            );
            return new ReviewExecution(parsed, repairResult.modelId());
        }

        if (explicitConceptualRequest && looksLikeCodeHeavy(parsed.final_response())) {
            String rewritePrompt = """
                Reescreva a resposta abaixo para formato conceitual em portugues do Brasil.
                Regras obrigatorias:
                - Nao use blocos de codigo, pseudo-codigo, scripts ou comandos.
                - Preserve os fatos corretos e a estrutura de aprendizado.
                - Use markdown com secoes e listas curtas.

                Resposta:
                %s
                """.formatted(parsed.final_response());
            OpenRouterClient.ModelCallResult rewriteResult = client.callModelWithFallbackResult(
                modelCatalog.resolvePreferredModels(selectedModelIds, hasFile),
                rewritePrompt
            );
            parsed = new ReviewerOutput(
                parsed.approved(),
                parsed.issues_found(),
                rewriteResult.content()
            );
            return new ReviewExecution(parsed, rewriteResult.modelId());
        }

        return new ReviewExecution(parsed, result.modelId());
    }

    private boolean needsFormattingRepair(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }

        String normalized = content.replace("\r", "");
        if (normalized.matches("(?s)(?m).*^\\s*#{1,6}\\s*$.*")) {
            return true;
        }
        if (normalized.matches("(?s).*\\|\\s*resumo\\s+rapido\\s*[:\\-].*")) {
            return true;
        }
        if (normalized.contains("###1.") || normalized.contains("##1.") || normalized.contains(".-")) {
            return true;
        }
        if (hasLineWithMultipleOrderedMarkers(normalized)) {
            return true;
        }
        if (normalized.matches("(?m).*^[\\s>*\\-•]+\\|\\s*$.*")) {
            return true;
        }
        return hasInconsistentTableColumns(normalized);
    }

    private boolean hasLineWithMultipleOrderedMarkers(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("|")) {
                continue;
            }

            int occurrences = 0;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b\\d+\\.").matcher(trimmed);
            while (matcher.find()) {
                occurrences++;
                if (occurrences >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasInconsistentTableColumns(String content) {
        int expectedColumns = -1;
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("- |") || line.startsWith("• |")) {
                return true;
            }
            if (!line.contains("|")) {
                continue;
            }
            if (!line.startsWith("|") || !line.endsWith("|")) {
                continue;
            }

            int pipes = (int) line.chars().filter(character -> character == '|').count();
            int columns = Math.max(0, pipes - 1);
            if (columns < 2) {
                continue;
            }
            if (expectedColumns == -1) {
                expectedColumns = columns;
                continue;
            }
            if (columns != expectedColumns) {
                return true;
            }
        }
        return false;
    }

    private ReviewerOutput parseJson(String raw, String draft) {
        try {
            String cleaned = OpenRouterClient.cleanJsonEnvelope(raw);
            return mapper.readValue(cleaned, ReviewerOutput.class);
        } catch (Exception exception) {
            logger.warn(
                "reviewer_json_parse_failed reason={} rawLength={}",
                safeMessage(exception),
                raw == null ? 0 : raw.length()
            );
            return ReviewerOutput.fallback(draft);
        }
    }

    private String safeMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "unknown_error";
        }
        String compact = exception.getMessage().replaceAll("\\s+", " ").trim();
        return compact.length() > 160 ? compact.substring(0, 160) : compact;
    }

    private String transcript(List<ChatMessage> messages) {
        int start = Math.max(messages.size() - 8, 0);
        StringBuilder builder = new StringBuilder();
        for (int index = start; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            builder.append("- ").append(message.role()).append(": ").append(message.content()).append("\n");
        }
        return builder.toString().trim();
    }

    private boolean mustPreserveCode(List<ChatMessage> messages) {
        return isExplicitCodeRequest(latestUserMessage(messages));
    }

    private String latestUserMessage(List<ChatMessage> messages) {
        return messages.stream()
            .filter(message -> "user".equalsIgnoreCase(message.role()))
            .reduce((first, second) -> second)
            .map(ChatMessage::content)
            .orElse("");
    }

    private boolean isExplicitCodeRequest(String latestUserMessage) {
        String normalized = normalizeForMatching(latestUserMessage);
        return containsAny(normalized, List.of(
            "codigo",
            "funcao",
            "script",
            "classe",
            "mostre o codigo",
            "me mostre o codigo",
            "implemente",
            "query",
            "query sql",
            "script sql",
            "consulta sql",
            "select",
            "insert",
            "update ",
            "delete ",
            "migration",
            "debug"
        ));
    }

    private boolean isExplicitConceptualRequest(String latestUserMessage) {
        String normalized = normalizeForMatching(latestUserMessage);
        return containsAny(normalized, List.of(
            "o que e",
            "o que sao",
            "explique",
            "resuma",
            "defina",
            "conceito",
            "significado",
            "diferenca",
            "como funciona",
            "quais sao",
            "porque",
            "causas",
            "sintomas",
            "tratamento"
        ));
    }

    private boolean looksLikeCodeHeavy(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        if (content.contains("```")) {
            return true;
        }
        String normalized = normalizeForMatching(content);
        return containsAny(normalized, List.of(
            "public static",
            "class ",
            "def ",
            "function ",
            "import ",
            "return ",
            "console log",
            "system out"
        ));
    }

    private boolean containsAny(String value, List<String> terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForMatching(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}\\s]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
