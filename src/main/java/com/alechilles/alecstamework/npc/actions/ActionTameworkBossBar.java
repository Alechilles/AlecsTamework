package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.builtin.encountermanager.EncounterBossBarState;
import com.hypixel.hytale.builtin.encountermanager.EncounterManagerPlugin;
import com.hypixel.hytale.builtin.encountermanager.EncounterMembers;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Uses native membership and packets without replacing the NPC's role supports. */
public final class ActionTameworkBossBar extends TameworkActionBase {
    private static final double REFRESH_SECONDS = 0.25;
    private static final float MEMBER_TTL_SECONDS = 0.75f;

    private final double range;
    @Nullable
    private final String nameKey;
    private double refreshRemaining;

    public ActionTameworkBossBar(@Nonnull BuilderActionTameworkBossBar builder,
                                @Nonnull BuilderSupport support) {
        super(builder);
        this.range = builder.getRange(support);
        String configuredName = builder.getName(support);
        this.nameKey = configuredName == null || configuredName.isBlank() ? null : configuredName;
    }

    @Override
    public void activate(@Nullable Role role, @Nullable InfoProvider infoProvider) {
        super.activate(role, infoProvider);
        refreshRemaining = 0;
    }

    @Override
    public boolean execute(@Nullable Ref<EntityStore> npcRef,
                           @Nullable Role role,
                           @Nullable InfoProvider infoProvider,
                           double dt,
                           @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        super.execute(npcRef, role, infoProvider, dt, store);
        // Dead roles may still evaluate instructions during their corpse animation.
        if (store.getArchetype(npcRef).contains(DeathComponent.getComponentType())) {
            return true;
        }
        refreshRemaining -= dt;
        if (refreshRemaining > 0) {
            return true;
        }
        refreshRemaining = REFRESH_SECONDS;

        if (EncounterManagerPlugin.get() == null) {
            return true;
        }

        UUIDComponent uuid = store.getComponent(npcRef, UUIDComponent.getComponentType());
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (uuid == null || transform == null) {
            return true;
        }
        SpatialResource<Ref<EntityStore>, EntityStore> spatial =
                store.getResource(EntityModule.get().getPlayerSpatialResourceType());
        if (spatial == null) {
            return true;
        }

        ComponentType<EntityStore, EncounterMembers> membersType = EncounterMembers.getComponentType();
        EncounterMembers members = store.getComponent(npcRef, membersType);
        if (members == null) {
            members = new EncounterMembers();
            store.putComponent(npcRef, membersType, members);
        }
        ComponentType<EntityStore, EncounterBossBarState> barType = EncounterBossBarState.getComponentType();
        EncounterBossBarState bar = store.getComponent(npcRef, barType);
        if (bar == null) {
            bar = new EncounterBossBarState();
            store.putComponent(npcRef, barType, bar);
        }
        bar.setTracked(npcRef, uuid.getUuid(), nameKey);

        // This reusable list is consumed in this callback and never retained across ticks.
        List<Ref<EntityStore>> nearby = SpatialResource.getThreadLocalReferenceList();
        spatial.getSpatialStructure().collect(transform.getPosition(), range, nearby);
        for (int i = 0; i < nearby.size(); i++) {
            Ref<EntityStore> playerRef = nearby.get(i);
            if (playerRef != null && playerRef.isValid()) {
                members.stampMember(playerRef, MEMBER_TTL_SECONDS);
            }
        }
        return true;
    }
}
