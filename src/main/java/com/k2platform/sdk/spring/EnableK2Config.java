package com.k2platform.sdk.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Scans for {@link K2Config} interfaces and registers a live proxy bean for each (Mode 2).
 * Place on a {@code @Configuration} / {@code @SpringBootApplication} class. With no packages
 * given, the annotated class's package is scanned.
 *
 * <p>Mode 1 (K2 as a high-precedence {@code PropertySource} replacing {@code application.yml})
 * needs no annotation — it activates automatically when this jar is on a Spring Boot classpath
 * and {@code k2.base-url}/{@code k2.token}/{@code k2.env} are set.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(K2ConfigurationRegistrar.class)
public @interface EnableK2Config {

    /** Base packages to scan for {@link K2Config} interfaces. */
    String[] basePackages() default {};

    /** Type-safe alternative to {@link #basePackages()} — their packages are scanned. */
    Class<?>[] basePackageClasses() default {};
}
