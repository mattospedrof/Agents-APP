package com.fachat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

public class OpenRouterClient {
    private static final Logger logger = LoggerFactory.getLogger(OpenRouterClient.class);

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenRouterClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.mapper = new ObjectMapper();
    }

    public record OpenRouterModel(
        String id,
        String name,
        String description,
        boolean freeByIdVariant,
        boolean freeByPricing
    ) {
        public boolean isFree() {
            return freeByIdVariant || freeByPricing;
        }
    }

    public record ModelCallResult(String modelId, String content) {
    }

    public String callModelWithFallback(List<String> models, String prompt) {
        return callModelWithFallbackResult(models, prompt).content();
    }

    public ModelCallResult callModelWithFallbackResult(List<String> models, String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("OPENROUTER_API_KEY não configurada.");
        }

        Exception lastError = null;
        for (String model : models) {
            try {
                logger.info("{\"event\":\"openrouter_try\",\"model\":\"{}\"}", model);
                return new ModelCallResult(model, callModel(model, prompt));
            } catch (Exception exception) {
                logger.warn(
                    "{\"event\":\"openrouter_fallback\",\"model\":\"{}\",\"reason\":\"{}\"}",
                    model,
                    safeMessage(exception)
                );
                lastError = exception;
            }
        }

        if (lastError == null) {
            throw new RuntimeException("Nenhum modelo foi fornecido.");
        }
        throw new RuntimeException("Todos os modelos falharam. Último erro: " + lastError.getMessage(), lastError);
    }

    public ModelCallResult callModelWithFallbackStreamResult(
        List<String> models,
        String prompt,
        Consumer<String> onDelta
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("OPENROUTER_API_KEY nÃ£o configurada.");
        }

        Exception lastError = null;
        for (String model : models) {
            try {
                logger.info("{\"event\":\"openrouter_stream_try\",\"model\":\"{}\"}", model);
                return new ModelCallResult(model, callModelStream(model, prompt, onDelta));
            } catch (Exception exception) {
                logger.warn(
                    "{\"event\":\"openrouter_stream_fallback\",\"model\":\"{}\",\"reason\":\"{}\"}",
                    model,
                    safeMessage(exception)
                );
                lastError = exception;
            }
        }

        if (lastError == null) {
            throw new RuntimeException("Nenhum modelo foi fornecido.");
        }
        throw new RuntimeException("Todos os modelos falharam. Ãšltimo erro: " + lastError.getMessage(), lastError);
    }

    public String callModel(String model, String prompt) {
        try {
            long startedAt = System.nanoTime();
            ObjectNode root = mapper.createObjectNode();
            root.put("model", model);

            ArrayNode messages = root.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            message.put("content", prompt);

            String body = mapper.writeValueAsString(root);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                logger.warn(
                    "{\"event\":\"openrouter_response\",\"model\":\"{}\",\"statusCode\":{},\"latencyMs\":{}}",
                    model,
                    response.statusCode(),
                    durationMs
                );
                throw new RuntimeException("HTTP " + response.statusCode() + " no OpenRouter");
            }

            if (response.statusCode() != 200) {
                logger.warn(
                    "{\"event\":\"openrouter_response\",\"model\":\"{}\",\"statusCode\":{},\"latencyMs\":{}}",
                    model,
                    response.statusCode(),
                    durationMs
                );
                throw new RuntimeException("Status inesperado: " + response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            if (json.has("error")) {
                String messageText = json.get("error").path("message").asText("unknown error");
                throw new RuntimeException("API error: " + messageText);
            }

            JsonNode choices = json.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Resposta sem choices");
            }

            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new RuntimeException("Content vazio");
            }

            logger.info(
                "{\"event\":\"openrouter_response\",\"model\":\"{}\",\"statusCode\":{},\"latencyMs\":{},\"responseLength\":{}}",
                model,
                response.statusCode(),
                durationMs,
                content.asText().length()
            );
            return content.asText();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Erro ao chamar " + model + ": " + exception.getMessage(), exception);
        }
    }

    public String callModelStream(String model, String prompt, Consumer<String> onDelta) {
        try {
            long startedAt = System.nanoTime();
            ObjectNode root = mapper.createObjectNode();
            root.put("model", model);
            root.put("stream", true);

            ArrayNode messages = root.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            message.put("content", prompt);

            String body = mapper.writeValueAsString(root);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
            );
            long headerLatencyMs = (System.nanoTime() - startedAt) / 1_000_000;

            if (response.statusCode() != 200) {
                logger.warn(
                    "{\"event\":\"openrouter_stream_response\",\"model\":\"{}\",\"statusCode\":{},\"latencyMs\":{}}",
                    model,
                    response.statusCode(),
                    headerLatencyMs
                );
                throw new RuntimeException("Status inesperado: " + response.statusCode());
            }

            StringBuilder fullResponse = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) {
                        continue;
                    }

                    String payload = trimmed.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }

                    JsonNode chunk = mapper.readTree(payload);
                    String delta = extractDeltaContent(chunk);
                    if (!delta.isBlank()) {
                        fullResponse.append(delta);
                        onDelta.accept(delta);
                    }
                }
            }

            if (fullResponse.length() == 0) {
                throw new RuntimeException("Resposta stream vazia");
            }
            long totalDurationMs = (System.nanoTime() - startedAt) / 1_000_000;
            logger.info(
                "{\"event\":\"openrouter_stream_response\",\"model\":\"{}\",\"statusCode\":{},\"latencyMs\":{},\"responseLength\":{}}",
                model,
                response.statusCode(),
                totalDurationMs,
                fullResponse.length()
            );
            return fullResponse.toString();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Erro ao chamar " + model + " em stream: " + exception.getMessage(), exception);
        }
    }

    public List<OpenRouterModel> listFreeModels() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/models"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(35))
                .GET();

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Status inesperado ao listar modelos: " + response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            JsonNode data = json.path("data");
            if (!data.isArray()) {
                throw new RuntimeException("Resposta sem lista de modelos.");
            }

            List<OpenRouterModel> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = text(node, "id");
                if (id.isBlank()) {
                    continue;
                }

                String name = text(node, "name");
                String description = text(node, "description");
                boolean freeByIdVariant = id.toLowerCase(Locale.ROOT).contains(":free");
                boolean freeByPricing = isFreeByPricing(node.path("pricing"));
                OpenRouterModel model = new OpenRouterModel(
                    id,
                    name,
                    description,
                    freeByIdVariant,
                    freeByPricing
                );
                if (model.isFree()) {
                    models.add(model);
                }
            }

            models.sort(Comparator.comparing(OpenRouterModel::id));
            return List.copyOf(models);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Erro ao listar modelos no OpenRouter: " + exception.getMessage(), exception);
        }
    }

    public static String cleanJsonEnvelope(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```\\w*\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private String text(JsonNode node, String field) {
        return Optional.ofNullable(node.get(field))
            .map(JsonNode::asText)
            .orElse("")
            .trim();
    }

    private boolean isFreeByPricing(JsonNode pricing) {
        if (pricing == null || pricing.isMissingNode() || pricing.isNull()) {
            return false;
        }

        return isZeroPrice(pricing.get("prompt"))
            && isZeroPrice(pricing.get("completion"));
    }

    private boolean isZeroPrice(JsonNode valueNode) {
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return false;
        }

        String value = valueNode.asText("").trim();
        if (value.isBlank()) {
            return false;
        }

        try {
            return Double.parseDouble(value) == 0d;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String extractDeltaContent(JsonNode chunk) {
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }

        JsonNode delta = choices.get(0).path("delta");
        JsonNode contentNode = delta.get("content");
        if (contentNode == null || contentNode.isNull() || contentNode.isMissingNode()) {
            return "";
        }

        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }

        if (contentNode.isArray()) {
            StringBuilder content = new StringBuilder();
            for (JsonNode part : contentNode) {
                String text = part.path("text").asText("");
                if (!text.isBlank()) {
                    content.append(text);
                }
            }
            return content.toString();
        }
        return "";
    }

    private String safeMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "unknown_error";
        }
        String compact = exception.getMessage().replaceAll("\\s+", " ").trim();
        return compact.length() > 180 ? compact.substring(0, 180) : compact;
    }
}
