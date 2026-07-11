package com.k2platform.sdk;

import java.util.List;
import java.util.Map;

/**
 * Produces a signed {@code sts:GetCallerIdentity} request the SDK sends to k2, which relays it to
 * AWS STS to prove the workload's identity (design §1a). A seam so the AWS signing
 * ({@link AwsStsIdentitySigner}) can be swapped in tests.
 */
@FunctionalInterface
public interface StsIdentitySigner {

    /** Sign a {@code GetCallerIdentity} request from the ambient AWS credentials. */
    SignedStsRequest signGetCallerIdentity();

    /**
     * The signed request, relayed verbatim to AWS STS by k2. Field names match the server's
     * {@code StsVerificationService.SignedStsRequest} so it deserializes directly.
     */
    record SignedStsRequest(String url, Map<String, List<String>> headers, String body) {}
}
