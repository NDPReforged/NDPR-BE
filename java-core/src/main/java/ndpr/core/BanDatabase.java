package ndpr.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 云端下发的 SQLite 封禁库访问（online/offline 双表，schema 自适应）。
 */
public final class BanDatabase {

    public static final class Row {
        public final String table;
        public final String player;
        public final String reason;
        public final String time;

        public Row(String table, String player, String reason, String time) {
            this.table = table;
            this.player = player;
            this.reason = reason;
            this.time = time;
        }
    }

    private final Path path;
    private final Map<String, Set<String>> schemaCache = new ConcurrentHashMap<String, Set<String>>();

    public BanDatabase(Path dir) {
        this.path = dir.resolve("ban_database.db");
    }

    public Path getPath() {
        return path;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    private Connection open() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath().toString().replace('\\', '/'));
    }

    private Set<String> tableSchema(Connection conn, String table) {
        Set<String> cached = schemaCache.get(table);
        if (cached != null) return cached;
        Set<String> cols = new HashSet<String>();
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                cols.add(rs.getString(2).toLowerCase());
            }
        } catch (Exception e) {
            // ignore
        }
        schemaCache.put(table, cols);
        return cols;
    }

    private String timeCol(Connection conn, String table) {
        Set<String> cols = tableSchema(conn, table);
        if (cols.contains("ban_time")) return "ban_time";
        if (cols.contains("last_seen")) return "last_seen";
        return "offline".equals(table) ? "ban_time" : "last_seen";
    }

    private boolean hasMcuuid(Connection conn, String table) {
        return tableSchema(conn, table).contains("mcuuid");
    }

    /** 校验下载文件是否为合法封禁库（返回总记录数，-1 表示非法）。 */
    public int validate(String dbPath) {
        int count = 0;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.replace('\\', '/'))) {
            for (String table : new String[]{"online", "offline"}) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) count += rs.getInt(1);
                }
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    public int count() {
        return validate(path.toAbsolutePath().toString().replace('\\', '/'));
    }

    /** 进服检查：按 mcuuid/player/ip/ipv6 联合查询，返回命中行或 null。 */
    public Row query(String player, String ip, String ipv6, String mcuuid) {
        if (!exists()) return null;
        try (Connection conn = open()) {
            for (String table : new String[]{"online", "offline"}) {
                String tc = timeCol(conn, table);
                String sql;
                List<Object> params = new ArrayList<Object>();
                if (hasMcuuid(conn, table)) {
                    sql = "SELECT player, ban_reason, " + tc + " FROM " + table + " WHERE mcuuid = ? OR player = ? OR ip = ? OR ipv6 = ?";
                    params.add(mcuuid);
                    params.add(player);
                    params.add(ip);
                    params.add(ipv6);
                } else {
                    sql = "SELECT player, ban_reason, " + tc + " FROM " + table + " WHERE player = ? OR ip = ? OR ipv6 = ?";
                    params.add(player);
                    params.add(ip);
                    params.add(ipv6);
                }
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < params.size(); i++) {
                        Object p = params.get(i);
                        ps.setObject(i + 1, p);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new Row(table, rs.getString(1), rs.getString(2), rs.getString(3));
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 IP / IPv6 / UUID 查询封禁记录。 */
    public Row lookupByIdentifier(String type, String value) {
        if (!exists()) return null;
        try (Connection conn = open()) {
            for (String table : new String[]{"online", "offline"}) {
                String tc = timeCol(conn, table);
                String col;
                if ("ip".equals(type)) col = "ip";
                else if ("ipv6".equals(type)) col = "ipv6";
                else {
                    if (!hasMcuuid(conn, table)) continue;
                    col = "mcuuid";
                }
                String sql = "SELECT player, ban_reason, " + tc + " FROM " + table + " WHERE " + col + " = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, value);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new Row(table, rs.getString(1), rs.getString(2), rs.getString(3));
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 按玩家名查询封禁记录（返回 IP、原因、时间）。 */
    public Row lookupByPlayer(String playerName) {
        if (!exists()) return null;
        try (Connection conn = open()) {
            for (String table : new String[]{"online", "offline"}) {
                String tc = timeCol(conn, table);
                String sql = "SELECT ip, ban_reason, " + tc + " FROM " + table + " WHERE player = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new Row(table, rs.getString(1), rs.getString(2), rs.getString(3));
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 模糊建议。 */
    public List<String> fuzzy(String query, int limit) {
        List<String> matches = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        if (!exists()) return matches;
        try (Connection conn = open()) {
            String pattern = "%" + query.toLowerCase() + "%";
            for (String table : new String[]{"online", "offline"}) {
                String sql = "SELECT player FROM " + table + " WHERE LOWER(player) LIKE ? LIMIT ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, pattern);
                    ps.setInt(2, limit * 2);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String name = rs.getString(1);
                            if (name != null && seen.add(name)) {
                                matches.add(name);
                                if (matches.size() >= limit) return matches;
                            }
                        }
                    }
                }
            }
            return matches;
        } catch (Exception e) {
            return matches;
        }
    }
}
