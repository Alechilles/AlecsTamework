package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.api.commandui.CommandUiApi;
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

    /** Returns paid revival when its corresponding capability is advertised. */
    default PaidCommandRevivalApi paidCommandRevival() {
        return PaidCommandRevivalApi.unavailable();
    }

    /** Returns read-only population-group counts and reconciliation state. */
    default PopulationGroupApi populationGroups() {
        return PopulationGroupApi.unavailable();
    }

    /** Returns the separate bonded-companion authority when advertised. */
    default BondedCompanionApi bondedCompanions() {
        return BondedCompanionApi.unavailable();
    }

    /**
     * Returns the optional command-menu provider registration facade.
     *
     * <p>This default keeps source and binary compatibility with API
     * implementations compiled before command UI providers existed.</p>
     */
    default CommandUiApi commandUi() {
        return CommandUiApi.unavailable();
    }
}

