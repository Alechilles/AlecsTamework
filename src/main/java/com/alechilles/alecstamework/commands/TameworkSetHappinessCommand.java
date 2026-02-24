package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
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
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to set shared companion happiness for the targeted NPC.
 */
public final class TameworkSetHappinessCommand extends AbstractPlayerCommand {
    public TameworkSetHappinessCommand() {
        super("sethappiness", "Set happiness of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Double requested = parseRequestedValue(commandContext);
        if (requested == null) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw sethappiness <value>"));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkHappinessComponent happiness = happinessType != null
                ? store.getComponent(candidate.ref, happinessType)
                : null;
        TameworkBreedingComponent breeding = breedingType != null
                ? store.getComponent(candidate.ref, breedingType)
                : null;

        if (happinessType == null && breedingType == null) {
            commandContext.sender().sendMessage(Message.raw("Happiness and breeding components are not available."));
            return;
        }
        if (happiness == null && breeding == null && happinessType == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "NPC " + candidate.npcUuid + " has no tracked happiness or breeding state."
            ));
            return;
        }

        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(candidate.ref, store, happiness);
        TwBreedingConfig breedingConfig = BreedingConfigResolver.resolveConfig(candidate.ref, store, breeding);
        ClampRules clampRules = resolveClampRules(happinessConfig, breedingConfig);
        double clamped = clampRules != null
                ? clamp(requested, clampRules.min(), clampRules.max())
                : requested;

        long now = System.currentTimeMillis();
        if (happinessType != null) {
            TameworkHappinessComponent next = happiness == null
                    ? new TameworkHappinessComponent(resolveHappinessConfigId(happinessConfig), clamped, now)
                    : happiness;
            next.setValue(clamped);
            next.setLastUpdateMs(now);
            if ((next.getConfigId() == null || next.getConfigId().isBlank()) && happinessConfig != null) {
                next.setConfigId(happinessConfig.getId());
            }
            store.putComponent(candidate.ref, happinessType, next);
        }

        Boolean ready = null;
        if (breedingType != null && breeding != null) {
            breeding.setHappiness(clamped);
            breeding.setLastHappinessUpdateMs(now);
            if ((breeding.getConfigId() == null || breeding.getConfigId().isBlank())
                    && breedingConfig != null
                    && breedingConfig.getId() != null
                    && !breedingConfig.getId().isBlank()) {
                breeding.setConfigId(breedingConfig.getId());
            }
            if (breedingConfig != null) {
                ready = clamped >= breedingConfig.getHappiness().getThreshold();
                breeding.setReady(ready);
            }
            store.putComponent(candidate.ref, breedingType, breeding);
        }

        commandContext.sender().sendMessage(Message.raw(buildResultMessage(
                candidate.npcUuid.toString(),
                requested,
                clamped,
                clampRules,
                ready
        )));
    }

    @Nullable
    private static Double parseRequestedValue(@Nonnull CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(tokens[2]);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static String resolveHappinessConfigId(@Nullable TwHappinessConfig config) {
        if (config == null || config.getId() == null || config.getId().isBlank()) {
            return null;
        }
        return config.getId();
    }

    @Nullable
    private static ClampRules resolveClampRules(@Nullable TwHappinessConfig happinessConfig,
                                                @Nullable TwBreedingConfig breedingConfig) {
        if (happinessConfig != null && happinessConfig.isEnabled()) {
            double min = happinessConfig.getValues().getMin();
            double max = happinessConfig.getValues().getMax();
            return normalizeRange(min, max);
        }
        if (breedingConfig != null && breedingConfig.isEnabled()) {
            double min = breedingConfig.getHappiness().getMin();
            double max = breedingConfig.getHappiness().getMax();
            return normalizeRange(min, max);
        }
        return null;
    }

    @Nullable
    private static ClampRules normalizeRange(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return null;
        }
        if (max < min) {
            return new ClampRules(max, min);
        }
        return new ClampRules(min, max);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static String buildResultMessage(String npcUuid,
                                             double requested,
                                             double applied,
                                             @Nullable ClampRules clampRules,
                                             @Nullable Boolean ready) {
        StringBuilder message = new StringBuilder();
        message.append("Set happiness for NPC ")
                .append(npcUuid)
                .append(": requested=")
                .append(formatDouble(requested))
                .append(", applied=")
                .append(formatDouble(applied));
        if (clampRules != null) {
            message.append(", clamp=[")
                    .append(formatDouble(clampRules.min()))
                    .append(", ")
                    .append(formatDouble(clampRules.max()))
                    .append("]");
        } else {
            message.append(", clamp=none");
        }
        if (ready != null) {
            message.append(", ready=").append(ready);
        }
        message.append(".");
        return message.toString();
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record ClampRules(double min, double max) {
    }
}
