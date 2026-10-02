package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
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
    /** "owner:name:seconds:style" per expiry notice sent to an owner. */
    private final List<String> notices = new ArrayList<>();
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
                public void notifyExpiry(UUID ownerUuid, String companionName,
                                         BondedCompanionExpiryWarningSchedule.Warning warning) {
                    notices.add(ownerUuid + ":" + companionName + ":" + warning.secondsRemaining()
                            + ":" + warning.style());
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
        due.stream().filter(timer -> !timer.cancelled).forEach(timer -> {
            now += timer.delayMs;
            timer.task.run();
        });
    }

    /** Lets the clock reach each pending timer in turn until none is left. */
    private void runUntilIdle() {
        while (timers.stream().anyMatch(timer -> !timer.cancelled)) {
            runTimers();
        }
    }

    private String notice(String name, int seconds, NotificationStyle style) {
        return owner + ":" + name + ":" + seconds + ":" + style;
    }

    @Test
    void theWarningPlaysFiveSecondsBeforeTheSessionEndsAndLastsUntilItDoes() {
        long untilMs = now + 600_000L;
        CompanionRecord record = summon(stored(), untilMs);

        assertEquals(1, timers.size());
        // A position refresh of the live body keeps the timer it has.
        index.update(record.profileId(), record.revision(), b -> b.location(CompanionLocation.live("default", 5, 0, 5)));
        assertEquals(1, timers.size());

        while (now < untilMs - BondedSummonEffects.WARNING_LEAD_MS) {
            assertTrue(played.isEmpty());
            runTimers();
        }

        assertEquals(untilMs - BondedSummonEffects.WARNING_LEAD_MS, now);
        assertEquals(List.of("Fading@" + untilMs), played);
        runUntilIdle();
        assertEquals(List.of("Fading@" + untilMs), played);
    }

    @Test
    void theOwnerIsToldAtEachThresholdOfASessionLongerThanAMinute() {
        summon(stored(), now + 600_000L);

        assertEquals(1, timers.size());
        assertEquals(540_000L, timers.get(0).delayMs);
        runUntilIdle();

        assertEquals(List.of(
                notice(DRAGON, 60, NotificationStyle.Warning), notice(DRAGON, 30, NotificationStyle.Warning),
                notice(DRAGON, 10, NotificationStyle.Warning), notice(DRAGON, 5, NotificationStyle.Danger),
                notice(DRAGON, 4, NotificationStyle.Danger), notice(DRAGON, 3, NotificationStyle.Danger),
                notice(DRAGON, 2, NotificationStyle.Danger), notice(DRAGON, 1, NotificationStyle.Danger)), notices);
    }

    @Test
    void aTwentySecondSessionStartsAtTenSecondsAndUsesTheRecordsName() {
        CompanionRecord record = stored();
        index.update(record.profileId(), record.revision(), b -> b.displayName("Ember"));
        summon(record, now + 20_000L);

        assertEquals(10_000L, timers.get(0).delayMs);
        runUntilIdle();

        assertEquals(List.of(
                notice("Ember", 10, NotificationStyle.Warning), notice("Ember", 5, NotificationStyle.Danger),
                notice("Ember", 4, NotificationStyle.Danger), notice("Ember", 3, NotificationStyle.Danger),
                notice("Ember", 2, NotificationStyle.Danger), notice("Ember", 1, NotificationStyle.Danger)), notices);
    }

    @Test
    void storingTheCompanionMidWayCancelsTheRestOfItsNotices() {
        CompanionRecord record = summon(stored(), now + 600_000L);
        runTimers();
        runTimers();
        assertEquals(2, notices.size());
        Timer pending = timers.get(0);

        store(record);

        assertTrue(pending.cancelled);
        // Even if the cancelled task still ran, it would find the companion stored and arm nothing.
        pending.task.run();
        assertEquals(2, notices.size());
        assertTrue(played.isEmpty());
        assertEquals(1, timers.size());
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
        assertTrue(notices.isEmpty());
        assertEquals(2, timers.size());
        assertEquals(840_000L, timers.get(1).delayMs);
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
                    public void notifyExpiry(UUID ownerUuid, String companionName,
                                             BondedCompanionExpiryWarningSchedule.Warning warning) {
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
