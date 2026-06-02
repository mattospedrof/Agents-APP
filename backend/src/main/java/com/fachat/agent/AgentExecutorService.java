package com.fachat.agent;

import org.springframework.stereotype.Service;

import com.fachat.agent.dto.ChatMessage;
import com.fachat.agent.dto.PlannerOutput;
import com.fachat.agent.dto.ReasoningMode;
import com.fachat.agent.service.FileContextService;
import com.fachat.agent.service.ModelCatalogService;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AgentExecutorService {

    public record ExecutorExecution(String response, String modelId) {
    }

    private final OpenRouterClient client;
    private final ModelCatalogService modelCatalog;

    public AgentExecutorService(OpenRouterClient client, ModelCatalogService modelCatalog) {
        this.client = client;
        this.modelCatalog = modelCatalog;
    }

    public String execute(
        PlannerOutput plan,
        List<ChatMessage> messages,
        List<String> selectedModelIds,
        ReasoningMode reasoningMode,
        FileContextService.FileContext fileContext
    ) {
        return executeWithTrace(plan, messages, selectedModelIds, reasoningMode, fileContext).response();
    }

    public ExecutorExecution executeWithTrace(
        PlannerOutput plan,
        List<ChatMessage> messages,
        List<String> selectedModelIds,
        ReasoningMode reasoningMode,
        FileContextService.FileContext fileContext
    ) {
        List<String> models = modelCatalog.resolvePreferredModels(selectedModelIds, fileContext.hasFile());
        boolean codeFirstResponse = shouldReturnCodeFirstByIntent(plan, messages);
        boolean avoidCodeResponse = shouldAvoidCodeResponseByIntent(plan, messages);
        String prompt = buildExecutorPrompt(plan, messages, reasoningMode, fileContext, codeFirstResponse, avoidCodeResponse);

        OpenRouterClient.ModelCallResult executionResult = client.callModelWithFallbackResult(models, prompt);
        String response = executionResult.content();
        String usedModel = executionResult.modelId();

        if (avoidCodeResponse && looksLikeCodeHeavy(response)) {
            String rewritePrompt = """
                Reescreva a resposta abaixo para formato conceitual e didatico em portugues do Brasil.
                Regras obrigatorias:
                - Nao use blocos de codigo, pseudo-codigo, comandos ou scripts.
                - Preserve o tema e os fatos corretos da resposta original.
                - Estruture com titulos curtos e listas objetivas.
                - Seja objetivo.
                - Retorne somente markdown textual.

                Resposta original:
                %s
                """.formatted(response);
            OpenRouterClient.ModelCallResult rewriteResult = client.callModelWithFallbackResult(models, rewritePrompt);
            response = rewriteResult.content();
            usedModel = rewriteResult.modelId();
        }

        if (codeFirstResponse && !containsCodeBlock(response)) {
            String rewritePrompt = """
                Reescreva a resposta abaixo para entregar uma solucao com codigo.
                Regras obrigatorias:
                - Inclua bloco de codigo markdown com linguagem explicita.
                - Entregue codigo funcional, nao apenas explicacao.
                - Depois do codigo, inclua secao "Como usar" com passos numerados curtos.
                - Mantenha a resposta em portugues do Brasil (exceto codigo).

                Resposta original:
                %s
                """.formatted(response);
            OpenRouterClient.ModelCallResult rewriteResult = client.callModelWithFallbackResult(models, rewritePrompt);
            response = rewriteResult.content();
            usedModel = rewriteResult.modelId();
        }

        return new ExecutorExecution(response, usedModel);
    }

    public ExecutorExecution executeStreamWithTrace(
        PlannerOutput plan,
        List<ChatMessage> messages,
        List<String> selectedModelIds,
        ReasoningMode reasoningMode,
        FileContextService.FileContext fileContext,
        java.util.function.Consumer<String> onDelta
    ) {
        List<String> models = modelCatalog.resolvePreferredModels(selectedModelIds, fileContext.hasFile());
        boolean codeFirstResponse = shouldReturnCodeFirstByIntent(plan, messages);
        boolean avoidCodeResponse = shouldAvoidCodeResponseByIntent(plan, messages);
        String prompt = buildExecutorPrompt(plan, messages, reasoningMode, fileContext, codeFirstResponse, avoidCodeResponse);
        OpenRouterClient.ModelCallResult streamResult = client.callModelWithFallbackStreamResult(models, prompt, onDelta);
        return new ExecutorExecution(streamResult.content(), streamResult.modelId());
    }

    public String suggestConversationTitle(
        List<ChatMessage> messages,
        List<String> selectedModelIds,
        boolean hasFile
    ) {
        List<String> models = modelCatalog.resolvePreferredModels(selectedModelIds, hasFile);

        String prompt = """
            Gere um titulo curto para conversa em portugues do Brasil.
            Regras obrigatorias:
            - Entre 4 e 5 palavras, sempre que possivel.
            - Comecar com letra maiuscula.
            - Sem aspas, sem emoji, sem pontuacao no final.
            - Deve soar como resumo da intencao do usuario.
            - Nao copie literalmente o inicio da mensagem do usuario.
            - Evite verbos de comando como "diga", "explique" e "mostre".
            - Retorne somente o titulo, em uma linha.

            Conversa recente:
            %s
            """.formatted(transcript(messages, 6));

        String raw = client.callModelWithFallback(models, prompt);
        return sanitizeSuggestedConversationTitle(raw, messages);
    }

    public String suggestFeedbackTheme(
        String userPrompt,
        String assistantResponse,
        List<String> selectedModelIds,
        boolean hasFile
    ) {
        List<String> models = modelCatalog.resolvePreferredModels(selectedModelIds, hasFile);
        String prompt = """
            Gere um tema curto em portugues do Brasil para classificar uma resposta de chat.
            Regras:
            - Maximo 4 palavras.
            - Comecar com letra maiuscula.
            - Sem aspas, sem emoji e sem pontuacao final.
            - Retorne somente o tema.

            Pergunta do usuario:
            %s

            Resposta do assistente:
            %s
            """.formatted(userPrompt == null ? "" : userPrompt, assistantResponse == null ? "" : assistantResponse);

        String raw = client.callModelWithFallback(models, prompt);
        String normalized = sanitizeSuggestedConversationTitle(raw, List.of(
            new ChatMessage("user", userPrompt == null ? "" : userPrompt, null, null)
        ));
        return normalized.isBlank() ? "Tema geral" : normalized;
    }

    private String transcript(List<ChatMessage> messages, int maxMessages) {
        int start = Math.max(messages.size() - maxMessages, 0);
        StringBuilder builder = new StringBuilder();
        for (int index = start; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            builder.append(message.role()).append(": ").append(message.content()).append("\n");
            if (message.attachmentSummary() != null && !message.attachmentSummary().isBlank()) {
                builder.append("attachment: ").append(message.attachmentSummary()).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private String buildExecutorPrompt(
        PlannerOutput plan,
        List<ChatMessage> messages,
        ReasoningMode reasoningMode,
        FileContextService.FileContext fileContext,
        boolean codeFirstResponse,
        boolean avoidCodeResponse
    ) {
        String latestUserMessage = latestUserMessage(messages);
        boolean comparisonRequest = isComparisonRequest(normalizeForMatching(latestUserMessage));
        boolean hasAssistantHistory = hasPreviousAssistantMessage(messages);
        String steps = plan.execution_plan() == null
            ? ""
            : plan.execution_plan().stream().map(step -> "- " + step).collect(Collectors.joining("\n"));

        String prompt = """
            Voce e o agente executor visivel ao usuario.
            Responda sempre em portugues do Brasil, exceto pelo codigo.

            Regras de produto:
            - Seja preciso, seguro e economico.
            - Nunca mencione agentes internos, roteamento, planner, reviewer ou nomes de modelos.
            - So use codigo quando o usuario pedir explicitamente implementacao, script, funcao, classe, query, endpoint ou exemplo executavel.
            - Se nao houver pedido explicito de codigo, responda em texto estruturado e nao inclua codigo.
            - Em perguntas conceituais, cientificas, educacionais ou de conhecimento geral, priorize explicacao objetiva em linguagem natural.
            - Se houver codigo, entregue blocos markdown com linguagem correta e explique so o necessario.
            - Quando codigo for o entregavel principal, nao responda apenas avaliando ou descrevendo; mostre uma solucao concreta.
            - Use tom humano e colaborativo.
            - Se esta for a primeira resposta da conversa, voce pode abrir com frase curta como "Segue uma opcao", "Aqui esta" ou "Vamos por partes".
            - Se ja houver resposta anterior do assistente, nao reinicie com saudacao ("Oi, tudo bem?"); continue direto no contexto.
            - Mantenha tom conversacional e gentil, evitando respostas secas ou roboticas.
            - Quando entregar codigo, inclua orientacao de uso logo depois do codigo (como executar, como chamar ou como testar).
            - Organize respostas em markdown com secoes claras.
            - Use subtitulos em markdown (##) entre explicacoes quando houver mais de um bloco de conteudo.
            - Para instrucoes, use lista numerada; para pontos curtos, use bullets.
            - Nao duplique numeracao: se comecar uma lista 1, 2, 3, mantenha a sequencia sem reiniciar desnecessariamente.
            - Markdown deve ser valido: coloque espaco apos # em titulos, mantenha cada item em sua propria linha e separe blocos com linha em branco.
            - So use tabela markdown quando realmente for comparacao tabular; fora disso, prefira listas.
            - Nunca gere heading vazio como "#" ou "##" isolado.
            - Nunca misture heading e tabela na mesma linha.
            - Nunca deixe "Resumo rapido" dentro de tabela; o resumo deve vir em secao separada apos a tabela.
            - Nao misture lista com tabela na mesma secao; conclua a tabela e depois abra nova secao.
            - Evite colar titulo, lista ou separador na mesma linha do paragrafo anterior; use quebra de linha.
            - Destaque termos importantes com **negrito** e evite blocos longos sem estrutura.
            - Se a solicitacao estiver ambigua, escolha a interpretacao mais util com base no contexto disponivel.
            - Preserve continuidade com a conversa anterior.

            Modo de resposta:
            - FAST -> priorize objetividade, menos floreio e solucao direta.
            - THOUGHTFUL -> seja mais cuidadoso, detalhado e criterioso quando isso agregar valor real.

            Contexto interno:
            Intent: %s
            Tipo: %s
            Complexidade: %s
            Executor: %s
            Estilo: %s
            Codigo obrigatorio nesta rodada: %s
            Codigo proibido nesta rodada: %s
            Pedido de comparacao/tabular: %s
            Conversa em continuidade (ja houve resposta do assistente): %s
            Passos:
            %s

            Conversa recente:
            %s
            """.formatted(
            plan.intent(),
            plan.task_type(),
            plan.complexity(),
            plan.executor(),
            plan.response_style(),
            codeFirstResponse,
            avoidCodeResponse,
            comparisonRequest,
            hasAssistantHistory,
            steps,
            transcript(messages, reasoningMode.isThoughtful() ? 12 : 8)
        );

        prompt += """

            Contrato de saida (obrigatorio):
            - Se nao houver resposta anterior do assistente, uma abertura humana curta e natural (1 frase) e opcional.
            - Se ja houver resposta anterior do assistente, continue direto no tema sem novo cumprimento.
            - Depois entregue conteudo principal em blocos curtos e escaneaveis.
            - Se for comparacao/tabular (%s):
              - Use UMA tabela markdown valida com cabecalho claro.
              - Em comparacao de multiplos produtos, mantenha o mesmo numero de colunas em todas as linhas.
              - Nao use celulas de separador textual ("---" como conteudo de celula).
              - Nunca use "|" dentro do conteudo da celula. Para separar itens na mesma celula, use virgula, ponto e virgula ou <br>.
              - Nunca misture bullet com tabela. Nao gere linhas como "- |" ou "• |".
              - Se houver resumo, finalize a tabela e somente depois adicione "## Resumo rapido" em paragrafo curto fora da tabela.
              - Nao adicione "Resumo rapido" quando a resposta ja estiver curta e objetiva.
            - Se nao for comparacao/tabular:
              - Prefira listas e subtitulos curtos; evite tabela.
            - Nunca repita numeracao de forma quebrada (ex.: "1." reiniciando sem necessidade).
            - Cada item numerado deve ficar em sua propria linha (1 linha por item).
            - Termine com 1 linha de proximo passo opcional quando fizer sentido.
            """.formatted(comparisonRequest);

        if (codeFirstResponse) {
            prompt += """

                Instrucao obrigatoria para esta resposta:
                - Entregue uma solucao funcional em codigo nesta primeira resposta.
                - Abra com um bloco de codigo markdown com a linguagem correta ou traga o bloco logo apos uma frase curta.
                - Depois do codigo, inclua uma secao "Como usar" com passos numerados.
                - Depois do "Como usar", inclua uma secao "Pontos-chave" em bullets curtos.
                - Nao substitua o codigo por uma revisao textual do que seria feito.
                """;
        }

        if (avoidCodeResponse) {
            prompt += """

                Instrucao obrigatoria para esta resposta:
                - Esta solicitacao pede conhecimento conceitual.
                - Responda em texto estruturado e intuitivo.
                - Nao retorne blocos de codigo, pseudo-codigo, scripts ou comandos.
                """;
        }

        if (fileContext.hasFile()) {
            prompt += """

                Arquivo anexado:
                Nome: %s
                Tipo: %s
                Tamanho: %d bytes
                Conteudo extraido:
                %s
                """.formatted(
                fileContext.fileName(),
                fileContext.contentType(),
                fileContext.size(),
                fileContext.hasExtractedText()
                    ? fileContext.extractedText()
                    : "Conteudo nao extraido automaticamente. Considere o arquivo como contexto parcial e peca outro formato somente se realmente necessario."
            );
        }

        return prompt;
    }

    private boolean shouldReturnCodeFirstByIntent(PlannerOutput plan, List<ChatMessage> messages) {
        String normalized = normalizeForMatching(latestUserMessage(messages));

        if (isExplicitNoCodeRequest(normalized)) {
            return false;
        }

        if (isExplicitConceptualRequest(normalized) && !isExplicitCodeRequest(normalized)) {
            return false;
        }

        if (isExplicitCodeRequest(normalized)) {
            return true;
        }

        return "CODE_EXECUTOR".equals(plan.executor()) || "code".equalsIgnoreCase(plan.task_type());
    }

    private boolean shouldAvoidCodeResponseByIntent(PlannerOutput plan, List<ChatMessage> messages) {
        String normalized = normalizeForMatching(latestUserMessage(messages));

        if (isExplicitNoCodeRequest(normalized)) {
            return true;
        }

        if (isExplicitCodeRequest(normalized)) {
            return false;
        }

        boolean plannerSaysConcept = "explanation".equalsIgnoreCase(plan.task_type())
            || "conversation".equalsIgnoreCase(plan.task_type())
            || "GENERAL_EXECUTOR".equalsIgnoreCase(plan.executor());

        return plannerSaysConcept || isExplicitConceptualRequest(normalized);
    }

    private boolean isExplicitCodeRequest(String normalizedMessage) {
        if (normalizedMessage.isBlank()) {
            return false;
        }

        if (containsAny(normalizedMessage, List.of(
            "mostre o codigo",
            "me mostre o codigo",
            "gere o codigo",
            "escreva o codigo",
            "me de o codigo",
            "so o codigo",
            "somente codigo"
        ))) {
            return true;
        }

        if (containsAny(normalizedMessage, List.of(
            "implemente",
            "crie uma funcao",
            "crie um script",
            "crie uma classe",
            "faca uma funcao",
            "programa",
            "algoritmo",
            "query",
            "sql",
            "debug",
            "corrija",
            "refatore",
            "snippet"
        ))) {
            return true;
        }

        if (containsAny(normalizedMessage, List.of(
            "java",
            "python",
            "javascript",
            "typescript",
            "golang",
            "php",
            "ruby",
            "kotlin",
            "swift",
            "rust"
        ))) {
            return containsAny(normalizedMessage, List.of(
                "funcao",
                "classe",
                "script",
                "codigo",
                "exemplo",
                "snippet",
                "implemente"
            ));
        }

        return false;
    }

    private boolean isExplicitConceptualRequest(String normalizedMessage) {
        if (normalizedMessage.isBlank()) {
            return false;
        }

        return containsAny(normalizedMessage, List.of(
            "o que e",
            "o que sao",
            "oq e",
            "oq sao",
            "explique",
            "resuma",
            "defina",
            "conceito",
            "significado",
            "diferenca",
            "como funciona",
            "quais sao",
            "porque",
            "causas",
            "sintomas",
            "tratamento",
            "resumo rapido",
            "rapidamente"
        ));
    }

    private boolean isExplicitNoCodeRequest(String normalizedMessage) {
        if (normalizedMessage.isBlank()) {
            return false;
        }

        return containsAny(normalizedMessage, List.of(
            "sem codigo",
            "nao quero codigo",
            "nao use codigo",
            "apenas explicacao",
            "so explicacao",
            "somente explicacao"
        ));
    }

    private boolean isComparisonRequest(String normalizedMessage) {
        if (normalizedMessage.isBlank()) {
            return false;
        }

        return containsAny(normalizedMessage, List.of(
            "compar",
            "diferenca",
            "versus",
            "vs",
            "tabela",
            "quadro comparativo"
        ));
    }

    private boolean hasPreviousAssistantMessage(List<ChatMessage> messages) {
        return messages.stream()
            .anyMatch(message -> "assistant".equalsIgnoreCase(message.role()));
    }

    private boolean containsAny(String input, List<String> terms) {
        for (String term : terms) {
            if (input.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String latestUserMessage(List<ChatMessage> messages) {
        return messages.stream()
            .filter(message -> "user".equalsIgnoreCase(message.role()))
            .reduce((first, second) -> second)
            .map(ChatMessage::content)
            .orElse("");
    }

    private String normalizeForMatching(String value) {
        return simplify(value)
            .replaceAll("[^\\p{L}\\p{N}\\s]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private boolean containsCodeBlock(String response) {
        return response != null && response.contains("```");
    }

    private boolean looksLikeCodeHeavy(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        if (containsCodeBlock(response)) {
            return true;
        }

        String normalized = normalizeForMatching(response);
        if (normalized.isBlank()) {
            return false;
        }

        return containsAny(normalized, List.of(
            "public static",
            "class ",
            "def ",
            "function ",
            "const ",
            "let ",
            "var ",
            "import ",
            "from ",
            "return ",
            "print ",
            "system out",
            "console log"
        ));
    }

    String sanitizeSuggestedConversationTitle(String raw, List<ChatMessage> messages) {
        String fallback = keywordTitleFromMessages(messages);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        String cleaned = raw
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .replaceAll("^[\"'`]+|[\"'`]+$", "")
            .replaceAll("[\\p{Punct}\\s]+$", "")
            .trim();
        cleaned = stripTitleCommandAndFormatNoise(cleaned);

        if (cleaned.isBlank()) {
            return fallback;
        }

        List<String> candidateWords = extractMeaningfulTitleWords(cleaned, 5);
        if (candidateWords.isEmpty()) {
            return fallback;
        }

        String candidateTitle = candidateWords.stream()
            .limit(5)
            .map(this::normalizeTitleWord)
            .collect(Collectors.joining(" "))
            .trim();
        if (candidateTitle.isBlank()) {
            return fallback;
        }

        String titled = Character.toUpperCase(candidateTitle.charAt(0)) + candidateTitle.substring(1);
        if (looksLikeInputRetalho(titled, messages) || isForbiddenTitleShape(titled) || isWeakOrGenericTitle(titled)) {
            return fallback;
        }
        return titled;
    }

    private boolean looksLikeInputRetalho(String candidate, List<ChatMessage> messages) {
        String latestUserMessage = latestUserMessage(messages).trim();
        if (latestUserMessage.isBlank()) {
            return false;
        }

        String normalizedCandidate = simplify(candidate);
        String normalizedUser = simplify(latestUserMessage);
        if (normalizedCandidate.isBlank() || normalizedUser.isBlank()) {
            return false;
        }

        long candidateWordCount = normalizedCandidate.split("\\s+").length;
        long userWordCount = normalizedUser.split("\\s+").length;

        return userWordCount > 5
            && normalizedUser.startsWith(normalizedCandidate)
            && candidateWordCount >= 3;
    }

    String keywordTitleFromMessages(List<ChatMessage> messages) {
        String latestUserMessage = latestUserMessage(messages).trim();
        String latestAssistantMessage = messages.stream()
            .filter(message -> "assistant".equalsIgnoreCase(message.role()))
            .reduce((first, second) -> second)
            .map(ChatMessage::content)
            .orElse("")
            .trim();

        String comparisonTitle = comparisonTitleFromUserMessage(latestUserMessage);
        if (!comparisonTitle.isBlank()) {
            return comparisonTitle;
        }

        List<String> selected = new ArrayList<>(extractMeaningfulTitleWords(
            stripTitleCommandAndFormatNoise(latestUserMessage),
            5
        ));
        if (selected.isEmpty()) {
            selected.addAll(extractMeaningfulTitleWords(
                stripTitleCommandAndFormatNoise(latestAssistantMessage),
                5
            ));
        }
        if (selected.isEmpty()) {
            return "Nova conversa";
        }

        String title = selected.stream()
            .limit(5)
            .map(this::normalizeTitleWord)
            .collect(Collectors.joining(" "))
            .trim();
        if (title.isBlank()) {
            return "Nova conversa";
        }

        String titled = Character.toUpperCase(title.charAt(0)) + title.substring(1);
        if (!looksLikeInputRetalho(titled, messages) && !isForbiddenTitleShape(titled) && !isWeakOrGenericTitle(titled)) {
            return titled;
        }

        if (!latestAssistantMessage.isBlank()) {
            List<String> assistantWords = extractMeaningfulTitleWords(
                stripTitleCommandAndFormatNoise(latestAssistantMessage),
                5
            );
            if (!assistantWords.isEmpty()) {
                    String fromAssistant = assistantWords.stream()
                        .limit(5)
                        .map(this::normalizeTitleWord)
                        .collect(Collectors.joining(" "))
                        .trim();
                if (!fromAssistant.isBlank()) {
                    String assistantTitle =
                        Character.toUpperCase(fromAssistant.charAt(0)) + fromAssistant.substring(1);
                    if (!isForbiddenTitleShape(assistantTitle) && !isWeakOrGenericTitle(assistantTitle)) {
                        return assistantTitle;
                    }
                }
            }
        }

        return "Nova conversa";
    }

    private String comparisonTitleFromUserMessage(String latestUserMessage) {
        String normalizedUser = normalizeForMatching(latestUserMessage);
        if (!isComparisonRequest(normalizedUser)) {
            return "";
        }

        String cleaned = stripTitleCommandAndFormatNoise(latestUserMessage);
        if (cleaned.isBlank()) {
            return "";
        }

        List<String> entities = extractMeaningfulTitleWords(cleaned, 6).stream()
            .map(this::normalizeTitleWord)
            .filter(word -> !word.isBlank())
            .distinct()
            .limit(3)
            .toList();

        if (entities.size() < 2) {
            return "";
        }

        return String.join(" vs ", entities);
    }

    private String normalizeTitleWord(String word) {
        if (word == null || word.isBlank()) {
            return "";
        }
        String clean = word.trim();
        String key = simplify(clean).replace(" ", "");
        if ("api".equals(key)) {
            return "API";
        }
        if ("sql".equals(key)) {
            return "SQL";
        }
        if ("mhc".equals(key)) {
            return "MHC";
        }
        if ("ia".equals(key)) {
            return "IA";
        }
        if ("ui".equals(key)) {
            return "UI";
        }
        if ("ux".equals(key)) {
            return "UX";
        }
        if ("docker".equals(key)) {
            return "Docker";
        }
        if ("supabase".equals(key)) {
            return "Supabase";
        }
        if ("neon".equals(key)) {
            return "Neon";
        }
        if ("aiven".equals(key)) {
            return "Aiven";
        }
        if ("el".equals(key)) {
            return "El";
        }
        if ("nino".equals(key)) {
            return "Niño";
        }
        if ("fenomeno".equals(key)) {
            return "Fenômeno";
        }
        if ("comparacao".equals(key)) {
            return "Comparação";
        }
        if ("definicao".equals(key)) {
            return "Definição";
        }
        return clean.substring(0, 1).toUpperCase(Locale.ROOT) + clean.substring(1).toLowerCase(Locale.ROOT);
    }

    private boolean isForbiddenTitleShape(String title) {
        String simplified = simplify(title);
        if (simplified.isBlank()) {
            return true;
        }

        return simplified.startsWith("me fale ")
            || simplified.startsWith("explique ")
            || simplified.startsWith("compare ")
            || simplified.startsWith("diga ")
            || simplified.contains(" em topicos")
            || simplified.contains(" em uma tabela")
            || simplified.contains(" tabela simples")
            || simplified.contains(" passos numerados");
    }

    private boolean isWeakOrGenericTitle(String title) {
        String simplified = simplify(title);
        if (simplified.isBlank()) {
            return true;
        }

        String[] words = simplified.split("\\s+");
        if (words.length == 1) {
            return Set.of("fenomeno", "assunto", "tema", "resumo", "topico", "topicos", "explicacao")
                .contains(words[0]);
        }
        return false;
    }

    private String stripTitleCommandAndFormatNoise(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        return source
            .replaceAll("(?i)^(oi|ola|olá|bom dia|boa tarde|boa noite)\\b[\\s,!:.-]*", "")
            .replaceAll(
                "(?i)^(me\\s+)?(fale|explique|compare|diga|mostre|me\\s+explique|me\\s+fale)\\b[\\s,!:.-]*(sobre\\s+)?",
                ""
            )
            .replaceAll("(?i)\\bem\\s+t[oó]picos?\\b", " ")
            .replaceAll("(?i)\\bem\\s+\\d+\\s+passos?(\\s+numerados?)?\\b", " ")
            .replaceAll("(?i)\\bem\\s+uma\\s+tabela\\s+(simples|resumida)\\b", " ")
            .replaceAll("(?i)\\bcom\\s+definicao\\b", " ")
            .replaceAll("(?i)\\bdefini[cç][aã]o\\b", " ")
            .replaceAll("(?i)\\bvantagens\\b", " ")
            .replaceAll("(?i)\\bcuidados\\b", " ")
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private List<String> extractMeaningfulTitleWords(String source, int limit) {
        if (source == null || source.isBlank()) {
            return List.of();
        }

        String cleanedSource = source
            .replaceAll("```[\\s\\S]*?```", " ")
            .replaceAll("[#>*_`|\\[\\]{}()]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (cleanedSource.isBlank()) {
            return List.of();
        }

        Set<String> stopWords = Set.of(
            "a", "o", "as", "os", "de", "da", "do", "das", "dos", "e",
            "em", "para", "por", "com", "sem", "um", "uma", "uns", "umas",
            "que", "como", "qual", "quais", "sobre", "me", "mim", "favor",
            "pode", "ser", "sao", "rapido", "rapida", "rapidamente",
            "explique", "fale", "diga", "mostre", "segue", "aqui", "esta",
            "compare", "opcao", "parte", "partes", "geral", "resumo", "topicos",
            "tabela", "simples", "passos", "numerados", "definicao", "vantagens", "cuidados",
            "vs", "versus"
        );
        Set<String> shortAllowedWords = Set.of("el");

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String rawWord : cleanedSource.split("\\s+")) {
            String cleanWord = rawWord.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
            if (cleanWord.isBlank()) {
                continue;
            }

            String normalizedWord = simplify(cleanWord);
            if (normalizedWord.length() <= 2 && !shortAllowedWords.contains(normalizedWord)) {
                continue;
            }
            if (stopWords.contains(normalizedWord)) {
                continue;
            }

            selected.add(cleanWord);
            if (selected.size() >= limit) {
                break;
            }
        }

        return List.copyOf(selected);
    }

    private String simplify(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}\\s]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
