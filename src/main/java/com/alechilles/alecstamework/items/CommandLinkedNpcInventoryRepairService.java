package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Repairs every inventory copy of command-item records after an NPC projection UUID changes.
 *
 * <p>The production entry point is deliberately synchronous and must be called on the player's
 * world thread. It scans Hytale's hotbar-first combined hotbar/storage/backpack container. Stable
 * profile identity always wins over projection UUID evidence, so a UUID collision can never
 * rewrite a record already resolved to another profile.</p>
 */
public final class CommandLinkedNpcInventoryRepairService {
    private final CommandItemRegistry registry;
    private final StackAdapter<ItemStack> itemStackAdapter = CommandLinkedNpcInventoryAdapters.itemStacks();
    @Nullable
    private final StackCanonicalizer stackCanonicalizer;

    public CommandLinkedNpcInventoryRepairService(@Nullable CommandItemRegistry registry) {
        this(registry, (StackCanonicalizer) null);
    }

    CommandLinkedNpcInventoryRepairService(@Nullable CommandItemRegistry registry,
                                           @Nullable CommandNpcProfileActionResolver resolver) {
        this(registry, resolver == null ? null : records -> {
            CommandNpcProfileActionResolver.CanonicalRecords canonical =
                    resolver.canonicalizeRecords(records);
            return new CanonicalStackRecords(
                    canonical.records(), canonical.safeToPersist(), canonical.identityChanged());
        });
    }

    private CommandLinkedNpcInventoryRepairService(@Nullable CommandItemRegistry registry,
                                                    @Nullable StackCanonicalizer stackCanonicalizer) {
        this.registry = registry;
        this.stackCanonicalizer = stackCanonicalizer;
    }

    /**
     * Repairs all enabled command-item stacks in the player's combined inventory.
     * Callers are responsible for invoking this method on the player's current world thread.
     */
    @Nonnull
    public RepairResult repair(@Nullable Player player, @Nonnull RepairRequest request) {
        if (player == null || player.getInventory() == null) {
            return RepairResult.empty();
        }
        Inventory inventory = player.getInventory();
        CombinedItemContainer combined = inventory.getCombinedBackpackStorageHotbarFirst();
        if (combined == null) {
            return RepairResult.empty();
        }
        return repair(
                CommandLinkedNpcInventoryAdapters.combined(combined),
                itemStackAdapter,
                this::isEnabledCommandItem,
                request
        );
    }

    /** Pure adapter boundary used by focused tests and non-Player reconciliation callers. */
    @Nonnull
    <S> RepairResult repair(@Nonnull ContainerAdapter<S> container,
                            @Nonnull StackAdapter<S> stacks,
                            @Nonnull Predicate<String> enabledItem,
                            @Nonnull RepairRequest request) {
        int scannedSlots = 0;
        int enabledStacks = 0;
        int matchedStacks = 0;
        int updatedStacks = 0;
        int matchedRecords = 0;
        int deduplicatedRecords = 0;
        int invalidStacks = 0;
        TreeSet<String> affectedToolIds = new TreeSet<>();

        for (int slot = 0; slot < container.capacity(); slot++) {
            scannedSlots++;
            S stack = container.get(slot);
            if (stack == null || stacks.isEmpty(stack) || !enabledItem.test(stacks.itemId(stack))) {
                continue;
            }
            enabledStacks++;
            StackRecords decoded = stacks.readRecords(stack);
            if (decoded == null || !decoded.valid()) {
                invalidStacks++;
                continue;
            }
            RepairPlan plan = buildPlan(decoded.records(), request);
            if (!plan.matched()) {
                continue;
            }
            matchedStacks++;
            matchedRecords += plan.matchedRecords();
            if (!plan.changed()) {
                continue;
            }
            S updated = stacks.writeRecords(stack, plan.records());
            if (updated == null) {
                invalidStacks++;
                continue;
            }
            if (!container.set(slot, updated)) {
                invalidStacks++;
                continue;
            }
            updatedStacks++;
            deduplicatedRecords += Math.max(0, plan.matchedRecords() - 1);
            String toolId = normalize(stacks.toolId(updated));
            if (toolId != null) {
                affectedToolIds.add(toolId);
            }
        }
        return new RepairResult(
                scannedSlots,
                enabledStacks,
                matchedStacks,
                updatedStacks,
                matchedRecords,
                deduplicatedRecords,
                invalidStacks,
                List.copyOf(affectedToolIds)
        );
    }

