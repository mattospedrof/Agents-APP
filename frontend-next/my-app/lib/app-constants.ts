export const AGENT_API_BASE_URL = "/api/agent";

export const MODEL_SELECTION_STORAGE_KEY = "fa-chat-model-selection";
export const MODEL_ROLE_SELECTION_STORAGE_KEY = "fa-chat-model-role-selection";
export const SPEED_MODE_STORAGE_KEY = "fa-chat-speed-mode";

export function displayNameStorageKey(userId: string) {
  return `fa-chat-display-name:${userId}`;
}
