"use client";

import Image from "next/image";
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
  isValidElement,
} from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneDark } from "react-syntax-highlighter/dist/esm/styles/prism";
import { useSession, signIn, signOut } from "next-auth/react";
import {
  MODEL_ROLE_SELECTION_STORAGE_KEY,
  SPEED_MODE_STORAGE_KEY,
  displayNameStorageKey,
} from "@/lib/app-constants";
import {
  deleteConversation,
  getConfig,
  getConversation,
  listConversations,
  postChatStream,
  renameConversation,
  removeActiveFile,
  submitFeedback,
  type AgentSessionContext,
  type ActiveFile,
  type AssistantDocument,
  type ChatPayloadResponse,
} from "@/lib/api/agentService";
import {
  FALLBACK_FILE_MODEL_ID,
  FALLBACK_MODELS,
  FALLBACK_SELECTED_MODELS,
} from "@/lib/model-fallbacks";
import type { AppConfig } from "@/lib/types";

type ChatRole = "user" | "assistant";
type SpeedMode = "fast" | "thoughtful";

type ChatMessage = {
  role: ChatRole;
  content: string;
  attachmentName?: string | null;
  attachmentSummary?: string | null;
  fileContextId?: string | null;
  document?: AssistantDocument | null;
  responseTrace?: {
    plannerModelId: string | null;
    executorModelId: string | null;
    reviewerModelId: string | null;
  } | null;
};

type MarkdownCodeProps = {
  children?: ReactNode;
  className?: string;
};

type ConversationSummary = {
  id: string;
  title: string;
  preview: string;
  updatedAt: string;
};

type LocalConversation = ConversationSummary & {
  messages: ChatMessage[];
  activeFile?: ActiveFile | null;
  activeLocalFile?: File | null;
  titleLocked?: boolean;
};

type RoleModelSelection = {
  plannerModelId: string;
  executorModelId: string;
  reviewerModelId: string;
};

type ConversationMenuContext = "expanded" | "collapsed";
type ConversationMenuPosition = {
  left: number;
  top: number;
};

type ModelDropdownProps = {
  label: string;
  value: string;
  options: AppConfig["availableModels"];
  disabled?: boolean;
  isOpen: boolean;
  onToggle: () => void;
  onSelect: (modelId: string) => void;
};

type AssistantDocumentValidation = {
  valid: boolean;
  reason: string;
};

const LONG_USER_MESSAGE_CHARS = 800;
const LONG_USER_MESSAGE_LINES = 10;
const LONG_USER_MESSAGE_PREVIEW_CHARS = 500;
const BUILD_APP_VERSION = process.env.NEXT_PUBLIC_APP_VERSION;

function displayVersion(version?: string | null) {
  const normalized = (version ?? "").trim().replace(/^v/i, "");
  return normalized ? `v${normalized}` : "v0.0.0";
}

function uniqueModelList(models: string[]) {
  return Array.from(new Set(models.filter(Boolean)));
}

function buildDefaultRoleSelection(config: AppConfig): RoleModelSelection {
  const [firstModel] = config.availableModels;
  const fallback = firstModel?.id ?? "";
  const defaults = config.defaultSelectedModels;

  return {
    plannerModelId: defaults[1] ?? defaults[0] ?? fallback,
    executorModelId: defaults[0] ?? defaults[1] ?? fallback,
    reviewerModelId: defaults[2] ?? defaults[1] ?? defaults[0] ?? fallback,
  };
}

