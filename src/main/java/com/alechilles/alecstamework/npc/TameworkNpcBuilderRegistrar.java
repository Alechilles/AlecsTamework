package com.alechilles.alecstamework.npc;

import java.util.logging.Level;

import com.alechilles.alecstamework.lifecycle.TameworkEventRegistrationSupport;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkCaptureOwner;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkCaptureStranger;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkCaptureWild;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkDebugCombatSnapshot;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkDebugMessage;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkDenyCaptureUntamed;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkDenyInteract;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkHarvestAlarm;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkHarvestDrop;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkInteract;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkInteractPrompt;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkNeedsResourceConsume;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkNeedsResourceMovementDiagnostic;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkNeedsResourceRejectTarget;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkNeedsResourceReleaseTarget;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkRejectPositionTarget;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkSetFlyingCompanionMode;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkSetOwner;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkSetTamed;
import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkAttackedTargetSlotRecently;
import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkAttitudeFromTargetSlot;
import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkIsOwner;
import com.alechilles.alecstamework.npc.movement.BuilderBodyMotionTameworkFlyingOrbit;
import com.alechilles.alecstamework.npc.movement.BuilderBodyMotionTameworkMountedGlide;
import com.alechilles.alecstamework.npc.movement.BuilderBodyMotionTameworkRide;
import com.alechilles.alecstamework.npc.movement.BuilderMotionControllerTameworkFly;
import com.alechilles.alecstamework.npc.movement.BuilderMotionControllerTameworkMountedGlide;
import com.alechilles.alecstamework.npc.movement.BuilderMotionControllerTameworkRideWalk;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkAlarm;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkEffectActive;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHasOwner;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHook;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkIsOwner;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkIsTamed;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkLifeStage;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedBelow;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedLowest;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceConsumeSucceeded;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceFastMode;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceMovementStalled;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceTarget;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkReachableBlockTarget;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.event.PluginSetupEvent;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderFactory;
import com.hypixel.hytale.server.npc.corecomponents.IEntityFilter;
import com.hypixel.hytale.server.npc.instructions.Action;
import com.hypixel.hytale.server.npc.instructions.BodyMotion;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;

/**
 * Registers Tamework NPC action/sensor/filter builders once NPCPlugin is ready.
 */
public final class TameworkNpcBuilderRegistrar {

    private final JavaPlugin plugin;
    private boolean npcActionsRegistered;

