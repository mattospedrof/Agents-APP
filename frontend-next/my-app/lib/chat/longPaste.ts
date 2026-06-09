import type { ActiveFile } from "@/lib/api/agentService";

export const LONG_PASTE_CHAR_LIMIT = 8000;
export const LONG_PASTE_MAX_BYTES = 256 * 1024;
export const LONG_PASTE_FILE_NAME = "texto-colado.txt";
export const LONG_PASTE_FILE_TYPE = "text/plain;charset=utf-8";
export const LONG_PASTE_ATTACHMENT_PROMPT = "Analise o texto anexado.";
export const LONG_PASTE_ATTACHED_NOTICE =
  "Texto longo detectado. Transformei o conteúdo colado em arquivo para evitar travamento do input.";
export const LONG_PASTE_EXISTING_FILE_NOTICE =
  "Você já tem um arquivo anexado. Remova o arquivo atual antes de colar outro texto longo.";
export const LONG_PASTE_TOO_LARGE_NOTICE =
  "O texto colado ultrapassa o limite de 256 KB. Reduza o conteúdo ou envie um arquivo menor.";

export function activeFileFromFile(file: File): ActiveFile {
  return {
    id: `local-${file.name}-${file.size}-${file.lastModified}`,
    fileName: file.name,
    contentType: file.type || "text/plain",
    sizeBytes: file.size,
  };
}
