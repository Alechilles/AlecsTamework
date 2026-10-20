package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The single population-cap authority (spec 8.10): checks a record change against the owner's
 * records in the companion index and the current config. Callers hold the index lock, so the
 * count and the change they guard are one step. The rules are resolved on each call from
 * in-memory config, so a reload applies to the next change.
 *
 * <p>The asynchronous flows ask the admission provider themselves and call {@link #deny} with its
 * answer. The synchronous sites cannot wait for a provider: {@link #admit}, {@link #refuse},
 * {@link #precheckDenial} and {@link #denyBatch} use the decision in the
 * {@link ProviderDecisionCache} (plan 6 R11) and refuse while it is being fetched.</p>
 */
public final class CompanionAdmissionGate {
    /** Message key of a refused group limit. */
    public static final String GROUP_LIMIT_MESSAGE_KEY = "tamework.ui.population.groupLimit";

    private final CompanionIndex index;
    private final Supplier<CompanionAdmission.Rules> rules;
    @Nullable private final ProviderDecisionCache providerDecisions;

    /**
     * {@code groups} returns the current population-group config; null is read as no groups.
     * {@code providerDecisions} gives the synchronous sites the cached provider decision; null
     * means no role is managed.
     */
    public CompanionAdmissionGate(@Nonnull CompanionIndex index, @Nonnull Supplier<PopulationGroupConfigIndex> groups,
                                  @Nullable ProviderDecisionCache providerDecisions) {
        Objects.requireNonNull(groups, "groups");
        this.index = Objects.requireNonNull(index, "index");
        this.rules = () -> configuredRules(groups.get());
        this.providerDecisions = providerDecisions;
    }

    private CompanionAdmissionGate(Supplier<CompanionAdmission.Rules> rules, CompanionIndex index,
                                   @Nullable ProviderDecisionCache providerDecisions) {
        this.index = Objects.requireNonNull(index, "index");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.providerDecisions = providerDecisions;
    }

    /** A gate with fixed rules instead of the configured ones; {@code rules} is read on each check. */
    @Nonnull
    static CompanionAdmissionGate withRules(@Nonnull CompanionIndex index,
                                            @Nonnull Supplier<CompanionAdmission.Rules> rules) {
        return new CompanionAdmissionGate(rules, index, null);
    }

    /** As {@link #withRules(CompanionIndex, Supplier)}, with cached provider decisions. */
    @Nonnull
    static CompanionAdmissionGate withRules(@Nonnull CompanionIndex index,
                                            @Nonnull Supplier<CompanionAdmission.Rules> rules,
                                            @Nonnull ProviderDecisionCache providerDecisions) {
        return new CompanionAdmissionGate(rules, index, providerDecisions);
    }

    /**
     * The answer of a synchronous check. Admitted: {@code denial} is null and {@code record} is
     * the record to store, which carries the provider's claims when a provider allowed it.
     * Refused: {@code record} is the unchanged candidate.
     */
    public record Admission(@Nullable Denial denial, @Nonnull CompanionRecord record) {
        public boolean admitted() {
            return denial == null;
        }

        /** The refusal alone, or null when admitted. */
        @Nullable
        public CompanionAdmission.Refusal refusal() {
            return denial == null ? null : denial.refusal();
        }
    }

    /**
     * The synchronous check: may {@code after} replace {@code before} (null for a new record)?
     * The built-in caps come first. A managed role that needs admission then uses the cached
     * provider decision: a denial or an unavailable provider refuses with its message key, no
     * decision yet refuses with the checking message and starts the evaluation, and an allow is
     * checked against its domain limits. The caller stores {@link Admission#record}, so the claims
     * are written in the step that was checked. Call it under the index lock, in that step.
     */
    @Nonnull
    public Admission admit(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        if (after.ownerUuid() == null) {
            return new Admission(null, after);
        }
        return admit(index.fileRecords(after.ownerUuid()), before, after);
    }

    private Admission admit(Collection<CompanionRecord> ownerRecords, @Nullable CompanionRecord before,
                            CompanionRecord after) {
        CompanionAdmission.Refusal caps = CompanionAdmission.check(ownerRecords, before, after, rules.get(),
                CompanionAdmission.Provided.none());
        if (caps != null) {
            return new Admission(Denial.of(caps), after);
        }
        if (providerDecisions == null) {
            return new Admission(null, after);
        }
        ProviderAdmission.Outcome decision = providerDecisions.decide(before, after);
        if (!decision.admitted()) {
            Denial builtIn = Denial.of(decision.refusal());
            return new Admission(decision.messageKey() == null ? builtIn
                    : new Denial(decision.refusal(), decision.messageKey()), after);
        }
        if (!decision.asked()) {
            return new Admission(null, after);
        }
        CompanionAdmission.DomainRefusal domain =
                CompanionAdmission.checkDomains(ownerRecords, before, after, decision.provided());
        if (domain != null) {
            return new Admission(new Denial(CompanionAdmission.Refusal.PROVIDER_DENIED, domain.messageKey()), after);
        }
        return new Admission(null, after.toBuilder().domainClaims(decision.provided().claims()).build());
    }

    /**
     * {@link #admit} reduced to why the change is refused, or null when it is admitted. A caller
     * that stores the record itself loses the provider's claims; use {@link #admit} for a change
     * that is then applied.
     */
    @Nullable
    public CompanionAdmission.Refusal refuse(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        return admit(before, after).refusal();
    }

    /**
     * Spec 8.11, lock-free: would {@code candidates}, all new records of {@code owner}, be
     * admitted as a whole? Each is checked with the ones before it counted, claims included.
     * Returns the first denial, or null.
     */
    @Nullable
    public Denial denyBatch(@Nonnull UUID owner, @Nonnull List<CompanionRecord> candidates) {
        List<CompanionRecord> working = new ArrayList<>(index.fileRecords(owner));
        for (CompanionRecord candidate : candidates) {
            Admission admission = admit(working, null, candidate);
            if (!admission.admitted()) {
                return admission.denial();
            }
            working.add(admission.record());
        }
        return null;
    }

    /** Why a change is refused, with the translation key to show the player. */
    public record Denial(@Nonnull CompanionAdmission.Refusal refusal, @Nonnull String messageKey) {
        /** The built-in message of a refusal that carries no key of its own. */
        @Nonnull
        public static Denial of(@Nonnull CompanionAdmission.Refusal refusal) {
            return new Denial(refusal, switch (refusal) {
                case OWNED -> CompanionAdmission.OWNED_LIMIT_MESSAGE_KEY;
                case DEPLOYED -> CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY;
                case GROUP_OWNED, GROUP_DEPLOYED -> GROUP_LIMIT_MESSAGE_KEY;
                case PROVIDER_DENIED -> CompanionAdmission.PROVIDER_DENIED_MESSAGE_KEY;
                case PROVIDER_UNAVAILABLE -> CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY;
            });
        }
    }

    /** The under-lock check the asynchronous flows call; {@link #deny} in production. */
    @FunctionalInterface
    public interface Check {
        @Nullable
        Denial deny(@Nullable CompanionRecord before, @Nonnull CompanionRecord after,
                    @Nonnull CompanionAdmission.Provided provided);
    }

    /**
     * As {@link #refuse}, with what an admission provider allowed for {@code after}: the built-in
     * caps and each claimed domain's limit are checked in one step. A domain limit names its own
     * message key. Call it under the index lock, in the step that applies the change.
     */
    @Nullable
    public Denial deny(@Nullable CompanionRecord before, @Nonnull CompanionRecord after,
                       @Nonnull CompanionAdmission.Provided provided) {
        if (after.ownerUuid() == null) {
            return null;
        }
        var ownerRecords = index.fileRecords(after.ownerUuid());
        CompanionAdmission.Refusal caps = CompanionAdmission.check(ownerRecords, before, after, rules.get(),
                CompanionAdmission.Provided.none());
        if (caps != null) {
            return Denial.of(caps);
        }
        CompanionAdmission.DomainRefusal domain = CompanionAdmission.checkDomains(ownerRecords, before, after, provided);
        return domain == null ? null : new Denial(CompanionAdmission.Refusal.PROVIDER_DENIED, domain.messageKey());
    }

    /**
     * Lock-free pre-check for the tame, set-owner and spawn sites, so a capped tame is refused
     * before food is spent or effects play: would a new LIVE companion of {@code roleId} for
     * {@code owner} in {@code world} be refused? A null world checks a companion with no world
     * (the deployed limits are then not checked). The binding check is {@link #admit} under
     * the index lock; this one may miss a change made after it returns.
     */
    @Nullable
    public CompanionAdmission.Refusal precheck(@Nonnull UUID owner, @Nonnull String roleId, @Nullable String world) {
        return precheck(owner, roleId, world, world != null && !world.isBlank());
    }

    /**
     * As {@link #precheck(UUID, String, String)}, for a candidate that is deployed (LIVE in
     * {@code world}) or not. A non-deployed candidate is an ITEM whose home world is {@code world},
     * as a capture into an item creates: it counts toward the owned limits in that world but not
     * toward the deployed limits.
     */
    @Nullable
    public CompanionAdmission.Refusal precheck(@Nonnull UUID owner, @Nonnull String roleId, @Nullable String world,
                                               boolean deployed) {
        Denial denial = precheckDenial(owner, roleId, world, deployed);
        return denial == null ? null : denial.refusal();
    }

    /** As {@link #precheck(UUID, String, String, boolean)}, with the message key of the refusal. */
    @Nullable
    public Denial precheckDenial(@Nonnull UUID owner, @Nonnull String roleId, @Nullable String world,
                                 boolean deployed) {
        CompanionLocation at = deployed && world != null && !world.isBlank()
                ? CompanionLocation.live(world, 0, 0, 0) : CompanionLocation.item();
        CompanionRecord candidate = CompanionRecord.builder(UUID.randomUUID(), roleId, at)
                .ownerUuid(owner).homeWorld(world).build();
        return admit(index.fileRecords(owner), null, candidate).denial();
    }

    /** The rules the gate checks with now, for callers that check a whole batch (a litter). */
    @Nonnull
    public CompanionAdmission.Rules rules() {
        return rules.get();
    }

    @Nonnull
    private static CompanionAdmission.Rules configuredRules(@Nullable PopulationGroupConfigIndex current) {
        TwGlobalConfig active = TwGlobalConfig.resolveActive();
        TwGlobalConfig global = active == null ? TwGlobalConfig.defaultConfig() : active;
        int ownedLimit = TameworkRuntimeSettings.populationLimitPerPlayerOwnedTotal(
                global.getPopulationLimitPerPlayerOwnedTotal());
        int deployedLimit = TameworkRuntimeSettings.populationLimitPerPlayerDeployedTotal(
                global.getPopulationLimitPerPlayerDeployedTotal());
        boolean perWorld = TameworkRuntimeSettings.populationPerPlayerLimitScope(
                global.getPopulationPerPlayerLimitScope()) == TwGlobalConfig.PerPlayerLimitScope.PER_WORLD;
        PopulationGroupConfigIndex groupIndex = current == null ? PopulationGroupConfigIndex.empty() : current;
        return new CompanionAdmission.Rules(Math.max(0, ownedLimit), Math.max(0, deployedLimit), perWorld,
                groupIndex::resolvePoliciesForRole);
    }
}
