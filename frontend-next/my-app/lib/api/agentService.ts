import { AGENT_API_BASE_URL } from "@/lib/app-constants";
import type { AppConfig } from "@/lib/types";

export type Conversation = {
  id: string;
  title: string;
  preview: string;
  updatedAt: string;
};

export type ChatMessagePayload = {
  role: "user" | "assistant";
  content: string;
  attachmentName?: string | null;
  attachmentSummary?: string | null;
  document?: AssistantDocument | null;
  fileContextId?: string | null;
};

export type AssistantBlock =
  | {
      type: "paragraph" | "blockquote";
      text: string;
    }
  | {
      type: "heading";
      text: string;
      level?: number | null;
    }
  | {
      type: "bulletList" | "orderedList";
      items: string[];
    }
  | {
      type: "codeBlock";
      language?: string | null;
      code: string;
    }
  | {
      type: "table";
      columns: string[];
      rows: string[][];
    }
  | {
      type: "horizontalRule";
    };

export type AssistantDocument = {
  version: number;
  blocks: AssistantBlock[];
};

export type ReasoningModePayload = "FAST" | "THOUGHTFUL";

export type AgentSessionContext = {
  userId?: string | null;
  userName?: string | null;
  userEmail?: string | null;
};

export type ActiveFile = {
  id: string;
  fileName: string;
  contentType?: string | null;
  sizeBytes: number;
};

export type ChatPayloadRequest = {
  conversationId: string | null;
  messages: ChatMessagePayload[];
  selectedModelIds: string[];
  modelSelection?: {
    plannerModelId: string;
    executorModelId: string;
    reviewerModelId: string;
  };
  reasoningMode: ReasoningModePayload;
  file?: File | null;
  activeFileContextId?: string | null;
  session?: AgentSessionContext;
};

export type ChatPayloadResponse = {
  response: string;
  conversationId: string | null;
  version: string;
  conversationTitle: string | null;
  plannerModelId: string | null;
  executorModelId: string | null;
  reviewerModelId: string | null;
  document?: AssistantDocument | null;
  activeFile?: ActiveFile | null;
  renderMeta?: {
    documentMs: number;
    repairMs: number;
    saveMs: number;
    cached: boolean;
  } | null;
};

export type ChatStreamHandlers = {
  onDelta: (chunk: string) => void;
  onDone: (response: ChatPayloadResponse) => void;
};

export type FeedbackPayloadRequest = {
  type: "LIKE" | "DISLIKE";
  responseContent: string;
  userPrompt: string;
  conversationId?: string | null;
  plannerModelId?: string | null;
  executorModelId?: string | null;
  reviewerModelId?: string | null;
  reason?: string | null;
  selectedModelIds?: string[];
  session?: AgentSessionContext;
};

function buildHeaders(session?: AgentSessionContext): HeadersInit {
  const headers: Record<string, string> = {};

  if (session?.userId) {
    headers["X-Session-User-Id"] = session.userId;
  }
  if (session?.userName) {
    headers["X-Session-User-Name"] = session.userName;
  }
  if (session?.userEmail) {
    headers["X-Session-User-Email"] = session.userEmail;
  }
  return headers;
}

async function parseResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(extractReadableError(errorBody, response.status));
  }
  return (await response.json()) as T;
}

function extractReadableError(errorBody: string, status: number): string {
  const fallback = `Request failed with status ${status}`;
  if (!errorBody) {
    return fallback;
  }

  try {
    const parsed = JSON.parse(errorBody) as {
      message?: string;
      error?: string;
      status?: number;
    };
    const message = (parsed.message ?? parsed.error ?? "").replace(/\s+/g, " ").trim();
    if (message) {
      return message.length > 240 ? `${message.slice(0, 240)}...` : message;
    }
  } catch {
    // Ignore JSON parse errors and fallback to raw text.
  }

  const compact = errorBody.replace(/\s+/g, " ").trim();
  if (!compact) {
    return fallback;
  }
  return compact.length > 240 ? `${compact.slice(0, 240)}...` : compact;
}

export async function getConfig(): Promise<AppConfig> {
  const response = await fetch(`${AGENT_API_BASE_URL}/api/config`);
  return parseResponse<AppConfig>(response);
}

export async function listConversations(session?: AgentSessionContext): Promise<Conversation[]> {
  const response = await fetch(`${AGENT_API_BASE_URL}/api/conversations`, {
    headers: buildHeaders(session),
  });
  return parseResponse<Conversation[]>(response);
}

export async function getConversation(
  conversationId: string,
  session?: AgentSessionContext
): Promise<{ id: string; title: string; messages: ChatMessagePayload[]; updatedAt: string; activeFile?: ActiveFile | null }> {
  const response = await fetch(`${AGENT_API_BASE_URL}/api/conversations/${conversationId}`, {
    headers: buildHeaders(session),
  });
  return parseResponse<{ id: string; title: string; messages: ChatMessagePayload[]; updatedAt: string; activeFile?: ActiveFile | null }>(response);
}

