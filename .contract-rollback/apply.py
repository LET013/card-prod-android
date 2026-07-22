from pathlib import Path
import re

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, value):
    (ROOT / path).write_text(value, encoding='utf-8')


def replace_once(path, old, new):
    value = read(path)
    count = value.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one occurrence, found {count}: {old[:100]!r}')
    write(path, value.replace(old, new, 1))


def replace_block(path, start, end, new):
    value = read(path)
    left = value.find(start)
    if left < 0:
        raise RuntimeError(f'{path}: start marker not found: {start!r}')
    right = value.find(end, left + len(start))
    if right < 0:
        raise RuntimeError(f'{path}: end marker not found: {end!r}')
    write(path, value[:left] + new + value[right:])


# ---------------------------------------------------------------------------
# Local endpoint configuration: keep the user-required independent endpoints,
# but never invent schemes, MQTT ports, or backend config aliases.
# ---------------------------------------------------------------------------
write('app/src/main/java/com/xingyao/card/core/BackendEndpointSettings.java', r'''package com.xingyao.card.core;

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
''')

write('app/src/main/java/com/xingyao/card/core/DeviceConfigMapper.java', r'''package com.xingyao.card.core;

import org.json.JSONException;
import org.json.JSONObject;

/** Applies only fields explicitly defined by V4.1 GET /api/v1/device/config. */
public final class DeviceConfigMapper {
    private DeviceConfigMapper() { }

    public static JSONObject apply(JSONObject localSettings, JSONObject remoteConfig)
            throws JSONException {
        JSONObject settings = localSettings == null ? new JSONObject()
                : new JSONObject(localSettings.toString());
        JSONObject config = remoteConfig == null ? new JSONObject() : remoteConfig;

        if (config.has("baudRate")) settings.put("baudRate",
                String.valueOf(config.optInt("baudRate", 57600)));
        if (config.has("groupSize")) settings.put("singleGroupCount",
                positive(config.optInt("groupSize"), settings.optInt("singleGroupCount", 16)));
        if (config.has("totalSlots")) settings.put("totalCount",
                positive(config.optInt("totalSlots"), settings.optInt("totalCount", 100)));
        if (config.has("pollingInterval")) settings.put("serialPollingIntervalMs",
                positive(config.optInt("pollingInterval"),
                        settings.optInt("serialPollingIntervalMs", 5000)));
        if (config.has("tcpPort")) settings.put("tcpPort",
                validPort(config.optInt("tcpPort"), settings.optInt("tcpPort", 9009)));
        if (config.has("httpPort")) settings.put("httpPort",
                validPort(config.optInt("httpPort"), settings.optInt("httpPort", 8082)));
        if (config.has("faceThreshold")) settings.put("faceRecognitionThreshold",
                bounded(config.optDouble("faceThreshold"),
                        settings.optDouble("faceRecognitionThreshold", 0.8)));
        if (config.has("fingerThreshold")) settings.put("fingerRecognitionThreshold",
                bounded(config.optDouble("fingerThreshold"), 0.8));

        String mode = config.optString("communicationMode", "").trim().toUpperCase();
        if (BackendEndpointSettings.MODE_MQTT.equals(mode)
                || BackendEndpointSettings.MODE_HTTP.equals(mode)) {
            settings.put("backendTransport", mode);
        }

        // V4.1 defines only one generic serverIp while this deployment requires different HTTP
        // and MQTT servers. Store it for diagnostics only; do not guess which local endpoint it owns.
        if (config.has("serverIp")) {
            settings.put("backendServerIp", config.optString("serverIp", ""));
        }
        settings.put("remoteConfigUpdatedAt", System.currentTimeMillis());
        return BackendEndpointSettings.normalize(settings);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int validPort(int value, int fallback) {
        return value >= 1 && value <= 65535 ? value : fallback;
    }

    private static double bounded(double value, double fallback) {
        return value >= 0D && value <= 1D ? value : fallback;
    }
}
''')

# Native settings: no undocumented test servers, MQTT port/scheme, card-number mode, or topology.
path = 'app/src/main/java/com/xingyao/card/core/NativeSettingsRepository.java'
replace_once(path,
'''            "deviceToken", "runtimeToken", "mqttPassword", "signingKey", "machineId", "clientId",''',
'''            "deviceToken", "runtimeToken", "mqttPassword", "signingKey", "machineId", "deviceId", "clientId",''')
for old, new in [
    ('.put("serialPollingEnabled", true)', '.put("serialPollingEnabled", false)'),
    ('.put("serialResponseTimeoutMs", 1500)', '.put("serialResponseTimeoutMs", 100)'),
    ('.put("httpScheme", "http")', '.put("httpScheme", "")'),
    ('.put("httpServerAddress", "card-test.quyohui.com")', '.put("httpServerAddress", "")'),
    ('.put("httpPort", 80)', '.put("httpPort", 8082)'),
    ('.put("mqttScheme", "tcp")', '.put("mqttScheme", "")'),
    ('.put("mqttServerAddress", "119.146.88.108")', '.put("mqttServerAddress", "")'),
    ('.put("mqttPort", 48419)', '.put("mqttPort", 0)'),
    ('.put("cameraRotation", 90)', '.put("cameraRotation", 270)'),
]:
    replace_once(path, old, new)

# HTTP Bearer remains deviceToken exactly as V4.1 documents. The HTTP login token is retained only
# as returned data until the backend documents where it must be supplied.
path = 'app/src/main/java/com/xingyao/card/core/BackendHttpGateway.java'
replace_once(path,
'''        String token = "";
        if (withToken) {
            token = settings.optString("runtimeToken", "").trim();
            if (token.isEmpty()) token = settings.optString("deviceToken", "");
        }
        return BackendHttpClient.downloadBytes(absoluteUrl, token);''',
'''        String token = withToken ? settings.optString("deviceToken", "") : "";
        return BackendHttpClient.downloadBytes(absoluteUrl, token);''')
replace_once(path,
'''        boolean runtimeTokenReady = !settings.optString("runtimeToken", "").isEmpty();''',
'''        boolean httpLoginTokenPresent = !settings.optString("runtimeToken", "").isEmpty();''')
replace_once(path,
'''                .put("runtimeTokenReady", runtimeTokenReady)''',
'''                .put("httpLoginTokenPresent", httpLoginTokenPresent)''')
replace_once(path,
'''    private BackendHttpClient runtimeClient(JSONObject settings) {
        String token = settings.optString("runtimeToken", "").trim();
        if (token.isEmpty()) token = settings.optString("deviceToken", "");
        return client(settings, token);
    }''',
'''    private BackendHttpClient runtimeClient(JSONObject settings) {
        return deviceClient(settings);
    }''')

