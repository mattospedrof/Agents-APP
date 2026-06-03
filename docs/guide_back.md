# Guia do backend

Este guia descreve o backend do FA Chat, seus endpoints, filtros de segurança, persistência, pipeline de resposta e cuidados para manutenção em produção.

## 1. Visão geral

O backend fica em:

```txt
backend
```

Stack principal:

- Java;
- Spring Boot;
- Maven;
- Spring Web;
- Spring Data JPA;
- Hibernate com `ddl-auto=validate`;
- Flyway para migrations;
- PostgreSQL/Supabase;
- OpenRouter para modelos de IA;
- Cloud Run como runtime de produção.

O OpenRouter deve ser chamado apenas pelo backend. O frontend acessa o backend por meio do proxy server-side da Vercel.

## 2. Estrutura de pastas relevante

```txt
backend
├─ Dockerfile
├─ pom.xml
├─ run-local.ps1
└─ src/main
   ├─ java/com/fachat/agent
   │  ├─ AgentApplication.java
   │  ├─ AgentController.java
   │  ├─ AgentExecutorService.java
   │  ├─ PlannerService.java
   │  ├─ ReviewerService.java
   │  ├─ OpenRouterClient.java
   │  ├─ config/
   │  ├─ dto/
   │  ├─ model/
   │  ├─ repository/
   │  ├─ service/
   │  └─ web/
   └─ resources
      ├─ application.properties
      └─ db/migration/
```

Responsabilidades principais:

- `AgentController.java`: endpoints `/api/*`.
- `PlannerService.java`: etapa de planejamento.
- `AgentExecutorService.java`: execução, prompts principais, geração de título e resposta.
- `ReviewerService.java`: revisão quando necessária.
- `OpenRouterClient.java`: integração com OpenRouter e fallback de modelos.
- `ModelCatalogService.java`: catálogo de modelos permitidos/disponíveis.
- `ResponseDocumentService.java`: conversão da resposta para `AssistantDocument`.
- `ConversationService.java`: persistência de conversas e mensagens.
- `UploadedFileService.java`: persistência de arquivo ativo por conversa.
- `FileContextService.java`: extração e limite de conteúdo de arquivo.
- `config/AgentProperties.java`: propriedades do app.
- `config/ProductionSafetyValidator.java`: bloqueios de segurança em produção.
- `config/WebConfig.java`: CORS.
- `web/*Filter.java`: filtros de segurança, auditoria, request ID e rate limit.
- `resources/db/migration`: migrations Flyway.

## 3. Endpoints

Todos os endpoints abaixo estão no backend Spring. No frontend, normalmente são chamados via `/api/agent/*`.

### Health

```txt
GET /health
GET /actuator/health
```

Finalidade:

- health check público;
- usado por Cloud Run e testes de disponibilidade.

Proteção:

- não exige credencial interna do proxy;
- não deve revelar detalhes sensíveis.

### Configuração

```txt
GET /api/config
```

Finalidade:

- retornar versão do backend;
- retornar modelos disponíveis;
- retornar seleção padrão;
- retornar modelo de arquivo.

Proteção:

- não é classificado como endpoint sensível pelo `ApiRoutePolicy`;
- ainda deve respeitar CORS quando chamado do navegador;
- normalmente é acessado pelo proxy da Vercel.

Impacto de alterar:

- pode quebrar seleção de modelos no frontend.

### Conversas

```txt
GET /api/conversations
GET /api/conversations/{conversationId}
PATCH /api/conversations/{conversationId}/title
DELETE /api/conversations/{conversationId}
DELETE /api/conversations/{conversationId}/active-file
```

Finalidade:

- listar conversas do usuário autenticado;
- carregar detalhes;
- renomear;
- excluir;
- remover arquivo ativo.

Proteção:

- exige credencial interna do proxy;
- usa headers de sessão enviados pelo proxy:
  - `X-Session-User-Id`;
  - `X-Session-User-Name`;
  - `X-Session-User-Email`.

Impacto de alterar:

- pode afetar histórico, persistência, sidebar e arquivos ativos.

### Chat sem stream

```txt
POST /api/chat
```

Finalidade:

- processar mensagem por multipart/form-data;
- aceitar `payload` JSON;
- aceitar arquivo opcional;
- retornar resposta final.

Proteção:

- exige credencial interna do proxy;
- sujeito a rate limit;
- sujeito a limite de body e mensagem.

Observação:

- o frontend atual usa principalmente o endpoint de stream.

### Chat com stream

```txt
POST /api/chat/stream
```

Finalidade:

- processar mensagem com SSE;
- emitir eventos `delta`;
- emitir evento final `done`;
- emitir evento `error` seguro em falha.

Proteção:

