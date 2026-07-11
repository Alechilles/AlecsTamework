package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Rewrites command-record projection UUIDs without confusing two stable NPC profiles.
 *
 * <p>Profile-aware calls select records by profile first. UUID-only calls remain available for
 * legacy callers, but they fail closed when the historical UUID does not identify one profile.
 */
final class CommandLinkedNpcRecordRemapService {
    private static final LinkedNpcRecordCodec RECORD_CODEC = new LinkedNpcRecordCodec();

    private CommandLinkedNpcRecordRemapService() {
    }

    /**
     * Legacy UUID-only hotbar repair. Ambiguous UUID evidence is left untouched.
     */
    static boolean remapLinkedNpcRecordsInHotbar(@Nullable Player player,
                                                 @Nullable UUID oldNpcUuid,
                                                 @Nullable UUID newNpcUuid) {
        return remapLinkedNpcRecordsInHotbar(player, null, oldNpcUuid, newNpcUuid);
    }

    /**
     * Repairs records for one stable profile while retaining the leading cached projection UUID.
     */
    static boolean remapLinkedNpcRecordsInHotbar(@Nullable Player player,
                                                 @Nullable String profileId,
                                                 @Nullable UUID oldNpcUuid,
                                                 @Nullable UUID newNpcUuid) {
        if (player == null || !hasDistinctUuids(oldNpcUuid, newNpcUuid)) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        boolean changedAny = false;
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack slotStack = hotbar.getItemStack(slot);
            if (slotStack == null || slotStack.isEmpty()) {
                continue;
            }
            String encodedLinks = slotStack.getFromMetadataOrNull(
                    TameworkMetadataKeys.COMMAND_LINKED_NPCS,
                    Codec.STRING
            );
            String rewritten = rewriteLinkedNpcRecords(encodedLinks, profileId, oldNpcUuid, newNpcUuid);
            if (rewritten == null || rewritten.equals(encodedLinks)) {
                continue;
            }
            hotbar.setItemStackForSlot(
                    slot,
                    slotStack.withMetadata(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING, rewritten)
            );
            changedAny = true;
        }
        return changedAny;
    }

    /** Returns an explicit fail-closed decision for a legacy UUID-only remap. */
    static RemapResult remapLinkedNpcRecords(@Nullable List<LinkedNpcRecord> source,
                                             @Nullable UUID oldNpcUuid,
                                             @Nullable UUID newNpcUuid) {
        return remapLinkedNpcRecords(source, null, oldNpcUuid, newNpcUuid);
    }

    /**
     * Remaps one stable profile, adopting unresolved UUID-only records only when safe.
     */
    static RemapResult remapLinkedNpcRecords(@Nullable List<LinkedNpcRecord> source,
                                             @Nullable String profileId,
                                             @Nullable UUID oldNpcUuid,
                                             @Nullable UUID newNpcUuid) {
        List<LinkedNpcRecord> records = source != null ? source : List.of();
        if (records.isEmpty() || !hasDistinctUuids(oldNpcUuid, newNpcUuid)) {
            return RemapResult.noMatch(records);
        }
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                return RemapResult.conflict(records);
            }
        }

        Selection selection = selectRecords(records, profileId, oldNpcUuid, newNpcUuid);
        if (selection.status() != RemapStatus.REMAPPED) {
            return new RemapResult(selection.status(), records);
        }
        if (!needsRewrite(records, selection, newNpcUuid)) {
            return RemapResult.noMatch(records);
        }
        return RemapResult.remapped(applySelection(records, selection, newNpcUuid));
    }

    private static Selection selectRecords(List<LinkedNpcRecord> records,
                                           @Nullable String profileId,
                                           UUID oldNpcUuid,
                                           UUID newNpcUuid) {
        String normalizedProfileId = LinkedNpcRecordCodec.normalizeProfileId(profileId);
        return normalizedProfileId != null
                ? selectProfileAware(records, normalizedProfileId, oldNpcUuid, newNpcUuid)
                : selectUuidOnly(records, oldNpcUuid, newNpcUuid);
    }

    private static Selection selectProfileAware(List<LinkedNpcRecord> records,
                                                String profileId,
                                                UUID oldNpcUuid,
                                                UUID newNpcUuid) {
        ArrayList<Integer> profileMatches = new ArrayList<>();
        ArrayList<Integer> unresolvedOldMatches = new ArrayList<>();
        boolean foreignOldEvidence = false;

        for (int index = 0; index < records.size(); index++) {
            LinkedNpcRecord record = records.get(index);
            if (newNpcUuid.equals(record.npcUuid) && !profileId.equals(record.profileId)) {
                return Selection.conflict();
            }
            if (profileId.equals(record.profileId)) {
                profileMatches.add(index);
            } else if (oldNpcUuid.equals(record.npcUuid)) {
                if (record.profileId == null) {
                    unresolvedOldMatches.add(index);
                } else {
                    foreignOldEvidence = true;
                }
            }
        }

        if (profileMatches.isEmpty()) {
            if (foreignOldEvidence || unresolvedOldMatches.size() > 1) {
                return Selection.conflict();
            }
            if (unresolvedOldMatches.isEmpty()) {
                return Selection.noMatch();
            }
            int legacyIndex = unresolvedOldMatches.getFirst();
            return Selection.remap(profileId, Set.of(legacyIndex), legacyIndex);
        }

        Set<Integer> selected = new HashSet<>(profileMatches);
        if (!foreignOldEvidence && unresolvedOldMatches.size() == 1) {
            selected.add(unresolvedOldMatches.getFirst());
        }
        int canonicalIndex = preferredCanonicalIndex(records, profileMatches, profileId, newNpcUuid);
        return Selection.remap(profileId, selected, canonicalIndex);
    }

    private static Selection selectUuidOnly(List<LinkedNpcRecord> records,
                                            UUID oldNpcUuid,
                                            UUID newNpcUuid) {
        ArrayList<Integer> oldMatches = new ArrayList<>();
        Set<String> resolvedProfiles = new HashSet<>();
        for (int index = 0; index < records.size(); index++) {
            LinkedNpcRecord record = records.get(index);
            if (oldNpcUuid.equals(record.npcUuid)) {
                oldMatches.add(index);
                if (record.profileId != null) {
                    resolvedProfiles.add(record.profileId);
                }
            }
        }
        if (oldMatches.isEmpty()) {
            return Selection.noMatch();
        }
        if (resolvedProfiles.size() > 1) {
            return Selection.conflict();
        }

        String resolvedProfile = resolvedProfiles.stream().findFirst().orElse(null);
        if (resolvedProfile == null) {
            if (oldMatches.size() != 1 || hasDestinationEvidence(records, newNpcUuid, Set.of())) {
                return Selection.conflict();
            }
            int legacyIndex = oldMatches.getFirst();
            return Selection.remap(null, Set.of(legacyIndex), legacyIndex);
        }

        Set<Integer> selected = new HashSet<>(oldMatches);
        ArrayList<Integer> resolvedProfileMatches = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            LinkedNpcRecord record = records.get(index);
            if (resolvedProfile.equals(record.profileId)) {
                selected.add(index);
                resolvedProfileMatches.add(index);
                continue;
            }
            if (newNpcUuid.equals(record.npcUuid)) {
                return Selection.conflict();
            }
        }
        int canonicalIndex = preferredCanonicalIndex(
                records,
                resolvedProfileMatches,
                resolvedProfile,
                newNpcUuid
        );
        return Selection.remap(resolvedProfile, selected, canonicalIndex);
    }

    private static boolean hasDestinationEvidence(List<LinkedNpcRecord> records,
                                                  UUID newNpcUuid,
                                                  Set<Integer> ignoredIndexes) {
        for (int index = 0; index < records.size(); index++) {
            if (!ignoredIndexes.contains(index) && newNpcUuid.equals(records.get(index).npcUuid)) {
                return true;
            }
        }
        return false;
    }

    private static int preferredCanonicalIndex(List<LinkedNpcRecord> records,
                                               List<Integer> candidates,
                                               String profileId,
                                               UUID newNpcUuid) {
        for (int index : candidates) {
            LinkedNpcRecord record = records.get(index);
            if (profileId.equals(record.profileId) && newNpcUuid.equals(record.npcUuid)) {
                return index;
            }
        }
        return candidates.getFirst();
    }

    private static boolean needsRewrite(List<LinkedNpcRecord> records,
                                        Selection selection,
                                        UUID newNpcUuid) {
        LinkedNpcRecord canonical = records.get(selection.canonicalIndex());
        return selection.selectedIndexes().size() > 1
                || !newNpcUuid.equals(canonical.npcUuid)
                || !Objects.equals(selection.profileId(), canonical.profileId);
    }

    private static List<LinkedNpcRecord> applySelection(List<LinkedNpcRecord> records,
                                                        Selection selection,
                                                        UUID newNpcUuid) {
        int insertionIndex = selection.selectedIndexes().stream().mapToInt(Integer::intValue).min().orElseThrow();
        LinkedNpcRecord remapped = withIdentity(
                records.get(selection.canonicalIndex()),
                selection.profileId(),
                newNpcUuid
        );
        ArrayList<LinkedNpcRecord> updated = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            if (index == insertionIndex) {
                updated.add(remapped);
            }
            if (!selection.selectedIndexes().contains(index)) {
                updated.add(records.get(index));
            }
        }
        return List.copyOf(updated);
    }

    private static LinkedNpcRecord withIdentity(LinkedNpcRecord record,
                                                @Nullable String profileId,
                                                UUID npcUuid) {
        return new LinkedNpcRecord(
                npcUuid,
                profileId,
                record.lastKnownPosition,
                record.lastKnownWorldName,
                record.homePosition,
                record.cachedDisplayName,
                record.cachedNameKey,
                record.cachedRoleId,
                record.cachedCommandState,
                record.active,
                record.breedingEnabled,
                record.groupId
        );
    }

    @Nullable
    private static String rewriteLinkedNpcRecords(@Nullable String encodedLinks,
                                                  @Nullable String profileId,
                                                  UUID oldNpcUuid,
                                                  UUID newNpcUuid) {
        if (encodedLinks == null || encodedLinks.isBlank()) {
            return encodedLinks;
        }
        ArrayList<String> rawLines = new ArrayList<>();
        ArrayList<LinkedNpcRecord> records = new ArrayList<>();
        for (String line : encodedLinks.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            LinkedNpcRecord record = RECORD_CODEC.parse(line);
            if (record == null) {
                return encodedLinks;
            }
            rawLines.add(line.trim());
            records.add(record);
        }
        Selection selection = selectRecords(records, profileId, oldNpcUuid, newNpcUuid);
        if (selection.status() != RemapStatus.REMAPPED
                || !needsRewrite(records, selection, newNpcUuid)) {
            return encodedLinks;
        }
        return rewriteSelectedLines(rawLines, records, selection, newNpcUuid);
    }

    private static String rewriteSelectedLines(List<String> rawLines,
                                               List<LinkedNpcRecord> records,
                                               Selection selection,
                                               UUID newNpcUuid) {
        int insertionIndex = selection.selectedIndexes().stream().mapToInt(Integer::intValue).min().orElseThrow();
        LinkedNpcRecord canonical = records.get(selection.canonicalIndex());
        String remappedLine = rewriteIdentityToken(
                rawLines.get(selection.canonicalIndex()),
                canonical,
                selection.profileId(),
                newNpcUuid
        );
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < rawLines.size(); index++) {
            if (index == insertionIndex) {
                appendEncodedLine(encoded, remappedLine);
            }
            if (!selection.selectedIndexes().contains(index)) {
                appendEncodedLine(encoded, rawLines.get(index));
            }
        }
        return encoded.toString();
    }

    private static String rewriteIdentityToken(String rawLine,
                                               LinkedNpcRecord original,
                                               @Nullable String profileId,
                                               UUID newNpcUuid) {
        int separator = rawLine.indexOf('|');
        String suffix = separator >= 0 ? rawLine.substring(separator) : "";
        StringBuilder rewritten = new StringBuilder(newNpcUuid.toString()).append(suffix);
        if (original.profileId == null && profileId != null) {
            rewritten.append("|pid=").append(profileId);
        }
        return rewritten.toString();
    }

    private static void appendEncodedLine(StringBuilder target, String line) {
        if (target.length() > 0) {
            target.append('\n');
        }
        target.append(line);
    }

    private static boolean hasDistinctUuids(@Nullable UUID oldNpcUuid, @Nullable UUID newNpcUuid) {
        return oldNpcUuid != null && newNpcUuid != null && !oldNpcUuid.equals(newNpcUuid);
    }

    enum RemapStatus {
        REMAPPED,
        NO_MATCH,
        CONFLICT
    }

    record RemapResult(RemapStatus status, List<LinkedNpcRecord> records) {
        RemapResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(records, "records");
        }

        boolean changed() {
            return status == RemapStatus.REMAPPED;
        }

        private static RemapResult remapped(List<LinkedNpcRecord> records) {
            return new RemapResult(RemapStatus.REMAPPED, records);
        }

        private static RemapResult noMatch(List<LinkedNpcRecord> records) {
            return new RemapResult(RemapStatus.NO_MATCH, records);
        }

        private static RemapResult conflict(List<LinkedNpcRecord> records) {
            return new RemapResult(RemapStatus.CONFLICT, records);
        }
    }

    private record Selection(RemapStatus status,
                             @Nullable String profileId,
                             Set<Integer> selectedIndexes,
                             int canonicalIndex) {
        private static Selection remap(@Nullable String profileId,
                                       Set<Integer> selectedIndexes,
                                       int canonicalIndex) {
            return new Selection(RemapStatus.REMAPPED, profileId, Set.copyOf(selectedIndexes), canonicalIndex);
        }

        private static Selection noMatch() {
            return new Selection(RemapStatus.NO_MATCH, null, Set.of(), -1);
        }

        private static Selection conflict() {
            return new Selection(RemapStatus.CONFLICT, null, Set.of(), -1);
        }
    }
}
