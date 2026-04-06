package com.alechilles.alecstamework.metrics;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpCrashReportClientTest {

    @Test
    void returnsSuccessFor2xxAndSendsJsonPayload() throws Exception {
        AtomicBoolean jsonContentTypePresent = new AtomicBoolean(false);
        HttpServer server = startServer(exchange -> {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            jsonContentTypePresent.set("application/json; charset=UTF-8".equals(contentType));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });

        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/telemetry";
            HttpCrashReportClient client = new HttpCrashReportClient(endpoint, 1000, 1000, null);
            CrashReportClient.UploadResult result = client.upload("{\"hello\":\"world\"}");

            assertTrue(result.success());
            assertTrue(jsonContentTypePresent.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsFailureForNon2xx() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/telemetry";
            HttpCrashReportClient client = new HttpCrashReportClient(endpoint, 1000, 1000, null);
            CrashReportClient.UploadResult result = client.upload("{\"hello\":\"world\"}");

            assertFalse(result.success());
            assertTrue(result.statusCode() == 500 || result.statusCode() == 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsFailureOnReadTimeout() throws Exception {
        HttpServer server = startServer(exchange -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/telemetry";
            HttpCrashReportClient client = new HttpCrashReportClient(endpoint, 100, 100, null);
            CrashReportClient.UploadResult result = client.upload("{\"hello\":\"world\"}");

            assertFalse(result.success());
        } finally {
            server.stop(0);
        }
    }

    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private static HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/telemetry", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }
}
