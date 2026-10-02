package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountPhase;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountSessionComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionExpiryWarningSchedule;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionNames;
import com.alechilles.alecstamework.companion.bonded.BondedSummonEffects;
import com.alechilles.alecstamework.companion.bonded.BondedTalentUpdates;
import com.alechilles.alecstamework.companion.flow.CompanionBodies;
import com.alechilles.alecstamework.companion.flow.HytaleCaptureDelivery;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.damage.ExpiryDismountFallProtectionService;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What the bonded companion code does to a loaded body: play an effect, protect its rider at
 * expiry, and change its talents. Every method may be called from any thread. It reads only the
 * loaded-body registry on the caller's thread and runs the entity work on the body's own world
 * thread (at once when the caller is already on it), where the ref is checked again. The expiry
 * notice goes to the owner instead, on the owner's current world thread.
 */
public final class HytaleBondedBodies implements BondedSummonEffects.Bodies, BondedTalentUpdates.LiveBody {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String EXPIRES_IN_KEY = "tamework.ui.notifications.bonded.expiresIn";

    private final Function<UUID, Ref<EntityStore>> loaded;
    private final TameworkUiMessageService notifications = new TameworkUiMessageService();

    /** @param loaded a profile's loaded body, or null; {@code LoadedBodies::get} */
    public HytaleBondedBodies(@Nonnull Function<UUID, Ref<EntityStore>> loaded) {
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Override
    public void playEffect(@Nonnull CompanionRecord record, @Nonnull String effectId, long keepUntilMs) {
        UUID ownerUuid = record.ownerUuid();
        onBody(record.profileId(), (world, body) -> {
            Store<EntityStore> store = body.getStore();
            Ref<EntityStore> target = effectTarget(world, store, body, ownerUuid);
            if (keepUntilMs == 0L) {
                TameworkEntityEffectService.applyEffect(target, effectId, store);
                return;
            }
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectId);
            EffectControllerComponent controller =
                    store.getComponent(target, EffectControllerComponent.getComponentType());
            if (effect != null && controller != null) {
                // The effect must outlast the session, or it ends before the body is removed.
                controller.addEffect(target, effect, BondedCompanionExpiryWarningSchedule.effectDurationSeconds(
                        keepUntilMs, System.currentTimeMillis(), effect.getDuration()), OverlapBehavior.OVERWRITE, store);
            }
        });
    }

    /** The owner is resolved, and the text translated, on the owner's current world thread. */
    @Override
    public void notifyExpiry(@Nonnull CompanionRecord record,
                             @Nonnull BondedCompanionExpiryWarningSchedule.Warning warning) {
        HytaleCaptureDelivery.onPlayerWorld(record.ownerUuid(), (world, store, ref, player) -> {
            String language = player.getPlayerRef() == null ? null : player.getPlayerRef().getLanguage();
            notifications.showKey(player, warning.style(), EXPIRES_IN_KEY,
                    BondedCompanionNames.displayName(record, language), warning.secondsRemaining());
        }, null);
    }

    @Override
    public void armExpiryDismount(@Nonnull CompanionRecord record) {
        onBody(record.profileId(), (world, body) -> {
            Store<EntityStore> store = body.getStore();
            UUID riderUuid = riderUuid(store, body);
            Ref<EntityStore> riderRef = riderUuid == null ? null : world.getEntityRef(riderUuid);
            if (riderRef == null || !riderRef.isValid() || riderRef.getStore() != store
                    || store.getComponent(riderRef, Player.getComponentType()) == null) {
                return;
            }
            if (ExpiryDismountFallProtectionService.getInstance().arm(riderUuid, System.currentTimeMillis())) {
                TameworkEntityEffectService.applyEffect(riderRef, ExpiryDismountFallProtectionService.EFFECT_ID, store);
            }
        });
    }

    /**
     * Buys or resets talents on the loaded body with the checks every live companion gets
     * ({@link CompanionTalentService}), which also re-apply its stat modifiers.
     */
    @Override
    @Nullable
    public CompletableFuture<BondedTalentUpdates.Outcome> update(@Nonnull CompanionRecord record,
                                                                 @Nonnull BondedCompanionTalentActionRequest request) {
        CompletableFuture<BondedTalentUpdates.Outcome> outcome = new CompletableFuture<>();
        boolean started = onBody(record.profileId(), (world, body) -> {
            try {
                outcome.complete(updateTalents(body, request));
            } finally {
                // A throw inside the change must not leave the caller waiting.
                outcome.complete(BondedTalentUpdates.Outcome.of(BondedTalentUpdates.Status.FAILED));
            }
        }, () -> outcome.complete(BondedTalentUpdates.Outcome.of(BondedTalentUpdates.Status.BODY_UNAVAILABLE)));
        return started ? outcome : null;
    }