    /**
     * Canonicalizes every enabled command-item stack in hotbar, storage, and backpack order.
     * Callers must invoke this method at a command/event boundary on the player's world thread.
     */
    @Nonnull
    public CanonicalizationResult canonicalize(@Nullable Player player) {
        if (player == null || player.getInventory() == null || stackCanonicalizer == null) {
            return CanonicalizationResult.empty();
        }
        CombinedItemContainer combined =
                player.getInventory().getCombinedBackpackStorageHotbarFirst();
        if (combined == null) {
            return CanonicalizationResult.empty();
        }
        return canonicalize(
                CommandLinkedNpcInventoryAdapters.combined(combined),
                itemStackAdapter,
                this::isEnabledCommandItem,
                stackCanonicalizer
        );
    }

    /** Canonicalizes a loaded player holder before it is inserted into the destination world. */
    @Nonnull
    public CanonicalizationResult canonicalize(@Nullable Holder<EntityStore> holder) {
        if (stackCanonicalizer == null) {
            return CanonicalizationResult.empty();
        }
        ContainerAdapter<ItemStack> inventory =
                CommandLinkedNpcInventoryAdapters.playerInventory(holder);
        if (inventory == null) {
            return CanonicalizationResult.empty();
        }
        return canonicalize(
                inventory,
                itemStackAdapter,
                this::isEnabledCommandItem,
                stackCanonicalizer
        );
    }

    /** Pure adapter boundary used by focused inventory-compartment tests. */
    @Nonnull
    <S> CanonicalizationResult canonicalize(@Nonnull ContainerAdapter<S> container,
                                            @Nonnull StackAdapter<S> stacks,
                                            @Nonnull Predicate<String> enabledItem,
                                            @Nonnull StackCanonicalizer canonicalizer) {
        int scannedSlots = 0;
        int enabledStacks = 0;
        int updatedStacks = 0;
        int deduplicatedRecords = 0;
        int unsafeStacks = 0;
        int invalidStacks = 0;
        TreeSet<String> affectedToolIds = new TreeSet<>();

        for (int slot = 0; slot < container.capacity(); slot++) {
            scannedSlots++;
            S stack = container.get(slot);
            if (stack == null || stacks.isEmpty(stack) || !enabledItem.test(stacks.itemId(stack))) {
                continue;
            }
            enabledStacks++;
            StackRecords decoded = stacks.readRecords(stack);
            if (decoded == null || !decoded.valid()) {
                invalidStacks++;
                continue;
            }
            CanonicalStackRecords canonical = canonicalizer.canonicalize(decoded.records());
            if (canonical == null || !canonical.safeToPersist()) {
                unsafeStacks++;
                continue;
            }
            if (!canonical.changed()) {
                continue;
            }
            S updated = stacks.writeRecords(stack, canonical.records());
            if (updated == null || !container.set(slot, updated)) {
                invalidStacks++;
                continue;
            }
            updatedStacks++;
            deduplicatedRecords += Math.max(0, decoded.records().size() - canonical.records().size());
            String toolId = normalize(stacks.toolId(updated));
            if (toolId != null) {
                affectedToolIds.add(toolId);
            }
        }
        return new CanonicalizationResult(
                scannedSlots,
                enabledStacks,
                updatedStacks,
                deduplicatedRecords,
                unsafeStacks,
                invalidStacks,
                List.copyOf(affectedToolIds)
        );
    }

