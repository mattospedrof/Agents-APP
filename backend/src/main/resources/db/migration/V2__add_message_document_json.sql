ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS document_json TEXT;
