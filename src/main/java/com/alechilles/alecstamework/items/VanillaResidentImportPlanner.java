package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Produces a deterministic, report-only plan for vanilla residents discovered on one managed coop.
 *
 * <p>The planner never inspects live game state and never guesses from role or display name. Only
 * exact UUID and stable-profile evidence can match an existing managed resident.
 */
public final class VanillaResidentImportPlanner {
    private static final Comparator<VanillaResidentEvidence> SOURCE_ORDER = Comparator
            .comparingInt(VanillaResidentEvidence::sourceOrder)
            .thenComparing(evidence -> evidence.sourceSlot() == null ? Integer.MAX_VALUE : evidence.sourceSlot())
            .thenComparing(VanillaResidentEvidence::sourceFingerprint)
            .thenComparing(VanillaResidentEvidence::sourcePayload)
            .thenComparing(evidence -> evidence.persistentUuid() == null
                    ? "" : evidence.persistentUuid().toString())
            .thenComparing(evidence -> nullableOrderKey(evidence.resolvedProfileId()))
            .thenComparing(evidence -> nullableOrderKey(evidence.roleId()))
            .thenComparing(evidence -> nullableOrderKey(evidence.displayName()));

    public enum Classification {
        MATCH_EXISTING,
        IMPORTABLE,
        CONFLICT,
        OVERFLOW
    }

    public enum Reason {
        EXACT_UUID_MATCH,
        EXACT_PROFILE_MATCH,
        EXACT_UUID_AND_PROFILE_MATCH,
        NEW_DISTINCT_SOURCE,
        CAPACITY_EXCEEDED,
        DUPLICATE_SOURCE_FINGERPRINT,
        DUPLICATE_SOURCE_ORDER,
        DUPLICATE_SOURCE_SLOT,
        DUPLICATE_PERSISTENT_UUID,
        DUPLICATE_RESOLVED_PROFILE,
        MANAGED_AUTHORITY_MISMATCH,
        MANAGED_DUPLICATE_SLOT,
        MANAGED_DUPLICATE_PROFILE,
        MANAGED_DUPLICATE_UUID,
        MANAGED_UUID_PROFILE_MISMATCH
    }

    public record CoopAuthority(@Nonnull String authorityId,
                                @Nonnull String worldName,
                                @Nonnull String coopId,
                                int x,
                                int y,
                                int z,
                                int maximumResidents) {
        public CoopAuthority {
            authorityId = requireText(authorityId, "authorityId");
            worldName = requireText(worldName, "worldName");
            coopId = requireText(coopId, "coopId");
            if (maximumResidents < 0) {
                throw new IllegalArgumentException("maximumResidents must not be negative");
            }
        }
    }

    public record ManagedResidentEvidence(@Nonnull String residentId,
                                          @Nonnull String authorityId,
                                          @Nonnull String coopId,
                                          int residentSlot,
                                          @Nonnull String profileId,
                                          @Nonnull UUID residentUuid,
                                          @Nullable UUID sourceNpcUuid,
                                          @Nullable UUID deployedNpcUuid) {
        public ManagedResidentEvidence {
            residentId = requireText(residentId, "residentId");
            authorityId = requireText(authorityId, "authorityId");
            coopId = requireText(coopId, "coopId");
            profileId = requireText(profileId, "profileId");
            Objects.requireNonNull(residentUuid, "residentUuid");
            if (residentSlot < 0) {
                throw new IllegalArgumentException("residentSlot must not be negative");
            }
        }

        @Nonnull
        Set<UUID> identityUuids() {
            LinkedHashSet<UUID> identities = new LinkedHashSet<>();
            identities.add(residentUuid);
            if (sourceNpcUuid != null) {
                identities.add(sourceNpcUuid);
            }
            if (deployedNpcUuid != null) {
                identities.add(deployedNpcUuid);
            }
            return Set.copyOf(identities);
        }
    }

    public record VanillaResidentEvidence(@Nonnull String sourceFingerprint,
                                          @Nonnull String sourcePayload,
                                          @Nullable Integer sourceSlot,
                                          int sourceOrder,
                                          @Nullable UUID persistentUuid,
                                          @Nullable String resolvedProfileId,
                                          @Nullable String roleId,
                                          @Nullable String displayName) {
        public VanillaResidentEvidence {
            if (sourceFingerprint == null || sourceFingerprint.isBlank()) {
                throw new IllegalArgumentException("sourceFingerprint must not be blank");
            }
            Objects.requireNonNull(sourcePayload, "sourcePayload");
            if (sourceSlot != null && sourceSlot < 0) {
                throw new IllegalArgumentException("sourceSlot must not be negative");
            }
            if (sourceOrder < 0) {
                throw new IllegalArgumentException("sourceOrder must not be negative");
            }
            resolvedProfileId = optionalText(resolvedProfileId);
            roleId = optionalText(roleId);
            displayName = optionalText(displayName);
        }
    }

