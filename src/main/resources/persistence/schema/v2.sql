CREATE INDEX idx_projection_outbox_type_sequence
    ON projection_outbox(event_type, event_sequence);
