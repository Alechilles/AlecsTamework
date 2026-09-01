package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
            assertEquals("1.4.0", invoke(versionType.getMethod("current"), null));

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

    /** Loads the shaded Patchwork and Telemetry packages before delegating Hytale dependencies. */
    private static final class PackagedRuntimeClassLoader extends URLClassLoader {
        private PackagedRuntimeClassLoader(URL packagedUrl, ClassLoader parent) {
            super(new URL[]{packagedUrl}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.alechilles.patchwork.")
                    || name.startsWith("com.alechilles.alecstelemetry.")) {
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
            if (name.startsWith("META-INF/maven/com.alechilles/patchwork-runtime/")
                    || name.startsWith("META-INF/maven/com.alechilles/alecstelemetry-runtime/")) {
                URL packagedResource = findResource(name);
                if (packagedResource != null) {
                    return packagedResource;
                }
            }
            return super.getResource(name);
        }
    }
}
