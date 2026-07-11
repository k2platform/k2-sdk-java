package com.k2platform.sdk.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a typed, live view over K2 configuration (Mode 2). Each method maps to a
 * property key and reads through {@link com.k2platform.sdk.K2Client}'s cache on every call, so
 * values stay live without a Spring context refresh. Register the interfaces with
 * {@link EnableK2Config}.
 *
 * <pre>{@code
 * @K2Config(prefix = "feature")
 * public interface FeatureFlags {
 *     @K2ConfigProperty(key = "x", defaultValue = "false")
 *     boolean x();
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface K2Config {

    /** Prefix prepended (dot-joined) to every property key in this interface. */
    String prefix() default "";
}