export async function postChat(payload: ChatPayloadRequest): Promise<ChatPayloadResponse> {
  const safeMessages = payload.messages.map((message) => ({
    role: message.role,
    content: message.content,
    attachmentName: message.attachmentName ?? null,
    attachmentSummary: message.attachmentSummary ?? null,
    fileContextId: message.fileContextId ?? null,
  }));

  const formData = new FormData();
  formData.append(
    "payload",
    JSON.stringify({
      conversationId: payload.conversationId,
      messages: safeMessages,
      selectedModelIds: payload.selectedModelIds,
      modelSelection: payload.modelSelection,
      reasoningMode: payload.reasoningMode,
      activeFileContextId: payload.activeFileContextId ?? null,
    })
  );

  if (payload.file) {
    formData.append("file", payload.file);
  }

  const response = await fetch(`${AGENT_API_BASE_URL}/api/chat`, {
    method: "POST",
    headers: buildHeaders(payload.session),
    body: formData,
  });

  return parseResponse<ChatPayloadResponse>(response);
}

export async function postChatStream(
  payload: ChatPayloadRequest,
  handlers: ChatStreamHandlers
): Promise<void> {
  const safeMessages = payload.messages.map((message) => ({
    role: message.role,
    content: message.content,
    attachmentName: message.attachmentName ?? null,
    attachmentSummary: message.attachmentSummary ?? null,
    fileContextId: message.fileContextId ?? null,
  }));

  const formData = new FormData();
  formData.append(
    "payload",
    JSON.stringify({
      conversationId: payload.conversationId,
      messages: safeMessages,
      selectedModelIds: payload.selectedModelIds,
      modelSelection: payload.modelSelection,
      reasoningMode: payload.reasoningMode,
      activeFileContextId: payload.activeFileContextId ?? null,
    })
  );

  if (payload.file) {
    formData.append("file", payload.file);
  }

  const response = await fetch(`${AGENT_API_BASE_URL}/api/chat/stream`, {
    method: "POST",
    headers: buildHeaders(payload.session),
    body: formData,
  });

  if (!response.ok || !response.body) {
    const errorBody = await response.text();
    throw new Error(extractReadableError(errorBody, response.status));
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const flushEvent = (eventChunk: string) => {
    const lines = eventChunk.split("\n");
    let eventName = "message";
    const dataLines: string[] = [];
    for (const rawLine of lines) {
      const line = rawLine.trimEnd();
      if (line.startsWith("event:")) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        const dataValue = line.slice(5);
        dataLines.push(dataValue.startsWith(" ") ? dataValue.slice(1) : dataValue);
      }
    }

    if (dataLines.length === 0) {
      return;
    }

    const payloadText = dataLines.join("\n");
    const parsed = JSON.parse(payloadText) as
      | { content?: string; message?: string }
      | ChatPayloadResponse;

    if (eventName === "delta") {
      handlers.onDelta((parsed as { content?: string }).content ?? "");
      return;
    }

    if (eventName === "done") {
      handlers.onDone(parsed as ChatPayloadResponse);
      return;
    }

    if (eventName === "error") {
      throw new Error((parsed as { message?: string }).message ?? "Falha no streaming de resposta.");
    }
  };

  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true }).replace(/\r/g, "");

    let boundary = buffer.indexOf("\n\n");
    while (boundary !== -1) {
      const eventChunk = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      if (eventChunk.trim()) {
        flushEvent(eventChunk);
      }
      boundary = buffer.indexOf("\n\n");
    }
  }

  const remaining = buffer;
  if (remaining.trim()) {
    flushEvent(remaining);
  }
}

export async function renameConversation(
  conversationId: string,
  title: string,
  session?: AgentSessionContext
): Promise<void> {
  const response = await fetch(`${AGENT_API_BASE_URL}/api/conversations/${conversationId}/title`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
      ...buildHeaders(session),
    },
    body: JSON.stringify({ title }),
  });

  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(errorBody || `Request failed with status ${response.status}`);
  }
}

export async function deleteConversation(
  conversationId: string,
  session?: AgentSessionContext
): Promise<void> {
  const response = await fetch(`${AGENT_API_BASE_URL}/api/conversations/${conversationId}`, {
    method: "DELETE",
    headers: buildHeaders(session),
  });

  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(errorBody || `Request failed with status ${response.status}`);
  }
}

export async function removeActiveFile(
  conversationId: string,
  session?: AgentSessionContext
): Promise<void> {
  const response = await fetch(`${AGENT_API_BASE_URL}/api/conversations/${conversationId}/active-file`, {
    method: "DELETE",
    headers: buildHeaders(session),
  });

  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(errorBody || `Request failed with status ${response.status}`);
  }
}

export async function submitFeedback(payload: FeedbackPayloadRequest): Promise<void> {
  const response = await fetch(`${AGENT_API_BASE_URL}/api/feedback`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...buildHeaders(payload.session),
    },
    body: JSON.stringify({
      type: payload.type,
      responseContent: payload.responseContent,
      userPrompt: payload.userPrompt,
      conversationId: payload.conversationId,
      plannerModelId: payload.plannerModelId,
      executorModelId: payload.executorModelId,
      reviewerModelId: payload.reviewerModelId,
      reason: payload.reason,
      selectedModelIds: payload.selectedModelIds,
    }),
  });

  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(errorBody || `Request failed with status ${response.status}`);
  }
}
