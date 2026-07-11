package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for asynchronous legacy-adoption link results. */
class LinkToggleResultTest {

    @Test
    void pendingResultDoesNotReportACompletedLinkMutation() {
        LinkToggleResult result = LinkToggleResult.pending();

        assertTrue(result.pending);
        assertFalse(result.toggled);
        assertFalse(result.linked);
        assertFalse(result.active);
        assertNull(result.updatedItem);
    }
}
