# Guia de deploy

Este guia documenta como reproduzir o deploy do FA Chat usando Vercel para o frontend e Google Cloud Run para o backend.

Os valores abaixo são fictícios. Nunca coloque secrets reais neste arquivo.

## 1. Arquitetura de produção

```txt
Browser
-> Vercel / Next.js
-> API proxy server-side /api/agent/*
-> Cloud Run chat-backend
-> Supabase PostgreSQL
-> OpenRouter
```

Ambiente validado:

- frontend: `https://fa-chat.vercel.app`;
- backend: Cloud Run, serviço `chat-backend`;
- frontend deploya automaticamente pela Vercel;
- backend deploya automaticamente pelo Cloud Build Trigger `deploy-chat-backend-main`;
- trigger do backend roda apenas quando mudam:
  - `backend/**`;
  - `cloudbuild.yaml`;
- tag validada atual: `v0.1.2`;
- versão exibida no site vem do `package.json` do frontend via `NEXT_PUBLIC_APP_VERSION`.

## 2. Pré-requisitos

- Conta Google Cloud com billing ativo.
- Google Cloud CLI.
- Node.js e npm.
- Java e Maven.
- Docker, para build local de imagem quando necessário.
- Git e GitHub.
- Conta Vercel.
- Projeto Supabase.
- Conta OpenRouter.
- Credencial OAuth Google.

## 3. APIs do Google Cloud

Ativar no projeto:

```txt
Cloud Run API
Cloud Build API
Artifact Registry API
Secret Manager API
IAM API
Cloud Logging API
```

Exemplo:

```bash
gcloud services enable run.googleapis.com
gcloud services enable cloudbuild.googleapis.com
gcloud services enable artifactregistry.googleapis.com
gcloud services enable secretmanager.googleapis.com
gcloud services enable iam.googleapis.com
gcloud services enable logging.googleapis.com
```

## 4. Budget

Criar budget no Google Cloud antes de abrir tráfego público.

Sugestão inicial:

- alerta em 50%;
- alerta em 90%;
- alerta em 100%.

## 5. Secrets no Secret Manager

Criar secrets com nomes minúsculos:

```txt
openrouter-api-key
database-url
database-username
database-password
proxy-internal-credential
```

Mapeamento no Cloud Run:

```txt
OPENROUTER_API_KEY=openrouter-api-key:latest
DATABASE_URL=database-url:latest
DATABASE_USERNAME=database-username:latest
DATABASE_PASSWORD=database-password:latest
CREDENCIAL_DO_PROXY=credencial-do-proxy:latest
```

Exemplos fictícios de valores:

```txt
OPENROUTER_API_KEY=sk-or-v1-xxxx
DATABASE_URL=jdbc:postgresql://host:5432/postgres?sslmode=require
DATABASE_USERNAME=postgres.xxxxx
DATABASE_PASSWORD=senha-forte
CREDENCIAL_DO_PROXY=uma-string-forte-com-32-ou-mais-caracteres
```

Nunca versionar esses valores.

## 6. Supabase PostgreSQL

Passos:

1. Criar projeto no Supabase.
2. Usar PostgreSQL.
3. Preferir Session Pooler/IPv4 quando necessário.
4. Extrair:
   - `DATABASE_URL`;
   - `DATABASE_USERNAME`;
   - `DATABASE_PASSWORD`.
5. Garantir `sslmode=require`.

Formato esperado:

```txt
DATABASE_URL=jdbc:postgresql://host:5432/postgres?sslmode=require
DATABASE_USERNAME=postgres.xxxxx
DATABASE_PASSWORD=senha-forte
```

## 7. Backend local

Arquivo local esperado:

```txt
backend/confs/.env
```

Importante:

- Maven/IDE não carrega `.env` automaticamente.
- Docker Compose pode usar `env_file`, se configurado.
- `run-local.ps1` ajuda a carregar variáveis locais no Windows.
- Não commitar `.env`.

Comandos de validação:

```bash
cd backend
mvn -q test
mvn -q -DskipTests package
```

Para subir localmente, usar o fluxo local do projeto, por exemplo:

```powershell
cd backend
.\run-local.ps1
```

## 8. Flyway e migrations

O backend usa Flyway. Em produção:

```txt
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
```