# Provisioning: expireTime is documented as reference only; MQTT username is not documented.
path = 'app/src/main/java/com/xingyao/card/core/DeviceProvisioningManager.java'
replace_once(path,
'''                || credentialsExpired(settings)
                || (mqttRequested && !hasMqttCredentials(settings));''',
'''                || (mqttRequested && !hasMqttCredentials(settings));''')
replace_once(path,
'''        merge(settings, data, "mqttPassword", "signingKey", "clientId", "expireTime",
                "deviceName", "deviceCode", "mqttUsername");''',
'''        merge(settings, data, "mqttPassword", "signingKey", "clientId", "expireTime",
                "deviceName", "deviceCode");''')
replace_once(path,
'''        boolean authorized = data.optBoolean("authorized", false);
        return new JSONObject().put("state", authorized ? "AUTHORIZED" : "UNAUTHORIZED")
                .put("authorized", authorized)
                .put("authExpireTime", data.optLong("authExpireTime", 0L))
                .put("authType", data.optString("authType", ""))
                .put("message", authorized ? "设备授权有效" : "设备未授权或授权已过期");''',
'''        boolean authorized = data.optBoolean("authorized", false);
        return new JSONObject().put("state", authorized ? "AUTHORIZED" : "UNAUTHORIZED")
                .put("authorized", authorized)
                .put("authorizedUntil", data.optLong("authorizedUntil", 0L))
                .put("daysRemaining", data.optLong("daysRemaining", 0L))
                .put("features", data.optJSONArray("features") == null
                        ? new org.json.JSONArray() : data.optJSONArray("features"))
                .put("message", authorized ? "设备授权有效" : "设备未授权或授权已过期");''')
value = read(path)
value = re.sub(r'\n    private static boolean credentialsExpired\(JSONObject settings\) \{.*?\n    \}', '', value, count=1, flags=re.S)
write(path, value)

# MQTT: never synthesize a clientId or guess a username. Optional login IP is omitted rather than
# hardcoded to loopback.
path = 'app/src/main/java/com/xingyao/card/core/WebSocketConnectionManager.java'
replace_once(path,
'''        String configuredClientId = optString(settings, "mqttClientId",
                optString(settings, "clientId", "")).trim();
        String machineId = optString(settings, "machineId", "").trim();
        clientId = configuredClientId.isEmpty()
                ? "device_" + (machineId.isEmpty() ? deviceCode : machineId) : configuredClientId;''',
'''        clientId = optString(settings, "mqttClientId",
                optString(settings, "clientId", "")).trim();''')
replace_once(path,
'''    private List<String> mqttUsernameCandidates() {
        ArrayList<String> candidates = new ArrayList<>();
        if (mqttUsernameConfigured) addUnique(candidates, mqttUsername);
        // V4.1 does not define a username field. These compatibility candidates are retained until
        // the broker credential contract explicitly identifies the username.
        addUnique(candidates, deviceCode);
        addUnique(candidates, clientId);
        addUnique(candidates, "");
        return candidates;
    }''',
'''    private List<String> mqttUsernameCandidates() {
        ArrayList<String> candidates = new ArrayList<>();
        // V4.1 does not define MQTT username. Use an explicitly configured value only; otherwise
        // connect without a username and record the missing contract if the broker rejects it.
        addUnique(candidates, mqttUsernameConfigured ? mqttUsername : "");
        return candidates;
    }''')
replace_once(path,
'''        if (value.equals(deviceCode)) return "deviceCode";
        if (value.equals(clientId)) return "clientId";
        return "configured";''',
'''        return "configured";''')
replace_once(path,
'''        send(new JSONObject().put("cmd", "login")
                .put("data", new JSONObject().put("version", BuildConfig.VERSION_NAME)
                        .put("ip", "127.0.0.1")));''',
'''        send(new JSONObject().put("cmd", "login")
                .put("data", new JSONObject().put("version", BuildConfig.VERSION_NAME)));''')

# Downlink contract requires msgId/cmd/timestamp but defines no rejection window or stale-processing
# timeout. Keep idempotency, but do not manufacture time policy.
path = 'app/src/main/java/com/xingyao/card/core/InboundCommandRepository.java'
replace_once(path, '    private static final long DEFAULT_REPLAY_WINDOW_MS = 10L * 60L * 1000L;\n', '')
replace_once(path, '    private static final long STALE_PROCESSING_MS = 5L * 60L * 1000L;\n', '')
replace_once(path,
'''        if (Math.abs(now - timestamp) > DEFAULT_REPLAY_WINDOW_MS) {
            return rejected("STALE_COMMAND", "后台指令时间戳超出允许窗口", msgId);
        }

''', '')
replace_once(path,
'''            long updatedAt = existing.optLong("updatedAt", existing.optLong("receivedAt", 0L));
            if (updatedAt > 0L && now - updatedAt > STALE_PROCESSING_MS) {
                return rejected("RECOVERY_REQUIRED",
                        "指令在上次进程中未完成，禁止自动重复副作用，请查询硬件状态后人工恢复", msgId);
            }
''', '')

# Serial topology: neither modulo nor direct slotId->slaveAddress is documented. Disable logical
# polling/actions until a real mapping or group-select protocol is provided. ASCII card number is the
# only documented parse.
path = 'app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java'
replace_once(path, '    private static final long RESPONSE_TIMEOUT_MS = 1500L;',
             '    private static final long RESPONSE_TIMEOUT_MS = 100L;')
replace_once(path, '    private static final long POLLING_INTERVAL_MS = 1200L;',
             '    private static final long POLLING_INTERVAL_MS = 5000L;')
replace_once(path,
'''        pollingEnabled = settings != null && settings.optBoolean("serialPollingEnabled", true);
        cardNumberMode = settings == null ? "VISIBLE"
                : settings.optString("cardNumberMode", "VISIBLE").trim().toUpperCase(Locale.US);
        if (!"PHYSICAL".equals(cardNumberMode)) cardNumberMode = "VISIBLE";''',
'''        pollingEnabled = false;
        cardNumberMode = "VISIBLE";''')
replace_once(path,
'''    public synchronized JSONObject setPollingEnabled(boolean enabled) throws JSONException {
        pollingEnabled = enabled;''',
'''    public synchronized JSONObject setPollingEnabled(boolean enabled) throws JSONException {
        if (enabled) {
            throw new IllegalStateException("SERIAL_TOPOLOGY_UNCONFIRMED：文档未定义逻辑卡位到从机地址的映射");
        }
        pollingEnabled = false;''')
replace_block(path,
'''    public JSONObject openAllDoors(boolean administrator) throws Exception {''',
'''    public synchronized JSONObject snapshot() throws JSONException {''',
'''    public JSONObject openAllDoors(boolean administrator) {
        throw new IllegalStateException(
                "SERIAL_TOPOLOGY_UNCONFIRMED：无法在未知地址拓扑下执行一键弹卡");
    }

''')
replace_once(path, '.put("addressMode", "DIRECT")', '.put("addressMode", "UNCONFIRMED")')
replace_once(path,
'''            int addressLimit = pollingAddressLimit();
            int address = Math.min(nextAddress, addressLimit);''',
'''            int addressLimit = pollingAddressLimit();
            if (addressLimit < 1) return;
            int address = Math.min(nextAddress, addressLimit);''')
