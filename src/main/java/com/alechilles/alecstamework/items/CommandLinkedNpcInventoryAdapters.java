package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/** Production Hytale inventory adapters for profile-first command-record repair. */
final class CommandLinkedNpcInventoryAdapters {
    private CommandLinkedNpcInventoryAdapters() {
    }

    static CommandLinkedNpcInventoryRepairService.ContainerAdapter<ItemStack> combined(
            @Nonnull CombinedItemContainer container) {
        return new CombinedAdapter(container);
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
        public void set(int slot, @Nonnull ItemStack stack) {
            container.setItemStackForSlot((short) slot, stack);
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
