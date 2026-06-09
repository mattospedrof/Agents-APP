export function AssistantAvatar() {
  return (
    <div className="flex h-11 w-11 items-center justify-center rounded-2xl border border-sky-400/20 bg-gradient-to-br from-sky-400 to-blue-600 text-sm font-bold text-white shadow-[0_0_35px_rgba(59,130,246,0.32)]">
      AI
    </div>
  );
}

export function UserAvatar({ label }: { label: string }) {
  return (
    <div className="flex h-11 w-11 items-center justify-center rounded-2xl border border-white/10 bg-white/10 text-sm font-bold text-white">
      {label}
    </div>
  );
}

export function TypingIndicator() {
  return (
    <div className="flex w-fit items-center gap-1 rounded-2xl border border-white/10 bg-[rgba(8,15,33,0.82)] px-4 py-3 shadow-xl">
      <span className="h-2 w-2 animate-bounce rounded-full bg-slate-300 [animation-delay:-0.25s]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-slate-300 [animation-delay:-0.1s]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-slate-300" />
    </div>
  );
}
