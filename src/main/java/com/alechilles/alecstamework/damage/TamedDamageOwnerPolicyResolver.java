package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical live owner resolver shared by runtime and public damage evaluation. */
public final class TamedDamageOwnerPolicyResolver {
    private TamedDamageOwnerPolicyResolver() {
    }

    @Nonnull
    public static Resolution resolveLive(@Nonnull Ref<EntityStore> targetRef,
                                         @Nonnull Store<EntityStore> store) {
        return resolve(
                component(targetRef, store, TameworkOwnerComponent.getComponentType()),
                component(targetRef, store, TameworkCommandLinksComponent.getComponentType()),
                component(targetRef, store, TameworkNpcNameComponent.getComponentType()),
                CompanionRoleIdResolver.resolveRoleId(targetRef, store)
        );
    }

    @Nonnull
    public static Resolution resolve(@Nullable TameworkOwnerComponent owner,
                                      @Nullable TameworkCommandLinksComponent links,
                                      @Nullable TameworkNpcNameComponent npcName,
                                      @Nullable String roleId) {
        UUID ownerId = owner == null ? null : owner.getOwnerId();
        if (ownerId == null && links != null) {
            ownerId = links.getOwnerId();
        }
        if (ownerId == null && npcName != null) {
            ownerId = npcName.getOwnerId();
        }
        if (ownerId == null) {
            return new Resolution(TamedDamageOwnerPolicy.unowned(), roleId);
        }
        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.resolveEffectiveForRole(roleId);
        return new Resolution(new TamedDamageOwnerPolicy(
                ownerId,
                TameworkRuntimeSettings.blockOwnerDamage(settings.isBlockOwnerDamage()),
                TameworkRuntimeSettings.blockAllPlayerDamageIfOwned(
                        settings.isBlockAllPlayerDamageIfOwned()
                ),
                TameworkRuntimeSettings.invulnerableIfOwned(settings.isInvulnerableIfOwned())
        ), roleId);
    }

    @Nullable
    private static <T extends Component<EntityStore>> T component(
            @Nonnull Ref<EntityStore> targetRef,
            @Nonnull Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> type) {
        return type == null ? null : store.getComponent(targetRef, type);
    }

    public record Resolution(@Nonnull TamedDamageOwnerPolicy policy,
                             @Nullable String roleId) {
    }
}
