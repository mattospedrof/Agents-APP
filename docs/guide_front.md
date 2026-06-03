# Guia do frontend

Este guia descreve o frontend do FA Chat, onde ficam as principais funcionalidades e quais cuidados tomar antes de alterar a interface ou o fluxo de comunicação com o backend.

## 1. Visão geral

O frontend fica em:

```txt
frontend-next/my-app
```

Ele é uma aplicação Next.js com autenticação via NextAuth e deploy automático pela Vercel.

O navegador não chama o Cloud Run diretamente nas rotas sensíveis. As chamadas passam pelo proxy server-side do Next.js:

```txt
Browser
-> /api/agent/*
-> app/api/agent/[...path]/route.ts
-> Cloud Run backend
```

Esse proxy usa a URL do backend e uma credencial interna de comunicação servidor-servidor.

```txt
AGENT_BACKEND_URL
CREDENCIAL_DO_PROXY
```

O objetivo é manter essa credencial apenas no servidor da Vercel, nunca no client.

## 2. Estrutura de pastas relevante

```txt
frontend-next/my-app
├─ app/
│  ├─ page.tsx
│  ├─ layout.tsx
│  ├─ providers.tsx
│  ├─ globals.css
│  ├─ api/
│  │  ├─ agent/[...path]/route.ts
│  │  └─ auth/[...nextauth]/route.ts
│  └─ oauth/google/callback/page.tsx
├─ lib/
│  ├─ api/agentService.ts
│  ├─ app-constants.ts
│  ├─ auth-session.ts
│  ├─ model-fallbacks.ts
│  └─ types.ts
├─ next.config.ts
├─ package.json
└─ package-lock.json
```

Principais responsabilidades:

- `app/page.tsx`: concentra a tela principal do chat, sidebar, composer, estado de conversas, renderização das mensagens, anexos, feedback, restauração de resposta e controle visual.
- `app/api/agent/[...path]/route.ts`: proxy server-side para o backend. Injeta a credencial interna do proxy e dados de sessão quando disponíveis.
- `app/api/auth/[...nextauth]/route.ts`: configuração do NextAuth com Google.
- `lib/api/agentService.ts`: funções usadas pela UI para chamar `/api/agent/api/config`, `/api/chat`, `/api/chat/stream`, conversas e feedback.
- `lib/types.ts`: tipos compartilhados do frontend, incluindo `AppConfig`.
- `lib/model-fallbacks.ts`: modelos locais de fallback quando a configuração do backend não carrega.
- `next.config.ts`: injeta a versão do `package.json` no build via `NEXT_PUBLIC_APP_VERSION`.
- `package.json`: fonte de verdade da versão exibida no site.

## 3. Funcionalidades da UI

### Sidebar

A sidebar fica em `app/page.tsx`. Ela exibe:

- logo e nome FA Chat;
- botão de recolher/expandir;
- botão `Nova conversa`;
- seleção de modelos;
- lista de conversas;
- menu de conversa com renomear, compartilhar e excluir.

Ao alterar a sidebar, testar sempre:

- sidebar aberta;
- sidebar recolhida;
- menu de conversas;
- lista vazia;
- lista com muitas conversas;
- usuário logado e usuário guest.

### Nova conversa

O botão `Nova conversa` limpa o estado local da conversa atual e inicia uma nova conversa. Para usuários autenticados, a conversa só é persistida no backend após envio/resposta. Para usuários guest, a conversa é mantida apenas em memória enquanto a página estiver aberta.

### Lista de conversas

Usuário autenticado:

- conversa vem do backend por `/api/conversations`;
- histórico persiste após refresh.

Usuário guest:

- conversa fica em estado local;
- histórico some ao fechar/recarregar a página.

### Seleção de modelos

A UI mostra três seletores:

```txt
Pensar
Executar
Revisar
```

Eles usam os modelos retornados por `/api/config`. Quando há arquivo anexado, a seleção fica limitada ao modelo configurado no backend para upload de arquivo.

Evite alterar essa lógica sem testar:

- envio simples;
- envio com arquivo;
- fallback de modelo;
- mudança de modelo antes do envio;
- modo rápido e modo sábio.

### Envio de mensagens

O envio usa `postChatStream` em `lib/api/agentService.ts`, que chama:

```txt
POST /api/agent/api/chat/stream
```

O payload é enviado como `FormData`, com:

- `payload`: JSON da conversa;
- `file`: arquivo opcional.

### Streaming da resposta

O stream é processado no frontend a partir de eventos SSE:

- `delta`: pedaços de texto;
- `done`: resposta final estruturada;
- `error`: erro seguro.

Durante o stream, a UI mostra uma versão parcial da resposta. No `done`, o frontend usa a versão final com `AssistantDocument` quando disponível.

### Restaurar/refazer resposta

O botão de restauração reenvia o contexto até a mensagem do usuário correspondente e substitui a resposta da IA. Ele não cria nova conversa.

### Feedback positivo e negativo

Os botões de feedback ficam abaixo da resposta da IA:

- like;
- dislike com motivo;
- restore;
- copy.

O feedback chama `/api/feedback` pelo proxy e envia metadados de modelos e contexto, sem depender de alteração no fluxo de conversa.

### Renderização estruturada

