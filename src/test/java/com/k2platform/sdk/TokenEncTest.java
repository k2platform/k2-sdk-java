package com.k2platform.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** K2_TOKEN_ENC resolution (§1b) via an injected {@link TokenDecryptor} (no real KMS). */
class TokenEncTest {

    private static final String BODY = """
        { "organization":"acme","application":"billing","environment":"prod",
          "properties": { "db.url":"jdbc:postgresql://h/db" }, "propertyCount":1 }
        """;

    @Test
    void tokenEncIsDecryptedAndSentAsBearer() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl())
                    .tokenEnc("Y2lwaGVydGV4dA==")               // stand-in ciphertext
                    .tokenDecryptor(ct -> "tok_decrypted.secret") // fake KMS
                    .build();
            // A successful read proves the decrypted token was used for auth.
            assertEquals("jdbc:postgresql://h/db",
                    client.getConfiguration("prod").getString("db.url", null));
            assertEquals("/api/config/token/prod/current", server.lastPath());
        }
    }

    @Test
    void explicitTokenWinsOverTokenEnc() throws Exception {
        boolean[] decryptorCalled = { false };
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl())
                    .token("tok_explicit.secret")
                    .tokenEnc("ignored")
                    .tokenDecryptor(ct -> { decryptorCalled[0] = true; return "x"; })
                    .build();
            client.getConfiguration("prod");
            assertTrue(!decryptorCalled[0], "explicit token must short-circuit K2_TOKEN_ENC decryption");
        }
    }

    @Test
    void fileModeNeverTouchesTokenEnc() throws Exception {
        boolean[] decryptorCalled = { false };
        // FILE mode is fully offline — no server, no KMS — even if K2_TOKEN_ENC happens to be set.
        K2Client client = K2Client.builder()
                .source(K2Client.Source.FILE)
                .tokenEnc("ignored")
                .tokenDecryptor(ct -> { decryptorCalled[0] = true; return "x"; })
                .organization("acme").application("noapp")
                .fileSource(new K2ConfigFileSource())
                .build();
        assertTrue(!decryptorCalled[0], "FILE mode must not decrypt K2_TOKEN_ENC");
        // (no read attempted — building alone must not have called the decryptor)
    }
}