- exige credencial interna do proxy;
- sujeito a rate limit;
- sujeito a limite de body e mensagem.

Impacto de alterar:

- pode afetar a experiência principal do chat.

### Feedback

```txt
POST /api/feedback
```

Finalidade:

- registrar feedback positivo ou negativo;
- gerar tema resumido;
- logar metadados sem prompt completo.

Proteção:

- exige credencial interna do proxy;
- sujeito a rate limit global de `/api/**`.

## 4. Segurança

### Filtro de credencial interna

Protege endpoints sensíveis com:

```txt
credencial interna do proxy
```

Endpoints sensíveis:

```txt
/api/chat
/api/chat/stream
/api/conversations/**
/api/feedback
```

O filtro:

- não protege `/health`;
- não protege `/actuator/health`;
- compara segredo com `MessageDigest.isEqual`;
- não loga o valor recebido;
- retorna erro seguro.

### RestrictedEndpointFilter

Bloqueia endpoints que não devem ficar expostos:

```txt
/actuator/env
/actuator/configprops
/actuator/beans
/actuator/heapdump
/actuator/threaddump
/actuator/loggers
/swagger-ui
/v3/api-docs
/api-docs
/config
/env
/debug
/admin
/internal
```

Resposta esperada:

- genérica;
- sem stacktrace;
- sem variáveis de ambiente.

### RequestBodySizeFilter

Aplica limite de tamanho do body com base em:

```txt
MAX_REQUEST_BODY_BYTES
```

### RateLimitFilter

Aplica rate limit em memória para `/api/**`.

Categorias:

- global por IP;
- chat por IP;
- chat por usuário quando `X-Session-User-Id` existe.

Headers úteis:

```txt
Retry-After
X-RateLimit-Limit
X-RateLimit-Remaining
```

Limitação:

- rate limit em memória não é global entre múltiplas instâncias Cloud Run.

### CORS

Configurado em `WebConfig.java` para `/api/**`.

Métodos permitidos:

```txt
GET, POST, PATCH, DELETE, OPTIONS
```

Headers permitidos:

```txt
Content-Type
X-Session-User-Id
X-Session-User-Name
X-Session-User-Email
credencial interna do proxy
X-Request-Id
```

Em produção, `CORS_ALLOWED_ORIGINS` deve ser explícito e não pode conter `*`.

### RequestIdFilter e RequestAuditLoggingFilter

Garantem `requestId` e logs estruturados para auditoria no Cloud Logging.

Cloud Run pode permitir invocação pública (`allUsers`) porque os endpoints sensíveis continuam protegidos pela credencial interna validada pelo backend. CORS não é a única camada de segurança.

## 5. Banco e migrations

O backend usa:

```txt
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

Migrations atuais:

```txt
V1__initial_schema.sql
V2__add_message_document_json.sql
V3__add_uploaded_files.sql
```

Tabelas principais:

- `conversations`;
- `conversation_messages`;
- `uploaded_files`;
- `flyway_schema_history`.

Regra permanente:

> Migration já aplicada nunca deve ser editada.

Se precisar mudar schema:

1. Criar nova migration `V4__descricao.sql`, `V5__descricao.sql` etc.
2. Usar alterações incrementais.
3. Não usar `DROP`, `TRUNCATE` ou `flyway clean` em produção.
4. Manter `ddl-auto=validate`.

### Checksum mismatch

Se o Flyway acusar checksum mismatch, significa que uma migration aplicada foi alterada. Em produção:

- não usar `flyway clean`;
- não apagar dados;
- restaurar a migration ao conteúdo aplicado;
- colocar mudanças novas em uma migration nova.

### Baseline

O projeto possui:

```txt
FLYWAY_BASELINE_ON_MIGRATE
FLYWAY_BASELINE_VERSION
```

Use baseline apenas quando houver um banco já existente e decisão consciente de operação. Para banco novo, o fluxo normal é deixar o Flyway aplicar V1 em diante.

## 6. Persistência

### ConversationEntity

Tabela:

```txt
conversations
```

Campos principais:

- `id`;
- `external_user_id`;
- `title`;
- `created_at`;
- `updated_at`.

### ConversationMessageEntity

Tabela:

```txt
conversation_messages
```

Campos principais:

- `conversation_id`;
- `order_index`;
- `role`;
- `content`;
- `attachment_name`;
- `attachment_summary`;
- `document_json`;
- `file_context_id`;
- `created_at`.

### UploadedFileEntity

Tabela:

```txt
uploaded_files
```

Campos principais:

- `id`;
- `user_id`;
- `conversation_id`;
- `original_filename`;
- `safe_filename`;
- `content_type`;
- `size_bytes`;
- `extracted_text`;
- `created_at`;
- `expires_at`;
- `status`.

Impacto de alterar entidades:

- qualquer mudança estrutural precisa de nova migration;
- `ddl-auto=validate` falhará se entidade e banco divergirem.

## 7. Pipeline de resposta

Fluxo simplificado:

```txt
mensagens
-> PlannerService
-> AgentExecutorService
-> ReviewerService, quando necessário
-> ResponseDocumentService
-> ConversationService / UploadedFileService
-> resposta ao frontend
```

### Planner

Planeja intenção, executor e título base quando aplicável. Pode usar fast lane para solicitações simples.

### Executor

Gera a resposta principal, usa OpenRouter e também sugere título de conversa quando a conversa está sendo criada.

### Reviewer

Revisa respostas em modo sábio, código ou quando há arquivo.

### ResponseDocumentService

Transforma resposta final em documento estruturado (`AssistantDocument`) e mede:

```txt
documentMs
repairMs
saveMs
cached
```

### Título de conversa

O título só deve ser gerado na criação da conversa. Se o usuário renomear, o título definido pelo usuário deve ser preservado.

### Modelos

O catálogo fica em `ModelCatalogService.java` e é influenciado por:

```txt
ALLOWED_MODEL_IDS
MODEL_CATALOG_DYNAMIC_ENABLED
AGENT_FILE_UPLOAD_MODEL_ID
```

Não aceitar modelo arbitrário do client sem passar pela allowlist/catálogo do backend.

## 8. Variáveis de ambiente do backend

### Produção obrigatória

```txt
ENVIRONMENT=production
PORT=8080
CORS_ALLOWED_ORIGINS=https://meu-app.vercel.app
DATABASE_URL=jdbc:postgresql://host:5432/postgres?sslmode=require
DATABASE_USERNAME=postgres.xxxxx
DATABASE_PASSWORD=senha-forte
OPENROUTER_API_KEY=sk-or-v1-xxxx
CREDENCIAL_DO_PROXY=uma-string-forte-com-32-ou-mais-caracteres
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
```

### Configuração de modelos

```txt
ALLOWED_MODEL_IDS=
MODEL_CATALOG_DYNAMIC_ENABLED=true
AGENT_FILE_UPLOAD_MODEL_ID=openai/gpt-oss-120b:free
```

### Rate limit e limites

```txt
RATE_LIMIT_ENABLED=true
RATE_LIMIT_CHAT_PER_MINUTE=10
RATE_LIMIT_CHAT_USER_PER_MINUTE=20
RATE_LIMIT_GLOBAL_PER_MINUTE=60
RATE_LIMIT_BLOCK_SECONDS=300
MAX_REQUEST_BODY_BYTES=1048576
MAX_MESSAGE_CHARS=12000
MAX_MESSAGES_PER_REQUEST=40
MAX_TITLE_CHARS=64
```

### Banco e pool

```txt
HIKARI_MAX_POOL_SIZE=3
HIKARI_MIN_IDLE=0
HIKARI_IDLE_TIMEOUT_MS=30000
HIKARI_MAX_LIFETIME_MS=600000
FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=false
FLYWAY_BASELINE_VERSION=0
```

### Secrets

Devem ficar no Secret Manager em produção:

- `DATABASE_URL`;
- `DATABASE_USERNAME`;
- `DATABASE_PASSWORD`;
- `OPENROUTER_API_KEY`;
- credencial interna do proxy.

## 9. Produção e ProductionSafetyValidator

Em `ENVIRONMENT=production`, o `ProductionSafetyValidator` bloqueia startup quando:

- `CORS_ALLOWED_ORIGINS` está ausente;
- `CORS_ALLOWED_ORIGINS` contém `*`;
- a credencial interna do proxy está ausente;
- a credencial interna do proxy tem menos de 32 caracteres;
- `DATABASE_URL` está ausente;
- `DATABASE_USERNAME` está ausente;
- `DATABASE_PASSWORD` está ausente;
- `OPENROUTER_API_KEY` está ausente;
- `spring.jpa.hibernate.ddl-auto` não é exatamente `validate`.

Isso evita produção cair em configuração local, H2 por acidente ou schema mutável.

## 10. Pontos de atenção

- Não editar migrations antigas.
- Não usar `ddl-auto=update`, `create` ou `create-drop` em produção.
- Não expor `OPENROUTER_API_KEY` no frontend.
- Não remover o filtro de credencial interna.
- Não deixar CORS com `*` em produção.
- Não aumentar limites sem avaliar custo e abuso.
- Não logar prompt completo, cookie, token, senha ou connection string com senha.
- Rate limit em memória é suficiente para MVP, mas não é distribuído entre instâncias.
- Ao alterar prompts, testar perguntas gerais, código, tabelas, listas, arquivos e modo sábio.
