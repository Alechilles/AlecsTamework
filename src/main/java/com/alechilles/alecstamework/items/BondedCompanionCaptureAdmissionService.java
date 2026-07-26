package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nullable;

/** Reads bonded-only tranquilized and roster-tool admission evidence. */
final class BondedCompanionCaptureAdmissionService {
    private final SpawnerCapturePolicyService capturePolicy;
    @Nullable private final CommandItemRegistry commandItems;
    @Nullable private final BondedCompanionRosterRegistry rosters;

    BondedCompanionCaptureAdmissionService(
            SpawnerCapturePolicyService capturePolicy,
            @Nullable CommandItemRegistry commandItems,
            @Nullable BondedCompanionRosterRegistry rosters) {
        this.capturePolicy = Objects.requireNonNull(capturePolicy, "capturePolicy");
        this.commandItems = commandItems;
        this.rosters = rosters;
    }

    boolean hasToolAccess(Player player, ItemFeatureConfig config) {
        if (player == null || commandItems == null
                || player.getInventory() == null) return false;
        var mechanics = config.getCaptureMechanics();
        TwCommandItemConfig required = commandItems.getByConfigId(
                mechanics.requiredCommandConfigId());
        if (required == null || !required.isEnabled()
                || !required.usesBondedCompanionRoster()
                || !Objects.equals(required.getBondedRosterId(),
                mechanics.bondedRosterId())) return false;
        ItemContainer items = player.getInventory()
                .getCombinedBackpackStorageHotbarFirst();
        if (items == null) return false;
        for (short slot = 0; slot < items.getCapacity(); slot++) {
            ItemStack stack = items.getItemStack(slot);
            if (!ItemStack.isEmpty(stack)
                    && commandItems.get(stack.getItemId()) == required) return true;
        }
        return false;
    }

    boolean isTranquilized(Player player, Ref<EntityStore> targetRef) {
        World world = player == null ? null : player.getWorld();
        return world != null && world.getEntityStore() != null
                && capturePolicy.isTranquilized(
                targetRef, world.getEntityStore().getStore());
    }

    @Nullable
    SpawnerCapturePolicyService.BondedAdmissionEvidence assess(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            ItemFeatureConfig config
    ) {
        if (config == null || rosters == null) return null;
        var roster = rosters.resolve(
                config.getCaptureMechanics().bondedRosterId()).orElse(null);
        return roster == null ? null : capturePolicy.assessBonded(
                player, targetRef, config, source, roster);
    }
}
