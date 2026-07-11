package com.k2platform.sdk;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/**
 * Signs {@code sts:GetCallerIdentity} with SigV4 from the workload's ambient AWS credentials
 * (design §1a), producing a request k2 relays to AWS STS. <b>No AWS credentials leave the process</b>
 * — only the signed request is sent, and k2 never sees the secret key.
 *
 * <p><b>Cloud-only.</b> Requires the optional {@code software.amazon.awssdk:auth} + {@code :regions}
 * dependencies and an ambient IAM identity (task/instance role, {@code AWS_*} env, or profile).
 * Region resolves from the AWS default chain ({@code AWS_REGION} / instance metadata); override with
 * the {@code region}-supplier constructor.
 */
public final class AwsStsIdentitySigner implements StsIdentitySigner {

    private static final String BODY = "Action=GetCallerIdentity&Version=2011-06-15";

    private final AwsCredentialsProvider credentialsProvider;
    private final Supplier<Region> region;

    public AwsStsIdentitySigner() {
        this(DefaultCredentialsProvider.create(), defaultRegion());
    }

    public AwsStsIdentitySigner(AwsCredentialsProvider credentialsProvider, Supplier<Region> region) {
        this.credentialsProvider = credentialsProvider;
        this.region = region;
    }

    @Override
    public SignedStsRequest signGetCallerIdentity() {
        try {
            Region r = region.get();
            URI uri = URI.create("https://sts." + r.id() + ".amazonaws.com/");
            SdkHttpFullRequest unsigned = SdkHttpFullRequest.builder()
                    .method(SdkHttpMethod.POST)
                    .uri(uri)
                    .putHeader("Content-Type", "application/x-www-form-urlencoded")
                    .contentStreamProvider(() -> new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8)))
                    .build();
            Aws4SignerParams params = Aws4SignerParams.builder()
                    .awsCredentials(credentialsProvider.resolveCredentials())
                    .signingName("sts")
                    .signingRegion(r)
                    .build();
            SdkHttpFullRequest signed = Aws4Signer.create().sign(unsigned, params);
            // Copy into a plain map so it serializes cleanly and is decoupled from the SDK request.
            Map<String, List<String>> headers = new LinkedHashMap<>(signed.headers());
            return new SignedStsRequest(signed.getUri().toString(), headers, BODY);
        } catch (NoClassDefFoundError e) {
            throw new K2Exception("STS auth is enabled but the AWS signing runtime is missing — add the "
                    + "optional dependencies software.amazon.awssdk:auth and :regions (this is a cloud-only "
                    + "credential). See SDK_AUTH_AND_OFFLINE_DESIGN.md §1a/§1c.", -1);
        } catch (RuntimeException e) {
            throw new K2Exception("Could not sign sts:GetCallerIdentity from ambient AWS credentials: "
                    + e.getMessage() + " — check the workload's IAM role and AWS_REGION.", e);
        }
    }

    private static Supplier<Region> defaultRegion() {
        return () -> {
            try {
                return DefaultAwsRegionProviderChain.builder().build().getRegion();
            } catch (RuntimeException e) {
                throw new K2Exception("No AWS region resolved for STS signing — set AWS_REGION.", e);
            }
        };
    }
}
