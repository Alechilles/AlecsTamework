package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.HappinessConfigResolver;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
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
    private static final double DEFAULT_HAPPINESS_MIN = 0.0;
    private static final double DEFAULT_HAPPINESS_MAX = 100.0;

    public TameworkSetHappinessCommand() {
        super("happiness", "server.tamework.commands.setHappiness.description");
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
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setHappiness.usage.tw.sethappiness.value"));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setHappiness.no.npc.found.in.view"));
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
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setHappiness.happiness.and.breeding.components.are.not.available"));
            return;
        }
        if (happiness == null && breeding == null && happinessType == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setHappiness.npc.has.no.tracked.happiness.or.breeding").param("0", String.valueOf(candidate.npcUuid)));
            return;
        }

        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(candidate.ref, store, happiness);
        if (!HappinessConfigResolver.isRuntimeEnabled(happinessConfig)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setHappiness.npc.has.no.enabled.happiness.progression").param("0", String.valueOf(candidate.npcUuid)));
            return;
        }
        TwBreedingConfig breedingConfig = BreedingConfigResolver.resolveConfig(candidate.ref, store, breeding);
        ClampRules clampRules = resolveClampRules(happinessConfig);
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
                String roleId = CompanionRoleIdResolver.resolveRoleId(candidate.ref, store);
                ready = breeding.isEnabled()
                        && clamped >= TameworkRuntimeSettings.breedingHappinessThreshold(
                                breedingConfig.resolveHappiness(roleId).getThreshold(),
                                TwHappinessConfig.isEnabledForRole(roleId)
                        );
                breeding.setReady(ready);
            }
            store.putComponent(candidate.ref, breedingType, breeding);
        }

        commandContext.sender().sendMessage(buildLocalizedResultMessage(
                candidate.npcUuid.toString(), requested, clamped, clampRules, ready));
    }

    @Nullable
    private static Double parseRequestedValue(@Nonnull CommandContext commandContext) {
        try {
            String argument = TameworkCommandInput.firstArgument(
                    commandContext.getInputString(), "happiness"
            );
            if (argument == null) {
                return null;
            }
            double parsed = Double.parseDouble(argument);
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
    private static ClampRules resolveClampRules(@Nullable TwHappinessConfig happinessConfig) {
        if (HappinessConfigResolver.isRuntimeEnabled(happinessConfig)) {
            double min = happinessConfig.getValues().getMin();
            double max = happinessConfig.getValues().getMax();
            return normalizeRange(min, max);
        }
        return normalizeRange(DEFAULT_HAPPINESS_MIN, DEFAULT_HAPPINESS_MAX);
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

    @Nonnull
    private static Message buildLocalizedResultMessage(@Nonnull String npcUuid,
                                                       double requested,
                                                       double applied,
                                                       @Nullable ClampRules clampRules,
                                                       @Nullable Boolean ready) {
        String requestedText = formatDouble(requested);
        String appliedText = formatDouble(applied);
        if (clampRules == null) {
            if (ready == null) {
                return Message.translation("server.tamework.commands.setHappiness.result.noClamp")
                        .param("0", npcUuid)
                        .param("1", requestedText)
                        .param("2", appliedText);
            }
            return Message.translation("server.tamework.commands.setHappiness.result.noClamp.ready")
                    .param("0", npcUuid)
                    .param("1", requestedText)
                    .param("2", appliedText)
                    .param("3", String.valueOf(ready));
        }
        if (ready == null) {
            return Message.translation("server.tamework.commands.setHappiness.result")
                    .param("0", npcUuid)
                    .param("1", requestedText)
                    .param("2", appliedText)
                    .param("3", formatDouble(clampRules.min()))
                    .param("4", formatDouble(clampRules.max()));
        }
        return Message.translation("server.tamework.commands.setHappiness.result.ready")
                .param("0", npcUuid)
                .param("1", requestedText)
                .param("2", appliedText)
                .param("3", formatDouble(clampRules.min()))
                .param("4", formatDouble(clampRules.max()))
                .param("5", String.valueOf(ready));
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record ClampRules(double min, double max) {
    }
}
