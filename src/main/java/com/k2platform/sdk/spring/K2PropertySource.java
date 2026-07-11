package com.k2platform.sdk.spring;

import org.springframework.core.env.EnumerablePropertySource;

import java.util.Map;
import java.util.Set;

/**
 * Exposes a K2 configuration snapshot as a Spring {@link org.springframework.core.env.PropertySource}.
 * Inserted into the environment by {@link K2EnvironmentPostProcessor}; its placement decides whether
 * K2 overrides {@code application.yml} (it does, by default).
 */
final class K2PropertySource extends EnumerablePropertySource<Map<String, Object>> {

    static final String NAME = "k2-platform";

    K2PropertySource(Map<String, Object> source) {
        super(NAME, source);
    }

    @Override
    public Object getProperty(String name) {
        return source.get(name);
    }

    @Override
    public boolean containsProperty(String name) {
        return source.containsKey(name);
    }

    @Override
    public String[] getPropertyNames() {
        Set<String> keys = source.keySet();
        return keys.toArray(new String[0]);
    }
}
