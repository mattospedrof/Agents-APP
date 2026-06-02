"use client";

// NextAuth processa o callback em /api/auth/callback/google automaticamente.
// Esta página só existe para redirecionar caso alguém acesse a rota diretamente.

import { useEffect } from "react";
import { useRouter } from "next/navigation";

export default function GoogleCallbackPage() {
  const router = useRouter();

  useEffect(() => {
    router.replace("/");
  }, [router]);

  return (
    <main className="min-h-screen bg-background text-foreground flex items-center justify-center px-6">
      <div className="max-w-md rounded-[2rem] border border-white/10 bg-[rgba(8,15,33,0.88)] px-7 py-8 text-center shadow-[0_24px_90px_rgba(2,6,23,0.6)]">
        <p className="text-sm uppercase tracking-[0.28em] text-sky-300/70">FA Chat</p>
        <h1 className="mt-4 text-2xl font-semibold">Redirecionando...</h1>
      </div>
    </main>
  );
}