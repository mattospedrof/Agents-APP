package com.fachat.agent.service;

import org.springframework.stereotype.Service;

import com.fachat.agent.OpenRouterClient;
import com.fachat.agent.config.AgentProperties;
import com.fachat.agent.dto.ModelOption;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ModelCatalogService {

    public record ModelDefinition(
        String id,
        String label,
        String description,
        boolean supportsFiles,
        List<String> recommendedFor
    ) {
    }

    private final AgentProperties properties;
    private final OpenRouterClient openRouterClient;
    private final Duration cacheTtl = Duration.ofMinutes(20);

    private final List<ModelDefinition> fallbackModels = List.of(
        new ModelDefinition(
            "openai/gpt-oss-120b:free",
            "GPT-OSS 120B",
            "Bom equilibrio entre clareza, contexto e tarefas gerais.",
            true,
            List.of("conversation", "research", "files")
        ),
        new ModelDefinition(
            "qwen/qwen3-coder:free",
            "Qwen Coder",
            "Mais indicado para programacao, debugging e snippets longos.",
            false,
            List.of("code", "review")
        ),
        new ModelDefinition(
            "nvidia/nemotron-3-super-120b-a12b:free",
            "Nemotron 3 Super",
            "Util para respostas completas e revisao final.",
            false,
            List.of("research", "review")
        ),
        new ModelDefinition(
            "google/gemma-4-31b-it:free",
            "Gemma 4 31B",
            "Alternativa economica para apoio geral e fallback.",
            false,
            List.of("conversation")
        ),
        new ModelDefinition(
            "poolside/laguna-xs.2:free",
            "Laguna XS",
            "Fallback enxuto para tarefas gerais e de pesquisa.",
            false,
            List.of("conversation", "research")
        )
    );

    private volatile List<ModelDefinition> cachedModels = List.of();
    private volatile Instant cachedAt;

    public ModelCatalogService(AgentProperties properties, OpenRouterClient openRouterClient) {
        this.properties = properties;
        this.openRouterClient = openRouterClient;
    }

    public List<ModelOption> listOptions() {
        return currentModels().stream()
            .map(model -> new ModelOption(
                model.id(),
                model.label(),
                model.description(),
                model.supportsFiles(),
                model.recommendedFor()
            ))
            .toList();
    }

    public List<String> defaultSelection() {
        List<String> ids = currentModels().stream().map(ModelDefinition::id).toList();
        String gptOss = pickPreferred(ids, List.of("openai/gpt-oss-120b:free", "gpt-oss"), 0);
        return List.of(gptOss, gptOss, gptOss);
    }

    public List<String> resolvePreferredModels(List<String> selectedModelIds, boolean hasFile) {
        List<ModelDefinition> activeModels = currentModels();
        Set<String> preferred = new LinkedHashSet<>();
        List<String> requested = (selectedModelIds == null || selectedModelIds.isEmpty())
            ? defaultSelection()
            : selectedModelIds;

        if (hasFile) {
            String fileModelId = getFileUploadModelId();
            if (isKnownModel(fileModelId)) {
                preferred.add(fileModelId);
            }
        }

        preferred.addAll(
            requested.stream()
                .filter(this::isKnownModel)
                .toList()
        );

        List<String> fallbacks = activeModels.stream()
            .map(ModelDefinition::id)
            .filter(modelId -> !preferred.contains(modelId))
            .collect(Collectors.toCollection(ArrayList::new));

        preferred.addAll(fallbacks);
        return List.copyOf(preferred);
    }

    public boolean supportsFiles(String modelId) {
        return currentModels().stream()
            .anyMatch(model -> model.id().equals(modelId) && model.supportsFiles());
    }

    public boolean isKnownModel(String modelId) {
        return currentModels().stream().anyMatch(model -> model.id().equals(modelId));
    }

    public String getFileUploadModelId() {
        List<ModelDefinition> models = currentModels();
        String configured = properties.getFileUploadModelId();
        if (configured != null && !configured.isBlank() && isKnownModel(configured) && !isChatIncompatibleModel(configured, "", "")) {
            return configured;
        }
        return pickPreferred(
            models.stream().map(ModelDefinition::id).toList(),
            List.of("openai/gpt-oss-120b:free", "gpt-oss"),
            0
        );
    }

    private List<ModelDefinition> currentModels() {
        if (!properties.isModelCatalogDynamicEnabled()) {
            return fallbackWithFileModel();
        }

        Instant now = Instant.now();
        Instant snapshot = cachedAt;
        if (!cachedModels.isEmpty() && snapshot != null && Duration.between(snapshot, now).compareTo(cacheTtl) < 0) {
            return cachedModels;
        }
        return refreshDynamicModels();
    }

    private synchronized List<ModelDefinition> refreshDynamicModels() {
        Instant now = Instant.now();
        if (!cachedModels.isEmpty() && cachedAt != null && Duration.between(cachedAt, now).compareTo(cacheTtl) < 0) {
            return cachedModels;
        }

        List<ModelDefinition> models = buildDynamicModelList();
        cachedModels = models;
        cachedAt = now;
        return models;
    }

    private List<ModelDefinition> buildDynamicModelList() {
        try {
            List<OpenRouterClient.OpenRouterModel> freeModels = openRouterClient.listFreeModels();
            if (freeModels.isEmpty()) {
                return fallbackWithFileModel();
            }

            Map<String, ModelDefinition> byId = new HashMap<>();
            for (OpenRouterClient.OpenRouterModel model : freeModels) {
                if (model.id() == null || model.id().isBlank()) {
                    continue;
                }

                String id = model.id().trim();
                if (!isStrictFreeId(id)
                    || isExcludedModel(id, model.name())
                    || isChatIncompatibleModel(id, model.name(), model.description())) {
                    continue;
                }

                byId.put(id, new ModelDefinition(
                    id,
                    deriveLabel(model),
                    deriveDescription(model),
                    id.equals(properties.getFileUploadModelId()),
                    recommendedTagsFromId(id)
                ));
            }

            applyAllowList(byId);
            if (byId.isEmpty()) {
                return fallbackWithFileModel();
            }

            ensureFileModel(byId);

            return byId.values().stream()
                .sorted(Comparator.comparing(ModelDefinition::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
        } catch (Exception ignored) {
            return fallbackWithFileModel();
        }
    }

    private boolean isStrictFreeId(String modelId) {
        return modelId != null && modelId.toLowerCase(Locale.ROOT).contains(":free");
    }

    private boolean isExcludedModel(String modelId, String modelName) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        String name = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        return id.contains("free-models-router") || name.contains("free models router");
    }

    private boolean isChatIncompatibleModel(String modelId, String modelName, String description) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        String name = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        String desc = description == null ? "" : description.toLowerCase(Locale.ROOT);

        boolean embeddingLike = id.contains("embed")
            || name.contains("embed")
            || desc.contains("embedding");
        boolean rerankLike = id.contains("rerank")
            || name.contains("rerank")
            || desc.contains("rerank");

        return embeddingLike || rerankLike;
    }

    private List<ModelDefinition> fallbackWithFileModel() {
        Map<String, ModelDefinition> byId = fallbackModels.stream()
            .collect(Collectors.toMap(ModelDefinition::id, model -> model, (left, right) -> left, HashMap::new));
        applyAllowList(byId);
        ensureFileModel(byId);
        return byId.values().stream()
            .sorted(Comparator.comparing(ModelDefinition::label, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private void ensureFileModel(Map<String, ModelDefinition> byId) {
        String fileModelId = properties.getFileUploadModelId();
        if (fileModelId == null
            || fileModelId.isBlank()
            || !isStrictFreeId(fileModelId)
            || isChatIncompatibleModel(fileModelId, "", "")) {
            return;
        }

        ModelDefinition existing = byId.get(fileModelId);
        if (existing != null) {
            byId.put(fileModelId, new ModelDefinition(
                existing.id(),
                existing.label(),
                existing.description(),
                true,
                existing.recommendedFor()
            ));
            return;
        }

        byId.put(fileModelId, new ModelDefinition(
            fileModelId,
            humanizeModelLabel(fileModelId),
            "Modelo configurado para leitura de arquivo anexado.",
            true,
            List.of("files")
        ));
    }

    private String pickPreferred(List<String> modelIds, List<String> hints, int fallbackIndex) {
        for (String modelId : modelIds) {
            String lower = modelId.toLowerCase(Locale.ROOT);
            if (hints.stream().anyMatch(lower::contains)) {
                return modelId;
            }
        }

        if (modelIds.isEmpty()) {
            return properties.getFileUploadModelId();
        }

        int safeIndex = Math.min(Math.max(fallbackIndex, 0), modelIds.size() - 1);
        return modelIds.get(safeIndex);
    }

    private String deriveLabel(OpenRouterClient.OpenRouterModel model) {
        if (model.name() != null && !model.name().isBlank()) {
            String cleaned = model.name().replace("(free)", "").trim();
            return cleaned.isBlank() ? humanizeModelLabel(model.id()) : cleaned;
        }
        return humanizeModelLabel(model.id());
    }

    private String deriveDescription(OpenRouterClient.OpenRouterModel model) {
        if (model.description() != null && !model.description().isBlank()) {
            String oneLine = model.description().replaceAll("\\s+", " ").trim();
            if (oneLine.length() > 110) {
                return oneLine.substring(0, 110).trim() + "...";
            }
            return oneLine;
        }
        return "Modelo gratuito obtido dinamicamente no OpenRouter.";
    }

    private List<String> recommendedTagsFromId(String modelId) {
        String lower = modelId.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tags = new LinkedHashSet<>();

        if (lower.contains("code") || lower.contains("coder")) {
            tags.add("code");
        }
        if (lower.contains("reason") || lower.contains("think")) {
            tags.add("review");
        }
        if (lower.contains("search") || lower.contains("research")) {
            tags.add("research");
        }
        tags.add("conversation");

        return List.copyOf(tags);
    }

    private String humanizeModelLabel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "Modelo";
        }
        String slug = modelId.contains("/") ? modelId.substring(modelId.indexOf('/') + 1) : modelId;
        slug = slug.replace(":free", "");
        String[] chunks = slug.split("[-_\\.]");
        StringBuilder label = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk.isBlank()) {
                continue;
            }
            if (label.length() > 0) {
                label.append(" ");
            }
            label.append(Character.toUpperCase(chunk.charAt(0)))
                .append(chunk.substring(1));
        }
        return label.length() == 0 ? modelId : label.toString();
    }

    private void applyAllowList(Map<String, ModelDefinition> byId) {
        Set<String> allowed = properties.getAllowedModelIds() == null
            ? Set.of()
            : properties.getAllowedModelIds().stream()
                .filter(modelId -> modelId != null && !modelId.isBlank())
                .collect(Collectors.toSet());

        if (allowed.isEmpty()) {
            return;
        }
        byId.entrySet().removeIf(entry -> !allowed.contains(entry.getKey()));
    }
}
