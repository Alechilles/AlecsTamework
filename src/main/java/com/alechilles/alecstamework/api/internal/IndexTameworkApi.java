package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ActivityFeedApi;
import com.alechilles.alecstamework.api.CapturedItemDisplayApi;
import com.alechilles.alecstamework.api.CommandLinksApi;
import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.HusbandryOutcomeApi;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.PolicyApi;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.ProgressionApi;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.TameworkConfigReadApi;
import com.alechilles.alecstamework.api.TameworkEventsApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.api.commandhud.CommandHudApi;
import com.alechilles.alecstamework.api.commandui.CommandUiApi;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The public API root (3.0.0) over the companion index. It exists only while the companion
 * module is ready. Profile, command link, progression, policy and config reads come from
 * {@link TameworkApiImpl} over {@link IndexNpcProfilesApi}; this class adds the activity feed
 * and owns the lifecycle. The command UI and HUD registries it returns are the instances the
 * command handlers and HUD services use.
 *
 * <p>Profile data and diagnostics are not served on the index yet: their accessors return
 * fail-closed facades and their capabilities are not advertised.</p>
 */
public final class IndexTameworkApi implements TameworkApi, AutoCloseable {
    private static final Set<TameworkApiCapability> NOT_SERVED = Set.of(
            TameworkApiCapability.PROFILE_DATA,
            TameworkApiCapability.PROFILE_DATA_TRANSACTIONS,
            TameworkApiCapability.DIAGNOSTICS
    );

    private final TameworkApiImpl base;
    private final LiveActivityFeed activities = new LiveActivityFeed();
    private final AtomicBoolean closed = new AtomicBoolean();

    public IndexTameworkApi(
            @Nonnull IndexNpcProfilesApi profiles,
            @Nonnull TameworkEventBus eventBus,
            @Nullable CommandLinkedNpcStateSnapshotService stateSnapshots,
            @Nonnull InteractionExtensionApi interactionExtensions,
            @Nonnull TraitEffectApi traitEffects,
            @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy,
            @Nonnull CommandUiRegistry commandUi,
            @Nonnull CommandHudRegistry commandHud,
            @Nonnull ItemFeatureRegistry captureItemConfigs,
            @Nonnull CapturePolicyRegistry capturePolicies
    ) {
        this.base = new TameworkApiImpl(
                profiles,
                new UnavailableProfileData(),
                IndexTameworkApi::unavailableDiagnostics,
                eventBus,
                stateSnapshots,
                interactionExtensions,
                traitEffects,
                damagePolicy,
                commandUi,
                commandHud
        );
        base.activateCapturePolicyRuntime(captureItemConfigs, capturePolicies);
    }

    /** The publisher of the one shared activity feed, for {@code ActivityRuntime.install}. */
    @Nonnull
    public LiveActivityFeed.Publisher activityPublisher() {
        return activities.publisher();
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
        result.removeAll(NOT_SERVED);
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
    public ActivityFeedApi activities() {
        return activities;
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

    @Nonnull
    private static PersistenceDiagnosticsView unavailableDiagnostics() {
        return new PersistenceDiagnosticsView(
                "",
                0L,
                0L,
                0L,
                0L,
                new PersistenceDiagnosticsView.QueueMetricsView(
                        0, 0, 0, 0L, 0L, 0L, 0L, 0.0, 0.0, 0.0, null, 0L),
                new PersistenceDiagnosticsView.HealthView("UNAVAILABLE", "diagnostics-unavailable", 0L)
        );
    }

    /** Reads nothing and refuses every write; the versioned calls keep their interface defaults. */
    private static final class UnavailableProfileData implements ProfileDataApi {
        @Override
        public Optional<String> get(String profileId, String namespace, String key) {
            return Optional.empty();
        }

        @Override
        public Map<String, String> list(String profileId, String namespace) {
            return Map.of();
        }

        @Override
        public boolean put(String profileId, String namespace, String key, String jsonPayload) {
            return false;
        }

        @Override
        public boolean delete(String profileId, String namespace, String key) {
            return false;
        }
    }
}
