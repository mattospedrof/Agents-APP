import type { ModelOption } from "@/lib/types";

export const FALLBACK_MODELS: ModelOption[] = [
  {
    id: "qwen/qwen3-coder:free",
    label: "Qwen Coder",
    description: "Mais indicado para programação, debugging e snippets longos.",
    supportsFiles: false,
    recommendedFor: ["code", "review"],
  },
  {
    id: "openai/gpt-oss-120b:free",
    label: "GPT-OSS 120B",
    description: "Bom equilíbrio entre clareza, contexto e tarefas gerais.",
    supportsFiles: true,
    recommendedFor: ["conversation", "research", "files"],
  },
  {
    id: "nvidia/nemotron-3-super-120b-a12b:free",
    label: "Nemotron 3 Super",
    description: "Útil para respostas completas e revisão final.",
    supportsFiles: false,
    recommendedFor: ["research", "review"],
  },
  {
    id: "google/gemma-4-31b-it:free",
    label: "Gemma 4 31B",
    description: "Alternativa econômica para apoio geral e fallback.",
    supportsFiles: false,
    recommendedFor: ["conversation"],
  },
  {
    id: "poolside/laguna-xs.2:free",
    label: "Laguna XS",
    description: "Fallback enxuto para tarefas gerais e de pesquisa.",
    supportsFiles: false,
    recommendedFor: ["conversation", "research"],
  },
];

export const FALLBACK_SELECTED_MODELS = [
  "openai/gpt-oss-120b:free",
  "openai/gpt-oss-120b:free",
  "openai/gpt-oss-120b:free",
];

export const FALLBACK_FILE_MODEL_ID = "openai/gpt-oss-120b:free";
