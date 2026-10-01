package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The external admission provider stage (plan 6 R10). For a change that needs admission on a role
 * with a managed profile, it asks the profile's provider and turns the decision into the claims
 * and domain limits the under-lock check uses, or into a refusal with a message key.
 *
 * <p>{@link #evaluate} never blocks and never touches the companion index, so call it before the
 * index lock. Its stage completes on the caller's thread when no provider is asked, otherwise on
 * the thread that completes the provider's decision (a provider callback or timeout thread);
 * continuations must not touch live entities there. A managed role whose provider is missing,
 * slow or failing is refused: the stage fails closed.</p>
 */
public final class ProviderAdmission {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Hytale chunks are 32 blocks wide. */
    private static final int CHUNK_SHIFT = 5;
    private static final Outcome NOT_ASKED = new Outcome(CompanionAdmission.Provided.none(), null, null, false);
    private static final ProviderAdmission NONE =
            new ProviderAdmission(roleId -> null, request -> CompletableFuture.completedFuture(
                    PopulationAdmissionProviderDecision.unavailable("provider-not-ready")));

    /** What a role's managed profile gives the provider request. */
    public record Managed(@Nonnull String providerId, int contractVersion, @Nonnull String managedProfileId,
                          @Nonnull String familyGroupId, @Nonnull Set<String> groupIds, @Nonnull String gateKey,
                          int weight, long configRevision) {
    }

    /**
     * The stage's answer. Admitted: {@code refusal} is null and {@code provided} holds the claims
     * and domain limits for the under-lock check. Refused: {@code refusal} is
     * {@code PROVIDER_DENIED} or {@code PROVIDER_UNAVAILABLE} and {@code messageKey} is the
     * translation key to show. {@code asked} is true when a provider gave the answer; only then
     * are the claims stored on the record.
     */
    public record Outcome(@Nonnull CompanionAdmission.Provided provided, @Nullable CompanionAdmission.Refusal refusal,
                          @Nullable String messageKey, boolean asked) {
        public boolean admitted() {
            return refusal == null;
        }
    }

    private final Function<String, Managed> managedForRole;
    private final Function<PopulationAdmissionProviderRequest, CompletionStage<PopulationAdmissionProviderDecision>> providers;

    /**
     * @param managedForRole the managed profile data of a role id, or null when the role is not managed
     * @param providers      evaluates one request without blocking; fails closed to UNAVAILABLE
     */
    public ProviderAdmission(@Nonnull Function<String, Managed> managedForRole,
                             @Nonnull Function<PopulationAdmissionProviderRequest,
                                     CompletionStage<PopulationAdmissionProviderDecision>> providers) {
        this.managedForRole = Objects.requireNonNull(managedForRole, "managedForRole");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    /** The production stage: managed profiles from config, decisions from the provider registry. */
    @Nonnull
    public static ProviderAdmission of(@Nonnull ManagedActivityConfigRegistry managed,
                                       @Nonnull AdmissionProviderRegistry registry) {
        Objects.requireNonNull(managed, "managed");
        Objects.requireNonNull(registry, "registry");
        return new ProviderAdmission(
                roleId -> managed.resolveRole(roleId).map(r -> new Managed(r.profile().providerId(),
                        r.profile().providerContractVersion(), r.profile().profileId(), r.family().groupId(),
                        r.profile().families().keySet(), r.family().gateKey(), r.family().weight(),
                        r.profile().configRevision())).orElse(null),
                registry::evaluate);
    }

    /** A stage with no managed roles: every change passes without claims. */
    @Nonnull
    public static ProviderAdmission none() {
        return NONE;
    }

    /** The managed profile id of a role, or null when the role is not managed. */
    @Nullable
    public String managedProfileId(@Nonnull String roleId) {
        Managed managed = managedForRole.apply(roleId);
        return managed == null ? null : managed.managedProfileId();
    }

    /**
     * True when the change gives {@code after} an owner it did not count for, or deploys it (LIVE)
     * when it was not deployed. Only such changes are put to a provider.
     */
    public static boolean needsAdmission(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        UUID owner = after.ownerUuid();
        if (owner == null || !after.countsAsOwned()) {
            return false;
        }
        boolean counted = before != null && owner.equals(before.ownerUuid()) && before.countsAsOwned();
        return !counted || (after.isDeployed() && !before.isDeployed());
    }

    /**
     * Asks the provider about the change from {@code before} (null for a new record) to
     * {@code after}. Completes at once, not asked, when the change needs no admission or the role
     * is not managed. Never completes exceptionally.
     */
    @Nonnull
    public CompletionStage<Outcome> evaluate(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        if (!needsAdmission(before, after)) {
            return CompletableFuture.completedFuture(NOT_ASKED);
        }
        Managed managed = managedForRole.apply(after.roleId());
        if (managed == null) {
            return CompletableFuture.completedFuture(NOT_ASKED);
        }
        CompletionStage<PopulationAdmissionProviderDecision> decided;
        try {
            decided = providers.apply(request(managed, before, after));
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log(
                    "Admission provider %s could not be asked about companion %s", managed.providerId(),
                    after.profileId());
            return CompletableFuture.completedFuture(unavailable());
        }
        return decided.handle((decision, error) -> error != null || decision == null
                ? unavailable() : outcome(managed, decision));
    }

    private static Outcome outcome(Managed managed, PopulationAdmissionProviderDecision decision) {
        return switch (decision.status()) {
            case DENY -> new Outcome(CompanionAdmission.Provided.none(), CompanionAdmission.Refusal.PROVIDER_DENIED,
                    decision.messageKey(), true);
            case UNAVAILABLE -> unavailable();
            case ALLOW -> {
                if (decision.configRevision() != managed.configRevision()) {
                    // The provider decided against other content than the server has loaded.
                    yield unavailable();
                }
                List<DomainClaim> claims = new ArrayList<>();
                Set<String> buckets = new HashSet<>();
                for (PopulationDomainClaim claim : decision.claims()) {
                    // Incomplete or ambiguous evidence is not an answer: a claim needs its limit,
                    // and a domain bucket may be claimed once.
                    if (!decision.domainLimits().containsKey(claim.domainId())
                            || (claim.owned() && !buckets.add("owned " + claim.domainId()))
                            || (claim.deployable() && !buckets.add("deployed " + claim.domainId()))) {
                        LOGGER.at(Level.WARNING).log(
                                "Admission provider %s allowed with an unusable claim on domain %s; refused as unavailable",
                                managed.providerId(), claim.domainId());
                        yield unavailable();
                    }
                    claims.add(new DomainClaim(claim.domainId(), claim.weight(), claim.owned(), claim.deployable()));
                }
                // The decision's set has no order; the record stores a stable one.
                claims.sort(Comparator.comparing(DomainClaim::domainId).thenComparing(DomainClaim::owned)
                        .thenComparing(DomainClaim::deployable).thenComparingInt(DomainClaim::weight));
                yield new Outcome(new CompanionAdmission.Provided(claims, decision.domainLimits()), null, null, true);
            }
        };
    }

    private static Outcome unavailable() {
        return new Outcome(CompanionAdmission.Provided.none(), CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE,
                CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY, true);
    }

    /** Contract v1 request: the old and new owner, where the companion is and where it will be. */
    private static PopulationAdmissionProviderRequest request(Managed managed, @Nullable CompanionRecord before,
                                                              CompanionRecord after) {
        UUID newOwner = after.ownerUuid();
        UUID oldOwner = before == null ? null : before.ownerUuid();
        String fallbackWorld = world(after, before == null ? "" : world(before, ""));
        PopulationAdmissionLocation destination = location(after, fallbackWorld);
        PopulationAdmissionRequest base;
        if (before == null) {
            base = new PopulationAdmissionRequest(
                    new PopulationAdmissionIdentity(null, after.profileId().toString(), null), after.currentNpcUuid(),
                    PopulationAdmissionRequest.NEW_PROFILE_REVISION, null, newOwner, null, destination,
                    PopulationAdmissionOperation.NEW_OWNERSHIP, 1, PopulationAdmissionForcePolicy.ENFORCE,
                    lifecycle(after));
        } else {
            PopulationAdmissionOperation operation = oldOwner == null ? PopulationAdmissionOperation.NEW_OWNERSHIP
                    : oldOwner.equals(newOwner) ? PopulationAdmissionOperation.RESTORE
                    : PopulationAdmissionOperation.OWNER_TRANSFER;
            UUID npcUuid = before.currentNpcUuid() != null ? before.currentNpcUuid() : after.currentNpcUuid();
            base = new PopulationAdmissionRequest(
                    new PopulationAdmissionIdentity(after.profileId().toString(), null, null), npcUuid,
                    before.revision(), oldOwner, newOwner, location(before, fallbackWorld), destination, operation, 1,
                    PopulationAdmissionForcePolicy.ENFORCE, lifecycle(after));
        }
        return new PopulationAdmissionProviderRequest(managed.providerId(), managed.contractVersion(),
                new PopulationAdmissionRequestV3(
                        new PopulationAdmissionRequestV2(base, after.roleId(), destination.worldName()),
                        managed.managedProfileId()),
                managed.familyGroupId(), managed.groupIds(), managed.gateKey(), managed.weight(),
                managed.configRevision());
    }

    private static String world(CompanionRecord record, String fallback) {
        String world = CompanionAdmission.scopeWorld(record);
        return world.isBlank() ? fallback : world;
    }

    /** The record's chunk when it is in a world (LIVE, COOP), else chunk 0,0 of the world it counts in. */
    private static PopulationAdmissionLocation location(CompanionRecord record, String fallbackWorld) {
        CompanionLocation at = record.location();
        if (at.world() != null && !at.world().isBlank()) {
            return new PopulationAdmissionLocation(at.world(), (int) Math.floor(at.x()) >> CHUNK_SHIFT,
                    (int) Math.floor(at.z()) >> CHUNK_SHIFT);
        }
        return new PopulationAdmissionLocation(world(record, fallbackWorld), 0, 0);
    }

    private static PopulationCompanionLifecycle lifecycle(CompanionRecord after) {
        return switch (after.location().kind()) {
            case LIVE -> PopulationCompanionLifecycle.ACTIVE;
            case ITEM -> PopulationCompanionLifecycle.CAPTURED;
            case COOP -> PopulationCompanionLifecycle.COOP;
            case STORED -> PopulationCompanionLifecycle.ROSTER_STORED;
            case DEAD -> PopulationCompanionLifecycle.DEAD_REVIVABLE;
            case LOST -> PopulationCompanionLifecycle.LOST;
            case RELEASED -> PopulationCompanionLifecycle.RELEASED;
        };
    }
}
