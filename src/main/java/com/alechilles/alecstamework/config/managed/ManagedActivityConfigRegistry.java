package com.alechilles.alecstamework.config.managed;

import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigDefinition;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compiles managed-activity assets into an immutable role resolver and swaps
 * the complete candidate atomically.
 *
 * <p>A rejected candidate never replaces the last valid snapshot. The
 * population-group registry is the authority for family role membership.</p>
 */
public final class ManagedActivityConfigRegistry {
    private static final Comparator<TwManagedActivityConfig> CONFIG_ORDER =
            Comparator.comparingInt(
                            TwManagedActivityConfig::getPriority
                    )
                    .reversed()
                    .thenComparing(
                            value -> safeId(value.getId()),
                            String.CASE_INSENSITIVE_ORDER
                    )
                    .thenComparing(value -> safeId(value.getId()));

    private final PopulationGroupConfigRegistry populationGroups;
    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(Snapshot.empty());
    private final AtomicReference<Map<String, UnavailableProfile>> rejected =
            new AtomicReference<>(Map.of());

    public ManagedActivityConfigRegistry() {
        this(new PopulationGroupConfigRegistry());
    }

    public ManagedActivityConfigRegistry(
            @Nonnull PopulationGroupConfigRegistry populationGroups
    ) {
        this.populationGroups = Objects.requireNonNull(
                populationGroups,
                "populationGroups"
        );
    }

    @Nonnull
    public Snapshot snapshot() {
        return current.get();
    }

    /**
     * Compiles and publishes one complete candidate.
     *
     * <p>The requested revision is a lower bound. A successful replacement
     * always advances beyond the active revision, even when callers reuse a
     * stale asset-event counter.</p>
     */
    @Nonnull
    public ReloadResult replace(
            @Nonnull Collection<TwManagedActivityConfig> configs,
            long requestedRevision
    ) {
        Objects.requireNonNull(configs, "configs");
        Snapshot previous = current.get();
        try {
            long revision = nextRevision(previous.revision(), requestedRevision);
            Snapshot replacement = compile(configs, revision);
            current.set(replacement);
            rejected.set(Map.of());
            return new ReloadResult(true, replacement, null);
        } catch (RuntimeException invalid) {
            String detail = stableDetail(invalid);
            rejected.set(rejectedProfiles(configs, detail));
            return new ReloadResult(false, previous, detail);
        }
    }