Migrations atuais:

```txt
V1__initial_schema.sql
V2__add_message_document_json.sql
V3__add_uploaded_files.sql
```

Regras:

- migration aplicada nunca deve ser editada;
- mudança nova vira `V4`, `V5` etc.;
- não usar `flyway clean` em produção;
- não criar tabelas manualmente no Supabase;
- se houver checksum mismatch, restaurar migration aplicada ou criar nova migration incremental.

## 9. Artifact Registry

Criar repositório Docker:

```bash
gcloud artifacts repositories create chat-backend \
  --repository-format=docker \
  --location=southamerica-east1 \
  --description="Docker images for chat backend"
```

Exemplo de variáveis fictícias:

```txt
PROJECT_ID=meu-projeto-gcp
REGION=southamerica-east1
REPOSITORY=chat-backend
SERVICE_NAME=chat-backend
```

## 10. Cloud Build manual

O projeto usa `cloudbuild.yaml` na raiz.

Comando fictício:

```bash
gcloud builds submit \
  --config cloudbuild.yaml \
  --substitutions=_CORS_ALLOWED_ORIGINS="https://meu-app.vercel.app"
```

Observações:

- `BUILD_ID` é usado na tag da imagem.
- `COMMIT_SHA` pode vir vazio em build manual.
- Por isso o `cloudbuild.yaml` usa `BUILD_ID`.
- `CORS_ALLOWED_ORIGINS` pode ter vírgulas; o `cloudbuild.yaml` usa delimitador customizado em `--set-env-vars`.

## 11. Cloud Run

Configuração inicial recomendada:

```txt
service: chat-backend
region: southamerica-east1
min instances: 0
max instances: 2
cpu: 1
memory: 1Gi
concurrency: 20
timeout: 120s
```

O serviço pode permitir tráfego público (`allUsers` invoker), porque os endpoints sensíveis continuam exigindo:

```txt
credencial interna do proxy
```

Teste de health:

```bash
curl -i https://BACKEND_URL/health
```

Esperado:

```txt
HTTP 200
{"status":"UP"}
```

Teste de proteção:

```bash
curl -i https://BACKEND_URL/api/chat
```

Esperado:

```txt
401 ou 403
```

## 12. Variáveis não secretas do Cloud Run

Exemplo:

```txt
ENVIRONMENT=production
RATE_LIMIT_ENABLED=true
RATE_LIMIT_CHAT_PER_MINUTE=10
RATE_LIMIT_GLOBAL_PER_MINUTE=60
RATE_LIMIT_BLOCK_SECONDS=300
MAX_REQUEST_BODY_BYTES=1048576
MAX_MESSAGE_CHARS=12000
CORS_ALLOWED_ORIGINS=https://meu-app.vercel.app
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
```

## 13. Vercel

Configuração do projeto:

```txt
Root Directory: frontend-next/my-app
Framework Preset: Next.js
```

Se a Vercel detectar como `Other`, ajustar manualmente para `Next.js`.

Variáveis de ambiente:

```txt
NEXTAUTH_URL=https://meu-app.vercel.app
NEXTAUTH_SECRET=uma-string-forte-gerada
GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=xxxx
AGENT_BACKEND_URL=https://BACKEND_URL
CREDENCIAL_DO_PROXY=mesmo valor configurado no backend
```

Não cadastrar a credencial interna do proxy como `NEXT_PUBLIC`.

## 14. Google OAuth

Authorized JavaScript origins:

```txt
https://meu-app.vercel.app
```

Authorized redirect URIs:

```txt
https://meu-app.vercel.app/api/auth/callback/google
```

Local:

```txt
http://localhost:3000/api/auth/callback/google
```

Se trocar domínio da Vercel, atualizar:

- `NEXTAUTH_URL`;
- Google OAuth redirect URI;
- `CORS_ALLOWED_ORIGINS` no Cloud Run.

## 15. CORS

O backend precisa receber a origem da Vercel:

```txt
CORS_ALLOWED_ORIGINS=https://meu-app.vercel.app
```

No `cloudbuild.yaml`, isso fica como substitution:

```txt
_CORS_ALLOWED_ORIGINS
```

Em produção:

- não usar `*`;
- não deixar vazio;
- incluir apenas domínios reais do frontend.

