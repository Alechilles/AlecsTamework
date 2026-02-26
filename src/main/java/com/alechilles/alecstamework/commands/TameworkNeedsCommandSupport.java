package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared helpers for needs debug commands.
 */
final class TameworkNeedsCommandSupport {
    private TameworkNeedsCommandSupport() {
    }

    @Nullable
    static Double parseDoubleArg(@Nullable String input, int index) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (index < 0 || tokens.length <= index) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(tokens[index]);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    static NeedsContext resolveContext(@Nonnull Ref<EntityStore> npcRef,
                                       @Nonnull Store<EntityStore> store) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TameworkNeedsComponent needs = CompanionNeedsService.ensureNeedsComponent(npcRef, store, roleId);
        if (needs == null) {
            return null;
        }
        TwNeedsConfig config = NeedsConfigResolver.resolveConfig(npcRef, store, needs);
        return new NeedsContext(needs, config, roleId);
    }

    static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    static String format(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    record NeedsContext(
            TameworkNeedsComponent component,
            @Nullable TwNeedsConfig config,
            @Nullable String roleId
    ) {
    }
}

