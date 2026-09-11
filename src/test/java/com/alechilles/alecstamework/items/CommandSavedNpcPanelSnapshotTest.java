package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpoint;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpointCodec;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the visible last-known vital values used when an NPC is unavailable. */
class CommandSavedNpcPanelSnapshotTest {
    @Test
    void appliesFullStateVitalsToAnOfflineCard() {
        ProfileId profileId = new ProfileId(UUID.randomUUID());
        CoopResidentStateSnapshot state = new CoopResidentStateSnapshot(
                UUID.randomUUID(), null, -1, "Sheep_Pet", null, null,
                null, null, null, null, null, null, null, null, null, null,
                17.6, 42.4, 42.0, -1L
        );
        var encoded = TameworkSnapshotCodecs.create().encode(
                TameworkSnapshotCodecs.COOP, 1, CoopResidentStateSnapshot.class, state);
        CompanionSnapshot snapshot = new CompanionSnapshot(
                SnapshotId.create(), profileId, TameworkSnapshotCodecs.COOP, 1,
                encoded.payloadJson(), Sha256Hash.ofUtf8(encoded.payloadJson()),
                LifecycleRevision.INITIAL, true, -10L
        );

        CommandSavedNpcPanelSnapshot saved = CommandSavedNpcPanelSnapshot.decode(
                profile(profileId, snapshot));
        assertNotNull(saved);

        LinkedNpcEntry applied = saved.apply(baseCard(), null);
        assertEquals(18, applied.currentHealth());
        assertEquals(42, applied.maxHealth());
    }

    @Test
    void appliesAttachmentSpecificPortraitFromFullState() throws Exception {
        try (var assets = new DynamicIconTestAssets("""
                {"RoleIds":["Tamed_Sheep"],"IconOverrides":[
                  {"Icon":"Icons/Portraits/Sheep_Black.png","Attachments":{"Coat":"Black"}}]}
                """)) {
            ProfileId profileId = new ProfileId(UUID.randomUUID());
            CoopResidentStateSnapshot state = new CoopResidentStateSnapshot(
                    UUID.randomUUID(), null, -1, "Tamed_Sheep", null, null,
                    null, null, null, null, null, null, null, null, null,
                    new TameworkAttachmentsComponent(null, Map.of("Coat", "Black")),
                    null, null, null, -1L
            );
            var encoded = TameworkSnapshotCodecs.create().encode(
                    TameworkSnapshotCodecs.COOP, 1, CoopResidentStateSnapshot.class, state);
            CompanionSnapshot snapshot = new CompanionSnapshot(
                    SnapshotId.create(), profileId, TameworkSnapshotCodecs.COOP, 1,
                    encoded.payloadJson(), Sha256Hash.ofUtf8(encoded.payloadJson()),
                    LifecycleRevision.INITIAL, true, -10L
            );

            CommandSavedNpcPanelSnapshot saved = CommandSavedNpcPanelSnapshot.decode(
                    profile(profileId, snapshot));

            assertNotNull(saved);
            assertEquals("Icons/Portraits/Sheep_Black.png",
                    saved.apply(baseCard(), null).portraitIcon());
        }
    }

    @Test
    void appliesNamedCheckpointComponentsWithoutEnablingOfflineActions() {
        ProfileId profileId = new ProfileId(UUID.randomUUID());
        BsonDocument components = new BsonDocument()
                .append("TameworkNeeds", TameworkNeedsComponent.CODEC.encode(
                        new TameworkNeedsComponent("care-test", 33.0, 11.0, 0.0, 0L, 0L),
                        new ExtraInfo()))
                .append("TameworkLeveling", TameworkLevelingComponent.CODEC.encode(
                        new TameworkLevelingComponent("level-test", 3, 77.0, 177.0),
                        new ExtraInfo()))
                .append("TameworkTraits", TameworkTraitsComponent.CODEC.encode(
                        new TameworkTraitsComponent("traits-test", 7L,
                                new TameworkTraitsComponent.TraitValue[] {
                                        new TameworkTraitsComponent.TraitValue("Trait_Test", 1.2)
                                }),
                        new ExtraInfo()))
                .append("TameworkTalents", TameworkTalentsComponent.CODEC.encode(
                        new TameworkTalentsComponent("talents-test", 2, new String[] {"Talent_Test"}),
                        new ExtraInfo()))
                .append("TameworkBreeding", TameworkBreedingComponent.CODEC.encode(
                        new TameworkBreedingComponent("breed-test", 0.0, 0L, true, true,
                                0L, null),
                        new ExtraInfo()));
        CompanionEntityCheckpointCodec codec = new CompanionEntityCheckpointCodec();
        CompanionEntityCheckpoint checkpoint = CompanionEntityCheckpoint.create(
                profileId, new NpcAlias(UUID.randomUUID()), 0L, new OwnerId(UUID.randomUUID()),
                LifecycleRevision.INITIAL, ReconciliationGeneration.INITIAL, "world", 1.0, 2.0,
                3.0, CompanionEntityCheckpoint.CaptureBoundary.UNLOAD, -15L,
                new BsonDocument().append("Components", components), codec);

        CommandSavedNpcPanelSnapshot saved = CommandSavedNpcPanelSnapshot.decode(
                profileWithoutSnapshots(profileId), codec.encode(checkpoint));
        assertNotNull(saved);

        LinkedNpcEntry applied = saved.apply(baseCard(), null);
        assertTrue(applied.breedingEnabled());
        assertTrue(applied.isTraitsActionVisible());
        assertFalse(applied.isTraitsActionEnabled());
        assertFalse(applied.isTalentsActionVisible());
        assertFalse(applied.isTalentsActionEnabled());
    }

    private static CompanionProfileReadModel profile(
            ProfileId profileId,
            CompanionSnapshot snapshot
    ) {
        UUID owner = UUID.randomUUID();
        return new CompanionProfileReadModel(
                new CompanionIdentity(profileId, "Sheep", "Sheep_Pet", null,
                        null, "world", -20L, -20L, -20L, 0L),
                new CompanionAlias(new NpcAlias(UUID.randomUUID()), profileId, 0L,
                        CompanionAlias.State.CURRENT, null, -20L, null),
                new CompanionLifecycle(profileId, new OwnerId(owner), LifecycleState.UNLOADED,
                        LifecycleLocation.none(), LifecycleRevision.INITIAL, null, -20L,
                        ReconciliationGeneration.INITIAL, null, null),
                List.of(), List.of(snapshot), null
        );
    }

    private static CompanionProfileReadModel profileWithoutSnapshots(ProfileId profileId) {
        UUID owner = UUID.randomUUID();
        return new CompanionProfileReadModel(
                new CompanionIdentity(profileId, "Sheep", "Sheep_Pet", null,
                        null, "world", -20L, -20L, -20L, 0L),
                new CompanionAlias(new NpcAlias(UUID.randomUUID()), profileId, 0L,
                        CompanionAlias.State.CURRENT, null, -20L, null),
                new CompanionLifecycle(profileId, new OwnerId(owner), LifecycleState.UNLOADED,
                        LifecycleLocation.none(), LifecycleRevision.INITIAL, null, -20L,
                        ReconciliationGeneration.INITIAL, null, null),
                List.of(), List.of(), null
        );
    }

    private static LinkedNpcEntry baseCard() {
        return new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 1, 1, 0, 0,
                null, 0, 0, 0, 0, false, false, false, false, false, false,
                0L, new com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator[0]);
    }

}