Quando o backend retorna `AssistantDocument`, a UI usa `AssistantDocumentRenderer`.

Blocos suportados:

- heading;
- paragraph;
- blockquote;
- bulletList;
- orderedList;
- table;
- codeBlock;
- horizontalRule.

Essa renderização é o caminho principal porque reduz problemas de Markdown malformado.

### Renderização fallback Markdown

Quando o documento estruturado não existe ou não passa na validação local, a UI usa `ReactMarkdown` com `remark-gfm`. Antes de renderizar, passa por normalizadores para corrigir problemas comuns de LLM, como:

- títulos colados em texto;
- tabelas com pipes quebrados;
- listas sem espaçamento;
- palavras coladas entre rótulo e conteúdo.

Não mexer nessa área sem testar:

- Markdown com `##`;
- listas ordenadas;
- listas não ordenadas;
- tabelas;
- bloco de código;
- texto longo;
- resposta em português.

### Tabelas

Tabelas estruturadas usam bloco `table` do `AssistantDocument`. Tabelas Markdown válidas usam `remark-gfm`. Se a tabela vier quebrada, o frontend tenta cair para uma lista segura em vez de montar uma tabela visualmente incorreta.

### Listas

Listas estruturadas são renderizadas como `bulletList` ou `orderedList`. O fallback Markdown também suporta listas, mas depende de espaçamento correto no texto.

### Blocos de código

Blocos de código usam `react-syntax-highlighter` e botão de copiar no topo do bloco.

### Mensagens longas do usuário

Mensagens longas do usuário são colapsadas para manter a conversa legível. O usuário pode expandir quando precisar revisar o texto completo.

### Anexos e paste longo

O input já possui suporte visual a arquivo anexado. O paste longo no textarea transforma texto grande em arquivo `.txt`, dentro do limite configurado no frontend, para evitar travamento do input.

MVP atual:

- um arquivo por mensagem;
- texto/código;
- limite de 256 KB no paste transformado em arquivo;
- envio por `FormData`.

### Tela inicial

Quando não há mensagens, a tela mostra um card de boas-vindas com sugestões de prompts.

### Login, logout e saudação

O botão de login usa NextAuth/Google. Quando o usuário está logado, a UI mostra uma saudação e permite configurar como ele será chamado localmente.

### Versão exibida no site

A sidebar mostra:

```txt
Multi-agent chat vX.Y.Z
```

A versão vem do `package.json` do frontend, injetada no build por `next.config.ts`.

## 4. Fluxo de comunicação com backend

Fluxo real:

```txt
Browser
-> /api/agent/*
-> proxy server-side da Vercel
-> Cloud Run
-> backend Spring
```

Por que isso existe:

- proteger a credencial interna de comunicação entre frontend server-side e backend;
- evitar expor o backend diretamente no client;
- centralizar headers de sessão;
- permitir que o backend valide chamadas originadas pelo proxy server-side;
- manter o OpenRouter chamado apenas pelo backend.

O frontend usa:

```txt
AGENT_API_BASE_URL=/api/agent
```

Essa constante fica em `lib/app-constants.ts`.

## 5. Variáveis de ambiente do frontend

### Secrets

Estas variáveis devem existir na Vercel e não devem ter prefixo `NEXT_PUBLIC`:

```txt
NEXTAUTH_SECRET=uma-string-forte-gerada
GOOGLE_CLIENT_SECRET=xxxx
CREDENCIAL_DO_PROXY=uma-string-forte-com-32-ou-mais-caracteres
```

### Configuração normal

```txt
NEXTAUTH_URL=https://meu-app.vercel.app
GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
AGENT_BACKEND_URL=https://chat-backend-xxxxx-xx.a.run.app
```

### Versão pública

```txt
NEXT_PUBLIC_APP_VERSION
```

Essa variável é injetada pelo build via `next.config.ts`, usando `process.env.npm_package_version`. Não precisa ser cadastrada manualmente na Vercel quando o build roda por `npm run build`.

## 6. Versionamento frontend

O `package.json` do frontend é a fonte de verdade da versão visível.

Fluxo recomendado:

1. Alterar `version` em `frontend-next/my-app/package.json`.
2. Atualizar `package-lock.json` se necessário.
3. Rodar `npm run build`.
4. Fazer commit.
5. Criar tag com o mesmo número:

```bash
git tag -a vX.Y.Z -m "Release vX.Y.Z"
```

6. Enviar branch e tags:

```bash
git push origin main --follow-tags
```

Exemplo:

```txt
package.json: 0.1.2
tag Git: v0.1.2
site: Multi-agent chat v0.1.2
```

## 7. Pontos de atenção

- Não colocar a credencial interna do proxy com prefixo `NEXT_PUBLIC`.
- Não chamar Cloud Run diretamente do browser em rotas sensíveis.
- Não remover o proxy `/api/agent/*`.
- Não alterar `NEXTAUTH_URL` sem atualizar o OAuth Google.
- Não trocar domínio da Vercel sem atualizar CORS no Cloud Run e redirect URI no Google Console.
- Não mexer no render estruturado sem testar Markdown, tabela, lista, código e resposta longa.
- Não adicionar dependências de renderização sem validar impacto no bundle.
- Não salvar segredo em `.env.local` versionado.
