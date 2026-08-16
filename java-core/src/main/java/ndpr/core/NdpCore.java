package ndpr.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NDPR 业务核心（平台无关），NDPReforged 封禁系统基岩版客户端。
 * gomint / Allay / Nukkit 三平台通过 {@link Platform} 适配层复用本类。
 */
public final class NdpCore {

    public static final String VERSION = "2.1";

    private final Platform platform;
    private final Translations tr = new Translations();
    private final Config config;
    private final BanDatabase banDb;
    private final Path dataDir;
    private final Path playerInfoPath;
    private final Path hwidTempPath;

    private final Map<String, VerifySession> verifySessions = new ConcurrentHashMap<String, VerifySession>();
    private volatile boolean downloadInflight = false;
    private volatile boolean downloadLoopRunning = false;
    private volatile boolean shutdown = false;

    private static final class VerifySession {
        volatile boolean cancel;
        volatile String sessionId;
        String player;
        String ip;
        boolean firstVerify;
        String originalGameMode;
        double[] freezePos;
    }

    public NdpCore(Platform platform) {
        this.platform = platform;
        this.dataDir = platform.getDataDir().resolve("ndpr_data");
        this.config = new Config(platform.getDataDir());
        this.banDb = new BanDatabase(this.dataDir);
        this.playerInfoPath = this.dataDir.resolve("player_info.json");
        this.hwidTempPath = this.dataDir.resolve("hwid_temp.json");
    }

    // ================= 初始化 =================

