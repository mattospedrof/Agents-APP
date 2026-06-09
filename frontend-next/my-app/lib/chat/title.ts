export type ConversationPreviewMessage = {
  content: string;
};

export function firstNameOf(name: string) {
  return name.trim().split(/\s+/)[0] || "você";
}

export function shortLabelOf(name: string) {
  return firstNameOf(name).slice(0, 1).toUpperCase() || "U";
}

export function formatConversationDate(value: string) {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

export function normalizeConversationText(value: string) {
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

export function normalizeConversationTitle(title: string | null | undefined) {
  const normalized = normalizeConversationText(title ?? "");
  if (!normalized) {
    return "";
  }
  return buildShortTitleFromText(normalized);
}

export function chooseConversationTitle(candidates: Array<string | null | undefined>) {
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

export function normalizeBackendConversationTitle(title: string | null | undefined) {
  const normalized = normalizeConversationText(title ?? "")
    .replace(/^["'`]+|["'`.]+$/g, "")
    .trim();
  if (!normalized || normalized.toLowerCase() === "nova conversa") {
    return "";
  }
  return normalized.length > 64 ? `${normalized.slice(0, 61).trimEnd()}...` : normalized;
}

export function titleFromUserPrompt(prompt: string) {
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

export function normalizedProvidedTitle(title: string | null | undefined) {
  const computed = normalizeBackendConversationTitle(title);
  return computed === "Nova conversa" ? "" : computed;
}

export function titleFromAssistantResponse(response: string) {
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

export function conversationPreviewFromMessages(messages: ConversationPreviewMessage[]) {
  if (messages.length === 0) {
    return "";
  }

  const latestMessage = messages[messages.length - 1];
  const normalized = normalizeConversationText(latestMessage.content);
  return normalized.length > 90 ? `${normalized.slice(0, 90)}...` : normalized;
}
