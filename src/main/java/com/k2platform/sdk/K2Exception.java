package com.k2platform.sdk;

/**
 * Thrown when a K2 config read fails — transport error, non-2xx response, or
 * a malformed body. {@link #getStatusCode()} is the HTTP status when one was
 * received, or {@code -1} for transport-level failures.
 */
public class K2Exception extends RuntimeException {

    private final int statusCode;

    public K2Exception(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public K2Exception(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    /** HTTP status of the failed response, or {@code -1} for transport errors. */
    public int getStatusCode() {
        return statusCode;
    }
}
