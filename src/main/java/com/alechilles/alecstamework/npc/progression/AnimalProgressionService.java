package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.AnimalAgingSettings;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.util.UUID;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * World-thread adapter for lazy animal progression. The owner clock runs without entities or chunks.
 * Component checkpoints travel with normal entity saves and full-state snapshots.
 */
public final class AnimalProgressionService {
    private AnimalProgressionService() { }

    /** Settles earned progress on a detached snapshot before storage or coop residency begins. */
    @Nullable
    public static TameworkLifeStageComponent snapshot(@Nullable Ref<EntityStore> ref,
                                                      @Nullable Store<EntityStore> store) {
        TameworkLifeStageComponent live = state(ref, store);
        if (live == null) return null;
        TameworkLifeStageComponent copy = live.clone();
        advance(copy, ref, store, true);
        return copy;
    }

    /** Read-only virtual game time for breeding and harvest deadlines, including unloaded time. */
    public static long currentTimeMs(@Nullable Ref<EntityStore> ref, @Nullable Store<EntityStore> store) {
        TameworkLifeStageComponent state = state(ref, store);
        if (state == null || !state.isProgressionInitialized()) {
            return BreedingTimeService.resolveCurrentTimeMs(store);
        }
        long elapsed = pendingElapsed(state);
        double rate = BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(store);
        return BreedingTimeService.saturatingAdd(state.getLastProgressionWorldMs(), scaled(elapsed, rate));
    }

    /** Advances a component only; caller owns its ECS write. Unloaded intervals never inherit poor care. */
    static void advance(TameworkLifeStageComponent state, Ref<EntityStore> ref,
                        Store<EntityStore> store, boolean loaded) {
        UUID owner = CompanionNeedsService.resolveOwnerId(ref, store);
        String ownerText = owner == null ? null : owner.toString();
        long current = AnimalProgressionClock.get().current(owner);
        long elapsed = state.isProgressionInitialized() && !state.isStoredProgressionPaused()
                && Objects.equals(state.getProgressionOwnerId(), ownerText) ? pendingElapsed(state) : 0L;
        state.setActiveProgressMs(BreedingTimeService.saturatingAdd(state.getActiveProgressMs(), elapsed));
        long worldNow = BreedingTimeService.resolveCurrentTimeMs(store);
        if (!state.isProgressionInitialized()) {
            state.setLastProgressionWorldMs(worldNow);
        } else {
            state.setLastProgressionWorldMs(BreedingTimeService.saturatingAdd(
                    state.getLastProgressionWorldMs(), scaled(elapsed,
                            BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(store))));
        }
        state.setProgressionInitialized(true);
        state.setProgressionOwnerId(ownerText);
        state.setProgressionClockMs(current);
        state.setStoredProgressionPaused(false);

        String role = CompanionRoleIdResolver.resolveRoleId(ref, store);
        TwBreedingConfig config = TwBreedingConfig.resolveForRole(role);
        AnimalAgingSettings aging = settings(config, role);
        if (aging == null || !aging.isEnabled()) return;
        boolean tamed = com.alechilles.alecstamework.npc.TamedStateResolver.isTamed(ref, store);
        initializeJuvenileClock(state, worldNow, store);
        TameworkNeedsComponent needs = TameworkNeedsComponent.getComponentType() == null ? null
                : store.getComponent(ref, TameworkNeedsComponent.getComponentType());
        double hunger = needs == null ? 100 : needs.getHunger();
        double thirst = needs == null ? 100 : needs.getThirst();
        var needsConfig = CompanionNeedsService.resolveNeedsConfig(ref, store, role, needs);
        if (needs != null && needsConfig != null) {
            var values = needsConfig.getValues();
            hunger = needPercent(hunger, values.getHungerMin(), values.getHungerMax());
            thirst = needPercent(thirst, values.getThirstMin(), values.getThirstMax());
        }
        double growthRate = loaded ? AnimalAgingPolicy.growthRate(hunger, thirst) : 1.0;
        long juvenileRemaining = state.isGrowthScalingEnabled()
                ? Math.max(0L, state.getAdultAtMs() - state.getLifecycleNowMs()) : 0L;
        long juvenileElapsed = Math.min(elapsed, (long) Math.ceil(juvenileRemaining / growthRate));
        state.setLifecycleNowMs(BreedingTimeService.saturatingAdd(
                state.getLifecycleNowMs(), scaled(elapsed, growthRate)));
        boolean newlyInitialized = !state.isAgingInitialized();
        if (newlyInitialized) {
            // Existing/wild adults begin before prime. No retroactive age is charged on upgrade.
            state.setAgingInitialized(tamed);
            state.setAgeProgressMs(0);
        }
        // Wild juveniles still grow, but wild adults cannot earn prime age before taming.
        long adultElapsed = newlyInitialized || !tamed ? 0 : Math.max(0, elapsed - juvenileElapsed);
        AnimalAgingPolicy.Progress result = AnimalAgingPolicy.advance(aging, state.getAgeProgressMs(),
                adultElapsed, loaded, hunger, thirst);
        state.setAgeProgressMs(result.ageProgressMs());
    }

