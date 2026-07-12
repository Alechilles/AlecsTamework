package com.alechilles.alecstamework.integration.questlinesclaims;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in smoke test for the real QuestLines Claims jar without adding it as a compile dependency.
 */
class QuestLinesClaimsJarContractTest {

    private static final String JAR_ENV = "QUESTLINES_CLAIMS_JAR";
    private static final String PLUGIN_CLASS = "net.evilcraft.questlinesclaims.QuestLinesClaimsPlugin";
    private static final String API_CLASS = "net.evilcraft.questlinesclaims.api.QuestLinesClaimsAPI";
    private static final String CLAIM_CLASS = "net.evilcraft.questlinesclaims.data.PlayerClaim";
    private static final String CHUNK_CLASS = "net.evilcraft.questlinesclaims.data.ChunkCoord";
    private static final String OWNER_TYPE_CLASS = "net.evilcraft.questlinesclaims.data.ClaimOwnerType";

    @Test
    void configuredQuestLinesClaimsJarMatchesVerifiedContract() throws Exception {
        String configuredJar = System.getenv(JAR_ENV);
        Assumptions.assumeTrue(
                configuredJar != null && !configuredJar.isBlank(),
                () -> JAR_ENV + " is not configured; skipping the real-jar contract smoke test."
        );

        Path jarPath = Path.of(configuredJar).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(jarPath), () -> JAR_ENV + " is not a jar file: " + jarPath);
        assertManifest(jarPath);

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{jarPath.toUri().toURL()},
                QuestLinesClaimsJarContractTest.class.getClassLoader()
        )) {
            Class<?> plugin = Class.forName(PLUGIN_CLASS, false, loader);
            Class<?> api = Class.forName(API_CLASS, false, loader);
            Class<?> claim = Class.forName(CLAIM_CLASS, false, loader);
            Class<?> chunk = Class.forName(CHUNK_CLASS, false, loader);
            Class<?> ownerType = Class.forName(OWNER_TYPE_CLASS, false, loader);

            assertEquals(
                    jarPath,
                    Path.of(plugin.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .toAbsolutePath()
                            .normalize(),
                    "The smoke test must inspect the configured jar, not a hidden compile dependency."
            );

            Method getInstance = assertPublicMethod(plugin, "getInstance", plugin);
            assertTrue(Modifier.isStatic(getInstance.getModifiers()), "getInstance() must remain static.");
            assertPublicMethod(plugin, "getApi", api);
            assertPublicMethod(api, "getClaimAtBlock", claim, String.class, int.class, int.class);

            assertPublicMethod(claim, "getClaimId", int.class);
            assertPublicMethod(claim, "getOwnerUuid", java.util.UUID.class);
            assertPublicMethod(claim, "getOwnerType", ownerType);
            assertPublicMethod(claim, "getWorldName", String.class);
            Method getChunks = assertPublicMethod(claim, "getChunks", Set.class);
            assertTrue(
                    getChunks.getGenericReturnType() instanceof ParameterizedType,
                    "getChunks() must retain its generic ChunkCoord element contract."
            );
            assertEquals(
                    CHUNK_CLASS,
                    ((Class<?>) ((ParameterizedType) getChunks.getGenericReturnType())
                            .getActualTypeArguments()[0]).getName(),
                    "getChunks() must remain a Set<ChunkCoord>."
            );

            assertPublicMethod(chunk, "getChunkX", int.class);
            assertPublicMethod(chunk, "getChunkZ", int.class);
            assertPublicMethod(chunk, "getWorldName", String.class);
            assertTrue(ownerType.isEnum(), "ClaimOwnerType must remain an enum.");
        }
    }

    private static void assertManifest(Path jarPath) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("manifest.json");
            assertNotNull(entry, "QuestLines Claims jar must contain a root manifest.json.");
            try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject manifest = JsonParser.parseReader(reader).getAsJsonObject();
                assertEquals("net.evilcraft", manifest.get("Group").getAsString());
                assertEquals("QuestLinesClaims", manifest.get("Name").getAsString());
                assertEquals("1.3.1", manifest.get("Version").getAsString());
                assertEquals(PLUGIN_CLASS, manifest.get("Main").getAsString());
            }
        }
    }

    private static Method assertPublicMethod(
            Class<?> owner,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes
    ) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        assertTrue(Modifier.isPublic(method.getModifiers()), () -> owner.getName() + '#' + name + " must be public.");
        assertEquals(returnType, method.getReturnType(), () -> owner.getName() + '#' + name + " return type changed.");
        return method;
    }
}
