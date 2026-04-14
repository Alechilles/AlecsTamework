package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Logs an explicit debug probe message with NPC identity context.
 */
public final class ActionTameworkDebugMessage extends TameworkActionBase {
    private static final Logger FALLBACK_LOGGER = Logger.getLogger(ActionTameworkDebugMessage.class.getName());
    private static final long REPEAT_LOG_INTERVAL_MS = 2_000L;
    private static final ConcurrentHashMap<String, Long> LAST_LOG_BY_MESSAGE_KEY = new ConcurrentHashMap<>();

    @Nullable
    private final String message;

    public ActionTameworkDebugMessage(@Nonnull BuilderActionTameworkDebugMessage builder,
                                      @Nonnull BuilderSupport support) {
        super(builder);
        this.message = builder.getMessage(support);
    }

    @Override
    public boolean canExecute(@Nullable Ref<EntityStore> npcRef,
                              @Nullable Role role,
                              @Nullable InfoProvider infoProvider,
                              double dt,
                              @Nullable Store<EntityStore> store) {
        return npcRef != null && store != null && npcRef.isValid();
    }

    @Override
    public boolean execute(@Nullable Ref<EntityStore> npcRef,
                           @Nullable Role role,
                           @Nullable InfoProvider infoProvider,
                           double dt,
                           @Nullable Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        String npcId = resolveNpcId(npcRef, store);
        String value = message == null ? "" : message.trim();
        String probe = value.isBlank() ? "<empty>" : value;
        String logMessage = "Debug action message: npc=" + npcId + " message=" + probe;
        long nowMs = System.currentTimeMillis();
        if (shouldSuppress(npcId + '|' + probe, nowMs)) {
            return true;
        }
        Level level = resolveLevel(probe);
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(level).log(logMessage);
            return true;
        }
        FALLBACK_LOGGER.log(level, logMessage);
        return true;
    }

    private static boolean shouldSuppress(@Nonnull String messageKey, long nowMs) {
        Long previous = LAST_LOG_BY_MESSAGE_KEY.put(messageKey, nowMs);
        return previous != null && nowMs < previous + REPEAT_LOG_INTERVAL_MS;
    }

    @Nonnull
    private static Level resolveLevel(@Nonnull String probe) {
        return probe.startsWith("Needs seek blocked:") ? Level.WARNING : Level.INFO;
    }

    @Nonnull
    private static String resolveNpcId(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getUuid() != null) {
            return npc.getUuid().toString();
        }
        return npcRef.toString();
    }
}
