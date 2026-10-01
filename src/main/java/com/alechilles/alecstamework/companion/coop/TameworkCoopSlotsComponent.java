package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.BsonDocumentCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * One coop's resident slots (spec 8.9), saved with its block. Codec id "TameworkCoopSlots". It
 * replaces the retired {@code TameworkCoopCaptureReceipts}, which stays registered so old blocks
 * still load and the coop schedule system can strip it.
 *
 * <p>The component is immutable: {@link #with} and {@link #without} return copies, so a copy can
 * be put on the block without sharing state with the one it replaced.
 */
public final class TameworkCoopSlotsComponent implements Component<ChunkStore> {
    public static final String CODEC_ID = "TameworkCoopSlots";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * One resident. {@code profileId} and {@code generation} for a companion; {@code unownedEntity}
     * (BSON entity document) for an unowned resident, which has no record and keeps its state
     * (tamed or wild) as stored. {@code producedUntilMs} is the production watermark on the
     * resident's active-time clock, 0 for none. Treat the entity document as read-only.
     */
    public record Slot(int slot, @Nullable UUID profileId, long generation, @Nullable BsonDocument unownedEntity,
                       long producedUntilMs) {
        public Slot {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative");
            }
            if ((profileId == null) == (unownedEntity == null)) {
                throw new IllegalArgumentException("a slot holds either a companion or an unowned entity");
            }
        }

        @Nonnull
        public static Slot companion(int slot, @Nonnull UUID profileId, long generation) {
            return new Slot(slot, profileId, generation, null, 0L);
        }

        @Nonnull
        public static Slot unowned(int slot, @Nonnull BsonDocument entity) {
            return new Slot(slot, null, 0L, entity, 0L);
        }
    }

    /** The saved shape of one slot; invalid entries are dropped on load. */
    private static final class Entry {
        private Integer slot;
        private UUID profileId;
        private Long generation;
        private BsonDocument unownedEntity;
        private Long producedUntilMs;
    }

    @SuppressWarnings("deprecation")
    private static final BuilderCodec<Entry> ENTRY_CODEC = BuilderCodec.builder(Entry.class, Entry::new)
            .<Integer>append(new KeyedCodec<>("Slot", Codec.INTEGER), (e, v) -> e.slot = v, e -> e.slot).add()
            .<UUID>append(new KeyedCodec<>("ProfileId", new UUIDBinaryCodec()),
                    (e, v) -> e.profileId = v, e -> e.profileId).add()
            .<Long>append(new KeyedCodec<>("Generation", Codec.LONG), (e, v) -> e.generation = v, e -> e.generation).add()
            .<BsonDocument>append(new KeyedCodec<>("UnownedEntity", new BsonDocumentCodec()),
                    (e, v) -> e.unownedEntity = v, e -> e.unownedEntity).add()
            .<Long>append(new KeyedCodec<>("ProducedUntilMs", Codec.LONG),
                    (e, v) -> e.producedUntilMs = v, e -> e.producedUntilMs).add()
            .build();

    public static final BuilderCodec<TameworkCoopSlotsComponent> CODEC = BuilderCodec.builder(
                    TameworkCoopSlotsComponent.class, TameworkCoopSlotsComponent::new)
            .<Entry[]>append(new KeyedCodec<>("Slots", new ArrayCodec<>(ENTRY_CODEC, Entry[]::new)),
                    TameworkCoopSlotsComponent::decode, TameworkCoopSlotsComponent::encode)
            .add()
            .build();

    @Nullable private static ComponentType<ChunkStore, TameworkCoopSlotsComponent> type;
    @Nullable private static ComponentType<ChunkStore, ? extends Component<ChunkStore>> retiredReceiptsType;

    private List<Slot> slots = List.of();

    public TameworkCoopSlotsComponent() {
    }

    private TameworkCoopSlotsComponent(List<Slot> slots) {
        this.slots = List.copyOf(slots);
    }

    /**
     * Registers the component. {@code retiredReceipts} is the registered type of the receipts
     * component this one replaces; the coop schedule system strips it from coop blocks.
     */
    public static void register(@Nonnull Tamework plugin,
                                @Nullable ComponentType<ChunkStore, ? extends Component<ChunkStore>> retiredReceipts) {
        type = plugin.getChunkStoreRegistry().registerComponent(TameworkCoopSlotsComponent.class, CODEC_ID, CODEC);
        retiredReceiptsType = retiredReceipts;
    }

    /** The registered type, or {@code null} before {@link #register} runs. */
    @Nullable
    public static ComponentType<ChunkStore, TameworkCoopSlotsComponent> getComponentType() {
        return type;
    }

    /** The retired receipts type, or {@code null} before {@link #register} runs. */
    @Nullable
    public static ComponentType<ChunkStore, ? extends Component<ChunkStore>> retiredReceiptsType() {
        return retiredReceiptsType;
    }

    /** The entries, ordered by slot number. */
    @Nonnull
    public List<Slot> slots() {
        return slots;
    }

    /** The entry for {@code slot}, or null. */
    @Nullable
    public Slot get(int slot) {
        for (Slot entry : slots) {
            if (entry.slot() == slot) {
                return entry;
            }
        }
        return null;
    }

    /** A copy holding {@code slot}, replacing any entry with the same slot number. */
    @Nonnull
    public TameworkCoopSlotsComponent with(@Nonnull Slot slot) {
        List<Slot> next = new ArrayList<>(slots.size() + 1);
        for (Slot entry : slots) {
            if (entry.slot() != slot.slot()) {
                next.add(entry);
            }
        }
        next.add(slot);
        next.sort(Comparator.comparingInt(Slot::slot));
        return new TameworkCoopSlotsComponent(next);
    }

    /** A copy without the entry for {@code slot}. */
    @Nonnull
    public TameworkCoopSlotsComponent without(int slot) {
        List<Slot> next = new ArrayList<>(slots.size());
        for (Slot entry : slots) {
            if (entry.slot() != slot) {
                next.add(entry);
            }
        }
        return new TameworkCoopSlotsComponent(next);
    }

    /** Entries are immutable and their documents read-only, so the copy shares them. */
    @Override
    @Nonnull
    public TameworkCoopSlotsComponent clone() {
        return new TameworkCoopSlotsComponent(slots);
    }

    private Entry[] encode() {
        Entry[] out = new Entry[slots.size()];
        for (int i = 0; i < out.length; i++) {
            Slot slot = slots.get(i);
            Entry entry = new Entry();
            entry.slot = slot.slot();
            entry.profileId = slot.profileId();
            entry.generation = slot.profileId() == null ? null : slot.generation();
            entry.unownedEntity = slot.unownedEntity();
            entry.producedUntilMs = slot.producedUntilMs() == 0L ? null : slot.producedUntilMs();
            out[i] = entry;
        }
        return out;
    }

    /** Drops an entry that is malformed or repeats a slot number, so a damaged block still loads. */
    private void decode(@Nullable Entry[] entries) {
        TameworkCoopSlotsComponent decoded = new TameworkCoopSlotsComponent();
        if (entries != null) {
            for (Entry entry : entries) {
                Slot slot = toSlot(entry);
                if (slot == null || decoded.get(slot.slot()) != null) {
                    LOGGER.at(Level.WARNING).log("Dropped an unreadable or duplicate coop slot entry");
                    continue;
                }
                decoded = decoded.with(slot);
            }
        }
        slots = decoded.slots;
    }

    @Nullable
    private static Slot toSlot(@Nullable Entry entry) {
        if (entry == null || entry.slot == null) {
            return null;
        }
        try {
            return new Slot(entry.slot, entry.profileId, entry.generation == null ? 0L : entry.generation,
                    entry.unownedEntity, entry.producedUntilMs == null ? 0L : entry.producedUntilMs);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
