CREATE TABLE IF NOT EXISTS uploaded_files (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(80) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    safe_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(120),
    size_bytes BIGINT NOT NULL,
    extracted_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_uploaded_files_conversation_status
    ON uploaded_files (user_id, conversation_id, status, created_at DESC);

ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS file_context_id VARCHAR(80);
