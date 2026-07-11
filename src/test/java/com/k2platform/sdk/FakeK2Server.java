package com.k2platform.sdk;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny in-JVM stand-in for {@code k2-app}'s token config endpoint, so SDK tests exercise the real
 * HTTP path ({@code java.net.http}) without an external dependency. Serves a fixed JSON body for
 * {@code GET /api/config/token/{env}/current} and counts hits (to assert caching).
 */
public final class FakeK2Server implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body;
    private volatile String lastPath;

    public FakeK2Server(String body) throws IOException {
        this.body = body;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/config/token/", exchange -> {
            hits.incrementAndGet();
            lastPath = exchange.getRequestURI().getPath();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            int code = status;
            if (code == 200) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int hits() {
        return hits.get();
    }

    /** The path of the most recent request (URL-decoded), or {@code null} if none yet. */
    public String lastPath() {
        return lastPath;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
