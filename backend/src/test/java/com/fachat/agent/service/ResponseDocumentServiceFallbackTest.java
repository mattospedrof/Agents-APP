package com.fachat.agent.service;

import com.fachat.agent.dto.AssistantBlock;
import com.fachat.agent.dto.AssistantDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseDocumentServiceFallbackTest {
    private final ResponseDocumentService service = new ResponseDocumentService();

    @Test
    void separatesGluedNumbersAndKeepsUnicode() {
        String text = service.sanitizeInlineText(
            "de0,1\u00b0C a0,2\u00b0C mais de20% entre9 e12 meses at\u00e918 meses cada2 a7 anos El Ni\u00f1o Fen\u00f4meno Compara\u00e7\u00e3o"
        );

        assertThat(text).isEqualTo(
            "de 0,1\u00b0C a 0,2\u00b0C mais de 20% entre 9 e 12 meses at\u00e9 18 meses cada 2 a 7 anos El Ni\u00f1o Fen\u00f4meno Compara\u00e7\u00e3o"
        );
    }

    @Test
    void convertsInvalidHyphenTableIntoHeadingAndSafeList() {
        AssistantDocument document = service.toDocument("""
            Principais impactos globais - Regi\u00e3o - Efeito t\u00edpico - Consequ\u00eancia
            Am\u00e9rica do Sul - Secas intensas - Redu\u00e7\u00e3o da produ\u00e7\u00e3o agr\u00edcola
            Am\u00e9rica do Norte - Inunda\u00e7\u00f5es e chuvas fortes - Aumento de deslizamentos
            """);

        assertThat(document.blocks()).noneMatch(block -> "table".equals(block.type()));

        AssistantBlock heading = document.blocks().stream()
            .filter(block -> "heading".equals(block.type()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected heading block but got: " + document.blocks()));
        AssistantBlock list = document.blocks().stream()
            .filter(block -> "bulletList".equals(block.type()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected bullet list block but got: " + document.blocks()));

        assertThat(heading.text()).isEqualTo("Principais impactos globais");
        assertThat(list.items()).containsExactly(
            "Am\u00e9rica do Sul: Secas intensas; Redu\u00e7\u00e3o da produ\u00e7\u00e3o agr\u00edcola",
            "Am\u00e9rica do Norte: Inunda\u00e7\u00f5es e chuvas fortes; Aumento de deslizamentos"
        );
    }

    @Test
    void convertsInvalidPipeTableIntoHeadingAndSafeList() {
        AssistantDocument document = service.toDocument("""
            | Produto | Onde comprar | P\u00fablico-alvo |
            | --- | --- |
            | Vestu\u00e1rio fitness | Distribuidores | Jovens | Instagram |
            """);

        assertThat(document.blocks()).noneMatch(block -> "table".equals(block.type()));
        assertThat(document.blocks())
            .anyMatch(block -> "heading".equals(block.type()) && "Dados da tabela".equals(block.text()));
        assertThat(document.blocks())
            .anyMatch(block -> "bulletList".equals(block.type())
                && block.items().contains("Vestu\u00e1rio fitness: Distribuidores; Jovens; Instagram"));
    }

    @Test
    void keepsTrailingSummaryOutsideStructuredTable() {
        AssistantDocument document = service.toDocument("""
            | Caracter\u00edstica | Supabase | Neon | Aiven |
            | --- | --- | --- | --- |
            | Tipo de servi\u00e7o | PostgreSQL gerenciado | PostgreSQL serverless | Plataforma gerenciada |
            Resumo r\u00e1pido: escolha depende do controle operacional e do ecossistema desejado.
            """);

        assertThat(document.blocks()).anyMatch(block -> "table".equals(block.type()));
        assertThat(document.blocks())
            .anyMatch(block -> "paragraph".equals(block.type()) && block.text().startsWith("Resumo r\u00e1pido:"));
    }

    @Test
    void rejectsPipeTableWithHeadingInsideHeaderCell() {
        AssistantDocument document = service.toDocument("""
            | ## Diferen\u00e7as entre El Ni\u00f1o e La Ni\u00f1a | Aspecto | El Ni\u00f1o | La Ni\u00f1a |
            | --- | --- | --- | --- |
            | Padr\u00e3o oce\u00e2nico | Aquecimento an\u00f4malo | Resfriamento an\u00f4malo |
            """);

        assertThat(document.blocks()).noneMatch(block -> "table".equals(block.type()));
        assertThat(document.blocks())
            .anyMatch(block -> "paragraph".equals(block.type()) && block.text().contains("Diferen\u00e7as entre El Ni\u00f1o e La Ni\u00f1a"));
    }
}