replace_once(path,
'''            if (frame.function == WorkCardProtocol.FUNCTION_QUERY) notifySlot(parseSlotStatus(frame));
            else if (frame.function == WorkCardProtocol.FUNCTION_OPEN_DOOR)''',
'''            if (frame.function == WorkCardProtocol.FUNCTION_QUERY) {
                JSONObject unmapped = parseSlotStatus(frame);
                notifyData(new JSONObject().put("type", "unmappedBoardStatus")
                        .put("boardAddress", frame.slaveAddress).put("status", unmapped));
            } else if (frame.function == WorkCardProtocol.FUNCTION_OPEN_DOOR)''')
replace_once(path,
'''        String rawCardHex = WorkCardProtocol.hex(cardBytes);
        String cardNo = "PHYSICAL".equals(cardNumberMode) ? rawCardHex
                : new String(cardBytes, StandardCharsets.US_ASCII).replace("\\u0000", "").trim();''',
'''        String rawCardHex = WorkCardProtocol.hex(cardBytes);
        String cardNo = new String(cardBytes, StandardCharsets.US_ASCII)
                .replace("\\u0000", "").trim();''')
replace_once(path,
'''    private int serialAddressForSlot(int slotNumber) {
        if (slotNumber < 1 || slotNumber > 255) {
            throw new IllegalArgumentException("V1.5从机地址必须在1至255之间");
        }
        return slotNumber;
    }
    private int pollingAddressLimit() { return Math.max(1, Math.min(totalSlots, 255)); }''',
'''    private int serialAddressForSlot(int slotNumber) {
        throw new IllegalStateException(
                "SERIAL_TOPOLOGY_UNCONFIRMED：文档只定义从机地址，未定义slotId到从机地址的对应关系");
    }
    private int pollingAddressLimit() { return 0; }''')

# Remote command wire format: execute only documented downlink commands and send only documented
# response/event fields. TAKE/RETURN is not sent at board ACK.
path = 'app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java'
replace_once(path,
'''                cached.put("duplicate", true).put("replayed", true);
                send(cached);''',
'''                if (!isLogToggle(cmd)) send(cached);''')
replace_once(path,
'''                processing.put("code", 202).put("status", "PROCESSING")
                        .put("msg", "相同指令正在处理中").put("duplicate", true);''',
'''                processing.put("code", 202).put("msg", "相同指令正在处理中");''')
replace_once(path,
'''                rejected.put("code", 4001).put("status", "REJECTED")
                        .put("errorCode", begin.code).put("msg", begin.message);''',
'''                rejected.put("code", 4001).put("msg", begin.message);''')
replace_once(path,
'''                case "queryStatus": handleQueryStatus(safeCommand); break;
                case "syncUser": handleSync(safeCommand, "all"); break;
                case "syncEmployeeData": handleSync(safeCommand, "employees"); break;
                case "syncFaceData": handleSync(safeCommand, "faces"); break;
                case "syncFingerData": handleSync(safeCommand, "fingers"); break;''',
'''                case "syncUser": handleSync(safeCommand, "all"); break;''')
replace_once(path,
'''                    complete(safeCommand, baseResponse(safeCommand, cmd.isEmpty() ? "commandResp" : cmd + "Resp")
                            .put("code", 9000).put("status", "UNSUPPORTED")
                            .put("msg", "unsupported command"), false);''',
'''                    complete(safeCommand, baseResponse(safeCommand,
                            cmd.isEmpty() ? "commandResp" : cmd + "Resp")
                            .put("code", 9000).put("msg", "unsupported command"), false);''')
replace_once(path,
'''                JSONObject response = baseResponse(safeCommand, cmd.isEmpty() ? "commandResp" : cmd + "Resp")
                        .put("code", 9000).put("status", "FAILED")
                        .put("msg", safeMessage(error));
                if (error instanceof DeviceOperationEngine.OperationException) {
                    DeviceOperationEngine.OperationException operationError =
                            (DeviceOperationEngine.OperationException) error;
                    response.put("operationId", operationError.getOperationId())
                            .put("errorCode", operationError.getFailureCode());
                }''',
'''                JSONObject response = baseResponse(safeCommand,
                        cmd.isEmpty() ? "commandResp" : cmd + "Resp")
                        .put("code", 9000).put("msg", safeMessage(error));''')