    /** Resolves one exact NPC role to one deterministic family mapping. */
    @Nonnull
    public Optional<RoleResolution> resolveRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return Optional.empty();
        }
        Snapshot snapshot = current.get();
        if (snapshot.revision() > 0L
                && snapshot.populationGroupRevision()
                != populationGroups.snapshot().revision()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.rolesById().get(roleId.trim()));
    }

    /** Returns explicit content readiness for one profile identity. */
    @Nonnull
    public Readiness readiness(@Nullable String profileId) {
        String requested = normalize(profileId);
        Snapshot snapshot = current.get();
        if (requested.isBlank()) {
            return Readiness.unavailable(
                    "",
                    "",
                    0,
                    snapshot.revision(),
                    "profile-id-required"
            );
        }
        if (snapshot.revision() > 0L
                && snapshot.populationGroupRevision()
                != populationGroups.snapshot().revision()) {
            UnavailableProfile staleRejection = rejected.get().get(requested);
            ManagedActivityProfile retainedProfile =
                    snapshot.profiles().get(requested);
            UnavailableProfile retainedUnavailable =
                    snapshot.unavailable().get(requested);
            String providerId = staleRejection != null
                    ? staleRejection.providerId()
                    : retainedProfile != null
                            ? retainedProfile.providerId()
                            : retainedUnavailable == null
                                    ? ""
                                    : retainedUnavailable.providerId();
            int providerContractVersion = staleRejection != null
                    ? staleRejection.providerContractVersion()
                    : retainedProfile != null
                            ? retainedProfile.providerContractVersion()
                            : retainedUnavailable == null
                                    ? 0
                                    : retainedUnavailable.providerContractVersion();
            return Readiness.unavailable(
                    requested,
                    providerId,
                    providerContractVersion,
                    snapshot.revision(),
                    staleRejection == null
                            ? "population-group-revision-stale"
                            : staleRejection.detail()
            );
        }
        ManagedActivityProfile profile = snapshot.profiles().get(requested);
        if (profile != null) {
            return new Readiness(
                    true,
                    profile.profileId(),
                    profile.providerId(),
                    profile.providerContractVersion(),
                    profile.configRevision(),
                    "ready"
            );
        }
        UnavailableProfile unavailable = snapshot.unavailable().get(requested);
        if (unavailable != null) {
            return Readiness.unavailable(
                    requested,
                    unavailable.providerId(),
                    unavailable.providerContractVersion(),
                    snapshot.revision(),
                unavailable.detail()
            );
        }
        unavailable = rejected.get().get(requested);
        if (unavailable != null) {
            return Readiness.unavailable(
                    requested,
                    unavailable.providerId(),
                    unavailable.providerContractVersion(),
                    snapshot.revision(),
                    unavailable.detail()
            );
        }
        return Readiness.unavailable(
                requested,
                "",
                0,
                snapshot.revision(),
                "profile-not-found"
        );
    }

    private Snapshot compile(
            Collection<TwManagedActivityConfig> configs,
            long revision
    ) {
        List<TwManagedActivityConfig> ordered = new ArrayList<>();
        Set<String> assetIds = new LinkedHashSet<>();
        for (TwManagedActivityConfig config : configs) {
            if (config == null) {
                continue;
            }
            String assetId = requireText(config.getId(), "managed asset id");
            if (!assetIds.add(assetId)) {
                throw new IllegalArgumentException(
                        "duplicate-managed-asset-id:" + assetId
                );
            }
            ordered.add(config);
        }
        ordered.sort(CONFIG_ORDER);

        LinkedHashMap<String, TwManagedActivityConfig> winners =
                new LinkedHashMap<>();
        LinkedHashMap<String, UnavailableProfile> unavailable =
                new LinkedHashMap<>();
        Set<String> selectedProfiles = new LinkedHashSet<>();
        for (TwManagedActivityConfig config : ordered) {
            String profileId = normalize(config.getProfileId());
            if (!config.isEnabled()) {
                if (!profileId.isBlank()) {
                    unavailable.putIfAbsent(
                            profileId,
                            new UnavailableProfile(
                                    profileId,
                                    normalize(config.getProviderId()),
                                    config.getProviderContractVersion(),
                                    "profile-disabled"
                            )
                    );
                }
                continue;
            }
            if (!profileId.isBlank() && !selectedProfiles.add(profileId)) {
                continue;
            }
            config.validateOrThrow();
            if (!winners.containsKey(profileId)) {
                winners.put(profileId, config);
            }
        }

        LinkedHashMap<String, ManagedActivityProfile> profiles =
                new LinkedHashMap<>();
        LinkedHashMap<String, RoleResolution> roles = new LinkedHashMap<>();
        PopulationGroupConfigIndex groupIndex =
                populationGroups.snapshot();
        for (Map.Entry<String, TwManagedActivityConfig> winner
                : winners.entrySet()) {
            ManagedActivityProfile profile = compileProfile(
                    winner.getValue(),
                    revision,
                    groupIndex
            );
            profiles.put(profile.profileId(), profile);
            addRoleMappings(
                    profile,
                    roles,
                    winner.getValue().getPriority(),
                    winner.getValue().getId()
            );
            unavailable.remove(profile.profileId());
        }
        return new Snapshot(
                revision,
                groupIndex.revision(),
                profiles,
                roles,
                unavailable
        );
    }

    private static Map<String, UnavailableProfile> rejectedProfiles(
            Collection<TwManagedActivityConfig> configs,
            String detail
    ) {
        LinkedHashMap<String, UnavailableProfile> result =
                new LinkedHashMap<>();
        for (TwManagedActivityConfig config : configs) {
            if (config == null) {
                continue;
            }
            String profileId = normalize(config.getProfileId());
            if (profileId.isBlank()) {
                continue;
            }
            result.putIfAbsent(
                    profileId,
                    new UnavailableProfile(
                            profileId,
                            normalize(config.getProviderId()),
                            config.getProviderContractVersion(),
                            detail
                    )
            );
        }
        return Map.copyOf(result);
    }

    private ManagedActivityProfile compileProfile(
            TwManagedActivityConfig config,
            long revision,
            PopulationGroupConfigIndex groupIndex
    ) {
        Map<String, TameworkApiCapability> capabilities =
                parseCapabilities(config);
        LinkedHashMap<String, ManagedActivityProfile.DomainDefinition>
                domains = new LinkedHashMap<>();
        for (TwManagedActivityConfig.DomainEntry entry : config.getDomains()) {
            String domainId = normalize(entry.getDomainId());
            if (domains.put(
                    domainId,
                    new ManagedActivityProfile.DomainDefinition(
                            domainId,
                            entry.isOwned(),
                            entry.isDeployable()
                    )
            ) != null) {
                throw new IllegalArgumentException(
                        "duplicate-domain-id:" + domainId
                );
            }
        }

        LinkedHashMap<String, ManagedActivityProfile.FamilyDefinition>
                families = new LinkedHashMap<>();
        Set<String> claimedRoles = new LinkedHashSet<>();
        for (TwManagedActivityConfig.FamilyEntry entry : config.getFamilies()) {
            String groupId = normalize(entry.getGroupId());
            PopulationGroupConfigDefinition group = groupIndex
                    .getDefinition(groupId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "managed-profile-missing-group:"
                                    + config.getProfileId() + ':' + groupId
                    ));
            LinkedHashSet<String> roleIds = new LinkedHashSet<>();
            for (String roleId : group.roleIds()) {
                String role = normalize(roleId);
                if (!claimedRoles.add(role)) {
                    throw new IllegalArgumentException(
                            "managed-profile-duplicate-role:"
                                    + config.getProfileId() + ':' + role
                    );
                }
                roleIds.add(role);
            }
            ManagedActivityProfile.FamilyDefinition family =
                    new ManagedActivityProfile.FamilyDefinition(
                            groupId,
                            normalize(entry.getGateKey()),
                            entry.getWeight(),
                            roleIds
                    );
            if (families.put(groupId, family) != null) {
                throw new IllegalArgumentException(
                        "duplicate-family-id:" + groupId
                );
            }
        }

        TwManagedActivityConfig.ActivitySettings settings =
                config.getActivities();
        ManagedActivityProfile.ActivityMapping activities =
                new ManagedActivityProfile.ActivityMapping(
                        normalize(settings.getFeed()),
                        settings.getHarvestContexts(),
                        settings.getPendingOutputItems(),
                        normalize(settings.getBreedingSuccess()),
                        normalize(settings.getTameSuccess()),
                        normalize(settings.getNeedSatisfied())
                );
        return new ManagedActivityProfile(
                normalize(config.getProfileId()),
                normalize(config.getProviderId()),
                config.getProviderContractVersion(),
                new LinkedHashSet<>(capabilities.values()),
                domains,
                families,
                activities,
                revision
        );
    }

    private Map<String, TameworkApiCapability> parseCapabilities(
            TwManagedActivityConfig config
    ) {
        LinkedHashMap<String, TameworkApiCapability> result =
                new LinkedHashMap<>();
        for (String raw : config.getRequiredCapabilities()) {
            String normalized = requireText(
                    raw,
                    "required capability"
            ).toUpperCase(Locale.ROOT);
            TameworkApiCapability capability;
            try {
                capability = TameworkApiCapability.valueOf(normalized);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(
                        "unknown-required-capability:"
                                + config.getProfileId() + ':' + normalized,
                        unknown
                );
            }
            if (result.put(normalized, capability) != null) {
                throw new IllegalArgumentException(
                        "duplicate-required-capability:" + normalized
                );
            }
        }
        if (!config.hasRequiredCapabilities()) {
            throw new IllegalArgumentException(
                    "required-capabilities-section-missing:"
                            + config.getProfileId()
            );
        }
        return result;
    }

    private static void addRoleMappings(
            ManagedActivityProfile profile,
            Map<String, RoleResolution> roles,
            int priority,
            @Nullable String assetId
    ) {
        for (ManagedActivityProfile.FamilyDefinition family
                : profile.families().values()) {
            for (String roleId : family.roleIds()) {
                RoleResolution candidate = new RoleResolution(
                        roleId,
                        profile,
                        family,
                        priority,
                        safeId(assetId)
                );
                RoleResolution existing = roles.get(roleId);
                if (existing == null || ROLE_ORDER.compare(candidate, existing) < 0) {
                    roles.put(roleId, candidate);
                }
            }
        }
    }

    private static final Comparator<RoleResolution> ROLE_ORDER =
            Comparator.comparingInt(RoleResolution::priority)
                    .reversed()
                    .thenComparing(
                            RoleResolution::assetId,
                            String.CASE_INSENSITIVE_ORDER
                    )
                    .thenComparing(RoleResolution::assetId);

    private static long nextRevision(long active, long requested) {
        if (requested < 0L) {
            throw new IllegalArgumentException(
                    "managed config revision cannot be negative"
            );
        }
        return Math.max(active + 1L, requested);
    }

    private static String stableDetail(RuntimeException invalid) {
        String message = invalid.getMessage();
        return message == null || message.isBlank()
                ? "managed-profile-invalid"
                : message.trim();
    }

    private static String safeId(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireText(@Nullable String value, String label) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return normalized;
    }

    /** Immutable active config snapshot. */
    public record Snapshot(
            long revision,
            long populationGroupRevision,
            @Nonnull Map<String, ManagedActivityProfile> profiles,
            @Nonnull Map<String, RoleResolution> rolesById,
            @Nonnull Map<String, UnavailableProfile> unavailable
    ) {
        public Snapshot {
            profiles = Map.copyOf(profiles);
            rolesById = Map.copyOf(rolesById);
            unavailable = Map.copyOf(unavailable);
        }

        private static Snapshot empty() {
            return new Snapshot(0L, 0L, Map.of(), Map.of(), Map.of());
        }
    }

    /** Result of one atomic candidate replacement attempt. */
    public record ReloadResult(
            boolean applied,
            @Nonnull Snapshot active,
            @Nullable String error
    ) {
        public ReloadResult {
            Objects.requireNonNull(active, "active");
            if (!applied && (error == null || error.isBlank())) {
                error = "managed-profile-invalid";
            }
        }
    }

    /** Explicit readiness result for downstream provider integration. */
    public record Readiness(
            boolean available,
            @Nonnull String profileId,
            @Nonnull String providerId,
            int providerContractVersion,
            long configRevision,
            @Nonnull String detail
    ) {
        public Readiness {
            profileId = profileId == null ? "" : profileId.trim();
            providerId = providerId == null ? "" : providerId.trim();
            detail = requireText(detail, "readiness detail");
        }

        private static Readiness unavailable(
                String profileId,
                String providerId,
                int providerContractVersion,
                long configRevision,
                String detail
        ) {
            return new Readiness(
                    false,
                    profileId,
                    providerId,
                    providerContractVersion,
                    configRevision,
                    detail
            );
        }
    }

    /** One role-to-family resolution result with deterministic source metadata. */
    public record RoleResolution(
            @Nonnull String roleId,
            @Nonnull ManagedActivityProfile profile,
            @Nonnull ManagedActivityProfile.FamilyDefinition family,
            int priority,
            @Nonnull String assetId
    ) {
        public RoleResolution {
            roleId = requireText(roleId, "roleId");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(family, "family");
            assetId = assetId == null ? "" : assetId.trim();
        }
    }

    /** Identity and stable diagnostic for a profile that cannot be used. */
    public record UnavailableProfile(
            @Nonnull String profileId,
            @Nonnull String providerId,
            int providerContractVersion,
            @Nonnull String detail
    ) {
        public UnavailableProfile {
            profileId = requireText(profileId, "profileId");
            providerId = providerId == null ? "" : providerId.trim();
            detail = requireText(detail, "detail");
        }
    }
}
