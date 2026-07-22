package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupClassificationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the canonical pre-transition role used by public V2 population admissions. */
final class PersistencePopulationRoleResolver
        implements PublicPopulationAdmissionPlanner.CanonicalRoleResolver {
    private final PopulationGroupRepository groups;
    private final NpcProfileRepository profiles;

    PersistencePopulationRoleResolver(@Nonnull PopulationGroupRepository groups,
                                      @Nonnull NpcProfileRepository profiles) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Nullable
    @Override
    public String resolve(@Nonnull String profileId) {
        try {
            PopulationGroupClassificationRecord classification =
                    groups.findClassification(profileId);
            if (classification != null && classification.roleId() != null) {
                return classification.roleId();
            }
            NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(profileId);
            return profile == null ? null : profile.roleId();
        } catch (Exception failure) {
            return null;
        }
    }
}