replace_block(path,
'''    public void reportCardEvent(int slotId, String eventType, String authType,''',
'''    public void reportSlotSnapshot() {''',
'''    public void reportCardEvent(int slotId, String eventType, String authType,
                                String operationId, String requestMsgId, String employeeId) {
        try {
            stateStore.record("card.event.awaitingPhysicalConfirmation", new JSONObject()
                    .put("slotId", slotId)
                    .put("eventType", safe(eventType))
                    .put("authType", safe(authType))
                    .put("operationId", safe(operationId))
                    .put("requestMsgId", safe(requestMsgId))
                    .put("employeeId", safe(employeeId)));
        } catch (Exception ignored) { }
    }

''')
replace_block(path,
'''    public void reportSlotSnapshot() {''',
'''    public synchronized void reportHardwareFault(JSONObject slot) {''',
'''    public void reportSlotSnapshot() {
        if (!backendPort.isAuthenticated()) return;
        try {
            JSONArray source = stateStore.backendSlots();
            JSONArray known = new JSONArray();
            JSONArray nativeSlots = stateStore.slotsSnapshot().optJSONArray("slots");
            for (int index = 0; nativeSlots != null && index < nativeSlots.length(); index++) {
                JSONObject nativeSlot = nativeSlots.getJSONObject(index);
                if (nativeSlot.optLong("updatedAt", 0L) > 0L && index < source.length()) {
                    known.put(source.getJSONObject(index));
                }
            }
            if (known.length() == 0) return;
            send(new JSONObject().put("cmd", "statusReport")
                    .put("data", new JSONObject().put("slots", known)));
        } catch (Exception error) {
            stateStore.record("backend.status.report.failed", message(error));
        }
    }

''')
replace_block(path,
'''    public synchronized void reportHardwareFault(JSONObject slot) {''',
'''    private void handleRemoteOpen(JSONObject command) throws Exception {''',
'''    public synchronized void reportHardwareFault(JSONObject slot) {
        if (slot == null) return;
        int slotId = slot.optInt("slotNumber", -1);
        if (slotId < 1) return;
        String status = slot.optString("status", "");
        String faultCode = slot.optString("faultCode", "");
        boolean hasFault = "CHARGING_FAULT".equals(status)
                || "COMMUNICATION_FAULT".equals(status)
                || "ILLEGAL_CARD".equals(status)
                || !faultCode.trim().isEmpty();
        String previous = activeFaults.get(slotId);
        if (!hasFault) {
            if (previous != null) {
                activeFaults.remove(slotId);
                try {
                    stateStore.record("hardware.fault.cleared", new JSONObject()
                            .put("slotId", slotId).put("previous", previous));
                } catch (Exception ignored) { }
            }
            return;
        }
        String signature = status + "|" + faultCode + "|"
                + slot.optString("faultMessage", "");
        if (signature.equals(previous)) return;
        activeFaults.put(slotId, signature);
        try {
            JSONObject mqttData = new JSONObject()
                    .put("slotId", slotId)
                    .put("faultCode", parseFaultCode(faultCode))
                    .put("faultMsg", slot.optString("faultMessage", status))
                    .put("timestamp", System.currentTimeMillis());
            if (BackendEndpointSettings.MODE_HTTP.equals(backendPort.transportMode())) {
                JSONObject httpData = new JSONObject(mqttData.toString())
                        .put("deviceId", currentDeviceCode());
                postAsync(BackendHttpGateway.FAULT_REPORT, httpData, "http.hardwareFault");
                return;
            }
            try {
                send(new JSONObject().put("cmd", "hardwareFault").put("data", mqttData));
            } catch (Exception error) {
                stateStore.record("backend.hardwareFault.failed", message(error));
                JSONObject httpData = new JSONObject(mqttData.toString())
                        .put("deviceId", currentDeviceCode());
                postAsync(BackendHttpGateway.FAULT_REPORT, httpData,
                        "http.hardwareFault.fallback");
            }
        } catch (Exception error) {
            stateStore.record("hardware.fault.build.failed", message(error));
        }
    }

''')
replace_block(path,
'''    private void handleRemoteOpen(JSONObject command) throws Exception {''',
'''    private void handleRemoteEjectAll(JSONObject command) throws Exception {''',
'''    private void handleRemoteOpen(JSONObject command) throws Exception {
        int slotId = command.optInt("slotId", -1);
        JSONObject response = baseResponse(command, "remoteOpenResp");
        try {
            JSONObject result = operationEngine.openDoor(slotId, true, "MQTT",
                    command.optString("msgId", ""), "");
            stateStore.record("operation.remoteOpen.boardAcked", result);
            complete(command, response.put("code", 0).put("msg", "success"), true);
        } catch (Exception error) {
            complete(command, response.put("code", 4003)
                    .put("msg", safeMessage(error)), false);
        }
    }

''')
replace_block(path,
'''    private void handleRemoteEjectAll(JSONObject command) throws Exception {''',
'''    private void handleQueryStatus(JSONObject command) throws Exception {''',
'''    private void handleRemoteEjectAll(JSONObject command) throws Exception {
        JSONObject response = baseResponse(command, "remoteEjectAllResp");
        if (!command.optBoolean("confirm", false)) {
            complete(command, response.put("code", 4001)
                    .put("msg", "confirm required"), false);
            return;
        }
        try {
            JSONObject result = operationEngine.openAllDoors(true, "MQTT",
                    command.optString("msgId", ""));
            stateStore.record("operation.remoteEjectAll.boardAcked", result);
            int failedCount = result.optInt("failedCount", 0);
            complete(command, response.put("code", failedCount == 0 ? 0 : 4001)
                    .put("msg", failedCount == 0 ? "success"
                            : "部分或全部单板未应答"), failedCount == 0);
        } catch (Exception error) {
            complete(command, response.put("code", 4003)
                    .put("msg", safeMessage(error)), false);
        }
    }

''')
# Remove undocumented queryStatus handler.
replace_block(path,
'''    private void handleQueryStatus(JSONObject command) throws Exception {''',
'''    private void handleSync(JSONObject command, String scope) throws JSONException {''',
'''    private void handleSync(JSONObject command, String scope) throws JSONException {''')
# The preceding replacement intentionally preserves the sync method signature once.
replace_block(path,
'''    private void handleSyncConfig(JSONObject command) throws Exception {''',
'''    private void handleUnsupportedUpgrade(JSONObject command, boolean cancel) throws Exception {''',
'''    private void handleSyncConfig(JSONObject command) throws Exception {
        JSONObject current = settingsRepository.load();
        JSONObject remote = httpGateway.getData(BackendHttpGateway.DEVICE_CONFIG);
        JSONObject saved = settingsRepository.save(DeviceConfigMapper.apply(current, remote));
        complete(command, baseResponse(command, "syncConfigResp")
                .put("code", 0).put("msg", "success"), true);
        if (configControl != null) configControl.apply(saved);
    }

''')
replace_block(path,
'''    private void handleUnsupportedUpgrade(JSONObject command, boolean cancel) throws Exception {''',
'''    private void handleDeviceSelfCheck(JSONObject command) throws Exception {''',
'''    private void handleUnsupportedUpgrade(JSONObject command, boolean cancel) throws Exception {
        complete(command, baseResponse(command,
                cancel ? "cancelUpgradeResp" : "firmwareUpgradeResp")
                .put("code", 501)
                .put("msg", cancel ? "当前没有可取消的真实固件升级任务"
                        : "当前版本尚未实现固件下载安装"), false);
    }

''')
replace_once(path,
'''        complete(command, baseResponse(command, "deviceSelfCheckResp")
                .put("code", 0).put("status", "SUCCESS")
                .put("msg", "success").put("data", data), true);''',
'''        complete(command, baseResponse(command, "deviceSelfCheckResp")
                .put("code", 0).put("msg", "success"), true);''')
replace_block(path,
'''    private void handleLogUploadToggle(JSONObject command) throws Exception {''',
'''    private void handleRestartApp(JSONObject command) throws Exception {''',
'''    private void handleLogUploadToggle(JSONObject command) throws Exception {
        JSONObject settings = settingsRepository.load();
        boolean enabled = command.optBoolean("enabled",
                "enableLogUpload".equals(command.optString("cmd")));
        settings.put("logUploadEnabled", enabled);
        settingsRepository.save(settings);
        stateStore.record("backend.logUpload", new JSONObject().put("enabled", enabled)
                .put("operatorId", command.optString("operatorId", "")));
        // V4.1 explicitly defines no response for enableLogUpload/disableLogUpload.
        inboundRepository.complete(command.optString("msgId", ""), null);
    }

''')
replace_once(path,
'''        complete(command, baseResponse(command, "restartAppResp")
                .put("code", 0).put("status", "ACCEPTED").put("msg", "restarting"), true);''',
'''        complete(command, baseResponse(command, "restartAppResp")
                .put("code", 0).put("msg", "restarting"), true);''')
replace_block(path,
'''    private JSONObject baseResponse(JSONObject command, String responseCmd) {''',
'''    private String currentDeviceCode() {''',
'''    private JSONObject baseResponse(JSONObject command, String responseCmd) {
        try {
            return new JSONObject().put("cmd",
                    responseCmd == null || responseCmd.trim().isEmpty()
                            ? "commandResp" : responseCmd);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static boolean isLogToggle(String cmd) {
        return "enableLogUpload".equals(cmd) || "disableLogUpload".equals(cmd);
    }

''')
replace_block(path,
'''    private String currentDeviceCode() {''',
'''    private static int parseFaultCode(String value) {''',
'''    private String currentDeviceCode() {
        try {
            JSONObject settings = settingsRepository.load();
            return settings.optString("deviceCode", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

''')

