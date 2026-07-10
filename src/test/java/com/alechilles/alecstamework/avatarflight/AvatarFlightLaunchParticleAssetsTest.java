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
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightLaunchParticleAssetsTest {
    private static final Path LAUNCH_ROOT = Path.of(
            "src/main/resources/Server/Particles/Tamework/AvatarFlight/Launch"
    );
    private static final Set<String> APPROVED_TEXTURES = Set.of(
            "Particles/Textures/Basic/Ring2.png",
            "Particles/Textures/Circles/Portal_Wind.png",
            "Particles/Textures/Smoke/Smoke_Mist.png",
            "Particles/Textures/Smoke/Smoke_Smooth2.png"
    );
    private static final String BASE_VISIBILITY_SPAWNER = "Impact_Ice_Shockwave";

    @Test
    void allLaunchSystemsReferenceAvailableSpawnersAndBaseVisibilityLayer() throws IOException {
        Set<String> spawnerIds = fileStems(LAUNCH_ROOT.resolve("Spawners"), ".particlespawner");
        Set<String> systemIds = fileStems(LAUNCH_ROOT, ".particlesystem");

        assertEquals(8, spawnerIds.size());
        assertEquals(5, systemIds.size());
        for (String systemId : systemIds) {
            JsonObject system = read(LAUNCH_ROOT.resolve(systemId + ".particlesystem"));
            assertTrue(system.get("IsImportant").getAsBoolean());
            assertTrue(system.get("CullDistance").getAsDouble() <= 75.0);
            boolean hasBaseVisibilitySpawner = false;
            for (var element : system.getAsJsonArray("Spawners")) {
                JsonObject group = element.getAsJsonObject();
                String spawnerId = group.get("SpawnerId").getAsString();
                assertTrue(spawnerIds.contains(spawnerId) || BASE_VISIBILITY_SPAWNER.equals(spawnerId),
                        systemId + " references missing " + spawnerId);
                hasBaseVisibilitySpawner |= BASE_VISIBILITY_SPAWNER.equals(spawnerId);
                assertTrue(group.has("PositionOffset"), systemId + " must lift " + spawnerId + " above terrain");
                assertTrue(group.getAsJsonObject("PositionOffset").get("Y").getAsDouble() >= 0.1,
                        systemId + " places " + spawnerId + " too close to terrain for soft-particle rendering");
            }
            assertTrue(hasBaseVisibilitySpawner,
                    systemId + " must retain the base-game visibility spawner");
        }
    }

    @Test
    void launchSpawnersUseOnlyApprovedBaseTexturesAndBoundedParticleCounts() throws IOException {
        int totalParticlesPerChargePulse = 0;
        for (String spawnerId : fileStems(LAUNCH_ROOT.resolve("Spawners"), ".particlespawner")) {
            JsonObject spawner = read(LAUNCH_ROOT.resolve("Spawners").resolve(spawnerId + ".particlespawner"));
            String texture = spawner.getAsJsonObject("Particle").get("Texture").getAsString();
            assertTrue(APPROVED_TEXTURES.contains(texture), spawnerId + " uses unexpected texture " + texture);
            int maxParticles = spawner.getAsJsonObject("TotalParticles").get("Max").getAsInt();
            assertTrue(maxParticles <= 4, spawnerId + " exceeds per-spawner burst budget");
            if (spawnerId.contains("Launch_Charge_")) totalParticlesPerChargePulse += maxParticles;
        }
        assertEquals(6, totalParticlesPerChargePulse);
    }

    @Test
    void fullReleaseCompositionRemainsBelowThirtyParticles() throws IOException {
        JsonObject full = read(LAUNCH_ROOT.resolve("Tamework_AvatarFlight_Launch_Release_Full.particlesystem"));
        int particles = 0;
        for (var element : full.getAsJsonArray("Spawners")) {
            String spawnerId = element.getAsJsonObject().get("SpawnerId").getAsString();
            if (BASE_VISIBILITY_SPAWNER.equals(spawnerId)) {
                particles += 1;
                continue;
            }
            JsonObject spawner = read(LAUNCH_ROOT.resolve("Spawners").resolve(spawnerId + ".particlespawner"));
            particles += spawner.getAsJsonObject("TotalParticles").get("Max").getAsInt();
        }
        assertTrue(particles < 30, "full launch release must stay under the particle budget");
        assertEquals(27, particles);
    }

    @Test
    void burstParticlesFinishWithinTheirParentSystemLifetime() throws IOException {
        Path spawners = LAUNCH_ROOT.resolve("Spawners");
        for (String systemId : fileStems(LAUNCH_ROOT, ".particlesystem")) {
            JsonObject system = read(LAUNCH_ROOT.resolve(systemId + ".particlesystem"));
            double systemLifeSpan = system.get("LifeSpan").getAsDouble();
            for (var element : system.getAsJsonArray("Spawners")) {
                JsonObject group = element.getAsJsonObject();
                String spawnerId = group.get("SpawnerId").getAsString();
                if (BASE_VISIBILITY_SPAWNER.equals(spawnerId)) continue;
                double startDelay = group.has("StartDelay") ? group.get("StartDelay").getAsDouble() : 0.0;
                JsonObject spawner = read(spawners.resolve(spawnerId + ".particlespawner"));
                double particleLifeSpan = spawner.getAsJsonObject("ParticleLifeSpan").get("Max").getAsDouble();

                assertTrue(startDelay + particleLifeSpan <= systemLifeSpan,
                        systemId + " ends before " + spawnerId + " can finish rendering");
            }
        }
    }

    @Test
    void launchSpawnersProducePopulatedHytaleNetworkPackets() throws IOException {
        Path spawners = LAUNCH_ROOT.resolve("Spawners");
        for (String spawnerId : fileStems(spawners, ".particlespawner")) {
            String json = Files.readString(
                    spawners.resolve(spawnerId + ".particlespawner"),
                    StandardCharsets.UTF_8
            );
            ExtraInfo extraInfo = new ExtraInfo(ExtraInfo.UNSET_VERSION, ValidationResults::new);
            ParticleSpawner decoded = ParticleSpawner.CODEC.decode(BsonDocument.parse(json), extraInfo);
            com.hypixel.hytale.protocol.ParticleSpawner packet = decoded.toPacket();

            assertNotNull(packet.particle, spawnerId + " has no particle packet");
            assertNotNull(packet.particle.texturePath, spawnerId + " has no texture path");
            assertNotNull(packet.particle.animationFrames, spawnerId + " has no animation frames");
            assertFalse(packet.particle.animationFrames.isEmpty(), spawnerId + " has no animation frames");
            assertNotNull(packet.particleLifeSpan, spawnerId + " has no particle lifespan");
            assertNotNull(packet.spawnRate, spawnerId + " has no spawn rate");
            assertNotNull(packet.totalParticles, spawnerId + " has no total-particle range");
            assertTrue(packet.maxConcurrentParticles > 0, spawnerId + " cannot allocate particles");
        }
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
