package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
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
 */
public final class CompanionAdmissionGate {
    /** Message key of a refused group limit. */
    public static final String GROUP_LIMIT_MESSAGE_KEY = "tamework.ui.population.groupLimit";

    private final CompanionIndex index;
    private final Supplier<CompanionAdmission.Rules> rules;

    /** {@code groups} returns the current population-group config; null is read as no groups. */
    public CompanionAdmissionGate(@Nonnull CompanionIndex index, @Nonnull Supplier<PopulationGroupConfigIndex> groups) {
        Objects.requireNonNull(groups, "groups");
        this.index = Objects.requireNonNull(index, "index");
        this.rules = () -> configuredRules(groups.get());
    }

    private CompanionAdmissionGate(Supplier<CompanionAdmission.Rules> rules, CompanionIndex index) {
        this.index = Objects.requireNonNull(index, "index");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    /** A gate with fixed rules instead of the configured ones; {@code rules} is read on each check. */
    @Nonnull
    static CompanionAdmissionGate withRules(@Nonnull CompanionIndex index,
                                            @Nonnull Supplier<CompanionAdmission.Rules> rules) {
        return new CompanionAdmissionGate(rules, index);
    }

    /**
     * Returns why {@code after} may not replace {@code before} (null for a new record), or null
     * when it is admitted. Call it under the index lock, in the step that applies the change.
     */
    @Nullable
    public CompanionAdmission.Refusal refuse(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        if (after.ownerUuid() == null) {
            return null;
        }
        return CompanionAdmission.check(index.fileRecords(after.ownerUuid()), before, after, rules.get(),
                CompanionAdmission.Provided.none());
    }

    /** Why a change is refused, with the translation key to show the player. */
    public record Denial(@Nonnull CompanionAdmission.Refusal refusal, @Nonnull String messageKey) {
        /** The built-in message of a refusal that carries no key of its own. */
        @Nonnull
        public static Denial of(@Nonnull CompanionAdmission.Refusal refusal) {
            return new Denial(refusal, switch (refusal) {
                case OWNED -> CompanionAdmission.OWNED_LIMIT_MESSAGE_KEY;
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
        CompanionAdmission.Refusal refusal = CompanionAdmission.check(ownerRecords, before, after, rules.get(), provided);
        if (refusal == null) {
            return null;
        }
        if (refusal == CompanionAdmission.Refusal.PROVIDER_DENIED) {
            CompanionAdmission.DomainRefusal domain = CompanionAdmission.checkDomains(ownerRecords, before, after, provided);
            if (domain != null) {
                return new Denial(refusal, domain.messageKey());
            }
        }
        return Denial.of(refusal);
    }

    /**
     * Lock-free pre-check for the tame, set-owner and spawn sites, so a capped tame is refused
     * before food is spent or effects play: would a new LIVE companion of {@code roleId} for
     * {@code owner} in {@code world} be refused? A null world checks a companion with no world
     * (the deployed group limit is then not checked). The binding check is {@link #refuse} under
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
     * toward the deployed group limit.
     */
    @Nullable
    public CompanionAdmission.Refusal precheck(@Nonnull UUID owner, @Nonnull String roleId, @Nullable String world,
                                               boolean deployed) {
        CompanionLocation at = deployed && world != null && !world.isBlank()
                ? CompanionLocation.live(world, 0, 0, 0) : CompanionLocation.item();
        CompanionRecord candidate = CompanionRecord.builder(UUID.randomUUID(), roleId, at)
                .ownerUuid(owner).homeWorld(world).build();
        return CompanionAdmission.check(index.fileRecords(owner), null, candidate, rules.get(),
                CompanionAdmission.Provided.none());
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
        boolean perWorld = TameworkRuntimeSettings.populationPerPlayerLimitScope(
                global.getPopulationPerPlayerLimitScope()) == TwGlobalConfig.PerPlayerLimitScope.PER_WORLD;
        PopulationGroupConfigIndex groupIndex = current == null ? PopulationGroupConfigIndex.empty() : current;
        return new CompanionAdmission.Rules(Math.max(0, ownedLimit), perWorld, groupIndex::resolvePoliciesForRole);
    }
}
