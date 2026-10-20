package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwItemCostComponent;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.CommandReviveCostPresentation;
import com.alechilles.alecstamework.ui.CommandRosterStatusPresentation;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Command-family roster rows and their Summon and Dismiss state, read from companion records. */
class CommandRosterPanelRecordSourceTest {
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String FAMILY = "hydragon:dragon_horn";
    private static final UUID LIVE_NPC = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final CommandPanelFeaturePresentationSource.ReviveTerms REVIVE_TERMS =
            new CommandPanelFeaturePresentationSource.ReviveTerms(true,
                    List.of(new TwItemCostComponent("Ingredient_Life_Essence", 3)));

    private final CompanionRecord stored = member(1, CompanionLocation.stored(StoredReason.ROSTER), null);
    private final CompanionRecord summoned = member(2, CompanionLocation.live("world-a", 1, 2, 3), LIVE_NPC);
    private final CompanionRecord unloaded = member(3, CompanionLocation.live("world-a", 4, 5, 6),
            UUID.fromString("60000000-0000-0000-0000-000000000003"));
    private final CompanionRecord dead = member(4, CompanionLocation.dead("fall"), null);

    /** Actions resolve a row back to its profile; records and members must name the same row. */
    @Test
    void aLiveMembersRowFollowsItsBodyAndOtherRowsFollowTheirProfile() {
        CommandRosterPanelRecordSource source = source(List.of(summoned, stored), Set.of());

        CommandRosterPanelRecordSource.PanelSnapshot snapshot = source.snapshotFor(OWNER, FAMILY);

        assertEquals(List.of(stored.profileId().toString(), summoned.profileId().toString()),
                snapshot.records().stream().map(record -> record.profileId).toList());
        assertEquals(CommandRosterPanelRecordSource.presentationUuid(new ProfileId(stored.profileId())),
                snapshot.records().get(0).npcUuid);
        assertEquals(LIVE_NPC, snapshot.records().get(1).npcUuid);
        assertEquals(snapshot.members().stream().map(CommandRosterPanelRecordSource.PanelMember::presentationUuid)
                .toList(), snapshot.records().stream().map(record -> record.npcUuid).toList());
    }

    @Test
    void storedMembersCanBeSummonedAndSummonedOnesDismissed() throws Exception {
        List<CompanionRecord> members = List.of(stored, summoned, unloaded, dead);
        Map<UUID, CommandPanelFeaturePresentation> rows = presentations(
                source(members, Set.of(summoned.profileId())), members, () -> null)
                .snapshot(OWNER, "world-a", ownerFamilyConfig());

        CommandRosterStatusPresentation storedRow = rows.get(row(stored)).roster();
        assertEquals(CommandTimedSummoningState.ROSTER_STORED, storedRow.state());
        assertTrue(storedRow.summonEnabled());
        assertFalse(storedRow.dismissVisible());

        CommandRosterStatusPresentation summonedRow = rows.get(LIVE_NPC).roster();
        assertEquals(CommandTimedSummoningState.ACTIVE, summonedRow.state());
        assertTrue(summonedRow.dismissEnabled());
        assertFalse(summonedRow.summonVisible());

        assertEquals(CommandTimedSummoningState.UNLOADED, rows.get(unloaded.currentNpcUuid()).roster().state());
        assertTrue(rows.get(row(dead)).roster().paidRevivalState());
        // With no deployed limit the row still counts the roster's summoned members.
        assertTrue(summonedRow.capUnlimited());
        assertEquals(2, summonedRow.activeCount());
    }

    @Test
    void aFullDeployedGroupDisablesSummon() throws Exception {
        PopulationGroupPolicy dragons = new PopulationGroupPolicy("dragons", PopulationGroupScope.GLOBAL, 0, 1, 1);
        CompanionAdmission.Rules rules = new CompanionAdmission.Rules(0, false, role -> List.of(dragons));

        List<CompanionRecord> members = List.of(stored, summoned);
        CommandRosterStatusPresentation storedRow = presentations(
                source(members, Set.of(summoned.profileId())), members, () -> rules)
                .snapshot(OWNER, "world-a", ownerFamilyConfig()).get(row(stored)).roster();

        assertEquals(1, storedRow.activeCount());
        assertEquals(1, storedRow.activeLimit());
        assertTrue(storedRow.summonVisible());
        assertFalse(storedRow.summonEnabled());
    }