    public record ImportRequest(@Nonnull CoopAuthority authority,
                                @Nonnull List<ManagedResidentEvidence> managedResidents,
                                @Nonnull List<VanillaResidentEvidence> vanillaResidents) {
        public ImportRequest {
            Objects.requireNonNull(authority, "authority");
            managedResidents = immutableNonNull(managedResidents, "managedResidents");
            vanillaResidents = immutableNonNull(vanillaResidents, "vanillaResidents");
        }
    }

    public record Decision(@Nonnull Classification classification,
                           @Nonnull VanillaResidentEvidence source,
                           @Nullable String matchedResidentId,
                           @Nullable String matchedProfileId,
                           @Nullable Integer targetSlot,
                           @Nonnull List<Reason> reasons) {
        public Decision {
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(source, "source");
            reasons = List.copyOf(reasons);
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("at least one decision reason is required");
            }
        }
    }

    public record ImportPlan(@Nonnull CoopAuthority authority,
                             @Nonnull List<Decision> decisions,
                             int committedResidentCount) {
        public ImportPlan {
            Objects.requireNonNull(authority, "authority");
            decisions = List.copyOf(decisions);
            if (committedResidentCount < 0) {
                throw new IllegalArgumentException("committedResidentCount must not be negative");
            }
        }

        public long count(@Nonnull Classification classification) {
            return decisions.stream().filter(decision -> decision.classification() == classification).count();
        }

        public boolean hasConflicts() {
            return count(Classification.CONFLICT) > 0L;
        }

        public boolean isOverCapacity() {
            return committedResidentCount > authority.maximumResidents()
                    || count(Classification.OVERFLOW) > 0L;
        }
    }

    /** Classifies every supplied vanilla source without mutating either representation. */
    @Nonnull
    public ImportPlan plan(@Nonnull ImportRequest request) {
        Objects.requireNonNull(request, "request");
        List<VanillaResidentEvidence> orderedSources = request.vanillaResidents().stream()
                .sorted(SOURCE_ORDER)
                .toList();
        ManagedIndex managed = buildManagedIndex(request.authority(), request.managedResidents());
        DuplicateIndex duplicates = buildDuplicateIndex(orderedSources);
        ArrayList<Decision> decisions = new ArrayList<>(orderedSources.size());
        ArrayList<VanillaResidentEvidence> importCandidates = new ArrayList<>();

        for (VanillaResidentEvidence source : orderedSources) {
            EnumSet<Reason> conflicts = duplicates.conflictsFor(source);
            conflicts.addAll(managed.globalConflicts());
            Match match = managed.match(source);
            conflicts.addAll(match.conflicts());
            if (!conflicts.isEmpty()) {
                decisions.add(conflict(source, conflicts));
            } else if (match.resident() != null) {
                decisions.add(matched(source, match));
            } else {
                importCandidates.add(source);
            }
        }

        allocateImportSlots(request.authority(), managed, importCandidates, decisions);
        decisions.sort(Comparator.comparing(Decision::source, SOURCE_ORDER));
        return new ImportPlan(request.authority(), decisions, request.managedResidents().size());
    }

    private ManagedIndex buildManagedIndex(CoopAuthority authority,
                                           List<ManagedResidentEvidence> residents) {
        Map<String, List<ManagedResidentEvidence>> byProfile = new HashMap<>();
        Map<UUID, List<ManagedResidentEvidence>> byUuid = new HashMap<>();
        Map<Integer, List<ManagedResidentEvidence>> bySlot = new HashMap<>();
        EnumSet<Reason> conflicts = EnumSet.noneOf(Reason.class);
        for (ManagedResidentEvidence resident : residents) {
            if (!authority.authorityId().equals(resident.authorityId())
                    || !authority.coopId().equals(resident.coopId())) {
                conflicts.add(Reason.MANAGED_AUTHORITY_MISMATCH);
            }
            byProfile.computeIfAbsent(resident.profileId(), ignored -> new ArrayList<>()).add(resident);
            bySlot.computeIfAbsent(resident.residentSlot(), ignored -> new ArrayList<>()).add(resident);
            for (UUID identity : resident.identityUuids()) {
                byUuid.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(resident);
            }
        }
        if (containsDuplicate(byProfile.values())) {
            conflicts.add(Reason.MANAGED_DUPLICATE_PROFILE);
        }
        if (containsDuplicate(bySlot.values())) {
            conflicts.add(Reason.MANAGED_DUPLICATE_SLOT);
        }
        if (containsDuplicate(byUuid.values())) {
            conflicts.add(Reason.MANAGED_DUPLICATE_UUID);
        }
        return new ManagedIndex(byProfile, byUuid, bySlot.keySet(), conflicts, residents.size());
    }

    private DuplicateIndex buildDuplicateIndex(List<VanillaResidentEvidence> sources) {
        Map<String, Integer> fingerprints = new HashMap<>();
        Map<Integer, Integer> orders = new HashMap<>();
        Map<Integer, Integer> slots = new HashMap<>();
        Map<UUID, Integer> uuids = new HashMap<>();
        Map<String, Integer> profiles = new HashMap<>();
        for (VanillaResidentEvidence source : sources) {
            increment(fingerprints, source.sourceFingerprint());
            increment(orders, source.sourceOrder());
            increment(slots, source.sourceSlot());
            increment(uuids, source.persistentUuid());
            increment(profiles, source.resolvedProfileId());
        }
        return new DuplicateIndex(fingerprints, orders, slots, uuids, profiles);
    }

    private void allocateImportSlots(CoopAuthority authority,
                                     ManagedIndex managed,
                                     List<VanillaResidentEvidence> candidates,
                                     List<Decision> decisions) {
        TreeSet<Integer> occupied = new TreeSet<>(managed.occupiedSlots());
        TreeSet<Integer> available = new TreeSet<>();
        for (int slot = 0; slot < authority.maximumResidents(); slot++) {
            if (!managed.occupiedSlots().contains(slot)) {
                available.add(slot);
            }
        }
        int remainingCapacity = Math.max(0, authority.maximumResidents() - managed.residentCount());
        for (VanillaResidentEvidence source : candidates) {
            Integer targetSlot = chooseSlot(source.sourceSlot(), available, remainingCapacity);
            if (targetSlot == null) {
                int overflowSlot = chooseOverflowSlot(
                        source.sourceSlot(), occupied, authority.maximumResidents());
                occupied.add(overflowSlot);
                decisions.add(new Decision(
                        Classification.OVERFLOW, source, null, source.resolvedProfileId(),
                        overflowSlot,
                        List.of(Reason.CAPACITY_EXCEEDED)));
                continue;
            }
            available.remove(targetSlot);
            occupied.add(targetSlot);
            remainingCapacity--;
            decisions.add(new Decision(
                    Classification.IMPORTABLE, source, null, source.resolvedProfileId(), targetSlot,
                    List.of(Reason.NEW_DISTINCT_SOURCE)));
        }
    }

    private int chooseOverflowSlot(@Nullable Integer preferred,
                                   Set<Integer> occupied,
                                   int maximumResidents) {
        if (preferred != null && !occupied.contains(preferred)) {
            return preferred;
        }
        int candidate = Math.max(0, maximumResidents);
        while (occupied.contains(candidate)) {
            if (candidate == Integer.MAX_VALUE) {
                throw new IllegalStateException("managed coop resident slot space exhausted");
            }
            candidate++;
        }
        return candidate;
    }

    @Nullable
    private Integer chooseSlot(@Nullable Integer preferred,
                               TreeSet<Integer> available,
                               int remainingCapacity) {
        if (remainingCapacity <= 0 || available.isEmpty()) {
            return null;
        }
        return preferred != null && available.contains(preferred) ? preferred : available.first();
    }

    private Decision matched(VanillaResidentEvidence source, Match match) {
        ManagedResidentEvidence resident = match.resident();
        Reason reason = match.uuidMatched() && match.profileMatched()
                ? Reason.EXACT_UUID_AND_PROFILE_MATCH
                : match.uuidMatched() ? Reason.EXACT_UUID_MATCH : Reason.EXACT_PROFILE_MATCH;
        return new Decision(
                Classification.MATCH_EXISTING, source, resident.residentId(), resident.profileId(),
                resident.residentSlot(), List.of(reason));
    }

    private Decision conflict(VanillaResidentEvidence source, EnumSet<Reason> conflicts) {
        return new Decision(
                Classification.CONFLICT, source, null, source.resolvedProfileId(), null,
                List.copyOf(conflicts));
    }

    private boolean containsDuplicate(Collection<? extends List<?>> values) {
        return values.stream().anyMatch(value -> value.size() > 1);
    }

    private <T> void increment(Map<T, Integer> counts, @Nullable T value) {
        if (value != null) {
            counts.merge(value, 1, Integer::sum);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Nullable
    private static String optionalText(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T> List<T> immutableNonNull(List<T> values, String field) {
        Objects.requireNonNull(values, field);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null values");
        }
        return List.copyOf(values);
    }

    private static String nullableOrderKey(@Nullable String value) {
        return value == null ? "" : value;
    }

    private record Match(@Nullable ManagedResidentEvidence resident,
                         @Nonnull EnumSet<Reason> conflicts,
                         boolean uuidMatched,
                         boolean profileMatched) {
    }

    private record ManagedIndex(Map<String, List<ManagedResidentEvidence>> byProfile,
                                Map<UUID, List<ManagedResidentEvidence>> byUuid,
                                Set<Integer> occupiedSlots,
                                EnumSet<Reason> globalConflicts,
                                int residentCount) {
        Match match(VanillaResidentEvidence source) {
            LinkedHashSet<ManagedResidentEvidence> uuidMatches = matches(byUuid, source.persistentUuid());
            LinkedHashSet<ManagedResidentEvidence> profileMatches = matches(
                    byProfile, source.resolvedProfileId());
            EnumSet<Reason> conflicts = EnumSet.noneOf(Reason.class);
            if (uuidMatches.size() > 1) {
                conflicts.add(Reason.MANAGED_DUPLICATE_UUID);
            }
            if (profileMatches.size() > 1) {
                conflicts.add(Reason.MANAGED_DUPLICATE_PROFILE);
            }
            LinkedHashSet<ManagedResidentEvidence> combined = new LinkedHashSet<>(uuidMatches);
            combined.addAll(profileMatches);
            if (combined.size() > 1) {
                conflicts.add(Reason.MANAGED_UUID_PROFILE_MISMATCH);
            }
            ManagedResidentEvidence resident = combined.size() == 1 ? combined.iterator().next() : null;
            if (resident != null && source.resolvedProfileId() != null
                    && !source.resolvedProfileId().equals(resident.profileId())) {
                conflicts.add(Reason.MANAGED_UUID_PROFILE_MISMATCH);
            }
            return new Match(
                    conflicts.isEmpty() ? resident : null,
                    conflicts,
                    !uuidMatches.isEmpty(),
                    !profileMatches.isEmpty()
            );
        }

        private static <K> LinkedHashSet<ManagedResidentEvidence> matches(
                Map<K, List<ManagedResidentEvidence>> index,
                @Nullable K key) {
            return key == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(index.getOrDefault(key, List.of()));
        }
    }

    private record DuplicateIndex(Map<String, Integer> fingerprints,
                                  Map<Integer, Integer> orders,
                                  Map<Integer, Integer> slots,
                                  Map<UUID, Integer> uuids,
                                  Map<String, Integer> profiles) {
        EnumSet<Reason> conflictsFor(VanillaResidentEvidence source) {
            EnumSet<Reason> conflicts = EnumSet.noneOf(Reason.class);
            addIfDuplicate(conflicts, fingerprints, source.sourceFingerprint(),
                    Reason.DUPLICATE_SOURCE_FINGERPRINT);
            addIfDuplicate(conflicts, orders, source.sourceOrder(), Reason.DUPLICATE_SOURCE_ORDER);
            addIfDuplicate(conflicts, slots, source.sourceSlot(), Reason.DUPLICATE_SOURCE_SLOT);
            addIfDuplicate(conflicts, uuids, source.persistentUuid(), Reason.DUPLICATE_PERSISTENT_UUID);
            addIfDuplicate(conflicts, profiles, source.resolvedProfileId(),
                    Reason.DUPLICATE_RESOLVED_PROFILE);
            return conflicts;
        }

        private static <K> void addIfDuplicate(EnumSet<Reason> conflicts,
                                               Map<K, Integer> counts,
                                               @Nullable K key,
                                               Reason reason) {
            if (key != null && counts.getOrDefault(key, 0) > 1) {
                conflicts.add(reason);
            }
        }
    }
}
