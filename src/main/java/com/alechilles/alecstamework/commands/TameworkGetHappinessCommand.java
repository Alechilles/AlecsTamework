package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.BreedingEligibilityService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to display shared happiness status for the targeted NPC.
 */
public final class TameworkGetHappinessCommand extends AbstractPlayerCommand {
    private static final String FERTILITY_MULTIPLIER_KEY = "FertilityMultiplier";

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
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType != null) {
            TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
            if (happiness != null && Double.isFinite(happiness.getValue())) {
                return new HappinessSnapshot(
                        happiness.getValue(),
                        normalizeBlank(happiness.getConfigId()),
                        happiness.getLastUpdateMs(),
                        "shared"
                );
            }
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
                "breeding-legacy"
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
        Double threshold = config != null ? config.getHappiness().getThreshold() : null;
        double fertilityMultiplier = TraitModifierService.resolveMultiplier(
                npcRef,
                store,
                FERTILITY_MULTIPLIER_KEY,
                1.0
        );
        double effective = BreedingEligibilityService.resolveEffectiveHappiness(baseHappiness, fertilityMultiplier, null);
        Boolean eligible = threshold != null
                ? BreedingEligibilityService.isEligible(effective, threshold)
                : null;
        String configId = normalizeBlank(breeding.getConfigId());
        if (configId == null && config != null) {
            configId = normalizeBlank(config.getId());
        }
        return new BreedingSnapshot(true, breeding.isReady(), configId, threshold, fertilityMultiplier, effective, eligible);
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
        message.append(")");

        if (!breeding.hasComponent()) {
            message.append(". Breeding component: none.");
            return message.toString();
        }

        message.append(". Breeding: readyFlag=").append(breeding.readyFlag());
        if (breeding.configId() != null) {
            message.append(", config=").append(breeding.configId());
        }
        message.append(", fertilityMultiplier=").append(formatDouble(breeding.fertilityMultiplier()));
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

    private record HappinessSnapshot(double value, @Nullable String configId, long lastUpdateMs, String source) {
    }

    private record BreedingSnapshot(boolean hasComponent,
                                    boolean readyFlag,
                                    @Nullable String configId,
                                    @Nullable Double threshold,
                                    double fertilityMultiplier,
                                    double effectiveHappiness,
                                    @Nullable Boolean eligible) {
        private static BreedingSnapshot empty() {
            return new BreedingSnapshot(false, false, null, null, 1.0, 0.0, null);
        }
    }
}
