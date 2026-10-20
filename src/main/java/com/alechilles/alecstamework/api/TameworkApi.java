package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.api.commandhud.CommandHudApi;
import com.alechilles.alecstamework.api.commandui.CommandUiApi;
import java.util.EnumSet;

public interface TameworkApi {
    String getApiVersion();

    EnumSet<TameworkApiCapability> getCapabilities();

    NpcProfilesApi profiles();

    CommandLinksApi commandLinks();

    ProgressionApi progression();

    PolicyApi policies();

    InteractionExtensionApi interactionExtensions();

    TraitEffectApi traitEffects();

    ProfileDataApi profileData();

    TameworkEventsApi events();

    TameworkConfigReadApi configs();

    DiagnosticsApi diagnostics();

    /** Returns read-only population-group counts and reconciliation state. */
    default PopulationGroupApi populationGroups() {
        return PopulationGroupApi.unavailable();
    }

    /** Returns the separate bonded-companion authority when advertised. */
    default BondedCompanionApi bondedCompanions() {
        return BondedCompanionApi.unavailable();
    }

    /** Returns the durable successful-activity feed when advertised. */
    default ActivityFeedApi activities() {
        return ActivityFeedApi.unavailable();
    }

    /** Returns immutable managed-content readiness when advertised. */
    default RequiredContentProfileApi requiredContentProfiles() {
        return RequiredContentProfileApi.unavailable();
    }

    /**
     * Returns the optional command UI renderer and contributor registration facade.
     *
     * <p>The default keeps adapters that do not host command UI fail-closed.</p>
     */
    default CommandUiApi commandUi() {
        return CommandUiApi.unavailable();
    }

    /**
     * Returns the optional command HUD renderer and contributor registration
     * facade.
     *
     * <p>The default keeps older, replacement, bonded-only, and degraded
     * implementations fail-closed while they are upgraded.</p>
     */
    default CommandHudApi commandHud() {
        return CommandHudApi.unavailable();
    }

    /** Returns the optional synchronous husbandry outcome provider facade. */
    default HusbandryOutcomeApi husbandryOutcomes() {
        return HusbandryOutcomeApi.unavailable();
    }

    /** Returns the optional synchronous captured-item display provider facade. */
    default CapturedItemDisplayApi capturedItemDisplay() {
        return CapturedItemDisplayApi.unavailable();
    }
}

