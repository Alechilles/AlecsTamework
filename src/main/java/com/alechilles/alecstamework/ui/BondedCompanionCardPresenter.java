package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionNames;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Binds the dedicated, durable-profile card used by bonded companion rosters: one row of state
 * accent, portrait, identity and vitals, status, and actions.
 *
 * <p>The card deliberately reads only immutable profile presentation. A live
 * projection may enrich the profile elsewhere, but must never be required for
 * a stored or dead row to render correctly.</p>
 */
final class BondedCompanionCardPresenter {
    static final String CARD_UI_PATH = "TameworkBondedCompanionPanelCard.ui";
    static final String CANCEL_UNLINK_COMMAND_PREFIX = "__bonded_cancel_unlink__:";
    private static final int HEALTH_FILL_WIDTH = 278;
    private static final int XP_FILL_WIDTH = 280;
    private static final int TIMER_FILL_WIDTH = 270;
    /** The icon row: 28 px buttons, 4 px apart, right aligned to the action column's edge at 850. */
    private static final int ICON_ROW_TOP = 46;
    private static final int ICON_ROW_RIGHT = 850;
    private static final int ICON_SIZE = 28;
    private static final int ICON_GAP = 4;
    /** The identity column, and the room the 20 px gender icon takes before the name. */
    private static final int IDENTITY_LEFT = 100;
    private static final int IDENTITY_RIGHT = 380;
    private static final int GENDER_ICON_SPACE = 24;

    private BondedCompanionCardPresenter() {
    }

    static void bind(
            @Nonnull UICommandBuilder commands,
            @Nonnull UIEventBuilder events,
            @Nonnull String entrySelector,
            @Nonnull UUID cardUuid,
            @Nonnull BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            @Nonnull LinkedNpcPanelCardBinder.CardBindingConfig config,
            @Nullable String language
    ) {
        ProgressionSummary progression = progressionSummary(row.attributes(),
                row.roleId());
        bindIdentity(commands, entrySelector, row, progression, language);
        bindState(commands, entrySelector, row, pendingUnlink, language);
        bindHealth(commands, entrySelector, row.attributes(), row.status().state());
        bindXpProgress(commands, entrySelector, row, progression, language);
        bindPortrait(commands, entrySelector, row);
        bindTalents(commands, entrySelector, progression, pendingUnlink, language);
        bindFlightToggle(commands, entrySelector, row, pendingUnlink, language);
        bindShoulderRide(commands, entrySelector, row, pendingUnlink, language);
        bindUnlink(commands, entrySelector, pendingUnlink);
        bindIconRow(commands, entrySelector, row);
        bindPrimaryAction(commands, entrySelector, row, pendingUnlink, language);

        bindUnlinkEvents(events, entrySelector, cardUuid, config);
        if (!pendingUnlink) {
            bindProgressionEvents(events, entrySelector, cardUuid, progression,
                    row.attributes(), config);
            bindFlightToggleEvents(events, entrySelector, cardUuid, row, config);
            bindShoulderRideEvents(events, entrySelector, cardUuid, row, config);
            bindPrimaryActionEvents(events, entrySelector, cardUuid, row, config);
        }
    }

    /**
     * Patches the status sentence, timer bar, vitals, and accents without recreating card
     * controls or their input bindings. This is the per-tick path of a running timer.
     */
    static void refreshDynamicState(
            @Nonnull UICommandBuilder commands,
            @Nonnull String entrySelector,
            @Nullable BondedCompanionPanelPresentation previous,
            @Nonnull BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            @Nullable String language
    ) {
        bindState(commands, entrySelector, row, pendingUnlink, language);
        bindHealth(commands, entrySelector, row.attributes(), row.status().state());
        bindXpProgress(commands, entrySelector, row,
                progressionSummary(row.attributes(), row.roleId()), language);
        bindPortrait(commands, entrySelector, row);
        // Keep the flight control stable during health and countdown updates.
        if (previous == null || flightToggleVisible(previous) != flightToggleVisible(row)
                || flightToggleAirborne(previous) != flightToggleAirborne(row)) {
            bindFlightToggle(commands, entrySelector, row, pendingUnlink, language);
        }
        bindShoulderRide(commands, entrySelector, row, pendingUnlink, language);
    }

