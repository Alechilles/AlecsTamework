package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;

/**
 * Applies linked-companion trait indicator state to UI card selectors.
 */
final class LinkedNpcTraitIndicatorBinder {
    static final int MAX_VISIBLE_TRAIT_INDICATORS = 4;

    private static final String POSITIVE_FILL_COLOR = "#6fc576";
    private static final String NEGATIVE_FILL_COLOR = "#d45f5f";

    private LinkedNpcTraitIndicatorBinder() {
    }

    static void bind(UICommandBuilder commandBuilder,
                     String entrySelector,
                     LinkedNpcTraitIndicator[] indicators) {
        LinkedNpcTraitIndicator[] safeIndicators = indicators == null ? LinkedNpcTraitIndicator.EMPTY : indicators;
        for (int index = 0; index < MAX_VISIBLE_TRAIT_INDICATORS; index++) {
            String slotSelector = entrySelector + " #TraitSlot" + index;
            LinkedNpcTraitIndicator indicator = index < safeIndicators.length ? safeIndicators[index] : null;
            if (indicator == null) {
                commandBuilder.set(slotSelector + ".Visible", false);
                continue;
            }
            commandBuilder.set(slotSelector + ".Visible", true);
            bindSlot(commandBuilder, slotSelector, indicator);
        }
    }

    private static void bindSlot(UICommandBuilder commandBuilder,
                                 String slotSelector,
                                 LinkedNpcTraitIndicator indicator) {
        String fillColor = indicator.belowDefault() ? NEGATIVE_FILL_COLOR : POSITIVE_FILL_COLOR;
        if (indicator.hasIconTexturePath()) {
            commandBuilder.set(slotSelector + " #TraitIcon.Visible", false);
            commandBuilder.set(slotSelector + " #TraitIconImage.Visible", true);
            commandBuilder.setObject(slotSelector + " #TraitIconImage.Anchor", iconAnchor(indicator.iconTexturePath(), 6, 5, 22));
            commandBuilder.setObject(
                    slotSelector + " #TraitIconImage.Background",
                    UiIconStyle.forTexture(indicator.iconTexturePath())
            );
        } else {
            commandBuilder.set(slotSelector + " #TraitIconImage.Visible", false);
            commandBuilder.set(slotSelector + " #TraitIcon.Visible", true);
            commandBuilder.set(slotSelector + " #TraitIcon.Text", indicator.iconText());
        }
        commandBuilder.set(slotSelector + " #TraitTooltip.TooltipText", indicator.tooltipText());
        commandBuilder.set(slotSelector + " #TraitTooltip.TooltipTextSpans", tooltipSpans(indicator.tooltipText()));
        commandBuilder.set(slotSelector + " #RingFillBar1.Background", fillColor);
        Anchor fill = new Anchor();
        fill.setLeft(Value.of(6));
        fill.setTop(Value.of(1));
        fill.setWidth(Value.of((int) Math.round(indicator.fillRatio() * 22)));
        fill.setHeight(Value.of(3));
        commandBuilder.setObject(slotSelector + " #RingFillBar1.Anchor", fill);
    }

    // Compensate for the slightly wider transparent margin in these generated silhouettes.
    static Anchor iconAnchor(String path, int left, int top, int size) {
        boolean padded = path.endsWith("/Trait_Appetite.png") || path.endsWith("/Trait_Productivity.png");
        int outset = padded ? 1 : 0;
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(left - outset));
        anchor.setTop(Value.of(top - outset));
        anchor.setWidth(Value.of(size + outset * 2));
        anchor.setHeight(Value.of(size + outset * 2));
        return anchor;
    }

    static Message tooltipSpans(String tooltip) {
        int newline = tooltip.indexOf('\n');
        String heading = newline < 0 ? tooltip : tooltip.substring(0, newline);
        Message title = Message.raw(heading).color("#e1be73").bold(true);
        return newline < 0 ? title : Message.join(title,
                Message.raw(tooltip.substring(newline)).color("#ffffff"));
    }

}
