package ndpr.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NDPR 配置（config.json）。
 */
public final class Config {

    public String apiUrl = "https://api.ndpreforged.com";
    public String language = "zh_CN";
    public String token = "";
    public String uuid = "";
    public boolean onlinemode = true;
    public String logPath = "server/logs/latest.log";      // 兼容保留（基岩版忽略）
    public String loggerMode = "default";                   // 兼容保留（基岩版忽略）
    public String loggerFormat = "<[%n%]%name%>%s%<%message%>"; // 兼容保留（基岩版忽略）
    public int downloadInterval = 900;
    public boolean checkHwid = false;
    public int checkInterval = 3;
    public boolean failClosed = false;
    public int verifyTimeout = 60;
    public int freezeInterval = 1;
    public java.util.List<String> admins = new java.util.ArrayList<String>();

    private final Path path;

    public Config(Path dir) {
        this.path = dir.resolve("config.json");
    }

    public Path getPath() {
        return path;
    }

    /** 加载配置；缺失键补默认值并回写；校验失败抛异常。 */
    public void load(Translations tr) throws Exception {
        Map<String, Object> loaded = new LinkedHashMap<String, Object>();
        if (Files.exists(path)) {
            try {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                loaded = Json.asMap(Json.parse(text));
            } catch (Exception e) {
                loaded = new LinkedHashMap<String, Object>();
            }
        }
        boolean changed = false;
        changed |= setStr(loaded, "api_url", apiUrl);
        changed |= setStr(loaded, "language", language);
        changed |= setStr(loaded, "token", token);
        changed |= setStr(loaded, "uuid", uuid);
        changed |= setBool(loaded, "onlinemode", onlinemode);
        changed |= setStr(loaded, "log_path", logPath);
        changed |= setStr(loaded, "logger_mode", loggerMode);
        changed |= setStr(loaded, "logger_format", loggerFormat);
        changed |= setInt(loaded, "download_interval", downloadInterval);
        changed |= setBool(loaded, "check_hwid", checkHwid);
        changed |= setInt(loaded, "check_interval", checkInterval);
        changed |= setBool(loaded, "fail_closed", failClosed);
        changed |= setInt(loaded, "verify_timeout", verifyTimeout);
        changed |= setInt(loaded, "freeze_interval", freezeInterval);

        apiUrl = strOf(loaded, "api_url", apiUrl);
        language = strOf(loaded, "language", language);
        token = strOf(loaded, "token", token);
        uuid = strOf(loaded, "uuid", uuid);
        onlinemode = boolOf(loaded, "onlinemode", onlinemode);
        logPath = strOf(loaded, "log_path", logPath);
        loggerMode = strOf(loaded, "logger_mode", loggerMode);
        loggerFormat = strOf(loaded, "logger_format", loggerFormat);
        downloadInterval = intOf(loaded, "download_interval", downloadInterval);
        checkHwid = boolOf(loaded, "check_hwid", checkHwid);
        checkInterval = intOf(loaded, "check_interval", checkInterval);
        failClosed = boolOf(loaded, "fail_closed", failClosed);
        verifyTimeout = intOf(loaded, "verify_timeout", verifyTimeout);
        freezeInterval = intOf(loaded, "freeze_interval", freezeInterval);

        Object adminsRaw = loaded.get("admins");
        admins = new java.util.ArrayList<String>();
        if (adminsRaw instanceof java.util.List) {
            for (Object o : (java.util.List<?>) adminsRaw) {
                if (o != null) admins.add(String.valueOf(o));
            }
        } else if (adminsRaw != null) {
            admins.add(String.valueOf(adminsRaw));
        }
        changed |= !admins.isEmpty();

        if (changed) save();

        // 校验
        StringBuilder errors = new StringBuilder();
        if (apiUrl == null || apiUrl.isEmpty()) errors.append(tr.tr("ndpr.error.config.field", mapOf("field", "api_url")));
        else if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://"))
            errors.append(tr.tr("ndpr.error.config.field_hint", mapOf("field", "api_url", "hint", tr.tr("ndpr.hint.api_url_scheme"))));
        if (token == null) errors.append(tr.tr("ndpr.error.config.field", mapOf("field", "token")));
        if (uuid == null) errors.append(tr.tr("ndpr.error.config.field", mapOf("field", "uuid")));
        if (errors.length() > 0) throw new Exception("Error: " + errors.toString());
    }

    public void save() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("api_url", apiUrl);
        map.put("language", language);
        map.put("token", token);
        map.put("uuid", uuid);
        map.put("onlinemode", onlinemode);
        map.put("log_path", logPath);
        map.put("logger_mode", loggerMode);
        map.put("logger_format", loggerFormat);
        map.put("download_interval", downloadInterval);
        map.put("check_hwid", checkHwid);
        map.put("check_interval", checkInterval);
        map.put("fail_closed", failClosed);
        map.put("verify_timeout", verifyTimeout);
        map.put("freeze_interval", freezeInterval);
        map.put("admins", admins);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, Json.stringify(map).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 忽略
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static boolean setStr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) {
            m.put(key, def);
            return true;
        }
        return false;
    }

    private static boolean setBool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            m.put(key, def);
            return true;
        }
        return false;
    }

    private static boolean setInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) {
            m.put(key, def);
            return true;
        }
        return false;
    }

    private static String strOf(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static boolean boolOf(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return String.valueOf(v).trim().equalsIgnoreCase("true");
    }

    private static int intOf(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
