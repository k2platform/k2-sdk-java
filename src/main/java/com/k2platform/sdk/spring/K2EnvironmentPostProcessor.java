package com.k2platform.sdk.spring;

import com.k2platform.sdk.K2Client;
import com.k2platform.sdk.K2ConfigFileSource;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Mode 1 — layers K2 into Spring's {@code Environment} as a high-precedence
 * {@link com.k2platform.sdk.spring.K2PropertySource} so K2 *replaces* {@code application.yml} as the
 * config source of record: existing {@code @Value}/{@code @ConfigurationProperties} resolve from K2
 * first, with {@code application.yml} as fallback.
 *
 * <p>Activates only when {@code k2.base-url} (+ token + env) is set and the property source is
 * enabled — so merely having the SDK on the classpath does nothing until configured. Runs after
 * ConfigData so {@code application.yml} is loaded (its {@code k2.*} bootstrap is readable) and the
 * application config property sources exist to be positioned against. Never crashes boot: a fetch
 * failure (with no offline cache) just leaves {@code application.yml} in charge.
 */
public class K2EnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final Log log;

    public K2EnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(K2EnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        // After ConfigData has contributed application.yml/.properties.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Contribute the optional coordinates-only k2.yml bootstrap (working dir, then classpath)
        // at lowest precedence, so K2_* env vars / application.yml still win, before binding k2.*.
        loadK2Yml(environment);

        K2Properties props = Binder.get(environment)
                .bind("k2", K2Properties.class)
                .orElseGet(K2Properties::new);

        if (!props.getPropertySource().isEnabled()) {
            log.debug("K2 PropertySource disabled (k2.property-source.enabled=false)");
            return;
        }

        boolean fileMode = willUseFileSource(props);
        boolean stsMode = !fileMode && props.getSts().isEnabled();

        if (stsMode) {
            if (!StringUtils.hasText(props.getBaseUrl())) {
                log.debug("k2.base-url not set — K2 PropertySource not registered");
                return;
            }
            if (!StringUtils.hasText(props.getEnv()) || !StringUtils.hasText(props.getApp())) {
                log.warn("K2 STS auth needs k2.env / K2_ENV and k2.app / K2_APP (STS is org-scoped) — skipping");
                return;
            }
        } else if (!fileMode) {
            if (!StringUtils.hasText(props.getBaseUrl())) {
                log.debug("k2.base-url not set (and no local file source) — K2 PropertySource not registered");
                return;
            }
            if (!props.hasCredential() || !StringUtils.hasText(props.getEnv())) {
                log.warn("k2.base-url is set but a credential / k2.env is missing — skipping K2 PropertySource "
                        + "(set K2_TOKEN, K2_TOKEN_FILE or K2_TOKEN_ENC, and k2.env)");
                return;
            }
        } else {
            if (!StringUtils.hasText(props.getEnv())) {
                log.warn("K2 file source needs k2.env / K2_ENV — skipping K2 PropertySource");
                return;
            }
            if (!StringUtils.hasText(props.getApp()) && !StringUtils.hasText(props.getConfigFile())) {
                log.warn("K2 file source needs k2.app / K2_APP (the k2 slug) or k2.config-file — skipping");
                return;
            }
        }

        try {
            K2Client client = K2Clients.from(props);
            Map<String, Object> snapshot = client.snapshot(props.getEnv());
            K2PropertySource source = new K2PropertySource(snapshot);
            insert(environment.getPropertySources(), source, props.getPropertySource().getPrecedence());
            String via = fileMode ? "local file source" : stsMode ? "server (STS identity)" : "server";
            log.info("K2 PropertySource registered: " + snapshot.size() + " keys for env '"
                    + props.getEnv() + "' via " + via
                    + " (precedence " + props.getPropertySource().getPrecedence() + ")");
        } catch (RuntimeException ex) {
            log.warn("Could not load K2 configuration (" + ex.getMessage()
                    + ") — continuing with application.yml defaults");
        }
    }

    /** Whether resolution will read the local file source ({@code source=file}, or {@code auto} with a file present). */
    private boolean willUseFileSource(K2Properties props) {
        switch (props.getSource()) {
            case SERVER:
                return false;
            case FILE:
                return true;
            case AUTO:
            default:
                K2ConfigFileSource fs = new K2ConfigFileSource(
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        props.getConfigDir(), props.getConfigFile());
                return StringUtils.hasText(props.getConfigFile()) || fs.hasFileFor(props.getApp());
        }
    }

    private void loadK2Yml(ConfigurableEnvironment environment) {
        if (environment.getPropertySources().contains("k2.yml")) {
            return; // already contributed (idempotent across repeated EPP runs)
        }
        Resource resource = firstReadable(
                new FileSystemResource("k2.yml"),
                new FileSystemResource("k2.yaml"),
                new ClassPathResource("k2.yml"),
                new ClassPathResource("k2.yaml"));
        if (resource == null) {
            return;
        }
        try {
            List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load("k2.yml", resource);
            // Lowest precedence: env vars, -D, and application.yml all override these bootstrap coordinates.
            for (PropertySource<?> ps : loaded) {
                environment.getPropertySources().addLast(ps);
            }
            if (!loaded.isEmpty()) {
                log.debug("Loaded K2 bootstrap coordinates from " + resource.getDescription());
            }
        } catch (Exception ex) {
            log.warn("Could not read k2.yml bootstrap (" + ex.getMessage() + ") — ignoring");
        }
    }

    private static Resource firstReadable(Resource... candidates) {
        for (Resource r : candidates) {
            if (r.isReadable()) {
                return r;
            }
        }
        return null;
    }

    private void insert(MutablePropertySources sources, K2PropertySource source,
                        K2Properties.Precedence precedence) {
        if (precedence == K2Properties.Precedence.HIGHEST) {
            sources.addFirst(source);
            return;
        }
        // ABOVE_APPLICATION_YAML: sit just before the first application config source so that
        // -D system properties and OS env (which come earlier) still override K2.
        String anchor = firstApplicationConfigSourceName(sources);
        if (anchor != null) {
            sources.addBefore(anchor, source);
        } else {
            sources.addLast(source);
        }
    }

    private String firstApplicationConfigSourceName(MutablePropertySources sources) {
        for (PropertySource<?> ps : sources) {
            String name = ps.getName();
            if (name == null) continue;
            // Spring Boot 3 ConfigData names look like
            // "Config resource 'class path resource [application.yml]' via location ...";
            // the legacy name was "applicationConfig: [classpath:/application.yml]".
            if (name.startsWith("Config resource")
                    || name.contains("applicationConfig")
                    || name.contains("application.yml")
                    || name.contains("application.properties")) {
                return name;
            }
        }
        return null;
    }
}
