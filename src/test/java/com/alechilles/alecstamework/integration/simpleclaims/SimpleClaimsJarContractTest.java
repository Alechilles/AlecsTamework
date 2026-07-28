package com.alechilles.alecstamework.integration.simpleclaims;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in smoke test for the real SimpleClaims jar without adding it as a compile dependency. */
class SimpleClaimsJarContractTest {
    private static final String JAR_ENV = "SIMPLECLAIMS_JAR";
    private static final String MAIN_CLASS = "com.buuz135.simpleclaims.Main";

    @Test
    void configuredSimpleClaimsJarMatchesVerifiedContract() throws Exception {
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
                SimpleClaimsJarContractTest.class.getClassLoader()
        )) {
            Class<?> manager = Class.forName(SimpleClaimsReflection.CLAIM_MANAGER_CLASS, false, loader);
            Class<?> chunk = Class.forName(SimpleClaimsReflection.CHUNK_INFO_CLASS, false, loader);
            Class<?> party = Class.forName(SimpleClaimsReflection.PARTY_INFO_CLASS, false, loader);

            assertEquals(
                    jarPath,
                    Path.of(manager.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .toAbsolutePath()
                            .normalize(),
                    "The smoke test must inspect the configured jar, not a hidden compile dependency."
            );

            Method getInstance = assertPublicMethod(manager, "getInstance", manager);
            assertTrue(Modifier.isStatic(getInstance.getModifiers()), "getInstance() must remain static.");
            assertPublicMethod(manager, "getChunkRawCoords", chunk, String.class, int.class, int.class);
            assertPublicMethod(manager, "getPartyById", party, UUID.class);
            assertPublicMethod(manager, "getAmountOfClaims", int.class, party);
            assertPublicMethod(
                    manager,
                    "isAllowedToInteract",
                    boolean.class,
                    UUID.class,
                    String.class,
                    int.class,
                    int.class,
                    Predicate.class,
                    String.class
            );

            assertPublicMethod(chunk, "getPartyOwner", UUID.class);
            assertPublicMethod(party, "isTamedDamageEnabled", boolean.class);
        }
    }

    private static void assertManifest(Path jarPath) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("manifest.json");
            assertNotNull(entry, "SimpleClaims jar must contain a root manifest.json.");
            try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject manifest = JsonParser.parseReader(reader).getAsJsonObject();
                assertEquals("Buuz135", manifest.get("Group").getAsString());
                assertEquals("SimpleClaims", manifest.get("Name").getAsString());
                assertEquals("1.0.38", manifest.get("Version").getAsString());
                assertEquals(MAIN_CLASS, manifest.get("Main").getAsString());
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
