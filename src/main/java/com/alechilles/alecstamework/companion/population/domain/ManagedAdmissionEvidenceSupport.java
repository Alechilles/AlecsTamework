package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.config.managed.ManagedActivityProfile;
import java.util.Objects;

/** Small pure helpers for managed admission evidence derivation. */
final class ManagedAdmissionEvidenceSupport {
    private ManagedAdmissionEvidenceSupport() {
    }

    static ProfileId profileId(PopulationAdmissionRequest request) {
        String value = request.identity().canonicalProfileId();
        if (value == null) {
            value = request.identity().provisionalProfileId();
        }
        if (value == null) {
            throw new IllegalStateException("managed-profile-identity-missing");
        }
        try {
            return ProfileId.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("managed-profile-identity-invalid", invalid);
        }
    }

    static boolean positiveTransition(
            OwnerId sourceOwner,
            String sourceWorld,
            LifecycleState beforeState,
            OwnerId targetOwner,
            String targetWorld,
            LifecycleState afterState,
            ManagedActivityProfile profile
    ) {
        if (targetOwner == null) {
            return false;
        }
        PopulationDomainLifecycleClassifier.Classification before = beforeState == null
                ? new PopulationDomainLifecycleClassifier.Classification(false, false)
                : PopulationDomainLifecycleClassifier.classify(beforeState);
        PopulationDomainLifecycleClassifier.Classification after =
                PopulationDomainLifecycleClassifier.classify(afterState);
        boolean ownerChanged = !Objects.equals(sourceOwner, targetOwner);
        boolean worldChanged = !Objects.equals(sourceWorld, targetWorld);
        for (ManagedActivityProfile.DomainDefinition domain : profile.domains().values()) {
            if (domain.owned() && after.owned()
                    && (ownerChanged || !before.owned()
                    || (sourceWorld != null && worldChanged))) {
                return true;
            }
            if (domain.deployable() && after.deployable()
                    && (ownerChanged || !before.deployable()
                    || (sourceWorld != null && worldChanged))) {
                return true;
            }
        }
        return false;
    }

    static String targetWorld(PopulationAdmissionRequestV3 request) {
        PopulationAdmissionRequest admission = request.request().request();
        return admission.destination() == null
                ? request.request().ownershipWorldName()
                : admission.destination().worldName();
    }
}
