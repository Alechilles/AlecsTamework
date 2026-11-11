package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.companioncard.CompanionCardInteractions;
import com.alechilles.alecstamework.companioncard.CompanionCardRequests;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CardSettings;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeStep;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The companion card's side of {@code TameworkInteract}: builds the card's interaction snapshot and runs the card's
 * requests in the role tick, through the same selection checks, effects and cooldowns as F.
 *
 * <p>Requests are checked against the config as it is now: a {@code RunEntry} must name an enabled entry that still
 * declares a {@code Card} block and is not the {@code ModeCycle} entry, and a {@code SetMode} must name a step that is
 * valid for the role. Crouch and movement requirements are skipped for both; every other requirement still applies.
 */
final class InteractionCardRuntime {
    private static final String ICON_ROOT = "Tamework/";

    private final InteractionSelector selector;
    private final TameworkInteractEffects effects;

    InteractionCardRuntime(InteractionSelector selector, TameworkInteractEffects effects) {
        this.selector = selector;
        this.effects = effects;
    }

    /** Result of a card request: whether anything ran, and the snapshot to show afterwards. */
    record Outcome(boolean applied, CompanionCardInteractions interactions) {
    }

    /** Runs {@code request} if it is still allowed and returns the card's snapshot afterwards. */
    Outcome handle(CompanionCardRequests.Request request,
                   TwInteractionConfig config,
                   Ref<EntityStore> npcRef,
                   Role role,
                   InfoProvider infoProvider,
                   Store<EntityStore> store,
                   Player player,
                   InteractionContextSnapshot ctx,
                   Applier applier) {
        ctx.skipGestures = true;
        boolean applied = false;
        if (request instanceof CompanionCardRequests.RunEntry run && sameConfig(run.configId(), config)) {
            InteractionEntry entry = entryAt(config, run.entryIndex());
            if (entry != null && entry.getCard() != null && !(entry instanceof ModeCycleInteraction)) {
                ActionTameworkInteract.ResolvedInteraction resolved = selector.checkCardEntry(
                        config, run.entryIndex(), npcRef, role, infoProvider, store, player, ctx);
                if (resolved != null && !resolved.blockedByCooldown) {
                    applied = applier.apply(resolved);
                }
            }
        } else if (request instanceof CompanionCardRequests.SetMode mode && sameConfig(mode.configId(), config)) {
            int modeIndex = modeEntryIndex(config);
            if (modeIndex >= 0) {
                ActionTameworkInteract.ResolvedInteraction resolved = selector.checkCardEntry(
                        config, modeIndex, npcRef, role, infoProvider, store, player, ctx);
                if (resolved != null && !resolved.blockedByCooldown && mayChangeMode(npcRef, store, player)) {
                    ctx.cardModeStep = mode.stepIndex();
                    try {
                        applied = applier.apply(resolved);
                    } finally {
                        ctx.cardModeStep = -1;
                    }
                }
            }
        }
        return new Outcome(applied, describe(config, npcRef, role, infoProvider, store, player, ctx));
    }

    /**
     * A player with shared access below command level may open the card but not change modes.
     * Owners pass, and players with no access are left to the entry's own requirements.
     */
    private static boolean mayChangeMode(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        com.alechilles.alecstamework.api.access.AccessLevel level =
                com.alechilles.alecstamework.access.CompanionAccess.level(npcRef, store, player.getUuid());
        return level == com.alechilles.alecstamework.api.access.AccessLevel.NONE
                || com.alechilles.alecstamework.api.access.AccessAction.COMMAND.allowedAt(level);
    }

    /** Builds the card's interaction snapshot for this viewer. Read only. */
    CompanionCardInteractions describe(TwInteractionConfig config,
                                       Ref<EntityStore> npcRef,
                                       Role role,
                                       InfoProvider infoProvider,
                                       Store<EntityStore> store,
                                       Player player,
                                       InteractionContextSnapshot ctx) {
        InteractionEntry[] entries = config != null ? config.getInteractions() : null;
        if (entries == null || entries.length == 0) {
            return CompanionCardInteractions.EMPTY;
        }
        ctx.skipGestures = true;
        String language = player.getPlayerRef() != null ? player.getPlayerRef().getLanguage() : null;
        List<CompanionCardInteractions.Mode> modes = new ArrayList<>();
        boolean modeAllowed = false;
        int modeIndex = modeEntryIndex(config);
        if (modeIndex >= 0) {
            ModeCycleInteraction cycle = (ModeCycleInteraction) entries[modeIndex];
            for (InteractionModeCycleEffects.CardModeStep step
                    : effects.describeModes(cycle.getCycle(), npcRef, role, store)) {
                modes.add(new CompanionCardInteractions.Mode(step.stepIndex(), modeLabel(step.step(), language),
                        modeIcon(step.step()), step.current()));
            }
            ActionTameworkInteract.ResolvedInteraction resolved = selector.checkCardEntry(
                    config, modeIndex, npcRef, role, infoProvider, store, player, ctx);
            modeAllowed = resolved != null && !resolved.blockedByCooldown && mayChangeMode(npcRef, store, player);
        }
        List<Indexed> buttons = new ArrayList<>();
        for (int index = 0; index < entries.length; index++) {
            InteractionEntry entry = entries[index];
            if (entry == null || !entry.isEnabled() || entry.getCard() == null
                    || entry instanceof ModeCycleInteraction) {
                continue;
            }
            ActionTameworkInteract.ResolvedInteraction resolved = selector.checkCardEntry(
                    config, index, npcRef, role, infoProvider, store, player, ctx);
            CompanionCardInteractions.Status status = resolved == null
                    ? CompanionCardInteractions.Status.UNAVAILABLE
                    : resolved.blockedByCooldown
                            ? CompanionCardInteractions.Status.COOLDOWN
                            : CompanionCardInteractions.Status.READY;
            CompanionCardInteractions.Kind kind = kindOf(entry);
            CardSettings card = entry.getCard();
            String label = LocalizedText.resolveConfigValue(language, card.getLabel(),
                    LocalizedText.resolve(language, defaultLabelKey(kind)));
            String icon = blankToNull(card.getIcon()) != null ? card.getIcon().trim() : defaultIcon(kind);
            buttons.add(new Indexed(card.getOrder(), index,
                    new CompanionCardInteractions.Action(index, kind, label, icon, status)));
        }
        buttons.sort(Comparator.comparingInt(Indexed::order).thenComparingInt(Indexed::index));
        List<CompanionCardInteractions.Action> actions = new ArrayList<>(buttons.size());
        for (Indexed button : buttons) {
            actions.add(button.action());
        }
        return new CompanionCardInteractions(config.getId(), modes, modeAllowed, actions);
    }

