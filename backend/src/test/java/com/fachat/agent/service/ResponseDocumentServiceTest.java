package com.fachat.agent.service;

import com.fachat.agent.dto.AssistantBlock;
import com.fachat.agent.dto.AssistantDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseDocumentServiceTest {
    private final ResponseDocumentService service = new ResponseDocumentService();

    @Test
    void convertsOrderedListsIntoStructuredItems() {
        AssistantDocument document = service.toDocument("""
            ## Ideias

            1. Roupas fitness
            2. Acessórios para celular
            3. Cosméticos naturais
            """);

        AssistantBlock list = document.blocks().stream()
            .filter(block -> "orderedList".equals(block.type()))
            .findFirst()
            .orElseThrow();

        assertThat(list.items()).containsExactly(
            "Roupas fitness",
            "Acessórios para celular",
            "Cosméticos naturais"
        );
    }

    @Test
    void convertsValidMarkdownTableIntoSingleTableBlock() {
        AssistantDocument document = service.toDocument("""
            | Característica | Supabase | Neon | Aiven |
            | --- | --- | --- | --- |
            | Tipo | PostgreSQL gerenciado | PostgreSQL serverless | Plataforma gerenciada |
            | Foco | Backend completo | Banco escalável | Dados corporativos |
            """);

        AssistantBlock table = document.blocks().stream()
            .filter(block -> "table".equals(block.type()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected table block but got: " + document.blocks()));

        assertThat(table.columns()).containsExactly("Característica", "Supabase", "Neon", "Aiven");
        assertThat(table.rows()).hasSize(2);
    }

    @Test
    void avoidsBrokenTableWhenRowsHaveInconsistentColumns() {
        AssistantDocument document = service.toDocument("""
            | Produto | Onde comprar | Público-alvo |
            | --- | --- |
            | Vestuário fitness | Distribuidores | Jovens | Instagram |
            """);

        assertThat(document.blocks())
            .noneMatch(block -> "table".equals(block.type()));
    }

    @Test
    void preservesCodeBlocksAsCodeBlocks() {
        AssistantDocument document = service.toDocument("""
            Segue:

            ```java
            class App {}
            ```
            """);

        AssistantBlock code = document.blocks().stream()
            .filter(block -> "codeBlock".equals(block.type()))
            .findFirst()
            .orElseThrow();

        assertThat(code.language()).isEqualTo("java");
        assertThat(code.code()).contains("class App");
    }

    @Test
    void sanitizesInlineMarkdownWithoutRemovingUnicode() {
        String text = service.sanitizeInlineText(
            "**Banco** `docker run` *Auth* opções.Se precisar, avise!Resumo: pronto"
        );

        assertThat(text)
            .isEqualTo("Banco docker run Auth opções. Se precisar, avise! Resumo: pronto");
    }

    @Test
    void doesNotLeakInlineMarkdownInStructuredParagraphs() {
        AssistantDocument document = service.toDocument("""
            **Banco** `docker run` *Auth* opções.Se precisar, avise!Resumo: pronto
            """);

        String renderedText = document.blocks().stream()
            .map(AssistantBlock::text)
            .filter(text -> text != null && !text.isBlank())
            .reduce("", (left, right) -> (left + " " + right).trim());

        assertThat(renderedText).doesNotContain("**", "`", "*Auth");
        assertThat(renderedText).contains("opções. Se", "avise! Resumo:");
    }
}
