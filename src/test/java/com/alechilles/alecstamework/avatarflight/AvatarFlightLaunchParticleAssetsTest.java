package com.alechilles.alecstamework.avatarflight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.validation.ValidationResults;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawner;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
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
    private static final Path TEXTURE_ROOT = Path.of(
            "src/main/resources/Common/Particles/Textures/Tamework/AvatarFlight/Launch"
    );
    private static final Set<String> APPROVED_TEXTURES = Set.of(
            "Particles/Textures/Basic/Ball3.png",
            "Particles/Textures/Smoke/Smoke_Mist.png",
            "Particles/Textures/Smoke/Smoke_Smooth2.png",
            "Particles/Textures/Tamework/AvatarFlight/Launch/Wind_Arc.png",
            "Particles/Textures/Tamework/AvatarFlight/Launch/Wind_Curl.png",
            "Particles/Textures/Tamework/AvatarFlight/Launch/Wind_Streak.png"
    );
    private static final Map<String, Integer> EXPECTED_CUSTOM_SPRITES = Map.of(
            "Wind_Arc.png", 256,
            "Wind_Curl.png", 128,
            "Wind_Streak.png", 32
    );
    private static final Set<String> EXPECTED_SPAWNER_IDS = Set.of(
            "TwLaunchCancelPuff",
            "TwLaunchCancelRing",
            "TwLaunchChargeDust",
            "TwLaunchChargeRing",
            "TwLaunchChargeWisps",
            "TwLaunchReleaseColumn",
            "TwLaunchReleaseDust",
            "TwLaunchReleaseRing",
            "TwLaunchReleaseStreamers"
    );

    @Test
    void allLaunchSystemsReferenceAvailableSpawners() throws IOException {
        Set<String> spawnerIds = fileStems(LAUNCH_ROOT.resolve("Spawners"), ".particlespawner");
        Set<String> systemIds = fileStems(LAUNCH_ROOT, ".particlesystem");

        assertEquals(EXPECTED_SPAWNER_IDS, spawnerIds);
        assertEquals(5, systemIds.size());
        for (String systemId : systemIds) {
            JsonObject system = read(LAUNCH_ROOT.resolve(systemId + ".particlesystem"));
            assertFalse(system.get("IsImportant").getAsBoolean());
            assertTrue(system.get("CullDistance").getAsDouble() <= 75.0);
            for (var element : system.getAsJsonArray("Spawners")) {
                JsonObject group = element.getAsJsonObject();
                String spawnerId = group.get("SpawnerId").getAsString();
                assertTrue(spawnerIds.contains(spawnerId),
                        systemId + " references missing " + spawnerId);
                assertTrue(group.has("PositionOffset"), systemId + " must lift " + spawnerId + " above terrain");
                assertTrue(group.getAsJsonObject("PositionOffset").get("Y").getAsDouble() >= 0.1,
                        systemId + " places " + spawnerId + " too close to the launch surface");
            }
        }
    }

    @Test
    void launchSpawnersUseOnlyApprovedTexturesAndBoundedConcurrency() throws IOException {
        for (String spawnerId : fileStems(LAUNCH_ROOT.resolve("Spawners"), ".particlespawner")) {
            JsonObject spawner = read(LAUNCH_ROOT.resolve("Spawners").resolve(spawnerId + ".particlespawner"));
            String texture = spawner.getAsJsonObject("Particle").get("Texture").getAsString();
            assertTrue(APPROVED_TEXTURES.contains(texture), spawnerId + " uses unexpected texture " + texture);
            int maxConcurrent = spawner.get("MaxConcurrentParticles").getAsInt();
            assertTrue(maxConcurrent <= 10, spawnerId + " exceeds its concurrency budget");
            assertTrue(spawner.getAsJsonObject("TotalParticles").get("Max").getAsInt() <= maxConcurrent,
                    spawnerId + " can emit more particles than it can retain");
        }
    }

    @Test
    void sequenceCompositionsMatchParticleBudgets() throws IOException {
        assertEquals(14, compositionConcurrency("Tamework_AvatarFlight_Launch_Charge_Pulse"));
        assertEquals(4, compositionConcurrency("Tamework_AvatarFlight_Launch_Cancel"));
        assertEquals(15, compositionConcurrency("Tamework_AvatarFlight_Launch_Release_Partial"));
        assertEquals(23, compositionConcurrency("Tamework_AvatarFlight_Launch_Release_Mid"));
        int fullReleaseParticles = compositionConcurrency("Tamework_AvatarFlight_Launch_Release_Full");
        assertEquals(38, fullReleaseParticles);
        assertTrue(fullReleaseParticles < 45, "full launch release must stay under the particle budget");
    }

    @Test
    void windLayersUseSweptArcsAndCurvedParticleMotion() throws IOException {
        JsonObject chargeArc = spawner("TwLaunchChargeRing");
        JsonObject releaseArc = spawner("TwLaunchReleaseRing");
        assertEquals("Particles/Textures/Tamework/AvatarFlight/Launch/Wind_Arc.png",
                chargeArc.getAsJsonObject("Particle").get("Texture").getAsString());
        assertEquals("Particles/Textures/Tamework/AvatarFlight/Launch/Wind_Arc.png",
                releaseArc.getAsJsonObject("Particle").get("Texture").getAsString());
        assertSceneLitTranslucent(chargeArc, "TwLaunchChargeRing");
        assertSceneLitTranslucent(releaseArc, "TwLaunchReleaseRing");

        JsonObject cancelCurl = spawner("TwLaunchCancelRing");
        assertEquals("Particles/Textures/Tamework/AvatarFlight/Launch/Wind_Curl.png",
                cancelCurl.getAsJsonObject("Particle").get("Texture").getAsString());
        assertSceneLitTranslucent(cancelCurl, "TwLaunchCancelRing");

        JsonObject chargeMotes = spawner("TwLaunchChargeWisps");
        assertEquals("Particles/Textures/Basic/Ball3.png",
                chargeMotes.getAsJsonObject("Particle").get("Texture").getAsString());
        assertCurvedMotion(chargeMotes, "TwLaunchChargeWisps");

        JsonObject releaseStreamers = spawner("TwLaunchReleaseStreamers");
        assertEquals("Particles/Textures/Tamework/AvatarFlight/Launch/Wind_Streak.png",
                releaseStreamers.getAsJsonObject("Particle").get("Texture").getAsString());
        assertCurvedMotion(releaseStreamers, "TwLaunchReleaseStreamers");
        assertSceneLitTranslucent(releaseStreamers, "TwLaunchReleaseStreamers");
    }

    @Test
    void customWindSpritesHaveHytaleDimensionsAndUsableAlpha() throws IOException {
        for (Map.Entry<String, Integer> expected : EXPECTED_CUSTOM_SPRITES.entrySet()) {
            Path path = TEXTURE_ROOT.resolve(expected.getKey());
            BufferedImage image = ImageIO.read(path.toFile());
            assertNotNull(image, expected.getKey() + " is not a readable PNG");
            assertEquals(expected.getValue(), image.getWidth(), expected.getKey() + " has the wrong width");
            assertEquals(expected.getValue(), image.getHeight(), expected.getKey() + " has the wrong height");
            assertTrue(image.getColorModel().hasAlpha(), expected.getKey() + " has no alpha channel");

            assertEquals(0, alphaAt(image, 0, 0), expected.getKey() + " has a visible top-left corner");
            assertEquals(0, alphaAt(image, image.getWidth() - 1, 0),
                    expected.getKey() + " has a visible top-right corner");
            assertEquals(0, alphaAt(image, 0, image.getHeight() - 1),
                    expected.getKey() + " has a visible bottom-left corner");
            assertEquals(0, alphaAt(image, image.getWidth() - 1, image.getHeight() - 1),
                    expected.getKey() + " has a visible bottom-right corner");

            double coverage = visiblePixels(image) / (double) (image.getWidth() * image.getHeight());
            assertTrue(coverage > 0.02, expected.getKey() + " is effectively blank");
            assertTrue(coverage < 0.55, expected.getKey() + " leaves too little transparent padding");
        }
    }

    @Test
    void particlesFinishWithinTheirParentSystemLifetime() throws IOException {
        Path spawners = LAUNCH_ROOT.resolve("Spawners");
        for (String systemId : fileStems(LAUNCH_ROOT, ".particlesystem")) {
            JsonObject system = read(LAUNCH_ROOT.resolve(systemId + ".particlesystem"));
            double systemLifeSpan = system.get("LifeSpan").getAsDouble();
            for (var element : system.getAsJsonArray("Spawners")) {
                JsonObject group = element.getAsJsonObject();
                String spawnerId = group.get("SpawnerId").getAsString();
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

    private static int compositionConcurrency(String systemId) throws IOException {
        JsonObject system = read(LAUNCH_ROOT.resolve(systemId + ".particlesystem"));
        int particles = 0;
        for (var element : system.getAsJsonArray("Spawners")) {
            String spawnerId = element.getAsJsonObject().get("SpawnerId").getAsString();
            JsonObject spawner = read(LAUNCH_ROOT.resolve("Spawners").resolve(spawnerId + ".particlespawner"));
            particles += spawner.get("MaxConcurrentParticles").getAsInt();
        }
        return particles;
    }

    private static JsonObject spawner(String spawnerId) throws IOException {
        return read(LAUNCH_ROOT.resolve("Spawners").resolve(spawnerId + ".particlespawner"));
    }

    private static void assertCurvedMotion(JsonObject spawner, String spawnerId) {
        JsonObject attractor = spawner.getAsJsonArray("Attractors").get(0).getAsJsonObject();
        assertTrue(attractor.get("RadialAcceleration").getAsDouble() < 0,
                spawnerId + " must pull particles toward its orbit");
        assertTrue(Math.abs(attractor.get("RadialTangentAcceleration").getAsDouble()) > 0,
                spawnerId + " must bend particles around its orbit");
    }

    private static void assertSceneLitTranslucent(JsonObject spawner, String spawnerId) {
        assertEquals("BlendLinear", spawner.get("RenderMode").getAsString(),
                spawnerId + " must use alpha blending instead of a hard erosion silhouette");
        assertTrue(spawner.get("LightInfluence").getAsDouble() >= 1,
                spawnerId + " must receive scene lighting instead of rendering fullbright");

        JsonObject particle = spawner.getAsJsonObject("Particle");
        double maxOpacity = particle.getAsJsonObject("Animation").entrySet().stream()
                .map(Map.Entry::getValue)
                .map(value -> value.getAsJsonObject())
                .filter(frame -> frame.has("Opacity"))
                .mapToDouble(frame -> frame.get("Opacity").getAsDouble())
                .max()
                .orElse(0);
        JsonObject initialFrame = particle.getAsJsonObject("InitialAnimationFrame");
        if (initialFrame.has("Opacity")) {
            maxOpacity = Math.max(maxOpacity, initialFrame.get("Opacity").getAsDouble());
        }
        assertTrue(maxOpacity <= 0.4, spawnerId + " is too opaque for a wind sheet");
    }

    private static int visiblePixels(BufferedImage image) {
        int visible = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alphaAt(image, x, y) > 8) {
                    visible++;
                }
            }
        }
        return visible;
    }

    private static int alphaAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
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