    private static BondedTalentUpdates.Outcome updateTalents(Ref<EntityStore> body,
                                                             BondedCompanionTalentActionRequest request) {
        Store<EntityStore> store = body.getStore();
        TameworkTalentsComponent talents;
        if (request.action() == BondedCompanionTalentActionRequest.Action.PURCHASE) {
            CompanionTalentService.PurchaseResult result =
                    CompanionTalentService.purchaseTalent(body, store, request.talentId());
            talents = result.applied() ? result.component() : null;
        } else {
            CompanionTalentService.ResetResult result = CompanionTalentService.resetTalents(body, store);
            talents = result.applied() ? result.component() : null;
        }
        if (talents == null) {
            return BondedTalentUpdates.Outcome.of(BondedTalentUpdates.Status.REJECTED);
        }
        CompanionLevelingService.LevelingSnapshot leveling = CompanionLevelingService.resolveSnapshot(
                body, store, CompanionRoleIdResolver.resolveRoleId(body, store));
        return new BondedTalentUpdates.Outcome(BondedTalentUpdates.Status.APPLIED, talents.clone(),
                leveling == null ? 1 : leveling.level(), leveling == null ? null : leveling.configId());
    }

    private boolean onBody(UUID profileId, BiConsumer<World, Ref<EntityStore>> action) {
        return onBody(profileId, action, () -> { });
    }

    /**
     * Runs {@code action} on the world thread of the profile's loaded body. Returns false, running
     * nothing, when no body is loaded or its world takes no tasks. {@code gone} runs instead of
     * {@code action} when the body left its store before the task ran.
     */
    private boolean onBody(UUID profileId, BiConsumer<World, Ref<EntityStore>> action, Runnable gone) {
        Ref<EntityStore> body = loaded.apply(profileId);
        World world = body == null ? null : CompanionBodies.worldOf(body);
        if (world == null || !world.isAlive()) {
            return false;
        }
        Runnable task = () -> {
            if (!body.isValid()) {
                gone.run();
                return;
            }
            try {
                action.accept(world, body);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.at(Level.WARNING).withCause(failure)
                        .log("A bonded companion body action failed for profile %s", profileId);
            }
        };
        if (world.isInThread()) {
            task.run();
            return true;
        }
        try {
            world.execute(task);
            return true;
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            return false;
        }
    }

    /**
     * The entity that shows the companion: the owner while the owner flies as the companion
     * (avatar flight hides the companion and puts its model on the player), else the body.
     */
    private static Ref<EntityStore> effectTarget(World world, Store<EntityStore> store, Ref<EntityStore> body,
                                                 @Nullable UUID ownerUuid) {
        ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType =
                AvatarFlightMountSessionComponent.getComponentType();
        Ref<EntityStore> ownerRef = ownerUuid == null || sessionType == null ? null : world.getEntityRef(ownerUuid);
        if (ownerRef == null || !ownerRef.isValid() || ownerRef.getStore() != store) {
            return body;
        }
        AvatarFlightMountSessionComponent session = store.getComponent(ownerRef, sessionType);
        NPCEntity npc = store.getComponent(body, NPCEntity.getComponentType());
        return session != null && npc != null && npc.getUuid() != null
                && session.getPhase() == AvatarFlightMountPhase.ACTIVE
                && npc.getUuid().toString().equals(session.getSourceNpcUuid()) ? ownerRef : body;
    }

    /** The player riding the body through any of the three mount kinds, or null. */
    @Nullable
    private static UUID riderUuid(Store<EntityStore> store, Ref<EntityStore> body) {
        ComponentType<EntityStore, TameworkRideMountComponent> rideType = TameworkRideMountComponent.getComponentType();
        TameworkRideMountComponent ride = rideType == null ? null : store.getComponent(body, rideType);
        ComponentType<EntityStore, TameworkMountedGlideComponent> glideType =
                TameworkMountedGlideComponent.getComponentType();
        TameworkMountedGlideComponent glide = glideType == null ? null : store.getComponent(body, glideType);
        ComponentType<EntityStore, AvatarFlightSourceComponent> flightType =
                AvatarFlightSourceComponent.getComponentType();
        AvatarFlightSourceComponent flight = flightType == null ? null : store.getComponent(body, flightType);
        return ExpiryDismountRiderUuidResolver.resolve(ride == null ? null : ride.getRiderUuid(),
                glide == null ? null : glide.getRiderUuid(), flight == null ? null : flight.getRiderUuid());
    }
}