    /** Patches progression-derived labels and tooltips without emitting events. */
    static void refreshProgressionState(@Nonnull UICommandBuilder commands,
                                        @Nonnull String entrySelector,
                                        @Nonnull BondedCompanionPanelPresentation row,
                                        boolean pendingUnlink, @Nullable String language) {
        ProgressionSummary progression = progressionSummary(row.attributes(), row.roleId());
        bindIdentity(commands, entrySelector, row, progression, language);
        bindXpProgress(commands, entrySelector, row, progression, language);
        bindTalents(commands, entrySelector, progression, pendingUnlink, language);
    }

    /** Abandon keeps its two steps: the icon asks, then Confirm and Cancel replace the actions. */
    private static void bindUnlink(
            UICommandBuilder commands,
            String entrySelector,
            boolean pendingUnlink
    ) {
        LinkedNpcPanelIconStyles.visible(commands, entrySelector + " #BondedUnlinkButton",
                !pendingUnlink);
        commands.set(entrySelector + " #BondedUnlinkConfirmButton.Visible",
                pendingUnlink);
        commands.set(entrySelector + " #BondedUnlinkCancelButton.Visible", pendingUnlink);
    }

    private static void bindUnlinkEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            LinkedNpcPanelCardBinder.CardBindingConfig config
    ) {
        String command = config.unlinkCommandPrefix() + cardUuid;
        events.addEventBinding(CustomUIEventBindingType.Activating,
                entrySelector + " #BondedUnlinkButton",
                EventData.of(config.eventCommandId(), command), false);
        events.addEventBinding(CustomUIEventBindingType.Activating,
                entrySelector + " #BondedUnlinkConfirmButton",
                EventData.of(config.eventCommandId(), command), false);
        events.addEventBinding(CustomUIEventBindingType.Activating,
                entrySelector + " #BondedUnlinkCancelButton",
                EventData.of(config.eventCommandId(), CANCEL_UNLINK_COMMAND_PREFIX + cardUuid), false);
    }

    private static void bindIdentity(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            ProgressionSummary progression,
            @Nullable String language
    ) {
        commands.set(entrySelector + " #BondedName.Text", displayName(row, language));
        boolean male = "male".equalsIgnoreCase(row.gender());
        boolean female = "female".equalsIgnoreCase(row.gender());
        commands.set(entrySelector + " #BondedGenderMaleIcon.Visible", male);
        commands.set(entrySelector + " #BondedGenderFemaleIcon.Visible", female);
        // The name starts right of the gender icon when there is one.
        int nameLeft = male || female ? IDENTITY_LEFT + GENDER_ICON_SPACE : IDENTITY_LEFT;
        commands.setObject(entrySelector + " #BondedName.Anchor",
                fixedWidthAnchor(nameLeft, 8, IDENTITY_RIGHT - nameLeft, 22));
        commands.set(entrySelector + " #BondedSubtitle.Text",
                identityLine(row, progression, language));
    }

    /**
     * The muted line under the name: the translated role name (only when the companion has a
     * given name, since the role name is the title otherwise) and the level. Unknown parts are
     * left out. The parts are separate facts, each translated on its own.
     */
    private static String identityLine(
            BondedCompanionPanelPresentation row,
            ProgressionSummary progression,
            @Nullable String language
    ) {
        ArrayList<String> parts = new ArrayList<>(2);
        if (row.displayName() != null && !row.displayName().isBlank()) {
            String role = BondedCompanionNames.speciesLabel(row.species(),
                    row.attributes().get(BondedCompanionNames.NAME_KEY), row.roleId(), language);
            if (role != null) {
                parts.add(role);
            }
        }
        if (progression.visible()) {
            parts.add(LocalizedText.format(language,
                    "tamework.ui.linkedPanel.bonded.talents.level", progression.level()));
        }
        return String.join(" \u00b7 ", parts);
    }

    /** The state tone (accent, chip, button ring), the status sentence and its timer bar. */
    private static void bindState(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            @Nullable String language
    ) {
        BondedCompanionStatusPresentation status = row.status();
        boolean ring = !pendingUnlink
                && BondedCompanionCardStatePresentation.primaryActionAvailable(row);
        for (BondedCompanionStateView state : BondedCompanionStateView.values()) {
            String tone = entrySelector + " " + toneSelector(state);
            boolean current = state == status.state();
            commands.set(tone + ".Visible", current);
            if (current) {
                commands.set(tone + " #ChipLabel.Text",
                        LinkedNpcPanelFeatureBinder.bondedStateText(status, language));
                commands.set(tone + " #ActionRing.Visible", ring);
            }
        }
        BondedCompanionCardStatePresentation.Status copy =
                BondedCompanionCardStatePresentation.resolve(row, language);
        boolean hasText = !copy.text().isBlank();
        commands.set(entrySelector + " #BondedStatusText.Visible", hasText && !copy.warning());
        commands.set(entrySelector + " #BondedStatusWarning.Visible", hasText && copy.warning());
        commands.set(entrySelector + (copy.warning() ? " #BondedStatusWarning.Text"
                : " #BondedStatusText.Text"), copy.text());
        commands.set(entrySelector + " #BondedStatusSecondary.Visible", !copy.secondary().isBlank());
        commands.set(entrySelector + " #BondedStatusSecondary.Text", copy.secondary());

        int width = (int) Math.round(TIMER_FILL_WIDTH * copy.ratio());
        boolean session = copy.timer() == BondedCompanionCardStatePresentation.Timer.SESSION;
        boolean cooldown = copy.timer() == BondedCompanionCardStatePresentation.Timer.COOLDOWN;
        commands.set(entrySelector + " #BondedTimerFrame.Visible", session || cooldown);
        commands.set(entrySelector + " #BondedTimerFillSession.Visible", session && width > 0);
        commands.set(entrySelector + " #BondedTimerFillCooldown.Visible", cooldown && width > 0);
        if (session || cooldown) {
            commands.setObject(entrySelector + (session ? " #BondedTimerFillSession.Anchor"
                    : " #BondedTimerFillCooldown.Anchor"), fixedWidthAnchor(0, 0, width, 4));
        }
    }

    static String toneSelector(BondedCompanionStateView state) {
        return switch (state) {
            case ACTIVE -> "#BondedToneActive";
            case STORED -> "#BondedToneStored";
            case DEAD -> "#BondedToneDead";
        };
    }

    private static void bindHealth(
            UICommandBuilder commands,
            String entrySelector,
            Map<String, String> attributes,
            BondedCompanionStateView state
    ) {
        int maximum = positiveRoundedInt(attributes.get("maxHealth"), 100);
        int current = state == BondedCompanionStateView.DEAD ? 0
                : boundedInt(attributes.get("currentHealth"), maximum,
                        percent(attributes.get("healthPercent"), maximum));
        commands.set(entrySelector + " #BondedHealthTextShadow.Text", current + " / " + maximum);
        commands.set(entrySelector + " #BondedHealthText.Text",
                current + " / " + maximum);
        commands.setObject(entrySelector + " #BondedHealthFill.Anchor",
                fixedWidthAnchor(1, 1, (int) Math.round(HEALTH_FILL_WIDTH
                        * current / maximum), 18));
        commands.set(entrySelector + " #BondedHealthFill.Visible", current > 0);
        commands.set(entrySelector + " #BondedHealthFill.Background",
                state == BondedCompanionStateView.ACTIVE ? "#85b99a" : "#737a74");
    }

    private static void bindXpProgress(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            ProgressionSummary progression,
            @Nullable String language
    ) {
        Map<String, String> attributes = row.attributes();
        TwLevelingConfig config = TwLevelingConfig.resolveById(
                attributes.get("levelingConfigId"));
        boolean visible = progression.visible() && config != null && config.isEnabled();
        commands.set(entrySelector + " #BondedXpFrame.Visible", visible);
        commands.set(entrySelector + " #BondedXpButton.Visible", visible);
        if (!visible) {
            return;
        }
        int maxLevel = Math.max(1, config.getLevels().getMaxLevel());
        int level = Math.min(progression.level(), maxLevel);
        int requiredXp = (int) Math.max(1L, Math.round(
                config.getLevels().getBaseXp()
                        * Math.pow(config.getLevels().getGrowthFactor(), level - 1)));
        int currentXp = nonNegativeRoundedInt(attributes.get("currentXp"));
        int width = level >= maxLevel ? XP_FILL_WIDTH : (int) Math.round(
                XP_FILL_WIDTH * Math.min(1.0, (double) currentXp / requiredXp));
        commands.setObject(entrySelector + " #BondedXpFill.Anchor",
                fixedWidthAnchor(0, 0, width, 4));
        commands.set(entrySelector + " #BondedXpButton.TooltipText",
                progressionTooltip(progression, attributes, row.roleId(), language));
    }

    private static void bindPortrait(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row
    ) {
        LinkedNpcPanelPortraitBinder.bindIcon(commands,
                entrySelector + " #BondedPortrait", row.attributes().get("portraitIcon"));
        commands.set(entrySelector + " #BondedPortraitDim.Visible",
                row.status().state() == BondedCompanionStateView.DEAD);
    }

    /** The talents icon button and its badge of unspent points. */
    private static void bindTalents(
            UICommandBuilder commands,
            String entrySelector,
            ProgressionSummary progression,
            boolean pendingUnlink,
            @Nullable String language
    ) {
        boolean points = progression.talentsConfigured() && progression.availablePoints() > 0;
        commands.set(entrySelector + " #BondedTalentPointAction.Visible", !pendingUnlink);
        commands.set(entrySelector + " #BondedTalentPointBadge.Visible", points);
        commands.set(entrySelector + " #BondedTalentPointCount.Text", points
                ? Integer.toString(progression.availablePoints()) : "");
        commands.set(entrySelector + " #BondedTalentPointButton.TooltipText",
                LocalizedText.resolve(language, "tamework.ui.linkedPanel.bonded.talents.tooltip"));
    }

    /**
     * Places the icon row right aligned with no gaps: Talents, Flight, Shoulder ride, Abandon.
     * Flight and shoulder ride take a place only when the companion supports them and is active;
     * a change of either rebuilds the card, so the places are set once per bind.
     */
    private static void bindIconRow(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row
    ) {
        int left = ICON_ROW_RIGHT - ICON_SIZE;
        LinkedNpcPanelIconStyles.anchor(commands, entrySelector + " #BondedUnlinkButton", iconAnchor(left));
        if (shoulderRideVisible(row)) {
            left -= ICON_SIZE + ICON_GAP;
            LinkedNpcPanelIconStyles.anchor(commands, entrySelector + " #BondedShoulderRideButton",
                    iconAnchor(left));
        }
        if (flightToggleVisible(row)) {
            left -= ICON_SIZE + ICON_GAP;
            LinkedNpcPanelIconStyles.anchor(commands, entrySelector + " #BondedFlightToggleButton",
                    iconAnchor(left));
        }
        left -= ICON_SIZE + ICON_GAP;
        commands.setObject(entrySelector + " #BondedTalentPointAction.Anchor", iconAnchor(left));
    }

    private static Anchor iconAnchor(int left) {
        return fixedWidthAnchor(left, ICON_ROW_TOP, ICON_SIZE, ICON_SIZE);
    }

    private static void bindProgressionEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            ProgressionSummary progression,
            Map<String, String> attributes,
            LinkedNpcPanelCardBinder.CardBindingConfig config
    ) {
        // A saved level is sufficient to inspect the durable talent page. The
        // page itself resolves a saved or role-derived tree and can explain a
        // genuinely missing configuration instead of leaving a silent button.
        String commandValue = config.openTalentsCommandPrefix() + cardUuid;
        events.addEventBinding(CustomUIEventBindingType.Activating,
                entrySelector + " #BondedTalentPointButton",
                EventData.of(config.eventCommandId(), commandValue), false);
        if (xpProgressVisible(progression, attributes)) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + " #BondedXpButton",
                    EventData.of(config.eventCommandId(), commandValue), false);
        }
    }

    private static boolean xpProgressVisible(
            ProgressionSummary progression,
            Map<String, String> attributes
    ) {
        TwLevelingConfig config = TwLevelingConfig.resolveById(
                attributes.get("levelingConfigId"));
        return progression.visible() && config != null && config.isEnabled();
    }

    /** The primary button is absent, not disabled, when its action is not possible. */
    private static void bindPrimaryAction(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            @Nullable String language
    ) {
        commands.set(entrySelector + " #BondedPrimaryAction.Visible", !pendingUnlink
                && BondedCompanionCardStatePresentation.primaryActionAvailable(row));
        commands.set(entrySelector + " #BondedPrimaryAction.Text",
                actionLabel(row.status().action(), language));
    }

    private static void bindPrimaryActionEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            LinkedNpcPanelCardBinder.CardBindingConfig config
    ) {
        if (!BondedCompanionCardStatePresentation.primaryActionAvailable(row)) {
            return;
        }
        String command = switch (row.status().action()) {
            case SUMMON -> config.summonCommandPrefix() + cardUuid;
            case DISMISS -> config.dismissCommandPrefix() + cardUuid;
            case REVIVE -> config.respawnCommandPrefix() + cardUuid;
            case NONE -> null;
        };
        if (command != null) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + " #BondedPrimaryAction",
                    EventData.of(config.eventCommandId(), command), false);
        }
    }

    private static void bindFlightToggle(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            @Nullable String language
    ) {
        boolean visible = flightToggleVisible(row);
        boolean airborne = flightToggleAirborne(row);
        LinkedNpcPanelIconStyles.visible(commands, entrySelector + " #BondedFlightToggleButton",
                visible && !pendingUnlink);
        LinkedNpcPanelIconStyles.style(commands, entrySelector + " #BondedFlightToggleButton", airborne ? "FlightAirborne" : "FlightGrounded");
        commands.set(entrySelector + " #BondedFlightToggleButton.TooltipText",
                visible ? LocalizedText.resolve(language, airborne
                        ? "tamework.ui.linkedPanel.bonded.flight.switchToGround"
                        : "tamework.ui.linkedPanel.bonded.flight.switchToFlight") : "");
    }

    private static boolean flightToggleVisible(BondedCompanionPanelPresentation row) {
        return row.status().state() == BondedCompanionStateView.ACTIVE
                && Boolean.parseBoolean(row.attributes().get(
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE));
    }

    private static boolean flightToggleAirborne(BondedCompanionPanelPresentation row) {
        return Boolean.parseBoolean(row.attributes().get(
                BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE));
    }

    private static void bindFlightToggleEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            LinkedNpcPanelCardBinder.CardBindingConfig config
    ) {
        if (flightToggleVisible(row)) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + " #BondedFlightToggleButton",
                    EventData.of(config.eventCommandId(),
                            config.bondedFlightToggleCommandPrefix() + cardUuid), false);
        }
    }

    private static String actionLabel(
            BondedCompanionStatusPresentation.Action action,
            @Nullable String language
    ) {
        return switch (action) {
            case SUMMON -> LocalizedText.resolve(language,
                    "tamework.ui.linkedPanel.bonded.action.summon");
            case DISMISS -> LocalizedText.resolve(language,
                    "tamework.ui.linkedPanel.bonded.action.dismiss");
            case REVIVE -> LocalizedText.resolve(language,
                    "tamework.ui.linkedPanel.bonded.action.revive");
            case NONE -> "";
        };
    }

    private static ProgressionSummary progressionSummary(
            Map<String, String> attributes,
            @Nullable String roleId
    ) {
        int level = positiveRoundedInt(attributes.get("level"), 0);
        String levelingConfig = attributes.get("levelingConfigId");
        String talentConfig = attributes.get("talentConfigId");
        if (level == 0 || levelingConfig == null || levelingConfig.isBlank()) {
            return ProgressionSummary.hidden();
        }
        int spent = nonNegativeInt(attributes.get("talentSpentPoints"));
        int earned = CompanionLevelingService.resolveEarnedTalentPoints(level,
                levelingConfig);
        boolean talentsConfigured = talentConfig != null && !talentConfig.isBlank()
                || roleId != null && !roleId.isBlank()
                && TwTalentConfig.resolveForRole(roleId) != null;
        int available = talentsConfigured ? Math.max(0, earned - spent) : 0;
        return new ProgressionSummary(true, talentsConfigured, level, available);
    }

    private static Anchor fixedWidthAnchor(int left, int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(top));
        anchor.setLeft(Value.of(left));
        anchor.setWidth(Value.of(Math.max(0, width)));
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    private static String displayName(BondedCompanionPanelPresentation row, @Nullable String language) {
        return BondedCompanionNames.displayName(row.displayName(), row.species(),
                row.attributes().get(BondedCompanionNames.NAME_KEY), row.roleId(), language);
    }

    private static String progressionTooltip(
            ProgressionSummary progression,
            Map<String, String> attributes,
            @Nullable String roleId,
            @Nullable String language
    ) {
        TwLevelingConfig config = TwLevelingConfig.resolveById(
                attributes.get("levelingConfigId"));
        if (config == null || !config.isEnabled()) {
            return LocalizedText.format(language, "tamework.ui.roster.progression.level", progression.level());
        }
        int maxLevel = Math.max(1, config.getLevels().getMaxLevel());
        int level = Math.min(progression.level(), maxLevel);
        if (level >= maxLevel) {
            return LinkedNpcPanelProgressionBinder.resolveXpTooltip(
                    new LinkedNpcEntry.FutureStat(LocalizedText.format(language, "tamework.ui.talents.levelSummary.max", level),
                            1, 1,
                            LocalizedText.format(language, "tamework.ui.roster.progression.max", level, maxLevel),
                            modifierTooltip(config, level, attributes, roleId, language)));
        }
        int currentXp = nonNegativeRoundedInt(attributes.get("currentXp"));
        int requiredXp = (int) Math.max(1L, Math.round(
                config.getLevels().getBaseXp()
                        * Math.pow(config.getLevels().getGrowthFactor(), level - 1)));
        return LinkedNpcPanelProgressionBinder.resolveXpTooltip(
                new LinkedNpcEntry.FutureStat(LocalizedText.format(language, "tamework.ui.roster.progression.level", level),
                        currentXp, requiredXp,
                        LocalizedText.format(language, "tamework.ui.roster.progression.xp", level, maxLevel, currentXp, requiredXp),
                        modifierTooltip(config, level, attributes, roleId, language)));
    }

    @Nullable
    private static String modifierTooltip(
            TwLevelingConfig config,
            int level,
            Map<String, String> attributes,
            @Nullable String roleId,
            @Nullable String language
    ) {
        return LinkedNpcPanelProgressionBinder.resolveSavedModifierTooltip(
                config, level, attributes.get("talentConfigId"),
                attributes.get("talents"), attributes.get("traitConfigId"),
                roleId, attributes.get("traits"),
                nonNegativeDouble(attributes.get("maxHealth")), language);
    }

    private static int positiveRoundedInt(@Nullable String value, int fallback) {
        int parsed = nonNegativeRoundedInt(value);
        return parsed > 0 ? parsed : fallback;
    }

    private static int boundedInt(@Nullable String value, int maximum,
                                  int fallback) {
        int parsed = value == null ? fallback : nonNegativeRoundedInt(value);
        return Math.min(Math.max(0, maximum), Math.max(0, parsed));
    }

    private static int nonNegativeRoundedInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                return 0;
            }
            return (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, Math.round(parsed)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double nonNegativeDouble(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed >= 0.0 ? parsed : 0.0;
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static void bindShoulderRide(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            @Nullable String language
    ) {
        boolean visible = shoulderRideVisible(row);
        boolean mounted = Boolean.parseBoolean(row.attributes().get(
                BondedCompanionPresentationAttributes.SHOULDER_RIDE_MOUNTED));
        LinkedNpcPanelIconStyles.visible(commands, entrySelector + " #BondedShoulderRideButton",
                visible && !pendingUnlink);
        LinkedNpcPanelIconStyles.style(commands, entrySelector + " #BondedShoulderRideButton", mounted ? "ShoulderOn" : "ShoulderOff");
        commands.set(entrySelector + " #BondedShoulderRideButton.TooltipText",
                visible ? LocalizedText.resolve(language, mounted
                        ? "tamework.ui.linkedPanel.bonded.shoulder.down.tooltip"
                        : "tamework.ui.linkedPanel.bonded.shoulder.toMe.tooltip") : "");
    }

    private static boolean shoulderRideVisible(
            BondedCompanionPanelPresentation row) {
        return row.status().state() == BondedCompanionStateView.ACTIVE
                && Boolean.parseBoolean(row.attributes().get(
                BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE));
    }

    private static void bindShoulderRideEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            LinkedNpcPanelCardBinder.CardBindingConfig config
    ) {
        if (shoulderRideVisible(row)) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + " #BondedShoulderRideButton",
                    EventData.of(config.eventCommandId(),
                            CommandSelectionPageEventBinder
                                    .BONDED_SHOULDER_RIDE_COMMAND_PREFIX
                                    + cardUuid), false);
        }
    }

    private static int percent(@Nullable String value, int scale) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                return 0;
            }
            double normalized = parsed >= 0D && parsed <= 1D
                    ? parsed * 100D : parsed;
            return (int) Math.round(Math.max(0D, Math.min(100D, normalized))
                    * Math.max(1, scale) / 100D);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int nonNegativeInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record ProgressionSummary(
            boolean visible,
            boolean talentsConfigured,
            int level,
            int availablePoints
    ) {
        private static ProgressionSummary hidden() {
            return new ProgressionSummary(false, false, 0, 0);
        }

    }
}
