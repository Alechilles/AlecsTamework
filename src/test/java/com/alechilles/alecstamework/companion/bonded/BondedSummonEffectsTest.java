package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The summon aura, the expiry warning and the expiry fall protection of bonded companions. */
class BondedSummonEffectsTest {
    private static final String ROSTER = "hydragon:horn";
    private static final String DRAGON = "Tamed_Dragon_Fire";

    /** A timer task as the scheduler was given it. */
    private static final class Timer {
        private final Runnable task;
        private final long delayMs;
        private boolean cancelled;

        private Timer(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
        }
    }

    private final UUID owner = UUID.randomUUID();
    private long now = 1_000_000L;
    private final CompanionIndex index = new CompanionIndex(() -> now, (b, a) -> { });
    private final List<Timer> timers = new ArrayList<>();
    /** "effect@keepUntilMs" per played effect, and the profiles whose rider was protected. */
    private final List<String> played = new ArrayList<>();
    private final List<UUID> armed = new ArrayList<>();
    private final BondedCompanionPolicy policy = new BondedCompanionPolicy(7L, ROSTER, "hydragon:fire_dragon",
            Set.of(DRAGON), 0, 0, 600L, 30L, 120L, "Aura", "Fading", null,
            new BondedCompanionPolicy.FeatureFlags(true, true, true, true, true));
    private final BondedSummonEffects effects = new BondedSummonEffects(index::get,
            (rosterId, roleId) -> ROSTER.equals(rosterId) && DRAGON.equals(roleId) ? policy : null,
            new BondedSummonEffects.Bodies() {
                @Override
                public void playEffect(CompanionRecord record, String effectId, long keepUntilMs) {
                    played.add(effectId + "@" + keepUntilMs);
                }

                @Override
                public void armExpiryDismount(CompanionRecord record) {
                    armed.add(record.profileId());
                }
            },
            (task, delayMs) -> {
                Timer timer = new Timer(task, delayMs);
                timers.add(timer);
                return () -> timer.cancelled = true;
            },
            () -> now);

    BondedSummonEffectsTest() {
        index.addAfterUnlockListener(effects::onChanged);
    }

    private CompanionRecord stored() {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), DRAGON,
                        CompanionLocation.stored(StoredReason.BONDED))
                .ownerUuid(owner).rosterId(ROSTER).bonded(true).generation(4).build();
        index.insert(record);
        return index.get(record.profileId());
    }

    /** As a summon's commit: the record goes LIVE at the next generation with a session timer. */
    private CompanionRecord summon(CompanionRecord record, long untilMs) {
        CompanionRecord current = index.get(record.profileId());
        index.update(current.profileId(), current.revision(), b -> b.generation(current.generation() + 1)
                .location(CompanionLocation.live("default", 0, 0, 0)).summonedUntilMs(untilMs));
        return index.get(record.profileId());
    }

    private void store(CompanionRecord record) {
        CompanionRecord current = index.get(record.profileId());
        index.update(current.profileId(), current.revision(),
                b -> b.location(CompanionLocation.stored(StoredReason.BONDED)).summonedUntilMs(0L));
    }

    /** Runs every timer task that was not cancelled, as the timer thread would when they come due. */
    private void runTimers() {
        List<Timer> due = new ArrayList<>(timers);
        timers.clear();
        due.stream().filter(timer -> !timer.cancelled).forEach(timer -> timer.task.run());
    }

    @Test
    void theWarningPlaysFiveSecondsBeforeTheSessionEndsAndLastsUntilItDoes() {
        CompanionRecord record = summon(stored(), now + 600_000L);

        assertEquals(1, timers.size());
        assertEquals(600_000L - BondedSummonEffects.WARNING_LEAD_MS, timers.get(0).delayMs);
        // A position refresh of the live body keeps the timer it has.
        index.update(record.profileId(), record.revision(), b -> b.location(CompanionLocation.live("default", 5, 0, 5)));
        assertEquals(1, timers.size());

        runTimers();

        assertEquals(List.of("Fading@" + (now + 600_000L)), played);
    }

    @Test
    void aCompanionStoredOrSummonedAgainIsNotWarnedByItsOldTimer() {
        CompanionRecord record = summon(stored(), now + 600_000L);
        Timer first = timers.get(0);

        store(record);
        assertTrue(first.cancelled);
        summon(record, now + 900_000L);
        // Even if the cancelled task still ran, it would find another timer on the record.
        first.task.run();

        assertTrue(played.isEmpty());
        assertEquals(2, timers.size());
        assertEquals(900_000L - BondedSummonEffects.WARNING_LEAD_MS, timers.get(1).delayMs);
    }

    @Test
    void companionsAlreadyActiveAtStartAreWarnedAndAnUntimedOneIsNot() {
        CompanionRecord timed = summon(stored(), now + 2_000L);
        summon(stored(), 0L);
        timers.clear();

        effects.rebuild(index);
        // The listener already tracked both; a rebuild does not schedule them twice.
        assertTrue(timers.isEmpty());

        effects.close();
        BondedSummonEffects restarted = new BondedSummonEffects(index::get, (rosterId, roleId) -> policy,
                new BondedSummonEffects.Bodies() {
                    @Override
                    public void playEffect(CompanionRecord record, String effectId, long keepUntilMs) {
                        played.add(record.profileId() + ":" + effectId);
                    }

                    @Override
                    public void armExpiryDismount(CompanionRecord record) {
                    }
                },
                (task, delayMs) -> {
                    timers.add(new Timer(task, delayMs));
                    return () -> { };
                }, () -> now);
        restarted.rebuild(index);

        // Less than the lead is left, so the one timed session is warned without delay.
        assertEquals(1, timers.size());
        assertEquals(0L, timers.get(0).delayMs);
        runTimers();
        assertEquals(List.of(timed.profileId() + ":Fading"), played);
    }

    @Test
    void theAuraPlaysForACompanionBroughtIntoTheWorldAndTheRiderIsProtectedOnlyAtExpiry() {
        CompanionRecord record = stored();
        effects.summoned(record.profileId());
        assertTrue(played.isEmpty());

        summon(record, now + 600_000L);
        effects.summoned(record.profileId());
        assertEquals(List.of("Aura@0"), played);

        // Before the session ends the companion is not about to be removed.
        effects.expiring(record.profileId());
        assertTrue(armed.isEmpty());
        now += 600_000L;
        effects.expiring(record.profileId());
        assertEquals(List.of(record.profileId()), armed);
    }
}
