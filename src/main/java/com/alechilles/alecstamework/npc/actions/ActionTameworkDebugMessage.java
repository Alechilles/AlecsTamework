package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Logs an explicit debug probe message with NPC identity context.
 */
public final class ActionTameworkDebugMessage extends TameworkActionBase {
    private static final Logger FALLBACK_LOGGER = Logger.getLogger(ActionTameworkDebugMessage.class.getName());

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
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.INFO).log(logMessage);
            return true;
        }
        FALLBACK_LOGGER.log(Level.INFO, logMessage);
        return true;
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
