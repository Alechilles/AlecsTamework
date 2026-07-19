package com.alechilles.alecstamework.persistence.recovery;

/** Republishes one canonical runtime index after storage evidence has passed validation. */
@FunctionalInterface
public interface StorageRecoveryIndexPublisher {
    void publish() throws Exception;
}
