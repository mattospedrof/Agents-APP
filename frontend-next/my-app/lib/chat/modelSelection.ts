import type { AppConfig } from "@/lib/types";

export type RoleModelSelection = {
  plannerModelId: string;
  executorModelId: string;
  reviewerModelId: string;
};

export function displayVersion(version?: string | null) {
  const normalized = (version ?? "").trim().replace(/^v/i, "");
  return normalized ? `v${normalized}` : "v0.0.0";
}

export function uniqueModelList(models: string[]) {
  return Array.from(new Set(models.filter(Boolean)));
}

export function buildDefaultRoleSelection(config: AppConfig): RoleModelSelection {
  const [firstModel] = config.availableModels;
  const fallback = firstModel?.id ?? "";
  const defaults = config.defaultSelectedModels;

  return {
    plannerModelId: defaults[1] ?? defaults[0] ?? fallback,
    executorModelId: defaults[0] ?? defaults[1] ?? fallback,
    reviewerModelId: defaults[2] ?? defaults[1] ?? defaults[0] ?? fallback,
  };
}

export function sanitizeRoleSelection(
  raw: Partial<RoleModelSelection> | null | undefined,
  config: AppConfig
): RoleModelSelection {
  const validIds = new Set(config.availableModels.map((model) => model.id));
  const defaults = buildDefaultRoleSelection(config);

  const pick = (value: string | undefined, fallback: string) =>
    value && validIds.has(value) ? value : fallback;

  return {
    plannerModelId: pick(raw?.plannerModelId, defaults.plannerModelId),
    executorModelId: pick(raw?.executorModelId, defaults.executorModelId),
    reviewerModelId: pick(raw?.reviewerModelId, defaults.reviewerModelId),
  };
}
