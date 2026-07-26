package com.alechilles.alecstamework.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BondedCompanionApiContractTest {
    @Test
    void tameworkFallbackReturnsExplicitUnavailableResult() {
        BondedCompanionApi bonded = new FallbackOnlyApi()
                .bondedCompanions();

        BondedCompanionResult<?> result = bonded.list(
                UUID.randomUUID(),
                "hydragon:dragons"
        ).join();

        assertNotNull(bonded);
        assertNotNull(bonded.availability());
        assertFalse(bonded.availability().available());
        assertEquals(BondedCompanionResultCode.UNAVAILABLE, result.code());
        assertFalse(result.successful());
    }

    @Test
    void profileViewDefensivelyCopiesSnapshotPresentationData() {
        Map<String, String> source = new HashMap<>();
        source.put("variant", "ember");
        BondedCompanionProfileView view = new BondedCompanionProfileView(
                "profile-1",
                UUID.randomUUID(),
                "hydragon:dragons",
                "hydragon:dragon",
                "Tamed_Dragon_Fire",
                "Ember",
                "Dragon",
                "Female",
                4L,
                BondedCompanionState.STORED,
                true,
                false,
                false,
                source,
                null,
                0L,
                null
        );

        source.put("variant", "ice");

        assertEquals("ember", view.snapshotPresentationData().get("variant"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> view.snapshotPresentationData().put("variant", "storm")
        );
    }

    @Test
    void extensionDataIsProfileKeyedNamespacedAndRevisionFenced() {
        UUID ownerUuid = UUID.randomUUID();
        BondedCompanionExtensionDataKey key =
                new BondedCompanionExtensionDataKey(
                        ownerUuid,
                        "profile-1",
                        "hydragon.combat"
                );
        BondedCompanionExtensionDataUpdate update =
                new BondedCompanionExtensionDataUpdate(
                        key,
                        "{\"stance\":\"guard\"}",
                        7L
                );

        assertEquals(ownerUuid, update.key().ownerUuid());
        assertEquals("profile-1", update.key().profileId());
        assertEquals("hydragon.combat", update.key().namespace());
        assertEquals(7L, update.expectedRevision());
        assertThrows(
                IllegalArgumentException.class,
                () -> new BondedCompanionExtensionDataUpdate(
                        key,
                        "{}",
                        -1L
                )
        );
    }

    private static final class FallbackOnlyApi implements TameworkApi {
        @Override
        public String getApiVersion() {
            return "test";
        }

        @Override
        public EnumSet<TameworkApiCapability> getCapabilities() {
            return EnumSet.noneOf(TameworkApiCapability.class);
        }

        @Override public NpcProfilesApi profiles() { return null; }
        @Override public CommandLinksApi commandLinks() { return null; }
        @Override public ProgressionApi progression() { return null; }
        @Override public PolicyApi policies() { return null; }
        @Override public InteractionExtensionApi interactionExtensions() { return null; }
        @Override public TraitEffectApi traitEffects() { return null; }
        @Override public ProfileDataApi profileData() { return null; }
        @Override public TameworkEventsApi events() { return null; }
        @Override public TameworkConfigReadApi configs() { return null; }
        @Override public DiagnosticsApi diagnostics() { return null; }
    }
}
