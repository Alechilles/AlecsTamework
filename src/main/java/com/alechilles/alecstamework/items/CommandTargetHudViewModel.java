package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Compact data model rendered by the command target HUD. */
public record CommandTargetHudViewModel(@Nonnull LinkedNpcEntry status,
                                        @Nullable FoodRow favoriteFood,
                                        @Nonnull List<FoodRow> compatibleFoods,
                                        @Nonnull List<AttachmentRow> attachments,
                                        @Nullable TameRequirementRow tameRequirement) {
    public CommandTargetHudViewModel {
        compatibleFoods = compatibleFoods == null ? List.of() : List.copyOf(compatibleFoods);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public record FoodRow(@Nonnull String itemId,
                          @Nonnull String displayName,
                          @Nullable String iconPath,
                          @Nullable Double happinessDelta) {
        public FoodRow(@Nonnull String itemId, @Nonnull String displayName, @Nullable String iconPath) {
            this(itemId, displayName, iconPath, null);
        }
    }

    public record AttachmentRow(@Nonnull String setLabel, @Nonnull String valueLabel) {
        @Nonnull
        public String displayLine() {
            if (valueLabel == null || valueLabel.isBlank()) {
                return setLabel;
            }
            return setLabel + ": " + valueLabel;
        }
    }

    public record TameRequirementRow(boolean tranquilizerRequired,
                                     int requiredStacks,
                                     @Nullable String currentStacksText) {
    }
}
