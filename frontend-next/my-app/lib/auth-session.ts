// Sessão agora é gerenciada pelo NextAuth (useSession).
// Este arquivo mantém apenas utilitários de display name.

import { displayNameStorageKey } from "@/lib/app-constants";

export function loadDisplayName(userId: string): string {
  if (typeof window === "undefined") return "";
  return window.localStorage.getItem(displayNameStorageKey(userId)) ?? "";
}

export function saveDisplayName(userId: string, name: string) {
  window.localStorage.setItem(displayNameStorageKey(userId), name);
}

export function clearDisplayName(userId: string) {
  window.localStorage.removeItem(displayNameStorageKey(userId));
}
