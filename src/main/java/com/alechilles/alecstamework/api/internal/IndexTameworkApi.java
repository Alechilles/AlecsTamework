package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ActivityFeedApi;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.CapturedItemDisplayApi;
import com.alechilles.alecstamework.api.CommandLinksApi;
import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.HusbandryOutcomeApi;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.PolicyApi;
import com.alechilles.alecstamework.api.PopulationGroupApi;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.ProgressionApi;
import com.alechilles.alecstamework.api.RequiredContentProfileApi;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.TameworkConfigReadApi;
import com.alechilles.alecstamework.api.TameworkEventsApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.api.commandhud.CommandHudApi;
import com.alechilles.alecstamework.api.commandui.CommandUiApi;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The public API root (3.0.0) over the companion index. It exists only while the companion
 * module is ready. Profile, command link, progression, policy and config reads come from
 * {@link TameworkApiImpl} over {@link IndexNpcProfilesApi}, which also forwards profile data and
 * diagnostics to the index-backed delegates given here; this class adds population group reads,
 * required content profile readiness and the activity feed, and owns the lifecycle. The admission
 * provider registry is owned by the caller, which closes it. The command UI and HUD registries it returns
 * are the instances the command handlers and HUD services use.
 */
public final class IndexTameworkApi implements TameworkApi, AutoCloseable {
    private final TameworkApiImpl base;
    private final PopulationGroupApi populationGroups;
    private final RequiredContentProfileApi requiredContentProfiles;
    private final LiveActivityFeed activities = new LiveActivityFeed();
    private final AtomicBoolean closed = new AtomicBoolean();
    @Nullable private volatile BondedCompanionApi bondedCompanions;

    public IndexTameworkApi(
            @Nonnull IndexNpcProfilesApi profiles,
            @Nonnull ProfileDataApi profileData,
            @Nonnull DiagnosticsApi diagnostics,
            @Nonnull PopulationGroupApi populationGroups,
            @Nonnull TameworkEventBus eventBus,
            @Nullable CommandLinkedNpcStateSnapshotService stateSnapshots,
            @Nonnull InteractionExtensionApi interactionExtensions,
            @Nonnull TraitEffectApi traitEffects,
            @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy,
            @Nonnull CommandUiRegistry commandUi,
            @Nonnull CommandHudRegistry commandHud,
            @Nonnull ItemFeatureRegistry captureItemConfigs,
            @Nonnull CapturePolicyRegistry capturePolicies,
            @Nonnull AdmissionProviderRegistry admissionProviders,
            @Nonnull ManagedActivityConfigRegistry managedActivities
    ) {
        Objects.requireNonNull(managedActivities, "managedActivities");
        this.base = new TameworkApiImpl(
                profiles,
                profileData,
                diagnostics,
                eventBus,
                stateSnapshots,
                interactionExtensions,
                traitEffects,
                damagePolicy,
                commandUi,
                commandHud
        );
        this.populationGroups = Objects.requireNonNull(populationGroups, "populationGroups");
        this.requiredContentProfiles =
                new RequiredContentProfileReadiness(managedActivities::readiness, admissionProviders);
        base.activateCapturePolicyRuntime(captureItemConfigs, capturePolicies);
        base.useAdmissionProviders(admissionProviders);
    }

    /** The publisher of the one shared activity feed, for {@code ActivityRuntime.install}. */
    @Nonnull
    public LiveActivityFeed.Publisher activityPublisher() {
        return activities.publisher();
    }

    /**
     * Sets the bonded companion authority this API hands out and advertises. The caller owns it
     * and closes it; null, or an authority that reports itself unavailable, leaves bonded
     * companions unavailable and {@code BONDED_COMPANIONS} unadvertised.
     */
    public void useBondedCompanions(@Nullable BondedCompanionApi bondedCompanions) {
        this.bondedCompanions = bondedCompanions;
    }

    /** Drops reflected optional-claim contracts after a settings change. */
    public void onRuntimeSettingsChanged() {
        base.onRuntimeSettingsChanged();
    }

    @Override
    public String getApiVersion() {
        return base.getApiVersion();
    }

    @Override
    public EnumSet<TameworkApiCapability> getCapabilities() {
        EnumSet<TameworkApiCapability> result = base.getCapabilities();
        result.add(TameworkApiCapability.POPULATION_GROUPS);
        result.add(TameworkApiCapability.DURABLE_POPULATION_GROUP_COUNTS);
        result.add(TameworkApiCapability.DURABLE_DEPLOYABLE_POPULATION_COUNTS);
        result.add(TameworkApiCapability.EXTERNAL_ADMISSION_PROVIDERS);
        result.add(TameworkApiCapability.REQUIRED_CONTENT_PROFILES);
        result.add(TameworkApiCapability.CAPTURE_TAME_AND_LINK);
        result.add(TameworkApiCapability.CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION);
        if (bondedCompanions().availability().available()) {
            result.add(TameworkApiCapability.BONDED_COMPANIONS);
        }
        if (activities.isOpen()) {
            result.add(TameworkApiCapability.ACTIVITY_FEED_V2);
            result.add(TameworkApiCapability.REVIVAL_ACTIVITY_CONTEXT);
        }
        return result;
    }

    @Override
    public NpcProfilesApi profiles() {
        return base.profiles();
    }

    @Override
    public CommandLinksApi commandLinks() {
        return base.commandLinks();
    }

    @Override
    public ProgressionApi progression() {
        return base.progression();
    }

    @Override
    public PolicyApi policies() {
        return base.policies();
    }

    @Override
    public InteractionExtensionApi interactionExtensions() {
        return base.interactionExtensions();
    }

    @Override
    public TraitEffectApi traitEffects() {
        return base.traitEffects();
    }

    @Override
    public ProfileDataApi profileData() {
        return base.profileData();
    }

    @Override
    public TameworkEventsApi events() {
        return base.events();
    }

    @Override
    public TameworkConfigReadApi configs() {
        return base.configs();
    }

    @Override
    public DiagnosticsApi diagnostics() {
        return base.diagnostics();
    }

    @Override
    public BondedCompanionApi bondedCompanions() {
        BondedCompanionApi current = bondedCompanions;
        return current == null || closed.get() ? BondedCompanionApi.unavailable() : current;
    }

    @Override
    public PopulationGroupApi populationGroups() {
        return populationGroups;
    }

    @Override
    public ActivityFeedApi activities() {
        return activities;
    }

    @Override
    public RequiredContentProfileApi requiredContentProfiles() {
        return requiredContentProfiles;
    }

    @Override
    public CommandUiApi commandUi() {
        return base.commandUi();
    }

    @Override
    public CommandHudApi commandHud() {
        return base.commandHud();
    }

    @Override
    public HusbandryOutcomeApi husbandryOutcomes() {
        return base.husbandryOutcomes();
    }

    @Override
    public CapturedItemDisplayApi capturedItemDisplay() {
        return base.capturedItemDisplay();
    }

    /** Closes the feed and the registries. Clear {@code ActivityRuntime} before calling this. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            activities.close();
            base.close();
        }
    }
}