    private static boolean sameConfig(String requested, TwInteractionConfig config) {
        return requested != null && config != null && requested.equals(config.getId());
    }

    /** Index of the first enabled {@code ModeCycle} entry, or -1. */
    static int modeEntryIndex(TwInteractionConfig config) {
        InteractionEntry[] entries = config != null ? config.getInteractions() : null;
        if (entries == null) {
            return -1;
        }
        for (int index = 0; index < entries.length; index++) {
            if (entries[index] instanceof ModeCycleInteraction && entries[index].isEnabled()) {
                return index;
            }
        }
        return -1;
    }

    private static InteractionEntry entryAt(TwInteractionConfig config, int index) {
        InteractionEntry[] entries = config != null ? config.getInteractions() : null;
        if (entries == null || index < 0 || index >= entries.length) {
            return null;
        }
        InteractionEntry entry = entries[index];
        return entry != null && entry.isEnabled() ? entry : null;
    }

    private static CompanionCardInteractions.Kind kindOf(InteractionEntry entry) {
        if (entry instanceof MountInteraction) return CompanionCardInteractions.Kind.MOUNT;
        if (entry instanceof FeedInteraction) return CompanionCardInteractions.Kind.FEED;
        if (entry instanceof BreedInteraction) return CompanionCardInteractions.Kind.BREED;
        if (entry instanceof HarvestInteraction) return CompanionCardInteractions.Kind.HARVEST;
        if (entry instanceof TameInteraction) return CompanionCardInteractions.Kind.TAME;
        return CompanionCardInteractions.Kind.CUSTOM;
    }

    private static String defaultLabelKey(CompanionCardInteractions.Kind kind) {
        return "tamework.ui.companionCard.action." + kind.name().toLowerCase(Locale.ROOT);
    }

    private static String defaultIcon(CompanionCardInteractions.Kind kind) {
        return switch (kind) {
            case MOUNT -> ICON_ROOT + "PanelActions/Ride_Glyph_Default.png";
            case BREED -> ICON_ROOT + "PanelActions/BreedingOn_Glyph_Default.png";
            case FEED -> ICON_ROOT + "LinkedPanelIcons/Need_Hunger.png";
            case HARVEST -> ICON_ROOT + "LinkedPanelIcons/Harvest_Cooldown.png";
            case TAME, CUSTOM -> ICON_ROOT + "PanelActions/Pet_Glyph_Default.png";
        };
    }

    /** Default label key and glyph for the common mode states; other states show their configured label or name. */
    private static String modeLabel(ModeStep step, String language) {
        String key = modeKey(step.getState());
        String fallback = key != null
                ? LocalizedText.resolve(language, "tamework.ui.companionCard.mode." + key)
                : step.getState();
        return LocalizedText.resolveConfigValue(language, step.getLabel(), fallback);
    }

    private static String modeIcon(ModeStep step) {
        if (blankToNull(step.getIcon()) != null) {
            return step.getIcon().trim();
        }
        String key = modeKey(step.getState());
        String glyph = key == null ? "Idle" : switch (key) {
            case "follow" -> "Follow";
            case "hold" -> "Hold";
            case "defend" -> "Defend";
            case "aggressive" -> "Aggressive";
            default -> "Idle";
        };
        return ICON_ROOT + "CommandHotswaps/" + glyph + ".png";
    }

    private static String modeKey(String state) {
        if (state == null) {
            return null;
        }
        return switch (state.trim().toLowerCase(Locale.ROOT)) {
            case "follow" -> "follow";
            case "hold", "stay" -> "hold";
            case "idle", "wander" -> "idle";
            case "defend" -> "defend";
            case "aggressive" -> "aggressive";
            case "sleep" -> "sleep";
            default -> null;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Runs a resolved entry through the action's normal apply path (effects plus cooldown). */
    @FunctionalInterface
    interface Applier {
        boolean apply(ActionTameworkInteract.ResolvedInteraction resolved);
    }

    private record Indexed(int order, int index, CompanionCardInteractions.Action action) {
    }
}
