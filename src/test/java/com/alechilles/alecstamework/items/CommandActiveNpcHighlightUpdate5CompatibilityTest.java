package com.alechilles.alecstamework.items;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the active-highlight packet path against the supported Update 5 engine binary. */
class CommandActiveNpcHighlightUpdate5CompatibilityTest {
    private static final String EMITTER =
            "com.alechilles.alecstamework.items.CommandActiveNpcHighlightEmitter";
    private static final String ANCHOR =
            "com.alechilles.alecstamework.items.CommandActiveNpcHighlightAnchor";

    @Test
    void emissionLinksAndBuildsTheExpectedPacketOnUpdate5() throws Exception {
        URL mainClasses = CommandActiveNpcHighlightEmitter.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();
        URL update5Server = Path.of(System.getProperty("tamework.update5ServerJar"))
                .toUri()
                .toURL();

        try (Update5ClassLoader loader = new Update5ClassLoader(mainClasses, update5Server)) {
            Class<?> packetSinkType = loader.loadClass(EMITTER + "$PacketSink");
            List<Object> packets = new ArrayList<>();
            Object packetSink = Proxy.newProxyInstance(
                    loader,
                    new Class<?>[]{packetSinkType},
                    (proxy, method, arguments) -> {
                        if ("send".equals(method.getName())) {
                            packets.add(arguments[1]);
                            return true;
                        }
                        return method.invoke(this, arguments);
                    }
            );

            Class<?> emitterType = loader.loadClass(EMITTER);
            Constructor<?> emitterConstructor = emitterType.getDeclaredConstructor(packetSinkType);
            emitterConstructor.setAccessible(true);
            Object emitter = emitterConstructor.newInstance(packetSink);

            Class<?> networkIdType = loader.loadClass(
                    "com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId");
            Object networkId = networkIdType.getConstructor(int.class).newInstance(42);
            Class<?> refType = loader.loadClass("com.hypixel.hytale.component.Ref");
            Class<?> storeType = loader.loadClass("com.hypixel.hytale.component.Store");
            Object viewerRef = refType.getConstructor(storeType, int.class).newInstance(null, 7);

            Class<?> vectorType = loader.loadClass("org.joml.Vector3f");
            Object offset = vectorType.getConstructor(float.class, float.class, float.class)
                    .newInstance(0.0f, 0.25f, 0.0f);
            Class<?> anchorType = loader.loadClass(ANCHOR);
            Constructor<?> anchorConstructor = anchorType.getDeclaredConstructor(String.class, vectorType);
            anchorConstructor.setAccessible(true);
            Object anchor = anchorConstructor.newInstance("Head", offset);

            Method emit = emitterType.getDeclaredMethod(
                    "emit",
                    networkIdType,
                    refType,
                    String.class,
                    anchorType,
                    storeType
            );
            emit.setAccessible(true);
            assertTrue((boolean) emit.invoke(emitter, networkId, viewerRef, "#112233", anchor, null));

            assertEquals(1, packets.size());
            Object packet = packets.getFirst();
            assertEquals(42, publicField(packet, "entityId"));
            Object[] modelParticles = (Object[]) publicField(packet, "modelParticles");
            assertEquals(1, modelParticles.length);
            assertEquals(CommandActiveNpcHighlightEmitter.PARTICLE_SYSTEM_ID,
                    publicField(modelParticles[0], "systemId"));
            assertEquals("Head", publicField(modelParticles[0], "targetNodeName"));
        }
    }

    @Test
    void cancellationDoesNotLinkTheUpdate6OnlyPacketOnUpdate5() throws Exception {
        URL mainClasses = CommandActiveNpcHighlightEmitter.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();
        URL update5Server = Path.of(System.getProperty("tamework.update5ServerJar"))
                .toUri()
                .toURL();

        try (Update5ClassLoader loader = new Update5ClassLoader(mainClasses, update5Server)) {
            Class<?> packetSinkType = loader.loadClass(EMITTER + "$PacketSink");
            List<Object> packets = new ArrayList<>();
            Object packetSink = Proxy.newProxyInstance(
                    loader,
                    new Class<?>[]{packetSinkType},
                    (proxy, method, arguments) -> {
                        if ("send".equals(method.getName())) {
                            packets.add(arguments[1]);
                            return true;
                        }
                        return method.invoke(this, arguments);
                    }
            );

            Class<?> emitterType = loader.loadClass(EMITTER);
            Constructor<?> emitterConstructor = emitterType.getDeclaredConstructor(packetSinkType);
            emitterConstructor.setAccessible(true);
            Object emitter = emitterConstructor.newInstance(packetSink);
            Class<?> refType = loader.loadClass("com.hypixel.hytale.component.Ref");
            Class<?> storeType = loader.loadClass("com.hypixel.hytale.component.Store");
            Object viewerRef = refType.getConstructor(storeType, int.class).newInstance(null, 7);

            Method cancel = emitterType.getDeclaredMethod("cancel", refType, storeType);
            cancel.setAccessible(true);
            assertEquals(false, cancel.invoke(emitter, viewerRef, null));
            assertTrue(packets.isEmpty());
        }
    }

    private static Object publicField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        return field.get(target);
    }

    private static final class Update5ClassLoader extends URLClassLoader {
        private Update5ClassLoader(URL mainClasses, URL update5Server) {
            super(new URL[]{mainClasses, update5Server}, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = loadIsolated(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> loadIsolated(String name) throws ClassNotFoundException {
            if (name.equals(EMITTER)
                    || name.startsWith(EMITTER + "$")
                    || name.equals(ANCHOR)
                    || name.startsWith("com.hypixel.hytale.")
                    || name.startsWith("org.joml.")) {
                return findClass(name);
            }
            return super.loadClass(name, false);
        }
    }
}
