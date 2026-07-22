package com.alechilles.alecstamework.api;

import java.util.EnumSet;

public interface TameworkApi {
    String getApiVersion();

    EnumSet<TameworkApiCapability> getCapabilities();

    NpcProfilesApi profiles();

    CommandLinksApi commandLinks();

    /** Timed roster operations; require {@link TameworkApiCapability#COMMAND_TIMED_SUMMONING}. */
    default CommandTimedSummoningApi commandTimedSummoning() {
        return CommandTimedSummoningApi.unavailable();
    }

    ProgressionApi progression();

    PolicyApi policies();

    InteractionExtensionApi interactionExtensions();

    TraitEffectApi traitEffects();

    ProfileDataApi profileData();

    TameworkEventsApi events();

    TameworkConfigReadApi configs();

    DiagnosticsApi diagnostics();

    /** Returns the durable owner/command-family roster authority when advertised. */
    default CommandFamilyRosterApi commandFamilyRosters() {
        return CommandFamilyRosterApi.unavailable();
    }

    /**
     * Returns the idempotent companion-provisioning authority when advertised by
     * {@link TameworkApiCapability#COMPANION_PROVISIONING}.
     */
    default CompanionProvisioningApi companionProvisioning() {
        return CompanionProvisioningApi.unavailable();
    }

    /** Returns paid command revival when {@link TameworkApiCapability#PAID_COMMAND_REVIVAL} is advertised. */
    default PaidCommandRevivalApi paidCommandRevival() {
        return PaidCommandRevivalApi.unavailable();
    }

    /** Read-only population-group counts used by roster capacity presentation. */
    default PopulationGroupApi populationGroups() {
        return PopulationGroupApi.unavailable();
    }
}