    public void init() {
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            platform.log("创建数据目录失败: " + e);
        }
        try {
            config.load(tr);
        } catch (Exception e) {
            platform.log("配置错误，插件未正常启动: " + e.getMessage());
            return;
        }
        tr.setLanguage(config.language);
        platform.log(tr.tr("ndpr.log.server_type", mapOf("type",
                tr.tr(config.onlinemode ? "ndpr.word.online" : "ndpr.word.offline"))));
        platform.log(tr.tr("ndpr.log.uuid", mapOf("uuid", config.uuid.isEmpty() ? tr.tr("ndpr.word.unset") : config.uuid)));
        platform.runAsync(new Runnable() {
            @Override
            public void run() {
                asyncInit();
            }
        });
    }

    private void asyncInit() {
        String[][] stages = {
                {tr.tr("ndpr.word.stage_uuid"), "uuid"},
                {tr.tr("ndpr.word.stage_db"), "db"},
                {tr.tr("ndpr.word.stage_update"), "update"},
        };
        for (String[] stage : stages) {
            try {
                if ("uuid".equals(stage[1])) obtainUuid();
                else if ("db".equals(stage[1])) downloadBanDatabase(null);
                else checkPluginUpdate(null);
            } catch (Exception e) {
                platform.log(tr.tr("ndpr.error.init_stage_failed", mapOf("stage", stage[0], "error", String.valueOf(e))));
            }
        }
        startDownloadTask();
        platform.log(tr.tr("ndpr.log.init_done"));
    }

    public void shutdown() {
        shutdown = true;
        for (VerifySession s : verifySessions.values()) {
            s.cancel = true;
        }
        verifySessions.clear();
        platform.onShutdown();
        platform.log(tr.tr("ndpr.log.unloaded"));
    }

    /** 管理员名单（玩家名或 XUID），供平台权限判定使用。 */
    public java.util.List<String> getAdmins() {
        return config.admins;
    }

    public boolean isTokenConfigured() {
        return config.token != null && !config.token.isEmpty();
    }

    // ================= 配置重载 =================

    public void reload(Object src) {
        try {
            platform.reply(src, "§e" + tr.tr("ndpr.reply.reloading"));
            config.load(tr);
            tr.setLanguage(config.language);
            downloadBanDatabase(new ReplySink(src));
            platform.reply(src, "§a" + tr.tr("ndpr.reply.reloaded"));
        } catch (Exception e) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.reload_failed", mapOf("error", String.valueOf(e))));
        }
    }

    // ================= HTTP 辅助 =================

    private Map<String, String> authHeaders() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Authorization", "Bearer " + config.token);
        return h;
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return Json.asMap(Json.parse(body == null ? "{}" : body));
        } catch (Exception e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ================= UUID =================

    private void obtainUuid() throws Exception {
        if (!config.uuid.isEmpty()) return;
        if (config.apiUrl.isEmpty()) throw new Exception("Error: " + tr.tr("ndpr.error.api_url_missing"));
        platform.log(tr.tr("ndpr.log.getting_uuid"));
        Http.Result resp = Http.postJson(config.apiUrl + "/uuid/getuuid", null, null, 10);
        if (resp.code != 200) {
            throw new Exception("Error: " + tr.tr("ndpr.error.get_uuid_http", mapOf("code", resp.code, "body", trimBody(resp.body))));
        }
        Map<String, Object> data = parseJson(resp.body);
        Object uuid = data.get("uuid");
        if (uuid == null) throw new Exception("Error: " + tr.tr("ndpr.error.get_uuid_invalid", mapOf("data", resp.body)));
        config.uuid = String.valueOf(uuid);
        config.save();
        platform.log(tr.tr("ndpr.log.uuid_obtained", mapOf("uuid", config.uuid)));
    }

    private static String trimBody(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    // ================= 封禁库下载 =================

    private final class ReplySink implements java.util.function.Consumer<String> {
        private final Object src;

        ReplySink(Object src) {
            this.src = src;
        }

        @Override
        public void accept(String msg) {
            platform.reply(src, msg);
        }
    }

    public void downloadBanDatabase(java.util.function.Consumer<String> reply) {
        if (config.token == null || config.token.isEmpty()) {
            String msg = tr.tr("ndpr.warn.token_missing");
            platform.log(msg);
            if (reply != null) reply.accept("§c" + msg);
            return;
        }
        if (config.apiUrl.isEmpty()) {
            String msg = tr.tr("ndpr.error.db_api_unconfigured");
            platform.log(msg);
            if (reply != null) reply.accept("§c" + msg);
            return;
        }
        try {
            Http.Result resp = Http.get(config.apiUrl + "/bans/download", authHeaders(), 30);
            if (resp.code != 200) {
                String msg = tr.tr("ndpr.error.db_download_http", mapOf("code", resp.code, "body", trimBody(resp.body)));
                platform.log(msg);
                if (reply != null) reply.accept("§c" + msg);
                return;
            }
            Map<String, Object> data = parseJson(resp.body);
            Object url = data.get("url");
            if (url == null) {
                String msg = tr.tr("ndpr.error.db_download_no_url");
                platform.log(msg);
                if (reply != null) reply.accept("§c" + msg);
                return;
            }
            Http.Result fileResp = Http.get(String.valueOf(url), null, 60);
            if (fileResp.code != 200) {
                String msg = tr.tr("ndpr.error.db_file_download_http", mapOf("code", fileResp.code));
                platform.log(msg);
                if (reply != null) reply.accept("§c" + msg);
                return;
            }
            Path tmp = dataDir.resolve("ban_database.db.tmp");
            Files.write(tmp, fileResp.body.getBytes(StandardCharsets.ISO_8859_1));
            int count = banDb.validate(tmp.toAbsolutePath().toString().replace('\\', '/'));
            if (count < 0) {
                Files.deleteIfExists(tmp);
                String msg = tr.tr("ndpr.error.db_file_invalid", mapOf("error", "not a valid NDPR sqlite database"));
                platform.log(msg);
                if (reply != null) reply.accept("§c" + msg);
                return;
            }
            Files.move(tmp, banDb.getPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            String detailMsg = tr.tr("ndpr.log.db_updated", mapOf("count", count));
            platform.log(detailMsg);
            if (reply != null) {
                reply.accept("§a" + tr.tr("ndpr.reply.db_download_success"));
                reply.accept("§7" + detailMsg);
            }
            try {
                Http.postJson(config.apiUrl + "/bans/download/done", null, authHeaders(), 10);
            } catch (Exception e) {
                // ignore
            }
        } catch (Exception e) {
            String msg = tr.tr("ndpr.reply.connection_error") + " (" + e.getMessage() + ")";
            platform.log(msg);
            if (reply != null) reply.accept("§c" + msg);
        }
    }

    public void asyncDownload(java.util.function.Consumer<String> reply) {
        if (downloadInflight) {
            if (reply != null) reply.accept("§e" + tr.tr("ndpr.reply.download_inflight"));
            return;
        }
        downloadInflight = true;
        platform.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadBanDatabase(reply);
                } finally {
                    downloadInflight = false;
                }
            }
        });
    }

    private void startDownloadTask() {
        int interval = config.downloadInterval;
        if (interval <= 0) {
            platform.log(tr.tr("ndpr.log.auto_update_disabled"));
            return;
        }
        if (downloadLoopRunning) return;
        downloadLoopRunning = true;
        platform.log(tr.tr("ndpr.log.auto_update_started", mapOf("interval", interval)));
        scheduleNextDownload(interval);
    }

    private void scheduleNextDownload(final int interval) {
        platform.runLater(new Runnable() {
            @Override
            public void run() {
                if (shutdown || !downloadLoopRunning) return;
                platform.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            downloadBanDatabase(null);
                        } catch (Exception e) {
                            platform.log("自动更新失败: " + e);
                        }
                        scheduleNextDownload(interval);
                    }
                });
            }
        }, interval * 1000L);
    }

    // ================= 玩家信息持久化 =================

    private synchronized void savePlayerInfo(String player, String ip, String uuid, String ipv6) {
        try {
            Map<String, Object> info = new LinkedHashMap<String, Object>();
            if (Files.exists(playerInfoPath)) {
                try {
                    info = Json.asMap(Json.parse(new String(Files.readAllBytes(playerInfoPath), StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    info = new LinkedHashMap<String, Object>();
                }
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("ip", ip);
            entry.put("uuid", uuid);
            entry.put("ipv6", ipv6);
            entry.put("timestamp", System.currentTimeMillis() / 1000.0);
            info.put(player, entry);
            Files.write(playerInfoPath, Json.stringify(info).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // ignore
        }
    }

    private Map<String, Object> loadPlayerInfo(String player) {
        try {
            if (!Files.exists(playerInfoPath)) return new LinkedHashMap<String, Object>();
            Map<String, Object> info = Json.asMap(Json.parse(new String(Files.readAllBytes(playerInfoPath), StandardCharsets.UTF_8)));
            Object entry = info.get(player);
            return entry instanceof Map ? Json.asMap(entry) : new LinkedHashMap<String, Object>();
        } catch (Exception e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private synchronized Map<String, Object> loadHwidTemp() {
        try {
            if (!Files.exists(hwidTempPath)) return new LinkedHashMap<String, Object>();
            return Json.asMap(Json.parse(new String(Files.readAllBytes(hwidTempPath), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private synchronized void saveHwidTemp(String player, String ip) {
        try {
            Map<String, Object> records = loadHwidTemp();
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("ip", ip == null ? "" : ip);
            entry.put("time", System.currentTimeMillis() / 1000.0);
            records.put(player, entry);
            Files.write(hwidTempPath, Json.stringify(records).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // ignore
        }
    }

    // ================= 工具 =================

    public static String normalizeUuid(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) return value;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\[I?;?\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]$")
                .matcher(value);
        if (m.matches()) {
            long[] ints = new long[4];
            for (int i = 0; i < 4; i++) ints[i] = Long.parseLong(m.group(i + 1)) & 0xFFFFFFFFL;
            return String.format("%08x-%04x-%04x-%04x-%012x",
                    ints[0],
                    (ints[1] >> 16) & 0xFFFF, ints[1] & 0xFFFF,
                    (ints[2] >> 16) & 0xFFFF, ((ints[2] & 0xFFFF) << 32) | ints[3]);
        }
        return value;
    }

    public static String detectIdentifierType(String target) {
        if (target.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return "ip";
        if (target.contains(":") && target.matches("^[0-9a-fA-F:.]+$")) return "ipv6";
        if (target.length() == 36 && target.split("-").length == 5) return "uuid";
        return "id";
    }

    // ================= 进服/离服 =================

    public void onPlayerJoin(Object player) {
        String name = platform.playerName(player);
        String ip = platform.playerIp(player);
        String xuid = platform.playerXuid(player);
        String uuid = platform.playerUuid(player);
        if (uuid != null) uuid = normalizeUuid(uuid);
        String playerUuid = uuid != null ? uuid : xuid;

        platform.log(tr.tr("ndpr.log.player_info", mapOf("player", name,
                "ip", ip == null ? "?" : ip, "uuid", playerUuid == null ? "?" : playerUuid, "ipv6", "?")));
        if (playerUuid == null && ip == null) {
            platform.log(tr.tr("ndpr.warn.player_info_parse_failed", mapOf("player", name)));
        }
        savePlayerInfo(name, ip, playerUuid, null);

        final String kickInitializing = "§c" + tr.tr("ndpr.kick.system_initializing");

        if (!banDb.exists()) {
            platform.log(tr.tr("ndpr.warn.db_missing"));
            asyncDownload(null);
            if (config.failClosed) {
                platform.log(tr.tr("ndpr.warn.fail_closed_rejected", mapOf("player", name)));
                platform.kick(player, kickInitializing);
                return;
            }
            platform.log(tr.tr("ndpr.warn.fail_open_allowed", mapOf("player", name)));
        } else {
            try {
                BanDatabase.Row row = banDb.query(name, ip, null, playerUuid);
                if (row != null) {
                    platform.log(tr.tr("ndpr.log.banned_detected", mapOf("player", name, "table", row.table)));
                    platform.kick(player, "§c" + tr.tr("ndpr.kick.banned"));
                    reportKick();
                    return;
                }
            } catch (Exception e) {
                if (config.failClosed) {
                    platform.log(tr.tr("ndpr.warn.fail_closed_query_error", mapOf("player", name, "error", String.valueOf(e))));
                    platform.kick(player, kickInitializing);
                    return;
                }
                platform.log(tr.tr("ndpr.warn.fail_open_query_error", mapOf("player", name, "error", String.valueOf(e))));
            }
        }

        if (config.checkHwid) {
            startHwidVerify(name, ip, false);
        }
    }

    public void onPlayerLeft(Object player) {
        String name = platform.playerName(player);
        VerifySession session = verifySessions.remove(name);
        if (session != null) {
            session.cancel = true;
            if (session.sessionId != null) {
                final String sid = session.sessionId;
                platform.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        cancelApiSession(sid);
                    }
                });
            }
        }
    }

    private void reportKick() {
        try {
            Http.postJson(config.apiUrl + "/stats/a", new LinkedHashMap<String, Object>(), authHeaders(), 5);
        } catch (Exception e) {
            // ignore
        }
    }

    // ================= HWID 验证 =================

    private Map<String, Object> createVerifySession(String player, String ip) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("player_id", player);
        if (ip != null) payload.put("ip", ip);
        try {
            Http.Result resp = Http.postJson(config.apiUrl + "/hwid/upd", payload, authHeaders(), 10);
            if (resp.code == 200) return parseJson(resp.body);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> checkVerifyStatus(String sessionId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("session_id", sessionId);
            Http.Result resp = Http.postJson(config.apiUrl + "/hwid/upd/check", payload, authHeaders(), 3);
            if (resp.code == 200) return parseJson(resp.body);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> queryHasHwid(String player) {
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("player_id", player);
            Http.Result resp = Http.postJson(config.apiUrl + "/hwid/has", payload, authHeaders(), 5);
            if (resp.code == 200) return parseJson(resp.body);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void cancelApiSession(String sessionId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("session_id", sessionId);
            Http.postJson(config.apiUrl + "/hwid/upd/cancel", payload, authHeaders(), 5);
        } catch (Exception e) {
            // ignore
        }
    }

    public void startHwidVerify(final String name, final String ip, final boolean force) {
        if (!force && !config.checkHwid) return;
        if (config.token == null || config.token.isEmpty()) {
            platform.log(tr.tr("ndpr.warn.token_missing_hwid"));
            return;
        }
        boolean firstVerify = true;
        if (!force) {
            Object record = loadHwidTemp().get(name);
            if (record instanceof Map) {
                Object timeRaw = Json.asMap(record).get("time");
                double lastTime = timeRaw instanceof Number ? ((Number) timeRaw).doubleValue() : 0;
                if (System.currentTimeMillis() / 1000.0 - lastTime < config.checkInterval * 86400.0) return;
                firstVerify = false;
            }
        }
        VerifySession old = verifySessions.get(name);
        if (old != null && !old.cancel) old.cancel = true;
        if (old != null && old.sessionId != null) cancelApiSession(old.sessionId);

        final VerifySession session = new VerifySession();
        session.cancel = false;
        session.player = name;
        session.ip = ip;
        session.firstVerify = firstVerify;
        verifySessions.put(name, session);

        platform.runAsync(new Runnable() {
            @Override
            public void run() {
                runVerify(session);
            }
        });
    }

    private void runVerify(VerifySession session) {
        final String name = session.player;
        Object player = platform.getPlayer(name);
        if (player == null) {
            verifySessions.remove(name, session);
            return;
        }
        session.originalGameMode = platform.playerGameMode(player);
        session.freezePos = platform.playerPosition(player);
        if (session.freezePos == null) {
            platform.log(tr.tr("ndpr.warn.no_anchor_pos", mapOf("player", name)));
        }

        platform.execute("gamerule sendcommandfeedback false");
        platform.giveEffect(player, "blindness", 999999, 0, true);
        platform.setGameMode(player, "adventure");
        platform.sendTitle(player, tr.tr("ndpr.title.verify"), tr.tr("ndpr.subtitle.verify"));
        if (session.firstVerify) {
            platform.sendMessage(player, "§e" + tr.tr("ndpr.tell.verify_enabled"));
        }

        try {
            Map<String, Object> result = createVerifySession(name, session.ip);
            if (result == null || result.get("session_id") == null) {
                platform.log(tr.tr("ndpr.error.verify_start_failed", mapOf("player", name)));
                platform.kick(player, "§c" + tr.tr("ndpr.kick.verify_unavailable"));
                clearSession(name, session);
                return;
            }
            session.sessionId = String.valueOf(result.get("session_id"));

            int verifyTimeout = config.verifyTimeout < 30 ? 60 : config.verifyTimeout;
            long now = System.currentTimeMillis() / 1000;
            Object expiresRaw = result.get("expires_at");
            long rawExpires = expiresRaw instanceof Number ? ((Number) expiresRaw).longValue() : now + verifyTimeout;
            if (rawExpires <= 0) rawExpires = now + verifyTimeout;
            long expiresAt = Math.min(Math.max(rawExpires, now + 30), now + verifyTimeout);

            String verifyUrl = Json.str(result.get("verify_url"));
            platform.sendMessage(player, "§e" + tr.tr("ndpr.tell.click_verify") + verifyUrl);
            platform.sendMessage(player, "§7" + tr.tr("ndpr.tell.verify_freeze_notice"));

            int freezeInterval = config.freezeInterval < 1 ? 1 : config.freezeInterval;
            long lastFreeze = 0;

            while (!session.cancel && System.currentTimeMillis() / 1000 < expiresAt) {
                if (session.freezePos != null && System.currentTimeMillis() / 1000 - lastFreeze >= freezeInterval) {
                    platform.teleport(player, session.freezePos[0], session.freezePos[1], session.freezePos[2]);
                    lastFreeze = System.currentTimeMillis() / 1000;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
                if (session.cancel) return;
                Map<String, Object> status = checkVerifyStatus(session.sessionId);
                if (status == null) continue;
                Object completed = status.get("completed");
                if (completed instanceof Boolean && (Boolean) completed) {
                    boolean banned;
                    Object banReason;
                    if (status.containsKey("banned")) {
                        banned = Boolean.valueOf(String.valueOf(status.get("banned")));
                        banReason = status.get("reason");
                    } else {
                        Map<String, Object> has = queryHasHwid(name);
                        if (has == null) {
                            platform.kick(player, "§c" + tr.tr("ndpr.kick.hwid_status_unknown"));
                            clearSession(name, session);
                            return;
                        }
                        banned = Boolean.valueOf(String.valueOf(has.get("banned")));
                        banReason = has.get("reason");
                    }
                    if (banned) {
                        String reason = banReason == null || String.valueOf(banReason).isEmpty()
                                ? tr.tr("ndpr.word.hwid_banned")
                                : String.valueOf(banReason);
                        platform.kick(player, "§c" + tr.tr("ndpr.kick.banned_with_reason", mapOf("reason", reason)));
                        reportKick();
                    } else {
                        saveHwidTemp(name, session.ip);
                        platform.sendMessage(player, "§a" + tr.tr("ndpr.tell.verify_done"));
                    }
                    clearSession(name, session);
                    return;
                }
                if ("cancelled".equals(String.valueOf(status.get("status")))) {
                    clearSession(name, session);
                    return;
                }
                if ("expired".equals(String.valueOf(status.get("status")))) {
                    break;
                }
            }
            if (!session.cancel) {
                platform.kick(player, "§c" + tr.tr("ndpr.kick.verify_timeout"));
            }
        } finally {
            platform.clearEffect(player, "blindness");
            if (session.originalGameMode != null) {
                platform.setGameMode(player, session.originalGameMode);
            }
            platform.execute("gamerule sendcommandfeedback true");
            clearSession(name, session);
        }
    }

    private void clearSession(String player, VerifySession session) {
        verifySessions.remove(player, session);
    }

    // ================= 命令处理 =================

    public void handleCommand(Object src, String sub, String target, String reason) {
        String s = sub == null ? "help" : sub.toLowerCase();
        try {
            if ("help".equals(s) || s.isEmpty()) {
                help(src);
            } else if ("d".equals(s) || "download".equals(s)) {
                requireAdmin(src);
                platform.reply(src, "§e" + tr.tr("ndpr.reply.downloading"));
                asyncDownload(new ReplySink(src));
            } else if ("ban".equals(s)) {
                requireAdmin(src);
                if (target == null || reason == null || reason.isEmpty()) {
                    platform.reply(src, "§c" + tr.tr("ndpr.reply.ban_reason_required"));
                    platform.reply(src, "§7" + tr.tr("ndpr.reply.ban_usage"));
                    return;
                }
                banSubmit(src, target, reason);
            } else if ("check".equals(s)) {
                if (target == null || target.isEmpty()) {
                    platform.reply(src, "§7" + tr.tr("ndpr.reply.ban_usage"));
                    return;
                }
                String idType = detectIdentifierType(target);
                if ("ip".equals(idType) || "ipv6".equals(idType) || "uuid".equals(idType)) {
                    checkByIdentifier(src, idType, target);
                } else {
                    checkBanStatus(src, target);
                }
            } else if ("reload".equals(s)) {
                requireAdmin(src);
                platform.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        reload(src);
                    }
                });
            } else if ("cu".equals(s) || "checkupdate".equals(s)) {
                requireAdmin(src);
                platform.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        checkPluginUpdate(src);
                    }
                });
            } else if ("auth".equals(s)) {
                requireAdmin(src);
                if (target == null || target.isEmpty()) {
                    platform.reply(src, "§7" + tr.tr("ndpr.reply.auth_usage"));
                    return;
                }
                platform.reply(src, "§e" + tr.tr("ndpr.reply.auth_starting", mapOf("player", target)));
                Map<String, Object> info = loadPlayerInfo(target);
                startHwidVerify(target, Json.str(info.get("ip")), true);
            } else {
                help(src);
            }
        } catch (PermissionDeniedException e) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.permission_denied"));
        } catch (Exception e) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.query_failed", mapOf("error", String.valueOf(e))));
        }
    }

    private static final class PermissionDeniedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private void requireAdmin(Object src) {
        if (!platform.isAdmin(src)) throw new PermissionDeniedException();
    }

    private void help(Object src) {
        platform.reply(src, "§6========== §b" + tr.tr("ndpr.help.title") + " §6==========");
        platform.reply(src, "§e" + tr.tr("ndpr.help.version", mapOf("version", VERSION)));
        platform.reply(src, "§e" + tr.tr("ndpr.help.author"));
        platform.reply(src, tr.tr("ndpr.help.qq_group"));
        platform.reply(src, "");
        platform.reply(src, "§b" + tr.tr("ndpr.help.commands"));
        platform.reply(src, "§f/ndpr help §7- " + tr.tr("ndpr.help.desc.help"));
        platform.reply(src, "§f/ndpr d / download §7- " + tr.tr("ndpr.help.desc.download"));
        platform.reply(src, "§f/ndpr ban <ID> <reason> §7- " + tr.tr("ndpr.help.desc.ban"));
        platform.reply(src, "§f/ndpr check <ID/IP/UUID> §7- " + tr.tr("ndpr.help.desc.check"));
        platform.reply(src, "§f/ndpr reload §7- " + tr.tr("ndpr.help.desc.reload"));
        platform.reply(src, "§f/ndpr cu / checkupdate §7- " + tr.tr("ndpr.help.desc.checkupdate"));
        platform.reply(src, "§f/ndpr auth <ID> §7- " + tr.tr("ndpr.help.desc.auth"));
        platform.reply(src, "");
        platform.reply(src, tr.tr("ndpr.help.footer"));
    }

    private void checkByIdentifier(Object src, String idType, String value) {
        if (!banDb.exists()) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.no_data"));
            return;
        }
        try {
            BanDatabase.Row row = banDb.lookupByIdentifier(idType, value);
            if (row != null) {
                platform.reply(src, "§7" + tr.tr("ndpr.label.player", mapOf("player", row.player)));
                platform.reply(src, "§7" + tr.tr("ndpr.label.reason", mapOf("reason", row.reason)));
                platform.reply(src, "§7" + tr.tr("ndpr.label.ban_time", mapOf("time", row.time)));
            } else {
                platform.reply(src, tr.tr("ndpr.reply.record_not_found", mapOf("type", idType, "value", value)));
            }
        } catch (Exception e) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.query_failed", mapOf("error", String.valueOf(e))));
        }
    }

    private void checkBanStatus(Object src, String playerName) {
        if (!banDb.exists()) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.no_data"));
            return;
        }
        try {
            BanDatabase.Row row = banDb.lookupByPlayer(playerName);
            if (row != null) {
                platform.reply(src, "§c" + tr.tr("ndpr.reply.banned_in_table", mapOf("player", playerName, "table", row.table)));
                platform.reply(src, "§7" + tr.tr("ndpr.label.ip", mapOf("ip", row.player)));
                platform.reply(src, "§7" + tr.tr("ndpr.label.reason", mapOf("reason", row.reason)));
                platform.reply(src, "§7" + tr.tr("ndpr.label.ban_time", mapOf("time", row.time)));
            } else {
                platform.reply(src, "§a" + tr.tr("ndpr.reply.not_banned", mapOf("player", playerName)));
                java.util.List<String> matches = banDb.fuzzy(playerName, 5);
                if (!matches.isEmpty()) {
                    platform.reply(src, "§7" + tr.tr("ndpr.reply.fuzzy_suggestion"));
                    for (String nm : matches) {
                        platform.reply(src, "§8" + nm);
                    }
                }
            }
        } catch (Exception e) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.query_failed", mapOf("error", String.valueOf(e))));
        }
    }

    private void banSubmit(Object src, String playerName, String reason) {
        if (config.token == null || config.token.isEmpty()) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.token_not_configured"));
            return;
        }
        if (config.apiUrl.isEmpty()) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.api_not_configured"));
            return;
        }
        platform.reply(src, "§e" + tr.tr("ndpr.reply.getting_player_info", mapOf("player", playerName)));
        Map<String, Object> info = loadPlayerInfo(playerName);
        if (info.isEmpty()) {
            platform.reply(src, "§c" + tr.tr("ndpr.reply.player_info_not_found"));
            platform.reply(src, "§7" + tr.tr("ndpr.reply.player_info_hint"));
            return;
        }
        final String playerIp = Json.str(info.get("ip"));
        final String playerUuid = Json.str(info.get("uuid"));
        StringBuilder infoList = new StringBuilder();
        if (playerIp != null) infoList.append("IP: ").append(playerIp);
        if (playerUuid != null) {
            if (infoList.length() > 0) infoList.append(", ");
            infoList.append("UUID: ").append(playerUuid);
        }
        if (infoList.length() > 0) {
            platform.reply(src, "§e" + tr.tr("ndpr.reply.info_obtained", mapOf("info", infoList.toString())));
        }
        platform.reply(src, "§e" + tr.tr("ndpr.reply.ban_reason_echo", mapOf("reason", reason)));
        platform.reply(src, "§e" + tr.tr("ndpr.reply.submitting"));

        final Object fsrc = src;
        platform.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("player_id", playerName);
                    payload.put("ip", playerIp);
                    payload.put("ipv6", Json.str(info.get("ipv6")));
                    payload.put("uuid", playerUuid);
                    payload.put("onlinemode", config.onlinemode);
                    payload.put("reason", reason);
                    Http.Result resp = Http.postJson(config.apiUrl + "/check/uploader", payload, authHeaders(), 10);
                    if (resp.code == 200) {
                        Map<String, Object> result = parseJson(resp.body);
                        if ("success".equals(String.valueOf(result.get("result")))) {
                            platform.reply(fsrc, "§a" + tr.tr("ndpr.reply.submit_success"));
                            platform.reply(fsrc, "§7" + tr.tr("ndpr.reply.check_id", mapOf("check_id", Json.str(result.get("check_id")))));
                            platform.reply(fsrc, "§7" + tr.tr("ndpr.reply.wait_review"));
                        } else {
                            Object message = result.get("message");
                            platform.reply(fsrc, "§c" + tr.tr("ndpr.reply.submit_failed",
                                    mapOf("message", message == null ? tr.tr("ndpr.reply.unknown_error") : message)));
                        }
                    } else if (resp.code == 403) {
                        platform.reply(fsrc, "§c" + tr.tr("ndpr.reply.no_upload_permission"));
                    } else {
                        platform.reply(fsrc, "§c" + tr.tr("ndpr.reply.submit_failed_http", mapOf("code", resp.code)));
                        Object err = parseJson(resp.body).get("error");
                        platform.reply(fsrc, "§7" + tr.tr("ndpr.reply.error_info",
                                mapOf("error", err == null ? tr.tr("ndpr.reply.unknown_error") : err)));
                    }
                } catch (Exception e) {
                    platform.reply(fsrc, "§c" + tr.tr("ndpr.reply.connection_error"));
                }
            }
        });
    }

    // ================= 更新检查 =================

    public void checkPluginUpdate(Object src) {
        if (src != null) platform.reply(src, "§a" + tr.tr("ndpr.reply.checking_update"));
        try {
            Http.Result resp = Http.get("https://api.github.com/repos/NDPReforged/NDPR-BE/releases/latest", null, 30);
            if (resp.code != 200) {
                if (src != null) platform.reply(src, "§c" + tr.tr("ndpr.reply.query_failed", mapOf("error", "HTTP " + resp.code)));
                return;
            }
            Map<String, Object> data = parseJson(resp.body);
            String latest = String.valueOf(data.get("tag_name") == null ? "" : data.get("tag_name")).replaceFirst("^v", "");
            boolean hasUpdate = false;
            try {
                String[] curParts = VERSION.split("\\.");
                String[] latParts = latest.split("\\.");
                int n = Math.max(curParts.length, latParts.length);
                int[] cur = new int[n];
                int[] lat = new int[n];
                for (int i = 0; i < n; i++) {
                    cur[i] = i < curParts.length ? Integer.parseInt(curParts[i]) : 0;
                    lat[i] = i < latParts.length ? Integer.parseInt(latParts[i]) : 0;
                }
                for (int i = 0; i < n; i++) {
                    if (lat[i] > cur[i]) {
                        hasUpdate = true;
                        break;
                    }
                    if (lat[i] < cur[i]) break;
                }
            } catch (Exception e) {
                hasUpdate = false;
            }
            if (hasUpdate) {
                if (src != null) {
                    platform.reply(src, "§a" + tr.tr("ndpr.reply.update_found"));
                    platform.reply(src, "§a" + tr.tr("ndpr.reply.current_version", mapOf("version", VERSION)));
                    platform.reply(src, "§a" + tr.tr("ndpr.reply.latest_version", mapOf("version", latest)));
                    Object notes = data.get("body");
                    if (notes != null && !String.valueOf(notes).isEmpty()) {
                        String noteText = String.valueOf(notes);
                        if (noteText.length() > 100) noteText = noteText.substring(0, 100) + "...";
                        platform.reply(src, "§a" + tr.tr("ndpr.reply.update_notes", mapOf("notes", noteText)));
                    }
                    platform.reply(src, "§a" + tr.tr("ndpr.reply.download_url", mapOf("url", Json.str(data.get("html_url")))));
                }
                platform.log(tr.tr("ndpr.log.update_found", mapOf("latest", latest, "current", VERSION, "url", Json.str(data.get("html_url")))));
            } else if (src != null) {
                platform.reply(src, "§a" + tr.tr("ndpr.reply.up_to_date", mapOf("version", VERSION)));
            }
        } catch (Exception e) {
            if (src != null) platform.reply(src, "§c" + tr.tr("ndpr.reply.query_failed", mapOf("error", String.valueOf(e))));
        }
    }
}