    private RepairPlan buildPlan(@Nullable List<LinkedNpcRecord> source, @Nonnull RepairRequest request) {
        List<LinkedNpcRecord> records = source != null ? source : List.of();
        ArrayList<Integer> matches = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            LinkedNpcRecord record = records.get(index);
            if (record == null || record.npcUuid == null) {
                return RepairPlan.noMatch(records);
            }
            if (request.profileId.equals(record.profileId)
                    || (record.profileId == null && request.aliases.contains(record.npcUuid))) {
                matches.add(index);
            }
        }
        if (matches.isEmpty()) {
            return RepairPlan.noMatch(records);
        }

        int canonicalIndex = selectCanonical(records, matches, request);
        LinkedNpcRecord canonical = records.get(canonicalIndex);
        LinkedNpcRecord repaired = mergeRecord(records, matches, canonical, request);
        int insertionIndex = matches.getFirst();
        ArrayList<LinkedNpcRecord> updated = new ArrayList<>(records.size() - matches.size() + 1);
        for (int index = 0; index < records.size(); index++) {
            if (index == insertionIndex) {
                updated.add(repaired);
            }
            if (!matches.contains(index)) {
                updated.add(records.get(index));
            }
        }
        boolean changed = matches.size() > 1 || !sameRecord(canonical, repaired) || canonicalIndex != insertionIndex;
        return new RepairPlan(true, changed, matches.size(), changed ? List.copyOf(updated) : records);
    }

    private int selectCanonical(@Nonnull List<LinkedNpcRecord> records,
                                @Nonnull List<Integer> matches,
                                @Nonnull RepairRequest request) {
        for (int index : matches) {
            LinkedNpcRecord record = records.get(index);
            if (request.profileId.equals(record.profileId) && request.currentNpcUuid.equals(record.npcUuid)) {
                return index;
            }
        }
        for (int index : matches) {
            if (request.profileId.equals(records.get(index).profileId)) {
                return index;
            }
        }
        for (int index : matches) {
            if (request.currentNpcUuid.equals(records.get(index).npcUuid)) {
                return index;
            }
        }
        return matches.getFirst();
    }

    private LinkedNpcRecord mergeRecord(@Nonnull List<LinkedNpcRecord> records,
                                        @Nonnull List<Integer> matches,
                                        @Nonnull LinkedNpcRecord canonical,
                                        @Nonnull RepairRequest request) {
        return new LinkedNpcRecord(
                request.currentNpcUuid,
                request.profileId,
                firstVector(request.position(), canonical.lastKnownPosition, records, matches, Field.POSITION),
                firstString(request.worldName, canonical.lastKnownWorldName, records, matches, Field.WORLD),
                firstVector(request.homePosition(), canonical.homePosition, records, matches, Field.HOME),
                firstString(request.displayName, canonical.cachedDisplayName, records, matches, Field.DISPLAY),
                firstString(request.nameKey, canonical.cachedNameKey, records, matches, Field.NAME_KEY),
                firstString(request.roleId, canonical.cachedRoleId, records, matches, Field.ROLE),
                firstString(request.commandState, canonical.cachedCommandState, records, matches, Field.COMMAND_STATE),
                canonical.active,
                canonical.breedingEnabled,
                canonical.groupId
        );
    }

    @Nullable
    private Vector3d firstVector(@Nullable Vector3d fresh,
                                 @Nullable Vector3d canonical,
                                 @Nonnull List<LinkedNpcRecord> records,
                                 @Nonnull List<Integer> matches,
                                 @Nonnull Field field) {
        if (fresh != null) {
            return fresh;
        }
        if (canonical != null) {
            return canonical;
        }
        for (int index : matches) {
            Vector3d candidate = field == Field.POSITION
                    ? records.get(index).lastKnownPosition
                    : records.get(index).homePosition;
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    @Nullable
    private String firstString(@Nullable String fresh,
                               @Nullable String canonical,
                               @Nonnull List<LinkedNpcRecord> records,
                               @Nonnull List<Integer> matches,
                               @Nonnull Field field) {
        if (fresh != null) {
            return fresh;
        }
        if (canonical != null) {
            return canonical;
        }
        for (int index : matches) {
            String candidate = field.read(records.get(index));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private boolean sameRecord(@Nonnull LinkedNpcRecord left, @Nonnull LinkedNpcRecord right) {
        return Objects.equals(left.npcUuid, right.npcUuid)
                && Objects.equals(left.profileId, right.profileId)
                && Objects.equals(left.lastKnownPosition, right.lastKnownPosition)
                && Objects.equals(left.lastKnownWorldName, right.lastKnownWorldName)
                && Objects.equals(left.homePosition, right.homePosition)
                && Objects.equals(left.cachedDisplayName, right.cachedDisplayName)
                && Objects.equals(left.cachedNameKey, right.cachedNameKey)
                && Objects.equals(left.cachedRoleId, right.cachedRoleId)
                && Objects.equals(left.cachedCommandState, right.cachedCommandState)
                && left.active == right.active
                && left.breedingEnabled == right.breedingEnabled
                && Objects.equals(left.groupId, right.groupId);
    }

    private boolean isEnabledCommandItem(@Nullable String itemId) {
        TwCommandItemConfig config = registry != null ? registry.get(itemId) : null;
        return config != null && config.isEnabled();
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    interface ContainerAdapter<S> {
        int capacity();

        @Nullable
        S get(int slot);

        boolean set(int slot, @Nonnull S stack);
    }

    interface StackAdapter<S> {
        boolean isEmpty(@Nonnull S stack);

        @Nullable
        String itemId(@Nonnull S stack);

        @Nullable
        String toolId(@Nonnull S stack);

        @Nonnull
        StackRecords readRecords(@Nonnull S stack);

        @Nullable
        S writeRecords(@Nonnull S stack, @Nonnull List<LinkedNpcRecord> records);
    }

    record StackRecords(boolean valid, @Nonnull List<LinkedNpcRecord> records) {
        StackRecords {
            records = List.copyOf(records);
        }

        static StackRecords valid(@Nonnull List<LinkedNpcRecord> records) {
            return new StackRecords(true, records);
        }

        static StackRecords invalid() {
            return new StackRecords(false, List.of());
        }
    }

    @FunctionalInterface
    interface StackCanonicalizer {
        @Nonnull
        CanonicalStackRecords canonicalize(@Nonnull List<LinkedNpcRecord> records);
    }

    record CanonicalStackRecords(@Nonnull List<LinkedNpcRecord> records,
                                 boolean safeToPersist,
                                 boolean changed) {
        CanonicalStackRecords {
            records = List.copyOf(records);
        }
    }

    /** Deterministic totals for one whole-inventory canonicalization pass. */
    public record CanonicalizationResult(int scannedSlots,
                                         int enabledCommandStacks,
                                         int updatedStacks,
                                         int deduplicatedRecords,
                                         int unsafeStacks,
                                         int invalidStacks,
                                         @Nonnull List<String> affectedToolIds) {
        public CanonicalizationResult {
            affectedToolIds = List.copyOf(affectedToolIds);
        }

        static CanonicalizationResult empty() {
            return new CanonicalizationResult(0, 0, 0, 0, 0, 0, List.of());
        }
    }

    /** Deterministic repair totals and sorted unique tool IDs changed by this pass. */
    public record RepairResult(int scannedSlots,
                               int enabledCommandStacks,
                               int matchedStacks,
                               int updatedStacks,
                               int matchedRecords,
                               int deduplicatedRecords,
                               int invalidStacks,
                               @Nonnull List<String> affectedToolIds) {
        public RepairResult {
            affectedToolIds = List.copyOf(affectedToolIds);
        }

        static RepairResult empty() {
            return new RepairResult(0, 0, 0, 0, 0, 0, 0, List.of());
        }
    }

    /** Immutable profile-first evidence and optional fresh cached state for one repair pass. */
    public static final class RepairRequest {
        private final String profileId;
        private final UUID currentNpcUuid;
        private final Set<UUID> aliases;
        private final Vector3d position;
        private final String worldName;
        private final Vector3d homePosition;
        private final String displayName;
        private final String nameKey;
        private final String roleId;
        private final String commandState;

        public RepairRequest(@Nonnull String profileId,
                             @Nonnull UUID currentNpcUuid,
                             @Nullable Set<UUID> aliases,
                             @Nullable Vector3d position,
                             @Nullable String worldName,
                             @Nullable Vector3d homePosition,
                             @Nullable String displayName,
                             @Nullable String nameKey,
                             @Nullable String roleId,
                             @Nullable String commandState) {
            String normalizedProfileId = LinkedNpcRecordCodec.normalizeProfileId(profileId);
            if (normalizedProfileId == null) {
                throw new IllegalArgumentException("profileId is required");
            }
            this.profileId = normalizedProfileId;
            this.currentNpcUuid = Objects.requireNonNull(currentNpcUuid, "currentNpcUuid");
            LinkedHashSet<UUID> knownAliases = new LinkedHashSet<>();
            if (aliases != null) {
                for (UUID alias : aliases) {
                    if (alias != null) {
                        knownAliases.add(alias);
                    }
                }
            }
            knownAliases.add(currentNpcUuid);
            this.aliases = Set.copyOf(knownAliases);
            this.position = position != null ? new Vector3d(position) : null;
            this.worldName = normalize(worldName);
            this.homePosition = homePosition != null ? new Vector3d(homePosition) : null;
            this.displayName = normalize(displayName);
            this.nameKey = normalize(nameKey);
            this.roleId = normalize(roleId);
            this.commandState = normalize(commandState);
        }

        @Nonnull
        public String profileId() {
            return profileId;
        }

        @Nonnull
        public UUID currentNpcUuid() {
            return currentNpcUuid;
        }

        @Nonnull
        public Set<UUID> aliases() {
            return aliases;
        }

        @Nullable
        public Vector3d position() {
            return position != null ? new Vector3d(position) : null;
        }

        @Nullable
        public String worldName() {
            return worldName;
        }

        @Nullable
        public Vector3d homePosition() {
            return homePosition != null ? new Vector3d(homePosition) : null;
        }

        @Nullable
        public String displayName() {
            return displayName;
        }

        @Nullable
        public String nameKey() {
            return nameKey;
        }

        @Nullable
        public String roleId() {
            return roleId;
        }

        @Nullable
        public String commandState() {
            return commandState;
        }
    }

    private enum Field {
        POSITION(null),
        WORLD(record -> record.lastKnownWorldName),
        HOME(null),
        DISPLAY(record -> record.cachedDisplayName),
        NAME_KEY(record -> record.cachedNameKey),
        ROLE(record -> record.cachedRoleId),
        COMMAND_STATE(record -> record.cachedCommandState);

        private final java.util.function.Function<LinkedNpcRecord, String> reader;

        Field(@Nullable java.util.function.Function<LinkedNpcRecord, String> reader) {
            this.reader = reader;
        }

        @Nullable
        String read(@Nonnull LinkedNpcRecord record) {
            return reader != null ? reader.apply(record) : null;
        }
    }

    private record RepairPlan(boolean matched,
                              boolean changed,
                              int matchedRecords,
                              @Nonnull List<LinkedNpcRecord> records) {
        private static RepairPlan noMatch(@Nonnull List<LinkedNpcRecord> records) {
            return new RepairPlan(false, false, 0, records);
        }
    }

}
