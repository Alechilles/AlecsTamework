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
        Assertions.assertTrue(source.contains("super(playerRef, HUD_KEY)"));
        Assertions.assertTrue(source.contains("new UICommandBuilder()"));
        Assertions.assertTrue(source.contains("AvatarFlightHudBinder.bind(commandBuilder, updatedModel)"));
        Assertions.assertTrue(source.contains("update(false, commandBuilder)"));
        Assertions.assertTrue(source.contains("removeCustomHud(player.getPlayerRef(), TameworkAvatarFlightHud.HUD_KEY)"));
    }

    @Test
    void hudSystemBuildsModelFromFlightAndUsesCurrentStorePlayer() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystem.java"
        ));

        Assertions.assertTrue(source.contains("extends EntityTickingSystem<EntityStore>"));
        Assertions.assertTrue(source.contains("Query.and(flightType, playerType)"));
        Assertions.assertTrue(source.contains("archetypeChunk.getComponent(index, playerType)"));
        Assertions.assertTrue(source.contains("AvatarFlightSpeedMetrics.horizontalSpeed"));
        Assertions.assertTrue(source.contains("AvatarFlightSpeedMetrics.speedRatio"));
        Assertions.assertTrue(source.contains("flight.getVelocityX()"));
        Assertions.assertTrue(source.contains("flight.getVigourCharges()"));
        Assertions.assertTrue(source.contains("flight.getHudTargetSpeedRatio()"));
        Assertions.assertTrue(source.contains("flight.getHudPitchRadians()"));
        Assertions.assertTrue(source.contains("config.getVigour().getMaxCharges()"));
        Assertions.assertTrue(source.contains("flight.getVigourRechargeMode()"));
        Assertions.assertTrue(source.contains("config.getVigour().isHudEnabled()"));
        Assertions.assertTrue(source.contains("config.getVigour().getHudResendIntervalMs()"));
        Assertions.assertTrue(source.contains("player.getHudManager().addCustomHud(playerRef, hud)"));
        Assertions.assertTrue(source.contains("TameworkAvatarFlightHud.removeFrom(player)"));
        Assertions.assertFalse(source.contains("Universe.get()"));
        Assertions.assertFalse(source.contains("PlayerRef.getComponent(Player"));
    }

    @Test
    void tameworkRegistersHudSystemAfterMovementAndBeforeVisualCleanup() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/Tamework.java"));

        int importIndex = source.indexOf("import com.alechilles.alecstamework.avatarflight.AvatarFlightHudSystem;");
        int movementIndex = source.indexOf("new AvatarFlightMovementSystem(");
        int hudIndex = source.indexOf("new AvatarFlightHudSystem(");
        int visualIndex = source.indexOf("new AvatarFlightEquipmentVisualSystem(");
        int playerTypeIndex = source.indexOf("Player.getComponentType()", hudIndex);

        Assertions.assertTrue(importIndex >= 0);
        Assertions.assertTrue(movementIndex >= 0);
        Assertions.assertTrue(hudIndex > movementIndex);
        Assertions.assertTrue(visualIndex > hudIndex);
        Assertions.assertTrue(playerTypeIndex > hudIndex);
    }

    @Test
    void activatorDisableRemovesKeyedHudAfterFlightComponentRemoval() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightActivator.java"
        ));

        int disableIndex = source.indexOf("public Result disable");
        int playerLookup = source.indexOf("store.getComponent(ref, Player.getComponentType())", disableIndex);
        int removeFlight = source.indexOf("store.tryRemoveComponent(ref, flightType)", disableIndex);
        int removeHud = source.indexOf("TameworkAvatarFlightHud.removeFrom(player)", disableIndex);

        Assertions.assertTrue(playerLookup > disableIndex);
        Assertions.assertTrue(removeFlight > playerLookup);
        Assertions.assertTrue(removeHud > removeFlight);
    }
}
