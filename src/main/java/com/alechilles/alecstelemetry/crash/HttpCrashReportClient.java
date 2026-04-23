package com.alechilles.alecstelemetry.crash;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;

/**
 * HTTP implementation for crash report uploads.
 */
public final class HttpCrashReportClient implements CrashReportClient {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final HytaleLogger logger;

    public HttpCrashReportClient(int connectTimeoutMs,
                                 int readTimeoutMs,
                                 @Nullable HytaleLogger logger) {
        this.connectTimeoutMs = Math.max(100, connectTimeoutMs);
        this.readTimeoutMs = Math.max(100, readTimeoutMs);
        this.logger = logger;
    }

    @Nonnull
    @Override
    public UploadResult upload(@Nonnull DeliveryTarget target, @Nonnull String payloadJson) {
        DeliveryTarget normalizedTarget = target.normalize();
        if (normalizedTarget.endpoint().isBlank()) {
            return UploadResult.failure(0, "Endpoint is not configured.");
        }
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) URI.create(normalizedTarget.endpoint()).toURL().openConnection();
            http.setConnectTimeout(connectTimeoutMs);
            http.setReadTimeout(readTimeoutMs);
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            for (Map.Entry<String, String> header : normalizedTarget.headers().entrySet()) {
                http.setRequestProperty(header.getKey(), header.getValue());
            }

            byte[] payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8);
            http.setFixedLengthStreamingMode(payloadBytes.length);
            try (OutputStream output = http.getOutputStream()) {
                output.write(payloadBytes);
            }

            int statusCode = http.getResponseCode();
            if (statusCode >= 200 && statusCode < 300) {
                closeQuietly(http.getInputStream());
                return UploadResult.success(statusCode);
            }

            closeQuietly(http.getErrorStream());
            return UploadResult.failure(statusCode, "HTTP " + statusCode);
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log("Crash telemetry upload request failed.");
            }
            return UploadResult.failure(0, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (http != null) {
                http.disconnect();
            }
        }
    }

    private static void closeQuietly(@Nullable InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // Intentionally ignored.
        }
    }
}
