package com.alechilles.alecstamework.metrics;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Optional reflection-based bridge to Alec's Telemetry dependency mode runtime API.
 */
public final class AlecsTelemetryBridge {

    private static final String PROJECT_ID = "alecs-tamework";
    private static final String LOCATOR_CLASS = "com.alechilles.alecstelemetry.api.TelemetryRuntimeLocator";

    @Nonnull
    public InvocationResult recordError(@Nonnull String eventName,
                                        @Nullable Throwable throwable,
                                        @Nullable String detail) {
        try {
            Object projectHandle = resolveProjectHandle();
            if (projectHandle == null) {
                return new InvocationResult(false, false, false, "Alec's Telemetry project '" + PROJECT_ID + "' is not available.");
            }
            Method method = projectHandle.getClass().getMethod("recordError", String.class, Throwable.class, String.class);
            method.invoke(projectHandle, eventName, throwable, detail);
            requestFlush(projectHandle);
            return new InvocationResult(true, true, true, "Telemetry error event queued for project '" + PROJECT_ID + "'.");
        } catch (ReflectiveOperationException ex) {
            return new InvocationResult(true, true, false, "Failed to invoke Alec's Telemetry error API: " + ex.getClass().getSimpleName());
        }
    }

    @Nonnull
    public InvocationResult recordLifecycle(@Nonnull String eventName,
                                            int durationMs,
                                            boolean success,
                                            @Nullable String detail) {
        try {
            Object projectHandle = resolveProjectHandle();
            if (projectHandle == null) {
                return new InvocationResult(false, false, false, "Alec's Telemetry project '" + PROJECT_ID + "' is not available.");
            }
            Method method = projectHandle.getClass().getMethod("recordLifecycle", String.class, int.class, boolean.class, String.class);
            method.invoke(projectHandle, eventName, Math.max(0, durationMs), success, detail);
            requestFlush(projectHandle);
            return new InvocationResult(true, true, true, "Telemetry lifecycle event queued for project '" + PROJECT_ID + "'.");
        } catch (ReflectiveOperationException ex) {
            return new InvocationResult(true, true, false, "Failed to invoke Alec's Telemetry lifecycle API: " + ex.getClass().getSimpleName());
        }
    }

    @Nonnull
    public InvocationResult recordPerformance(@Nonnull String eventName,
                                              int durationMs,
                                              @Nullable Double metricValue,
                                              @Nullable String detail) {
        try {
            Object projectHandle = resolveProjectHandle();
            if (projectHandle == null) {
                return new InvocationResult(false, false, false, "Alec's Telemetry project '" + PROJECT_ID + "' is not available.");
            }
            Method method = projectHandle.getClass().getMethod("recordPerformance", String.class, int.class, Double.class, String.class);
            method.invoke(projectHandle, eventName, Math.max(0, durationMs), metricValue, detail);
            requestFlush(projectHandle);
            return new InvocationResult(true, true, true, "Telemetry performance event queued for project '" + PROJECT_ID + "'.");
        } catch (ReflectiveOperationException ex) {
            return new InvocationResult(true, true, false, "Failed to invoke Alec's Telemetry performance API: " + ex.getClass().getSimpleName());
        }
    }

    @Nonnull
    public InvocationResult recordUsage(@Nonnull String eventName,
                                        @Nullable String detail) {
        try {
            Object projectHandle = resolveProjectHandle();
            if (projectHandle == null) {
                return new InvocationResult(false, false, false, "Alec's Telemetry project '" + PROJECT_ID + "' is not available.");
            }
            Method method = projectHandle.getClass().getMethod("recordUsage", String.class, String.class);
            method.invoke(projectHandle, eventName, detail);
            requestFlush(projectHandle);
            return new InvocationResult(true, true, true, "Telemetry usage event queued for project '" + PROJECT_ID + "'.");
        } catch (ReflectiveOperationException ex) {
            return new InvocationResult(true, true, false, "Failed to invoke Alec's Telemetry usage API: " + ex.getClass().getSimpleName());
        }
    }

    @Nullable
    private Object resolveProjectHandle() throws ReflectiveOperationException {
        Class<?> locatorClass = Class.forName(LOCATOR_CLASS);
        Method tryGet = locatorClass.getMethod("tryGet");
        Object api = tryGet.invoke(null);
        if (api == null) {
            return null;
        }
        Method findProject = api.getClass().getMethod("findProject", String.class);
        return findProject.invoke(api, PROJECT_ID);
    }

    private void requestFlush(@Nonnull Object projectHandle) throws ReflectiveOperationException {
        Method requestFlush = projectHandle.getClass().getMethod("requestFlush");
        requestFlush.invoke(projectHandle);
    }

    public record InvocationResult(boolean runtimeAvailable,
                                   boolean projectRegistered,
                                   boolean invoked,
                                   @Nonnull String message) {
    }
}
