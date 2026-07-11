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
        super("gethappiness", "Get happiness of the NPC you are looking at.");
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
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        HappinessSnapshot snapshot = resolveHappinessSnapshot(candidate.ref, store);
        if (snapshot == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "NPC " + candidate.npcUuid + " has no tracked happiness state."
            ));
            return;
        }

        BreedingSnapshot breeding = resolveBreedingSnapshot(candidate.ref, store, snapshot.value());
        commandContext.sender().sendMessage(Message.raw(buildMessage(candidate.npcUuid, snapshot, breeding)));
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

    private static String buildMessage(@Nonnull UUID npcUuid,
                                       @Nonnull HappinessSnapshot happiness,
                                       @Nonnull BreedingSnapshot breeding) {
        StringBuilder message = new StringBuilder();
        message.append("Happiness for NPC ")
                .append(npcUuid)
                .append(": ")
                .append(formatDouble(happiness.value()))
                .append(" (source=")
                .append(happiness.source());
        if (happiness.configId() != null) {
            message.append(", config=").append(happiness.configId());
        }
        if (happiness.lastUpdateMs() > 0L) {
            message.append(", lastUpdateMs=").append(happiness.lastUpdateMs());
        }
        message.append(", base=").append(formatDouble(happiness.baseSetpoint()));
        message.append(", target=").append(formatDouble(happiness.target()));
        if (!happiness.modifiers().isEmpty()) {
            message.append(", modifiers=").append(formatModifiers(happiness.modifiers()));
        } else {
            message.append(", modifiers=[]");
        }
        message.append(")");

        if (!breeding.hasComponent()) {
            message.append(". Breeding component: none.");
            return message.toString();
        }

        message.append(". Breeding: readyFlag=").append(breeding.readyFlag());
        message.append(", cooldownActive=").append(breeding.cooldownActive());
        if (breeding.cooldownUntilMs() != 0L) {
            message.append(", cooldownUntilMs=").append(breeding.cooldownUntilMs());
        }
        if (breeding.cooldownActive()) {
            message.append(", cooldownRemainingMs=").append(breeding.cooldownRemainingMs());
        }
        message.append(", readyNow=").append(breeding.readyFlag() && !breeding.cooldownActive());
        if (breeding.configId() != null) {
            message.append(", config=").append(breeding.configId());
        }
        message.append(", fertilityOffspringFactor=").append(formatDouble(breeding.fertilityMultiplier()));
        message.append(", cooldownTraitFactor=").append(formatDouble(breeding.cooldownMultiplier()));
        message.append(", timerBasis=").append(breeding.timerBasis());
        message.append(", gameSecPerRealSecCurrent=").append(formatDouble(breeding.rateCurrent()));
        message.append(", gameSecPerRealSecBaseline=").append(formatDouble(breeding.rateBaseline()));
        if (breeding.cooldownActive()) {
            message.append(", cooldownRemainingRealSec~=").append(formatDouble(breeding.cooldownRemainingRealSeconds()));
        }
        message.append(", effective=").append(formatDouble(breeding.effectiveHappiness()));
        if (breeding.threshold() != null) {
            message.append(", threshold=").append(formatDouble(breeding.threshold()));
            message.append(", eligible=").append(Boolean.TRUE.equals(breeding.eligible()));
        } else {
            message.append(", threshold=n/a, eligible=n/a");
        }
        message.append(".");
        return message.toString();
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
