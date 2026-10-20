package com.alechilles.alecstamework.compat.runes;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** The player's saved originals while Tamework temporarily owns ability primary slots. */
public final class RuneInputLeaseComponent implements Component<EntityStore> {
    public static final BuilderCodec<RuneInputLeaseComponent> CODEC = BuilderCodec.builder(
            RuneInputLeaseComponent.class, RuneInputLeaseComponent::new)
            .append(new KeyedCodec<>("Ability2Original", ItemStack.CODEC),
                    (lease, stack) -> lease.ability2Original = copy(stack),
                    lease -> lease.ability2Original).add()
            .append(new KeyedCodec<>("Ability2Pending", Codec.BOOLEAN),
                    (lease, pending) -> lease.ability2Pending = pending,
                    lease -> lease.ability2Pending).add()
            .append(new KeyedCodec<>("Ability3Original", ItemStack.CODEC),
                    (lease, stack) -> lease.ability3Original = copy(stack),
                    lease -> lease.ability3Original).add()
            .append(new KeyedCodec<>("Ability3Pending", Codec.BOOLEAN),
                    (lease, pending) -> lease.ability3Pending = pending,
                    lease -> lease.ability3Pending).add()
            .build();

    @Nullable private static ComponentType<EntityStore, RuneInputLeaseComponent> type;
    @Nullable private ItemStack ability2Original;
    @Nullable private ItemStack ability3Original;
    private boolean ability2Pending;
    private boolean ability3Pending;

    public static void register(@Nonnull Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(
                RuneInputLeaseComponent.class, "TameworkRuneInputLease", CODEC);
    }

    @Nullable
    public static ComponentType<EntityStore, RuneInputLeaseComponent> getComponentType() {
        return type;
    }

    boolean pending(int line) {
        return line == 0 ? ability2Pending : ability3Pending;
    }

    @Nullable ItemStack original(int line) {
        return line == 0 ? ability2Original : ability3Original;
    }

    void capture(int line, @Nullable ItemStack stack) {
        if (line == 0) {
            ability2Original = copy(stack);
            ability2Pending = true;
        } else {
            ability3Original = copy(stack);
            ability3Pending = true;
        }
    }

    void clear(int line) {
        if (line == 0) {
            ability2Pending = false;
            ability2Original = null;
        } else {
            ability3Pending = false;
            ability3Original = null;
        }
    }

    boolean empty() {
        return !ability2Pending && !ability3Pending;
    }

    @Override @Nonnull
    public RuneInputLeaseComponent clone() {
        RuneInputLeaseComponent result = new RuneInputLeaseComponent();
        result.ability2Original = copy(ability2Original);
        result.ability3Original = copy(ability3Original);
        result.ability2Pending = ability2Pending;
        result.ability3Pending = ability3Pending;
        return result;
    }

    @Nullable
    private static ItemStack copy(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) return null;
        ItemStack result = new ItemStack(stack.getItemId(), stack.getQuantity(),
                stack.getDurability(), stack.getMaxDurability(),
                stack.getQualityIndex(), stack.getMetadata() == null ? null : stack.getMetadata().clone());
        result.setOverrideDroppedItemAnimation(stack.getOverrideDroppedItemAnimation());
        return result;
    }
}
