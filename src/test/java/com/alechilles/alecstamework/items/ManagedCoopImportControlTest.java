package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopImportControl.AuthorizationStatus;
import com.alechilles.alecstamework.items.ManagedCoopImportControl.ConfirmationStatus;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportInspectionService.ImportInspection;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportInspectionService.InspectionStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for exact, process-local legacy resident import confirmation. */
class ManagedCoopImportControlTest {
    private static final String FIRST = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @Test
    void confirmationRequiresLatestExactAuthorityAndFingerprint() {
        ManagedCoopImportControl control = new ManagedCoopImportControl();
        control.observe(inspection(AUTHORITY, FIRST));

        assertEquals(ConfirmationStatus.NO_INSPECTION, control.confirm(
                new ManagedCoopAuthorityKey("world", 1, 2, 4), FIRST, "operator").status());
        assertEquals(ConfirmationStatus.FINGERPRINT_MISMATCH,
                control.confirm(AUTHORITY, SECOND, "operator").status());
        assertEquals(ConfirmationStatus.CONFIRMED,
                control.confirm(AUTHORITY, FIRST, "operator").status());
        assertEquals(AuthorizationStatus.APPROVED, control.authorize(AUTHORITY, FIRST));
        assertEquals("operator", control.approval(AUTHORITY).orElseThrow().actor());
    }

    @Test
    void changedInspectionRevokesApprovalBeforeAnotherImportStep() {
        ManagedCoopImportControl control = new ManagedCoopImportControl();
        control.observe(inspection(AUTHORITY, FIRST));
        assertTrue(control.confirm(AUTHORITY, FIRST, "operator").confirmed());

        // Protects the duplication bug pattern: changed source evidence cannot reuse old consent.
        control.observe(inspection(AUTHORITY, SECOND));

        assertFalse(control.hasApproval(AUTHORITY));
        assertEquals(AuthorizationStatus.MISSING, control.authorize(AUTHORITY, SECOND));
        assertEquals(SECOND,
                control.latestInspection(AUTHORITY).orElseThrow().auditFingerprint());
    }

    @Test
    void cancelAndShutdownClearFutureProgressWithoutInventingRollback() {
        ManagedCoopImportControl control = new ManagedCoopImportControl();
        control.observe(inspection(AUTHORITY, FIRST));
        assertTrue(control.confirm(AUTHORITY, FIRST, "operator").confirmed());

        assertTrue(control.cancel(AUTHORITY));
        assertFalse(control.hasApproval(AUTHORITY));
        assertTrue(control.latestInspection(AUTHORITY).isPresent());

        assertTrue(control.confirm(AUTHORITY, FIRST, "operator").confirmed());
        control.observe(clearInspection());
        assertFalse(control.hasApproval(AUTHORITY));
        assertEquals(InspectionStatus.CLEAR,
                control.latestInspection(AUTHORITY).orElseThrow().status());
        assertEquals(ConfirmationStatus.NOT_APPROVABLE,
                control.confirm(AUTHORITY, FIRST, "operator").status());

        control.clearAll();
        assertFalse(control.hasApproval(AUTHORITY));
        assertTrue(control.latestInspection(AUTHORITY).isEmpty());
    }

    private ImportInspection inspection(ManagedCoopAuthorityKey authority, String fingerprint) {
        return new ImportInspection(
                authority,
                "coop_chicken",
                InspectionStatus.APPROVAL_REQUIRED,
                null,
                fingerprint,
                "session-" + fingerprint,
                List.of(),
                true,
                "explicit_import_confirmation_required"
        );
    }

    private ImportInspection clearInspection() {
        return new ImportInspection(
                AUTHORITY,
                "coop_chicken",
                InspectionStatus.CLEAR,
                null,
                null,
                null,
                List.of(),
                false,
                "managed_coop_import_clear"
        );
    }
}
