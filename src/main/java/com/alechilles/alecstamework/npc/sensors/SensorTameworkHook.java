package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkHookInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkHookInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHook;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.sensorinfo.parameterproviders.MultipleParameterProvider;
import com.hypixel.hytale.server.npc.sensorinfo.parameterproviders.SingleDoubleParameterProvider;
import com.hypixel.hytale.server.npc.sensorinfo.parameterproviders.SingleStringParameterProvider;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Sensor that matches when a Tamework hook has been triggered on the NPC.
 */
public final class SensorTameworkHook extends TameworkSensorBase {
    private static final long MAX_HOOK_AGE_MS = 1000L;
    private static final AtomicLong LAST_LOG_MS = new AtomicLong();
    private static final long LOG_THROTTLE_MS = 500L;
    private final String hookId;
    private final boolean consume;
    private final MultipleParameterProvider parameterProvider = new MultipleParameterProvider();
    private final SingleStringParameterProvider hookIdProvider;
    private final SingleStringParameterProvider playerIdProvider;
    private final SingleStringParameterProvider playerNameProvider;
    private final SingleStringParameterProvider heldItemProvider;
    private final SingleDoubleParameterProvider timestampProvider;
    private final TameworkHookInfo hookInfo = new TameworkHookInfo();
    private final InfoProvider infoProvider;

    public SensorTameworkHook(@Nonnull BuilderSensorTameworkHook builder, @Nonnull BuilderSupport support) {
        super(builder);
        this.hookId = builder.getHookId(support);
        this.consume = builder.isConsume(support);
        int hookIdSlot = support.getParameterSlot(TameworkHookInfo.PARAM_HOOK_ID);
        this.hookIdProvider = new SingleStringParameterProvider(hookIdSlot);
        this.parameterProvider.addParameterProvider(hookIdSlot, this.hookIdProvider);

        int playerIdSlot = support.getParameterSlot(TameworkHookInfo.PARAM_HOOK_PLAYER_ID);
        this.playerIdProvider = new SingleStringParameterProvider(playerIdSlot);
        this.parameterProvider.addParameterProvider(playerIdSlot, this.playerIdProvider);

        int playerNameSlot = support.getParameterSlot(TameworkHookInfo.PARAM_HOOK_PLAYER_NAME);
        this.playerNameProvider = new SingleStringParameterProvider(playerNameSlot);
        this.parameterProvider.addParameterProvider(playerNameSlot, this.playerNameProvider);

        int heldItemSlot = support.getParameterSlot(TameworkHookInfo.PARAM_HOOK_HELD_ITEM_ID);
        this.heldItemProvider = new SingleStringParameterProvider(heldItemSlot);
        this.parameterProvider.addParameterProvider(heldItemSlot, this.heldItemProvider);

        int timestampSlot = support.getParameterSlot(TameworkHookInfo.PARAM_HOOK_TIMESTAMP_MS);
        this.timestampProvider = new SingleDoubleParameterProvider(timestampSlot);
        this.parameterProvider.addParameterProvider(timestampSlot, this.timestampProvider);

        this.infoProvider = new TameworkHookInfoProvider(this.parameterProvider, this.hookInfo);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHookComponent> type = TameworkHookComponent.getComponentType();
        if (type == null) {
            clearProviders();
            return false;
        }
        TameworkHookComponent component = store.getComponent(ref, type);
        if (component == null || !component.matchesHook(hookId)) {
            clearProviders();
            return false;
        }
        long timestampMs = component.getTimestampMs();
        if (timestampMs <= 0L || System.currentTimeMillis() - timestampMs > MAX_HOOK_AGE_MS) {
            clearProviders();
            if (consume || component.isConsumeOnMatch()) {
                store.putComponent(ref, type, new TameworkHookComponent());
            }
            logHookEvent("stale", ref, component, null);
            return false;
        }
        this.hookIdProvider.overrideString(component.getHookId());
        this.playerIdProvider.overrideString(component.getPlayerId() != null ? component.getPlayerId().toString() : null);
        this.playerNameProvider.overrideString(component.getPlayerName());
        this.heldItemProvider.overrideString(component.getHeldItemId());
        this.timestampProvider.overrideDouble((double) component.getTimestampMs());
        this.hookInfo.updateFrom(component);
        logHookEvent("match", ref, component, null);
        if (consume || component.isConsumeOnMatch()) {
            store.putComponent(ref, type, new TameworkHookComponent());
        }
        return true;
    }

    @Override
    public InfoProvider getSensorInfo() {
        return infoProvider;
    }

    private void clearProviders() {
        this.parameterProvider.clear();
        this.hookInfo.clear();
    }

    private void logHookEvent(String action,
                              Ref<EntityStore> npcRef,
                              TameworkHookComponent component,
                              Ref<EntityStore> interactionTarget) {
        long now = System.currentTimeMillis();
        long last = LAST_LOG_MS.get();
        if (now - last < LOG_THROTTLE_MS) {
            return;
        }
        if (!LAST_LOG_MS.compareAndSet(last, now)) {
            return;
        }
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null || !instance.isDebugHookEnabled()) {
            return;
        }
        String hookIdValue = component != null ? component.getHookId() : null;
        long tsMs = component != null ? component.getTimestampMs() : 0L;
        instance.getLogger().at(Level.WARNING).log(
                "TameworkHook: " + action
                        + " npcRef=" + npcRef
                        + " hookId=" + hookIdValue
                        + " tsMs=" + tsMs
                        + " interactionTarget=" + interactionTarget
                        + " consume=" + consume
        );
    }
}
