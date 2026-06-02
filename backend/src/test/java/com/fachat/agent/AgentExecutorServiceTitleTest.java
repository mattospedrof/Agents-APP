package com.fachat.agent;

import com.fachat.agent.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutorServiceTitleTest {

    private final AgentExecutorService service = new AgentExecutorService(null, null);

    @Test
    void shouldNotKeepCommandPrefixInSuggestedTitle() {
        String prompt = "me fale sobre o fen\u00f4meno el nino em t\u00f3picos";
        List<ChatMessage> messages = List.of(
            new ChatMessage("user", prompt, null, null)
        );

        String title = service.sanitizeSuggestedConversationTitle(
            "Me fale sobre o fen\u00f4meno el nino em t\u00f3picos",
            messages
        );

        String lowered = title.toLowerCase();
        assertFalse(lowered.startsWith("me fale"));
        assertFalse(lowered.contains("em t\u00f3picos"));
        assertFalse(lowered.contains("em topicos"));
    }

    @Test
    void shouldKeepShortValidTitleWithoutArtificialExpansion() {
        List<ChatMessage> messages = List.of(
            new ChatMessage("user", "explique docker em 5 passos numerados", null, null)
        );

        String title = service.sanitizeSuggestedConversationTitle("Docker", messages);
        assertEquals("Docker", title);
    }

    @Test
    void shouldGenerateKeywordTitleFromUserMessageForSupabase() {
        List<ChatMessage> messages = List.of(
            new ChatMessage("user", "explique supabase em t\u00f3picos com defini\u00e7\u00e3o, vantagens e cuidados", null, null)
        );

        String title = service.keywordTitleFromMessages(messages);
        assertEquals("Supabase", title);
    }

    @Test
    void shouldGenerateComparisonTitleForSupabaseNeonAiven() {
        List<ChatMessage> messages = List.of(
            new ChatMessage("user", "compare supabase, neon e aiven em uma tabela simples", null, null)
        );

        String title = service.keywordTitleFromMessages(messages);
        assertEquals("Supabase vs Neon vs Aiven", title);
    }

    @Test
    void shouldCreateExpectedTitleForElNinoPrompt() {
        List<ChatMessage> messages = List.of(
            new ChatMessage("user", "me fale sobre o fen\u00f4meno el nino em t\u00f3picos", null, null)
        );

        String title = service.keywordTitleFromMessages(messages);
        assertTrue(
            title.equals("Fenômeno El Niño") || title.equals("El Niño"),
            () -> "Titulo inesperado: " + title
        );
    }

    @Test
    void shouldCreateExpectedTitleForDockerPrompt() {
        List<ChatMessage> messages = List.of(
            new ChatMessage("user", "explique docker em 5 passos numerados", null, null)
        );

        String title = service.keywordTitleFromMessages(messages);
        assertEquals("Docker", title);
    }

}
