# agents_app

Monorepo do chat multiagente:
- `frontend-next/my-app`: Next.js + NextAuth
- `backend`: Spring Boot (API + integracao OpenRouter)

## Desenvolvimento local com Docker Compose
Use apenas para ambiente local:

```bash
docker compose -f docker-compose.dev.yml up --build
```

## Producao (Cloud Run)
Guia de deploy/backend:
- [docs/deploy.md](docs/deploy.md)

## Arquivos de ambiente
- Backend: `backend/.env.example`
- Frontend: `frontend-next/my-app/.env.example`

Nunca commite `.env` com valores reais. Se algum segredo local ja foi exposto, rotacione antes de deploy.
