package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;

/** Canonicalizes generic command records before item persistence. */
final class CommandLinkedRecordCanonicalizer {
    private final CommandLinkedNpcRecordStore records;
    private final CommandNpcProfileActionResolver profiles;

    CommandLinkedRecordCanonicalizer(CommandLinkedNpcRecordStore records,
                                    CommandNpcProfileActionResolver profiles) {
        this.records = records;
        this.profiles = profiles;
    }

    ItemStack canonicalize(Player player, Store<EntityStore> store,
                           TwCommandItemConfig config, ItemStack stack,
                           String toolId) {
        if (stack == null || stack.isEmpty() || profiles == null) return stack;
        List<LinkedNpcRecord> current = records.read(stack);
        CommandNpcProfileActionResolver.CanonicalRecords canonical =
                profiles.canonicalizeRecords(current);
        return canonical.safeToPersist() && canonical.identityChanged()
                ? records.write(stack, canonical.records()) : stack;
    }
}
