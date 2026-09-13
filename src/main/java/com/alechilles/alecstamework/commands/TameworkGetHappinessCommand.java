package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.BreedingEligibilityService;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.alechilles.alecstamework.npc.progression.HappinessConfigResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to display shared happiness status for the targeted NPC.
 */
public final class TameworkGetHappinessCommand extends AbstractPlayerCommand {
    private static final String FERTILITY_MULTIPLIER_KEY = "FertilityMultiplier";
    private static final String BREED_COOLDOWN_MULTIPLIER_KEY = "BreedCooldownMultiplier";

    public TameworkGetHappinessCommand() {
        super("happiness", "server.tamework.commands.getHappiness.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getHappiness.no.npc.found.in.view"));
            return;
        }

        HappinessSnapshot snapshot = resolveHappinessSnapshot(candidate.ref, store);
        if (snapshot == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getHappiness.npc.has.no.tracked.happiness.state").param("0", String.valueOf(candidate.npcUuid)));
            return;
        }

        BreedingSnapshot breeding = resolveBreedingSnapshot(candidate.ref, store, snapshot.value());
        commandContext.sender().sendMessage(buildLocalizedMessage(candidate.npcUuid, snapshot, breeding));
    }

    @Nullable
    private static HappinessSnapshot resolveHappinessSnapshot(@Nonnull Ref<EntityStore> npcRef,
                                                              @Nonnull Store<EntityStore> store) {
        HappinessSnapshot sourceSnapshot = resolveSnapshotFromComponents(npcRef, store);
        CompanionHappinessService.HappinessSnapshot equilibriumSnapshot =
                CompanionHappinessService.resolveSnapshot(npcRef, store);
        if (sourceSnapshot == null && equilibriumSnapshot == null) {
            return null;
        }

        double value = sourceSnapshot != null
                ? sourceSnapshot.value()
                : equilibriumSnapshot.value();
        String configId = sourceSnapshot != null ? sourceSnapshot.configId() : null;
        long lastUpdateMs = sourceSnapshot != null ? sourceSnapshot.lastUpdateMs() : 0L;
        String source = sourceSnapshot != null ? sourceSnapshot.source() : "computed";
        double baseSetpoint = equilibriumSnapshot != null ? equilibriumSnapshot.baseSetpoint() : value;
        double target = equilibriumSnapshot != null ? equilibriumSnapshot.target() : value;
        List<CompanionHappinessModifierService.ModifierEntry> modifiers = equilibriumSnapshot != null
                ? equilibriumSnapshot.modifiers()
                : List.of();
        return new HappinessSnapshot(
                value,
                configId,
                lastUpdateMs,
                source,
                baseSetpoint,
                target,
                modifiers
        );
    }

    private static BreedingSnapshot resolveBreedingSnapshot(@Nonnull Ref<EntityStore> npcRef,
                                                            @Nonnull Store<EntityStore> store,
                                                            double baseHappiness) {
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return BreedingSnapshot.empty();
        }

        TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
        if (breeding == null) {
            return BreedingSnapshot.empty();
        }

        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        Double threshold = config != null ? config.resolveHappiness(roleId).getThreshold() : null;
        double fertilityMultiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                FERTILITY_MULTIPLIER_KEY,
                1.0
        );
        double effective = BreedingEligibilityService.resolveEffectiveHappiness(baseHappiness, 1.0, null);
        Boolean eligible = threshold != null
                ? BreedingEligibilityService.isEligible(effective, threshold)
                : null;
        TwBreedingConfig.TimerBasis timerBasis = config != null
                ? config.resolveTiming(roleId).getTimerBasis()
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
        double rateCurrent = BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(store);
        double rateBaseline = BreedingTimeService.resolveBaselineGameSecondsPerRealSecond(store);
        double cooldownMultiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                BREED_COOLDOWN_MULTIPLIER_KEY,
                1.0
        );
        String configId = normalizeBlank(breeding.getConfigId());
        if (configId == null && config != null) {
            configId = normalizeBlank(config.getId());
        }
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        boolean cooldownActive = breeding.isCooldownActive(now);
        long cooldownUntilMs = breeding.getCooldownUntilMs();
        long cooldownRemainingMs = BreedingTimeService.remainingDurationMs(cooldownUntilMs, now);
        double cooldownRemainingRealSeconds = resolveApproximateRealSeconds(cooldownRemainingMs, rateCurrent);
        return new BreedingSnapshot(
                true,
                breeding.isReady(),
                cooldownActive,
                cooldownRemainingMs,
                cooldownUntilMs,
                configId,
                threshold,
                fertilityMultiplier,
                cooldownMultiplier,
                timerBasis,
                rateCurrent,
                rateBaseline,
                cooldownRemainingRealSeconds,
                effective,
                eligible
        );
    }

    @Nonnull
    private static Message buildLocalizedMessage(@Nonnull UUID npcUuid,
                                                 @Nonnull HappinessSnapshot happiness,
                                                 @Nonnull BreedingSnapshot breeding) {
        String configId = happiness.configId() != null ? happiness.configId() : "-";
        String modifiers = formatModifiers(happiness.modifiers());
        if (!breeding.hasComponent()) {
            return Message.translation("server.tamework.commands.getHappiness.result.noBreeding")
                    .param("0", String.valueOf(npcUuid))
                    .param("1", formatDouble(happiness.value()))
                    .param("2", happiness.source())
                    .param("3", configId)
                    .param("4", String.valueOf(happiness.lastUpdateMs()))
                    .param("5", formatDouble(happiness.baseSetpoint()))
                    .param("6", formatDouble(happiness.target()))
                    .param("7", modifiers);
        }
        return Message.translation("server.tamework.commands.getHappiness.result")
                .param("0", String.valueOf(npcUuid))
                .param("1", formatDouble(happiness.value()))
                .param("2", happiness.source())
                .param("3", configId)
                .param("4", String.valueOf(happiness.lastUpdateMs()))
                .param("5", formatDouble(happiness.baseSetpoint()))
                .param("6", formatDouble(happiness.target()))
                .param("7", modifiers)
                .param("8", String.valueOf(breeding.readyFlag()))
                .param("9", String.valueOf(breeding.cooldownActive()))
                .param("10", String.valueOf(breeding.cooldownUntilMs()))
                .param("11", String.valueOf(breeding.cooldownRemainingMs()))
                .param("12", String.valueOf(breeding.readyFlag() && !breeding.cooldownActive()))
                .param("13", breeding.configId() != null ? breeding.configId() : "-")
                .param("14", formatDouble(breeding.fertilityMultiplier()))
                .param("15", formatDouble(breeding.cooldownMultiplier()))
                .param("16", breeding.timerBasis().toConfigValue())
                .param("17", formatDouble(breeding.rateCurrent()))
                .param("18", formatDouble(breeding.rateBaseline()))
                .param("19", formatDouble(breeding.cooldownRemainingRealSeconds()))
                .param("20", formatDouble(breeding.effectiveHappiness()))
                .param("21", breeding.threshold() != null ? formatDouble(breeding.threshold()) : "-")
                .param("22", breeding.eligible() != null ? String.valueOf(breeding.eligible()) : "-");
    }

    @Nonnull
    private static String formatModifiers(@Nonnull List<CompanionHappinessModifierService.ModifierEntry> modifiers) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (CompanionHappinessModifierService.ModifierEntry modifier : modifiers) {
            if (modifier == null || !Double.isFinite(modifier.value())) {
                continue;
            }
            if (!first) {
                builder.append("; ");
            }
            first = false;
            builder.append(modifier.label())
                    .append("=")
                    .append(String.format(Locale.ROOT, "%+.2f", modifier.value()));
        }
        builder.append("]");
        return builder.toString();
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static double resolveApproximateRealSeconds(long gameDurationMs, double gameSecondsPerRealSecond) {
        if (gameDurationMs <= 0L || !Double.isFinite(gameSecondsPerRealSecond) || gameSecondsPerRealSecond <= 0.0) {
            return 0.0;
        }
        return (double) gameDurationMs / (gameSecondsPerRealSecond * 1000.0);
    }

    @Nullable
    private static HappinessSnapshot resolveSnapshotFromComponents(@Nonnull Ref<EntityStore> npcRef,
                                                                   @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happiness = null;
        if (happinessType != null) {
            happiness = store.getComponent(npcRef, happinessType);
            if (happiness != null && Double.isFinite(happiness.getValue())) {
                return new HappinessSnapshot(
                        happiness.getValue(),
                        normalizeBlank(happiness.getConfigId()),
                        happiness.getLastUpdateMs(),
                        "shared",
                        happiness.getValue(),
                        happiness.getValue(),
                        List.of()
                );
            }
        }
        if (!HappinessConfigResolver.isRuntimeEnabled(HappinessConfigResolver.resolveConfig(npcRef, store, happiness))) {
            return null;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return null;
        }
        TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
        if (breeding == null || !Double.isFinite(breeding.getHappiness())) {
            return null;
        }
        return new HappinessSnapshot(
                breeding.getHappiness(),
                normalizeBlank(breeding.getConfigId()),
                breeding.getLastHappinessUpdateMs(),
                "breeding-legacy",
                breeding.getHappiness(),
                breeding.getHappiness(),
                List.of()
        );
    }

    private record HappinessSnapshot(double value,
                                     @Nullable String configId,
                                     long lastUpdateMs,
                                     String source,
                                     double baseSetpoint,
                                     double target,
                                     List<CompanionHappinessModifierService.ModifierEntry> modifiers) {
    }

    private record BreedingSnapshot(boolean hasComponent,
                                    boolean readyFlag,
                                    boolean cooldownActive,
                                    long cooldownRemainingMs,
                                    long cooldownUntilMs,
                                    @Nullable String configId,
                                    @Nullable Double threshold,
                                    double fertilityMultiplier,
                                    double cooldownMultiplier,
                                    TwBreedingConfig.TimerBasis timerBasis,
                                    double rateCurrent,
                                    double rateBaseline,
                                    double cooldownRemainingRealSeconds,
                                    double effectiveHappiness,
                                    @Nullable Boolean eligible) {
        private static BreedingSnapshot empty() {
            return new BreedingSnapshot(
                    false,
                    false,
                    false,
                    0L,
                    0L,
                    null,
                    null,
                    1.0,
                    1.0,
                    TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    null
            );
        }
    }
}
