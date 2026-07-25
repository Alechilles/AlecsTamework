package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.api.SpawnerCaptureMechanicsView;
import com.alechilles.alecstamework.companion.capture
        .CaptureAttemptFormula;
import com.alechilles.alecstamework.companion.capture
        .CaptureAttemptResolution;
import com.alechilles.alecstamework.companion.command
        .CommandRosterActionView;
import com.alechilles.alecstamework.companion.command
        .CommandRosterMembership;
import com.alechilles.alecstamework.companion.command
        .CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonProjectionView;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle
        .CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle
        .ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group
        .PopulationGroupCounts;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.population
        .PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.authoring
        .ReplacementFeaturePolicySource;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Focused production-source fences for tame-and-command-link capture. */
class SpawnerTameAndLinkProductionEvidenceSourceTest {
    private static final String SLOT_NAMESPACE =
            "tamework:capture-tame-command-slot:v1:";

    private final SpawnerTameAndLinkEvidenceFixture fixture =
            new SpawnerTameAndLinkEvidenceFixture();

    @Test
    void missingOrLaggingProjectionFailsClosed() {
        SpawnerTameAndLinkProjectionSource missing =
                new SpawnerTameAndLinkProjectionSource(
                        () -> null, bucket -> zero()
                );
        assertNull(missing.freeze(input(), config()));

        SpawnerTameAndLinkProjectionSource lagging =
                source(values(
                        Map.of(), Set.of(fixture.PROFILE),
                        Map.of(), Set.of(), Map.of(), Set.of()
                ), zero());
        assertNull(lagging.freeze(input(), config()));
    }

    @Test
    void profileSlotAndLeaseDuplicatesFailClosed() {
        var valid = source(emptyValues(), zero()).freeze(
                input(), config()
        );
        assertNotNull(valid);
        CommandRosterMembership profileDuplicate = membership(
                fixture.PROFILE, valid.slotId()
        );
        CommandRosterActionView action = action(profileDuplicate);
        Map<ProfileId, CommandRosterActionView> actions =
                Map.of(fixture.PROFILE, action);
        assertNull(source(values(
                actions, Set.of(), Map.of(fixture.FAMILY, 1L),
                Set.of(), Map.of(), Set.of()
        ), zero()).freeze(input(), config()));

        CommandRosterMembership slotDuplicate = membership(
                fixture.OTHER_PROFILE, valid.slotId()
        );
        Map<ProfileId, CommandRosterActionView> slotActions =
                Map.of(fixture.OTHER_PROFILE, action(slotDuplicate));
        assertNull(source(values(
                slotActions, Set.of(), Map.of(fixture.FAMILY, 1L),
                Set.of(), Map.of(), Set.of()
        ), zero()).freeze(input(), config()));

        TimedSummonProjectionView timed = new TimedSummonProjectionView(
                lease(profileDuplicate),
                profileDuplicate,
                ownedLifecycle(fixture.PROFILE)
        );
        assertNull(source(values(
                Map.of(), Set.of(), Map.of(), Set.of(),
                Map.of(fixture.PROFILE, timed), Set.of()
        ), zero()).freeze(input(), config()));
    }

    @Test
    void ownerAndPopulationGroupCapacityFailClosed() {
        Map<ProfileId, CompanionLifecycle> fullOwner =
                Map.of(
                        profile(21), ownedLifecycle(profile(21)),
                        profile(22), ownedLifecycle(profile(22)),
                        profile(23), ownedLifecycle(profile(23)),
                        profile(24), ownedLifecycle(profile(24))
                );
        SpawnerTameAndLinkProjectionSource ownerCap = source(
                new SpawnerTameAndLinkProjectionSource.ProjectionValues(
                        Map.of(), fullOwner, Map.of(), Set.of(),
                        Map.of(), Set.of(), Map.of(), Map.of(), Set.of()
                ),
                zero()
        );
        assertNull(ownerCap.freeze(input(), config()));

        PopulationGroupCounts fullGroup =
                new PopulationGroupCounts(4, 3, 0, 0);
        assertNull(source(emptyValues(), fullGroup).freeze(
                input(), config()
        ));
    }

    @Test
    void validProjectionBuildsAllCommittedBucketsAndStableSlot() {
        var projection = source(emptyValues(), zero()).freeze(
                input(), config()
        );

        assertNotNull(projection);
        assertEquals(2, projection.ownerPopulation().counts().size());
        assertEquals(2, projection.groupCounts().size());
        assertEquals(stableSlot(), projection.slotId());
        assertEquals(0L, projection.expectedRosterRevision());
        assertNull(projection.currentRoster());
    }

