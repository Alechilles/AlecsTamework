package com.alechilles.alecstamework.companioncard;

import com.alechilles.alecstamework.items.components.TameworkCompanionCardPreferenceComponent;
import com.alechilles.alecstamework.settings.CompanionCardSettingsStore;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What F on a companion does for a player, and the server's companion card settings. The server sets a default
 * and may lock it; otherwise each player's saved choice wins. Reads use only memory and the player's components,
 * so they are safe on the world thread inside a role tick.
 */
public final class CompanionCardPreferences {
    private CompanionCardPreferences() {
    }

    /** The player's saved choice when the server allows one, otherwise the server default. Read only. */
    @Nonnull
    public static CompanionCardFBehaviour effective(@Nonnull Ref<EntityStore> playerRef,
                                                    @Nonnull ComponentAccessor<EntityStore> store) {
        CompanionCardSettingsStore.Settings settings = CompanionCardSettingsStore.current();
        if (settings.allowPlayerChoice()) {
            CompanionCardFBehaviour saved = TameworkCompanionCardPreferenceComponent.readSaved(playerRef, store);
            if (saved != null) {
                return saved;
            }
        }
        return settings.defaultFBehaviour();
    }

    /** The player's own saved choice, or {@code null} when they follow the server default. Ignores the lock. */
    @Nullable
    public static CompanionCardFBehaviour savedOverride(@Nonnull Ref<EntityStore> playerRef,
                                                        @Nonnull ComponentAccessor<EntityStore> store) {
        return TameworkCompanionCardPreferenceComponent.readSaved(playerRef, store);
    }

    /** Whether players may choose for themselves; when false everyone uses {@link #serverDefault()}. */
    public static boolean playerChoiceAllowed() {
        return CompanionCardSettingsStore.current().allowPlayerChoice();
    }

    /**
     * Saves the player's choice, or clears it when {@code behaviour} is null so they follow the server default.
     * Call on the world thread from a page event or command, never inside an ECS system iteration. Does not check
     * {@link #playerChoiceAllowed()}; callers refuse a locked choice themselves.
     */
    public static void setOverride(@Nonnull Ref<EntityStore> playerRef,
                                   @Nonnull Store<EntityStore> store,
                                   @Nullable CompanionCardFBehaviour behaviour) {
        if (behaviour == null) {
            TameworkCompanionCardPreferenceComponent.clear(playerRef, store);
        } else {
            TameworkCompanionCardPreferenceComponent.write(playerRef, store, behaviour);
        }
    }

    @Nonnull
    public static CompanionCardFBehaviour serverDefault() {
        return CompanionCardSettingsStore.current().defaultFBehaviour();
    }

    @Nonnull
    public static CompanionCardRenameMode renameMode() {
        return CompanionCardSettingsStore.current().renameMode();
    }
}
