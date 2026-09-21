package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Role policy must use the canonical role cached for an owned companion. */
class CommandPanelEntrySourceServiceRoleEligibilityTest {
    private static final UUID OWNER = UUID.fromString(
            "76000000-0000-0000-0000-000000000001");
    private static final UUID ALLOWED = UUID.fromString(
            "76000000-0000-0000-0000-000000000002");
    private static final UUID UNSUPPORTED = UUID.fromString(
            "76000000-0000-0000-0000-000000000003");

    @Test
    void ownedEligibilityUsesCachedRoleInsteadOfSpeciesPresentation() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> playerRef = store.createReference();
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER);
            player.loadIntoWorld(world);
            player.setReference(null);

            ItemStack stack = new CommandLinkedNpcRecordStore().write(
                    new MetadataStack("test:flute", new BsonDocument()),
                    List.of(
                            record(ALLOWED, "Cow"),
                            record(UNSUPPORTED, "Chicken")));
            TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                    BsonDocument.parse("""
                            {"AllowedRoles":{"Mode":"Allowlist","Allowlist":["Cow"]}}
                            """), new ExtraInfo());
            var allowedProfile = profile(OWNER, ALLOWED, "Cow", com.alechilles.alecstamework.companion.lifecycle.LifecycleState.UNLOADED);
            var unsupportedProfile = profile(OWNER, UNSUPPORTED, "Chicken", com.alechilles.alecstamework.companion.lifecycle.LifecycleState.UNLOADED);
            UUID storedId = UUID.randomUUID();
            var storedProfile = profile(null, storedId, "Cow", com.alechilles.alecstamework.companion.lifecycle.LifecycleState.CAPTURED);
            var profiles = java.util.Map.of(allowedProfile.profileId(), allowedProfile,
                    unsupportedProfile.profileId(), unsupportedProfile, storedProfile.profileId(), storedProfile);
            stack = new CommandLinkedNpcRecordStore().write(stack, List.of(
                    record(ALLOWED, "Cow"), record(UNSUPPORTED, "Chicken"), record(storedId, "Cow")));
            var persistence = new CommandPersistenceView(new CommandPersistenceView.ProjectionLookup() {
                public java.util.Optional<com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState> find(
                        com.alechilles.alecstamework.companion.identity.ProfileId id) {
                    return java.util.Optional.ofNullable(profiles.get(id));
                }
                public java.util.Optional<com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState> find(
                        com.alechilles.alecstamework.companion.identity.NpcAlias alias) {
                    return profiles.values().stream().filter(profile -> alias.equals(profile.currentAlias())).findFirst();
                }
            });
            var names = new CommandNpcNameResolver();
            var policy = new CommandLinkPolicyService();
            var linked = new CommandLinkedPanelEntryService(new CommandLinkedNpcRecordStore(),
                    null, names, null, persistence, policy, new CommandGroupService(), null);
            CommandPanelEntrySourceService source = new CommandPanelEntrySourceService(linked,
                    new CommandPanelPreferenceService(), policy, names, null, null, null,
                    new CommandOwnedPanelRecordSource(() -> profiles));
            var snapshot = source.buildSnapshot(player, store, stack, config, "flute");
            List<LinkedNpcEntry> entries = snapshot.entries();
            var captured = entries.stream().filter(row -> storedId.equals(row.npcUuid())).findFirst().orElseThrow();
            assertTrue(captured.captured(), "A capture whose owner was cleared must reach the Stored filter.");
            assertFalse(captured.selectionSupported());
            assertTrue(captured.active(), "The captured card retains its flute selection while command control is unavailable.");
            org.junit.jupiter.api.Assertions.assertEquals(
                    CommandCompanionGroups.profileKey(storedProfile.profileId().toString()), captured.companionKey(),
                    "Captured group edits must use the same stable profile key after release.");
            assertTrue(snapshot.featurePresentations().get(storedId).managesRosterRow(),
                    "The informational captured card must suppress generic mutation actions.");

            assertTrue(entries.stream().filter(row -> ALLOWED.equals(row.npcUuid()))
                    .findFirst().orElseThrow().selectionSupported());
            assertFalse(entries.stream().filter(row -> UNSUPPORTED.equals(row.npcUuid()))
                    .findFirst().orElseThrow().selectionSupported());

            // A text filter must not change the group-selection summary of the full roster.
            var filtered = source.buildSnapshot(player, store,
                    new CommandPanelPreferenceService().setSpeciesFilter(stack, "Chicken"), config, "flute");
            org.junit.jupiter.api.Assertions.assertEquals(List.of(UNSUPPORTED),
                    filtered.entries().stream().map(LinkedNpcEntry::npcUuid).toList());
            org.junit.jupiter.api.Assertions.assertEquals(
                    CommandGroupAssignPageService.resolveGroupActivationValue(snapshot.selectionEntries(), List.of()),
                    CommandGroupAssignPageService.resolveGroupActivationValue(filtered.selectionEntries(), List.of()));
            assertTrue(filtered.selectionEntries().stream().anyMatch(row -> ALLOWED.equals(row.npcUuid())));
        }
    }

    private static LinkedNpcRecord record(UUID id, String roleId) {
        return new LinkedNpcRecord(id, null, null, "Companion", null, roleId);
    }

    private static com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState profile(
            UUID owner, UUID alias, String role, com.alechilles.alecstamework.companion.lifecycle.LifecycleState state) {
        return new com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState(
                new com.alechilles.alecstamework.companion.identity.ProfileId(UUID.randomUUID()),
                new com.alechilles.alecstamework.companion.identity.NpcAlias(alias), state,
                owner == null ? null : new com.alechilles.alecstamework.companion.identity.OwnerId(owner),
                null, role, role, null, true, null, null, java.util.Set.of(), java.util.Set.of(), 1L);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class MetadataStack extends ItemStack {
        private MetadataStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public <T> ItemStack withMetadata(String key,
                                          com.hypixel.hytale.codec.Codec<T> codec,
                                          T value) {
            BsonDocument next = metadata == null ? new BsonDocument() : metadata.clone();
            if (value == null) {
                next.remove(key);
            } else {
                next.put(key, codec.encode(value));
            }
            return new MetadataStack(itemId, next);
        }
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityComponentStore store;

        private TestEntityStore(World world) {
            super(world);
            ((TestWorld) world).entityStore = this;
        }

        @Override
        public TestEntityComponentStore getStore() {
            return store;
        }
    }

    private static final class TestWorld extends World {
        private EntityStore entityStore;

        private TestWorld() throws java.io.IOException {
            super("unused", Path.of("."),
                    new com.hypixel.hytale.server.core.universe.world.WorldConfig());
        }

        @Override
        public Ref<EntityStore> getEntityRef(UUID id) { return null; }

        @Override
        public String getName() {
            return "world-a";
        }

        @Override
        public EntityStore getEntityStore() {
            return entityStore;
        }
    }
}
