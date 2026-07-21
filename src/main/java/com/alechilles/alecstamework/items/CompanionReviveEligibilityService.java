package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselStateChangedEvent;
import com.alechilles.alecstamework.api.ProvisionedCompanionDeathRecordedEvent;
import com.alechilles.alecstamework.api.ProvisionedCompanionRevivedEvent;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransition;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransitionRequest;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationObservation;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationObservationPersistResult;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.provisioning.ProvisioningPopulationBackend;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Memory-only death/revive qualification derived from durable vessel and provisioning authority.
 *
 * <p>The snapshot is loaded before death systems become authoritative and is refreshed by the
 * lifecycle coordinators after durable commits. World-thread callers never read SQLite.</p>
 */
public final class CompanionReviveEligibilityService {
    private static final AtomicReference<CompanionReviveEligibilityService> CURRENT =
            new AtomicReference<>(unavailable());

    private final ConcurrentHashMap<UUID, Eligibility> byNpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Eligibility> byProfile = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> emittedOperations = new ConcurrentHashMap<>();
    private volatile boolean ready;
    private volatile String reason;
    private volatile BondedVesselRepository vesselRepository;
    private volatile CompanionProvisioningRepository provisioningRepository;
    private volatile NpcProfileRepository profileRepository;
    private volatile EventSink eventSink = EventSink.NO_OP;
    private volatile BondedLifecycleSink bondedLifecycleSink = BondedLifecycleSink.NO_OP;

    public CompanionReviveEligibilityService() {
        this(false, "revive-eligibility-not-loaded");
    }

    private CompanionReviveEligibilityService(boolean ready, String reason) {
        this.ready = ready;
        this.reason = requireText(reason, "reason");
    }

    @Nonnull
    public static CompanionReviveEligibilityService current() {
        return CURRENT.get();
    }

    public static void install(@Nonnull CompanionReviveEligibilityService service) {
        CURRENT.set(Objects.requireNonNull(service, "service"));
    }

    /** Loads one authoritative snapshot off the world thread. */
    @Nonnull
    public BootstrapReport bootstrap(
            @Nonnull BondedVesselRepository vessels,
            @Nonnull CompanionProvisioningRepository provisioning,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull OwnerPopulationIndex population
    ) {
        Objects.requireNonNull(vessels, "vessels");
        Objects.requireNonNull(provisioning, "provisioning");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(population, "population");
        try {
            Collection<BondedVesselBindingRecord> vesselRows = vessels.loadNonReleasedBindings();
            Collection<CompanionProvisioningOperationRecord> provisionedRows =
                    provisioning.loadAuthoritativeProfiles();
            ConcurrentHashMap<UUID, Eligibility> nextNpc = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Eligibility> nextProfile = new ConcurrentHashMap<>();
            for (BondedVesselBindingRecord binding : vesselRows) {
                if (binding == null
                        || binding.lifecycleState() == BondedVesselBindingRecord.LifecycleState.RELEASED) {
                    continue;
                }
                OwnerPopulationEntry owner = population.entry(binding.profileId()).orElse(null);
                if (owner == null || owner.lifecycleState() == CompanionLifecycleState.RELEASED) {
                    continue;
                }
                Eligibility eligibility = new Eligibility(
                        binding.profileId(), Authority.BONDED_VESSEL, binding.activeNpcUuid());
                put(nextNpc, nextProfile, eligibility);
            }
            for (CompanionProvisioningOperationRecord operation : provisionedRows) {
                String profileId = operation == null ? null : operation.canonicalProfileId();
                if (profileId == null) continue;
                OwnerPopulationEntry owner = population.entry(profileId).orElse(null);
                NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(profileId);
                if (owner == null || owner.lifecycleState() == CompanionLifecycleState.RELEASED
                        || profile == null || profile.ownerUuid() == null) {
                    continue;
                }
                put(nextNpc, nextProfile, new Eligibility(
                        profileId, Authority.PROVISIONED, profile.currentNpcUuid()));
            }
            byNpc.clear();
            byProfile.clear();
            byNpc.putAll(nextNpc);
            byProfile.putAll(nextProfile);
            vesselRepository = vessels;
            provisioningRepository = provisioning;
            profileRepository = profiles;
            ready = true;
            reason = "revive-eligibility-ready";
            return new BootstrapReport(true, byProfile.size(), byNpc.size(), reason);
        } catch (Exception | LinkageError failure) {
            ready = false;
            reason = "revive-eligibility-load-failed";
            return new BootstrapReport(false, byProfile.size(), byNpc.size(), reason);
        }
    }