    @Test
    void draconicStoneLikeConfigRequiresExactTargetRoleAccess()
            throws Exception {
        ItemFeatureRegistry items = itemRegistry();
        PopulationGroupConfigRegistry groups =
                new PopulationGroupConfigRegistry();
        ReplacementFeaturePolicySource policy = role -> rolePolicy(role);

        CommandItemRegistry validCommands = commandRegistry(
                "Tamed_RockDrakeT1"
        );
        SpawnerTameAndLinkConfigSource valid =
                new SpawnerTameAndLinkConfigSource(
                        groups, items, validCommands, policy
                );
        var frozen = valid.freeze(input());
        assertNotNull(frozen);
        assertEquals("Tamed_RockDrakeT1", frozen.targetRoleId());
        assertEquals(
                List.of("HyDragon_Dragon_Horn"),
                frozen.commandAccess().accessItemIds()
        );

        SpawnerTameAndLinkConfigSource denied =
                new SpawnerTameAndLinkConfigSource(
                        groups,
                        items,
                        commandRegistry("Tamed_Hydra"),
                        policy
                );
        assertNull(denied.freeze(input()));
    }

    private SpawnerTameAndLinkProjectionSource source(
            SpawnerTameAndLinkProjectionSource.ProjectionValues values,
            PopulationGroupCounts counts
    ) {
        return new SpawnerTameAndLinkProjectionSource(
                () -> values, bucket -> counts
        );
    }

    private SpawnerTameAndLinkProjectionSource.ProjectionValues emptyValues() {
        return values(
                Map.of(), Set.of(), Map.of(), Set.of(), Map.of(), Set.of()
        );
    }

    private SpawnerTameAndLinkProjectionSource.ProjectionValues values(
            Map<ProfileId, CommandRosterActionView> actions,
            Set<ProfileId> laggingGroups,
            Map<com.alechilles.alecstamework.companion.command.CommandFamilyKey,
                    Long> revisions,
            Set<ProfileId> laggingRosters,
            Map<ProfileId, TimedSummonProjectionView> timed,
            Set<ProfileId> laggingTimed
    ) {
        return new SpawnerTameAndLinkProjectionSource.ProjectionValues(
                Map.of(), Map.of(), Map.of(), laggingGroups,
                actions, laggingRosters, revisions, timed, laggingTimed
        );
    }

    private TameworkSpawnerTameAndLinkEvidenceSource.ConfigSnapshot config() {
        return new TameworkSpawnerTameAndLinkEvidenceSource.ConfigSnapshot(
                "Tamed_RockDrakeT1",
                4,
                2,
                fixture.POLICY_REVISION,
                List.of(fixture.GLOBAL_POLICY, fixture.WORLD_POLICY),
                fixture.FAMILY,
                fixture.ACCESS,
                fixture.TIMED_POLICY
        );
    }

    private CommandRosterMembership membership(
            ProfileId profile,
            CommandRosterSlotId slot
    ) {
        return new CommandRosterMembership(
                slot,
                fixture.FAMILY,
                profile,
                1L,
                null,
                true,
                null,
                fixture.REQUESTED_AT,
                fixture.REQUESTED_AT
        );
    }

    private CommandRosterActionView action(
            CommandRosterMembership membership
    ) {
        return new CommandRosterActionView(
                membership,
                "Tamed_RockDrakeT1",
                1L,
                null,
                ownedLifecycle(membership.profileId())
        );
    }

    private TimedSummonLease lease(CommandRosterMembership membership) {
        return new TimedSummonLease(
                membership.profileId(),
                1L,
                null,
                null,
                null,
                fixture.TIMED_POLICY,
                Set.of(),
                null,
                fixture.REQUESTED_AT,
                fixture.REQUESTED_AT
        );
    }

