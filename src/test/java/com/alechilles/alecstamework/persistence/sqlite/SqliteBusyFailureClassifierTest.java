package com.alechilles.alecstamework.persistence.sqlite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteBusyFailureClassifierTest {
    @Test
    void recognizesWrappedSqliteLockFailures() {
        IllegalStateException wrapped = new IllegalStateException(
                "outer",
                new RuntimeException("database table is locked")
        );

        assertTrue(SqliteBusyFailureClassifier.isTransient(wrapped));
        assertTrue(SqliteBusyFailureClassifier.isTransient(new RuntimeException("SQLITE_BUSY snapshot")));
    }

    @Test
    void rejectsUnrelatedFailures() {
        assertFalse(SqliteBusyFailureClassifier.isTransient(new RuntimeException("constraint failed")));
    }
}