    public boolean supports(@Nullable UUID npcUuid) {
        return npcUuid != null && byNpc.containsKey(npcUuid);
    }

    /** Degraded authority must preserve an owned death rather than release it destructively. */
    public boolean protectsFromPermanentDeath(@Nullable UUID npcUuid) {
        return npcUuid != null && (!ready || supports(npcUuid));
    }

    @Nullable
    public Eligibility findByNpc(@Nullable UUID npcUuid) {
        return npcUuid == null ? null : byNpc.get(npcUuid);
    }

    @Nullable
    public Eligibility findByProfile(@Nullable String profileId) {
        return profileId == null ? null : byProfile.get(profileId);
    }

    /** Publishes a committed non-released authority row. */
    public void record(@Nonnull String profileId,
                       @Nonnull Authority authority,
                       @Nullable UUID currentNpcUuid) {
        Eligibility next = new Eligibility(profileId, authority, currentNpcUuid);
        Eligibility previous = byProfile.put(next.profileId(), next);
        if (previous != null && previous.currentNpcUuid() != null
                && !previous.currentNpcUuid().equals(currentNpcUuid)) {
            byNpc.remove(previous.currentNpcUuid(), previous);
        }
        if (currentNpcUuid != null) byNpc.put(currentNpcUuid, next);
    }

    /** Refreshes the live UUID after a committed lifecycle transition. */
    public void remap(@Nonnull String profileId, @Nullable UUID currentNpcUuid) {
        Eligibility current = byProfile.get(requireText(profileId, "profileId"));
        if (current != null) record(profileId, current.authority(), currentNpcUuid);
    }

    public void release(@Nonnull String profileId) {
        Eligibility removed = byProfile.remove(requireText(profileId, "profileId"));
        if (removed != null && removed.currentNpcUuid() != null) {
            byNpc.remove(removed.currentNpcUuid(), removed);
        }
    }

    public void setEventSink(@Nullable EventSink eventSink) {
        this.eventSink = eventSink == null ? EventSink.NO_OP : eventSink;
    }

    /** Installs the command-link-independent durable vessel lifecycle writer. */
    public void setBondedLifecycleSink(@Nullable BondedLifecycleSink sink) {
        bondedLifecycleSink = sink == null ? BondedLifecycleSink.NO_OP : sink;
    }

    /** Called by the population writer only after its SQLite transaction committed. */
    public void onPopulationCommitted(
            @Nonnull CompanionPopulationObservation observation,
            @Nonnull CompanionPopulationObservationPersistResult result
    ) {
        if (!result.persisted()) return;
        Eligibility eligibility = findByProfile(observation.profileId());
        if (eligibility == null) return;
        if (observation.lifecycleState() == CompanionLifecycleState.DEAD_REVIVABLE) {
            if (eligibility.authority() == Authority.PROVISIONED) {
                emitProvisionedDeath(observation, result);
            } else {
                observeBondedLifecycle(observation, result, BondedVesselState.DEAD);
            }
            remap(observation.profileId(), null);
        } else if (observation.lifecycleState() == CompanionLifecycleState.LOST
                && eligibility.authority() == Authority.BONDED_VESSEL) {
            observeBondedLifecycle(observation, result, BondedVesselState.LOST);
            remap(observation.profileId(), null);
        }
    }