# Repository and sync semantics: use only documented primary keys/status values; do not create a
# local employee from an unknown biometric enrolment; do not silently ignore template deletion.
path = 'app/src/main/java/com/xingyao/card/core/DeviceDataRepository.java'
replace_once(path,
'''        if (employee == null) {
            employee = new JSONObject()
                    .put("id", id)
                    .put("employeeId", id)
                    .put("employeeCode", id)
                    .put("employeeName", employeeName == null ? "" : employeeName)
                    .put("faceRegistered", false)
                    .put("fingerprintRegistered", false)
                    .put("enabled", true);
        } else {
            employee = copy(employee);
        }''',
'''        if (employee == null) {
            throw new IllegalStateException("员工不存在，禁止仅凭本机录入创建后台员工资料");
        }
        employee = copy(employee);''')
replace_once(path,
'''            String employeeId = item.optString("employeeId", "").trim();
            String key = firstKey(item, primaryKey, "id", "employeeId");
            if (isDeleted(item)) {
                if (!key.isEmpty()) target.remove(key);
                if (!employeeId.isEmpty()) removeFeaturesForEmployee(target, employeeId);
                continue;
            }
            if (key.isEmpty()) key = "ROW-" + index + "-" + System.currentTimeMillis();''',
'''            String key = firstKey(item, primaryKey, "id");
            if (key.isEmpty()) continue;
            if (isDeleted(item)) {
                target.remove(key);
                continue;
            }''')
replace_once(path,
'''            String key = firstKey(item, preferredKeys);
            if (key.isEmpty()) key = "ROW-" + index;''',
'''            String key = firstKey(item, preferredKeys);
            if (key.isEmpty()) continue;''')
replace_once(path,
'''        return "1".equals(status) || "DELETED".equalsIgnoreCase(status)
                || "DISABLED".equalsIgnoreCase(status);''',
'''        return "1".equals(status);''')

path = 'app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java'
# No undocumented deviceCode field in HTTP sync request bodies.
for old, new in [
    ('''                full ? 0L : dataRepository.employeeSyncVersion(), EMPLOYEE_PAGE_SIZE,
                deviceScope, "deletedEmployeeIds");''',
     '''                full ? 0L : dataRepository.employeeSyncVersion(), EMPLOYEE_PAGE_SIZE,
                null, "deletedEmployeeIds");'''),
    ('''                new JSONObject(deviceScope.toString()).put("includeFlags",
                        settings.optInt("faceSyncIncludeFlags", 3)), null);''',
     '''                new JSONObject().put("includeFlags",
                        settings.optInt("faceSyncIncludeFlags", 3)), null);'''),
    ('''                full ? 0L : dataRepository.fingerSyncVersion(), FINGER_PAGE_SIZE,
                deviceScope, null);''',
     '''                full ? 0L : dataRepository.fingerSyncVersion(), FINGER_PAGE_SIZE,
                null, null);'''),
    ('''                full ? 0L : dataRepository.employeeSyncVersion(), EMPLOYEE_PAGE_SIZE,
                deviceScope(settings), "deletedEmployeeIds");''',
     '''                full ? 0L : dataRepository.employeeSyncVersion(), EMPLOYEE_PAGE_SIZE,
                null, "deletedEmployeeIds");'''),
    ('''        JSONObject scope = deviceScope(settings).put("includeFlags",''',
     '''        JSONObject scope = new JSONObject().put("includeFlags",'''),
    ('''                full ? 0L : dataRepository.fingerSyncVersion(), FINGER_PAGE_SIZE,
                deviceScope(settings), null);''',
     '''                full ? 0L : dataRepository.fingerSyncVersion(), FINGER_PAGE_SIZE,
                null, null);'''),
]:
    replace_once(path, old, new)
replace_once(path,
'''        dataRepository.applyEmployeeSync(employees, employeePage.deletedIds, full,
                employeePage.syncVersion);
        deleteEmployeeTemplates(employeePage.deletedIds);''',
'''        deleteEmployeeTemplates(employeePage.deletedIds);
        dataRepository.applyEmployeeSync(employees, employeePage.deletedIds, full,
                employeePage.syncVersion);''')
replace_once(path,
'''        JSONObject snapshot = dataRepository.applyEmployeeSync(employees, page.deletedIds,
                full, page.syncVersion);
        deleteEmployeeTemplates(page.deletedIds);''',
'''        deleteEmployeeTemplates(page.deletedIds);
        JSONObject snapshot = dataRepository.applyEmployeeSync(employees, page.deletedIds,
                full, page.syncVersion);''')
replace_once(path,
'''            String employeeId = face.optString("employeeId",
                    face.optString("employeeCode", face.optString("faceId", ""))).trim();''',
'''            String employeeId = face.optString("employeeId", "").trim();''')
replace_once(path,
'''                    try {
                        arcFaceManager.enrollImage(employeeId, employeeName,
                                httpGateway.downloadBytes(imageUrl, true), imageUrl);
                    } catch (Exception authorizedError) {
                        arcFaceManager.enrollImage(employeeId, employeeName,
                                httpGateway.downloadBytes(imageUrl, false), imageUrl);
                    }''',
'''                    arcFaceManager.enrollImage(employeeId, employeeName,
                            httpGateway.downloadBytes(imageUrl, true), imageUrl);''')
replace_block(path,
'''    private void deleteEmployeeTemplates(JSONArray deletedEmployeeIds) {''',
'''    private static JSONObject failure(JSONObject source, String message) throws JSONException {''',
'''    private void deleteEmployeeTemplates(JSONArray deletedEmployeeIds) {
        if (deletedEmployeeIds == null) return;
        for (int index = 0; index < deletedEmployeeIds.length(); index++) {
            String id = String.valueOf(deletedEmployeeIds.opt(index)).trim();
            if (id.isEmpty()) continue;
            templateCleaner.deleteTemplate(id);
        }
    }

''')
replace_once(path,
'''        return "1".equals(status) || "DELETED".equalsIgnoreCase(status)
                || "DISABLED".equalsIgnoreCase(status);''',
'''        return "1".equals(status);''')
replace_once(path,
'''        return "1".equals(text) || "true".equalsIgnoreCase(text)
                || "yes".equalsIgnoreCase(text);''',
'''        return "1".equals(text) || "true".equalsIgnoreCase(text);''')
replace_block(path,
'''    private static String normalizeFaceFeatureValue(JSONObject face) {''',
'''    private static String normalizeFaceImageBase64(JSONObject face) {''',
'''    private static String normalizeFaceFeatureValue(JSONObject face) {
        if (face == null) return "";
        Object raw = face.opt("faceFeature");
        if (raw == null || raw == JSONObject.NULL) return "";
        return stripDataPrefix(String.valueOf(raw)).replaceAll("\\s+", "");
    }

''')
replace_block(path,
'''    private static String normalizeFaceImageBase64(JSONObject face) {''',
'''    private static String firstString(JSONObject source, String... keys) {''',
'''    private static String normalizeFaceImageBase64(JSONObject face) {
        if (face == null) return "";
        return stripDataPrefix(face.optString("faceImageBase64", ""))
                .replaceAll("\\s+", "");
    }

''')
# Remove now-unused deviceScope helper and firstString helper.
value = read(path)
value = re.sub(r'\n    private static JSONObject deviceScope\(JSONObject settings\) throws JSONException \{.*?\n    \}', '', value, count=1, flags=re.S)
value = re.sub(r'\n    private static String firstString\(JSONObject source, String\.\.\. keys\) \{.*?\n    \}', '', value, count=1, flags=re.S)
write(path, value)

