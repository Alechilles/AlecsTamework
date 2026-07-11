package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.joml.Vector3d;

/**
 * Applies profile-first identity, deduplication, and mutation rules to linked-NPC record lists.
 */
final class LinkedNpcRecordCollection {
    List<LinkedNpcRecord> deduplicate(List<LinkedNpcRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        ArrayList<LinkedNpcRecord> deduplicated = new ArrayList<>(records.size());
        Set<String> seen = new HashSet<>();
        for (LinkedNpcRecord record : records) {
            if (record != null && record.npcUuid != null && seen.add(identityKey(record))) {
                deduplicated.add(record);
            }
        }
        return deduplicated;
    }

    List<LinkedNpcRecord> upsert(List<LinkedNpcRecord> source,
                                 String profileId,
                                 UUID npcUuid,
                                 Vector3d position,
                                 String lastKnownWorldName,
                                 Vector3d homePosition,
                                 String cachedDisplayName,
                                 String cachedNameKey,
                                 String cachedRoleId,
                                 Boolean activeOverride,
                                 String cachedCommandState) {
        ArrayList<LinkedNpcRecord> records = new ArrayList<>(source != null ? source : List.of());
        String normalizedProfileId = LinkedNpcRecordCodec.normalizeProfileId(profileId);
        int matchingIndex = findUpsertIndex(records, normalizedProfileId, npcUuid);
        if (matchingIndex < 0) {
            records.add(newRecord(
                    normalizedProfileId, npcUuid, position, lastKnownWorldName, homePosition,
                    cachedDisplayName, cachedNameKey, cachedRoleId, activeOverride, cachedCommandState));
            return deduplicate(records);
        }
        LinkedNpcRecord existing = records.get(matchingIndex);
        records.set(matchingIndex, merge(
                existing, normalizedProfileId, npcUuid, position, lastKnownWorldName, homePosition,
                cachedDisplayName, cachedNameKey, cachedRoleId, activeOverride, cachedCommandState));
        return deduplicate(records);
    }

    List<LinkedNpcRecord> removeByUuid(List<LinkedNpcRecord> source, UUID npcUuid) {
        if (source == null || source.isEmpty() || npcUuid == null) {
            return source != null ? source : List.of();
        }
        ArrayList<LinkedNpcRecord> filtered = new ArrayList<>(source.size());
        for (LinkedNpcRecord record : source) {
            if (record != null && record.npcUuid != null && !npcUuid.equals(record.npcUuid)) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    List<LinkedNpcRecord> removeByIdentity(List<LinkedNpcRecord> source,
                                           String profileId,
                                           UUID fallbackNpcUuid) {
        if (source == null || source.isEmpty()) {
            return source != null ? source : List.of();
        }
        String normalizedProfileId = LinkedNpcRecordCodec.normalizeProfileId(profileId);
        ArrayList<LinkedNpcRecord> filtered = new ArrayList<>(source.size());
        for (LinkedNpcRecord record : source) {
            if (!shouldRemove(record, normalizedProfileId, fallbackNpcUuid)
                    && record != null && record.npcUuid != null) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    LinkedNpcRecord findByUuid(List<LinkedNpcRecord> records, UUID npcUuid) {
        if (records == null || npcUuid == null) {
            return null;
        }
        for (LinkedNpcRecord record : records) {
            if (record != null && npcUuid.equals(record.npcUuid)) {
                return record;
            }
        }
        return null;
    }

    LinkedNpcRecord findByIdentity(List<LinkedNpcRecord> records,
                                   String profileId,
                                   UUID fallbackNpcUuid) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        String normalizedProfileId = LinkedNpcRecordCodec.normalizeProfileId(profileId);
        LinkedNpcRecord unresolvedFallback = null;
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (normalizedProfileId != null && normalizedProfileId.equals(record.profileId)) {
                return record;
            }
            if (record.profileId == null && fallbackNpcUuid != null && fallbackNpcUuid.equals(record.npcUuid)) {
                if (unresolvedFallback != null) {
                    return null;
                }
                unresolvedFallback = record;
            }
        }
        return unresolvedFallback;
    }

    private int findUpsertIndex(List<LinkedNpcRecord> records, String profileId, UUID npcUuid) {
        int unresolvedProfileFallback = -1;
        int soleResolvedUuidMatch = -1;
        for (int index = 0; index < records.size(); index++) {
            LinkedNpcRecord record = records.get(index);
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (profileId != null) {
                if (profileId.equals(record.profileId)) {
                    return index;
                }
                if (record.profileId == null && npcUuid.equals(record.npcUuid)) {
                    unresolvedProfileFallback = unresolvedProfileFallback == -1 ? index : -2;
                }
                continue;
            }
            if (!npcUuid.equals(record.npcUuid)) {
                continue;
            }
            if (record.profileId == null) {
                return index;
            }
            soleResolvedUuidMatch = soleResolvedUuidMatch == -1 ? index : -2;
        }
        return profileId != null
                ? (unresolvedProfileFallback >= 0 ? unresolvedProfileFallback : -1)
                : (soleResolvedUuidMatch >= 0 ? soleResolvedUuidMatch : -1);
    }

    private boolean shouldRemove(LinkedNpcRecord record, String profileId, UUID fallbackNpcUuid) {
        if (record == null) {
            return false;
        }
        boolean profileMatch = profileId != null && profileId.equals(record.profileId);
        boolean unresolvedFallbackMatch = record.profileId == null
                && fallbackNpcUuid != null
                && fallbackNpcUuid.equals(record.npcUuid);
        return profileMatch || unresolvedFallbackMatch;
    }

    private LinkedNpcRecord merge(LinkedNpcRecord record,
                                  String profileId,
                                  UUID npcUuid,
                                  Vector3d position,
                                  String lastKnownWorldName,
                                  Vector3d homePosition,
                                  String cachedDisplayName,
                                  String cachedNameKey,
                                  String cachedRoleId,
                                  Boolean activeOverride,
                                  String cachedCommandState) {
        return new LinkedNpcRecord(
                npcUuid,
                profileId != null ? profileId : record.profileId,
                position != null ? position : record.lastKnownPosition,
                firstNonBlank(lastKnownWorldName, record.lastKnownWorldName),
                homePosition != null ? homePosition : record.homePosition,
                firstNonBlank(cachedDisplayName, record.cachedDisplayName),
                firstNonBlank(cachedNameKey, record.cachedNameKey),
                firstNonBlank(cachedRoleId, record.cachedRoleId),
                firstNonBlank(cachedCommandState, record.cachedCommandState),
                activeOverride != null ? activeOverride : record.active,
                record.breedingEnabled,
                record.groupId
        );
    }

    private LinkedNpcRecord newRecord(String profileId,
                                      UUID npcUuid,
                                      Vector3d position,
                                      String lastKnownWorldName,
                                      Vector3d homePosition,
                                      String cachedDisplayName,
                                      String cachedNameKey,
                                      String cachedRoleId,
                                      Boolean activeOverride,
                                      String cachedCommandState) {
        return new LinkedNpcRecord(
                npcUuid, profileId, position, lastKnownWorldName, homePosition,
                firstNonBlank(cachedDisplayName, null), firstNonBlank(cachedNameKey, null),
                firstNonBlank(cachedRoleId, null), firstNonBlank(cachedCommandState, null),
                activeOverride == null || activeOverride, false, null
        );
    }

    private String identityKey(LinkedNpcRecord record) {
        return record.profileId != null ? "profile:" + record.profileId : "uuid:" + record.npcUuid;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
