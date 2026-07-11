package com.k2platform.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STS workload-identity auth (§1a): the SDK posts a signed envelope to /api/config/sts/{app}/{env}. */
class StsAuthTest {

    private static final StsIdentitySigner FAKE_SIGNER = () -> new StsIdentitySigner.SignedStsRequest(
            "https://sts.us-east-1.amazonaws.com/",
            Map.of("Authorization", List.of("AWS4-HMAC-SHA256 ..."),
                   "X-Amz-Date", List.of("20260705T000000Z")),
            "Action=GetCallerIdentity&Version=2011-06-15");

    @Test
    void postsSignedEnvelopeToStsPath() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (StsFakeServer server = new StsFakeServer(200,
                "{ \"organization\":\"acme\",\"application\":\"billing\",\"environment\":\"prod\","
                        + "\"properties\":{ \"db.url\":\"jdbc:postgresql://h/db\" } }", path, body)) {

            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl())
                    .application("billing")           // STS is org-scoped → app named by client
                    .stsSigner(FAKE_SIGNER)
                    .build();                          // no token required

            K2Configuration cfg = client.getConfiguration("prod");
            assertEquals("jdbc:postgresql://h/db", cfg.getString("db.url", null));
            assertEquals("/api/config/sts/billing/prod/current", path.get());
            assertTrue(body.get().contains("GetCallerIdentity"), "signed STS envelope must be posted");
            assertTrue(body.get().contains("sts.us-east-1.amazonaws.com"));
        }
    }

    @Test
    void stsWithoutAppFailsFast() {
        K2Client client = K2Client.builder()
                .baseUrl("http://localhost:1")   // never contacted
                .stsSigner(FAKE_SIGNER)
                .build();
        K2Exception ex = assertThrows(K2Exception.class, () -> client.getConfiguration("prod"));
        assertTrue(ex.getMessage().contains("K2_APP"));
    }

    @Test
    void rejectionSurfacesAs403() throws Exception {
        try (StsFakeServer server = new StsFakeServer(403, "", new AtomicReference<>(), new AtomicReference<>())) {
            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl()).application("billing").stsSigner(FAKE_SIGNER).build();
            K2Exception ex = assertThrows(K2Exception.class, () -> client.getConfiguration("prod"));
            assertEquals(403, ex.getStatusCode());
        }
    }

    /** Minimal server for the POST /api/config/sts/** contract, capturing path + body. */
    private static final class StsFakeServer implements AutoCloseable {
        private final HttpServer server;

        StsFakeServer(int status, String responseBody, AtomicReference<String> path,
                      AtomicReference<String> body) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/config/sts/", exchange -> {
                path.set(exchange.getRequestURI().getPath());
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                if (status == 200) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                } else {
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override public void close() { server.stop(0); }
    }
}
