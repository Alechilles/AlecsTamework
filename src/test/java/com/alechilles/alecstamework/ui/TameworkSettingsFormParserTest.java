package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.logger.HytaleLogger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsFormParserTest {
    private final TameworkSettingsFormParser parser =
            new TameworkSettingsFormParser(null, HytaleLogger.getLogger());

    @Test
    void unsavedWarningTracksEditsRevertsAndTheLastSuccessfulSave() {
        TameworkSettingsValues saved = TameworkSettingsValues.fromRuntime();
        TameworkSettingsPage.EventPayload form = form(saved);
        assertFalse(parser.hasChanges(form, saved, saved));

        form.needsEnabled = !saved.needsEnabled();
        assertTrue(parser.hasChanges(form, saved, saved));
        TameworkSettingsValues applied = parser.parse(form, saved).values();
        assertFalse(parser.hasChanges(form, saved, applied));

        form.needsEnabled = saved.needsEnabled();
        assertFalse(parser.hasChanges(form, saved, saved));
        form.needsResourceMode = "Accurate".equalsIgnoreCase(saved.needsResourceMode())
                ? "AlwaysFast" : "Accurate";
        assertTrue(parser.hasChanges(form, saved, saved));
    }

    @Test
    void invalidNumericDraftRemainsUnsavedUntilCorrected() {
        TameworkSettingsValues saved = TameworkSettingsValues.fromRuntime();
        TameworkSettingsPage.EventPayload form = form(saved);
        form.populationLimit = "invalid";
        assertTrue(parser.hasChanges(form, saved, saved));
        form.populationLimit = Integer.toString(saved.populationLimitPerPlayerOwnedTotal() + 1);
        assertTrue(parser.hasChanges(form, saved, saved));
        form.populationLimit = Integer.toString(saved.populationLimitPerPlayerOwnedTotal());
        assertFalse(parser.hasChanges(form, saved, saved));
    }

    private static TameworkSettingsPage.EventPayload form(TameworkSettingsValues values) {
        TameworkSettingsPage.EventPayload form = new TameworkSettingsPage.EventPayload();
        form.populationLimit = Integer.toString(values.populationLimitPerPlayerOwnedTotal());
        form.populationScope = values.populationPerPlayerLimitScope().configValue();
        form.claimLimitChunk = Integer.toString(values.simpleClaimsLimitPerClaimChunk());
        form.claimLimitTotal = Integer.toString(values.simpleClaimsLimitPerClaimTotal());
        form.needsOwnerOfflineGraceHours = Double.toString(values.needsOwnerOfflineGraceHours());
        form.needsOwnerOfflineDecayMultiplier = Double.toString(values.needsOwnerOfflineDecayMultiplier());
        form.needsStarvationDamagePerMinute = Double.toString(values.needsStarvationDamagePerMinute());
        form.needsDehydrationDamagePerMinute = Double.toString(values.needsDehydrationDamagePerMinute());
        return form;
    }
}
