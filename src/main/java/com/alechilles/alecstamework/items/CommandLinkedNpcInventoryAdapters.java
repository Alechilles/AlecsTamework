package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Production Hytale inventory adapters for profile-first command-record repair. */
final class CommandLinkedNpcInventoryAdapters {
    private CommandLinkedNpcInventoryAdapters() {
    }

    static CommandLinkedNpcInventoryRepairService.ContainerAdapter<ItemStack> combined(
            @Nonnull CombinedItemContainer container) {
        return new CombinedAdapter(container);
    }

    @Nullable
    static CommandLinkedNpcInventoryRepairService.ContainerAdapter<ItemStack> playerInventory(
            @Nullable Holder<EntityStore> holder) {
        if (holder == null) {
            return null;
        }
        ArrayList<ItemContainer> containers = new ArrayList<>(3);
        add(containers, holder.getComponent(InventoryComponent.Hotbar.getComponentType()));
        add(containers, holder.getComponent(InventoryComponent.Storage.getComponentType()));
        add(containers, holder.getComponent(InventoryComponent.Backpack.getComponentType()));
        return containers.isEmpty()
                ? null
                : combined(new CombinedItemContainer(containers.toArray(ItemContainer[]::new)));
    }

    private static void add(@Nonnull List<ItemContainer> containers,
                            @Nullable InventoryComponent component) {
        if (component != null && component.getInventory() != null) {
            containers.add(component.getInventory());
        }
    }

    static CommandLinkedNpcInventoryRepairService.StackAdapter<ItemStack> itemStacks() {
        return new ItemStacks();
    }

    private static final class CombinedAdapter
            implements CommandLinkedNpcInventoryRepairService.ContainerAdapter<ItemStack> {
        private final CombinedItemContainer container;

        private CombinedAdapter(@Nonnull CombinedItemContainer container) {
            this.container = container;
        }

        @Override
        public int capacity() {
            return container.getCapacity();
        }

        @Override
        public ItemStack get(int slot) {
            return container.getItemStack((short) slot);
        }

        @Override
        public boolean set(int slot, @Nonnull ItemStack stack) {
            var transaction = container.setItemStackForSlot((short) slot, stack);
            return transaction != null && transaction.succeeded();
        }
    }

    private static final class ItemStacks
            implements CommandLinkedNpcInventoryRepairService.StackAdapter<ItemStack> {
        private final LinkedNpcRecordCodec codec = new LinkedNpcRecordCodec();

        @Override
        public boolean isEmpty(@Nonnull ItemStack stack) {
            return stack.isEmpty();
        }

        @Override
        public String itemId(@Nonnull ItemStack stack) {
            return stack.getItemId();
        }

        @Override
        public String toolId(@Nonnull ItemStack stack) {
            return stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
        }

        @Override
        public CommandLinkedNpcInventoryRepairService.StackRecords readRecords(@Nonnull ItemStack stack) {
            String encoded = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING);
            if (encoded == null || encoded.isBlank()) {
                return CommandLinkedNpcInventoryRepairService.StackRecords.valid(List.of());
            }
            ArrayList<LinkedNpcRecord> records = new ArrayList<>();
            for (String line : encoded.split("\\R")) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                LinkedNpcRecord record = codec.parse(line);
                if (record == null || record.npcUuid == null) {
                    return CommandLinkedNpcInventoryRepairService.StackRecords.invalid();
                }
                records.add(record);
            }
            return CommandLinkedNpcInventoryRepairService.StackRecords.valid(records);
        }

        @Override
        public ItemStack writeRecords(@Nonnull ItemStack stack, @Nonnull List<LinkedNpcRecord> records) {
            StringBuilder encoded = new StringBuilder();
            for (LinkedNpcRecord record : records) {
                if (record == null || record.npcUuid == null) {
                    return null;
                }
                if (encoded.length() > 0) {
                    encoded.append('\n');
                }
                encoded.append(codec.encode(record));
            }
            return stack.withMetadata(
                    TameworkMetadataKeys.COMMAND_LINKED_NPCS,
                    Codec.STRING,
                    encoded.toString()
            );
        }
    }
}