    private CompanionLifecycle ownedLifecycle(ProfileId profile) {
        return new CompanionLifecycle(
                profile,
                fixture.OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        profile.toString(), fixture.WORLD
                ),
                new LifecycleRevision(1L),
                null,
                fixture.REQUESTED_AT,
                ReconciliationGeneration.INITIAL,
                null,
                fixture.WORLD
        );
    }

    private ProfileId profile(int suffix) {
        return new ProfileId(new UUID(0L, suffix));
    }

    private CommandRosterSlotId stableSlot() {
        return new CommandRosterSlotId(UUID.nameUUIDFromBytes(
                (SLOT_NAMESPACE + fixture.PROFILE)
                        .getBytes(StandardCharsets.UTF_8)
        ));
    }

    private PopulationGroupCounts zero() {
        return new PopulationGroupCounts(0, 0, 0, 0);
    }

    private ItemFeatureRegistry itemRegistry() {
        ItemFeatureConfig.CaptureItemMechanics mechanics =
                new ItemFeatureConfig.CaptureItemMechanics(
                        CaptureChanceMode.GUARANTEED,
                        1,
                        1.0D,
                        0.0D,
                        1.0D,
                        1.0D,
                        0,
                        null,
                        null,
                        CaptureSourceConsumption.RESOLVED_ATTEMPT,
                        CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                        "hydragon:dragon_horn",
                        "HyDragonDragonHorn",
                        true
                );
        ItemFeatureConfig feature = ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .captureTamesTarget(true)
                .captureTamedRoleOverrides(Map.of(
                        "RockDrakeT1", "Tamed_RockDrakeT1"
                ))
                .captureMechanics(mechanics)
                .build();
        SpawnerCaptureMechanicsView view =
                new SpawnerCaptureMechanicsView(
                        "HyDragonDraconicStone",
                        1L,
                        "Draconic_Stone",
                        CaptureChanceMode.GUARANTEED,
                        1,
                        1.0D,
                        0.0D,
                        1.0D,
                        1.0D,
                        0L,
                        null,
                        null,
                        CaptureSourceConsumption.RESOLVED_ATTEMPT,
                        CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                        "hydragon:dragon_horn",
                        "HyDragonDragonHorn",
                        true
                );
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.replaceSpawnerConfigs(
                0L,
                List.of(new ItemFeatureRegistry.CompiledSpawnerConfig(
                        "HyDragonDraconicStone",
                        "Draconic_Stone",
                        feature,
                        view
                ))
        );
        return registry;
    }

    private CommandItemRegistry commandRegistry(String allowedRole)
            throws Exception {
        TwCommandItemConfig config = commandConfig(allowedRole);
        CommandItemRegistry registry = new CommandItemRegistry();
        registry.register(
                "HyDragonDragonHorn",
                "HyDragon_Dragon_Horn",
                config
        );
        return registry;
    }

    private TwCommandItemConfig commandConfig(String allowedRole)
            throws Exception {
        Constructor<TwCommandItemConfig> constructor =
                TwCommandItemConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCommandItemConfig config = constructor.newInstance();
        set(config, "id", "HyDragonDragonHorn");
        set(config, "itemIds", new String[]{"HyDragon_Dragon_Horn"});
        set(config, "commandFamilyId", "hydragon:dragon_horn");
        set(
                config,
                "rosterStorage",
                TwCommandItemConfig.RosterStorage.OwnerCommandFamily
        );
        TwCommandItemConfig.AllowlistRoles roles =
                new TwCommandItemConfig.AllowlistRoles();
        set(roles, "allowlist", new String[]{allowedRole});
        set(config, "allowedRoles", roles);
        return config;
    }

    private ReplacementFeaturePolicySource.RolePolicySnapshot rolePolicy(
            String role
    ) {
        return new ReplacementFeaturePolicySource.RolePolicySnapshot(
                role,
                "HyDragonCompanion",
                Sha256Hash.ofUtf8(role).toString(),
                4,
                2,
                true,
                fixture.TIMED_POLICY,
                false,
                0L,
                List.<RevivalCostItem>of(),
                null
        );
    }

    private SpawnerTameAndLinkIntentFactory.Input input() {
        CaptureAttemptResolution resolution = resolution();
        return new SpawnerTameAndLinkIntentFactory.Input(
                resolution.attemptId().toString(),
                fixture.OWNER.value(),
                "Alec",
                fixture.WORLD,
                2,
                HytaleItemStackTestFixture.stack(
                        "Draconic_Stone", new BsonDocument()
                ),
                null,
                null,
                fixture.PROFILE,
                new NpcAlias(UUID.fromString(fixture.ALIAS)),
                null,
                "RockDrakeT1",
                resolution,
                null
        );
    }

    private CaptureAttemptResolution resolution() {
        return new CaptureAttemptResolution(
                UUID.fromString(
                        "95000000-0000-0000-0000-000000000001"
                ),
                "RockDrakeT1",
                new CaptureAttemptFormula(
                        "HyDragonDraconicStone",
                        1L,
                        CaptureChanceMode.GUARANTEED,
                        1,
                        1.0D,
                        0.0D,
                        1.0D,
                        1.0D,
                        null,
                        0L,
                        0,
                        0.0D,
                        1.0D,
                        0.0D,
                        null,
                        Sha256Hash.ofUtf8("[]"),
                        1L
                ),
                CaptureSourceConsumption.RESOLVED_ATTEMPT,
                CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                CaptureAttemptResolution.Outcome.SUCCESS,
                "capture-success",
                1.0D,
                true,
                0.0D,
                null,
                null
        );
    }

    private void set(Object target, String field, Object value)
            throws ReflectiveOperationException {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
