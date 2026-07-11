package com.k2platform.sdk.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a {@link K2Config} interface method to a K2 property key. If {@link #key()} is empty the
 * key is derived from the method name ({@code getXxx}/{@code isXxx} → {@code xxx},
 * otherwise camelCase → dotted), then prefixed with the interface's {@link K2Config#prefix()}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface K2ConfigProperty {

    /** Property key. Empty ⇒ derive from the method name. */
    String key() default "";

    /** Value returned when the property is absent in K2 (parsed to the method's return type). */
    String defaultValue() default "";

    /** Human-readable description (documentation only). */
    String description() default "";
}