    private void observeBondedLifecycle(
            CompanionPopulationObservation observation,
            CompanionPopulationObservationPersistResult result,
            BondedVesselState target) {
        try {
            bondedLifecycleSink.observe(observation, result, target);
        } catch (RuntimeException | LinkageError ignored) {
            // Population authority is already committed; reconciliation may retry this observation.
        }
    }

    public void onProvisionedTransitionCommitted(
            @Nonnull UUID operationId,
            @Nonnull ProvisionedCompanionTransitionRequest request,
            @Nonnull ProvisioningPopulationBackend.ProfileSnapshot profile,
            boolean recovered
    ) {
        if (request.transition() != ProvisionedCompanionTransition.REVIVE_ACTIVE
                && request.transition() != ProvisionedCompanionTransition.REVIVE_DORMANT) {
            record(profile.profileId(), Authority.PROVISIONED, profile.currentNpcUuid());
            return;
        }
        CompanionProvisioningOperationRecord authority = findProvisioning(profile.profileId());
        if (authority == null || profile.profileRevision() <= request.expectedProfileRevision()) return;
        record(profile.profileId(), Authority.PROVISIONED, profile.currentNpcUuid());
        emitOnce(operationId, new ProvisionedCompanionRevivedEvent(
                operationId, authority.callerNamespace(), authority.idempotencyKey(),
                profile.profileId(), profile.ownerUuid(), profile.roleId(), profile.currentNpcUuid(),
                profile.lifecycle(), request.expectedProfileRevision(), profile.profileRevision(),
                recovered, profile.updatedAtMs(), System.currentTimeMillis()));
    }

    private void emitProvisionedDeath(
            CompanionPopulationObservation observation,
            CompanionPopulationObservationPersistResult result
    ) {
        CompanionProvisioningOperationRecord authority = findProvisioning(observation.profileId());
        NpcProfileRepository profileStore = profileRepository;
        NpcProfileRepository.ProfileRecord profile = profileStore == null
                ? null : profileStore.loadProfileById(observation.profileId());
        if (authority == null || profile == null || profile.roleId() == null
                || observation.ownerUuid() == null || result.revision() <= observation.expectedRevision()) {
            return;
        }
        UUID operationId = logicalOperationId(
                "provisioned-death", observation.profileId(), result.revision());
        long now = System.currentTimeMillis();
        emitOnce(operationId, new ProvisionedCompanionDeathRecordedEvent(
                operationId, authority.callerNamespace(), authority.idempotencyKey(),
                observation.profileId(), observation.ownerUuid(), profile.roleId(),
                observation.currentNpcUuid(), observation.expectedRevision(), result.revision(),
                false, now, now));
    }

    private void emitBondedLifecycle(
            CompanionPopulationObservation observation,
            CompanionPopulationObservationPersistResult result,
            BondedVesselState target
    ) {
        BondedVesselRepository repository = vesselRepository;
        if (repository == null || result.revision() <= observation.expectedRevision()) return;
        try {
            BondedVesselBindingRecord binding = repository.findBindingByProfile(
                    observation.profileId());
            if (binding == null || binding.generation() <= 1L
                    || BondedVesselState.valueOf(binding.lifecycleState().name()) != target) return;
            UUID operationId = logicalOperationId(
                    "bonded-" + target.name().toLowerCase(java.util.Locale.ROOT),
                    observation.profileId(), result.revision());
            long now = System.currentTimeMillis();
            emitOnce(operationId, new BondedVesselStateChangedEvent(
                    operationId, UUID.fromString(binding.bindingId()), binding.profileId(),
                    binding.ownerUuid(), binding.configId(), binding.generation() - 1L,
                    binding.generation(), BondedVesselState.ACTIVE, target,
                    result.revision(), binding.cooldownUntilMs(),
                    target == BondedVesselState.DEAD
                            ? "bonded-companion-death-recorded"
                            : "bonded-companion-lost-recorded",
                    false, binding.updatedAtMs(), now));
        } catch (Exception | LinkageError ignored) {
            // Notification lookup cannot change the already-committed lifecycle transaction.
        }
    }

