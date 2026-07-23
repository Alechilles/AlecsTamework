package com.alechilles.alecstamework.persistence.recovery;

/** Evidence-driven next step for one leased nonterminal replacement operation. */
public enum OperationRecoveryAction {
    RESUME_LIVE_APPLY,
    VERIFY_LIVE_APPLY,
    PUBLISH_DURABLE,
    VERIFY_COMPENSATION,
    RETRY_FROM_EVIDENCE,
    MANUAL_REVIEW
}
