package com.k2platform.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local file config source (§3b) + resolution modes. */
class K2ConfigFileSourceTest {

    private static final String DOC = """
        {
          "org": "acme",
          "app": "test-app",
          "environments": {
            "dev":  { "app.name": "test11",  "app.code": "ABC123", "app.status": 4 },
            "prod": { "app.name": "prod-11", "app.code": "XYZ999", "app.status": 1 }
          }
        }
        """;

    private static Path writeDoc(Path dir, String app, String body) throws Exception {
        Path f = dir.resolve(app + ".k2.json");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void readsPerEnvBlock(@TempDir Path dir) throws Exception {
        writeDoc(dir, "test-app", DOC);
        K2ConfigFileSource fs = new K2ConfigFileSource(new ObjectMapper(), dir.toString(), null);

        var prod = fs.load("acme", "test-app", "prod");
        assertEquals("prod-11", prod.get("app.name"));
        assertEquals("XYZ999", prod.get("app.code"));
        assertEquals(1, prod.get("app.status"));
    }

    @Test
    void hasFileForReflectsPresence(@TempDir Path dir) throws Exception {
        K2ConfigFileSource fs = new K2ConfigFileSource(new ObjectMapper(), dir.toString(), null);
        assertTrue(!fs.hasFileFor("test-app"));
        writeDoc(dir, "test-app", DOC);
        assertTrue(fs.hasFileFor("test-app"));
    }

    @Test
    void missingEnvBlockReturnsNull(@TempDir Path dir) throws Exception {
        writeDoc(dir, "test-app", DOC);
        K2ConfigFileSource fs = new K2ConfigFileSource(new ObjectMapper(), dir.toString(), null);
        assertNull(fs.load("acme", "test-app", "staging"));
    }

    @Test
    void mismatchedAppFailsLoudly(@TempDir Path dir) throws Exception {
        writeDoc(dir, "other-app", DOC); // filename other-app, but doc says app=test-app
        K2ConfigFileSource fs = new K2ConfigFileSource(new ObjectMapper(), dir.toString(), null);
        K2Exception ex = assertThrows(K2Exception.class, () -> fs.load("acme", "other-app", "dev"));
        assertTrue(ex.getMessage().contains("does not match"));
    }

    @Test
    void mismatchedOrgFailsLoudly(@TempDir Path dir) throws Exception {
        writeDoc(dir, "test-app", DOC);
        K2ConfigFileSource fs = new K2ConfigFileSource(new ObjectMapper(), dir.toString(), null);
        K2Exception ex = assertThrows(K2Exception.class, () -> fs.load("wrongco", "test-app", "dev"));
        assertTrue(ex.getMessage().contains("does not match"));
    }

    @Test
    void explicitFilePointerIgnoresAppName(@TempDir Path dir) throws Exception {
        Path f = writeDoc(dir, "weird-name", DOC);
        K2ConfigFileSource fs = new K2ConfigFileSource(new ObjectMapper(), null, f.toString());
        assertTrue(fs.hasFileFor(null));
        var dev = fs.load("acme", "test-app", "dev");
        assertEquals("test11", dev.get("app.name"));
    }

    // ---- resolution modes via K2Client (no server) ----

    @Test
    void fileModeReadsWithoutServer(@TempDir Path dir) throws Exception {
        writeDoc(dir, "test-app", DOC);
        K2Client client = K2Client.builder()
                .source(K2Client.Source.FILE)
                .organization("acme").application("test-app")
                .fileSource(new K2ConfigFileSource(new ObjectMapper(), dir.toString(), null))
                .build(); // note: no baseUrl / token — FILE mode is fully offline
        K2Configuration cfg = client.getConfiguration("dev");
        assertEquals("test11", cfg.getString("app.name", null));
        assertEquals("acme", cfg.getOrganization());
        assertEquals("test-app", cfg.getApplication());
    }

    @Test
    void fileModeMissingEnvFailsFast(@TempDir Path dir) throws Exception {
        writeDoc(dir, "test-app", DOC);
        K2Client client = K2Client.builder()
                .source(K2Client.Source.FILE)
                .organization("acme").application("test-app")
                .fileSource(new K2ConfigFileSource(new ObjectMapper(), dir.toString(), null))
                .build();
        K2Exception ex = assertThrows(K2Exception.class, () -> client.getConfiguration("nope"));
        assertTrue(ex.getMessage().contains("no config for env"));
    }

    @Test
    void autoModeFallsThroughToServerWhenNoFile(@TempDir Path dir) throws Exception {
        // No file written → AUTO must ignore the file source and hit the server.
        String body = """
            { "organization":"acme","application":"test-app","environment":"dev",
              "properties": { "app.name":"from-server" } }
            """;
        try (FakeK2Server server = new FakeK2Server(body)) {
            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl()).token("t")
                    .source(K2Client.Source.AUTO)
                    .organization("acme").application("test-app")
                    .fileSource(new K2ConfigFileSource(new ObjectMapper(), dir.toString(), null))
                    .build();
            assertEquals("from-server", client.getConfiguration("dev").getString("app.name", null));
            assertEquals(1, server.hits());
        }
    }

    @Test
    void autoModePrefersFileOverServer(@TempDir Path dir) throws Exception {
        writeDoc(dir, "test-app", DOC);
        String body = """
            { "organization":"acme","application":"test-app","environment":"dev",
              "properties": { "app.name":"from-server" } }
            """;
        try (FakeK2Server server = new FakeK2Server(body)) {
            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl()).token("t")
                    .source(K2Client.Source.AUTO)
                    .organization("acme").application("test-app")
                    .fileSource(new K2ConfigFileSource(new ObjectMapper(), dir.toString(), null))
                    .build();
            assertEquals("test11", client.getConfiguration("dev").getString("app.name", null));
            assertEquals(0, server.hits(), "AUTO with a present file must not contact the server");
        }
    }
}