    public TameworkNpcBuilderRegistrar(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerNpcActionsIfReady() {
        if (npcActionsRegistered || plugin == null) {
            return;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin != null) {
            plugin.getLogger().at(Level.INFO).log("Tamework NPC builder registration: NPCPlugin detected on setup.");
            registerNpcActions(npcPlugin);
            return;
        }
        plugin.getLogger().at(Level.INFO).log("Tamework NPC builder registration: NPCPlugin not ready, waiting for PluginSetupEvent.");
        TameworkEventRegistrationSupport.registerGlobal(
                plugin,
                PluginSetupEvent.class,
                this::onPluginSetup,
                "NPC builder plugin setup listener"
        );
    }

    // Called when plugins are set up; used to detect NPCPlugin availability.
    private void onPluginSetup(PluginSetupEvent event) {
        if (npcActionsRegistered) {
            return;
        }
        if (event.getPlugin() instanceof NPCPlugin) {
            plugin.getLogger().at(Level.INFO).log("Tamework NPC builder registration: PluginSetupEvent received NPCPlugin.");
            registerNpcActions((NPCPlugin) event.getPlugin());
        }
    }

    // Register custom action/sensor/filter builders and trigger NPC validation.
    private void registerNpcActions(NPCPlugin npcPlugin) {
        if (npcActionsRegistered || npcPlugin == null) {
            return;
        }
        BuilderFactory<Action> actionFactory = npcPlugin.getBuilderManager().getFactory(Action.class);
        if (actionFactory == null) {
            plugin.getLogger().at(Level.WARNING).log("Tamework NPC builder registration: Action factory missing.");
        } else {
            plugin.getLogger().at(Level.INFO).log("Tamework NPC builder registration: Action factory ready.");
            actionFactory.add(BuilderActionTameworkCaptureOwner.BUILDER_ID, BuilderActionTameworkCaptureOwner::new);
            actionFactory.add(BuilderActionTameworkCaptureStranger.BUILDER_ID, BuilderActionTameworkCaptureStranger::new);
            actionFactory.add(BuilderActionTameworkCaptureWild.BUILDER_ID, BuilderActionTameworkCaptureWild::new);
            actionFactory.add(BuilderActionTameworkDebugCombatSnapshot.BUILDER_ID, BuilderActionTameworkDebugCombatSnapshot::new);
            actionFactory.add(BuilderActionTameworkDebugMessage.BUILDER_ID, BuilderActionTameworkDebugMessage::new);
            actionFactory.add(BuilderActionTameworkDenyInteract.BUILDER_ID, BuilderActionTameworkDenyInteract::new);
            actionFactory.add(BuilderActionTameworkDenyCaptureUntamed.BUILDER_ID, BuilderActionTameworkDenyCaptureUntamed::new);
            actionFactory.add(BuilderActionTameworkHarvestAlarm.BUILDER_ID, BuilderActionTameworkHarvestAlarm::new);
            actionFactory.add(BuilderActionTameworkHarvestDrop.BUILDER_ID, BuilderActionTameworkHarvestDrop::new);
            actionFactory.add(BuilderActionTameworkInteract.BUILDER_ID, BuilderActionTameworkInteract::new);
            actionFactory.add(BuilderActionTameworkInteractPrompt.BUILDER_ID, BuilderActionTameworkInteractPrompt::new);
            actionFactory.add(BuilderActionTameworkNeedsResourceConsume.BUILDER_ID, BuilderActionTameworkNeedsResourceConsume::new);
            actionFactory.add(
                    BuilderActionTameworkNeedsResourceMovementDiagnostic.BUILDER_ID,
                    BuilderActionTameworkNeedsResourceMovementDiagnostic::new
            );
            actionFactory.add(
                    BuilderActionTameworkNeedsResourceRejectTarget.BUILDER_ID,
                    BuilderActionTameworkNeedsResourceRejectTarget::new
            );
            actionFactory.add(
                    BuilderActionTameworkNeedsResourceReleaseTarget.BUILDER_ID,
                    BuilderActionTameworkNeedsResourceReleaseTarget::new
            );
            actionFactory.add(
                    BuilderActionTameworkRejectPositionTarget.BUILDER_ID,
                    BuilderActionTameworkRejectPositionTarget::new
            );
            actionFactory.add(
                    BuilderActionTameworkSetFlyingCompanionMode.BUILDER_ID,
                    BuilderActionTameworkSetFlyingCompanionMode::new
            );
            actionFactory.add(BuilderActionTameworkSetTamed.BUILDER_ID, BuilderActionTameworkSetTamed::new);
            actionFactory.add(BuilderActionTameworkSetOwner.BUILDER_ID, BuilderActionTameworkSetOwner::new);
        }

        BuilderFactory<Sensor> sensorFactory = npcPlugin.getBuilderManager().getFactory(Sensor.class);
        if (sensorFactory == null) {
            plugin.getLogger().at(Level.WARNING).log("Tamework NPC builder registration: Sensor factory missing.");
        } else {
            plugin.getLogger().at(Level.INFO).log("Tamework NPC builder registration: Sensor factory ready.");
            sensorFactory.add(BuilderSensorTameworkIsOwner.BUILDER_ID, BuilderSensorTameworkIsOwner::new);
            sensorFactory.add(BuilderSensorTameworkHasOwner.BUILDER_ID, BuilderSensorTameworkHasOwner::new);
            sensorFactory.add(BuilderSensorTameworkIsTamed.BUILDER_ID, BuilderSensorTameworkIsTamed::new);
            sensorFactory.add(BuilderSensorTameworkLifeStage.BUILDER_ID, BuilderSensorTameworkLifeStage::new);
            sensorFactory.add(BuilderSensorTameworkAlarm.BUILDER_ID, BuilderSensorTameworkAlarm::new);
            sensorFactory.add(BuilderSensorTameworkHook.BUILDER_ID, BuilderSensorTameworkHook::new);
            sensorFactory.add(BuilderSensorTameworkEffectActive.BUILDER_ID, BuilderSensorTameworkEffectActive::new);
            sensorFactory.add(BuilderSensorTameworkNeedBelow.BUILDER_ID, BuilderSensorTameworkNeedBelow::new);
            sensorFactory.add(BuilderSensorTameworkNeedLowest.BUILDER_ID, BuilderSensorTameworkNeedLowest::new);
            sensorFactory.add(
                    BuilderSensorTameworkNeedsResourceConsumeSucceeded.BUILDER_ID,
                    BuilderSensorTameworkNeedsResourceConsumeSucceeded::new
            );
            sensorFactory.add(
                    BuilderSensorTameworkNeedsResourceFastMode.BUILDER_ID,
                    BuilderSensorTameworkNeedsResourceFastMode::new
            );
            sensorFactory.add(
                    BuilderSensorTameworkNeedsResourceMovementStalled.BUILDER_ID,
                    BuilderSensorTameworkNeedsResourceMovementStalled::new
            );
            sensorFactory.add(BuilderSensorTameworkNeedsResourceTarget.BUILDER_ID, BuilderSensorTameworkNeedsResourceTarget::new);
            sensorFactory.add(
                    BuilderSensorTameworkReachableBlockTarget.BUILDER_ID,
                    BuilderSensorTameworkReachableBlockTarget::new
            );
        }

        BuilderFactory<IEntityFilter> filterFactory = npcPlugin.getBuilderManager().getFactory(IEntityFilter.class);
        if (filterFactory == null) {
            plugin.getLogger().at(Level.WARNING).log("Tamework NPC builder registration: Entity filter factory missing.");
        } else {
            plugin.getLogger().at(Level.INFO).log("Tamework NPC builder registration: Entity filter factory ready.");
            filterFactory.add(
                    BuilderEntityFilterTameworkAttitudeFromTargetSlot.BUILDER_ID,
                    BuilderEntityFilterTameworkAttitudeFromTargetSlot::new
            );
            filterFactory.add(
                    BuilderEntityFilterTameworkAttackedTargetSlotRecently.BUILDER_ID,
                    BuilderEntityFilterTameworkAttackedTargetSlotRecently::new
            );
            filterFactory.add(BuilderEntityFilterTameworkIsOwner.BUILDER_ID, BuilderEntityFilterTameworkIsOwner::new);
        }

        BuilderFactory<BodyMotion> bodyMotionFactory = npcPlugin.getBuilderManager().getFactory(BodyMotion.class);
        if (bodyMotionFactory == null) {
            plugin.getLogger().at(Level.WARNING).log("Tamework NPC builder registration: Body motion factory missing.");
        } else {
            plugin.getLogger().at(Level.INFO).log("Tamework NPC builder registration: Body motion factory ready.");
            bodyMotionFactory.add(
                    BuilderBodyMotionTameworkFlyingOrbit.BUILDER_ID,
                    BuilderBodyMotionTameworkFlyingOrbit::new
            );
            bodyMotionFactory.add(
                    BuilderBodyMotionTameworkMountedGlide.BUILDER_ID,
                    BuilderBodyMotionTameworkMountedGlide::new
            );
            bodyMotionFactory.add(BuilderBodyMotionTameworkRide.BUILDER_ID, BuilderBodyMotionTameworkRide::new);
        }

        BuilderFactory<MotionController> motionControllerFactory =
                npcPlugin.getBuilderManager().getFactory(MotionController.class);
        if (motionControllerFactory == null) {
            plugin.getLogger().at(Level.WARNING).log("Tamework NPC builder registration: Motion controller factory missing.");
        } else {
            plugin.getLogger().at(Level.INFO).log("Tamework NPC builder registration: Motion controller factory ready.");
            motionControllerFactory.add(
                    BuilderMotionControllerTameworkFly.BUILDER_ID,
                    BuilderMotionControllerTameworkFly::new
            );
            motionControllerFactory.add(
                    BuilderMotionControllerTameworkMountedGlide.BUILDER_ID,
                    BuilderMotionControllerTameworkMountedGlide::new
            );
            motionControllerFactory.add(
                    BuilderMotionControllerTameworkRideWalk.BUILDER_ID,
                    BuilderMotionControllerTameworkRideWalk::new
            );
        }

        npcActionsRegistered = true;
        plugin.getLogger().at(Level.INFO).log("Registered Tamework NPC capture actions.");
        // Avoid forcing early validation before mod asset replacement has completed.
        // The normal NPC asset build/validation pass will run after pack loading.
        plugin.getLogger().at(Level.INFO)
                .log("Skipped early NPC revalidation after registering Tamework builders.");
    }
}