    /** Existing juvenile deadlines are converted once; new aging-enabled offspring use real durations. */
    private static void initializeJuvenileClock(TameworkLifeStageComponent state, long worldNow,
                                               Store<EntityStore> store) {
        if (state.isJuvenileClockInitialized()) return;
        double rate = BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(store);
        if (!(rate > 0) || !Double.isFinite(rate)) rate = 1;
        if (state.isGrowthScalingEnabled()) {
            state.setBornAtMs(rebase(state.getBornAtMs(), worldNow, rate));
            state.setAdolescentAtMs(rebase(state.getAdolescentAtMs(), worldNow, rate));
            state.setAdultAtMs(rebase(state.getAdultAtMs(), worldNow, rate));
            state.setFullyGrownAtMs(rebase(state.getFullyGrownAtMs(), worldNow, rate));
        }
        state.setLifecycleNowMs(worldNow);
        state.setJuvenileClockInitialized(true);
    }

    private static long rebase(long timestamp, long now, double rate) {
        if (timestamp == 0) return 0;
        long result = BreedingTimeService.saturatingAdd(now, (long) ((timestamp - (double) now) / rate));
        return result == 0 ? -1 : result;
    }

    static long lifeTime(TameworkLifeStageComponent state, Store<EntityStore> store) {
        return state.isJuvenileClockInitialized() ? state.getLifecycleNowMs()
                : state.isProgressionInitialized() ? state.getLastProgressionWorldMs()
                : BreedingTimeService.resolveCurrentTimeMs(store);
    }

    @Nullable
    public static AnimalAgingPolicy.Progress aging(@Nullable Ref<EntityStore> ref,
                                                   @Nullable Store<EntityStore> store,
                                                   @Nullable String role) {
        TameworkLifeStageComponent state = state(ref, store);
        TwBreedingConfig config = TwBreedingConfig.resolveForRole(role);
        if (state == null || config == null || !config.resolveAging(role).isEnabled()) return null;
        return AnimalAgingPolicy.advance(settings(config, role), state.getAgeProgressMs(), 0, false, 100, 100);
    }

    /** Lifecycle yield modifies expected output, preserving unbiased fractional item rounding. */
    public static double slaughterMultiplier(Ref<EntityStore> ref, Store<EntityStore> store, String role) {
        if (!CompanionLifeStageService.isAdult(ref, store, role)) return 0;
        AnimalAgingPolicy.Progress value = aging(ref, store, role);
        return value == null ? 1 : value.dead() ? 0 : value.yieldMultiplier();
    }

    /** Freeze a detached capture snapshot; never mutate a component still owned by ECS. */
    public static void pauseStored(@Nullable TameworkLifeStageComponent state) {
        if (state != null) state.setStoredProgressionPaused(true);
    }

    /** UI-neutral lifecycle view; saved active animals advance lazily, captured animals remain frozen. */
    @Nullable
    public static Presentation presentation(@Nullable TameworkLifeStageComponent state,
                                            @Nullable String role, boolean captured) {
        return presentation(state, role, captured, false, 100, 100);
    }

    /** Estimates loaded-animal countdowns at their current care rate. */
    @Nullable
    public static Presentation loadedPresentation(@Nullable TameworkLifeStageComponent state,
                                                   @Nullable String role, double hunger, double thirst) {
        return presentation(state, role, false, true, hunger, thirst);
    }

