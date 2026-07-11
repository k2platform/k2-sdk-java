package com.k2platform.sdk;

import java.util.Base64;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;

/**
 * Decrypts the {@code K2_TOKEN_ENC} envelope via <b>AWS KMS</b> using the workload's ambient IAM
 * role (design §1b/§1c). The env var / CI variable holds only ciphertext (safe to commit-adjacent);
 * the plaintext token never leaves memory. Needs no secret store — just KMS + IAM the SMB already
 * has, and works cross-VPC/account (IAM is account-global).
 *
 * <p><b>Cloud-only.</b> Requires the optional {@code software.amazon.awssdk:kms} dependency and an
 * ambient principal allowed {@code kms:Decrypt}. A laptop with only the k2 URL cannot use this —
 * use a plaintext {@code K2_TOKEN} there instead (§1c). Region + credentials resolve from the AWS
 * default chain ({@code AWS_REGION}, task/instance role, {@code AWS_*} env, profile, IMDS).
 *
 * <p>Constructed lazily so merely having the SDK on the classpath (without the KMS dep) is fine;
 * the AWS types are touched only when a {@code K2_TOKEN_ENC} is actually resolved.
 */
public final class KmsTokenDecryptor implements TokenDecryptor {

    private volatile KmsClient client;

    @Override
    public String decrypt(String ciphertextBase64) {
        if (ciphertextBase64 == null || ciphertextBase64.isBlank()) {
            throw new K2Exception("K2_TOKEN_ENC is blank", -1);
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(ciphertextBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new K2Exception("K2_TOKEN_ENC is not valid base64: " + e.getMessage(), e);
        }
        try {
            DecryptResponse resp = kms().decrypt(DecryptRequest.builder()
                    .ciphertextBlob(SdkBytes.fromByteArray(blob))
                    .build());
            return resp.plaintext().asUtf8String().trim();
        } catch (NoClassDefFoundError e) {
            throw new K2Exception("K2_TOKEN_ENC is set but the AWS KMS runtime is missing — add the "
                    + "optional dependency software.amazon.awssdk:kms (this is a cloud-only credential; "
                    + "off-cloud, use a plaintext K2_TOKEN instead). See SDK_AUTH_AND_OFFLINE_DESIGN.md §1c.", -1);
        } catch (RuntimeException e) {
            throw new K2Exception("K2_TOKEN_ENC could not be decrypted via AWS KMS: " + e.getMessage()
                    + " — check the workload's IAM kms:Decrypt permission and AWS_REGION.", e);
        }
    }

    private KmsClient kms() {
        KmsClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = KmsClient.create(); // default region + credential provider chain
                    client = c;
                }
            }
        }
        return c;
    }
}