    @Nullable
    private CompanionProvisioningOperationRecord findProvisioning(String profileId) {
        CompanionProvisioningRepository repository = provisioningRepository;
        if (repository == null) return null;
        try {
            return repository.findByCanonicalProfile(profileId);
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    private void emitOnce(UUID operationId, TameworkEvent event) {
        if (emittedOperations.putIfAbsent(operationId, Boolean.TRUE) != null) return;
        try {
            eventSink.emit(event);
        } catch (RuntimeException | LinkageError ignored) {
            // Listener failures are isolated; the operation ID remains logically delivered.
        }
    }

    private static UUID logicalOperationId(String kind, String profileId, long revision) {
        return UUID.nameUUIDFromBytes(("tamework:" + kind + ":" + profileId + ":" + revision)
                .getBytes(StandardCharsets.UTF_8));
    }

    public boolean ready() {
        return ready;
    }

    @Nonnull
    public String reason() {
        return reason;
    }

    /** Test/bootstrap seam that atomically replaces already-validated authority rows. */
    void replace(@Nonnull Collection<Eligibility> entries) {
        ConcurrentHashMap<UUID, Eligibility> nextNpc = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Eligibility> nextProfile = new ConcurrentHashMap<>();
        for (Eligibility entry : entries) put(nextNpc, nextProfile, entry);
        byNpc.clear();
        byProfile.clear();
        byNpc.putAll(nextNpc);
        byProfile.putAll(nextProfile);
        ready = true;
        reason = "revive-eligibility-ready";
    }

    private static void put(Map<UUID, Eligibility> npc,
                            Map<String, Eligibility> profile,
                            Eligibility eligibility) {
        Eligibility previous = profile.put(eligibility.profileId(), eligibility);
        if (previous != null && previous.authority() != eligibility.authority()) {
            // A profile cannot be provisioned and vessel-bound simultaneously. Preserve neither
            // classification rather than guessing which durable authority should win.
            profile.remove(eligibility.profileId());
            if (previous.currentNpcUuid() != null) npc.remove(previous.currentNpcUuid());
            return;
        }
        if (eligibility.currentNpcUuid() != null) {
            npc.put(eligibility.currentNpcUuid(), eligibility);
        }
    }

    private static CompanionReviveEligibilityService unavailable() {
        return new CompanionReviveEligibilityService(false, "revive-eligibility-not-installed");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    public enum Authority { BONDED_VESSEL, PROVISIONED }

    public record Eligibility(@Nonnull String profileId,
                              @Nonnull Authority authority,
                              @Nullable UUID currentNpcUuid) {
        public Eligibility {
            profileId = requireText(profileId, "profileId");
            authority = Objects.requireNonNull(authority, "authority");
        }
    }

    public record BootstrapReport(boolean ready, int profiles, int activeNpcIds,
                                  @Nonnull String reason) {
        public BootstrapReport {
            if (profiles < 0 || activeNpcIds < 0) {
                throw new IllegalArgumentException("Bootstrap counts cannot be negative");
            }
            reason = requireText(reason, "reason");
        }
    }

    @FunctionalInterface
    public interface EventSink {
        EventSink NO_OP = event -> { };
        void emit(@Nonnull TameworkEvent event);
    }

    @FunctionalInterface
    public interface BondedLifecycleSink {
        BondedLifecycleSink NO_OP = (observation, result, target) -> { };
        void observe(@Nonnull CompanionPopulationObservation observation,
                     @Nonnull CompanionPopulationObservationPersistResult result,
                     @Nonnull BondedVesselState target);
    }
}
