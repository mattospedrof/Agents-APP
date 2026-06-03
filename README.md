![Status](https://img.shields.io/badge/Status-Em%20Desenvolvimento-darkblue)

# FA Chat

Chat multiagente com foco em respostas claras, contexto de conversa e experiência simples para uso diário.

## Funcionalidades principais

- Chat multiagente com etapas de planejamento, execução e revisão.
- Seleção de modelos para pensar, executar e revisar.
- Streaming de resposta em tempo real.
- Respostas estruturadas com títulos, tópicos, listas, tabelas e blocos de código.
- Login com Google.
- Conversas persistidas para usuários autenticados.
- Modo guest com conversa temporária enquanto a página estiver aberta.
- Suporte visual a arquivos anexados.
- Proteção contra paste longo no input, convertendo texto grande em arquivo `.txt`.
- Botões de feedback, copiar e refazer resposta.
- Histórico lateral de conversas.
- Versionamento exibido na interface.

## Arquitetura resumida

```txt
Browser
-> Vercel / Next.js
-> Proxy server-side do frontend
-> Backend Spring Boot no Cloud Run
-> Supabase PostgreSQL
-> OpenRouter
```

O frontend roda na Vercel, o backend roda no Cloud Run e as respostas dos modelos são processadas pelo backend antes de voltarem para a interface.

# Rodar localmente
Para rodar localmente o backend:
- Crie um .env dentro de backend/confs (de acordo com .env-example)
- Coloque valores reais
- Dentro do terminal na pasta do backend rode ```.\run-local-back.ps1```

Para rodar localmente o frontend:
- Crie um .env.local dentro da pasta do app (de acordo com .env-example dentro dessa pasta)
- Coloque valores reais
- Dentro do terminal na pasta do app rode ```npm run dev```


## Stack

### Frontend

- Next.js.
- React.
- NextAuth.
- Vercel.

### Backend

- Java.
- Spring Boot.
- Maven.
- PostgreSQL/Supabase.
- Flyway.
- OpenRouter.
- Cloud Run.

## Documentação

- [Guia do frontend](docs/guide_front.md)
- [Guia do backend](docs/guide_back.md)
- [Guia de deploy](docs/deploy.md)

## Versionamento

A versão exibida no site vem do `package.json` do frontend.

Exemplo:

```txt
version: 0.1.2
tag: v0.1.2
```

2026 ~ Feito com Java, Spring Boot, Next.Js e OpenRouter
