package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderVisualServiceArchitectureTest {
    private static final Path SERVICE = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightRiderVisualService.java"
    );
    private static final Path ACTIVATOR = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightActivator.java"
    );

    @Test
    void riderVisualServiceCreatesNonSerializedMountedModelEntity() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("store.putComponent(ownerRef, visualType, marker(ownerUuid, null, false))"));
        assertTrue(source.contains("new NonSerialized()"));
        assertTrue(source.contains("new NetworkId("));
        assertTrue(source.contains("new ModelComponent(new Model(savedModel))"));
        assertTrue(source.contains("new MountedComponent(ownerRef"));
        assertTrue(source.contains("MountController.BlockMount"));
        assertTrue(source.contains("AddReason.SPAWN"));
        assertTrue(source.contains("RemoveReason.REMOVE"));
        assertFalse(source.contains("PlayerRef.getComponent(Player"));
    }

    @Test
    void activatorStartsAndStopsRiderVisualsAroundModelSwap() throws Exception {
        String source = Files.readString(ACTIVATOR, StandardCharsets.UTF_8);

        assertTrue(source.contains("riderVisualService.spawn("));
        assertTrue(source.contains("riderVisualService.remove("));
        assertTrue(source.indexOf("modelService.apply(") < source.indexOf("riderVisualService.spawn("));
        assertTrue(source.indexOf("riderVisualService.remove(") < source.indexOf("modelService.restore("));
    }
}
