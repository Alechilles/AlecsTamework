package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.alechilles.beacon.coordinator.TelemetryCoordinatorBridge;
import com.alechilles.beacon.coordinator.TelemetryCoordinatorRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Verifies observable behavior from Tamework's packaged Patchwork runtime.
 *
 * <p>The test deliberately invokes classes from the shaded jar through a child-first loader.
 * ZIP-entry inventories are not a useful contract: the runtime must report its aligned logical
 * version and remain non-fatal when no Hytale host is available to provide telemetry.</p>
 */
class PatchworkPackagingIT {
    @Test
    void packagedPatchworkReportsTheAlignedVersionAndGracefullyDisablesWithoutAHost() throws Exception {
        Path packagedJar = Path.of(System.getProperty("patchwork.packagedJar"));
        URL packagedUrl = packagedJar.toUri().toURL();
        try (URLClassLoader loader = new PackagedRuntimeClassLoader(packagedUrl, getClass().getClassLoader())) {
            Class<?> versionType = Class.forName("com.alechilles.patchwork.PatchworkVersion", true, loader);
            assertEquals("1.4.1", invoke(versionType.getMethod("current"), null));

            Class<?> telemetryType = Class.forName("com.alechilles.patchwork.telemetry.PatchworkTelemetry", true, loader);
            Class<?> javaPluginType = Class.forName(
                    "com.hypixel.hytale.server.core.plugin.JavaPlugin",
                    false,
                    getClass().getClassLoader()
            );
            Object telemetry = invoke(
                    telemetryType.getMethod("prepare", javaPluginType),
                    null,
                    new Object[]{null}
            );

            assertFalse((Boolean) invoke(telemetryType.getMethod("enabled"), telemetry));
            assertDoesNotThrow(() -> invoke(telemetryType.getMethod("start"), telemetry));
            assertDoesNotThrow(() -> invoke(telemetryType.getMethod("close"), telemetry));
        }
    }

    @Test
    void packagedEmbeddedBeaconUsesItsOwnRuntimeWhileSharingTheCoordinatorRegistry() throws Exception {
        Path packagedJar = Path.of(System.getProperty("patchwork.packagedJar"));
        URL packagedUrl = packagedJar.toUri().toURL();
        Class<?> parentBeacon = Class.forName(
                "com.alechilles.beacon.embedded.EmbeddedTelemetryService",
                true,
                getClass().getClassLoader()
        );
        ParentCoordinatorBridge parentBridge = new ParentCoordinatorBridge();
        TelemetryCoordinatorRegistry.clearForTests();
        TelemetryCoordinatorRegistry.register(parentBridge);
        try (URLClassLoader loader = new PackagedRuntimeClassLoader(packagedUrl, getClass().getClassLoader())) {
            Class<?> packagedBeacon = Class.forName(
                    "com.alechilles.alecstamework.shadow.beacon.embedded.EmbeddedTelemetryService",
                    true,
                    loader
            );
            assertNotEquals(parentBeacon, packagedBeacon);

            Object service = invoke(
                    packagedBeacon.getMethod("disabled", String.class, String.class,
                            Class.forName("com.hypixel.hytale.logger.HytaleLogger", false, getClass().getClassLoader()),
                            String.class),
                    null,
                    "test-project", "Test Project", null, "unavailable"
            );
            Method commandRuntime = packagedBeacon.getDeclaredMethod("commandRuntime");
            commandRuntime.setAccessible(true);
            assertNotNull(invoke(commandRuntime, service));

            Class<?> packagedRegistry = Class.forName(
                    "com.alechilles.alecstamework.shadow.beacon.coordinator.TelemetryCoordinatorRegistry",
                    true,
                    loader
            );
            Object activeBridge = invoke(packagedRegistry.getMethod("activeBridge"), null);
            assertNotNull(activeBridge);
            Method providerId = activeBridge.getClass().getDeclaredMethod("providerId");
            providerId.setAccessible(true);
            assertEquals(parentBridge.providerId(), invoke(providerId, activeBridge));
        } finally {
            TelemetryCoordinatorRegistry.clearForTests();
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    ? invocation.getCause()
                    : failure;
            throw new AssertionError("Packaged Patchwork behavior invocation failed: " + method, cause);
        }
    }

    /** Loads packaged private runtimes before delegating Hytale and competing Beacon dependencies. */
    private static final class PackagedRuntimeClassLoader extends URLClassLoader {
        private PackagedRuntimeClassLoader(URL packagedUrl, ClassLoader parent) {
            super(new URL[]{packagedUrl}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.alechilles.patchwork.")
                    || name.startsWith("com.alechilles.alecstamework.shadow.beacon.")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            loaded = super.loadClass(name, false);
                        }
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            if (name.startsWith("META-INF/maven/com.alechilles/patchwork-runtime/")) {
                URL packagedResource = findResource(name);
                if (packagedResource != null) {
                    return packagedResource;
                }
            }
            return super.getResource(name);
        }
    }

    private static final class ParentCoordinatorBridge implements TelemetryCoordinatorBridge {
        private boolean active;

        @Override
        public String providerId() {
            return "parent-beacon";
        }

        @Override
        public String origin() {
            return "STANDALONE";
        }

        @Override
        public String runtimeVersion() {
            return "999.0.0";
        }

        @Override
        public int coordinatorProtocolVersion() {
            return 3;
        }

        @Override
        public String providerPluginIdentifier() {
            return "parent";
        }

        @Override
        public String providerPluginVersion() {
            return "999.0.0";
        }

        @Override
        public String sourcePath() {
            return "parent-beacon.jar";
        }

        @Override
        public String sharedDataRoot() {
            return "parent-beacon-data";
        }

        @Override
        public void activate() {
            active = true;
        }

        @Override
        public void deactivate() {
            active = false;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
