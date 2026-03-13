package com.alechilles.alecstamework.metrics;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Level;

/**
 * Default HTTP client for HStats add-plugin reporting.
 */
public final class HttpHStatsAddPluginClient implements HStatsAddPluginClient {

    private static final String ADD_PLUGIN_URL = "https://api.hstats.dev/api/server/add-plugin";
    private static final int TIMEOUT_MS = 4000;

    private final HytaleLogger logger;

    public HttpHStatsAddPluginClient(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public boolean addPlugin(String serverUuid, String pluginUuid, String pluginVersion) {
        if (isBlank(serverUuid) || isBlank(pluginUuid)) {
            return false;
        }
        String resolvedVersion = isBlank(pluginVersion) ? "Unknown" : pluginVersion;
        Map<String, String> arguments = Map.of(
                "server_uuid", serverUuid,
                "plugin_uuid", pluginUuid,
                "plugin_version", resolvedVersion
        );
        try {
            HttpURLConnection http = (HttpURLConnection) URI.create(ADD_PLUGIN_URL).toURL().openConnection();
            http.setConnectTimeout(TIMEOUT_MS);
            http.setReadTimeout(TIMEOUT_MS);
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            byte[] payload = encodeForm(arguments).getBytes(StandardCharsets.UTF_8);
            http.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = http.getOutputStream()) {
                os.write(payload);
            }

            int code = http.getResponseCode();
            InputStream response = (code >= 200 && code < 300) ? http.getInputStream() : http.getErrorStream();
            if (response != null) {
                response.close();
            }
            http.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Tamework HStats forwarding: failed add-plugin request for plugin uuid " + pluginUuid);
            }
            return false;
        }
    }

    private static String encodeForm(Map<String, String> arguments) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            joiner.add(
                    URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                            + "="
                            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)
            );
        }
        return joiner.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
