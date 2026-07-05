package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderVisualConfigArchitectureTest {
    private static final Path CONFIG = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "config", "assets", "TwAvatarFlightConfig.java"
    );
    private static final Path DEFAULT_JSON = Path.of(
            "src", "main", "resources", "Server", "Tamework", "AvatarFlight",
            "Tamework_Avatar_Flight_Default.json"
    );

    @Test
    void riderVisualConfigIsCodecBackedAndInheritanceAware() throws Exception {
        String source = Files.readString(CONFIG, StandardCharsets.UTF_8);

        assertTrue(source.contains("BuilderCodec<RiderVisualSettings> RIDER_VISUAL_CODEC"));
        assertTrue(source.contains("new KeyedCodec<>(\"RiderVisual\", RIDER_VISUAL_CODEC)"));
        assertTrue(source.contains("inheritOrCopyRiderVisual("));
        assertTrue(source.contains("if (!keys.contains(\"ShowRider\"))"));
        assertTrue(source.contains("public RiderVisualSettings getRiderVisual()"));
    }

    @Test
    void defaultAvatarFlightConfigDeclaresRiderVisualDefaults() throws Exception {
        String json = Files.readString(DEFAULT_JSON, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"RiderVisual\""));
        assertTrue(json.contains("\"HideOwnerEquipment\": true"));
        assertTrue(json.contains("\"ShowRider\": true"));
        assertTrue(json.contains("\"SeatOffsetX\": 0.0"));
        assertTrue(json.contains("\"SeatOffsetY\": 1.35"));
        assertTrue(json.contains("\"SeatOffsetZ\": -0.25"));
        assertTrue(json.contains("\"EquipmentResendIntervalMs\": 250.0"));
    }
}
