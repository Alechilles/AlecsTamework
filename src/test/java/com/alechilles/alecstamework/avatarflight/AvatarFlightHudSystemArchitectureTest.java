package com.alechilles.alecstamework.avatarflight;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AvatarFlightHudSystemArchitectureTest {
    @Test
    void hudWrapperUsesKeyedCustomHudApis() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/TameworkAvatarFlightHud.java"
        ));

        Assertions.assertTrue(source.contains("extends CustomUIHud"));
        Assertions.assertTrue(source.contains("HUD_KEY = \"alecstamework:avatar_flight\""));
        Assertions.assertTrue(source.contains("UI_PATH = \"TameworkAvatarFlightHud.ui\""));
        Assertions.assertTrue(source.contains(
                "CONTROLS_UI_PATH = \"Hud/TameworkAvatarFlightControls.ui\""
        ));
        Assertions.assertTrue(source.contains("HUD_Z_ORDER = 1"));
        Assertions.assertTrue(source.contains("super(playerRef, HUD_KEY, HUD_Z_ORDER)"));
        Assertions.assertTrue(source.contains("new UICommandBuilder()"));
        Assertions.assertTrue(source.contains("AvatarFlightHudBinder.bind(commandBuilder, updatedModel)"));
        Assertions.assertTrue(source.contains("update(false, commandBuilder)"));
        Assertions.assertTrue(source.contains("commandBuilder.append(CONTROLS_UI_PATH)"));
        Assertions.assertTrue(source.contains("removeCustomHud(playerRef, TameworkAvatarFlightHud.HUD_KEY)"));
        Assertions.assertFalse(source.contains("TameworkAvatarFlightControlOverlay"));
    }

    @Test
    void hudSystemBuildsModelFromFlightAndUsesCurrentStorePlayer() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystem.java"
        ));

        Assertions.assertTrue(source.contains("extends EntityTickingSystem<EntityStore>"));
        Assertions.assertTrue(source.contains("Query.and(flightType, inputType, playerType)"));
        Assertions.assertTrue(source.contains("archetypeChunk.getComponent(index, playerType)"));
        Assertions.assertTrue(source.contains("archetypeChunk.getComponent(index, inputType)"));
        Assertions.assertTrue(source.contains("AvatarFlightSpeedMetrics.horizontalSpeed"));
        Assertions.assertTrue(source.contains("AvatarFlightSpeedMetrics.speedRatio"));
        Assertions.assertTrue(source.contains("flight.getVelocityX()"));
        Assertions.assertTrue(source.contains("flight.getVigourCharges()"));
        Assertions.assertTrue(source.contains("flight.getHudTargetSpeedRatio()"));
        Assertions.assertTrue(source.contains("flight.getHudPitchRadians()"));
        Assertions.assertTrue(source.contains("config.getVigour().getMaxCharges()"));
        Assertions.assertTrue(source.contains("flight.getVigourRechargeMode()"));
        Assertions.assertTrue(source.contains("input.isLaunchCharging()"));
        Assertions.assertTrue(source.contains("input.isOnGround()"));
        Assertions.assertTrue(source.contains("input.getLaunchChargeStartedAtMs()"));
        Assertions.assertTrue(source.contains("config.getLaunch().getMinChargeMs()"));
        Assertions.assertTrue(source.contains("config.getLaunch().getMaxChargeMs()"));
        Assertions.assertTrue(source.contains("launchChargeVisible"));
        Assertions.assertTrue(source.contains("launchChargeRatio"));
        Assertions.assertTrue(source.contains("launchMinChargeRatio"));
        Assertions.assertTrue(source.contains("config.getVigour().isHudEnabled()"));
        Assertions.assertTrue(source.contains("config.getVigour().getHudResendIntervalMs()"));
        Assertions.assertTrue(source.contains("player.getHudManager().addCustomHud(playerRef, hud)"));
        Assertions.assertFalse(source.contains("TameworkAvatarFlightControlOverlay"));
        Assertions.assertTrue(source.contains("TameworkAvatarFlightHud.removeFrom(player)"));
        Assertions.assertTrue(source.contains("previous.playerRef() == playerRef"));
        Assertions.assertTrue(source.contains("hud.refresh(model)"));
        Assertions.assertTrue(source.contains("!previousModel.equals(model)"));
        Assertions.assertTrue(source.contains("now - lastSentAtMs >= Math.max(1L, resendIntervalMs)"));
        Assertions.assertFalse(source.contains("Universe.get()"));
        Assertions.assertFalse(source.contains("PlayerRef.getComponent(Player"));
    }

    @Test
    void flightControlsDoNotUseMapOnlyServerContentAnchor() {
        Path overlay = Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/TameworkAvatarFlightControlOverlay.java"
        );

        Assertions.assertFalse(Files.exists(overlay));
    }

    @Test
    void tameworkRegistersHudSystemAfterMovementAndBeforeVisualCleanup() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/Tamework.java"));

        int importIndex = source.indexOf("import com.alechilles.alecstamework.avatarflight.AvatarFlightHudSystem;");
        int movementIndex = source.indexOf("new AvatarFlightMovementSystem(");
        int hudIndex = source.indexOf("new AvatarFlightHudSystem(");
        int visualIndex = source.indexOf("new AvatarFlightEquipmentVisualSystem(");
        int playerTypeIndex = source.indexOf("Player.getComponentType()", hudIndex);
        int inputTypeIndex = source.indexOf("avatarFlightInputComponentType", hudIndex);

        Assertions.assertTrue(importIndex >= 0);
        Assertions.assertTrue(movementIndex >= 0);
        Assertions.assertTrue(hudIndex > movementIndex);
        Assertions.assertTrue(visualIndex > hudIndex);
        Assertions.assertTrue(playerTypeIndex > hudIndex);
        Assertions.assertTrue(inputTypeIndex > hudIndex);
    }

    @Test
    void activatorDisableHidesReusableHudAfterFlightComponentRemoval() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightActivator.java"
        ));

        int disableIndex = source.indexOf("public Result disable");
        int playerLookup = source.indexOf("store.getComponent(ref, Player.getComponentType())", disableIndex);
        int removeFlight = source.indexOf("store.tryRemoveComponent(ref, flightType)", disableIndex);
        int hideHud = source.indexOf("AvatarFlightHudSystem.hideHud(playerUuid, player)", disableIndex);

        Assertions.assertTrue(playerLookup > disableIndex);
        Assertions.assertTrue(removeFlight > playerLookup);
        Assertions.assertTrue(hideHud > removeFlight);
        Assertions.assertTrue(source.contains("AvatarFlightHudSystem.forgetHud(playerUuid)"));
    }
}
