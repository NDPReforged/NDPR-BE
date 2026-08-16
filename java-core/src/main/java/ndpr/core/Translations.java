package ndpr.core;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 多语言支持：从 jar 内 /lang/zh_CN.json、/lang/en_us.json 加载翻译表。
 */
public final class Translations {

    public static final String DEFAULT_LANGUAGE = "zh_CN";

    private final Map<String, Map<String, String>> tables = new HashMap<String, Map<String, String>>();
    private String language = DEFAULT_LANGUAGE;

    public Translations() {
        load("zh_CN");
        load("en_us");
    }

    private void load(String lang) {
        Map<String, String> table = new HashMap<String, String>();
        try {
            // 注意：getResourceAsStream 不带前导斜杠
            InputStream is = Translations.class.getClassLoader().getResourceAsStream("lang/" + lang + ".json");
            if (is == null) {
                // 兼容直接以文件形式部署
                java.io.File f = new java.io.File("lang/" + lang + ".json");
                if (f.exists()) is = new java.io.FileInputStream(f);
            }
            if (is == null) return;
            Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            try {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
                Object parsed = Json.parse(sb.toString());
                for (Map.Entry<String, Object> e : Json.asMap(parsed).entrySet()) {
                    table.put(e.getKey(), Json.str(e.getValue()));
                }
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            // 翻译缺失不致命
        }
        tables.put(lang.toLowerCase().replace("-", "_"), table);
    }

    public void setLanguage(String lang) {
        if (lang != null && !lang.trim().isEmpty()) {
            this.language = lang.trim().toLowerCase().replace("-", "_");
        }
    }

    public String tr(String key) {
        return tr(key, null);
    }

    public String tr(String key, Map<String, Object> kwargs) {
        Map<String, String> table = tables.get(language.toLowerCase().replace("-", "_"));
        String text = table != null ? table.get(key) : null;
        if (text == null) {
            Map<String, String> fallback = tables.get(DEFAULT_LANGUAGE.toLowerCase());
            text = fallback != null ? fallback.get(key) : null;
        }
        if (text == null) text = key;
        if (kwargs != null && !kwargs.isEmpty()) {
            for (Map.Entry<String, Object> e : kwargs.entrySet()) {
                text = text.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
        }
        return text;
    }
}