# Runtime defaults must never briefly claim authorization before the real response is applied.
replace_once('app/src/main/java/com/xingyao/card/core/DeviceStateStore.java',
'''            sections.put("deviceAuthorization", state("AUTHORIZED", "已授权"));''',
'''            sections.put("deviceAuthorization", state("PENDING", "等待后端授权状态"));''')

# UI: local endpoint fields remain, but undocumented topology/card parse are disabled rather than
# presented as working options. deviceId is not editable because V4.1 registration uses machineId.
path = 'uniapp/src/pages/config/config.vue'
replace_once(path,
'''          <view class="field-row">
            <text class="field-label">设备ID</text>
            <input class="field-input wide" v-model.trim="form.deviceId" placeholder="留空时由 AndroidID 生成" />
          </view>''',
'''          <view class="field-row">
            <text class="field-label">机器标识</text>
            <input class="field-input wide readonly" value="由 AndroidID 生成，V4.1 不允许在此猜测或覆盖" disabled />
          </view>''')
replace_once(path, '<text class="field-label required">HTTP协议</text>',
             '<text class="field-label required">本机HTTP协议</text>')
replace_once(path, '<text class="field-label required">MQTT协议</text>',
             '<text class="field-label required">本机MQTT协议</text>')
replace_once(path,
'''          <view class="field-row">
            <text class="field-label">自动轮询</text>
            <UiSwitch v-model="form.serialPollingEnabled" />
          </view>''',
'''          <view class="field-row">
            <text class="field-label">自动轮询</text>
            <input class="field-input wide readonly" value="已禁用：缺少slotId到从机地址/切组协议" disabled />
          </view>''')
replace_once(path,
'''          <view class="field-row">
            <text class="field-label required">卡号解析方式</text>
            <view class="field-select" @click="openEditor('cardNumberMode')">{{ cardModeLabel }}</view>
          </view>''',
'''          <view class="field-row">
            <text class="field-label">卡号解析方式</text>
            <input class="field-input wide readonly" value="15字节ASCII（协议明确）" disabled />
          </view>''')
replace_once(path,
'''  cardNumberMode: { title: '卡号解析方式', options: [
    { value: 'VISIBLE', label: '可视卡号（15字节ASCII）' },
    { value: 'PHYSICAL', label: '物理卡号（原始字节十六进制）' }
  ] },
''', '')
replace_once(path,
'''const cardModeLabel = computed(() => String(form.cardNumberMode).toUpperCase() === 'PHYSICAL' ? '物理卡号' : '可视卡号')
''', '')
replace_once(path,
'''      faceRecognitionThreshold: Number(form.faceRecognitionThreshold),
      cardParseMode: String(form.cardNumberMode).toUpperCase() === 'PHYSICAL' ? '物理卡号' : '可视卡号' ''',
'''      faceRecognitionThreshold: Number(form.faceRecognitionThreshold),
      serialPollingEnabled: false,
      cardNumberMode: 'VISIBLE',
      cardParseMode: '转可见符' ''')
# Remove unused UiSwitch import after replacing the only switch in this page.
replace_once(path, "import UiSwitch from '@/components/UiSwitch.vue'\n", '')

# H5 defaults mirror native safe defaults.
path = 'uniapp/src/mock/data.js'
for old, new in [
    ('serialPollingEnabled: true', 'serialPollingEnabled: false'),
    ('serialResponseTimeoutMs: 1500', 'serialResponseTimeoutMs: 100'),
    ("httpScheme: 'http'", "httpScheme: ''"),
    ("httpServerAddress: 'card-test.quyohui.com'", "httpServerAddress: ''"),
    ('httpPort: 80', 'httpPort: 8082'),
    ("mqttScheme: 'tcp'", "mqttScheme: ''"),
    ("mqttServerAddress: '119.146.88.108'", "mqttServerAddress: ''"),
    ('mqttPort: 48419', 'mqttPort: 0'),
    ('cameraRotation: 90', 'cameraRotation: 270'),
]:
    replace_once(path, old, new)

# Tests now enforce evidence-only behavior.
write('app/src/test/java/com/xingyao/card/core/BackendEndpointSettingsTest.java', r'''package com.xingyao.card.core;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BackendEndpointSettingsTest {
    @Test
    public void explicitlyConfiguredLocalEndpointsRemainIndependent() throws Exception {
        JSONObject result = BackendEndpointSettings.normalize(new JSONObject()
                .put("httpScheme", "https")
                .put("httpServerAddress", "api.example.com")
                .put("httpPort", 8443)
                .put("httpBasePath", "/device")
                .put("mqttScheme", "ssl")
                .put("mqttServerAddress", "mqtt.example.com")
                .put("mqttPort", 8883)
                .put("tcpServerAddress", "legacy.example.com")
                .put("tcpPort", 9009));
        assertEquals("https://api.example.com:8443/device", result.getString("apiBaseUrl"));
        assertEquals("ssl://mqtt.example.com:8883", result.getString("mqttBrokerUrl"));
        assertEquals("legacy.example.com", result.getString("tcpServerAddress"));
    }

    @Test
    public void missingSchemeOrMqttPortDoesNotCreateAConnectionEndpoint() throws Exception {
        JSONObject result = BackendEndpointSettings.normalize(new JSONObject()
                .put("httpServerAddress", "api.example.com")
                .put("mqttServerAddress", "mqtt.example.com"));
        assertEquals("", result.getString("apiBaseUrl"));
        assertEquals("", result.getString("mqttBrokerUrl"));
        assertEquals(0, result.getInt("mqttPort"));
    }

    @Test
    public void explicitLegacyUrlsCanBeMigratedWithoutCrossingChannels() throws Exception {
        JSONObject result = BackendEndpointSettings.normalize(new JSONObject()
                .put("apiBaseUrl", "https://old-api.example.com:9443/prod")
                .put("mqttBrokerUrl", "tcp://old-mqtt.example.com:1883"));
        assertEquals("https://old-api.example.com:9443/prod", result.getString("apiBaseUrl"));
        assertEquals("tcp://old-mqtt.example.com:1883", result.getString("mqttBrokerUrl"));
        assertEquals("", result.getString("tcpServerAddress"));
    }
}
''')