## 16. Cloud Build Trigger automático

Configuração validada:

```txt
Trigger: deploy-chat-backend-main
Região: southamerica-east1
Evento: push para branch main
Repo: GitHub
Config: cloudbuild.yaml
Service account: cloud-build-deployer@PROJECT_ID.iam.gserviceaccount.com
Logging: options.logging=CLOUD_LOGGING_ONLY
Included files:
  backend/**
  cloudbuild.yaml
Ignored files:
  vazio
```

Isso evita redeploy backend quando mudar somente frontend ou documentação.

## 17. Permissões IAM

Criar service account:

```bash
gcloud iam service-accounts create cloud-build-deployer \
  --display-name="Cloud Build backend deployer"
```

Permissões no projeto:

```txt
roles/artifactregistry.writer
roles/run.admin
roles/logging.logWriter
roles/secretmanager.secretAccessor
roles/cloudbuild.builds.builder
```

Permissão para usar a service account runtime:

```txt
roles/iam.serviceAccountUser
```

Aplicada sobre:

```txt
PROJECT_NUMBER-compute@developer.gserviceaccount.com
```

## 18. Checklist pós-deploy

- `/health` retorna `UP`.
- `/api/agent/api/config` pela Vercel retorna `200`.
- Frontend abre.
- Chat guest responde.
- Login Google funciona.
- Chat autenticado responde.
- Conversa persiste após refresh.
- Cloud Run direto em `/api/chat` sem chave retorna `401` ou `403`.
- Logs da Vercel sem erro novo relevante.
- Logs do Cloud Run sem erro novo relevante.
- Versão exibida no site bate com `package.json` e tag Git.

## 19. Troubleshooting

### `gcloud` não reconhecido

Instalar Google Cloud CLI e reiniciar o terminal.

### Artifact Registry `NOT_FOUND`

Criar o repositório Docker na região correta.

### Secret com maiúscula/minúscula errada

Secret Manager diferencia nomes. Mapear:

```txt
ENV=secret-name:latest
```

Exemplo:

```txt
OPENROUTER_API_KEY=openrouter-api-key:latest
```

### `COMMIT_SHA` vazio em build manual

Usar `BUILD_ID` para tag de imagem em build manual.

### Cloud Run `/health` retorna 403

Faltou liberar `roles/run.invoker` para `allUsers`, ou o teste está chamando rota protegida.

### Vercel retorna 404 mesmo com build ok

Conferir:

- Root Directory;
- Framework Preset Next.js;
- rota `/` do App Router.

### Proxy da Vercel tenta `127.0.0.1:8080`

`AGENT_BACKEND_URL` não está configurado no runtime da Vercel ou o deploy atual ainda não recebeu a env.

### `/api/agent/health` retorna 500

O proxy monta rotas do backend sob `/api`. O health real do backend é:

```txt
/health
```

Para testar via Vercel, prefira:

```txt
/api/agent/api/config
```

Ou teste `/health` direto no Cloud Run.

### CORS warning no frontend

Atualizar `CORS_ALLOWED_ORIGINS` no Cloud Run/cloudbuild com o domínio exato da Vercel.

### Google OAuth `redirect_uri_mismatch`

Cadastrar a URI exata:

```txt
https://meu-app.vercel.app/api/auth/callback/google
```

### Cloud Build Trigger falha por logs

Garantir no `cloudbuild.yaml`:

```yaml
options:
  logging: CLOUD_LOGGING_ONLY
```

### Trigger backend roda ao mudar frontend

Configurar `Included files`:

```txt
backend/**
cloudbuild.yaml
```

### Flyway checksum mismatch

Uma migration aplicada foi alterada. Não usar `clean` em produção. Restaurar o arquivo original ou criar uma nova migration incremental.

## 20. Versionamento e release

Fluxo recomendado:

1. Atualizar `frontend-next/my-app/package.json`.
2. Atualizar `package-lock.json` se necessário.
3. Rodar `npm run build`.
4. Commitar.
5. Criar tag anotada:

```bash
git tag -a v0.1.2 -m "Release v0.1.2"
```

6. Enviar branch e tags:

```bash
git push origin main --follow-tags
```

A tag Git deve acompanhar a versão visível no site.
