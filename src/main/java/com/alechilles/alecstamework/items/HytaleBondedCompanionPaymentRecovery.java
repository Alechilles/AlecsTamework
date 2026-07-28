package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPaymentRecoveryService;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import com.alechilles.alecstamework.persistence.runtime.player
        .TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Reconciles a loaded player's retained bonded payment on its world thread. */
public final class HytaleBondedCompanionPaymentRecovery {
    private final BondedCompanionPaymentRecoveryService recovery;
    private final Supplier<ComponentType<EntityStore,
            TameworkBondedReviveEscrowComponent>> escrowTypes;
    private final Supplier<ComponentType<EntityStore,
            TameworkInventoryOperationReceiptsComponent>> receiptTypes;
    private final InventoryFactory inventories;
    private final LivePlayerCheck players;

    public HytaleBondedCompanionPaymentRecovery(
            @Nonnull BondedCompanionPaymentRecoveryService recovery) {
        this(recovery, HytaleBondedCompanionPaymentRecovery::resolveEscrowType,
                HytaleBondedCompanionPaymentRecovery::resolveReceiptType,
                HytaleBondedCompanionEscrowInventory::new,
                HytaleBondedCompanionPaymentRecovery::isLivePlayer);
    }

    HytaleBondedCompanionPaymentRecovery(
            BondedCompanionPaymentRecoveryService recovery,
            Supplier<ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent>> escrowTypes,
            Supplier<ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent>> receiptTypes,
            InventoryFactory inventories
    ) {
        this(recovery, escrowTypes, receiptTypes, inventories,
                (store, actor) -> true);
    }

    HytaleBondedCompanionPaymentRecovery(
            BondedCompanionPaymentRecoveryService recovery,
            Supplier<ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent>> escrowTypes,
            Supplier<ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent>> receiptTypes,
            InventoryFactory inventories,
            LivePlayerCheck players
    ) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.escrowTypes = Objects.requireNonNull(escrowTypes, "escrowTypes");
        this.receiptTypes = Objects.requireNonNull(receiptTypes, "receiptTypes");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.players = Objects.requireNonNull(players, "players");
    }

    /** Schedules one bounded recovery pass after the player joins a world. */
    public void onPlayerAdded(@Nonnull World world, @Nonnull UUID ownerUuid) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        try {
            world.execute(() -> recover(world, ownerUuid));
        } catch (RuntimeException | LinkageError ignored) {
            // A disappearing world leaves the durable escrow for the next join.
        }
    }

    private void recover(World world, UUID ownerUuid) {
        try {
            Ref<EntityStore> actor = world.getEntityRef(ownerUuid);
            if (actor == null || !actor.isValid()) return;
            Store<EntityStore> store = actor.getStore();
            if (store == null) return;
            store.assertThread();
            if (!players.isLive(store, actor)) return;
            ComponentType<EntityStore, TameworkBondedReviveEscrowComponent>
                    escrowType = escrowTypes.get();
            if (escrowType == null) return;
            TameworkBondedReviveEscrowComponent escrow = store.getComponent(
                    actor, escrowType);
            com.alechilles.alecstamework.api.BondedCompanionActionContext
                    .Inventory inventory = inventories.create(
                    world, store, ownerUuid, world.getName(), escrowType,
                    receiptTypes.get());
            if (escrow == null) return;
            BondedCompanionPaymentOperationId.parse(escrow.operationId())
                    .filter(identity -> ownerUuid.equals(identity.ownerUuid()))
                    .ifPresent(identity -> recovery.recover(
                            identity, inventory));
        } catch (RuntimeException | LinkageError ignored) {
            // Malformed or unavailable evidence remains quarantined for retry.
        }
    }

    private static ComponentType<EntityStore,
            TameworkBondedReviveEscrowComponent> resolveEscrowType() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null
                ? null : plugin.getBondedReviveEscrowComponentType();
    }

    private static ComponentType<EntityStore,
            TameworkInventoryOperationReceiptsComponent> resolveReceiptType() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null
                ? null : plugin.getInventoryOperationReceiptsComponentType();
    }

    private static boolean isLivePlayer(
            Store<EntityStore> store,
            Ref<EntityStore> actor
    ) {
        ComponentType<EntityStore, Player> playerType =
                Player.getComponentType();
        return playerType != null && store.getComponent(actor, playerType)
                != null;
    }

    @FunctionalInterface
    interface InventoryFactory {
        com.alechilles.alecstamework.api.BondedCompanionActionContext.Inventory
                create(
                        World world,
                        Store<EntityStore> store,
                        UUID ownerUuid,
                        String worldKey,
                        ComponentType<EntityStore,
                                TameworkBondedReviveEscrowComponent> escrowType,
                        ComponentType<EntityStore,
                                TameworkInventoryOperationReceiptsComponent>
                                receiptType);
    }

    @FunctionalInterface
    interface LivePlayerCheck {
        boolean isLive(Store<EntityStore> store, Ref<EntityStore> actor);
    }
}