write('app/src/test/java/com/xingyao/card/core/DeviceConfigMapperTest.java', r'''package com.xingyao.card.core;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DeviceConfigMapperTest {
    @Test
    public void appliesOnlyFieldsDocumentedByV41() throws Exception {
        JSONObject local = new JSONObject()
                .put("httpScheme", "https")
                .put("httpServerAddress", "api.customer.com")
                .put("httpPort", 443)
                .put("mqttScheme", "ssl")
                .put("mqttServerAddress", "mqtt.customer.com")
                .put("mqttPort", 8883)
                .put("tcpServerAddress", "legacy.customer.com")
                .put("tcpPort", 9009);
        JSONObject remote = new JSONObject()
                .put("serverIp", "10.0.0.5")
                .put("httpPort", 8082)
                .put("tcpPort", 9010)
                .put("communicationMode", "MQTT")
                .put("baudRate", 57600)
                .put("groupSize", 16)
                .put("totalSlots", 100)
                .put("pollingInterval", 5000)
                .put("faceThreshold", 0.82)
                .put("fingerThreshold", 0.81);

        JSONObject result = DeviceConfigMapper.apply(local, remote);
        assertEquals("api.customer.com", result.getString("httpServerAddress"));
        assertEquals(8082, result.getInt("httpPort"));
        assertEquals("mqtt.customer.com", result.getString("mqttServerAddress"));
        assertEquals(8883, result.getInt("mqttPort"));
        assertEquals("legacy.customer.com", result.getString("tcpServerAddress"));
        assertEquals(9010, result.getInt("tcpPort"));
        assertEquals("10.0.0.5", result.getString("backendServerIp"));
        assertEquals("MQTT", result.getString("backendTransport"));
    }

    @Test
    public void undocumentedEndpointAliasesAndMqttPortAreIgnored() throws Exception {
        JSONObject result = DeviceConfigMapper.apply(new JSONObject()
                        .put("httpScheme", "https")
                        .put("httpServerAddress", "old-api")
                        .put("httpPort", 443)
                        .put("mqttScheme", "ssl")
                        .put("mqttServerAddress", "old-mqtt")
                        .put("mqttPort", 8883),
                new JSONObject()
                        .put("httpHost", "invented-api")
                        .put("httpBaseUrl", "https://invented-api")
                        .put("mqttHost", "invented-mqtt")
                        .put("mqttBrokerUrl", "ssl://invented-mqtt:1883")
                        .put("mqttPort", 1883)
                        .put("communicationMode", "TCP"));
        assertEquals("old-api", result.getString("httpServerAddress"));
        assertEquals("old-mqtt", result.getString("mqttServerAddress"));
        assertEquals(8883, result.getInt("mqttPort"));
        assertEquals("MQTT", result.getString("backendTransport"));
    }
}
''')

# Evidence register replaces the previous mixed audit wording.
write('docs/DEVICE_CONFIGURATION_AND_INTERFACE_AUDIT.md', r'''# 设备配置与接口证据登记

更新时间：2026-07-23

本文件只记录已经有明确来源的事实，以及尚未确认的契约。它不把建议字段、推断状态或兼容尝试写成后端既有能力。

## 1. 证据优先级

1. `docs/source-2026-07-02/Android客户端接口文档.md` V4.1：当前后台接口和 MQTT 契约。
2. `docs/source-2026-07-02/智能工卡发卡机APP通信协议文档.md`：串口帧和旧通信说明。
3. `docs/source-2026-07-02/智能工卡发卡机设备APP需求文档.md`：产品页面和旧版配置需求。
4. 用户明确要求：本部署的 HTTP 与 MQTT 服务器不在一起，必须分别配置。

存在冲突时，不自行选择解释；运行代码采用不覆盖、不发送、不执行，并把冲突留在本文。

## 2. 文档明确的后台事实

- 所有 HTTP 请求除注册、版本检查外使用 `Authorization: Bearer deviceToken`。
- HTTP 登录返回 `token`，但文档没有说明该 token 应放入哪个后续请求头；客户端只保存，不把它猜成 Bearer。
- MQTT 激活/验证返回 `mqttPassword`、`signingKey`、`clientId`；文档没有 MQTT username。
- MQTT 上行包含 `msgId/cmd/timestamp/deviceCode/sign/data`。
- MQTT 下行包含 `msgId/cmd/timestamp/data`，明确不含 `sign`。
- 下行命令列表仅包含：`remoteOpen`、`remoteEjectAll`、`restartApp`、`syncUser`、`syncConfig`、`firmwareUpgrade`、`cancelUpgrade`、`deviceSelfCheck`、`enableLogUpload`、`disableLogUpload`。
- `enableLogUpload` 和 `disableLogUpload` 明确无终端响应。
- 员工同步包含 `deletedEmployeeIds`；人脸、指纹特征项使用 `faceId/fingerId/employeeId/status`。
- `/api/v1/device/config` 只定义 `baudRate/groupSize/totalSlots/pollingInterval/serverIp/tcpPort/httpPort/faceThreshold/fingerThreshold/communicationMode`。
- `communicationMode` 只定义 MQTT/HTTP。

## 3. 用户明确的本地配置需求

HTTP 与 MQTT 在本部署中是不同服务器，因此 Android 本地保存：

```text
httpScheme + httpServerAddress + httpPort + httpBasePath
mqttScheme + mqttServerAddress + mqttPort
tcpServerAddress + tcpPort（旧版兼容）
```

这些是本机连接配置，不是 `/api/v1/device/config` 已定义的返回字段。服务端通用 `serverIp` 只记录为 `backendServerIp`，不自动覆盖任何本机地址。

## 4. 当前禁用或留空，等待确切资料

### 4.1 MQTT username

V4.1 没有定义。客户端只使用显式配置值；未配置时按“无 username”连接，不再尝试 `deviceCode/clientId`。

### 4.2 MQTT 地址、端口和 TLS

V4.1 配置响应没有 MQTT host、port、scheme。新安装默认留空，必须由实际部署配置；不会填入测试 IP、1883、48419、TCP 或 SSL 默认值。

### 4.3 HTTP/MQTT 协议 scheme

这是本机建立连接必需的本地字段，但后台没有下发字段。新安装留空，由实际部署选择，不推断 HTTP/HTTPS 或 TCP/SSL。

### 4.4 串口拓扑

串口文档只说明“从机地址=目标单板地址”，没有说明：

- `slotId` 是否等于从机地址；
- 100 个卡位是否有 100 个唯一地址；
- 分组是否重复 1～16 地址；
- 切组命令和响应归属。

因此自动轮询、逻辑卡位开门和一键弹卡当前禁用。不会恢复取模，也不会采用直接映射。

### 4.5 卡号解析

串口文档明确卡号为 15 字节 ASCII。当前只按 ASCII 解析；原始十六进制可用于本地诊断，但不称为“物理卡号”。

### 4.6 TAKE/RETURN 确认时点

后台文档定义了 TAKE/RETURN 事件字段，但没有说明单板开门 ACK 是否等于实际取还卡。当前开门 ACK 只记录为本地操作阶段，不发送 `cardEvent`，直到硬件状态转换规则得到确认。

### 4.7 下行时间窗口和崩溃恢复期限

文档要求 timestamp，但没有定义允许偏差、10分钟窗口或5分钟恢复期限。客户端不再按自定时间窗拒绝指令；仍使用 `msgId` 防止重复副作用。PROCESSING 的人工恢复协议待补。

## 5. 当前严格按文档接入的 HTTP 路径

```text
POST /api/v1/app-version/check
POST /api/v1/device/register
POST /api/v1/device/activate
POST /api/v1/device/verify
GET  /api/v1/device/config
GET  /api/v1/device/auth/status
POST /api/v1/device/login
POST /api/v1/device/heartbeat
POST /api/v1/device/status
POST /api/v1/employee/sync
POST /api/v1/employee/face/sync
POST /api/v1/employee/finger/sync
POST /api/v1/card/event
POST /api/v1/log/report
POST /api/v1/fault/report
POST /api/v1/statistics/report
POST /api/v1/device/selfcheck
POST /api/v1/device/batch-result
POST /api/v1/upgrade/status
POST /api/v1/employee
POST /api/v1/employee/face
GET  /api/v1/employee/face/registered
GET  /api/v1/firmware/{firmwareId}/download
POST /api/v1/face/upload
POST /api/v1/fingerprint/upload
POST /api/v1/logs/batch
```

“路径有文档”不等于“业务闭环已实现”。OTA、multipart 上传、批量日志、真实统计、员工双向编辑仍未完成。

## 6. 明确未作为接口发送的本地字段

以下字段只允许存在于 Android 本地操作/诊断，不进入 V4.1 wire payload：

```text
operationId
requestMsgId
physicalConfirmed
recovered
BOARD_ACKED
PHYSICAL_PENDING
```

除非后端文档正式增加这些字段。

## 7. 配置删除候选

以下字段在当前文档体系没有可执行调用方，继续留空，等待统一删除确认：

```text
ignoreTokenFetch
codeValueType
cardSuccessResponseType
faceRegistrationResponseEnabled
tcpDoorCommandResponseEnabled
secondaryDoorEnabled
usbCardReaderEnabled
startCharacter
endCharacter
serialExtra
baudExtra
toastDisplay
```

`serverAddress/apiBaseUrl/mqttBrokerUrl/cardParseMode` 仅保留一个迁移版本，不能作为新配置真相。
''')