function sanitizeRoleSelection(
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

function ModelDropdown({
  label,
  value,
  options,
  disabled = false,
  isOpen,
  onToggle,
  onSelect,
}: ModelDropdownProps) {
  const selectedOption =
    options.find((model) => model.id === value) ??
    options[0] ??
    null;

  return (
    <div className="space-y-1.5">
      <label className="text-[11px] uppercase tracking-[0.18em] text-slate-400">
        {label}
      </label>
      <div className="relative" data-model-dropdown>
        <button
          type="button"
          onClick={onToggle}
          disabled={disabled}
          className="flex w-full items-center justify-between gap-2 rounded-lg border border-white/10 bg-[rgba(3,9,24,0.9)] px-3 py-2 text-left text-sm text-white shadow-[inset_0_0_0_1px_rgba(148,163,184,0.05)] outline-none transition duration-200 hover:border-sky-300/26 hover:bg-[rgba(7,15,36,0.95)] focus-visible:ring-2 focus-visible:ring-sky-300/35 disabled:cursor-not-allowed disabled:opacity-60"
          aria-haspopup="listbox"
          aria-expanded={isOpen}
        >
          <span className="truncate">{selectedOption?.label ?? "Selecione um modelo"}</span>
          <svg
            viewBox="0 0 20 20"
            fill="none"
            className={`h-4 w-4 shrink-0 text-slate-400 transition ${isOpen ? "rotate-180" : ""}`}
            aria-hidden="true"
          >
            <path
              d="M5 7.5L10 12.5L15 7.5"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>

        {isOpen && !disabled ? (
          <div
            role="listbox"
            className="scroll-shell absolute z-40 mt-2 max-h-60 w-full overflow-y-auto rounded-lg border border-white/12 bg-[rgba(4,10,27,0.98)] p-1.5 shadow-[inset_0_0_0_1px_rgba(148,163,184,0.08),0_24px_48px_rgba(2,6,23,0.62)]"
          >
            {options.map((model) => {
              const isSelected = model.id === value;
              return (
                <button
                  key={`${label}-${model.id}`}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  onClick={() => onSelect(model.id)}
                  className={`w-full rounded-md px-2.5 py-2 text-left transition ${
                    isSelected
                      ? "border border-sky-300/30 bg-sky-400/12 text-sky-100"
                      : "border border-transparent text-slate-200 hover:bg-white/6"
                  }`}
                >
                  <p className="truncate text-sm font-medium">{model.label}</p>
                  <p className="mt-0.5 text-[11px] leading-4 text-slate-400">
                    {model.description}
                  </p>
                </button>
              );
            })}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function AssistantAvatar() {
  return (
    <div className="flex h-11 w-11 items-center justify-center rounded-2xl border border-sky-400/20 bg-gradient-to-br from-sky-400 to-blue-600 text-sm font-bold text-white shadow-[0_0_35px_rgba(59,130,246,0.32)]">
      AI
    </div>
  );
}

function UserAvatar({ label }: { label: string }) {
  return (
    <div className="flex h-11 w-11 items-center justify-center rounded-2xl border border-white/10 bg-white/10 text-sm font-bold text-white">
      {label}
    </div>
  );
}

function TypingIndicator() {
  return (
    <div className="flex w-fit items-center gap-1 rounded-2xl border border-white/10 bg-[rgba(8,15,33,0.82)] px-4 py-3 shadow-xl">
      <span className="h-2 w-2 animate-bounce rounded-full bg-slate-300 [animation-delay:-0.25s]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-slate-300 [animation-delay:-0.1s]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-slate-300" />
    </div>
  );
}

function firstNameOf(name: string) {
  return name.trim().split(/\s+/)[0] || "você";
}

function shortLabelOf(name: string) {
  return firstNameOf(name).slice(0, 1).toUpperCase() || "U";
}

function formatConversationDate(value: string) {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function nodeToText(node: ReactNode): string {
  if (typeof node === "string" || typeof node === "number") {
    return String(node);
  }
  if (Array.isArray(node)) {
    return node.map(nodeToText).join("");
  }
  if (isValidElement<{ children?: ReactNode }>(node)) {
    return nodeToText(node.props.children);
  }
  return "";
}

const MARKDOWN_CALLOUT_PREFIX_REGEX =
  /^(resumo(?:\s+r[aá]pido)?|em resumo|conclus[aã]o|pr[oó]ximos?\s+passos?|observa[cç][aã]o|nota|dica pr[aá]tica)\s*:/i;

function normalizeHeadingLabel(text: string) {
  return text
    .replace(/^\s*#{1,6}\s*/g, "")
    .replace(/^[-\u2013\u2014]\s*/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

function headingTextFromChildren(children: ReactNode) {
  return normalizeHeadingLabel(nodeToText(children));
}

function isCalloutParagraph(children: ReactNode) {
  const compact = normalizeConversationText(nodeToText(children));
  return MARKDOWN_CALLOUT_PREFIX_REGEX.test(compact);
}

function emptyAssistantText() {
  return "Não consegui gerar a resposta agora. Tente novamente em instantes.";
}

function normalizeConversationText(value: string) {
  return value.replace(/\s+/g, " ").trim();
}

const TITLE_STOP_WORDS = new Set([
  "a",
  "o",
  "as",
  "os",
  "de",
  "da",
  "do",
  "das",
  "dos",
  "e",
  "em",
  "para",
  "por",
  "com",
  "sem",
  "um",
  "uma",
  "uns",
  "umas",
  "que",
  "como",
  "qual",
  "quais",
  "sobre",
  "rapidamente",
  "me",
  "mim",
  "favor",
  "pode",
  "ser",
  "sao",
  "são",
  "diga",
  "fale",
  "falar",
  "explique",
  "explica",
  "mostre",
  "compare",
  "comparar",
  "comparacao",
  "comparação",
  "segue",
  "aqui",
  "esta",
  "está",
  "opcao",
  "opção",
  "resumo",
  "geral",
  "topico",
  "topicos",
  "tabela",
  "simples",
  "passos",
  "numerados",
  "definicao",
  "definição",
  "vantagens",
  "cuidados",
]);

const TITLE_GREETING_PREFIX_REGEX =
  /^(oi|ola|olá|e ai|e aí|bom dia|boa tarde|boa noite)\b[\s,!:.-]*/i;

const TITLE_COMMAND_PREFIX_REGEX =
  /^(oi|ola|olá|e ai|e aí|bom dia|boa tarde|boa noite)?[\s,!:.-]*(me\s+)?(diga|fale|explique|explica|mostre|me explica|me fale)\b[\s,!:.-]*/i;

function sanitizeTitleSourceText(source: string) {
  const withoutGreeting = source
    .replace(TITLE_GREETING_PREFIX_REGEX, "")
    .trim();

  const cleaned = (withoutGreeting || source)
    .replace(TITLE_COMMAND_PREFIX_REGEX, "")
    .replace(/^(o que (é|eh)|sobre)\b[\s,!:.-]*/i, "")
    .replace(/\b(em\s+t[oó]picos?|resumid[oa]s?|detalhad[oa]s?|com\s+exemplos?)\b/gi, " ")
    .replace(/\s*[-–—]\s*$/g, "")
    .replace(/\s+/g, " ")
    .trim();

  return cleaned || withoutGreeting || source;
}

function buildShortTitleFromText(source: string) {
  const cleaned = sanitizeTitleSourceText(source);
  const words = cleaned
    .split(/\s+/)
    .map((word) => word.replace(/^[^A-Za-z0-9À-ÿ]+|[^A-Za-z0-9À-ÿ]+$/g, ""))
    .filter(Boolean);

  if (words.length === 0) {
    return "Nova conversa";
  }

  const meaningfulWords = words.filter((word) => {
    const normalizedWord = word.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
    return normalizedWord.length > 1 && !TITLE_STOP_WORDS.has(normalizedWord);
  });

  const normalizeToken = (token: string) =>
    token.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();

  if (meaningfulWords.length === 0) {
    return "Nova conversa";
  }

  const selectedWords = meaningfulWords.slice(0, 5);
  const uniqueSelectedWords: string[] = [];
  const seenTokens = new Set<string>();

  for (const word of selectedWords) {
    const key = normalizeToken(word);
    if (!key || seenTokens.has(key)) {
      continue;
    }
    seenTokens.add(key);
    uniqueSelectedWords.push(word);
  }

  const lowercaseConnectors = new Set(["de", "da", "do", "das", "dos", "e", "em", "para", "por", "com", "sem"]);
  const formattedWords = uniqueSelectedWords.map((word, index) => {
    const normalizedWord = normalizeToken(word);
    if (normalizedWord === "el") {
      return "El";
    }
    if (normalizedWord === "nino" || normalizedWord === "niño") {
      return "Niño";
    }
    if (index > 0 && lowercaseConnectors.has(normalizedWord)) {
      return normalizedWord;
    }
    return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
  });

  const title = formattedWords.join(" ").trim();
  if (!title) {
    return "Nova conversa";
  }

  const clipped = title.length > 60 ? `${title.slice(0, 57).trimEnd()}...` : title;
  return clipped.charAt(0).toUpperCase() + clipped.slice(1);
}

function comparisonTitleFromPrompt(prompt: string) {
  const normalized = prompt
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();

  if (!/(compar|diferenca|diferença|versus|\bvs\b)/.test(normalized)) {
    return "";
  }

  const cleaned = sanitizeTitleSourceText(prompt)
    .replace(/\bem\s+uma\s+tabela(?:\s+\w+)?\b/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!cleaned) {
    return "";
  }

  const entities = cleaned
    .split(/[,\n;]+/)
    .flatMap((part) => part.split(/\s+e\s+/i))
    .map((part) => part.trim())
    .map((part) => buildShortTitleFromText(part))
    .filter((part) => part && part !== "Nova conversa")
    .slice(0, 3);

  const unique = Array.from(new Set(entities));
  if (unique.length < 2) {
    return "";
  }
  return unique.join(" vs ");
}

function isWeakConversationTitle(title: string) {
  const normalized = normalizeConversationText(title);
  if (!normalized) {
    return true;
  }

  const words = normalized
    .split(/\s+/)
    .map((word) => word.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase());

  const weakSingletons = new Set(["fenomeno", "assunto", "tema", "resumo", "topico", "topicos"]);
  if (words.length === 1 && weakSingletons.has(words[0])) {
    return true;
  }

  return false;
}

function chooseConversationTitle(candidates: Array<string | null | undefined>) {
  for (const candidate of candidates) {
    const normalized = normalizeConversationTitle(candidate);
    if (!normalized) {
      continue;
    }
    if (normalized.toLowerCase() === "nova conversa") {
      continue;
    }
    if (isWeakConversationTitle(normalized)) {
      continue;
    }
    return normalized;
  }
  return "Nova conversa";
}

function normalizeConversationTitle(title: string | null | undefined) {
  const normalized = normalizeConversationText(title ?? "");
  if (!normalized) {
    return "";
  }
  return buildShortTitleFromText(normalized);
}

function normalizeBackendConversationTitle(title: string | null | undefined) {
  const normalized = normalizeConversationText(title ?? "")
    .replace(/^["'`]+|["'`.]+$/g, "")
    .trim();
  if (!normalized || normalized.toLowerCase() === "nova conversa") {
    return "";
  }
  return normalized.length > 64 ? `${normalized.slice(0, 61).trimEnd()}...` : normalized;
}

function titleFromUserPrompt(prompt: string) {
  const comparison = comparisonTitleFromPrompt(prompt);
  if (comparison) {
    return comparison;
  }

  const normalized = normalizeConversationText(prompt);
  if (!normalized) {
    return "Nova conversa";
  }
  return buildShortTitleFromText(normalized);
}

function normalizedProvidedTitle(title: string | null | undefined) {
  const computed = normalizeBackendConversationTitle(title);
  return computed === "Nova conversa" ? "" : computed;
}

function titleFromAssistantResponse(response: string) {
  return computeShortConversationTitle(response);
}

function computeShortConversationTitle(source: string) {
  const normalized = normalizeConversationText(source);
  if (!normalized) {
    return "Nova conversa";
  }

  const cleaned = normalized
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/[#>*_`|[\]{}()]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!cleaned) {
    return "Nova conversa";
  }
  return buildShortTitleFromText(cleaned);
}

function conversationPreviewFromMessages(messages: ChatMessage[]) {
  if (messages.length === 0) {
    return "";
  }

  const latestMessage = messages[messages.length - 1];
  const normalized = normalizeConversationText(latestMessage.content);
  return normalized.length > 90 ? `${normalized.slice(0, 90)}...` : normalized;
}

function looksLikeMarkdownTableLine(line: string) {
  const pipeCount = line.match(/\|/g)?.length ?? 0;
  if (pipeCount < 2) {
    return false;
  }
  if (/^[-*+]\s/.test(line) || /^\d+\.\s/.test(line)) {
    return false;
  }
  return line.startsWith("|") || line.endsWith("|") || line.includes(" | ");
}

function splitRawTableCells(line: string) {
  const trimmed = line.trim();
  const withoutEdgePipes = trimmed.replace(/^\|/, "").replace(/\|$/, "");
  return withoutEdgePipes.split("|").map((cell) => cell.trim());
}

function splitMixedHeadingTableLine(line: string) {
  const trimmed = line.trim();
  if (!trimmed.includes("|")) {
    return [line];
  }

  const rawCells = splitRawTableCells(trimmed);
  const nonEmptyCells = rawCells.filter((cell) => cell.length > 0);
  if (nonEmptyCells.length < 3) {
    return [line];
  }

  const headingCell = rawCells[0] ?? "";
  const headingLikeCell = headingCell
    .replace(/\s+/g, " ")
    .replace(/^#{1,6}\s*#\s*/, "# ")
    .trim();

  if (!/^#{1,6}\s+\S/.test(headingLikeCell)) {
    return [line];
  }

  const remainingCells = rawCells
    .slice(1)
    .map((cell) => cell.trim())
    .filter((cell) => cell.length > 0);

  if (remainingCells.length < 2) {
    return [headingCell];
  }

  return [headingLikeCell, `| ${remainingCells.join(" | ")} |`];
}

const TABLE_SECTION_BREAK_REGEX =
  /^(resumo(?:\s+r[aá]pido)?|em\s+resumo|conclus[aã]o|observa[cç][aã]o|nota|coment[aá]rio\s+final)\b[:\-–—]?/i;
const BULLET_PIPE_LINE_REGEX = /^[\u2022\u25E6\u25AA\-*+]\s*\|/;
const BULLET_PIPE_LINE_MOJIBAKE_REGEX = /^[â€¢â—¦â–ª\-*+]\s*\|/;

function isLikelyTableContentLine(line: string) {
  const trimmed = line.trim();
  if (!trimmed) {
    return false;
  }
  if (/^[\u2022\u25E6\u25AA]\s*\|/.test(trimmed)) {
    return false;
  }
  if (/^[•◦▪]\s*\|/.test(trimmed)) {
    return false;
  }
  if (isLooseTableNoiseLine(trimmed)) {
    return true;
  }
  if (!trimmed.includes("|")) {
    return false;
  }
  if (/^#{1,6}\s/.test(trimmed) && !trimmed.startsWith("|")) {
    return false;
  }
  if ((/^[-*+]\s/.test(trimmed) || /^\d+[.)]\s/.test(trimmed)) && !trimmed.startsWith("|")) {
    return false;
  }

  const pipeCount = trimmed.match(/\|/g)?.length ?? 0;
  if (pipeCount < 1) {
    return false;
  }

  const cells = splitRawTableCells(trimmed).filter((cell) => cell.length > 0);
  return cells.length >= 2;
}

function parseMarkdownTableCells(line: string) {
  const trimmed = line.trim();
  if (!trimmed.includes("|")) {
    return [];
  }
  if (/^[\u2022\u25E6\u25AA]\s*\|/.test(trimmed)) {
    return [];
  }
  if (/^[•◦▪]\s*\|/.test(trimmed)) {
    return [];
  }
  if (/^#{1,6}\s/.test(trimmed) && !trimmed.startsWith("|")) {
    return [];
  }
  if ((/^[-*+]\s/.test(trimmed) || /^\d+[.)]\s/.test(trimmed)) && !trimmed.startsWith("|")) {
    return [];
  }

  const cells = splitRawTableCells(trimmed);
  const nonEmptyCells = cells.filter((cell) => cell.length > 0);
  if (nonEmptyCells.length < 2) {
    return [];
  }
  if (nonEmptyCells.some((cell) => /^#{1,6}\s+/.test(cell))) {
    return [];
  }
  return cells;
}

function isLooseTableNoiseLine(line: string) {
  const trimmed = line.trim();
  if (!trimmed) {
    return false;
  }
  if (/^\|+$/.test(trimmed)) {
    return true;
  }
  if (/^[-:|\s]+$/.test(trimmed) && trimmed.includes("|")) {
    return true;
  }
  return /^-+\|$/.test(trimmed) || /^\|-+$/.test(trimmed);
}

function buildMarkdownTableFromRows(rows: string[][]) {
  if (rows.length < 2) {
    return null;
  }

  const columnCount = rows[0]?.length ?? 0;
  if (columnCount < 2) {
    return null;
  }
  if (rows.some((row) => row.length !== columnCount)) {
    return null;
  }

  const toRow = (row: string[]) =>
    `| ${Array.from({ length: columnCount }, (_, index) => row[index] ?? "").join(" | ")} |`;

  const normalizedRows = rows.map(toRow);
  const separator = `| ${Array.from({ length: columnCount }, () => "---").join(" | ")} |`;
  return [normalizedRows[0], separator, ...normalizedRows.slice(1)];
}

function normalizeLooseMarkdownTableBlock(blockLines: string[]) {
  let rows = blockLines
    .flatMap(splitMixedHeadingTableLine)
    .map(parseMarkdownTableCells)
    .filter((row) => row.length >= 2);

  if (rows.length < 2) {
    return null;
  }

  if (rows.length >= 3) {
    const firstRow = rows[0];
    const wrappedHeaderCandidate = rows[1];
    const thirdRow = rows[2];
    const thirdIsAlignment = thirdRow.length > 0 && thirdRow.every((cell) => /^:?-{2,}:?$/.test(cell.trim()));

    if (firstRow.length >= 2 && wrappedHeaderCandidate.length === 2 && thirdIsAlignment) {
      const secondCell = wrappedHeaderCandidate[1]?.trim() ?? "";
      const firstCell = wrappedHeaderCandidate[0]?.trim() ?? "";
      if (!firstCell && secondCell) {
        rows = [[...firstRow, secondCell], ...rows.slice(2)];
      }
    }
  }

  const compactRows = rows.filter((row, index) => {
    if (index === 0) {
      return true;
    }
    const meaningfulCells = row.filter((cell) => cell.trim().length > 0);
    if (meaningfulCells.length === 0) {
      return false;
    }
    return !meaningfulCells.every((cell) => /^:?-{2,}:?$/.test(cell));
  });

  if (compactRows.length < 2) {
    return null;
  }

  const baseColumnCount = compactRows[0].length;
  const extractedTrailingLines: string[] = [];
  let hasInvalidRow = false;

  const normalizedRows = compactRows.flatMap((row, index) => {
    if (index === 0) {
      return [row.map((cell) => cell.trim())];
    }

    const meaningfulCells = row
      .map((cell) => cell.trim())
      .filter((cell) => cell.length > 0);

    if (meaningfulCells.length > 0 && meaningfulCells.every((cell) => TABLE_SECTION_BREAK_REGEX.test(cell))) {
      extractedTrailingLines.push(meaningfulCells.join(" ").replace(/\s+/g, " ").trim());
      return [];
    }

    if (row.length !== baseColumnCount) {
      hasInvalidRow = true;
      return [];
    }

    const hasPipeInsideCell = row.some((cell) => cell.includes("|"));
    if (hasPipeInsideCell) {
      hasInvalidRow = true;
      return [];
    }

    return [row.map((cell) => cell.trim())];
  });
  if (hasInvalidRow) {
    return null;
  }

  const tableLines = buildMarkdownTableFromRows(normalizedRows);
  if (!tableLines) {
    return null;
  }

  if (extractedTrailingLines.length === 0) {
    return tableLines;
  }

  return [...tableLines, "", ...extractedTrailingLines];
}

function fallbackLooseTableBlockToList(blockLines: string[]) {
  const bulletPipeLines = blockLines
    .map((line) => line.trim())
    .filter((line) => BULLET_PIPE_LINE_REGEX.test(line) || BULLET_PIPE_LINE_MOJIBAKE_REGEX.test(line))
    .map((line) => line.replace(/^[\u2022\u25E6\u25AAâ€¢â—¦â–ª\-*+]\s*\|?\s*/, ""))
    .map((line) => line.replace(/\|+/g, " ").replace(/\s+/g, " ").trim())
    .filter(Boolean)
    .map((line) => `- ${line}`);

  const rows = blockLines
    .map(parseMarkdownTableCells)
    .filter((row) => row.length >= 2)
    .map((row) => row.map((cell) => cell.trim()))
    .filter((row) => row.some((cell) => cell.length > 0))
    .filter((row) => !row.every((cell) => /^:?-{2,}:?$/.test(cell)));

  if (rows.length < 2) {
    const plainLines = blockLines
      .map((line) => line.replace(/\|+/g, " ").replace(/\s+/g, " ").trim())
      .filter(Boolean);
    return bulletPipeLines.length > 0 ? [...plainLines, ...bulletPipeLines] : plainLines;
  }

  const headers = rows[0];
  const dataRows = rows.slice(1).filter((row) => !row.every((cell) => /^:?-{2,}:?$/.test(cell)));
  const listLines: string[] = [];

  for (const row of dataRows) {
    const pairs = headers
      .map((header, index) => {
        const key = header.trim();
        const value = row[index]?.trim() ?? "";
        if (!key || !value || /^:?-{2,}:?$/.test(value)) {
          return "";
        }
        return `${key}: ${value}`;
      })
      .filter(Boolean);

    if (pairs.length > 0) {
      listLines.push(`- ${pairs.join(" — ")}`);
    }
  }

  if (listLines.length > 0) {
    return bulletPipeLines.length > 0 ? [...listLines, ...bulletPipeLines] : listLines;
  }

  const plainLines = blockLines
    .map((line) => line.replace(/\|+/g, " ").replace(/\s+/g, " ").trim())
    .filter(Boolean);
  return bulletPipeLines.length > 0 ? [...plainLines, ...bulletPipeLines] : plainLines;
}

function normalizeTableLine(line: string) {
  let normalized = line.trim();
  if (!normalized.startsWith("|")) {
    normalized = `| ${normalized}`;
  }
  if (!normalized.endsWith("|")) {
    normalized = `${normalized} |`;
  }
  return normalized.replace(/\|\|+/g, "|").replace(/\s{2,}/g, " ").trim();
}

function normalizeMarkdownPlainChunk(chunk: string) {
  let prepared = chunk.replace(/\r/g, "");
  if (!prepared.includes("\n") && prepared.includes("\\n")) {
    prepared = prepared.replace(/\\r\\n/g, "\n").replace(/\\n/g, "\n");
  }
  if (!prepared.includes("\t") && prepared.includes("\\t")) {
    prepared = prepared.replace(/\\t/g, "\t");
  }

  prepared = prepared
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|li|tr|h[1-6]|blockquote)>/gi, "\n")
    .replace(/<(p|div|li|tr|h[1-6]|blockquote)[^>]*>/gi, "")
    .replace(/<[^>]+>/g, "")
    .replace(/(^|\n)\s*[•◦▪]\s+/g, "$1- ")
    .replace(/(^|\n)\s*[•◦▪]\s*\|/g, "$1|")
    .replace(/(^|\n)\s*[-*+]\s*\|/g, "$1|")
    .replace(/\u00a0/g, " ")
    .replace(/\\([\\`*_{}\[\]()#+\-.!|>])/g, "$1")
    .replace(/([a-z\u00E0-\u00FF])(?=\d)/g, "$1 ")
    .replace(/(\d)(?=[a-z\u00E0-\u00FF])/g, "$1 ")
    .replace(/---\s*(#{1,6})(?=\S)/g, "---\n\n$1 ")
    .replace(/(^|\n)(---+)\s*(#{1,6})(?=\S)/g, "$1$2\n\n$3 ")
    .replace(/([^\n])\s*(#{1,6})(?=\d|[A-Za-z\u00C0-\u00FF])/g, "$1\n\n$2 ")
    .replace(/([.!?])\s*(\d+)\.(?=[A-Za-z\u00C0-\u00FF])/g, "$1\n\n$2. ")
    .replace(/(^|[\s(])(\d+)\.(?=[A-Z\u00C0-\u00DD])/gm, "$1$2. ")
    .replace(/(^|\n)(#{1,6})(\d+[.)])\s*/g, "$1$2 $3 ")
    .replace(/(#{1,6}\s+[^\n#]*?)(?=\d+[.)]\s)/g, "$1\n\n")
    .replace(/(^|\n)(#{1,6})(?=\S)/g, "$1$2 ")
    .replace(/([A-Za-z\u00C0-\u00FF])(\d+[.)]\s)/g, "$1\n\n$2")
    .replace(/([.!?])\s*(\d+[.)])(?=[A-Z\u00C0-\u00DD])/g, "$1\n\n$2 ")
    .replace(/([a-z\u00E0-\u00FF0-9)\]])\s*(\d+[.)])(?=(?:\*\*)?[A-Z\u00C0-\u00DD])/g, "$1\n\n$2 ")
    .replace(/([a-z\u00E0-\u00FF0-9)\]])\s*(\d+[.)])(?=(?:\*\*)?[a-z\u00E0-\u00FF])/g, "$1\n\n$2 ")
    .replace(/(\d+[.)]\s+[^\n]{4,240}?)(?=\d+[.)]\s)/g, "$1\n")
    .replace(/([.!?])\s*[-–—]\s*(?=(?:\*\*)?[A-Z\u00C0-\u00DD])/g, "$1\n- ")
    .replace(/([.!?])\s*[-–—]\s*(?=[a-z\u00E0-\u00FF])/g, "$1\n- ")
    .replace(/([^\n])\s+[-–—]\s*(?=(?:\*\*)?[A-Z\u00C0-\u00DD][^:\n]{2,60}:)/g, "$1\n- ")
    .replace(/([A-Za-z\u00C0-\u00FF0-9])([*-]\s)/g, "$1\n\n$2")
    .replace(/([^\n])\s+(?=\d+\.\s)/g, "$1\n")
    .replace(/([^\n])\s+(?=[-*+]\s)/g, "$1\n")
    .replace(/([^\n])\s*(---+)(?=\s*(?:#{1,6}|[-*+]|\d+\.))/g, "$1\n\n$2\n\n")
    .replace(/\*\*\s+([^*\n][^*\n]*?)\s+\*\*/g, "**$1**")
    .replace(/\*\s+([^*\n][^*\n]*?)\s+\*/g, "*$1*")
    .replace(/-([A-Z\u00C0-\u00DD][^:\n]{2,40}:)/g, "- $1")
    .replace(/([a-z\u00E0-\u00FF0-9)])-(?=[A-Z\u00C0-\u00DD][a-z\u00E0-\u00FF])/g, "$1 - ")
    .replace(/\b(Defini[cç][aã]o|Resumo(?:\s+r[aá]pido)?|Conclus[aã]o|Observa[cç][aã]o|Nota|Dica pr[aá]tica)(?=[A-Z\u00C0-\u00DD])/g, "$1: ")
    .replace(/\b(Conceito B[aá]sico|Diferen[cç]a para Mudan[cç]as Clim[aá]ticas)(?=[A-Z\u00C0-\u00DD])/g, "$1: ")
    .replace(/\b(clim[aá]ticos)(s[aã]o)\b/gi, "$1 $2")
    .replace(/(\*\*[^*\n]{1,80}:\*\*):/g, "$1")
    .replace(/([.!?])(?=Pr[oó]ximo passo:)/g, "$1\n\n")
    .replace(/([.!?])\s*(#{1,6}\s+)/g, "$1\n\n$2");

  prepared = prepared
    .replace(/(^|\n)\s*#\s*#\s*(?=\S)/g, "$1## ")
    .replace(/(^|\n)\s*#{1,6}\s*(?=\n|$)/g, "$1")
    .replace(/\n{3,}/g, "\n\n");

  const lines = prepared.split("\n").flatMap(splitMixedHeadingTableLine);
  const output: string[] = [];

  for (let index = 0; index < lines.length; index++) {
    let line = lines[index]
      .replace(/[ \t]+$/g, "")
      .replace(/(\S)\s+(-\s+(?=(?:\*\*)?[A-Z\u00C0-\u00DD]))/g, "$1\n$2")
      .replace(/(\S)\s+((?:\d+)[.)]\s+(?=\S))/g, "$1\n$2")
      .replace(/(\S)(\d+[.)]\s*(?=\S))/g, "$1\n$2 ");

    line = line.replace(
      /^(\s*[-*+]\s+\*\*([^*\n][^*\n]{0,80})\*\*)(?!:)\s+(.+)$/,
      "$1: $3"
    );
    const trimmed = line.trim();

    if (!trimmed) {
      if (output.length === 0 || output[output.length - 1] === "") {
        continue;
      }
      output.push("");
      continue;
    }

    if (/^---+$/.test(trimmed)) {
      if (output.length > 0 && output[output.length - 1] !== "") {
        output.push("");
      }
      output.push("---");
      output.push("");
      continue;
    }

    if (isLikelyTableContentLine(trimmed)) {
      const blockLines: string[] = [];
      let cursor = index;
      let hasBulletPipeRow = false;

      while (cursor < lines.length) {
        const candidate = lines[cursor].trim();
        if (!candidate) {
          break;
        }
        if (
          BULLET_PIPE_LINE_REGEX.test(candidate) ||
          BULLET_PIPE_LINE_MOJIBAKE_REGEX.test(candidate)
        ) {
          hasBulletPipeRow = true;
          blockLines.push(lines[cursor]);
          cursor++;
          continue;
        }
        if (!isLikelyTableContentLine(candidate)) {
          const looksLikeWrappedHeaderCell =
            blockLines.length > 0 &&
            candidate.endsWith("|") &&
            !candidate.startsWith("|") &&
            !candidate.includes(" | ");
          if (looksLikeWrappedHeaderCell) {
            const continuationCell = candidate.replace(/\|+\s*$/g, "").trim();
            if (continuationCell) {
              const previousLine = blockLines[blockLines.length - 1]
                .replace(/\|+\s*$/g, "")
                .trimEnd();
              blockLines[blockLines.length - 1] = `${previousLine} | ${continuationCell} |`;
              cursor++;
              continue;
            }
          }
          break;
        }
        blockLines.push(lines[cursor]);
        cursor++;
      }

      const normalizedTableBlock = hasBulletPipeRow ? null : normalizeLooseMarkdownTableBlock(blockLines);
      if (normalizedTableBlock) {
        const previous = output[output.length - 1]?.trim() ?? "";
        if (previous) {
          output.push("");
        }
        output.push(...normalizedTableBlock);
        output.push("");
        index = cursor - 1;
        continue;
      }

      const fallbackLines = fallbackLooseTableBlockToList(blockLines);
      if (fallbackLines.length > 0) {
        const previous = output[output.length - 1]?.trim() ?? "";
        if (previous) {
          output.push("");
        }
        output.push(...fallbackLines);
        output.push("");
        index = cursor - 1;
        continue;
      }
    }

    if (looksLikeMarkdownTableLine(trimmed)) {
      const previousRawLine = lines[index - 1]?.trim() ?? "";
      const nextRawLine = lines[index + 1]?.trim() ?? "";
      const hasNeighborTableLine =
        isLikelyTableContentLine(previousRawLine) || isLikelyTableContentLine(nextRawLine);

      if (!hasNeighborTableLine) {
        output.push(trimmed.replace(/\|+/g, " ").replace(/\s+/g, " ").trim());
        continue;
      }

      const previous = output[output.length - 1]?.trim() ?? "";
      if (previous && !looksLikeMarkdownTableLine(previous)) {
        output.push("");
      }
      output.push(normalizeTableLine(trimmed));
      continue;
    }

    const previous = output[output.length - 1]?.trim() ?? "";
    if (previous && looksLikeMarkdownTableLine(previous)) {
      output.push("");
    }

    output.push(line);
  }

  return output.join("\n");
}

function normalizeMarkdown(content: string) {
  if (!content) {
    return "";
  }

  const base = content.replace(/\r/g, "");
  const fenceRegex = /```[\s\S]*?```/g;
  const chunks: string[] = [];
  let cursor = 0;
  let match: RegExpExecArray | null;

  while ((match = fenceRegex.exec(base)) !== null) {
    chunks.push(normalizeMarkdownPlainChunk(base.slice(cursor, match.index)));
    chunks.push(match[0]);
    cursor = match.index + match[0].length;
  }
  chunks.push(normalizeMarkdownPlainChunk(base.slice(cursor)));

  return chunks.join("").replace(/\n{3,}/g, "\n\n").trim();
}

function normalizeAssistantContent(content: string) {
  return normalizeMarkdown(content ?? "");
}

function mergeStreamChunk(previous: string, nextChunk: string) {
  if (!nextChunk) {
    return previous;
  }
  if (!previous) {
    return nextChunk;
  }
  if (nextChunk.startsWith(previous)) {
    return nextChunk;
  }
  if (previous.endsWith(nextChunk)) {
    return previous;
  }

  const maxOverlap = Math.min(previous.length, nextChunk.length);
  for (let size = maxOverlap; size > 0; size--) {
    if (previous.slice(-size) === nextChunk.slice(0, size)) {
      return previous + nextChunk.slice(size);
    }
  }
  return previous + nextChunk;
}

const STREAM_RENDER_INTERVAL_MS = 80;
const LONG_PASTE_CHAR_LIMIT = 8000;
const LONG_PASTE_MAX_BYTES = 256 * 1024;
const LONG_PASTE_FILE_NAME = "texto-colado.txt";
const LONG_PASTE_FILE_TYPE = "text/plain;charset=utf-8";
const LONG_PASTE_ATTACHMENT_PROMPT = "Analise o texto anexado.";
const LONG_PASTE_ATTACHED_NOTICE =
  "Texto longo detectado. Transformei o conteúdo colado em arquivo para evitar travamento do input.";
const LONG_PASTE_EXISTING_FILE_NOTICE =
  "Você já tem um arquivo anexado. Remova o arquivo atual antes de colar outro texto longo.";
const LONG_PASTE_TOO_LARGE_NOTICE =
  "O texto colado ultrapassa o limite de 256 KB. Reduza o conteúdo ou envie um arquivo menor.";

function markdownToClipboardText(content: string) {
  const normalized = normalizeAssistantContent(content);
  const lines = normalized.split("\n");
  const output: string[] = [];
  let insideCodeFence = false;

  for (const rawLine of lines) {
    if (/^\s*```/.test(rawLine)) {
      insideCodeFence = !insideCodeFence;
      continue;
    }

    if (insideCodeFence) {
      output.push(rawLine);
      continue;
    }

    let line = rawLine;
    line = line.replace(/^#{1,6}\s*/g, "");
    line = line.replace(/\[([^\]]+)\]\(([^)]+)\)/g, "$1 ($2)");
    line = line.replace(/`([^`]+)`/g, "$1");
    line = line.replace(/\*\*([^*]+)\*\*/g, "$1");
    line = line.replace(/__([^_]+)__/g, "$1");
    line = line.replace(/\*([^*\n]+)\*/g, "$1");
    line = line.replace(/_([^_\n]+)_/g, "$1");
    line = line.replace(/^\s*[-*+]\s+/gm, "- ");

    const trimmed = line.trim();
    const tableAlignmentRow = /^[:\-|\s]+$/.test(trimmed) && trimmed.includes("|");
    if (tableAlignmentRow) {
      continue;
    }
    if (trimmed.includes("|")) {
      const cells = trimmed
        .split("|")
        .map((cell) => cell.trim())
        .filter(Boolean);
      output.push(cells.join("    "));
      continue;
    }

    output.push(line);
  }

  return output.join("\n").replace(/\n{3,}/g, "\n\n").trim();
}

function stripInlineMarkdown(content: string) {
  return (content ?? "")
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, "$1 ($2)")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/__([^_]+)__/g, "$1")
    .replace(/\*([^*\n]+)\*/g, "$1")
    .replace(/_([^_\n]+)_/g, "$1")
    .replace(/<br\s*\/?>/gi, "\n")
    .trim();
}

function activeFileFromFile(file: File): ActiveFile {
  return {
    id: `local-${file.name}-${file.size}-${file.lastModified}`,
    fileName: file.name,
    contentType: file.type || "text/plain",
    sizeBytes: file.size,
  };
}

function assistantDocumentToClipboardText(document: AssistantDocument) {
  const output: string[] = [];

  for (const block of document.blocks ?? []) {
    if (block.type === "heading" || block.type === "paragraph" || block.type === "blockquote") {
      output.push(stripInlineMarkdown(block.text));
      continue;
    }

    if (block.type === "bulletList") {
      output.push(...block.items.map((item) => `- ${stripInlineMarkdown(item)}`));
      continue;
    }

    if (block.type === "orderedList") {
      output.push(...block.items.map((item, index) => `${index + 1}. ${stripInlineMarkdown(item)}`));
      continue;
    }

    if (block.type === "codeBlock") {
      output.push(block.code.trimEnd());
      continue;
    }

    if (block.type === "table") {
      output.push(block.columns.map(stripInlineMarkdown).join("\t"));
      output.push(...block.rows.map((row) => row.map(stripInlineMarkdown).join("\t")));
      continue;
    }
  }

  return output.join("\n\n").replace(/\n{3,}/g, "\n\n").trim();
}

function repairGluedLabelText(content: string) {
  return (content ?? "")
    .replace(
      /\b(Defini[cç][aã]o|Conceito B[aá]sico|Diferen[cç]a para Mudan[cç]as Clim[aá]ticas|Resumo(?:\s+r[aá]pido)?|Pr[oó]ximo passo)(?=\p{Lu})/gu,
      "$1: "
    )
    .replace(/\b(clim[aá]ticos)(s[aã]o)\b/giu, "$1 $2");
}

function textLooksGlued(content: string) {
  return /[A-ZÁÀÂÃÉÊÍÓÔÕÚÇ][a-záàâãéêíóôõúç]+[A-ZÁÀÂÃÉÊÍÓÔÕÚÇ][a-záàâãéêíóôõúç]+/.test(content)
    || /\b(?:Definição|Principais tipos|Resumo rápido|Como se formam|Pontos chave)\d+[.)]?/i.test(content)
    || /\b(?:de|da|do|a|até|entre|cada)\d/i.test(content)
    || /\d+(?:meses|dias|anos|horas)\b/i.test(content);
}

function validateAssistantDocument(document: AssistantDocument | null | undefined): AssistantDocumentValidation {
  if (!document?.blocks?.length) {
    return { valid: false, reason: "empty_document" };
  }

  for (const block of document.blocks) {
    if (block.type === "heading") {
      const text = block.text?.trim() ?? "";
      const wordCount = text.split(/\s+/).filter(Boolean).length;
      if (!text) {
        return { valid: false, reason: "empty_heading" };
      }
      if (text.length > 90 || wordCount > 12 || /[.!?]$/.test(text)) {
        return { valid: false, reason: "paragraph_like_heading" };
      }
      if (textLooksGlued(text)) {
        return { valid: false, reason: "glued_heading" };
      }
      continue;
    }

    if (block.type === "paragraph" || block.type === "blockquote") {
      const text = block.text?.trim() ?? "";
      if (!text) {
        return { valid: false, reason: "empty_text_block" };
      }
      if (
        textLooksGlued(text) ||
        /\b\d+[.)]\s*\S[\s\S]*\b\d+[.)]\s*\S/.test(text) ||
        /(?:^|\s)(?:[-*•])\s*$/.test(text)
      ) {
        return { valid: false, reason: "suspicious_paragraph" };
      }
      continue;
    }

    if (block.type === "bulletList" || block.type === "orderedList") {
      if (!block.items?.length) {
        return { valid: false, reason: "empty_list" };
      }
      if (block.items.some((item) => !item.trim() || textLooksGlued(item))) {
        return { valid: false, reason: "suspicious_list_item" };
      }
      continue;
    }

    if (block.type === "table") {
      if (!block.columns?.length || !block.rows?.length) {
        return { valid: false, reason: "empty_table" };
      }
      if (
        block.columns.some((column) => !column.trim()) ||
        block.rows.some((row) => row.length !== block.columns.length || row.some((cell) => textLooksGlued(cell)))
      ) {
        return { valid: false, reason: "invalid_table_shape" };
      }
      continue;
    }

    if (block.type === "codeBlock" && block.code == null) {
      return { valid: false, reason: "empty_code" };
    }
  }

  return { valid: true, reason: "ok" };
}

function selectRenderableAssistantDocument(
  document: AssistantDocument | null | undefined,
  fallbackContent: string,
  streamedContent: string
) {
  const validation = validateAssistantDocument(document);
  if (validation.valid) {
    return document ?? null;
  }

  if (process.env.NODE_ENV !== "production" && document?.blocks?.length) {
    console.info("assistant document rejected", {
      reason: validation.reason,
      blockCount: document.blocks.length,
      fallbackLength: fallbackContent.length,
      streamedLength: streamedContent.length,
    });
  }

  return null;
}

function isLongUserMessage(content: string) {
  return content.length > LONG_USER_MESSAGE_CHARS || content.split(/\r?\n/).length > LONG_USER_MESSAGE_LINES;
}

function userMessagePreview(content: string) {
  const normalized = content.replace(/\s+/g, " ").trim();
  if (normalized.length <= LONG_USER_MESSAGE_PREVIEW_CHARS) {
    return normalized;
  }
  return `${normalized.slice(0, LONG_USER_MESSAGE_PREVIEW_CHARS).trimEnd()}...`;
}

function InlineMarkdown({ children }: { children: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        p: ({ children }) => <>{children}</>,
        h1: ({ children }) => <span>{children}</span>,
        h2: ({ children }) => <span>{children}</span>,
        h3: ({ children }) => <span>{children}</span>,
        h4: ({ children }) => <span>{children}</span>,
        h5: ({ children }) => <span>{children}</span>,
        h6: ({ children }) => <span>{children}</span>,
        ul: ({ children }) => <>{children}</>,
        ol: ({ children }) => <>{children}</>,
        li: ({ children }) => <span>{children}</span>,
        table: ({ children }) => <span>{children}</span>,
        thead: ({ children }) => <span>{children}</span>,
        tbody: ({ children }) => <span>{children}</span>,
        tr: ({ children }) => <span>{children}</span>,
        th: ({ children }) => <span>{children}</span>,
        td: ({ children }) => <span>{children}</span>,
        pre: ({ children }) => <span>{children}</span>,
        blockquote: ({ children }) => <span>{children}</span>,
        hr: () => <span aria-hidden="true"> </span>,
        strong: ({ children }) => <strong className="font-semibold text-white">{children}</strong>,
        em: ({ children }) => <em className="italic text-slate-100">{children}</em>,
        code: ({ children }) => (
          <code className="rounded-md border border-sky-300/25 bg-sky-400/16 px-1.5 py-0.5 font-mono text-[0.9em] font-medium text-sky-100">
            {children}
          </code>
        ),
        a: ({ href, children }) => (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="font-medium text-sky-200 underline decoration-sky-300/45 underline-offset-2 transition hover:text-sky-100"
          >
            {children}
          </a>
        ),
      }}
    >
      {repairGluedLabelText(children)}
    </ReactMarkdown>
  );
}

function AssistantDocumentRenderer({
  document,
  copiedBlockId,
  onCopyCode,
}: {
  document: AssistantDocument;
  copiedBlockId: string | null;
  onCopyCode: (content: string) => void;
}) {
  return (
    <div className="space-y-4">
      {document.blocks.map((block, blockIndex) => {
        if (block.type === "heading") {
          const level = block.level ?? 2;
          const HeadingTag = level <= 2 ? "h2" : "h3";
          return (
            <HeadingTag
              key={`heading-${blockIndex}`}
              className={
                level <= 2
                  ? "rounded-xl border border-sky-300/18 bg-sky-400/8 px-3 py-2 text-lg font-semibold tracking-tight text-sky-50"
                  : "text-base font-semibold tracking-tight text-sky-100"
              }
            >
              <InlineMarkdown>{block.text}</InlineMarkdown>
            </HeadingTag>
          );
        }

        if (block.type === "paragraph") {
          return (
            <div key={`paragraph-${blockIndex}`} className="leading-7 text-slate-200">
              <InlineMarkdown>{block.text}</InlineMarkdown>
            </div>
          );
        }

        if (block.type === "blockquote") {
          return (
            <blockquote
              key={`quote-${blockIndex}`}
              className="rounded-r-2xl border-l-4 border-amber-300/60 bg-amber-300/6 px-4 py-3 italic text-slate-300"
            >
              <InlineMarkdown>{block.text}</InlineMarkdown>
            </blockquote>
          );
        }

        if (block.type === "orderedList") {
          let orderedStart = 1;
          for (let previousIndex = blockIndex - 1; previousIndex >= 0; previousIndex -= 1) {
            const previousBlock = document.blocks[previousIndex];
            if (previousBlock.type === "heading" || previousBlock.type === "horizontalRule") {
              break;
            }
            if (previousBlock.type === "orderedList") {
              orderedStart += previousBlock.items.length;
            }
          }
          return (
            <ol
              key={`list-${blockIndex}`}
              start={orderedStart}
              className="list-decimal space-y-2 pl-5 marker:font-semibold marker:text-sky-300"
            >
              {block.items.map((item, itemIndex) => (
                <li key={`${blockIndex}-${itemIndex}`} className="leading-7 text-slate-100">
                  <InlineMarkdown>{item}</InlineMarkdown>
                </li>
              ))}
            </ol>
          );
        }

        if (block.type === "bulletList") {
          return (
            <ul key={`list-${blockIndex}`} className="list-disc space-y-2 pl-5 marker:text-sky-300">
              {block.items.map((item, itemIndex) => (
                <li key={`${blockIndex}-${itemIndex}`} className="leading-7 text-slate-100">
                  <InlineMarkdown>{item}</InlineMarkdown>
                </li>
              ))}
            </ul>
          );
        }

        if (block.type === "table") {
          return (
            <div
              key={`table-${blockIndex}`}
              className="my-3 overflow-x-auto rounded-2xl border border-sky-300/15 bg-[#030c22]/70 shadow-[inset_0_0_0_1px_rgba(14,165,233,0.06)]"
            >
              <table className="min-w-[48rem] border-collapse text-left text-[13px] leading-6 sm:text-sm">
                <thead className="bg-sky-400/10">
                  <tr>
                    {block.columns.map((column, columnIndex) => (
                      <th
                        key={`${blockIndex}-head-${columnIndex}`}
                        className="border-b border-white/12 px-4 py-3 font-semibold text-sky-100"
                      >
                        <InlineMarkdown>{column}</InlineMarkdown>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/8">
                  {block.rows.map((row, rowIndex) => (
                    <tr key={`${blockIndex}-row-${rowIndex}`} className="align-top transition-colors hover:bg-white/[0.03]">
                      {block.columns.map((_, columnIndex) => (
                        <td
                          key={`${blockIndex}-cell-${rowIndex}-${columnIndex}`}
                          className="border-b border-white/6 px-4 py-3 align-top text-slate-200"
                        >
                          <InlineMarkdown>{row[columnIndex] ?? ""}</InlineMarkdown>
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          );
        }

        if (block.type === "codeBlock") {
          const codeText = block.code ?? "";
          const language = block.language || "texto";
          const isCopied = copiedBlockId === codeText;
          return (
            <div
              key={`code-${blockIndex}`}
              className="group relative my-5 overflow-hidden rounded-[1.35rem] border border-white/10 bg-[#020617]"
            >
              <div className="flex items-center justify-between border-b border-white/10 bg-white/[0.04] px-4 py-2 text-[11px] uppercase tracking-[0.24em] text-slate-400">
                <span>{language}</span>
                <button
                  type="button"
                  onClick={() => onCopyCode(codeText)}
                  className={`inline-flex items-center gap-2 rounded-full px-2.5 py-1 transition ${
                    isCopied
                      ? "copy-success bg-emerald-500/15 text-emerald-300"
                      : "text-slate-300 hover:bg-white/6 hover:text-white"
                  }`}
                >
                  <Image src="/media/icon-copy.svg" alt="" width={14} height={14} className="h-3.5 w-3.5" />
                  {isCopied ? "Copiado" : "Copiar"}
                </button>
              </div>
              <SyntaxHighlighter
                PreTag="div"
                language={block.language || "text"}
                style={oneDark}
                customStyle={{
                  margin: 0,
                  backgroundColor: "#020617",
                  padding: "1.2rem",
                  borderRadius: 0,
                  fontSize: "0.86rem",
                  lineHeight: "1.65",
                }}
                codeTagProps={{
                  style: { backgroundColor: "transparent" },
                }}
              >
                {codeText}
              </SyntaxHighlighter>
            </div>
          );
        }

        if (block.type === "horizontalRule") {
          return <hr key={`hr-${blockIndex}`} className="border-0 border-t border-white/12" />;
        }

        return null;
      })}
    </div>
  );
}

export default function Home() {
  const [config, setConfig] = useState<AppConfig>({
    version: "0.2.0",
    availableModels: FALLBACK_MODELS,
    defaultSelectedModels: FALLBACK_SELECTED_MODELS,
    fileUploadModelId: FALLBACK_FILE_MODEL_ID,
  });
  const { data: session } = useSession();
  const [displayName, setDisplayName] = useState("");
  const [displayNameInput, setDisplayNameInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [guestConversations, setGuestConversations] = useState<LocalConversation[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [guestConversationId, setGuestConversationId] = useState<string | null>(null);
  const [roleModelSelection, setRoleModelSelection] = useState<RoleModelSelection>(
    buildDefaultRoleSelection({
      version: "0.2.0",
      availableModels: FALLBACK_MODELS,
      defaultSelectedModels: FALLBACK_SELECTED_MODELS,
      fileUploadModelId: FALLBACK_FILE_MODEL_ID,
    })
  );
  const [speedMode, setSpeedMode] = useState<SpeedMode>("fast");
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [activeFile, setActiveFile] = useState<ActiveFile | null>(null);
  const [activeLocalFile, setActiveLocalFile] = useState<File | null>(null);
  const [input, setInput] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [showSpeedMenu, setShowSpeedMenu] = useState(false);
  const [showUserMenu, setShowUserMenu] = useState(false);
  const [showScrollButton, setShowScrollButton] = useState(false);
  const [copiedBlockId, setCopiedBlockId] = useState<string | null>(null);
  const [copiedMessageIndex, setCopiedMessageIndex] = useState<number | null>(null);
  const [activeConversationMenuId, setActiveConversationMenuId] = useState<string | null>(null);
  const [activeConversationMenuContext, setActiveConversationMenuContext] =
    useState<ConversationMenuContext>("expanded");
  const [conversationMenuPosition, setConversationMenuPosition] =
    useState<ConversationMenuPosition | null>(null);
  const [renamingConversationId, setRenamingConversationId] = useState<string | null>(null);
  const [renameConversationValue, setRenameConversationValue] = useState("");
  const [activeDislikeIndex, setActiveDislikeIndex] = useState<number | null>(null);
  const [dislikeReason, setDislikeReason] = useState("");
  const [feedbackLoadingIndex, setFeedbackLoadingIndex] = useState<number | null>(null);
  const [restoreLoadingIndex, setRestoreLoadingIndex] = useState<number | null>(null);
  const [errorText, setErrorText] = useState("");
  const [pasteNotice, setPasteNotice] = useState("");
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [showCollapsedConversations, setShowCollapsedConversations] = useState(false);
  const [expandedUserMessages, setExpandedUserMessages] = useState<Set<string>>(() => new Set());
  const [activeModelDropdownRole, setActiveModelDropdownRole] =
    useState<keyof RoleModelSelection | null>(null);

  const scrollRef = useRef<HTMLDivElement>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const speedMenuRef = useRef<HTMLDivElement>(null);
  const userMenuRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const effectiveRoleModelSelection = useMemo<RoleModelSelection>(() => {
    if (!selectedFile) {
      return roleModelSelection;
    }

    const fileModelId = config.fileUploadModelId;
    return {
      plannerModelId: fileModelId,
      executorModelId: fileModelId,
      reviewerModelId: fileModelId,
    };
  }, [config.fileUploadModelId, roleModelSelection, selectedFile]);

  const effectiveModelIds = useMemo(
    () =>
      uniqueModelList([
        effectiveRoleModelSelection.plannerModelId,
        effectiveRoleModelSelection.executorModelId,
        effectiveRoleModelSelection.reviewerModelId,
      ]),
    [effectiveRoleModelSelection]
  );

  const effectiveDisplayName = useMemo(() => {
    if (!session?.user) {
      return "";
    }
    return displayName.trim() || firstNameOf(session.user.name ?? "");
  }, [displayName, session]);

  const sessionContext = useMemo<AgentSessionContext | undefined>(() => {
    const user = session?.user;
    const userId = user?.id?.trim();
    if (!user || !userId) {
      return undefined;
    }

    return {
      userId,
      userName: user.name ?? undefined,
      userEmail: user.email ?? undefined,
    };
  }, [session]);

  function toggleExpandedUserMessage(messageKey: string) {
    setExpandedUserMessages((current) => {
      const next = new Set(current);
      if (next.has(messageKey)) {
        next.delete(messageKey);
      } else {
        next.add(messageKey);
      }
      return next;
    });
  }

  const sidebarConversations = useMemo<ConversationSummary[]>(
    () =>
      sessionContext
        ? conversations
        : guestConversations.map(({ id, title, preview, updatedAt }) => ({
            id,
            title,
            preview,
            updatedAt,
          })),
    [conversations, guestConversations, sessionContext]
  );

  const hasSidebarConversations = sidebarConversations.length > 0;
  const activeSidebarConversationId = sessionContext ? conversationId : guestConversationId;
  const activeConversationMenuConversation = useMemo(
    () =>
      activeConversationMenuId
        ? (sidebarConversations.find((conversation) => conversation.id === activeConversationMenuId) ?? null)
        : null,
    [activeConversationMenuId, sidebarConversations]
  );
  const displayedAppVersion = displayVersion(BUILD_APP_VERSION ?? config?.version);

  useEffect(() => {
    async function bootstrap() {
      try {
        const data = await getConfig();
        if (!data) {
          throw new Error("Falha ao carregar a configuração");
        }

        setConfig(data);

        const storedRoleSelection = window.localStorage.getItem(MODEL_ROLE_SELECTION_STORAGE_KEY);
        const parsedRoleSelection = storedRoleSelection
          ? (JSON.parse(storedRoleSelection) as Partial<RoleModelSelection>)
          : null;
        setRoleModelSelection(sanitizeRoleSelection(parsedRoleSelection, data));

        const storedSpeed = window.localStorage.getItem(SPEED_MODE_STORAGE_KEY);
        if (storedSpeed === "fast" || storedSpeed === "thoughtful") {
          setSpeedMode(storedSpeed);
        }
      } catch {
        setErrorText(
          "Não foi possível carregar a configuração do backend. Vou manter a interface utilizável com a configuração local, mas o envio depende de o backend aceitar chamadas do navegador."
        );
        const fallbackConfig: AppConfig = {
          version: "0.2.0",
          availableModels: FALLBACK_MODELS,
          defaultSelectedModels: FALLBACK_SELECTED_MODELS,
          fileUploadModelId: FALLBACK_FILE_MODEL_ID,
        };
        const storedRoleSelection = window.localStorage.getItem(MODEL_ROLE_SELECTION_STORAGE_KEY);
        const parsedRoleSelection = storedRoleSelection
          ? (JSON.parse(storedRoleSelection) as Partial<RoleModelSelection>)
          : null;
        setRoleModelSelection(sanitizeRoleSelection(parsedRoleSelection, fallbackConfig));
      }
    }

    bootstrap();
  }, []);

  useEffect(() => {
    if (!activeConversationMenuId) {
      setConversationMenuPosition(null);
    }
  }, [activeConversationMenuId]);


  useEffect(() => {
    if (!sessionContext) {
      setConversations([]);
      setConversationId(null);
      setActiveFile(null);
      setActiveLocalFile(null);
      return;
    }

    void refreshConversations(sessionContext);
  }, [sessionContext]);

  useEffect(() => {
    if (!session?.user?.id) {
      setDisplayName("");
      setDisplayNameInput("");
      return;
    }

    const storedDisplayName = window.localStorage.getItem(
      displayNameStorageKey(session.user.id)
    );
    const nextDisplayName = storedDisplayName ?? "";
    const fallbackName = firstNameOf(session.user.name ?? "");

    setDisplayName(nextDisplayName);
    setDisplayNameInput(nextDisplayName || fallbackName);
  }, [session]);

  useEffect(() => {
    function handleOutsideClick(event: MouseEvent) {
      const target = event.target as Node;
      const htmlTarget = event.target as HTMLElement;
      if (speedMenuRef.current && !speedMenuRef.current.contains(target)) {
        setShowSpeedMenu(false);
      }
      if (userMenuRef.current && !userMenuRef.current.contains(target)) {
        setShowUserMenu(false);
      }
      if (!htmlTarget.closest("[data-conversation-menu]")) {
        setActiveConversationMenuId(null);
      }
      if (
        !htmlTarget.closest("[data-collapsed-chats-menu]") &&
        !htmlTarget.closest("[data-collapsed-chats-trigger]")
      ) {
        setShowCollapsedConversations(false);
      }
      if (!htmlTarget.closest("[data-model-dropdown]")) {
        setActiveModelDropdownRole(null);
      }
      if (!htmlTarget.closest("[data-dislike-popover]")) {
        setActiveDislikeIndex(null);
        setDislikeReason("");
      }
    }

    document.addEventListener("mousedown", handleOutsideClick);
    return () => document.removeEventListener("mousedown", handleOutsideClick);
  }, []);

  useEffect(() => {
    window.localStorage.setItem(SPEED_MODE_STORAGE_KEY, speedMode);
  }, [speedMode]);

  useEffect(() => {
    window.localStorage.setItem(
      MODEL_ROLE_SELECTION_STORAGE_KEY,
      JSON.stringify(roleModelSelection)
    );
  }, [roleModelSelection]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({
      behavior: "smooth",
      block: "end",
    });
  }, [messages, isLoading]);

  useEffect(() => {
    function refreshScrollButtonVisibility() {
      const node = scrollRef.current;
      if (!node) {
        return;
      }

      const overflowAmount = node.scrollHeight - node.clientHeight;
      const distanceToBottom = node.scrollHeight - node.scrollTop - node.clientHeight;
      const hasMeaningfulOverflow = overflowAmount > 260;
      setShowScrollButton(hasMeaningfulOverflow && distanceToBottom > 220);
    }

    refreshScrollButtonVisibility();
    window.addEventListener("resize", refreshScrollButtonVisibility);
    return () => window.removeEventListener("resize", refreshScrollButtonVisibility);
  }, [messages, isLoading]);

  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) {
      return;
    }

    textarea.style.height = "0px";
    textarea.style.height = `${textarea.scrollHeight}px`;
  }, [input]);

  async function refreshConversations(sessionData: AgentSessionContext) {
    try {
      const data = await listConversations(sessionData);
      setConversations(data);
    } catch {
      setErrorText("Não foi possível carregar suas conversas salvas.");
    }
  }

  function upsertGuestConversation(
    nextMessages: ChatMessage[],
    options?: {
      preferredId?: string;
      title?: string | null;
      lockTitle?: boolean;
      activeFile?: ActiveFile | null;
      activeLocalFile?: File | null;
    }
  ) {
    const nextId =
      options?.preferredId ??
      guestConversationId ??
      (globalThis.crypto?.randomUUID?.() ?? `guest-${Date.now()}-${Math.random()}`);
    const providedTitle = normalizedProvidedTitle(options?.title);

    setGuestConversations((current) => {
      const existingConversation = current.find((conversation) => conversation.id === nextId);
      const fallbackTitleFromUser = titleFromUserPrompt(
        nextMessages.find((message) => message.role === "user")?.content ?? ""
      );
      const fallbackTitleFromAssistant = titleFromAssistantResponse(
        [...nextMessages]
          .reverse()
          .find((message) => message.role === "assistant")
          ?.content ?? ""
      );

      const fallbackTitle = chooseConversationTitle([
        fallbackTitleFromUser,
        fallbackTitleFromAssistant,
      ]);

      const existingTitle = (existingConversation?.title ?? "").trim();
      const keepLockedTitle = existingConversation?.titleLocked === true;
      const hasStableExistingTitle =
        existingTitle.length > 0 && existingTitle.toLowerCase() !== "nova conversa";
      const resolvedTitle = keepLockedTitle
        ? existingTitle
        : hasStableExistingTitle
          ? existingTitle
          : chooseConversationTitle([providedTitle, fallbackTitle]);

      const titleLocked =
        keepLockedTitle ||
        options?.lockTitle === true ||
        (resolvedTitle.length > 0 && resolvedTitle.toLowerCase() !== "nova conversa");

      const nextSummary: LocalConversation = {
        id: nextId,
        title: resolvedTitle || "Nova conversa",
        preview: conversationPreviewFromMessages(nextMessages),
        updatedAt: new Date().toISOString(),
        messages: nextMessages,
        activeFile: options?.activeFile ?? existingConversation?.activeFile ?? null,
        activeLocalFile: options?.activeLocalFile ?? existingConversation?.activeLocalFile ?? null,
        titleLocked,
      };
      const withoutCurrent = current.filter((conversation) => conversation.id !== nextId);
      return [nextSummary, ...withoutCurrent];
    });
    setGuestConversationId(nextId);
    return nextId;
  }

  async function loadConversation(summary: ConversationSummary) {
    if (!sessionContext) {
      const localConversation = guestConversations.find(
        (conversation) => conversation.id === summary.id
      );
      if (!localConversation) {
        return;
      }
      setGuestConversationId(localConversation.id);
      setMessages(localConversation.messages);
      setActiveFile(localConversation.activeFile ?? null);
      setActiveLocalFile(localConversation.activeLocalFile ?? null);
      setSelectedFile(null);
      setErrorText("");
      setShowCollapsedConversations(false);
      setActiveConversationMenuId(null);
      return;
    }

    try {
      const data = await getConversation(summary.id, sessionContext);
      setConversationId(data.id);
      setMessages(data.messages);
      setActiveFile(data.activeFile ?? null);
      setActiveLocalFile(null);
      setSelectedFile(null);
      setErrorText("");
      setShowCollapsedConversations(false);
      setActiveConversationMenuId(null);
    } catch {
      setErrorText("Não foi possível abrir esta conversa.");
    }
  }

  async function handleRenameConversation(conversation: ConversationSummary) {
    if (!sessionContext) {
      const nextTitle = renameConversationValue.trim();
      if (!nextTitle) {
        setErrorText("Informe um titulo para renomear a conversa.");
        return;
      }
      setGuestConversations((current) =>
        current.map((item) =>
          item.id === conversation.id
            ? {
                ...item,
                title: nextTitle,
                titleLocked: true,
              }
            : item
        )
      );
      setRenamingConversationId(null);
      setRenameConversationValue("");
      setActiveConversationMenuId(null);
      return;
    }

    const nextTitle = renameConversationValue.trim();
    if (!nextTitle) {
      setErrorText("Informe um titulo para renomear a conversa.");
      return;
    }

    try {
      await renameConversation(conversation.id, nextTitle, sessionContext);
      setConversations((current) =>
        current.map((item) =>
          item.id === conversation.id
            ? {
                ...item,
                title: nextTitle,
              }
            : item
        )
      );
      setRenamingConversationId(null);
      setRenameConversationValue("");
      setActiveConversationMenuId(null);
    } catch {
      setErrorText("Nao foi possivel renomear esta conversa.");
    }
  }

  async function handleDeleteConversation(conversation: ConversationSummary) {
    if (!sessionContext) {
      setGuestConversations((current) =>
        current.filter((item) => item.id !== conversation.id)
      );
      if (guestConversationId === conversation.id) {
        startNewConversation();
      }
      setActiveConversationMenuId(null);
      setShowCollapsedConversations(false);
      return;
    }

    try {
      await deleteConversation(conversation.id, sessionContext);
      setConversations((current) => current.filter((item) => item.id !== conversation.id));
      if (conversationId === conversation.id) {
        startNewConversation();
      }
      setActiveConversationMenuId(null);
    } catch {
      setErrorText("Nao foi possivel excluir esta conversa.");
    }
  }

  function handleShareConversation() {
    setErrorText("Compartilhar estara disponivel em breve.");
    setActiveConversationMenuId(null);
  }

  function resolveConversationMenuPosition(
    trigger: HTMLElement | null
  ): ConversationMenuPosition | null {
    if (!trigger) {
      return null;
    }

    const rect = trigger.getBoundingClientRect();
    const estimatedMenuWidth = 176;
    const estimatedMenuHeight = 170;
    const gap = 12;
    const edgePadding = 8;
    const spaceBelow = window.innerHeight - rect.bottom;
    const openUp = spaceBelow < estimatedMenuHeight;

    let left = rect.right + gap;
    const maxLeft = window.innerWidth - estimatedMenuWidth - edgePadding;
    if (left > maxLeft) {
      left = Math.max(edgePadding, rect.left - estimatedMenuWidth - gap);
    }

    let top = openUp ? rect.bottom - estimatedMenuHeight : rect.top;
    const maxTop = window.innerHeight - estimatedMenuHeight - edgePadding;
    top = Math.min(Math.max(edgePadding, top), Math.max(edgePadding, maxTop));

    return {
      left,
      top,
    };
  }

  function openConversationMenu(
    event: ReactMouseEvent<HTMLButtonElement>,
    conversationId: string,
    context: ConversationMenuContext
  ) {
    const nextPosition = resolveConversationMenuPosition(event.currentTarget);
    if (!nextPosition) {
      return;
    }

    setActiveConversationMenuId((current) => {
      if (current === conversationId) {
        return null;
      }

      setActiveConversationMenuContext(context);
      setConversationMenuPosition(nextPosition);
      return conversationId;
    });
  }

  async function handleLogin() {
    setErrorText("");
    const result = await signIn("google", { callbackUrl: "/" });
    if (result?.error) {
      setErrorText("Nao foi possivel iniciar o login com Google agora.");
    }
  }

  function handleLogout() {
    if (session?.user?.id) {
      window.localStorage.removeItem(displayNameStorageKey(session.user.id));
    }
    setDisplayName("");
    setDisplayNameInput("");
    setMessages([]);
    setConversations([]);
    setGuestConversations([]);
    setConversationId(null);
    setGuestConversationId(null);
    setShowUserMenu(false);
    setActiveDislikeIndex(null);
    setDislikeReason("");
    setFeedbackLoadingIndex(null);
    setRestoreLoadingIndex(null);
    setCopiedMessageIndex(null);
    void signOut({ callbackUrl: "/" });
  }

  function persistDisplayName() {
    if (!session?.user) {
      return;
    }

    const trimmed = displayNameInput.trim();
    const nextValue = trimmed === firstNameOf(session.user.name ?? "") ? "" : trimmed;
    setDisplayName(nextValue);
    window.localStorage.setItem(displayNameStorageKey(session.user.id ?? ""), nextValue);
    setShowUserMenu(false);
  }

  function updateRoleModel(role: keyof RoleModelSelection, modelId: string) {
    if (selectedFile) {
      return;
    }

    setRoleModelSelection((current) => ({
      ...current,
      [role]: modelId,
    }));
    setActiveModelDropdownRole(null);
  }

  function toggleModel(modelId: string) {
    updateRoleModel("executorModelId", modelId);
  }

  function toggleModelDropdown(role: keyof RoleModelSelection) {
    if (selectedFile) {
      return;
    }
    setActiveModelDropdownRole((current) => (current === role ? null : role));
  }

  function handleScroll() {
    const node = scrollRef.current;
    if (!node) {
      return;
    }

    const overflowAmount = node.scrollHeight - node.clientHeight;
    const distanceToBottom =
      node.scrollHeight - node.scrollTop - node.clientHeight;
    const hasMeaningfulOverflow = overflowAmount > 260;
    setShowScrollButton(hasMeaningfulOverflow && distanceToBottom > 220);
  }

  function scrollToBottom() {
    messagesEndRef.current?.scrollIntoView({
      behavior: "smooth",
      block: "end",
    });
  }

  async function copyCodeBlock(content: string) {
    try {
      await navigator.clipboard.writeText(content);
      setCopiedBlockId(content);
      window.setTimeout(() => setCopiedBlockId((current) => (current === content ? null : current)), 700);
    } catch {
      setErrorText("Não foi possível copiar o conteúdo do bloco.");
    }
  }

  async function copyMessageContent(message: ChatMessage, messageIndex: number) {
    try {
      const copiedText = message.document?.blocks?.length
        ? assistantDocumentToClipboardText(message.document)
        : markdownToClipboardText(message.content);
      await navigator.clipboard.writeText(copiedText);
      setCopiedMessageIndex(messageIndex);
      window.setTimeout(
        () => setCopiedMessageIndex((current) => (current === messageIndex ? null : current)),
        700
      );
      setErrorText("");
    } catch {
      setErrorText("Não foi possível copiar esta resposta.");
    }
  }

  function lastUserPromptBeforeIndex(targetIndex: number) {
    for (let index = targetIndex - 1; index >= 0; index--) {
      const message = messages[index];
      if (message?.role === "user") {
        return message.content;
      }
    }
    return "";
  }

  async function handleFeedback(type: "LIKE" | "DISLIKE", assistantIndex: number) {
    const assistantMessage = messages[assistantIndex];
    if (!assistantMessage || assistantMessage.role !== "assistant") {
      return;
    }

    if (type === "DISLIKE" && !dislikeReason.trim()) {
      setErrorText("Explique rapidamente o motivo do dislike.");
      return;
    }

    const prompt = lastUserPromptBeforeIndex(assistantIndex);
    const trace = assistantMessage.responseTrace;
    setFeedbackLoadingIndex(assistantIndex);
    setErrorText("");

    try {
      await submitFeedback({
        type,
        responseContent: assistantMessage.content,
        userPrompt: prompt || "Sem prompt identificado",
        conversationId: sessionContext ? conversationId : guestConversationId,
        plannerModelId: trace?.plannerModelId ?? effectiveRoleModelSelection.plannerModelId,
        executorModelId: trace?.executorModelId ?? effectiveRoleModelSelection.executorModelId,
        reviewerModelId: trace?.reviewerModelId ?? effectiveRoleModelSelection.reviewerModelId,
        reason: type === "DISLIKE" ? dislikeReason.trim() : null,
        selectedModelIds: effectiveModelIds,
        session: sessionContext,
      });
      setActiveDislikeIndex(null);
      setDislikeReason("");
    } catch {
      setErrorText("Não foi possível registrar o feedback agora.");
    } finally {
      setFeedbackLoadingIndex(null);
    }
  }

  async function handleRestoreResponse(assistantIndex: number) {
    const assistantMessage = messages[assistantIndex];
    if (!assistantMessage || assistantMessage.role !== "assistant") {
      return;
    }

    const displayContextMessages = messages.slice(0, assistantIndex);
    const retryUserIndex = (() => {
      for (let index = assistantIndex - 1; index >= 0; index -= 1) {
        if (messages[index]?.role === "user") {
          return index;
        }
      }
      return -1;
    })();
    if (retryUserIndex < 0) {
      return;
    }
    const retryPayloadMessages = messages.slice(0, retryUserIndex + 1);

    setRestoreLoadingIndex(assistantIndex);
    setErrorText("");
    setMessages([...displayContextMessages, { ...assistantMessage, content: "", document: null }]);
    let streamRenderTimer: number | null = null;
    try {
      let streamedContent = "";
      const finalPayloadRef: { payload: ChatPayloadResponse | null } = { payload: null };

      if (process.env.NODE_ENV !== "production") {
        const latestPayloadMessage = retryPayloadMessages.at(-1);
        console.info("[chat-restore]", {
          assistantIndex,
          retryUserIndex,
          payloadMessageCount: retryPayloadMessages.length,
          latestPayloadRole: latestPayloadMessage?.role ?? null,
          latestPayloadLength: latestPayloadMessage?.content.length ?? 0,
          hasConversationId: Boolean(sessionContext ? conversationId : guestConversationId),
        });
      }

      await postChatStream(
        {
          conversationId: sessionContext ? conversationId : guestConversationId,
          messages: retryPayloadMessages,
          selectedModelIds: effectiveModelIds,
          modelSelection: effectiveRoleModelSelection,
          reasoningMode: speedMode === "fast" ? "FAST" : "THOUGHTFUL",
          file: sessionContext ? null : activeLocalFile,
          activeFileContextId: sessionContext
            ? (retryPayloadMessages.at(-1)?.fileContextId ?? activeFile?.id ?? null)
            : null,
          session: sessionContext,
        },
        {
          onDelta: (chunk) => {
            streamedContent = mergeStreamChunk(streamedContent, chunk);
            if (streamRenderTimer !== null) {
              return;
            }
            streamRenderTimer = window.setTimeout(() => {
              streamRenderTimer = null;
              setMessages([
                ...displayContextMessages,
                {
                  ...assistantMessage,
                  content: normalizeAssistantContent(streamedContent),
                  document: null,
                },
              ]);
            }, STREAM_RENDER_INTERVAL_MS);
          },
          onDone: (payload) => {
            if (streamRenderTimer !== null) {
              window.clearTimeout(streamRenderTimer);
              streamRenderTimer = null;
            }
            finalPayloadRef.payload = payload;
          },
        }
      );

      const finalAssistantContent = normalizeAssistantContent(
        finalPayloadRef.payload?.response || streamedContent || emptyAssistantText()
      );
      const refreshedAssistant: ChatMessage = {
        role: "assistant",
        content: finalAssistantContent,
        document: selectRenderableAssistantDocument(
          finalPayloadRef.payload?.document,
          finalAssistantContent,
          streamedContent
        ),
        responseTrace: {
          plannerModelId: finalPayloadRef.payload?.plannerModelId ?? null,
          executorModelId: finalPayloadRef.payload?.executorModelId ?? null,
          reviewerModelId: finalPayloadRef.payload?.reviewerModelId ?? null,
        },
      };
      const nextMessages = [...displayContextMessages, refreshedAssistant];
      setMessages(nextMessages);
      setRestoreLoadingIndex(null);

      if (!sessionContext) {
        upsertGuestConversation(nextMessages, {
          preferredId: guestConversationId ?? undefined,
          activeFile,
          activeLocalFile,
        });
      } else if (finalPayloadRef.payload?.conversationId) {
        setConversationId(finalPayloadRef.payload.conversationId);
        setActiveFile(finalPayloadRef.payload.activeFile ?? activeFile);
        await refreshConversations(sessionContext);
      }
    } catch {
      setMessages([...displayContextMessages, assistantMessage]);
      setErrorText("Não foi possível refazer esta resposta agora.");
    } finally {
      if (streamRenderTimer !== null) {
        window.clearTimeout(streamRenderTimer);
      }
      setRestoreLoadingIndex(null);
    }
  }

  async function handleSubmit() {
    if ((!input.trim() && !selectedFile) || isLoading || !config) {
      return;
    }

    const activeFileForMessage = selectedFile ? activeFileFromFile(selectedFile) : activeFile;
    const userMessage: ChatMessage = {
      role: "user",
      content: input.trim() || `Analisar arquivo ${selectedFile?.name}`,
      attachmentName: selectedFile?.name ?? activeFileForMessage?.fileName ?? null,
      fileContextId: selectedFile ? null : activeFileForMessage?.id ?? null,
    };

    const isFirstGuestConversationMessage = !sessionContext && !guestConversationId;
    const nextMessages = [...messages, userMessage];
    setMessages(nextMessages);
    setInput("");
    setIsLoading(true);
    setErrorText("");

    const file = selectedFile ?? (sessionContext ? null : activeLocalFile);
    const uploadedFile = selectedFile;
    setSelectedFile(null);
    setPasteNotice("");
    setActiveModelDropdownRole(null);

    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }

    let streamRenderTimer: number | null = null;
    try {
      let streamedContent = "";
      const finalPayloadRef: { payload: ChatPayloadResponse | null } = { payload: null };

      await postChatStream(
        {
          conversationId: sessionContext
            ? conversationId
            : guestConversationId ?? null,
          messages: nextMessages,
          selectedModelIds: effectiveModelIds,
          modelSelection: effectiveRoleModelSelection,
          reasoningMode: speedMode === "fast" ? "FAST" : "THOUGHTFUL",
          file,
          activeFileContextId: sessionContext && !uploadedFile ? activeFile?.id ?? null : null,
          session: sessionContext,
        },
        {
          onDelta: (chunk) => {
            streamedContent = mergeStreamChunk(streamedContent, chunk);
            if (streamRenderTimer !== null) {
              return;
            }
            streamRenderTimer = window.setTimeout(() => {
              streamRenderTimer = null;
              setMessages([
                ...nextMessages,
                {
                  role: "assistant",
                  content: normalizeAssistantContent(streamedContent),
                  document: null,
                },
              ]);
            }, STREAM_RENDER_INTERVAL_MS);
          },
          onDone: (payload) => {
            if (streamRenderTimer !== null) {
              window.clearTimeout(streamRenderTimer);
              streamRenderTimer = null;
            }
            finalPayloadRef.payload = payload;
          },
        }
      );

      const finalAssistantContent = normalizeAssistantContent(
        finalPayloadRef.payload?.response || streamedContent || emptyAssistantText()
      );
      const assistantMessage: ChatMessage = {
        role: "assistant",
        content: finalAssistantContent,
        document: selectRenderableAssistantDocument(
          finalPayloadRef.payload?.document,
          finalAssistantContent,
          streamedContent
        ),
        responseTrace: {
          plannerModelId: finalPayloadRef.payload?.plannerModelId ?? null,
          executorModelId: finalPayloadRef.payload?.executorModelId ?? null,
          reviewerModelId: finalPayloadRef.payload?.reviewerModelId ?? null,
        },
      };
      const nextActiveFile = finalPayloadRef.payload?.activeFile
        ?? (uploadedFile ? activeFileFromFile(uploadedFile) : activeFile);
      const nextActiveLocalFile = uploadedFile ?? activeLocalFile;
      const resolvedUserMessages = nextActiveFile
        ? nextMessages.map((message, messageIndex) =>
            messageIndex === nextMessages.length - 1 && message.role === "user"
              ? {
                  ...message,
                  attachmentName: message.attachmentName ?? nextActiveFile.fileName,
                  fileContextId: message.fileContextId ?? nextActiveFile.id,
                }
              : message
          )
        : nextMessages;
      const finalMessages = [...resolvedUserMessages, assistantMessage];
      if (nextActiveFile) {
        setActiveFile(nextActiveFile);
      }
      if (!sessionContext && nextActiveLocalFile) {
        setActiveLocalFile(nextActiveLocalFile);
      }
      setMessages(finalMessages);
      if (!sessionContext) {
        const normalizedBackendTitle = normalizeBackendConversationTitle(finalPayloadRef.payload?.conversationTitle);
        const assistantBasedFallbackTitle = titleFromAssistantResponse(assistantMessage.content);
        const userBasedFallbackTitle = titleFromUserPrompt(userMessage.content);
        const resolvedTitle = isFirstGuestConversationMessage
          ? chooseConversationTitle([
              normalizedBackendTitle,
              userBasedFallbackTitle,
              assistantBasedFallbackTitle,
            ])
          : null;
        upsertGuestConversation(finalMessages, {
          preferredId: guestConversationId ?? undefined,
          title: resolvedTitle,
          lockTitle: isFirstGuestConversationMessage,
          activeFile: nextActiveFile,
          activeLocalFile: nextActiveLocalFile,
        });
      }

      if (finalPayloadRef.payload?.conversationId && sessionContext) {
        setConversationId(finalPayloadRef.payload.conversationId);
        setActiveFile(finalPayloadRef.payload.activeFile ?? nextActiveFile ?? null);
        setActiveLocalFile(null);
        await refreshConversations(sessionContext);
      }
    } catch (error) {
      const details = error instanceof Error ? error.message : "";
      const errorMessages = [
        ...nextMessages,
        {
          role: "assistant",
          content:
            `Não consegui concluir sua solicitação agora. Verifique o backend, o CORS e as chaves configuradas.${details ? `\n\nDetalhe técnico: ${details}` : ""}`,
        },
      ] as ChatMessage[];
      setMessages(errorMessages);
      if (!sessionContext && guestConversationId) {
        upsertGuestConversation(errorMessages, {
          preferredId: guestConversationId,
        });
      }
    } finally {
      if (streamRenderTimer !== null) {
        window.clearTimeout(streamRenderTimer);
      }
      setIsLoading(false);
    }
  }

  function handleFileChange(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    setSelectedFile(file);
    setPasteNotice("");
    if (file) {
      setActiveModelDropdownRole(null);
    }
    if (event.target) {
      event.target.value = "";
    }
  }

  function handleMessagePaste(event: React.ClipboardEvent<HTMLTextAreaElement>) {
    const pastedText = event.clipboardData.getData("text/plain");

    if (!pastedText || pastedText.length <= LONG_PASTE_CHAR_LIMIT) {
      setPasteNotice("");
      return;
    }

    event.preventDefault();

    if (selectedFile) {
      setPasteNotice(LONG_PASTE_EXISTING_FILE_NOTICE);
      return;
    }

    const pastedBytes = new TextEncoder().encode(pastedText).length;
    if (pastedBytes > LONG_PASTE_MAX_BYTES) {
      setPasteNotice(LONG_PASTE_TOO_LARGE_NOTICE);
      return;
    }

    const pastedFile = new File([pastedText], LONG_PASTE_FILE_NAME, {
      type: LONG_PASTE_FILE_TYPE,
      lastModified: Date.now(),
    });

    setSelectedFile(pastedFile);
    setActiveModelDropdownRole(null);
    setErrorText("");
    setPasteNotice(LONG_PASTE_ATTACHED_NOTICE);
    setInput((currentInput) =>
      currentInput.trim() ? currentInput : LONG_PASTE_ATTACHMENT_PROMPT
    );
  }

  async function handleRemoveActiveFile() {
    if (sessionContext && conversationId) {
      try {
        await removeActiveFile(conversationId, sessionContext);
      } catch {
        setErrorText("Não foi possível remover o arquivo ativo agora.");
        return;
      }
    }

    setActiveFile(null);
    setActiveLocalFile(null);
    setPasteNotice("");
    if (!sessionContext && guestConversationId) {
      setGuestConversations((current) =>
        current.map((conversation) =>
          conversation.id === guestConversationId
            ? { ...conversation, activeFile: null, activeLocalFile: null }
            : conversation
        )
      );
    }
  }

  function startNewConversation() {
    setConversationId(null);
    setGuestConversationId(null);
    setMessages([]);
    setSelectedFile(null);
    setActiveFile(null);
    setActiveLocalFile(null);
    setErrorText("");
    setPasteNotice("");
    setShowCollapsedConversations(false);
    setActiveConversationMenuId(null);
    setRenamingConversationId(null);
    setRenameConversationValue("");
    setActiveModelDropdownRole(null);
    setActiveDislikeIndex(null);
    setDislikeReason("");
    setFeedbackLoadingIndex(null);
    setRestoreLoadingIndex(null);
    setCopiedMessageIndex(null);
  }

  const heading = session?.user ? `Olá, ${effectiveDisplayName}!` : "Olá!";
  const userLabel = session?.user ? shortLabelOf(effectiveDisplayName) : "U";
  const shouldShowError =
    !!errorText &&
    !errorText.toLowerCase().includes("conversas salvas");
  const showGlobalTyping =
    isLoading && messages[messages.length - 1]?.role !== "assistant";

  return (
    <main className="app-shell-bg h-screen overflow-hidden text-white">
      <header
        className={`fixed top-0 right-0 z-30 border-b border-white/6 bg-[#040a1d]/88 shadow-[0_16px_40px_rgba(2,6,23,0.36)] backdrop-blur-2xl left-0 ${
          isSidebarCollapsed ? "md:left-[4.5rem]" : "md:left-[20rem]"
        }`}
      >
        <div className="mx-auto flex h-14 max-w-[1600px] items-center justify-end px-4 sm:px-6 lg:px-8">

          <div className="relative" ref={userMenuRef}>
            {session?.user ? (
              <>
                <button
                  type="button"
                  onClick={() => setShowUserMenu((current) => !current)}
                  className="inline-flex items-center gap-2 rounded-2xl border border-sky-300/20 bg-sky-400/12 px-4 py-2 text-sm font-medium text-sky-100 shadow-[0_10px_26px_rgba(14,116,144,0.22)] transition duration-200 hover:border-sky-300/35 hover:bg-sky-400/18 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-300/40 focus-visible:ring-offset-2 focus-visible:ring-offset-[#040a1d]"
                >
                  <Image
                    src="/media/icon-logged.svg"
                    alt=""
                    width={16}
                    height={16}
                    className="h-4 w-4 shrink-0"
                  />
                  Olá, {effectiveDisplayName}
                </button>

                {showUserMenu && (
                  <div className="absolute right-0 mt-3 w-72 rounded-[1.5rem] border border-white/10 bg-[rgba(6,11,24,0.96)] p-4 shadow-[0_28px_80px_rgba(2,6,23,0.55)]">
                    <p className="text-sm font-semibold text-white">
                      Como devemos te chamar?
                    </p>
                    <p className="mt-1 text-xs leading-5 text-slate-400">
                      Isso altera apenas a forma de saudação aqui no chat.
                    </p>
                    <input
                      value={displayNameInput}
                      onChange={(event) => setDisplayNameInput(event.target.value)}
                      className="mt-3 w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-2.5 text-sm outline-none transition focus:border-sky-300/30"
                      placeholder="Seu nome ou apelido"
                    />
                    <div className="mt-4 flex gap-2">
                      <button
                        type="button"
                        onClick={persistDisplayName}
                        className="flex-1 rounded-2xl bg-sky-500 px-3 py-2 text-sm font-medium text-white transition hover:bg-sky-400"
                      >
                        Salvar
                      </button>
                      <button
                        type="button"
                        onClick={handleLogout}
                        className="rounded-2xl border border-white/10 px-3 py-2 text-sm text-slate-300 transition hover:border-white/20 hover:bg-white/5"
                      >
                        Sair
                      </button>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <button
                type="button"
                onClick={handleLogin}
                className="inline-flex items-center gap-2 rounded-2xl border border-white/12 bg-white/[0.035] px-4 py-2 text-sm font-medium text-slate-200 shadow-[0_10px_24px_rgba(2,6,23,0.35)] transition duration-200 hover:border-sky-300/30 hover:bg-sky-300/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-300/30 focus-visible:ring-offset-2 focus-visible:ring-offset-[#040a1d]"
                aria-label="Entrar com Google"
              >
                <Image
                  src="/media/icon-login.svg"
                  alt=""
                  width={16}
                  height={16}
                  className="h-4 w-4 opacity-90"
                />
                Login
              </button>
            )}
          </div>
        </div>
      </header>

      <div className={`grid h-full ${isSidebarCollapsed ? "md:grid-cols-[4.5rem_minmax(0,1fr)]" : "md:grid-cols-[20rem_minmax(0,1fr)]"}`}>
        <aside
          className={`hidden h-screen border-r border-white/8 bg-[linear-gradient(180deg,rgba(3,8,24,0.96)_0%,rgba(2,8,24,0.92)_100%)] shadow-[inset_-1px_0_0_rgba(148,163,184,0.1)] md:flex md:flex-col ${
            isSidebarCollapsed ? "px-2 pt-0 pb-4" : "px-5 pt-0 pb-5"
          }`}
        >
          {isSidebarCollapsed ? (
            <div className="relative flex flex-1 flex-col items-center gap-3">
              <button
                type="button"
                onClick={() => {
                  setIsSidebarCollapsed(false);
                  setActiveModelDropdownRole(null);
                }}
                className="flex h-11 w-11 items-center justify-center rounded-xl border border-white/12 bg-white/[0.03] shadow-[0_10px_20px_rgba(2,6,23,0.35)] transition duration-200 hover:border-sky-300/28 hover:bg-sky-300/10"
                aria-label="Expandir sidebar"
                title="Expandir sidebar"
              >
                <Image src="/media/icon-crash-sidebar.svg" alt="" width={18} height={18} className="h-[18px] w-[18px]" />
              </button>

              <button
                type="button"
                onClick={startNewConversation}
                className="flex h-11 w-11 items-center justify-center rounded-xl border border-white/12 bg-white/[0.03] shadow-[0_10px_20px_rgba(2,6,23,0.35)] transition duration-200 hover:border-sky-300/28 hover:bg-sky-300/10"
                aria-label="Nova conversa"
                title="Nova conversa"
              >
                <Image src="/media/icon-new-chat.svg" alt="" width={18} height={18} className="h-[18px] w-[18px]" />
              </button>

              <div className="relative">
              <button
                type="button"
                onClick={() => {
                  setShowCollapsedConversations((current) => !current);
                  setActiveConversationMenuId(null);
                }}
                className="flex h-11 w-11 items-center justify-center rounded-xl border border-white/12 bg-white/[0.03] shadow-[0_10px_20px_rgba(2,6,23,0.35)] transition duration-200 hover:border-sky-300/28 hover:bg-sky-300/10"
                aria-label="Conversas"
                title="Conversas"
                data-collapsed-chats-trigger
              >
                <Image src="/media/icon-chats.svg" alt="" width={18} height={18} className="h-[18px] w-[18px]" />
              </button>

              {showCollapsedConversations ? (
                <div
                  className="absolute left-[calc(100%+10px)] top-0 z-40 w-[20rem] rounded-[1.75rem] border border-white/12 bg-[rgba(5,11,29,0.95)] p-3.5 shadow-[inset_0_0_0_1px_rgba(148,163,184,0.08),0_30px_80px_rgba(2,6,23,0.62)] backdrop-blur-xl"
                  data-collapsed-chats-menu
                >
                  <p className="mb-2.5 px-2 text-lg font-semibold text-slate-100">Recentes</p>
                  <div className="scroll-shell max-h-[70vh] overflow-y-auto pr-1">
                    {hasSidebarConversations ? (
                      <div className="space-y-1">
                        {sidebarConversations.map((conversation) => (
                          <div
                            key={`collapsed-${conversation.id}`}
                            data-conversation-menu
                            className={`relative overflow-visible rounded-2xl px-3 py-2.5 transition ${
                              activeSidebarConversationId === conversation.id
                                ? "bg-sky-400/18 shadow-[0_12px_28px_rgba(14,116,144,0.28)]"
                                : "hover:bg-white/8"
                            }`}
                          >
                            <div className="flex items-center justify-between gap-2">
                              <button
                                type="button"
                                onClick={() => void loadConversation(conversation)}
                                className="min-w-0 flex-1 text-left text-sm text-slate-100"
                              >
                                <span className="block truncate">{conversation.title}</span>
                              </button>
                              <button
                                type="button"
                                onClick={(event) =>
                                  openConversationMenu(event, conversation.id, "collapsed")
                                }
                                className="inline-flex h-7 w-7 items-center justify-center rounded-md text-base leading-none text-slate-200 transition hover:bg-white/10 hover:text-white"
                              >
                                ...
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="px-2 text-sm text-slate-300">
                        {sessionContext
                          ? "Nenhuma conversa salva ainda."
                          : "Nenhuma conversa nesta pagina ainda."}
                      </p>
                    )}
                  </div>
                </div>
              ) : null}
            </div>
            </div>
          ) : (
            <>
              <div className="mb-1 flex h-14 items-center justify-between gap-2">
                <button
                  type="button"
                  onClick={startNewConversation}
                  className="min-w-0 text-left"
                >
                  <div className="flex items-center gap-2">
                    <Image src="/media/icon-header-title.svg" alt="" width={26} height={26} className="h-[26px] w-[26px]" />
                    <p className="text-[1.18rem] font-semibold leading-[1.05] tracking-tight">FA Chat</p>
                  </div>
                </button>

                <button
                  type="button"
                  onClick={() => {
                    setIsSidebarCollapsed(true);
                    setActiveModelDropdownRole(null);
                  }}
                  className="mt-0.5 flex h-9 w-9 items-center justify-center rounded-lg border border-white/12 bg-white/[0.03] transition duration-200 hover:border-sky-300/30 hover:bg-sky-300/10"
                  aria-label="Recolher sidebar"
                  title="Recolher sidebar"
                >
                  <Image src="/media/icon-crash-sidebar.svg" alt="" width={16} height={16} className="h-4 w-4" />
                </button>
              </div>
              <p className="mb-7 text-left text-xs text-slate-400">
                Multi-agent chat {displayedAppVersion}
              </p>

              <div className="scroll-shell flex-1 overflow-y-auto pr-1">
            <button
              type="button"
              onClick={startNewConversation}
              className="mb-4 flex w-full items-center gap-2 rounded-xl border border-white/10 bg-white/[0.02] px-3 py-2 text-left text-xs font-semibold uppercase tracking-[0.14em] text-slate-300 transition duration-200 hover:border-sky-300/28 hover:bg-sky-300/8"
            >
              <Image src="/media/icon-new-chat.svg" alt="" width={14} height={14} className="h-3.5 w-3.5" />
              Nova conversa
            </button>
            <div className="rounded-[1.25rem] border border-white/10 bg-[rgba(7,14,34,0.78)] p-4 shadow-[inset_0_0_0_1px_rgba(148,163,184,0.06),0_22px_70px_rgba(2,6,23,0.36)]">
              <div className="space-y-2.5">
                <p className="text-[11px] font-semibold uppercase tracking-[0.17em] text-slate-300">
                  Escolha seu modelo
                </p>
                <ModelDropdown
                  label="Pensar"
                  value={effectiveRoleModelSelection.plannerModelId}
                  options={config.availableModels}
                  disabled={!!selectedFile}
                  isOpen={activeModelDropdownRole === "plannerModelId"}
                  onToggle={() => toggleModelDropdown("plannerModelId")}
                  onSelect={(modelId) => updateRoleModel("plannerModelId", modelId)}
                />

                <ModelDropdown
                  label="Executar"
                  value={effectiveRoleModelSelection.executorModelId}
                  options={config.availableModels}
                  disabled={!!selectedFile}
                  isOpen={activeModelDropdownRole === "executorModelId"}
                  onToggle={() => toggleModelDropdown("executorModelId")}
                  onSelect={(modelId) => updateRoleModel("executorModelId", modelId)}
                />

                <ModelDropdown
                  label="Revisar"
                  value={effectiveRoleModelSelection.reviewerModelId}
                  options={config.availableModels}
                  disabled={!!selectedFile}
                  isOpen={activeModelDropdownRole === "reviewerModelId"}
                  onToggle={() => toggleModelDropdown("reviewerModelId")}
                  onSelect={(modelId) => updateRoleModel("reviewerModelId", modelId)}
                />
              </div>
            </div>

            {hasSidebarConversations ? (
              <p className="mt-4 px-1 text-xs uppercase tracking-[0.2em] text-slate-400">
                Conversas
              </p>
            ) : null}

            {hasSidebarConversations ? (
              <div className="mt-4 space-y-2">
                {sidebarConversations.map((conversation) => (
                  <div
                    key={conversation.id}
                    data-conversation-menu
                    className={`relative overflow-visible w-full rounded-xl border px-3 py-3 text-left transition ${
                      activeSidebarConversationId === conversation.id
                        ? "border-sky-300/38 bg-sky-400/12 shadow-[0_14px_34px_rgba(14,116,144,0.26)]"
                        : "border-white/7 bg-white/[0.02] hover:border-sky-300/24 hover:bg-white/[0.06]"
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <button
                        type="button"
                        onClick={() => loadConversation(conversation)}
                        className="min-w-0 flex-1 pr-1 text-left"
                      >
                        <p className="truncate text-sm font-medium leading-6 text-white">{conversation.title}</p>
                      </button>

                      <button
                        type="button"
                        onClick={(event) =>
                          openConversationMenu(event, conversation.id, "expanded")
                        }
                        className="inline-flex h-7 w-7 items-center justify-center rounded-md text-base leading-none text-slate-300 transition hover:bg-white/10 hover:text-white"
                      >
                        ...
                      </button>
                    </div>

                    {renamingConversationId === conversation.id ? (
                      <div className="mt-2 flex items-center gap-2">
                        <input
                          value={renameConversationValue}
                          onChange={(event) => setRenameConversationValue(event.target.value)}
                          className="w-full rounded-md border border-white/10 bg-white/[0.04] px-2 py-1.5 text-sm text-white outline-none focus:border-sky-300/40"
                          placeholder="Novo titulo"
                        />
                        <button
                          type="button"
                          onClick={() => void handleRenameConversation(conversation)}
                          className="rounded-md bg-sky-500 px-2 py-1.5 text-xs text-white"
                        >
                          Salvar
                        </button>
                      </div>
                    ) : null}

                  </div>
                ))}
              </div>
            ) : null}

            <div className="hidden rounded-[2rem] border border-white/10 bg-[rgba(8,15,33,0.72)] p-5 shadow-[0_22px_90px_rgba(2,6,23,0.42)]">
              <p className="text-xs uppercase tracking-[0.24em] text-sky-300/70">
                Fluxo atual
              </p>
              <p className="mt-3 text-lg font-semibold text-white">
                Planejamento, execução e revisão
              </p>
              <p className="mt-2 text-sm leading-6 text-slate-400">
                O backend já recebe histórico, preferência de velocidade, modelos
                selecionados e contexto de arquivo quando existir.
              </p>
            </div>

            <div className="hidden mt-4 rounded-[2rem] border border-white/10 bg-[rgba(8,15,33,0.72)] p-5 shadow-[0_22px_90px_rgba(2,6,23,0.42)]">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="text-xs uppercase tracking-[0.24em] text-amber-300/70">
                    Modelos
                  </p>
                  <h2 className="mt-2 text-lg font-semibold text-white">
                    Escolha sua pilha
                  </h2>
                </div>
                {selectedFile && config ? (
                  <span className="rounded-full border border-amber-300/15 bg-amber-300/10 px-2.5 py-1 text-[11px] font-medium text-amber-200">
                    Arquivo: {config.fileUploadModelId}
                  </span>
                ) : null}
              </div>

              <div className="mt-4 space-y-3">
                {config.availableModels.map((model) => {
                  const checked = effectiveModelIds.includes(model.id);
                  const disabled = !!selectedFile && model.id !== config.fileUploadModelId;

                  return (
                    <button
                      key={model.id}
                      type="button"
                      onClick={() => toggleModel(model.id)}
                      disabled={disabled}
                      className={`w-full rounded-[1.5rem] border px-4 py-3 text-left transition ${
                        checked
                          ? "border-sky-400/30 bg-sky-400/10"
                          : "border-white/8 bg-white/[0.03]"
                      } ${disabled ? "cursor-not-allowed opacity-45" : "hover:border-white/15 hover:bg-white/[0.05]"}`}
                    >
                      <div className="flex items-center justify-between gap-3">
                        <div>
                          <p className="text-sm font-medium text-white">
                            {model.label}
                          </p>
                          <p className="mt-1 text-xs leading-5 text-slate-400">
                            {model.description}
                          </p>
                        </div>
                        <span
                          className={`h-4 w-4 rounded-full border ${
                            checked
                              ? "border-sky-300 bg-sky-400"
                              : "border-white/20 bg-transparent"
                          }`}
                        />
                      </div>
                    </button>
                  );
                })}
              </div>

              <p className="mt-4 text-xs leading-5 text-slate-400">
                Se o modelo escolhido falhar, o backend tenta outro disponível em
                fallback. Com arquivo anexado, a seleção é limitada ao modelo
                configurado no backend.
              </p>
              <p className="mt-3 text-[11px] uppercase tracking-[0.2em] text-slate-500">
                Fonte dos modelos: backend
              </p>
            </div>

            <div className="hidden mt-4 rounded-[2rem] border border-white/10 bg-[rgba(8,15,33,0.72)] p-5 shadow-[0_22px_90px_rgba(2,6,23,0.42)]">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <p className="text-xs uppercase tracking-[0.24em] text-emerald-300/70">
                    Conversas
                  </p>
                  <h2 className="mt-2 text-lg font-semibold text-white">
                    Histórico
                  </h2>
                </div>
                <button
                  type="button"
                  onClick={startNewConversation}
                  className="rounded-full border border-white/10 px-3 py-1.5 text-xs text-slate-300 transition hover:border-white/20 hover:bg-white/5"
                >
                  Nova
                </button>
              </div>

              {session ? (
                <div className="mt-4 space-y-3">
                  {conversations.length === 0 ? (
                    <p className="rounded-[1.4rem] border border-dashed border-white/10 px-4 py-4 text-sm leading-6 text-slate-400">
                      Suas conversas salvas vão aparecer aqui assim que você
                      começar a usar o chat logado.
                    </p>
                  ) : (
                    conversations.map((conversation) => (
                      <button
                        key={conversation.id}
                        type="button"
                        onClick={() => loadConversation(conversation)}
                        className={`w-full rounded-[1.5rem] border px-4 py-3 text-left transition ${
                          conversationId === conversation.id
                            ? "border-sky-400/30 bg-sky-400/10"
                            : "border-white/8 bg-white/[0.03] hover:border-white/15 hover:bg-white/[0.05]"
                        }`}
                      >
                        <p className="text-sm font-medium text-white">
                          {conversation.title}
                        </p>
                        <p className="mt-1 text-xs leading-5 text-slate-400">
                          {conversation.preview}
                        </p>
                        <p className="mt-2 text-[11px] uppercase tracking-[0.2em] text-slate-500">
                          {formatConversationDate(conversation.updatedAt)}
                        </p>
                      </button>
                    ))
                  )}
                </div>
              ) : (
                <p className="mt-4 rounded-[1.4rem] border border-dashed border-white/10 px-4 py-4 text-sm leading-6 text-slate-400">
                  Sem login, a conversa fica viva apenas enquanto esta página
                  estiver aberta. Atualizou, recomeça.
                </p>
              )}
            </div>
          </div>
            </>
          )}
        </aside>

        <section className="relative flex min-h-0 flex-col bg-[rgba(4,10,26,0.95)] pt-14">
          <div
            ref={scrollRef}
            onScroll={handleScroll}
            className="scroll-shell flex-1 overflow-x-hidden overflow-y-auto px-4 py-4 pb-8 sm:px-6 lg:px-8"
          >
            <div className="mx-auto w-full max-w-[62rem]">
              {messages.length === 0 ? (
                <div className="flex min-h-[64vh] flex-col items-center justify-center text-center">
                  <div className="max-w-3xl rounded-[2.4rem] border border-white/12 bg-[linear-gradient(145deg,rgba(8,15,33,0.94),rgba(7,10,22,0.8))] px-8 py-9 shadow-[0_30px_110px_rgba(2,6,23,0.56)]">
                    <p className="text-xs uppercase tracking-[0.28em] text-sky-300/70">
                      Bem-vindo!
                    </p>
                    <h1 className="mt-4 bg-gradient-to-r from-sky-50 via-cyan-100 to-sky-300 bg-clip-text text-4xl font-semibold text-transparent drop-shadow-[0_0_18px_rgba(56,189,248,0.2)] sm:text-5xl">
                      {heading}
                    </h1>
                    <p className="mt-4 text-sm leading-7 text-slate-200 sm:text-base">
                      Chat multiagente com foco em tarefas curtas. Não sou igual
                      o concorrente, mas aqui ele não vai te decepcionar
                      depois de 10 mensagens...
                    </p>

                    <div className="mt-7 grid gap-3 text-left sm:grid-cols-2">
                      {[
                        "Quero construir uma API em Java, por onde começo?",
                        "O que são fenômenos climáticos?",
                        "Me dê ideias de renda extra",
                        "Tive uma ideia, pode me ajudar?",
                      ].map((suggestion) => (
                        <button
                          key={suggestion}
                          type="button"
                          onClick={() => setInput(suggestion)}
                          className="rounded-[1.6rem] border border-white/10 bg-white/[0.03] px-4 py-4 text-sm text-slate-200 shadow-[0_8px_24px_rgba(2,6,23,0.22)] transition duration-200 ease-out hover:-translate-y-0.5 hover:border-sky-300/30 hover:bg-white/[0.07] hover:shadow-[0_18px_40px_rgba(8,47,73,0.26)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-300/30"
                        >
                          {suggestion}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              ) : (
                <div className="space-y-6">
                  {messages.map((message, index) => {
                    const isUser = message.role === "user";
                    const isAssistant = message.role === "assistant";
                    const normalizedAssistantContent = isAssistant
                      ? normalizeAssistantContent(message.content)
                      : "";
                    const assistantDocument = isAssistant && message.document?.blocks?.length
                      ? message.document
                      : null;
                    const hasMarkdownTable =
                      !!assistantDocument?.blocks.some((block) => block.type === "table") ||
                      (isAssistant &&
                        /(^|\n)\s*\|[^|\n]+(?:\|[^|\n]+)+\|?\s*$/m.test(normalizedAssistantContent));
                    const isRestoringAssistant =
                      isAssistant && restoreLoadingIndex === index && !message.content.trim();
                    const isLatestAssistant =
                      isAssistant &&
                      messages.findLastIndex((item) => item.role === "assistant") === index;
                    const userMessageKey = `${index}-${message.content.length}-${message.content.slice(0, 24)}`;
                    const shouldCollapseUserMessage = isUser && isLongUserMessage(message.content);
                    const isUserMessageExpanded = expandedUserMessages.has(userMessageKey);
                    return (
                      <div
                        key={`${message.role}-${index}-${message.content.slice(0, 24)}`}
                        className={`grid justify-center gap-4 ${
                          hasMarkdownTable
                            ? "md:grid-cols-[2.75rem_minmax(0,70rem)_2.75rem]"
                            : "md:grid-cols-[2.75rem_minmax(0,48rem)_2.75rem]"
                        }`}
                      >
                        {isUser ? (
                          <div className="hidden md:block" />
                        ) : (
                          <div className="hidden md:block">
                            <AssistantAvatar />
                          </div>
                        )}

                        <div className="min-w-0">
                          {isUser ? (
                            <div className="ml-auto w-fit max-w-full rounded-[2rem] rounded-br-md border border-sky-300/10 bg-[linear-gradient(135deg,rgba(29,78,216,0.92),rgba(8,47,73,0.94))] px-4 py-3.5 text-[13px] leading-6 text-white shadow-[0_18px_60px_rgba(30,64,175,0.24)] sm:text-sm">
                              {message.attachmentName ? (
                                <div className="mb-3 inline-flex items-center gap-2 rounded-full border border-white/15 bg-black/15 px-3 py-1 text-xs text-sky-100">
                                  <Image
                                    src="/media/icon-file.svg"
                                    alt=""
                                    width={14}
                                    height={14}
                                    className="h-3.5 w-3.5"
                                  />
                                  {message.attachmentName}
                                </div>
                              ) : null}
                              {shouldCollapseUserMessage && !isUserMessageExpanded ? (
                                <div className="relative max-w-[42rem]">
                                  <p className="whitespace-pre-wrap text-white/92">
                                    {userMessagePreview(message.content)}
                                  </p>
                                  <div className="pointer-events-none absolute inset-x-0 bottom-9 h-10 bg-gradient-to-t from-[#123a79]/95 to-transparent" />
                                  <button
                                    type="button"
                                    onClick={() => toggleExpandedUserMessage(userMessageKey)}
                                    className="mt-3 rounded-full border border-white/15 bg-white/10 px-3 py-1 text-xs font-semibold text-sky-50 transition hover:border-white/25 hover:bg-white/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-200/40"
                                  >
                                    Mostrar mais
                                  </button>
                                </div>
                              ) : (
                                <>
                                  <p className="whitespace-pre-wrap">{message.content}</p>
                                  {shouldCollapseUserMessage ? (
                                    <button
                                      type="button"
                                      onClick={() => toggleExpandedUserMessage(userMessageKey)}
                                      className="mt-3 rounded-full border border-white/15 bg-white/10 px-3 py-1 text-xs font-semibold text-sky-50 transition hover:border-white/25 hover:bg-white/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-200/40"
                                    >
                                      Mostrar menos
                                    </button>
                                  ) : null}
                                </>
                              )}
                            </div>
                          ) : (
                            <div
                              className={`rounded-[1.7rem] rounded-bl-md border border-white/10 bg-[rgba(8,15,33,0.82)] px-4 py-4 shadow-[0_20px_62px_rgba(2,6,23,0.4)] backdrop-blur-xl sm:px-5 ${
                                hasMarkdownTable ? "max-w-[70rem]" : "max-w-[48rem]"
                              }`}
                            >
                              {isRestoringAssistant ? (
                                <TypingIndicator />
                              ) : (
                                <div className="max-w-none break-words text-sm leading-7 text-slate-200 sm:text-[15px]">
                                  {assistantDocument ? (
                                    <AssistantDocumentRenderer
                                      document={assistantDocument}
                                      copiedBlockId={copiedBlockId}
                                      onCopyCode={(codeText) => void copyCodeBlock(codeText)}
                                    />
                                  ) : (
                                  <ReactMarkdown
                                    remarkPlugins={[remarkGfm]}
                                    components={{
                                    h1: ({ children }) => (
                                      (() => {
                                        const heading = headingTextFromChildren(children);
                                        if (!heading) {
                                          return null;
                                        }
                                        return (
                                          <h1 className="mb-4 border-b border-white/10 pb-2 text-xl font-semibold tracking-tight text-white sm:text-2xl">
                                            {heading}
                                          </h1>
                                        );
                                      })()
                                    ),
                                    h2: ({ children }) => (
                                      (() => {
                                        const heading = headingTextFromChildren(children);
                                        if (!heading) {
                                          return null;
                                        }
                                        return (
                                          <h2 className="mt-6 mb-3 rounded-xl border border-sky-300/18 bg-sky-400/8 px-3 py-2 text-lg font-semibold tracking-tight text-sky-50 sm:text-xl">
                                            {heading}
                                          </h2>
                                        );
                                      })()
                                    ),
                                    h3: ({ children }) => (
                                      (() => {
                                        const heading = headingTextFromChildren(children);
                                        if (!heading) {
                                          return null;
                                        }
                                        return (
                                          <h3 className="mt-5 mb-2.5 text-base font-semibold tracking-tight text-sky-100 sm:text-lg">
                                            {heading}
                                          </h3>
                                        );
                                      })()
                                    ),
                                    p: ({ children }) =>
                                      isCalloutParagraph(children) ? (
                                        <div className="my-4 rounded-xl border border-emerald-300/16 bg-emerald-400/[0.06] px-3.5 py-2.5 text-slate-100 shadow-[inset_0_0_0_1px_rgba(16,185,129,0.08)]">
                                          <p className="whitespace-pre-wrap leading-7 text-slate-100">{children}</p>
                                        </div>
                                      ) : (
                                        <p className="mb-3.5 whitespace-pre-wrap leading-7 text-slate-200 last:mb-0">
                                          {children}
                                        </p>
                                      ),
                                    strong: ({ children }) => {
                                      const content = nodeToText(children).trim();
                                      const isAlternativeHighlight =
                                        /^[A-Z]\)/.test(content) ||
                                        /^(alternativa|resposta|opcao|opção)\s+[A-Z]\)/i.test(content);

                                      return (
                                        <strong
                                          className={
                                            isAlternativeHighlight
                                              ? "rounded-md border border-sky-300/30 bg-sky-400/18 px-1.5 py-0.5 font-semibold text-sky-50"
                                              : "font-semibold text-white"
                                          }
                                        >
                                          {children}
                                        </strong>
                                      );
                                    },
                                    ul: ({ children }) => (
                                      <ul className="mb-4 list-disc space-y-2 pl-5 marker:text-sky-300">
                                        {children}
                                      </ul>
                                    ),
                                    ol: ({ children }) => (
                                      <ol className="mb-4 list-decimal space-y-2 pl-5 marker:text-[1.03em] marker:font-semibold marker:text-sky-300">
                                        {children}
                                      </ol>
                                    ),
                                    li: ({ children }) => (
                                      <li className="leading-7 text-slate-100 [&>p]:mb-0">{children}</li>
                                    ),
                                    em: ({ children }) => (
                                      <em className="italic text-slate-100">{children}</em>
                                    ),
                                    blockquote: ({ children }) => (
                                      <blockquote className="my-4 rounded-r-2xl border-l-4 border-amber-300/60 bg-amber-300/6 px-4 py-3 italic text-slate-300">
                                        {children}
                                      </blockquote>
                                    ),
                                    a: ({ href, children }) => (
                                      <a
                                        href={href}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        className="font-medium text-sky-200 underline decoration-sky-300/45 underline-offset-2 transition hover:text-sky-100"
                                      >
                                        {children}
                                      </a>
                                    ),
                                    hr: () => (
                                      <hr className="my-5 border-0 border-t border-white/12" />
                                    ),
                                    table: ({ children }) => (
                                      <div className="my-5 overflow-x-auto rounded-2xl border border-sky-300/15 bg-[#030c22]/70 shadow-[inset_0_0_0_1px_rgba(14,165,233,0.06)]">
                                        <table className="min-w-full border-collapse text-left text-[13px] leading-6 sm:text-sm">
                                          {children}
                                        </table>
                                      </div>
                                    ),
                                    thead: ({ children }) => (
                                      <thead className="bg-sky-400/10">{children}</thead>
                                    ),
                                    tbody: ({ children }) => (
                                      <tbody className="divide-y divide-white/8">{children}</tbody>
                                    ),
                                    tr: ({ children }) => (
                                      <tr className="align-top transition-colors hover:bg-white/[0.03]">{children}</tr>
                                    ),
                                    th: ({ children }) => (
                                      <th className="border-b border-white/12 px-4 py-2.5 font-semibold text-sky-100">
                                        {children}
                                      </th>
                                    ),
                                    td: ({ children }) => (
                                      <td className="border-b border-white/6 px-4 py-2.5 align-top text-slate-200">
                                        {children}
                                      </td>
                                    ),
                                    pre: ({ children }) => (
                                      <pre className="my-5 overflow-x-auto rounded-[1.2rem] border border-white/10 bg-[#020617] p-3.5 text-[13px] leading-6 text-slate-200">
                                        {children}
                                      </pre>
                                    ),
                                    code(props: MarkdownCodeProps) {
                                      const { children, className, ...rest } = props;
                                      const match = /language-(\w+)/.exec(className || "");
                                      const codeText = String(children).replace(/\n$/, "");
                                      const isCopied = copiedBlockId === codeText;

                                      return match ? (
                                        <div className="group relative my-6 overflow-hidden rounded-[1.35rem] border border-white/10 bg-[#020617]">
                                          <div className="flex items-center justify-between border-b border-white/10 bg-white/[0.04] px-4 py-2 text-[11px] uppercase tracking-[0.24em] text-slate-400">
                                            <span>{match[1]}</span>
                                            <button
                                              type="button"
                                              onClick={() => void copyCodeBlock(codeText)}
                                              className={`inline-flex items-center gap-2 rounded-full px-2.5 py-1 transition ${
                                                isCopied
                                                  ? "copy-success bg-emerald-500/15 text-emerald-300"
                                                  : "text-slate-300 hover:bg-white/6 hover:text-white"
                                              }`}
                                            >
                                              <Image
                                                src="/media/icon-copy.svg"
                                                alt=""
                                                width={14}
                                                height={14}
                                                className="h-3.5 w-3.5"
                                              />
                                              {isCopied ? "Copiado" : "Copiar"}
                                            </button>
                                          </div>
                                          <SyntaxHighlighter
                                            {...rest}
                                            PreTag="div"
                                            language={match[1]}
                                            style={oneDark}
                                            customStyle={{
                                              margin: 0,
                                              backgroundColor: "#020617",
                                              padding: "1.2rem",
                                              borderRadius: 0,
                                              fontSize: "0.86rem",
                                              lineHeight: "1.65",
                                            }}
                                            codeTagProps={{
                                              style: { backgroundColor: "transparent" },
                                            }}
                                          >
                                            {codeText}
                                          </SyntaxHighlighter>
                                        </div>
                                      ) : (
                                        <code className="rounded-md border border-sky-300/25 bg-sky-400/16 px-1.5 py-0.5 font-mono text-[0.9em] font-medium text-sky-100">
                                          {children}
                                        </code>
                                      );
                                    },
                                    }}
                                  >
                                    {normalizedAssistantContent}
                                  </ReactMarkdown>
                                  )}
                                </div>
                              )}
                            </div>
                          )}

                          {isAssistant ? (
                            <div className="mt-2 flex items-center gap-1 pl-1 text-slate-300">
                              <button
                                type="button"
                                onClick={() => void handleFeedback("LIKE", index)}
                                disabled={feedbackLoadingIndex === index || isRestoringAssistant}
                                className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-white/8 bg-white/[0.02] transition hover:border-white/20 hover:bg-white/[0.08] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-300/30 disabled:opacity-50"
                                aria-label="Gostei da resposta"
                              >
                                <Image src="/media/icon-chat-like.svg" alt="" width={14} height={14} className="h-3.5 w-3.5" />
                              </button>

                              <div className="relative" data-dislike-popover>
                                <button
                                  type="button"
                                  onClick={() => {
                                    setActiveDislikeIndex((current) => (current === index ? null : index));
                                    setDislikeReason("");
                                  }}
                                  disabled={feedbackLoadingIndex === index || isRestoringAssistant}
                                  className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-white/8 bg-white/[0.02] transition hover:border-white/20 hover:bg-white/[0.08] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-300/30 disabled:opacity-50"
                                  aria-label="Não gostei da resposta"
                                >
                                  <Image
                                    src="/media/icon-chat-like.svg"
                                    alt=""
                                    width={14}
                                    height={14}
                                    className="h-3.5 w-3.5 rotate-180"
                                  />
                                </button>

                                {activeDislikeIndex === index ? (
                                  <div className="absolute left-0 top-9 z-30 w-72 rounded-xl border border-white/14 bg-[#030712] p-3 shadow-[inset_0_0_0_1px_rgba(148,163,184,0.08),0_18px_45px_rgba(2,6,23,0.55)]">
                                    <p className="text-xs text-slate-200">O que faltou nessa resposta?</p>
                                    <textarea
                                      value={dislikeReason}
                                      onChange={(event) => setDislikeReason(event.target.value)}
                                      rows={3}
                                      className="mt-2 w-full resize-none rounded-lg border border-white/12 bg-white/[0.03] px-2.5 py-2 text-xs text-white outline-none focus:border-sky-300/35"
                                      placeholder="Escreva rapidamente o motivo..."
                                    />
                                    <div className="mt-2 flex items-center justify-end gap-2">
                                      <button
                                        type="button"
                                        onClick={() => {
                                          setActiveDislikeIndex(null);
                                          setDislikeReason("");
                                        }}
                                        className="rounded-md px-2 py-1 text-xs text-slate-300 hover:bg-white/8"
                                      >
                                        Cancelar
                                      </button>
                                      <button
                                        type="button"
                                        onClick={() => void handleFeedback("DISLIKE", index)}
                                        disabled={feedbackLoadingIndex === index}
                                        className="rounded-md bg-rose-500/80 px-2 py-1 text-xs text-white hover:bg-rose-500 disabled:opacity-50"
                                      >
                                        Enviar
                                      </button>
                                    </div>
                                  </div>
                                ) : null}
                              </div>

                              <button
                                type="button"
                                onClick={() => void handleRestoreResponse(index)}
                                disabled={!isLatestAssistant || restoreLoadingIndex === index}
                                className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-white/8 bg-white/[0.02] transition hover:border-white/20 hover:bg-white/[0.08] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-300/30 disabled:opacity-45"
                                aria-label="Refazer resposta"
                              >
                                <Image src="/media/icon-restore-chat.svg" alt="" width={14} height={14} className="h-3.5 w-3.5" />
                              </button>

                              <button
                                type="button"
                                onClick={() => void copyMessageContent(message, index)}
                                disabled={isRestoringAssistant}
                                className={`inline-flex h-7 w-7 items-center justify-center rounded-md border border-white/8 bg-white/[0.02] transition hover:border-white/20 hover:bg-white/[0.08] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-300/30 ${
                                  copiedMessageIndex === index
                                    ? "copy-success border-emerald-300/35 bg-emerald-500/15 text-emerald-300"
                                    : ""
                                }`}
                                aria-label="Copiar resposta"
                              >
                                <Image src="/media/icon-copy.svg" alt="" width={14} height={14} className="h-3.5 w-3.5" />
                              </button>
                            </div>
                          ) : null}
                        </div>

                        {isUser ? (
                          <div className="hidden md:block">
                            <UserAvatar label={userLabel} />
                          </div>
                        ) : (
                          <div className="hidden md:block" />
                        )}
                      </div>
                    );
                  })}

                  {showGlobalTyping ? (
                    <div className="grid justify-center gap-4 md:grid-cols-[2.75rem_minmax(0,48rem)_2.75rem]">
                      <div className="hidden md:block">
                        <AssistantAvatar />
                      </div>
                      <TypingIndicator />
                      <div className="hidden md:block" />
                    </div>
                  ) : null}
                </div>
              )}

              <div ref={messagesEndRef} />
            </div>
          </div>

          {showScrollButton ? (
            <button
              type="button"
              onClick={scrollToBottom}
              className="absolute bottom-40 right-4 z-20 flex h-12 w-12 items-center justify-center rounded-2xl border border-white/10 bg-[rgba(8,15,33,0.88)] shadow-[0_18px_45px_rgba(2,6,23,0.45)] backdrop-blur transition hover:border-sky-300/30 hover:bg-sky-400/12 sm:right-6"
              aria-label="Ir para o final da conversa"
            >
              <Image
                src="/media/icon-redirect-end.svg"
                alt=""
                width={20}
                height={20}
                className="h-5 w-5"
              />
            </button>
          ) : null}

          <div className="z-20 shrink-0 bg-transparent">
            <div className="mx-auto max-w-3xl px-4 py-2">
              {shouldShowError ? (
                <div className="mb-3 rounded-2xl border border-rose-300/15 bg-rose-300/8 px-4 py-3 text-sm text-rose-100">
                  {errorText}
                </div>
              ) : null}

              {pasteNotice ? (
                <div className="mb-3 rounded-2xl border border-sky-300/15 bg-sky-300/8 px-4 py-3 text-sm text-sky-100">
                  {pasteNotice}
                </div>
              ) : null}

              {activeFile && !selectedFile ? (
                <div className="mb-3 flex items-center justify-between gap-3 rounded-[1.5rem] border border-sky-300/15 bg-sky-300/8 px-4 py-3 text-sm text-sky-100">
                  <div className="flex min-w-0 items-center gap-2">
                    <Image
                      src="/media/icon-file.svg"
                      alt=""
                      width={16}
                      height={16}
                      className="h-4 w-4 shrink-0"
                    />
                    <span className="shrink-0 text-sky-200/80">Arquivo ativo:</span>
                    <span className="truncate">{activeFile.fileName}</span>
                  </div>
                  <button
                    type="button"
                    onClick={() => void handleRemoveActiveFile()}
                    className="text-xs uppercase tracking-[0.2em] text-sky-50/80"
                  >
                    Remover
                  </button>
                </div>
              ) : null}

              {selectedFile ? (
                <div className="mb-3 flex items-center justify-between gap-3 rounded-[1.5rem] border border-amber-300/15 bg-amber-300/10 px-4 py-3 text-sm text-amber-100">
                  <div className="flex min-w-0 items-center gap-2">
                    <Image
                      src="/media/icon-file.svg"
                      alt=""
                      width={16}
                      height={16}
                      className="h-4 w-4 shrink-0"
                    />
                    <span className="truncate">{selectedFile.name}</span>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setSelectedFile(null);
                      setPasteNotice("");
                    }}
                    className="text-xs uppercase tracking-[0.2em] text-amber-50/80"
                  >
                    Remover
                  </button>
                </div>
              ) : null}

              <div className="rounded-[1.8rem] border border-white/12 bg-[rgba(8,15,33,0.82)] px-3 py-2.5 shadow-[0_18px_48px_rgba(2,6,23,0.4)] transition duration-200 focus-within:border-sky-300/35 focus-within:shadow-[0_18px_52px_rgba(14,116,144,0.28)]">
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl border border-white/12 bg-white/[0.05] transition duration-200 hover:border-sky-300/30 hover:bg-sky-300/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-300/35"
                    aria-label="Adicionar arquivo"
                  >
                    <Image
                      src="/media/icon-file.svg"
                      alt=""
                      width={18}
                      height={18}
                      className="h-[18px] w-[18px]"
                    />
                  </button>

                  <input
                    ref={fileInputRef}
                    type="file"
                    className="hidden"
                    onChange={handleFileChange}
                  />

                  <textarea
                    ref={textareaRef}
                    value={input}
                    onChange={(event) => setInput(event.target.value)}
                    onPaste={handleMessagePaste}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" && !event.shiftKey) {
                        event.preventDefault();
                        void handleSubmit();
                      }
                    }}
                    rows={1}
                    placeholder="O que gostaria de saber hoje?"
                    className="max-h-36 min-h-[40px] w-full resize-none bg-transparent py-2 text-sm leading-6 text-white outline-none placeholder:text-slate-500 sm:text-[15px]"
                  />

                  <div className="relative" ref={speedMenuRef}>
                    <button
                      type="button"
                      onClick={() => setShowSpeedMenu((current) => !current)}
                      className="flex h-10 items-center rounded-2xl border border-white/12 bg-white/[0.05] px-3 text-xs font-medium uppercase tracking-[0.22em] text-slate-300 transition duration-200 hover:border-amber-300/25 hover:bg-amber-300/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300/30"
                    >
                      {speedMode === "fast" ? "Rápido" : "Sábio"}
                    </button>

                    {showSpeedMenu ? (
                      <div className="absolute bottom-14 right-0 w-56 rounded-[1.4rem] border border-white/10 bg-[rgba(6,11,24,0.96)] p-2 shadow-[0_24px_80px_rgba(2,6,23,0.55)]">
                        <button
                          type="button"
                          onClick={() => {
                            setSpeedMode("fast");
                            setShowSpeedMenu(false);
                          }}
                          className={`w-full rounded-[1rem] px-3 py-2.5 text-left text-sm transition ${
                            speedMode === "fast"
                              ? "bg-sky-400/12 text-sky-100"
                              : "text-slate-300 hover:bg-white/5"
                          }`}
                        >
                          Resposta rápida
                          <span className="mt-1 block text-xs text-slate-400">
                            Para quem tem pressa e não gosta de esperar.
                          </span>
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            setSpeedMode("thoughtful");
                            setShowSpeedMenu(false);
                          }}
                          className={`mt-2 w-full rounded-[1rem] px-3 py-2.5 text-left text-sm transition ${
                            speedMode === "thoughtful"
                              ? "bg-amber-300/12 text-amber-100"
                              : "text-slate-300 hover:bg-white/5"
                          }`}
                        >
                          Modo Sábio
                          <span className="mt-1 block text-xs text-slate-400">
                            Revisão mais criteriosa, o que pode levar mais tempo.
                          </span>
                        </button>
                      </div>
                    ) : null}
                  </div>

                  <button
                    type="button"
                    onClick={() => void handleSubmit()}
                    disabled={isLoading}
                    className="hidden"
                  >
                    ↑
                  </button>
                </div>
              </div>

              <div className="mt-3 flex items-center justify-center gap-4 text-xs text-slate-500">
                <span></span>
              </div>
            </div>
          </div>
        </section>
      </div>

      {activeConversationMenuId &&
      conversationMenuPosition &&
      activeConversationMenuConversation ? (
        <div
          data-conversation-menu
          className="fixed z-[90] w-48 rounded-xl border border-white/12 bg-[rgba(6,13,32,0.98)] p-1.5 text-sm shadow-[inset_0_0_0_1px_rgba(148,163,184,0.08),0_24px_52px_rgba(2,6,23,0.62)] backdrop-blur-xl"
          style={{
            left: `${conversationMenuPosition.left}px`,
            top: `${conversationMenuPosition.top}px`,
          }}
        >
          <button
            type="button"
            onClick={() => {
              if (activeConversationMenuContext === "collapsed") {
                setIsSidebarCollapsed(false);
                setActiveModelDropdownRole(null);
                setShowCollapsedConversations(false);
              }
              setRenamingConversationId(activeConversationMenuConversation.id);
              setRenameConversationValue(activeConversationMenuConversation.title);
              setActiveConversationMenuId(null);
            }}
            className="w-full rounded-md px-2 py-1.5 text-left text-slate-200 transition hover:bg-white/10"
          >
            Renomear
          </button>
          <button
            type="button"
            onClick={handleShareConversation}
            className="mt-1 w-full rounded-md px-2 py-1.5 text-left text-slate-200 transition hover:bg-white/10"
          >
            Compartilhar
          </button>
          <button
            type="button"
            onClick={() => void handleDeleteConversation(activeConversationMenuConversation)}
            className="mt-1 w-full rounded-md px-2 py-1.5 text-left text-rose-300 transition hover:bg-rose-400/10"
          >
            Excluir
          </button>
        </div>
      ) : null}
    </main>
  );
}
