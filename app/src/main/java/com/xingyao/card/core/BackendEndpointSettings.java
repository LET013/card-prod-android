package com.xingyao.card.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;

/**
 * Normalizes three independent LOCAL endpoint settings.
 *
 * These field names are an Android configuration model required by the product deployment where
 * HTTP and MQTT use different servers. They are not fields defined by /api/v1/device/config.
 * Legacy serverAddress/apiBaseUrl/mqttBrokerUrl are accepted only as local migration inputs.
 */
public final class BackendEndpointSettings {
    public static final String MODE_MQTT = "MQTT";
    public static final String MODE_HTTP = "HTTP";
    public static final String MODE_TCP = "TCP";

    private BackendEndpointSettings() { }

    public static JSONObject normalize(JSONObject source) throws JSONException {
        JSONObject result = source == null ? new JSONObject() : new JSONObject(source.toString());

        String mode = upper(result.optString("backendTransport", MODE_MQTT));
        if (!MODE_MQTT.equals(mode) && !MODE_HTTP.equals(mode) && !MODE_TCP.equals(mode)) {
            mode = MODE_MQTT;
        }
        result.put("backendTransport", mode);

        Endpoint http = parseEndpoint(
                firstNonBlank(result.optString("httpServerAddress", ""),
                        result.optString("apiBaseUrl", ""),
                        looksHttp(result.optString("serverAddress", ""))
                                ? result.optString("serverAddress", "") : ""),
                result.optString("httpScheme", ""),
                port(result.opt("httpPort"), 8082),
                result.optString("httpBasePath", ""));
        result.put("httpScheme", http.scheme)
                .put("httpServerAddress", http.host)
                .put("httpPort", http.port)
                .put("httpBasePath", http.path)
                .put("apiBaseUrl", http.asHttpBaseUrl());

        Endpoint mqtt = parseEndpoint(
                firstNonBlank(result.optString("mqttServerAddress", ""),
                        result.optString("mqttBrokerUrl", "")),
                result.optString("mqttScheme", ""),
                port(result.opt("mqttPort"), 0), "");
        result.put("mqttScheme", mqtt.scheme)
                .put("mqttServerAddress", mqtt.host)
                .put("mqttPort", mqtt.port)
                .put("mqttBrokerUrl", mqtt.asMqttUri());

        String legacyAddress = result.optString("serverAddress", "");
        String tcpInput = firstNonBlank(result.optString("tcpServerAddress", ""),
                looksHttp(legacyAddress) ? "" : legacyAddress);
        Endpoint tcp = parseEndpoint(tcpInput, "tcp", port(result.opt("tcpPort"), 9009), "");
        result.put("tcpServerAddress", tcp.host)
                .put("tcpPort", tcp.port)
                .put("serverAddress", tcp.host);
        return result;
    }

    public static String httpBaseUrl(JSONObject settings) {
        try { return normalize(settings).optString("apiBaseUrl", ""); }
        catch (JSONException ignored) { return ""; }
    }

    public static String mqttBrokerUrl(JSONObject settings) {
        try { return normalize(settings).optString("mqttBrokerUrl", ""); }
        catch (JSONException ignored) { return ""; }
    }

    public static String tcpHost(JSONObject settings) {
        try { return normalize(settings).optString("tcpServerAddress", ""); }
        catch (JSONException ignored) { return ""; }
    }

    private static Endpoint parseEndpoint(String rawValue, String configuredScheme,
                                          int configuredPort, String configuredPath) {
        String raw = rawValue == null ? "" : rawValue.trim();
        String scheme = lower(configuredScheme);
        String host = "";
        int resolvedPort = configuredPort;
        String path = normalizePath(configuredPath);

        if (!raw.isEmpty()) {
            try {
                boolean explicitScheme = raw.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
                String candidate = explicitScheme ? raw
                        : scheme.isEmpty() ? "//" + raw : scheme + "://" + raw;
                URI uri = URI.create(candidate);
                if (explicitScheme && uri.getScheme() != null) scheme = lower(uri.getScheme());
                host = uri.getHost();
                if (host == null || host.trim().isEmpty()) host = stripHost(raw);
                if (uri.getPort() > 0) resolvedPort = uri.getPort();
                if (path.isEmpty() && uri.getRawPath() != null) {
                    path = normalizePath(uri.getRawPath());
                }
            } catch (Exception ignored) {
                host = stripHost(raw);
            }
        }
        if ("mqtt".equals(scheme)) scheme = "tcp";
        if ("mqtts".equals(scheme)) scheme = "ssl";
        if (!"http".equals(scheme) && !"https".equals(scheme)
                && !"tcp".equals(scheme) && !"ssl".equals(scheme)) {
            scheme = "";
        }
        if (resolvedPort < 1 || resolvedPort > 65535) resolvedPort = 0;
        return new Endpoint(scheme, host == null ? "" : host.trim(), resolvedPort, path);
    }

    private static int port(Object value, int fallback) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return parsed >= 1 && parsed <= 65535 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean looksHttp(String value) {
        String raw = lower(value);
        return raw.startsWith("http://") || raw.startsWith("https://");
    }

    private static String stripHost(String value) {
        String result = value == null ? "" : value.trim();
        result = result.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slash = result.indexOf('/');
        if (slash >= 0) result = result.substring(0, slash);
        if (result.startsWith("[")) {
            int end = result.indexOf(']');
            return end > 0 ? result.substring(1, end) : result;
        }
        int colon = result.lastIndexOf(':');
        if (colon > 0 && result.indexOf(':') == colon) result = result.substring(0, colon);
        return result;
    }

    private static String normalizePath(String value) {
        String path = value == null ? "" : value.trim();
        if (path.isEmpty() || "/".equals(path)) return "";
        if (!path.startsWith("/")) path = "/" + path;
        return path.replaceAll("/+$", "");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.US);
    }

    private static final class Endpoint {
        final String scheme;
        final String host;
        final int port;
        final String path;

        Endpoint(String scheme, String host, int port, String path) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.path = path == null ? "" : path;
        }

        String asHttpBaseUrl() {
            if (host.isEmpty() || port < 1
                    || (!"http".equals(scheme) && !"https".equals(scheme))) return "";
            boolean standard = ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);
            return scheme + "://" + host + (standard ? "" : ":" + port) + path;
        }

        String asMqttUri() {
            if (host.isEmpty() || port < 1
                    || (!"tcp".equals(scheme) && !"ssl".equals(scheme))) return "";
            return scheme + "://" + host + ":" + port;
        }
    }
}
