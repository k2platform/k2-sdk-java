package com.k2platform.sdk.spring;

import com.k2platform.sdk.K2Client;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the shared {@link K2Client} bean from {@link K2Properties}. Used by the {@code @K2Config}
 * proxy (Mode 2) and available for direct injection. Only contributes a client when
 * {@code k2.base-url} is set, so apps that pull in the SDK without configuring it are unaffected.
 *
 * <p>Mode 1 (the PropertySource) does <em>not</em> depend on this — it runs in an
 * {@link K2EnvironmentPostProcessor} before beans exist.
 */
@AutoConfiguration
@EnableConfigurationProperties(K2Properties.class)
public class K2AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "k2", name = "base-url")
    public K2Client k2Client(K2Properties properties) {
        return K2Clients.from(properties);
    }
}
