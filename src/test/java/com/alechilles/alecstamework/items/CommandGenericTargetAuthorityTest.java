package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Behavioral contract for rejecting bonded projections at generic boundaries. */
class CommandGenericTargetAuthorityTest {
    @Test
    void hookDispatchRejectsMissingInvalidAndWrongStoreReferences()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install();
             TestEntityComponentStore otherStore =
                     new TestEntityComponentStore(scope.entityStore)) {
            LiveTarget target = scope.liveOrdinaryTarget(true);
            CommandNpcHookDispatchService service =
                    new CommandNpcHookDispatchService();

            assertFalse(service.dispatch("test.hook", target.player,
                    "test:item", null, scope.store, null));
            Ref<EntityStore> invalid = new Ref<>(scope.store);
            assertFalse(service.dispatch("test.hook", target.player,
                    "test:item", invalid, scope.store, null));
            assertFalse(service.dispatch("test.hook", target.player,
                    "test:item", target.reference, otherStore, null));

            assertTrue(service.dispatch("test.hook", target.player,
                    "test:item", target.reference, scope.store, null));
            assertEquals("test.hook", scope.store.getComponent(target.reference,
                    scope.hookType).getHookId());
        }
    }

    @Test
    void commandStepOrdinaryHookDelegatesExactPayloadAndResult()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget target = scope.liveOrdinaryTarget(true);
            CommandEntry command = commandEntry("""
                    {
                      "Id":"Hook",
                      "Steps":[{
                        "Type":"TriggerHook",
                        "HookId":"test.command.hook"
                      }]
                    }
                    """);

            StepResult result = executeCommand(scope, target, command, null);
            TameworkHookComponent hook = scope.store.getComponent(
                    target.reference, scope.hookType);

            assertTrue(result.applied);
            assertFalse(result.abortAll);
            assertEquals(null, result.appliedState);
            assertEquals("test.command.hook", hook.getHookId());
            assertEquals(target.owner, hook.getPlayerId());
            assertEquals("test:item", hook.getHeldItemId());
            assertTrue(hook.isConsumeOnMatch());
            assertFalse(hook.hasTargetPosition());
        }
    }

    @Test
    void commandStepMoveHookDelegatesFinalRaycastPosition()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget target = scope.liveOrdinaryTarget(true);
            Vector3d destination = new Vector3d(12.5, 64.0, -9.25);
            CommandEntry command = commandEntry("""
                    {
                      "Id":"Move",
                      "Steps":[{
                        "Type":"MoveToPosition",
                        "Source":"RaycastHit"
                      }]
                    }
                    """);

            StepResult result = executeCommand(
                    scope, target, command, destination);
            TameworkHookComponent hook = scope.store.getComponent(
                    target.reference, scope.hookType);

            assertTrue(result.applied);
            assertFalse(result.abortAll);
            assertEquals("Tamework.Command.MoveToPosition.RaycastHit",
                    hook.getHookId());
            assertEquals(destination, hook.getTargetPosition());
        }
    }

    @Test
    void commandStepStoredHomeHookDelegatesIntermediatePathPosition()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget target = scope.liveOrdinaryTarget(true);
            Vector3d start = new Vector3d(0.0, 0.0, 0.0);
            Vector3d home = new Vector3d(1_000.0, 0.0, 0.0);
            scope.store.put(target.reference, TransformComponent.getComponentType(),
                    new TransformComponent(start, new Rotation3f()));
            TameworkCommandLinksComponent links = scope.store.getComponent(
                    target.reference, scope.linksType);
            links.setHomePosition(home);
            scope.store.put(target.reference, scope.linksType, links);
            CommandEntry command = commandEntry("""
                    {
                      "Id":"ReturnHome",
                      "Steps":[{
                        "Type":"MoveToPosition",
                        "Source":"StoredHome"
                      }]
                    }
                    """);

            StepResult result = executeCommand(
                    scope,
                    target,
                    command,
                    null,
                    new CommandStepExecutionService(
                            null,
                            null,
                            null,
                            new CommandNpcHookDispatchService(),
                            () -> true
                    )
            );
            TameworkHookComponent hook = scope.store.getComponent(
                    target.reference, scope.hookType);

            assertTrue(result.applied);
            assertFalse(result.abortAll);
            assertEquals("Tamework.Command.MoveToPosition.StoredHome",
                    hook.getHookId());
            assertEquals(new Vector3d(24.0, 0.0, 0.0),
                    hook.getTargetPosition());
        }
    }

    @Test
    void identifiesOnlyLiveBondedProjectionMarkers() throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            Ref<EntityStore> bonded = scope.store.createReference();
            Ref<EntityStore> ordinary = scope.store.createReference();
            scope.store.put(bonded, scope.markerType,
                    TameworkProjectionIdentityComponent.bondedCompanion(
                            "profile", "lease"));
            scope.store.put(ordinary, scope.markerType,
                    new TameworkProjectionIdentityComponent(
                            "profile", "operation",
                            TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                            null, null, 0L));

            assertFalse(allowsGenericTargetMutation(bonded, scope.store));
            assertTrue(allowsGenericTargetMutation(ordinary, scope.store));
        }
    }

    @Test
    void forgedGenericReleaseLeavesBondedTargetOwnershipUntouched()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget target = scope.liveBondedTarget();
            CommandOwnerReleaseService release = new CommandOwnerReleaseService(
                    new CommandLinkPolicyService(),
                    new CommandStepExecutionService(null, null, null),
                    new CommandFeedbackService(null),
                    new CommandNpcNameResolver());

            release.release(target.player, "generic-tool", genericConfig(),
                    target.uuid);

            assertEquals(target.owner, scope.store.getComponent(
                    target.reference, scope.ownerType).getOwnerId());
        }
    }

    @Test
    void forgedGenericCullLeavesBondedTargetLinksUntouched()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget target = scope.liveBondedTarget();
            DamageCause previousCommandCause = DamageCause.COMMAND;
            DamageCause.COMMAND = new DamageCause("test-command");
            CommandOwnerCullService cull = new CommandOwnerCullService(
                    new CommandLinkPolicyService(),
                    new CommandItemRegistry(),
                    new CommandLinkMutationService(null,
                            new CommandLinkPolicyService(), null, null),
                    new CommandFeedbackService(null),
                    new CommandNpcNameResolver());

            try {
                try {
                    cull.cull(target.player, "generic-tool", genericConfig(),
                            target.uuid);
                } catch (NullPointerException fixtureOnlyDamageCauseFailure) {
                    // The bare ECS fixture has no DamageCause asset store. Cull
                    // has already cleared links before this base-game-only path.
                    assertTrue(fixtureOnlyDamageCauseFailure.getStackTrace()[0]
                            .getClassName().equals(DamageCause.class.getName()));
                }

                assertTrue(scope.store.getComponent(target.reference,
                        scope.linksType).containsToolId("generic-tool"));
            } finally {
                DamageCause.COMMAND = previousCommandCause;
            }
        }
    }

    @Test
    void forgedGenericLinkCannotPersistBondedProjectionUuid()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget target = scope.liveBondedTarget(false);
            CommandLinkMutationService links = new CommandLinkMutationService(
                    null, new CommandLinkPolicyService(), null, null);
            ItemStack unchanged = metadataStack("test:generic-whistle");

            LinkToggleResult result = links.tryToggleLink(
                    target.player, scope.store, target.reference,
                    "generic-tool", genericConfig(), unchanged);

            assertFalse(result.toggled);
            assertFalse(scope.store.getComponent(target.reference,
                    scope.linksType).containsToolId("generic-tool"));
            assertEquals(null, result.updatedItem);
            assertTrue(new CommandLinkedNpcRecordStore().read(unchanged)
                    .isEmpty());
        }
    }

    @Test
    void genericRecipientQueryExcludesBondedProjectionButKeepsOrdinaryNpc()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget bonded = scope.liveBondedTarget(true);
            LiveTarget ordinary = scope.liveOrdinaryTarget(true);
            Ref<EntityStore> playerRef = scope.store.createReference();
            scope.store.put(playerRef, scope.transformType,
                    new TransformComponent());
            Context context = new Context(
                    bonded.player, playerRef, scope.store, genericConfig(),
                    null, "test:generic-whistle", "generic-tool", null,
                    null, metadataStack("test:generic-whistle"), false,
                    false, 0D, 0D, 0L, 0D, 0D);

            List<Candidate> recipients = new CommandRecipientService(
                    null, null, null).queryRecipients(context);

            assertEquals(List.of(ordinary.uuid), recipients.stream()
                    .map(candidate -> candidate.npc.getUuid()).toList());
        }
    }

    @Test
    void genericPositionRefreshNeverWritesBondedProjectionRecord()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget bonded = scope.liveBondedTarget(true);
            LiveTarget ordinary = scope.liveOrdinaryTarget(true);
            CommandLinkedNpcRecordStore records =
                    new CommandLinkedNpcRecordStore();
            CommandLinkMutationService links = new CommandLinkMutationService(
                    records, new CommandLinkPolicyService(), null, null);

            ItemStack refreshed = links.refreshLinkedNpcPositions(
                    metadataStack("test:generic-whistle"),
                    List.of(new Candidate(bonded.reference, bonded.npc, 0D),
                            new Candidate(ordinary.reference, ordinary.npc, 1D)),
                    scope.store);

            assertEquals(List.of(ordinary.uuid), records.read(refreshed).stream()
                    .map(record -> record.npcUuid).toList());
        }
    }

    @Test
    void preferredAutoLinkRefreshCannotPersistBondedProjectionUuid()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget bonded = scope.liveBondedTarget(true);
            SimpleItemContainer hotbar = installCommandTool(
                    bonded.player, "test:generic-whistle", "generic-tool");
            CommandItemRegistry registry = new CommandItemRegistry();
            TwCommandItemConfig config = genericConfig();
            registry.register("test:generic-whistle", config);

            invokePreferredAutoLinkRefresh(
                    new CommandAutoLinkService(registry,
                            new CommandPanelPreferenceService(),
                            new CommandLinkMutationService(null, null, null)),
                    bonded.player, bonded.reference, scope.store,
                    hotbar, config, "generic-tool");

            assertTrue(new CommandLinkedNpcRecordStore().read(
                    hotbar.getItemStack((short) 0)).isEmpty());
        }
    }

    @Test
    void preferredAutoLinkRefreshStillPersistsOrdinaryGenericTarget()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget ordinary = scope.liveOrdinaryTarget(true);
            SimpleItemContainer hotbar = installCommandTool(
                    ordinary.player, "test:generic-whistle", "generic-tool");
            CommandItemRegistry registry = new CommandItemRegistry();
            TwCommandItemConfig config = genericConfig();
            registry.register("test:generic-whistle", config);

            invokePreferredAutoLinkRefresh(
                    new CommandAutoLinkService(registry,
                            new CommandPanelPreferenceService(),
                            new CommandLinkMutationService(null, null, null)),
                    ordinary.player, ordinary.reference, scope.store,
                    hotbar, config, "generic-tool");

            assertEquals(List.of(ordinary.uuid),
                    new CommandLinkedNpcRecordStore().read(
                                    hotbar.getItemStack((short) 0)).stream()
                            .map(record -> record.npcUuid).toList());
        }
    }

    @Test
    void genericNearbyPresentationRejectsBondedLiveMarker()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            Ref<EntityStore> bonded = scope.store.createReference();
            scope.store.put(bonded, scope.markerType,
                    TameworkProjectionIdentityComponent.bondedCompanion(
                            "nearby-profile", "nearby-lease"));

            assertFalse(isAllowedInGenericNearbyMode(bonded, scope.store));
        }
    }

    @Test
    void genericTargetMutationFailsClosedWhenMarkerTypeIsUnavailable()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            Ref<EntityStore> ordinary = scope.store.createReference();
            Field instance = staticField(Tamework.class, "instance");
            Object configuredTamework = instance.get(null);
            try {
                instance.set(null, null);
                assertFalse(allowsGenericTargetMutation(ordinary, scope.store));
            } finally {
                instance.set(null, configuredTamework);
            }
        }
    }

    @Test
    void corruptProjectionMarkerKindsFailClosedButKnownGenericAndUnmarkedPass()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            Ref<EntityStore> unmarked = scope.store.createReference();
            Ref<EntityStore> knownGeneric = scope.store.createReference();
            Ref<EntityStore> blankKind = scope.store.createReference();
            Ref<EntityStore> unknownKind = scope.store.createReference();
            scope.store.put(knownGeneric, scope.markerType,
                    new TameworkProjectionIdentityComponent("p", "o",
                            TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                            null, null, 0L));
            scope.store.put(blankKind, scope.markerType,
                    new TameworkProjectionIdentityComponent("p", "o", "",
                            null, null, 0L));
            scope.store.put(unknownKind, scope.markerType,
                    new TameworkProjectionIdentityComponent("p", "o",
                            "UNKNOWN_FUTURE_KIND", null, null, 0L));

            assertTrue(allowsGenericTargetMutation(unmarked, scope.store));
            assertTrue(allowsGenericTargetMutation(knownGeneric, scope.store));
            assertFalse(allowsGenericTargetMutation(blankKind, scope.store));
            assertFalse(allowsGenericTargetMutation(unknownKind, scope.store));
        }
    }

    @Test
    void staleGenericCallbackRejectsCurrentBondedToolConfiguration()
            throws Exception {
        ItemStack physicalBondedHorn = testStack("test:bonded-horn");
        TwCommandItemConfig pageConfig = genericConfig();
        TwCommandItemConfig currentConfig = bondedConfig();

        assertFalse(allowsGenericCallback(
                physicalBondedHorn, pageConfig, currentConfig
        ));
    }

    @Test
    void staleGenericPageRevisionRejectsReloadedGenericPolicy()
            throws Exception {
        ItemStack physicalGenericTool = testStack("test:generic-whistle");
        TwCommandItemConfig openedConfig = genericConfig();
        TwCommandItemConfig reloadedGenericConfig = TwCommandItemConfig.CODEC
                .decode(BsonDocument.parse("{\"RequireTamed\":true}"),
                        new ExtraInfo());
        CommandItemRegistry registry = new CommandItemRegistry();
        registry.register("test:generic-whistle", openedConfig);
        long openedRevision = registry.revision();

        assertTrue(allowsGenericCallbackAtRevision(
                physicalGenericTool, openedConfig, openedRevision, registry));
        registry.clear();
        registry.register("test:generic-whistle", reloadedGenericConfig);

        assertFalse(allowsGenericCallbackAtRevision(
                physicalGenericTool, openedConfig, openedRevision, registry));
    }

    @Test
    void genericLifecycleAuthorityRetainsExactRegisteredConfigIdOverride()
            throws Exception {
        ItemStack physicalOverrideItem = testStack("test:override-item");
        TwCommandItemConfig opened = genericConfig();
        setField(opened, TwCommandItemConfig.class, "id", "generic-override");
        CommandItemRegistry registry = bondedCommandRegistry(
                "test:roster-a", "test:override-item", "bonded-base",
                bondedConfig("test:roster-a", true));
        registry.register("generic-override", "test:configured-generic-item",
                opened);
        CommandItemRegistry nullBaseRegistry = new CommandItemRegistry();
        nullBaseRegistry.register("generic-override",
                "test:configured-generic-item", opened);

        assertTrue(allowsGenericCallbackAtRevision(
                physicalOverrideItem, opened, registry.revision(), registry));
        assertTrue(allowsGenericCallbackAtRevision(
                physicalOverrideItem, opened, nullBaseRegistry.revision(),
                nullBaseRegistry));
    }

    @Test
    void genericLifecycleAuthorityRejectsUnboundConfigAndPhysicalSubstitution()
            throws Exception {
        ItemStack physicalGenericTool = testStack("test:generic-whistle");
        TwCommandItemConfig registered = genericConfig();
        setField(registered, TwCommandItemConfig.class, "id", "registered");
        TwCommandItemConfig different = genericConfig();
        setField(different, TwCommandItemConfig.class, "id", "different");
        CommandItemRegistry registry = new CommandItemRegistry();
        registry.register("registered", "test:generic-whistle", registered);
        long openedRevision = registry.revision();

        assertFalse(allowsGenericCallbackAtRevision(
                physicalGenericTool, different, openedRevision, registry));
        assertFalse(allowsGenericCallbackAtRevision(
                testStack("test:substituted-whistle"),
                "test:generic-whistle", registered, openedRevision,
                registry));
    }

    @Test
    void bondedLifecycleAuthorityRequiresCurrentPhysicalConfigAndRoster()
            throws Exception {
        ItemStack horn = testStack("test:horn-a");
        TwCommandItemConfig opened = bondedConfig("test:roster-a", true);

        assertTrue(allowsBondedCallback(
                horn, "test:horn-a", opened,
                bondedConfig("test:roster-a", true)));
        assertFalse(allowsBondedCallback(
                horn, "test:horn-a", opened,
                bondedConfig("test:roster-b", true)));
        assertFalse(allowsBondedCallback(
                horn, "test:horn-a", opened, genericConfig()));
        assertFalse(allowsBondedCallback(
                horn, "test:horn-a", opened,
                bondedConfig("test:roster-a", false)));
        assertFalse(allowsBondedCallback(
                testStack("test:substituted-item"), "test:horn-a", opened,
                bondedConfig("test:roster-a", true)));
    }

    @Test
    void bondedLifecycleAuthorityRejectsRemovalAndAnyRegistryReload()
            throws Exception {
        ItemStack horn = testStack("test:horn-a");
        TwCommandItemConfig opened = bondedConfig("test:roster-a", true);
        CommandItemRegistry registry = bondedCommandRegistry(
                "test:roster-a", "test:horn-a", "horn-config",
                bondedConfig("test:roster-a", true));
        long openedRevision = registry.revision();

        assertTrue(allowsBondedCallbackAtRevision(
                horn, "test:horn-a", opened, openedRevision, registry));
        registry.clear();
        assertFalse(allowsBondedCallbackAtRevision(
                horn, "test:horn-a", opened, openedRevision, registry));

        registry.register("horn-config", "test:horn-a",
                bondedConfig("test:roster-a", true));
        assertFalse(allowsBondedCallbackAtRevision(
                horn, "test:horn-a", opened, openedRevision, registry));
    }

    @Test
    void bondedLifecycleAuthorityRetainsValidConfigIdOverride()
            throws Exception {
        ItemStack physicalOverrideItem = testStack("test:override-item");
        TwCommandItemConfig opened = bondedConfig("test:roster-a", true);
        setField(opened, TwCommandItemConfig.class, "id", "override-config");
        CommandItemRegistry registry = bondedCommandRegistry(
                "test:roster-a", "test:configured-item", "override-config",
                bondedConfig("test:roster-a", true));

        assertTrue(allowsBondedCallbackAtRevision(
                physicalOverrideItem, "test:override-item", opened,
                registry.revision(), registry));
    }

    @Test
    void duplicateToolIdsFailClosedRegardlessOfPhysicalStackOrder()
            throws Exception {
        ItemStack genericWhistle = testStack("test:generic-whistle");
        ItemStack bondedHorn = testStack("test:bonded-horn");

        assertEquals(genericWhistle, CommandToolInventoryService
                .selectUniqueToolStack("unique", java.util.List.of(
                        new CommandToolInventoryService.ToolCandidate(
                                genericWhistle, "unique"))));
        assertEquals(null, CommandToolInventoryService.selectUniqueToolStack(
                "unique", java.util.List.of()));
        assertEquals(null, CommandToolInventoryService.selectUniqueToolStack(
                "shared", java.util.List.of(
                        new CommandToolInventoryService.ToolCandidate(
                                genericWhistle, "shared"),
                        new CommandToolInventoryService.ToolCandidate(
                                bondedHorn, "shared"))));
        assertEquals(null, CommandToolInventoryService.selectUniqueToolStack(
                "shared", java.util.List.of(
                        new CommandToolInventoryService.ToolCandidate(
                                bondedHorn, "shared"),
                        new CommandToolInventoryService.ToolCandidate(
                                genericWhistle, "shared"))));
    }

    @Test
    void cullRepairSkipsBondedHornButRetainsGenericToolEligibility()
            throws Exception {
        ItemStack bondedHorn = testStack("test:bonded-horn");
        ItemStack genericWhistle = testStack("test:generic-whistle");

        assertFalse(allowsGenericCullRepair(bondedHorn, bondedConfig()));
        assertFalse(CommandGenericTargetAuthority.allowsGenericCullRepair(
                bondedHorn, null));
        assertFalse(CommandGenericTargetAuthority.allowsGenericCullRepair(
                bondedHorn, disabledGenericConfig()));
        assertTrue(allowsGenericCullRepair(genericWhistle, genericConfig()));
    }

    private static TwCommandItemConfig genericConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse(
                "{\"RequireTamed\":false}"),
                new ExtraInfo());
    }

    private static TwCommandItemConfig bondedConfig() {
        return bondedConfig("test:bonded", true);
    }

    private static TwCommandItemConfig bondedConfig(
            String rosterId, boolean enabled) {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "Enabled":%s,
                  "RosterStorage":"BondedCompanions",
                  "BondedRosterId":"%s"
                }
                """.formatted(enabled, rosterId)), new ExtraInfo());
    }

    private static TwCommandItemConfig disabledGenericConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse(
                "{\"Enabled\":false}"), new ExtraInfo());
    }

    private static boolean isAllowedInGenericNearbyMode(
            Ref<EntityStore> reference, TestEntityComponentStore store
    ) throws Exception {
        return invokeBoolean("allowsNearbyPresentation", reference, store);
    }

    private static boolean allowsGenericCallback(
            ItemStack stack, TwCommandItemConfig openTimeConfig,
            TwCommandItemConfig currentPhysicalConfig
    ) throws Exception {
        return invokeBoolean("allowsCurrentGenericCallback", stack,
                openTimeConfig, currentPhysicalConfig);
    }

    private static boolean allowsGenericCullRepair(
            ItemStack stack, TwCommandItemConfig currentPhysicalConfig
    ) throws Exception {
        return invokeBoolean("allowsGenericCullRepair", stack,
                currentPhysicalConfig);
    }

    private static boolean allowsGenericCallbackAtRevision(
            ItemStack stack,
            TwCommandItemConfig openTimeConfig,
            long openedRevision,
            CommandItemRegistry registry
    ) throws Exception {
        return invokeBoolean("allowsCurrentGenericCallback", stack,
                openTimeConfig, openedRevision, registry);
    }

    private static boolean allowsGenericCallbackAtRevision(
            ItemStack stack,
            String openedItemId,
            TwCommandItemConfig openTimeConfig,
            long openedRevision,
            CommandItemRegistry registry
    ) throws Exception {
        return invokeBoolean("allowsCurrentGenericCallback", stack,
                openedItemId, openTimeConfig, openedRevision, registry);
    }

    private static boolean allowsBondedCallback(
            ItemStack stack,
            String openedItemId,
            TwCommandItemConfig openTimeConfig,
            TwCommandItemConfig currentPhysicalConfig
    ) throws Exception {
        return invokeBoolean("allowsCurrentBondedCallback", stack,
                openedItemId, openTimeConfig, currentPhysicalConfig);
    }

    private static boolean allowsBondedCallbackAtRevision(
            ItemStack stack,
            String openedItemId,
            TwCommandItemConfig openTimeConfig,
            long openedRevision,
            CommandItemRegistry registry
    ) throws Exception {
        return invokeBoolean("allowsCurrentBondedCallback", stack,
                openedItemId, openTimeConfig, openedRevision, registry);
    }

    private static CommandItemRegistry bondedCommandRegistry(
            String rosterId,
            String itemId,
            String configId,
            TwCommandItemConfig config
    ) throws Exception {
        TwBondedCompanionRosterConfig roster =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId":"%s",
                                  "FamilyId":"test:family",
                                  "AllowedRoles":["test:role"]
                                }
                                """.formatted(rosterId)), new ExtraInfo());
        setField(roster, TwBondedCompanionRosterConfig.class,
                "id", "roster-policy");
        BondedCompanionRosterRegistry rosters =
                new BondedCompanionRosterRegistry();
        assertTrue(rosters.replace(List.of(roster), 1L).applied());
        CommandItemRegistry registry = new CommandItemRegistry(rosters);
        registry.register(configId, itemId, config);
        return registry;
    }

    private static boolean allowsGenericTargetMutation(
            Ref<EntityStore> reference, TestEntityComponentStore store
    ) throws Exception {
        return invokeBoolean("allowsGenericTargetMutation", reference, store);
    }

    private static boolean invokeBoolean(String methodName, Object... arguments)
            throws Exception {
        Class<?> authority;
        try {
            authority = Class.forName(
                    "com.alechilles.alecstamework.items.CommandGenericTargetAuthority"
            );
        } catch (ClassNotFoundException missing) {
            fail("Generic command boundaries need a bonded marker authority",
                    missing);
            return false;
        }
        Class<?>[] types = java.util.Arrays.stream(arguments)
                .map(argument -> argument instanceof TestEntityComponentStore
                        ? com.hypixel.hytale.component.Store.class
                        : argument instanceof Ref<?>
                        ? Ref.class
                        : argument instanceof Long
                        ? long.class
                        : argument.getClass())
                .toArray(Class<?>[]::new);
        Method method = authority.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return (boolean) method.invoke(null, arguments);
    }

    private static ItemStack testStack(String itemId) throws Exception {
        ItemStack stack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
        setField(stack, ItemStack.class, "itemId", itemId);
        unsafe().putInt(stack, unsafe().objectFieldOffset(
                ItemStack.class.getDeclaredField("quantity")), 1);
        return stack;
    }

    private static ItemStack metadataStack(String itemId) {
        return new MetadataItemStack(itemId, null);
    }

    private static SimpleItemContainer installCommandTool(
            Player player, String itemId, String toolId) throws Exception {
        ItemStack stack = metadataStack(itemId).withMetadata(
                com.alechilles.alecstamework.config.TameworkMetadataKeys
                        .COMMAND_TOOL_ID,
                com.hypixel.hytale.codec.Codec.STRING,
                toolId);
        SimpleItemContainer hotbar = new SimpleItemContainer((short) 1);
        hotbar.setItemStackForSlot((short) 0, stack);
        Inventory inventory = new Inventory();
        setField(inventory, Inventory.class, "hotbar",
                new InventoryComponent.Hotbar(hotbar, (byte) 0));
        setField(player, LivingEntity.class, "inventory", inventory);
        return hotbar;
    }

    private static void invokePreferredAutoLinkRefresh(
            CommandAutoLinkService service,
            Player player,
            Ref<EntityStore> npcRef,
            TestEntityComponentStore store,
            SimpleItemContainer hotbar,
            TwCommandItemConfig config,
            String toolId
    ) throws Exception {
        CombinedItemContainer combined = new CombinedItemContainer(hotbar);
        Class<?> candidateType = Class.forName(
                CommandAutoLinkService.class.getName() + "$ToolCandidate");
        var constructor = candidateType.getDeclaredConstructor(
                CombinedItemContainer.class, short.class, ItemStack.class,
                String.class, TwCommandItemConfig.class);
        constructor.setAccessible(true);
        Object candidate = constructor.newInstance(
                combined, (short) 0, hotbar.getItemStack((short) 0), toolId,
                config);
        Method method = CommandAutoLinkService.class.getDeclaredMethod(
                "syncPreferredCandidate",
                Player.class, com.hypixel.hytale.component.Store.class,
                Ref.class, candidateType,
                String.class);
        method.setAccessible(true);
        method.invoke(service, player, store, npcRef, candidate, toolId);
    }

    /** Asset-store-free stack that keeps real BSON metadata semantics. */
    private static final class MetadataItemStack extends ItemStack {
        private MetadataItemStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public <T> ItemStack withMetadata(
                String key, com.hypixel.hytale.codec.Codec<T> codec, T value) {
            BsonDocument next = metadata == null
                    ? new BsonDocument() : metadata.clone();
            if (value == null) next.remove(key);
            else next.put(key, codec.encode(value));
            return new MetadataItemStack(itemId,
                    next.isEmpty() ? null : next);
        }
    }

    private static final class ProjectionScope implements AutoCloseable {
        private final Object oldTamework;
        private final Object oldEntityModule;
        private final ComponentType<EntityStore, NPCEntity> npcType =
                new ComponentType<>();
        private final ComponentType<EntityStore,
                TameworkProjectionIdentityComponent> markerType =
                new ComponentType<>();
        private final ComponentType<EntityStore, TameworkOwnerComponent>
                ownerType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkCommandLinksComponent>
                linksType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkHookComponent>
                hookType = new ComponentType<>();
        private final ComponentType<EntityStore, TransformComponent>
                transformType = new ComponentType<>();
        private final TestWorld world;
        private final TestEntityStore entityStore;
        private final TestEntityComponentStore store;

        private ProjectionScope(Object oldTamework, Object oldEntityModule)
                throws Exception {
            this.oldTamework = oldTamework;
            this.oldEntityModule = oldEntityModule;
            this.world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
            this.entityStore = new TestEntityStore(world);
            this.world.bind(entityStore);
            this.store = new TestEntityComponentStore(entityStore);
            entityStore.store = store;
        }

        private static ProjectionScope install() throws Exception {
            Field instance = staticField(Tamework.class, "instance");
            Field entityModule = staticField(EntityModule.class, "instance");
            ProjectionScope scope = new ProjectionScope(instance.get(null),
                    entityModule.get(null));
            EntityModule module = (EntityModule) unsafe().allocateInstance(
                    EntityModule.class);
            Map<Class<?>, ComponentType<EntityStore, ?>> types = new HashMap<>();
            types.put(NPCEntity.class, scope.npcType);
            types.put(TransformComponent.class, scope.transformType);
            setField(module, EntityModule.class, "classToComponentType", types);
            entityModule.set(null, module);
            Tamework tamework = (Tamework) unsafe().allocateInstance(
                    Tamework.class);
            setField(tamework, Tamework.class,
                    "projectionIdentityComponentType", scope.markerType);
            setField(tamework, Tamework.class,
                    "ownerComponentType", scope.ownerType);
            setField(tamework, Tamework.class,
                    "commandLinksComponentType", scope.linksType);
            setField(tamework, Tamework.class,
                    "hookComponentType", scope.hookType);
            instance.set(null, tamework);
            return scope;
        }

        @Override
        public void close() throws Exception {
            store.close();
            staticField(Tamework.class, "instance").set(null, oldTamework);
            staticField(EntityModule.class, "instance").set(null,
                    oldEntityModule);
        }

        private LiveTarget liveBondedTarget() throws Exception {
            return liveBondedTarget(true);
        }

        private LiveTarget liveBondedTarget(boolean linked) throws Exception {
            return liveTarget(true, linked,
                    UUID.fromString("73000000-0000-0000-0000-000000000002"));
        }

        private LiveTarget liveOrdinaryTarget(boolean linked) throws Exception {
            return liveTarget(false, linked,
                    UUID.fromString("73000000-0000-0000-0000-000000000003"));
        }

        private LiveTarget liveTarget(boolean bonded, boolean linked, UUID uuid)
                throws Exception {
            UUID owner = UUID.fromString("73000000-0000-0000-0000-000000000001");
            Ref<EntityStore> reference = store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(uuid);
            store.put(reference, npcType, npc);
            store.put(reference, markerType, bonded
                    ? TameworkProjectionIdentityComponent.bondedCompanion(
                            "profile", "lease")
                    : new TameworkProjectionIdentityComponent(
                            "profile", "operation",
                            TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                            null, null, 0L));
            store.put(reference, transformType, new TransformComponent());
            TameworkOwnerComponent ownership = new TameworkOwnerComponent();
            ownership.setOwnerId(owner);
            store.put(reference, ownerType, ownership);
            TameworkCommandLinksComponent links =
                    new TameworkCommandLinksComponent();
            links.setOwnerId(owner);
            links.setToolIds(linked
                    ? new String[] {"generic-tool"} : new String[0]);
            store.put(reference, linksType, links);
            entityStore.testWorld.initialize(uuid, reference);
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(owner);
            setField(player, Player.class, "playerRef", playerRef(owner));
            player.loadIntoWorld(entityStore.testWorld);
            return new LiveTarget(player, reference, owner, uuid, npc);
        }
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityComponentStore store;
        private final TestWorld testWorld;

        private TestEntityStore(TestWorld world) {
            super(world);
            this.testWorld = world;
        }

        @Override
        public TestEntityComponentStore getStore() {
            return store;
        }
    }

    private static final class TestWorld extends
            com.hypixel.hytale.server.core.universe.world.World {
        private UUID uuid;
        private Ref<EntityStore> reference;
        private EntityStore entityStore;

        private TestWorld() throws java.io.IOException {
            super("test", java.nio.file.Path.of("."),
                    new com.hypixel.hytale.server.core.universe.world.WorldConfig());
        }

        private void initialize(UUID uuid, Ref<EntityStore> reference) {
            this.uuid = uuid;
            this.reference = reference;
        }

        private void bind(EntityStore entityStore) {
            this.entityStore = entityStore;
        }

        @Override
        public EntityStore getEntityStore() {
            return entityStore;
        }

        @Override
        public Ref<EntityStore> getEntityRef(UUID requested) {
            return uuid != null && uuid.equals(requested) ? reference : null;
        }
    }

    private record LiveTarget(Player player, Ref<EntityStore> reference,
                              UUID owner, UUID uuid, NPCEntity npc) {
    }

    private static PlayerRef playerRef(UUID owner) throws Exception {
        PlayerRef ref = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
        setField(ref, PlayerRef.class, "uuid", owner);
        setField(ref, PlayerRef.class, "username", "test-owner");
        setField(ref, PlayerRef.class, "language", "en-US");
        return ref;
    }


    private static Field staticField(Class<?> type, String name)
            throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static CommandEntry commandEntry(String json) {
        return TwCommandItemConfig.COMMAND_ENTRY_CODEC.decode(
                BsonDocument.parse(json), new ExtraInfo());
    }

    private static StepResult executeCommand(
            ProjectionScope scope,
            LiveTarget target,
            CommandEntry command,
            Vector3d raycastPosition
    ) {
        return executeCommand(
                scope,
                target,
                command,
                raycastPosition,
                new CommandStepExecutionService(null, null, null)
        );
    }

    private static StepResult executeCommand(
            ProjectionScope scope,
            LiveTarget target,
            CommandEntry command,
            Vector3d raycastPosition,
            CommandStepExecutionService service
    ) {
        Context context = new Context(
                target.player,
                null,
                scope.store,
                genericConfig(),
                command,
                "test:item",
                "generic-tool",
                null,
                raycastPosition,
                null,
                false,
                false,
                96.0,
                24.0,
                2_500L,
                20.0,
                80.0
        );
        return service.executeCommand(context,
                new Candidate(target.reference, target.npc, 0.0));
    }

    private static void setField(Object target, Class<?> type,
                                 String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putObject(target, unsafe().objectFieldOffset(field), value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
