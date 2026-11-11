package com.alechilles.alecstamework.access;

import com.alechilles.alecstamework.api.access.AccessAction;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one place gameplay code asks "may this player do this with that companion".
 *
 * <p>Use it wherever a check used to compare the acting player with the owner to decide whether
 * to allow something. Do not use it where the owner's identity matters (caps, admission, owner
 * files, inheritance, who a capture item belongs to): those keep reading the owner.</p>
 *
 * <p>An unowned companion gives every player {@link AccessLevel#NONE}; callers keep their
 * existing rule for unowned animals. The body methods read components and so run on the body's
 * world thread; the record methods are safe from any thread.</p>
 */
public final class CompanionAccess {
    private CompanionAccess() {
    }

    /** The level {@code actorId} holds on a loaded companion body. */
    @Nonnull
    public static AccessLevel level(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable ComponentAccessor<EntityStore> accessor,
                                    @Nullable UUID actorId) {
        if (npcRef == null || accessor == null || actorId == null || !npcRef.isValid()) {
            return AccessLevel.NONE;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null ? null : accessor.getComponent(npcRef, ownerType);
        return level(owner == null ? null : owner.getOwnerId(), actorId, npcRef, accessor);
    }

    /** As {@link #level(Ref, ComponentAccessor, UUID)} for a caller that already read the owner. */
    @Nonnull
    public static AccessLevel level(@Nullable UUID ownerId,
                                    @Nullable UUID actorId,
                                    @Nullable Ref<EntityStore> npcRef,
                                    @Nullable ComponentAccessor<EntityStore> accessor) {
        if (ownerId == null || actorId == null) {
            return AccessLevel.NONE;
        }
        if (ownerId.equals(actorId)) {
            return AccessLevel.OWNER;
        }
        SharedAccessService service = SharedAccessService.get();
        if (!service.sharesAnything(ownerId)) {
            return AccessLevel.NONE;
        }
        return service.level(ownerId, actorId, profileId(npcRef, accessor));
    }

    public static boolean allows(@Nullable Ref<EntityStore> npcRef,
                                 @Nullable ComponentAccessor<EntityStore> accessor,
                                 @Nullable UUID actorId,
                                 @Nonnull AccessAction action) {
        return action.allowedAt(level(npcRef, accessor, actorId));
    }

    public static boolean allows(@Nullable UUID ownerId,
                                 @Nullable UUID actorId,
                                 @Nullable Ref<EntityStore> npcRef,
                                 @Nullable ComponentAccessor<EntityStore> accessor,
                                 @Nonnull AccessAction action) {
        return action.allowedAt(level(ownerId, actorId, npcRef, accessor));
    }

    /** The level {@code actorId} holds on a companion known only by its record, loaded or not. */
    @Nonnull
    public static AccessLevel level(@Nullable CompanionRecord record, @Nullable UUID actorId) {
        if (record == null) {
            return AccessLevel.NONE;
        }
        return SharedAccessService.get().level(record.ownerUuid(), actorId, record.profileId());
    }

    public static boolean allows(@Nullable CompanionRecord record, @Nullable UUID actorId,
                                 @Nonnull AccessAction action) {
        return action.allowedAt(level(record, actorId));
    }

    /** The level by ids alone, for callers with neither a body nor a record at hand. */
    @Nonnull
    public static AccessLevel level(@Nullable UUID ownerId, @Nullable UUID actorId, @Nullable UUID profileId) {
        return SharedAccessService.get().level(ownerId, actorId, profileId);
    }

    public static boolean allows(@Nullable UUID ownerId, @Nullable UUID actorId, @Nullable UUID profileId,
                                 @Nonnull AccessAction action) {
        return action.allowedAt(level(ownerId, actorId, profileId));
    }

    @Nullable
    private static UUID profileId(@Nullable Ref<EntityStore> npcRef,
                                  @Nullable ComponentAccessor<EntityStore> accessor) {
        if (npcRef == null || accessor == null || !npcRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkCompanionComponent> type = TameworkCompanionComponent.getComponentType();
        TameworkCompanionComponent companion = type == null ? null : accessor.getComponent(npcRef, type);
        return companion == null ? null : companion.getProfileId();
    }
}
