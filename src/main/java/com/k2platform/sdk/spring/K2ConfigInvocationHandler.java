package com.k2platform.sdk.spring;

import com.k2platform.sdk.K2Client;
import com.k2platform.sdk.K2Configuration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Backs a {@link K2Config} proxy: resolves each invoked method to a property key, reads the
 * (cached) configuration for the client's default environment, and coerces the value to the
 * method's return type — falling back to {@link K2ConfigProperty#defaultValue()} when absent.
 */
final class K2ConfigInvocationHandler implements InvocationHandler {

    private final K2Client client;
    private final String prefix;

    K2ConfigInvocationHandler(K2Client client, String prefix) {
        this.client = client;
        this.prefix = prefix == null ? "" : prefix;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString" -> { return "K2Config proxy(prefix=" + prefix + ")"; }
            case "hashCode" -> { return System.identityHashCode(proxy); }
            case "equals"   -> { return proxy == (args == null ? null : args[0]); }
            default -> { /* fall through */ }
        }

        K2ConfigProperty ann = method.getAnnotation(K2ConfigProperty.class);
        String key = resolveKey(method, ann);
        Class<?> returnType = method.getReturnType();

        K2Configuration cfg = client.getCachedConfiguration();
        Object raw = cfg.get(key);
        String fallback = ann == null ? "" : ann.defaultValue();
        return coerce(raw, returnType, fallback);
    }

    private String resolveKey(Method method, K2ConfigProperty ann) {
        String base;
        if (ann != null && !ann.key().isEmpty()) {
            base = ann.key();
        } else {
            base = fromMethodName(method.getName());
        }
        return prefix.isEmpty() ? base : prefix + "." + base;
    }

    private static String fromMethodName(String name) {
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            return decapitalize(name.substring(2));
        }
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            return decapitalize(name.substring(3));
        }
        // camelCase → dotted (e.g. maxRetries → max.retries)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('.');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String decapitalize(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static Object coerce(Object raw, Class<?> type, String fallback) {
        String s = raw != null ? String.valueOf(raw) : (fallback == null ? "" : fallback);
        boolean absent = raw == null && (fallback == null || fallback.isEmpty());

        if (type == String.class) {
            return raw != null ? String.valueOf(raw) : (absent ? null : fallback);
        }
        if (type == boolean.class || type == Boolean.class) {
            if (raw instanceof Boolean b) return b;
            return absent ? (type == boolean.class ? false : null) : Boolean.parseBoolean(s.trim());
        }
        if (type == int.class || type == Integer.class) {
            if (raw instanceof Number n) return n.intValue();
            return absent ? (type == int.class ? 0 : null) : Integer.parseInt(s.trim());
        }
        if (type == long.class || type == Long.class) {
            if (raw instanceof Number n) return n.longValue();
            return absent ? (type == long.class ? 0L : null) : Long.parseLong(s.trim());
        }
        if (type == double.class || type == Double.class) {
            if (raw instanceof Number n) return n.doubleValue();
            return absent ? (type == double.class ? 0d : null) : Double.parseDouble(s.trim());
        }
        // Unsupported return type — hand back the raw value (or the default string).
        return raw != null ? raw : (absent ? null : fallback);
    }
}
