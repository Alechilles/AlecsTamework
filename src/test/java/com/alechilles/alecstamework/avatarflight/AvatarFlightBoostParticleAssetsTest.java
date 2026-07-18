package com.alechilles.alecstamework.avatarflight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.validation.ValidationResults;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightBoostParticleAssetsTest {
    private static final Path BOOST_ROOT = Path.of(
            "src/main/resources/Server/Particles/Tamework/AvatarFlight/Boost"
    );
    private static final Set<String> EXPECTED_SYSTEM_IDS = Set.of(
            "Tamework_AvatarFlight_Forward_Boost",
            "Tamework_AvatarFlight_Upward_Boost"
    );
    private static final Set<String> EXPECTED_SPAWNER_IDS = Set.of(
            "TwForwardBoostCompression",
            "TwForwardBoostLances",
            "TwUpwardBoostDownwash",
            "TwUpwardBoostLiftRibbons"
    );
    private static final Map<String, String> EXPECTED_TEXTURES = Map.of(
            "TwForwardBoostCompression",
            "Particles/Textures/Tamework/AvatarFlight/Boost/Forward_Compression_Arc.png",
            "TwForwardBoostLances",
            "Particles/Textures/Tamework/AvatarFlight/Boost/Forward_Wind_Lance.png",
            "TwUpwardBoostDownwash",
            "Particles/Textures/Tamework/AvatarFlight/Boost/Upward_Downwash_Fan.png",
            "TwUpwardBoostLiftRibbons",
            "Particles/Textures/Tamework/AvatarFlight/Boost/Upward_Lift_Ribbon.png"
    );

    @Test
    void boostSystemsReferenceOnlyAvailableBoundedSpawners() throws IOException {
        Set<String> systemIds = fileStems(BOOST_ROOT, ".particlesystem");
        Set<String> spawnerIds = fileStems(BOOST_ROOT.resolve("Spawners"), ".particlespawner");
        assertEquals(EXPECTED_SYSTEM_IDS, systemIds);
        assertEquals(EXPECTED_SPAWNER_IDS, spawnerIds);

        for (String systemId : systemIds) {
            JsonObject system = system(systemId);
            assertFalse(system.get("IsImportant").getAsBoolean());
            assertTrue(system.get("CullDistance").getAsDouble() <= 75.0);
            double lifeSpan = system.get("LifeSpan").getAsDouble();
            for (var element : system.getAsJsonArray("Spawners")) {
                JsonObject group = element.getAsJsonObject();
                String spawnerId = group.get("SpawnerId").getAsString();
                assertTrue(spawnerIds.contains(spawnerId), systemId + " references missing " + spawnerId);
                double delay = group.has("StartDelay") ? group.get("StartDelay").getAsDouble() : 0.0;
                double particleLife = spawner(spawnerId).getAsJsonObject("ParticleLifeSpan")
                        .get("Max").getAsDouble();
                assertTrue(delay + particleLife <= lifeSpan,
                        systemId + " ends before " + spawnerId + " can finish");
            }
        }
        assertEquals(18, compositionConcurrency("Tamework_AvatarFlight_Forward_Boost"));
        assertEquals(26, compositionConcurrency("Tamework_AvatarFlight_Upward_Boost"));
    }

    @Test
    void boostSpawnersUseGeneratedSpritesWithoutFullbrightOrAdditiveRendering() throws IOException {
        for (String spawnerId : EXPECTED_SPAWNER_IDS) {
            JsonObject spawner = spawner(spawnerId);
            assertEquals(EXPECTED_TEXTURES.get(spawnerId),
                    spawner.getAsJsonObject("Particle").get("Texture").getAsString());
            assertEquals("BlendLinear", spawner.get("RenderMode").getAsString());
            assertTrue(spawner.get("LightInfluence").getAsDouble() >= 1,
                    spawnerId + " must receive scene lighting");
            int concurrent = spawner.get("MaxConcurrentParticles").getAsInt();
            assertTrue(concurrent <= 8, spawnerId + " exceeds its concurrency budget");
            assertTrue(spawner.getAsJsonObject("TotalParticles").get("Max").getAsInt() <= concurrent);
            assertTrue(maxOpacity(spawner) <= 0.4, spawnerId + " is too opaque for a wind sheet");
        }
    }

    @Test
    void boostMotionMatchesForwardAndUpwardSilhouettes() throws IOException {
        JsonObject lances = spawner("TwForwardBoostLances");
        assertEquals("BillboardVelocity", lances.get("ParticleRotationInfluence").getAsString());
        assertTrue(lances.getAsJsonObject("InitialVelocity").getAsJsonObject("Yaw")
                .get("Max").getAsDouble() < 0.0, "forward lances must trail behind the boost origin");

        JsonObject downwash = spawner("TwUpwardBoostDownwash");
        assertTrue(downwash.getAsJsonObject("InitialVelocity").getAsJsonObject("Pitch")
                .get("Max").getAsDouble() < 0.0, "downwash must move downward");

        JsonObject lift = spawner("TwUpwardBoostLiftRibbons");
        assertTrue(lift.getAsJsonObject("InitialVelocity").getAsJsonObject("Pitch")
                .get("Min").getAsDouble() > 0.0, "lift ribbons must rise");
        JsonObject attractor = lift.getAsJsonArray("Attractors").get(0).getAsJsonObject();
        assertTrue(attractor.get("RadialAcceleration").getAsDouble() < 0.0);
        assertTrue(Math.abs(attractor.get("RadialTangentAcceleration").getAsDouble()) > 0.0);
    }

    @Test
    void boostSpawnersDecodeToPopulatedHytalePackets() throws IOException {
        for (String spawnerId : EXPECTED_SPAWNER_IDS) {
            Path path = BOOST_ROOT.resolve("Spawners").resolve(spawnerId + ".particlespawner");
            ExtraInfo extraInfo = new ExtraInfo(ExtraInfo.UNSET_VERSION, ValidationResults::new);
            ParticleSpawner decoded = ParticleSpawner.CODEC.decode(
                    BsonDocument.parse(Files.readString(path, StandardCharsets.UTF_8)),
                    extraInfo
            );
            com.hypixel.hytale.protocol.ParticleSpawner packet = decoded.toPacket();

            assertNotNull(packet.particle, spawnerId + " has no particle packet");
            assertNotNull(packet.particle.texturePath, spawnerId + " has no texture path");
            assertFalse(packet.particle.animationFrames.isEmpty(), spawnerId + " has no animation frames");
            assertNotNull(packet.particleLifeSpan, spawnerId + " has no lifespan");
            assertNotNull(packet.totalParticles, spawnerId + " has no total-particle range");
            assertTrue(packet.maxConcurrentParticles > 0, spawnerId + " cannot allocate particles");
        }
    }

    private static int compositionConcurrency(String systemId) throws IOException {
        int particles = 0;
        for (var element : system(systemId).getAsJsonArray("Spawners")) {
            String spawnerId = element.getAsJsonObject().get("SpawnerId").getAsString();
            particles += spawner(spawnerId).get("MaxConcurrentParticles").getAsInt();
        }
        return particles;
    }

    private static double maxOpacity(JsonObject spawner) {
        JsonObject particle = spawner.getAsJsonObject("Particle");
        double maximum = particle.getAsJsonObject("Animation").entrySet().stream()
                .map(Map.Entry::getValue)
                .map(value -> value.getAsJsonObject())
                .filter(frame -> frame.has("Opacity"))
                .mapToDouble(frame -> frame.get("Opacity").getAsDouble())
                .max()
                .orElse(0.0);
        JsonObject initial = particle.getAsJsonObject("InitialAnimationFrame");
        return initial.has("Opacity") ? Math.max(maximum, initial.get("Opacity").getAsDouble()) : maximum;
    }

    private static JsonObject system(String systemId) throws IOException {
        return read(BOOST_ROOT.resolve(systemId + ".particlesystem"));
    }

    private static JsonObject spawner(String spawnerId) throws IOException {
        return read(BOOST_ROOT.resolve("Spawners").resolve(spawnerId + ".particlespawner"));
    }

    private static Set<String> fileStems(Path directory, String suffix) throws IOException {
        Set<String> stems = new HashSet<>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(suffix))
                    .map(name -> name.substring(0, name.length() - suffix.length()))
                    .forEach(stems::add);
        }
        return stems;
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
