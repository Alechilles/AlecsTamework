package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Presents the dead or lost status strip in the action section of a generic companion card.
 *
 * <p>The full card render owns visibility, the icon, and the restore button placement; the
 * countdown and dynamic presenters call {@link #refresh} to update text, bar, and tint only.
 * Geometry lives in {@code TameworkLinkedNpcPanelCard.ui} (#StatusStrip); the strip keeps one
 * size in every state so nothing moves when the countdown completes.</p>
 */
final class LinkedNpcPanelStatusStrip {
    private static final String CARD_UI = "TameworkLinkedNpcPanelCard.ui";
    private static final int STRIP_TOP = 36;
    private static final int STRIP_LEFT = 432;
    private static final int STRIP_WIDTH = 414;
    private static final int STRIP_HEIGHT = 64;
    private static final int BUTTON_SIZE = 44;
    private static final int BUTTON_INSET = 14;
    private static final int BAR_WIDTH = 280;
    private static final int TEXT_LEFT = 62;
    private static final int TEXT_TOP_WITH_BAR = 9;
    private static final int TEXT_TOP_CENTERED = 14;

    enum State { COUNTDOWN, READY, DISABLED, MISSING }

    private LinkedNpcPanelStatusStrip() {
    }

    /** Dead companions, and lost ones not shown as in a coop, use the strip when the layout allows it. */
    static boolean applies(LinkedNpcEntry entry) {
        return entry != null && !entry.recoveryHeld()
                && (entry.dead() || entry.lost() && !entry.inCoop());
    }

    static State state(LinkedNpcEntry entry, long remainingMs, boolean restoreActionVisible) {
        if (entry.dead()) {
            return remainingMs < 0L ? State.DISABLED : remainingMs == 0L ? State.READY : State.COUNTDOWN;
        }
        return restoreActionVisible ? State.READY : State.MISSING;
    }

    /** Full render: shows the strip and moves a visible Revive/Recover button inside its right edge. */
    static void bind(UICommandBuilder commands, String card, LinkedNpcEntry entry, String emblem,
                     String restoreSelector, boolean restoreActionVisible, String language) {
        commands.set(card + " #StatusStrip.Visible", true);
        commands.setObject(card + " #StatusStrip #Icon.Background", UiIconStyle.forTexture(emblem));
        // The lost emblem has more transparent padding, so it renders larger to match visually.
        int iconSize = entry.dead() ? 36 : 44;
        int iconInset = (STRIP_HEIGHT - iconSize) / 2;
        commands.setObject(card + " #StatusStrip #Icon.Anchor", LinkedNpcPanelCardBinder.fixedAnchor(
                iconInset, 14 - (iconSize - 36) / 2, iconSize, iconSize));
        if (restoreActionVisible) {
            LinkedNpcPanelIconStyles.anchor(commands, restoreSelector, LinkedNpcPanelCardBinder.fixedAnchor(
                    STRIP_TOP + (STRIP_HEIGHT - BUTTON_SIZE) / 2,
                    STRIP_LEFT + STRIP_WIDTH - BUTTON_INSET - BUTTON_SIZE, BUTTON_SIZE, BUTTON_SIZE));
            // The primary line already says what the button does.
            commands.set(restoreSelector + "Caption.Visible", false);
        }
        refresh(commands, card, entry, entry.deadRespawnRemainingMs(), restoreActionVisible, language);
    }

    /** Updates text, progress bar, and tint for the given remaining revive time. */
    static void refresh(UICommandBuilder commands, String card, LinkedNpcEntry entry, long remainingMs,
                        boolean restoreActionVisible, String language) {
        String strip = card + " #StatusStrip";
        State state = state(entry, remainingMs, restoreActionVisible);
        Palette palette = Palette.of(state);
        commands.set(strip + ".Background", palette.border);
        commands.set(strip + " #Fill.Background", palette.fill);
        commands.set(strip + " #Text #State.Text", LocalizedText.resolve(language,
                entry.dead() ? "tamework.ui.linkedPanel.status.dead" : "tamework.ui.linkedPanel.status.lost"));
        commands.set(strip + " #Text #State.Style", Value.ref(CARD_UI, palette.labelStyle));
        commands.set(strip + " #Text #Primary.Text", primaryText(entry, state, remainingMs, language));
        commands.set(strip + " #Text #Primary.Style", Value.ref(CARD_UI,
                state == State.DISABLED ? "StatusStripPrimaryMuted" : "StatusStripPrimary"));
        double progress = progress(entry, state, remainingMs);
        boolean bar = progress >= 0.0;
        commands.set(strip + " #Bar.Visible", bar);
        commands.setObject(strip + " #Text.Anchor", LinkedNpcPanelCardBinder.fixedAnchor(
                bar ? TEXT_TOP_WITH_BAR : TEXT_TOP_CENTERED, TEXT_LEFT, BAR_WIDTH, 36));
        if (bar) {
            commands.set(strip + " #Bar #BarFill.Background", palette.bar);
            commands.setObject(strip + " #Bar #BarFill.Anchor", LinkedNpcPanelCardBinder.fixedAnchor(
                    0, 0, (int) Math.round(progress * BAR_WIDTH), 4));
        }
    }

    /** Elapsed share of the revive cooldown, 1 when ready, or -1 when no bar applies. */
    static double progress(LinkedNpcEntry entry, State state, long remainingMs) {
        if (!entry.dead()) return -1.0;
        if (state == State.READY) return 1.0;
        long total = entry.deadRespawnTotalMs();
        if (state != State.COUNTDOWN || total <= 0L) return -1.0;
        return Math.clamp(1.0 - (double) Math.min(remainingMs, total) / total, 0.0, 1.0);
    }

    private static String primaryText(LinkedNpcEntry entry, State state, long remainingMs, String language) {
        return switch (state) {
            case COUNTDOWN -> LocalizedText.format(language, "tamework.ui.linkedPanel.status.reviveIn",
                    LinkedNpcPanelStatusTextService.formatRemainingTime(remainingMs, language));
            case READY -> LocalizedText.resolve(language, entry.dead()
                    ? "tamework.ui.linkedPanel.status.reviveReady"
                    : "tamework.ui.linkedPanel.status.recoverReady");
            case DISABLED -> LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.reviveDisabled");
            case MISSING -> LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.lostMissing");
        };
    }

    // Runtime string patches accept opaque hex colors only.
    private record Palette(String border, String fill, String bar, String labelStyle) {
        static Palette of(State state) {
            return switch (state) {
                case COUNTDOWN -> new Palette("#7a4a48", "#2a1f22", "#e5786d", "StatusStripLabelDead");
                case READY -> new Palette("#a47620", "#1d2f45", "#e8aa35", "StatusStripLabelReady");
                case DISABLED -> new Palette("#2b405c", "#131d2b", "#586d8a", "StatusStripLabelMuted");
                case MISSING -> new Palette("#8a6a3a", "#2a251d", "#f0b45a", "StatusStripLabelLost");
            };
        }
    }
}
