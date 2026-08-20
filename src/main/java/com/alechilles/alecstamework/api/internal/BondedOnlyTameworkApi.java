package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.ActivityFeedApi;
import com.alechilles.alecstamework.api.CommandLinksApi;
import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.PolicyApi;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.ProgressionApi;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.TameworkConfigReadApi;
import com.alechilles.alecstamework.api.TameworkEventsApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import java.util.EnumSet;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Emergency API surface retained when generic persistence composition aborts.
 *
 * <p>Legacy generic surfaces are absent in this degraded startup mode and are
 * deliberately not advertised. The independently composed bonded authority is
 * exposed only while its own availability contract is satisfied.</p>
 */
public final class BondedOnlyTameworkApi implements TameworkApi {
    private static final String API_VERSION = "0.10.0";
    private final BondedCompanionApi bonded;

    public BondedOnlyTameworkApi(@Nonnull BondedCompanionApi bonded) {
        this.bonded = Objects.requireNonNull(bonded, "bonded");
    }

    @Override public String getApiVersion() {
        return API_VERSION;
    }

    @Override public EnumSet<TameworkApiCapability> getCapabilities() {
        return bonded.availability().available()
                ? EnumSet.of(TameworkApiCapability.BONDED_COMPANIONS)
                : EnumSet.noneOf(TameworkApiCapability.class);
    }

    @Override public BondedCompanionApi bondedCompanions() {
        return bonded.availability().available()
                ? bonded : BondedCompanionApi.unavailable();
    }

    @Override public ActivityFeedApi activities() {
        return ActivityFeedApi.unavailable();
    }

    @Nullable @Override public NpcProfilesApi profiles() { return null; }
    @Nullable @Override public CommandLinksApi commandLinks() { return null; }
    @Nullable @Override public ProgressionApi progression() { return null; }
    @Nullable @Override public PolicyApi policies() { return null; }
    @Nullable @Override public InteractionExtensionApi interactionExtensions() {
        return null;
    }
    @Nullable @Override public TraitEffectApi traitEffects() { return null; }
    @Nullable @Override public ProfileDataApi profileData() { return null; }
    @Nullable @Override public TameworkEventsApi events() { return null; }
    @Nullable @Override public TameworkConfigReadApi configs() { return null; }
    @Nullable @Override public DiagnosticsApi diagnostics() { return null; }
}
