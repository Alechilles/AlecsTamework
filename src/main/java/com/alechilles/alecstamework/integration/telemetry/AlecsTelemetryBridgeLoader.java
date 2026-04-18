package com.alechilles.alecstamework.integration.telemetry;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Loads the optional Alec's Telemetry runtime API when it is present.
 */
public final class AlecsTelemetryBridgeLoader {

    private static final String LOCATOR_CLASS = "com.alechilles.alecstelemetry.api.TelemetryRuntimeLocator";

    private AlecsTelemetryBridgeLoader() {
    }

    @Nonnull
    public static AlecsTelemetryBridge initialize(@Nullable HytaleLogger logger, @Nonnull String projectId) {
        if (!isApiPresent()) {
            return NoOpAlecsTelemetryBridge.INSTANCE;
        }
        if (logger != null) {
            logger.at(Level.INFO).log("Alec's Telemetry integration detected.");
        }
        return new ReflectiveAlecsTelemetryBridge(projectId, logger);
    }

    private static boolean isApiPresent() {
        try {
            Class.forName(LOCATOR_CLASS);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Tiny optional bridge surface used by Tamework.
     */
    public interface AlecsTelemetryBridge {
        void recordBreadcrumb(@Nonnull String category, @Nonnull String detail);

        void captureSetupFailure(@Nullable Throwable throwable);

        void captureStartFailure(@Nullable Throwable throwable);
    }

    private enum NoOpAlecsTelemetryBridge implements AlecsTelemetryBridge {
        INSTANCE;

        @Override
        public void recordBreadcrumb(@Nonnull String category, @Nonnull String detail) {
        }

        @Override
        public void captureSetupFailure(@Nullable Throwable throwable) {
        }

        @Override
        public void captureStartFailure(@Nullable Throwable throwable) {
        }
    }

    private static final class ReflectiveAlecsTelemetryBridge implements AlecsTelemetryBridge {

        private final String projectId;
        private final HytaleLogger logger;

        private ReflectiveAlecsTelemetryBridge(@Nonnull String projectId, @Nullable HytaleLogger logger) {
            this.projectId = projectId;
            this.logger = logger;
        }

        @Override
        public void recordBreadcrumb(@Nonnull String category, @Nonnull String detail) {
            invokeProjectHandleMethod("recordBreadcrumb", new Class<?>[]{String.class, String.class}, category, detail);
        }

        @Override
        public void captureSetupFailure(@Nullable Throwable throwable) {
            if (throwable == null) {
                return;
            }
            invokeProjectHandleMethod("captureSetupFailure", new Class<?>[]{Throwable.class}, throwable);
        }

        @Override
        public void captureStartFailure(@Nullable Throwable throwable) {
            if (throwable == null) {
                return;
            }
            invokeProjectHandleMethod("captureStartFailure", new Class<?>[]{Throwable.class}, throwable);
        }

        private void invokeProjectHandleMethod(@Nonnull String methodName,
                                               @Nonnull Class<?>[] parameterTypes,
                                               @Nonnull Object... args) {
            try {
                Object projectHandle = resolveProjectHandle();
                if (projectHandle == null) {
                    return;
                }
                Method method = projectHandle.getClass().getMethod(methodName, parameterTypes);
                method.invoke(projectHandle, args);
            } catch (Throwable ex) {
                if (logger != null) {
                    logger.at(Level.WARNING).withCause(ex)
                            .log("Alec's Telemetry integration failed while calling " + methodName + " for " + projectId + ".");
                }
            }
        }

        @Nullable
        private Object resolveProjectHandle() throws Exception {
            Class<?> locatorClass = Class.forName(LOCATOR_CLASS);
            Method tryGet = locatorClass.getMethod("tryGet");
            Object runtimeApi = tryGet.invoke(null);
            if (runtimeApi == null) {
                return null;
            }
            Method findProject = runtimeApi.getClass().getMethod("findProject", String.class);
            return findProject.invoke(runtimeApi, projectId);
        }
    }
}
