package ndpr.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 基于 HttpURLConnection 的 HTTP 客户端（Java 8 兼容，零依赖，支持 https）。
 */
public final class Http {

    public static final class Result {
        public final int code;
        public final String body;

        public Result(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Http() {
    }

    public static Result request(String method, String urlStr, String jsonBody, Map<String, String> headers, int timeoutSec) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] data = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(data);
                } finally {
                    os.close();
                }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (is != null) {
                try {
                    body = readAll(is);
                } finally {
                    is.close();
                }
            }
            return new Result(code, body);
        } catch (IOException e) {
            throw new RuntimeException("HTTP 请求失败: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static Result get(String urlStr, Map<String, String> headers, int timeoutSec) {
        return request("GET", urlStr, null, headers, timeoutSec);
    }

    public static Result postJson(String urlStr, Object payload, Map<String, String> headers, int timeoutSec) {
        return request("POST", urlStr, payload == null ? null : Json.stringify(payload), headers, timeoutSec);
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