    private static Presentation presentation(TameworkLifeStageComponent state, String role,
                                             boolean captured, boolean loaded, double hunger, double thirst) {
        TwBreedingConfig config = TwBreedingConfig.resolveForRole(role);
        AnimalAgingSettings settings = settings(config, role);
        if (state == null || settings == null || !settings.isEnabled()) return null;
        long elapsed = captured ? 0 : pendingElapsed(state);
        double growthRate = loaded ? AnimalAgingPolicy.growthRate(hunger, thirst) : 1;
        long lifeNow = BreedingTimeService.saturatingAdd(state.getLifecycleNowMs(), scaled(elapsed, growthRate));
        if (state.isGrowthScalingEnabled() && state.isJuvenileClockInitialized()
                && lifeNow < state.getAdultAtMs()) {
            boolean baby = lifeNow < state.getAdolescentAtMs();
            long until = baby ? state.getAdolescentAtMs() : state.getAdultAtMs();
            return new Presentation(baby ? "Baby" : "Adolescent", false, captured, false,
                    (long) Math.ceil(Math.max(0, until - lifeNow) / growthRate), 0,
                    stageProgress(lifeNow, baby ? state.getBornAtMs() : state.getAdolescentAtMs(), until));
        }
        long juvenileRemaining = state.isGrowthScalingEnabled() && state.isJuvenileClockInitialized()
                ? Math.max(0, state.getAdultAtMs() - state.getLifecycleNowMs()) : 0;
        AnimalAgingPolicy.Progress age = AnimalAgingPolicy.advance(settings, state.getAgeProgressMs(),
                Math.max(0, elapsed - (long) Math.ceil(juvenileRemaining / growthRate)), loaded, hunger, thirst);
        boolean prime = age.stage() == AnimalAgingPolicy.Stage.PRIME;
        boolean frozen = captured || settings.getMode() == AnimalAgingSettings.LifecycleMode.OFF
                || prime && settings.getMode() == AnimalAgingSettings.LifecycleMode.FREEZE_AT_PRIME;
        boolean death = age.stage() == AnimalAgingPolicy.Stage.SENIOR && settings.isOldAgeDeathEnabled();
        String stage = switch (age.stage()) {
            case ADULT -> "Adult"; case PRIME -> "Prime"; case SENIOR -> "Senior";
        };
        long remaining = age.remainingStageMs();
        if (remaining != Long.MAX_VALUE && loaded) {
            remaining = (long) Math.ceil(remaining / AnimalAgingPolicy.agingRate(
                    settings, age.ageProgressMs(), true, hunger, thirst));
        }
        double stageStart = age.stage() == AnimalAgingPolicy.Stage.ADULT ? 0
                : age.stage() == AnimalAgingPolicy.Stage.PRIME ? settings.getAdultToPrimeMs()
                : (double) settings.getAdultToPrimeMs() + settings.getPrimeMs();
        double stageDuration = switch (age.stage()) {
            case ADULT -> settings.getAdultToPrimeMs();
            case PRIME -> settings.getPrimeMs();
            case SENIOR -> settings.getSeniorMs();
        };
        double progress = stageProgress(age.ageProgressMs(), stageStart, stageStart + stageDuration);
        return new Presentation(stage, prime, frozen, death, remaining, age.yieldMultiplier(), progress);
    }

    /** True when loaded-world actions must wait for ordinary natural-death resolution. */
    public static boolean deathDue(@Nullable TameworkLifeStageComponent state, @Nullable String role) {
        Presentation view = presentation(state, role, false);
        return view != null && view.nextDeath() && view.remainingMs() == 0L;
    }

    public record Presentation(String stage, boolean prime, boolean frozen, boolean nextDeath,
                               long remainingMs, double yieldMultiplier, double stageProgress) {
        public Presentation(String stage, boolean prime, boolean frozen, boolean nextDeath,
                            long remainingMs, double yieldMultiplier) {
            this(stage, prime, frozen, nextDeath, remainingMs, yieldMultiplier, 0);
        }
    }

    private static double stageProgress(double now, double start, double end) {
        return end <= start ? 1 : Math.clamp((now - start) / (end - start), 0, 1);
    }

    private static AnimalAgingSettings settings(@Nullable TwBreedingConfig config, @Nullable String role) {
        if (config == null) return null;
        TameworkRuntimeSettings runtime = TameworkRuntimeSettings.current();
        return config.resolveAging(role).withRuntimePolicy(
                AnimalAgingSettings.LifecycleMode.valueOf(runtime.animalAgingMode().name()),
                runtime.animalOldAgeDeathEnabled());
    }

    /** Real active time follows the animal through roaming/coops/trade and pauses in captured storage. */
    public static long activeTimeMs(@Nullable TameworkLifeStageComponent state) {
        return state == null ? 0 : BreedingTimeService.saturatingAdd(state.getActiveProgressMs(), pendingElapsed(state));
    }

    private static long pendingElapsed(TameworkLifeStageComponent state) {
        if (!state.isProgressionInitialized() || state.isStoredProgressionPaused()) return 0;
        UUID owner = null;
        if (state.getProgressionOwnerId() != null) {
            try { owner = UUID.fromString(state.getProgressionOwnerId()); }
            catch (IllegalArgumentException ignored) { return 0; }
        }
        return Math.max(0, AnimalProgressionClock.get().current(owner) - state.getProgressionClockMs());
    }

    private static long scaled(long elapsed, double rate) {
        return elapsed <= 0 || !Double.isFinite(rate) || rate <= 0 ? 0
                : (long) Math.min(Long.MAX_VALUE, elapsed * rate);
    }

    private static double needPercent(double value, double min, double max) {
        return max > min ? Math.max(0, Math.min(100, (value - min) * 100 / (max - min))) : 100;
    }

    @Nullable
    private static TameworkLifeStageComponent state(@Nullable Ref<EntityStore> ref,
                                                    @Nullable Store<EntityStore> store) {
        return ref == null || !ref.isValid() || store == null || TameworkLifeStageComponent.getComponentType() == null
                ? null : store.getComponent(ref, TameworkLifeStageComponent.getComponentType());
    }
}
