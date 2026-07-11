package com.k2platform.sdk;

/**
 * Decrypts a {@code K2_TOKEN_ENC} ciphertext into the plaintext SDK token at boot (design §1b).
 * A seam so the KMS call ({@link KmsTokenDecryptor}) can be swapped in tests.
 */
@FunctionalInterface
public interface TokenDecryptor {

    /**
     * @param ciphertextBase64 base64 of the KMS-encrypted token blob (the value of {@code K2_TOKEN_ENC})
     * @return the plaintext SDK token
     * @throws K2Exception if decryption fails or the KMS runtime isn't available
     */
    String decrypt(String ciphertextBase64);
}