write('docs/CONTRACT_EVIDENCE_REGISTER.md', r'''# 未确认契约登记

任何条目在获得文档、后端示例报文、硬件协议或用户明确确认前，不进入运行逻辑。

| 项目 | 当前证据 | 运行策略 |
|---|---|---|
| HTTP 与 MQTT 独立服务器 | 用户明确要求 | 保留独立本机配置 |
| `/device/config` 独立 HTTP/MQTT host | V4.1 未定义 | 不解析候选别名，不覆盖本机地址 |
| MQTT username | V4.1 未定义 | 只用显式值，否则空 username |
| MQTT host/port/scheme 默认值 | V4.1 未定义 | 新安装留空 |
| HTTP 登录 token 后续用途 | 只定义返回，未定义请求头 | 保存但不用于 Bearer |
| slotId→从机地址 | 串口文档未定义 | 禁用逻辑轮询和开门 |
| 分组/切组协议 | 未提供 | `pollingMode` 留空 |
| “物理卡号”转换规则 | 串口仅定义15字节ASCII | 只使用ASCII |
| TAKE/RETURN 物理确认 | 未定义时点 | 开门ACK不发送cardEvent |
| 下行 timestamp 容差 | 未定义 | 不使用自定时间窗拒绝 |
| PROCESSING 恢复期限 | 未定义 | 不自动重放或自动判失败 |
| 故障恢复上报字段 | 未定义 `recovered` | 仅本地记录恢复 |
| 员工级指纹模块 | 未提供硬件/SDK | 不标记员工指纹已注册 |
| 本机员工/人脸反向上传时点 | 接口存在，产品流程未冻结 | 不自动上传 |
''')

# Make the no-invention rule binding for future Codex work.
replace_once('AGENTS.md',
'''## 绝对禁止
''',
'''## 契约证据规则

- 每个后端字段、路径、请求方法、枚举和响应语义必须能指向仓库原始文档或用户明确确认。
- 文档未定义时只能留空、禁用或写入 `docs/CONTRACT_EVIDENCE_REGISTER.md`，禁止兼容猜测。
- 本地工程状态可以有 `operationId` 等内部字段，但未经文档确认不得进入 HTTP/MQTT payload。
- 不得用测试 IP、常见端口、猜测 username、时间窗口或 slot 映射制造“可用”。
- 文档冲突时停止执行相关功能，不自行选择解释。

## 绝对禁止
''')
replace_once('.agents/skills/card-cabinet-architecture-guardian/SKILL.md',
'''## Stop conditions
''',
'''## Contract evidence gate

For every external field, endpoint, enum, timing rule, address mapping, authentication rule and
wire payload, cite an original repository document or an explicit user decision. If no source exists,
keep it disabled/blank and add it to `docs/CONTRACT_EVIDENCE_REGISTER.md`. Never implement a
"likely" backend or hardware behavior.

## Stop conditions
''')

# Permanent CI prevents the reverted assumptions from returning.
path = '.github/workflows/device-integration-ci.yml'
replace_once(path,
'''          print('validated strict UI -> Android data layer -> communication dependency direction')''',
'''          evidence = Path('docs/CONTRACT_EVIDENCE_REGISTER.md')
          assert evidence.is_file() and evidence.stat().st_size > 0
          mapper = (root / 'core/DeviceConfigMapper.java').read_text(encoding='utf-8')
          for forbidden in ['httpHost', 'mqttHost', 'mqttPort\") settings.put',
                            'mqttBrokerUrl\", \"mqttBrokerUrl', 'MODE_TCP.equals(mode)']:
              assert forbidden not in mapper, f'undocumented remote config mapping returned: {forbidden}'
          gateway = (root / 'core/BackendHttpGateway.java').read_text(encoding='utf-8')
          assert 'runtimeToken\", \"\"' not in gateway, 'runtimeToken used as HTTP Bearer'
          serial_text = read('serial')
          assert 'addressMode\", \"UNCONFIRMED' in serial_text
          assert 'return ((slotNumber - 1) %' not in serial_text
          assert 'return slotNumber;' not in serial_text
          assert '"PHYSICAL".equals(cardNumberMode)' not in serial_text
          coordinator_text = read('command_coordinator')
          for forbidden in ['physicalConfirmed', '.put("recovered"',
                            'case "queryStatus"', 'case "syncEmployeeData"',
                            'case "syncFaceData"', 'case "syncFingerData"']:
              assert forbidden not in coordinator_text, f'undocumented wire behavior returned: {forbidden}'
          settings_text = (root / 'core/NativeSettingsRepository.java').read_text(encoding='utf-8')
          for forbidden in ['card-test.quyohui.com', '119.146.88.108',
                            '.put("mqttPort", 48419)', '.put("mqttScheme", "tcp")']:
              assert forbidden not in settings_text, f'undocumented default returned: {forbidden}'
          print('validated strict UI -> Android data layer -> communication dependency direction')''')

print('contract evidence rollback applied')