    /** The clock of these presentations reads 1000 ms. */
    @Test
    void aDeadMembersReviveRowFollowsItsCooldownThenItsCost() {
        CompanionRecord cooling = dead.toBuilder().reviveAvailableAtMs(4_000L).build();
        CommandReviveCostPresentation waiting = reviveRow(cooling, item -> 3);
        assertEquals(PaidCommandRevivalQuote.Status.COOLDOWN, waiting.status());
        assertEquals(3_000L, waiting.cooldownRemainingMs());
        assertFalse(waiting.confirmEnabled());

        CompanionRecord due = dead.toBuilder().reviveAvailableAtMs(1_000L).build();
        CommandReviveCostPresentation shortOfItems = reviveRow(due, item -> 2);
        assertEquals(PaidCommandRevivalQuote.Status.INSUFFICIENT_COST, shortOfItems.status());
        assertEquals(List.of(new CommandReviveCostPresentation.CostLine(
                        "Ingredient_Life_Essence", "Ingredient_Life_Essence", null, 2, 3)),
                shortOfItems.costs());

        assertTrue(reviveRow(due, item -> 3).confirmEnabled());
    }

    @Test
    void aLostMemberRecoversForFree() {
        CompanionRecord lost = member(5, CompanionLocation.lost(null), null);

        CommandReviveCostPresentation row = reviveRow(lost, null);

        assertTrue(row.confirmEnabled());
        assertTrue(row.costs().isEmpty());
    }

    private static CommandReviveCostPresentation reviveRow(
            CompanionRecord record, java.util.function.ToIntFunction<String> held) {
        CommandRosterPanelRecordSource source = source(List.of(record), Set.of());
        return presentations(source, List.of(record), () -> null)
                .snapshotForMembers(OWNER, "world-a", FAMILY, source.membersFor(OWNER, FAMILY), held)
                .get(row(record)).revival();
    }

    private static CommandRosterPanelRecordSource source(List<CompanionRecord> members, Set<UUID> loaded) {
        return new CommandRosterPanelRecordSource(
                (owner, family) -> OWNER.equals(owner) && FAMILY.equals(family) ? members : List.of(),
                loaded::contains);
    }

    private static CommandPanelFeaturePresentationSource presentations(
            CommandRosterPanelRecordSource source, List<CompanionRecord> owned,
            java.util.function.Supplier<CompanionAdmission.Rules> rules) {
        return new CommandPanelFeaturePresentationSource(source, role -> REVIVE_TERMS,
                owner -> owned, rules, () -> 1_000L);
    }

    private static UUID row(CompanionRecord record) {
        return CommandRosterPanelRecordSource.presentationUuid(new ProfileId(record.profileId()));
    }

    private static CompanionRecord member(int suffix, CompanionLocation at, UUID npc) {
        return CompanionRecord.builder(
                        UUID.fromString("20000000-0000-0000-0000-" + String.format("%012d", suffix)),
                        "Dragon", at)
                .ownerUuid(OWNER).homeWorld("world-a").currentNpcUuid(npc)
                .rosterId(FAMILY).rosterSlot(-1).build();
    }

    private static TwCommandItemConfig ownerFamilyConfig() throws Exception {
        var constructor = TwCommandItemConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCommandItemConfig config = constructor.newInstance();
        setField(config, "commandFamilyId", FAMILY);
        setField(config, "rosterStorage", TwCommandItemConfig.RosterStorage.OwnerCommandFamily);
        return config;
    }

    private static void setField(TwCommandItemConfig config, String name, Object value) throws Exception {
        Field field = TwCommandItemConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }
}
