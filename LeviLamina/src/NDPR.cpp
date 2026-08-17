// NDPR - LeviLamina 业务核心实现（NDPReforged 封禁系统基岩版客户端）
#include "NDPR.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <regex>
#include <sstream>

#include "ll/api/command/CommandRegistrar.h"
#include "ll/api/service/Bedrock.h"
#include "ll/api/thread/ServerThreadExecutor.h"
#include "mc/server/ServerLevel.h"
#include "mc/server/commands/ServerCommandOrigin.h"
#include "mc/world/level/GameType.h"
#include "mc/world/level/Level.h"

#include "Http.h"
#include "Translations.h"
#include "vendor/sqlite3.h"

namespace ndpr {

namespace {
constexpr char const* VERSION = "2.1";
constexpr char const* DEFAULT_LANGUAGE = "zh_CN";
constexpr int ADMIN_PERM_LEVEL = 2; // 对应管理员权限（CommandPermissionLevel::Admin）

std::string format(std::string text, std::map<std::string, std::string> const& kwargs) {
    for (auto const& [k, v] : kwargs) {
        std::string key = "{" + k + "}";
        size_t pos = 0;
        while ((pos = text.find(key, pos)) != std::string::npos) {
            text.replace(pos, key.size(), v);
            pos += v.size();
        }
    }
    return text;
}

std::string normalizeUuid(std::string const& value) {
    if (value.empty()) return {};
    static std::regex const dashRe(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    if (std::regex_match(value, dashRe)) return value;
    static std::regex const intArrRe(R"(^\[I?;?\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\]$)");
    std::smatch m;
    if (std::regex_match(value, m, intArrRe)) {
        unsigned long long ints[4];
        for (int i = 0; i < 4; i++) ints[i] = std::stoull(m[i + 1].str()) & 0xFFFFFFFFULL;
        char buf[64];
        snprintf(buf, sizeof(buf), "%08llx-%04llx-%04llx-%04llx-%012llx", ints[0],
                 (ints[1] >> 16) & 0xFFFF, ints[1] & 0xFFFF, (ints[2] >> 16) & 0xFFFF,
                 ((ints[2] & 0xFFFF) << 32) | ints[3]);
        return buf;
    }
    return value;
}

std::string detectIdentifierType(std::string const& target) {
    static std::regex const ipRe(R"(^\d{1,3}(\.\d{1,3}){3}$)");
    static std::regex const ipv6Re(R"(^[0-9a-fA-F:.]+$)");
    if (std::regex_match(target, ipRe)) return "ip";
    if (target.find(':') != std::string::npos && std::regex_match(target, ipv6Re)) return "ipv6";
    if (target.size() == 36 && std::count(target.begin(), target.end(), '-') == 4) return "uuid";
    return "id";
}

std::string stripPort(std::string const& ipPort) {
    // BDS getIPAndPort() 返回 "ip:port"；仅 IPv4 形态剥离端口，IPv6 原样保留
    static std::regex const ipv4PortRe(R"(^(\d{1,3}(?:\.\d{1,3}){3}):\d+$)");
    std::smatch m;
    if (std::regex_match(ipPort, m, ipv4PortRe)) return m[1].str();
    return ipPort;
}

} // namespace

NDPR& NDPR::getInstance() {
    static NDPR instance;
    return instance;
}

// ================= 初始化 =================

void NDPR::init(ll::io::Logger& logger, std::filesystem::path const& configDir,
                std::filesystem::path const& dataDir) {
    mLogger = &logger;
    mConfigDir = configDir;
    mDataDir = dataDir;
    mConfigPath = mConfigDir / "config.json";
    mBanDbPath = mDataDir / "ban_database.db";
    mPlayerInfoPath = mDataDir / "player_info.json";
    mHwidTempPath = mDataDir / "hwid_temp.json";
    try {
        std::filesystem::create_directories(mDataDir);
    } catch (...) {
    }
    if (!loadConfig()) {
        logger.error("配置错误，插件未正常启动");
        return;
    }
    logger.info(tr("ndpr.log.server_type", {{"type", mConfig.onlinemode ? tr("ndpr.word.online") : tr("ndpr.word.offline")}}));
    logger.info(tr("ndpr.log.uuid", {{"uuid", mConfig.uuid.empty() ? tr("ndpr.word.unset") : mConfig.uuid}}));

    mThreads.emplace_back([this] { asyncInit(); });
}

void NDPR::shutdown() {
    mShutdown = true;
    {
        std::lock_guard lock(mVerifyMutex);
        for (auto& [name, session] : mVerifySessions) {
            session->cancel = true;
            if (!session->sessionId.empty()) cancelApiSession(session->sessionId);
        }
        mVerifySessions.clear();
    }
    for (auto& t : mThreads) {
        if (t.joinable()) t.detach();
    }
    if (mLogger) mLogger->info(tr("ndpr.log.unloaded"));
}

void NDPR::asyncInit() {
    struct Stage {
        std::string name;
        int         kind; // 0=uuid 1=db 2=update
    };
    std::vector<Stage> stages;
    if (mConfig.uuid.empty()) stages.push_back({tr("ndpr.word.stage_uuid"), 0});
    stages.push_back({tr("ndpr.word.stage_db"), 1});
    stages.push_back({tr("ndpr.word.stage_update"), 2});
    for (auto const& stage : stages) {
        try {
            if (stage.kind == 0) obtainUuid();
            else if (stage.kind == 1) downloadBanDatabase(nullptr);
            else checkPluginUpdate(nullptr);
        } catch (std::exception const& e) {
            mLogger->error(tr("ndpr.error.init_stage_failed", {{"stage", stage.name}, {"error", e.what()}}));
        }
    }
    startDownloadTask();
    mLogger->info(tr("ndpr.log.init_done"));
}

// ================= 配置 =================

bool NDPR::loadConfig() {
    NDPRConfig cfg;
    Json j;
    bool exists = std::filesystem::exists(mConfigPath);
    if (exists) {
        try {
            std::ifstream ifs(mConfigPath);
            ifs >> j;
        } catch (...) {
            j = Json::object();
        }
    }
    bool changed = !exists;
    auto getStr = [&](char const* key, std::string const& def) {
        if (j.contains(key) && j[key].is_string()) return j[key].get<std::string>();
        j[key] = def;
        changed = true;
        return def;
    };
    auto getBool = [&](char const* key, bool def) {
        if (j.contains(key) && j[key].is_boolean()) return j[key].get<bool>();
        j[key] = def;
        changed = true;
        return def;
    };
    auto getInt = [&](char const* key, int def) {
        if (j.contains(key) && j[key].is_number_integer()) return j[key].get<int>();
        j[key] = def;
        changed = true;
        return def;
    };

    cfg.apiUrl = getStr("api_url", cfg.apiUrl);
    cfg.language = getStr("language", cfg.language);
    cfg.token = getStr("token", cfg.token);
    cfg.uuid = getStr("uuid", cfg.uuid);
    cfg.onlinemode = getBool("onlinemode", cfg.onlinemode);
    cfg.logPath = getStr("log_path", cfg.logPath);
    cfg.loggerMode = getStr("logger_mode", cfg.loggerMode);
    cfg.loggerFormat = getStr("logger_format", cfg.loggerFormat);
    cfg.downloadInterval = getInt("download_interval", cfg.downloadInterval);
    cfg.checkHwid = getBool("check_hwid", cfg.checkHwid);
    cfg.checkInterval = getInt("check_interval", cfg.checkInterval);
    cfg.failClosed = getBool("fail_closed", cfg.failClosed);
    cfg.verifyTimeout = getInt("verify_timeout", cfg.verifyTimeout);
    cfg.freezeInterval = getInt("freeze_interval", cfg.freezeInterval);
    if (j.contains("admins") && j["admins"].is_array()) {
        for (auto const& a : j["admins"]) {
            if (a.is_string()) cfg.admins.push_back(a.get<std::string>());
        }
    } else {
        j["admins"] = Json::array();
        changed = true;
    }

    // 校验
    std::string errors;
    if (cfg.apiUrl.empty() || (cfg.apiUrl.rfind("http://", 0) != 0 && cfg.apiUrl.rfind("https://", 0) != 0)) {
        errors += tr("ndpr.error.config.field_hint", {{"field", "api_url"}, {"hint", tr("ndpr.hint.api_url_scheme")}});
        errors += "; ";
    }
    if (cfg.token.empty()) {
        errors += tr("ndpr.error.config.field", {{"field", "token"}});
        errors += "; ";
    }
    if (!errors.empty()) {
        mLogger->error("Error: " + errors);
        return false;
    }

    mConfig = cfg;
    if (changed) saveConfig();
    return true;
}

void NDPR::saveConfig() {
    Json j;
    j["api_url"] = mConfig.apiUrl;
    j["language"] = mConfig.language;
    j["token"] = mConfig.token;
    j["uuid"] = mConfig.uuid;
    j["onlinemode"] = mConfig.onlinemode;
    j["log_path"] = mConfig.logPath;
    j["logger_mode"] = mConfig.loggerMode;
    j["logger_format"] = mConfig.loggerFormat;
    j["download_interval"] = mConfig.downloadInterval;
    j["check_hwid"] = mConfig.checkHwid;
    j["check_interval"] = mConfig.checkInterval;
    j["fail_closed"] = mConfig.failClosed;
    j["verify_timeout"] = mConfig.verifyTimeout;
    j["freeze_interval"] = mConfig.freezeInterval;
    j["admins"] = mConfig.admins;
    try {
        std::filesystem::create_directories(mConfigDir);
        std::ofstream ofs(mConfigPath);
        ofs << j.dump(2);
    } catch (...) {
    }
}

// ================= 翻译 =================

std::string NDPR::tr(std::string const& key, std::map<std::string, std::string> const& kwargs) const {
    std::string lang = mConfig.language;
    std::transform(lang.begin(), lang.end(), lang.begin(), ::tolower);
    std::replace(lang.begin(), lang.end(), '-', '_');
    auto const& table = lang == "en_us" ? enUsTable() : zhCnTable();
    auto it = table.find(key);
    std::string text = it != table.end() ? it->second : key;
    if (lang != "en_us") {
        // zh 表缺键时回退 en
        if (it == table.end()) {
            auto it2 = enUsTable().find(key);
            if (it2 != enUsTable().end()) text = it2->second;
        }
    }
    return format(text, kwargs);
}

// ================= HTTP =================

std::map<std::string, std::string> NDPR::authHeaders() const {
    return {{"Authorization", "Bearer " + mConfig.token}};
}

std::optional<Json> NDPR::postJson(std::string const& url, Json const& payload,
                                   std::map<std::string, std::string> const& headers, int timeoutSec) {
    try {
        HttpResponse resp = Http::postJson(url, payload.is_null() ? std::string{} : payload.dump(), headers, timeoutSec);
        if (resp.status == 200 && !resp.body.empty()) return Json::parse(resp.body);
        if (resp.status == 200) return Json::object();
        return std::nullopt;
    } catch (...) {
        return std::nullopt;
    }
}

std::optional<Json> NDPR::getJson(std::string const& url, std::map<std::string, std::string> const& headers,
                                  int timeoutSec) {
    try {
        HttpResponse resp = Http::get(url, headers, timeoutSec);
        if (resp.status == 200 && !resp.body.empty()) return Json::parse(resp.body);
        if (resp.status == 200) return Json::object();
        return std::nullopt;
    } catch (...) {
        return std::nullopt;
    }
}

// ================= SQLite =================

void NDPR::clearSchemaCache() {
    std::lock_guard lock(mDbSchemaMutex);
    mSchemaCache.clear();
}

std::set<std::string> tableSchema(sqlite3* db, std::string const& table,
                                  std::map<std::string, std::set<std::string>>& cache,
                                  std::mutex& mutex) {
    {
        std::lock_guard lock(mutex);
        auto it = cache.find(table);
        if (it != cache.end()) return it->second;
    }
    std::set<std::string> cols;
    std::string sql = "PRAGMA table_info(" + table + ")";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql.c_str(), -1, &stmt, nullptr) == SQLITE_OK) {
        while (sqlite3_step(stmt) == SQLITE_ROW) {
            auto name = sqlite3_column_text(stmt, 1);
            if (name) {
                std::string col = reinterpret_cast<char const*>(name);
                std::transform(col.begin(), col.end(), col.begin(), ::tolower);
                cols.insert(col);
            }
        }
        sqlite3_finalize(stmt);
    }
    std::lock_guard lock(mutex);
    cache[table] = cols;
    return cols;
}

int NDPR::validateDb(std::string const& dbPath) {
    sqlite3* db = nullptr;
    if (sqlite3_open_v2(dbPath.c_str(), &db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK) {
        if (db) sqlite3_close(db);
        return -1;
    }
    int count = 0;
    for (auto const& table : {"online", "offline"}) {
        sqlite3_stmt* stmt = nullptr;
        std::string sql = "SELECT COUNT(*) FROM " + std::string(table);
        if (sqlite3_prepare_v2(db, sql.c_str(), -1, &stmt, nullptr) == SQLITE_OK) {
            if (sqlite3_step(stmt) == SQLITE_ROW) count += sqlite3_column_int(stmt, 0);
            sqlite3_finalize(stmt);
        } else {
            sqlite3_close(db);
            return -1;
        }
    }
    sqlite3_close(db);
    return count;
}

std::optional<BanRow> NDPR::queryBan(std::string const& player, std::string const& ip, std::string const& ipv6,
                                     std::string const& mcuuid) {
    if (!std::filesystem::exists(mBanDbPath)) return std::nullopt;
    sqlite3* db = nullptr;
    if (sqlite3_open_v2(mBanDbPath.string().c_str(), &db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK) {
        if (db) sqlite3_close(db);
        return std::nullopt;
    }
    std::optional<BanRow> result;
    for (auto const& table : {"online", "offline"}) {
        auto cols = tableSchema(db, table, mSchemaCache, mDbSchemaMutex);
        std::string timeCol = cols.count("ban_time") ? "ban_time" : (cols.count("last_seen") ? "last_seen"
                                                                                            : std::string(std::strcmp(table, "offline") == 0 ? "ban_time" : "last_seen"));
        bool hasMc = cols.count("mcuuid") > 0;
        std::string sql;
        if (hasMc) {
            sql = "SELECT player, ban_reason, " + timeCol + " FROM " + table +
                  " WHERE mcuuid = ? OR player = ? OR ip = ? OR ipv6 = ?";
        } else {
            sql = "SELECT player, ban_reason, " + timeCol + " FROM " + table +
                  " WHERE player = ? OR ip = ? OR ipv6 = ?";
        }
        sqlite3_stmt* stmt = nullptr;
        if (sqlite3_prepare_v2(db, sql.c_str(), -1, &stmt, nullptr) == SQLITE_OK) {
            int idx = 1;
            if (hasMc) sqlite3_bind_text(stmt, idx++, mcuuid.c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_bind_text(stmt, idx++, player.c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_bind_text(stmt, idx++, ip.c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_bind_text(stmt, idx++, ipv6.c_str(), -1, SQLITE_TRANSIENT);
            if (sqlite3_step(stmt) == SQLITE_ROW) {
                BanRow row;
                row.table = table;
                auto c0 = sqlite3_column_text(stmt, 0);
                auto c1 = sqlite3_column_text(stmt, 1);
                auto c2 = sqlite3_column_text(stmt, 2);
                row.player = c0 ? reinterpret_cast<char const*>(c0) : "";
                row.reason = c1 ? reinterpret_cast<char const*>(c1) : "";
                row.time = c2 ? reinterpret_cast<char const*>(c2) : "";
                result = row;
            }
            sqlite3_finalize(stmt);
        }
        if (result) break;
    }
    sqlite3_close(db);
    return result;
}

std::optional<BanRow> NDPR::lookupByPlayer(std::string const& player) {
    if (!std::filesystem::exists(mBanDbPath)) return std::nullopt;
    sqlite3* db = nullptr;
    if (sqlite3_open_v2(mBanDbPath.string().c_str(), &db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK) {
        if (db) sqlite3_close(db);
        return std::nullopt;
    }
    std::optional<BanRow> result;
    for (auto const& table : {"online", "offline"}) {
        auto cols = tableSchema(db, table, mSchemaCache, mDbSchemaMutex);
        std::string timeCol = cols.count("ban_time") ? "ban_time" : (cols.count("last_seen") ? "last_seen"
                                                                                            : std::string(std::strcmp(table, "offline") == 0 ? "ban_time" : "last_seen"));
        std::string sql = "SELECT ip, ban_reason, " + timeCol + " FROM " + table + " WHERE player = ?";
        sqlite3_stmt* stmt = nullptr;
        if (sqlite3_prepare_v2(db, sql.c_str(), -1, &stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_text(stmt, 1, player.c_str(), -1, SQLITE_TRANSIENT);
            if (sqlite3_step(stmt) == SQLITE_ROW) {
                BanRow row;
                row.table = table;
                auto c0 = sqlite3_column_text(stmt, 0);
                auto c1 = sqlite3_column_text(stmt, 1);
                auto c2 = sqlite3_column_text(stmt, 2);
                row.player = c0 ? reinterpret_cast<char const*>(c0) : "";
                row.reason = c1 ? reinterpret_cast<char const*>(c1) : "";
                row.time = c2 ? reinterpret_cast<char const*>(c2) : "";
                result = row;
            }
            sqlite3_finalize(stmt);
        }
        if (result) break;
    }
    sqlite3_close(db);
    return result;
}

std::optional<BanRow> NDPR::lookupByIdentifier(std::string const& type, std::string const& value) {
    if (!std::filesystem::exists(mBanDbPath)) return std::nullopt;
    sqlite3* db = nullptr;
    if (sqlite3_open_v2(mBanDbPath.string().c_str(), &db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK) {
        if (db) sqlite3_close(db);
        return std::nullopt;
    }
    std::optional<BanRow> result;
    for (auto const& table : {"online", "offline"}) {
        auto cols = tableSchema(db, table, mSchemaCache, mDbSchemaMutex);
        std::string timeCol = cols.count("ban_time") ? "ban_time" : (cols.count("last_seen") ? "last_seen"
                                                                                            : std::string(std::strcmp(table, "offline") == 0 ? "ban_time" : "last_seen"));
        std::string col;
        if (type == "ip") col = "ip";
        else if (type == "ipv6") col = "ipv6";
        else {
            if (!cols.count("mcuuid")) continue;
            col = "mcuuid";
        }
        std::string sql = "SELECT player, ban_reason, " + timeCol + " FROM " + table + " WHERE " + col + " = ?";
        sqlite3_stmt* stmt = nullptr;
        if (sqlite3_prepare_v2(db, sql.c_str(), -1, &stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_text(stmt, 1, value.c_str(), -1, SQLITE_TRANSIENT);
            if (sqlite3_step(stmt) == SQLITE_ROW) {
                BanRow row;
                row.table = table;
                auto c0 = sqlite3_column_text(stmt, 0);
                auto c1 = sqlite3_column_text(stmt, 1);
                auto c2 = sqlite3_column_text(stmt, 2);
                row.player = c0 ? reinterpret_cast<char const*>(c0) : "";
                row.reason = c1 ? reinterpret_cast<char const*>(c1) : "";
                row.time = c2 ? reinterpret_cast<char const*>(c2) : "";
                result = row;
            }
            sqlite3_finalize(stmt);
        }
        if (result) break;
    }
    sqlite3_close(db);
    return result;
}

std::vector<std::string> NDPR::fuzzySearch(std::string const& query, int limit) {
    std::vector<std::string> matches;
    if (!std::filesystem::exists(mBanDbPath)) return matches;
    sqlite3* db = nullptr;
    if (sqlite3_open_v2(mBanDbPath.string().c_str(), &db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK) {
        if (db) sqlite3_close(db);
        return matches;
    }
    std::string lower = query;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    std::string pattern = "%" + lower + "%";
    for (auto const& table : {"online", "offline"}) {
        std::string sql = "SELECT player FROM " + std::string(table) +
                          " WHERE LOWER(player) LIKE ? LIMIT ?";
        sqlite3_stmt* stmt = nullptr;
        if (sqlite3_prepare_v2(db, sql.c_str(), -1, &stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_text(stmt, 1, pattern.c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_bind_int(stmt, 2, limit * 2);
            while (sqlite3_step(stmt) == SQLITE_ROW) {
                auto c = sqlite3_column_text(stmt, 0);
                if (c) {
                    std::string name = reinterpret_cast<char const*>(c);
                    if (std::find(matches.begin(), matches.end(), name) == matches.end()) {
                        matches.push_back(name);
                        if ((int)matches.size() >= limit) {
                            sqlite3_finalize(stmt);
                            sqlite3_close(db);
                            return matches;
                        }
                    }
                }
            }
            sqlite3_finalize(stmt);
        }
    }
    sqlite3_close(db);
    return matches;
}

// ================= 下载 =================

void NDPR::downloadBanDatabase(std::function<void(std::string)> const& reply) {
    auto send = [&](std::string const& msg) {
        if (reply) reply(msg);
    };
    if (mConfig.token.empty()) {
        std::string msg = tr("ndpr.warn.token_missing");
        mLogger->warn(msg);
        send("§c" + msg);
        return;
    }
    auto resp = Http::get(mConfig.apiUrl + "/bans/download", authHeaders(), 30);
    if (resp.status != 200) {
        std::string msg = tr("ndpr.error.db_download_http",
                             {{"code", std::to_string(resp.status)}, {"body", resp.body.substr(0, 200)}});
        mLogger->error(msg);
        send("§c" + msg);
        return;
    }
    Json data;
    try {
        data = Json::parse(resp.body);
    } catch (...) {
        std::string msg = tr("ndpr.error.db_download_no_url");
        mLogger->error(msg);
        send("§c" + msg);
        return;
    }
    if (!data.contains("url") || !data["url"].is_string()) {
        std::string msg = tr("ndpr.error.db_download_no_url");
        mLogger->error(msg);
        send("§c" + msg);
        return;
    }
    auto fileResp = Http::get(data["url"].get<std::string>(), {}, 60);
    if (fileResp.status != 200) {
        std::string msg = tr("ndpr.error.db_file_download_http", {{"code", std::to_string(fileResp.status)}});
        mLogger->error(msg);
        send("§c" + msg);
        return;
    }
    auto tmpPath = mBanDbPath.string() + ".tmp";
    {
        std::ofstream ofs(tmpPath, std::ios::binary);
        ofs.write(fileResp.body.data(), (std::streamsize)fileResp.body.size());
    }
    int count = validateDb(tmpPath);
    if (count < 0) {
        std::remove(tmpPath.c_str());
        std::string msg = tr("ndpr.error.db_file_invalid", {{"error", "invalid sqlite database"}});
        mLogger->error(msg);
        send("§c" + msg);
        return;
    }
    try {
        std::filesystem::rename(tmpPath, mBanDbPath);
    } catch (...) {
        std::filesystem::copy_file(tmpPath, mBanDbPath, std::filesystem::copy_options::overwrite_existing);
        std::remove(tmpPath.c_str());
    }
    clearSchemaCache();
    std::string detailMsg = tr("ndpr.log.db_updated", {{"count", std::to_string(count)}});
    mLogger->info(detailMsg);
    send("§a" + tr("ndpr.reply.db_download_success"));
    send("§7" + detailMsg);
    try {
        Http::postJson(mConfig.apiUrl + "/bans/download/done", {}, authHeaders(), 10);
    } catch (...) {
    }
}

void NDPR::asyncDownload(std::function<void(std::string)> const& reply) {
    if (mDownloadInflight.exchange(true)) {
        if (reply) reply("§e" + tr("ndpr.reply.download_inflight"));
        return;
    }
    mThreads.emplace_back([this, reply] {
        downloadBanDatabase(reply);
        mDownloadInflight = false;
    });
}

void NDPR::startDownloadTask() {
    if (mConfig.downloadInterval <= 0) {
        mLogger->info(tr("ndpr.log.auto_update_disabled"));
        return;
    }
    if (mDownloadLoopRunning.exchange(true)) return;
    mLogger->info(tr("ndpr.log.auto_update_started", {{"interval", std::to_string(mConfig.downloadInterval)}}));
    scheduleNextDownload(mConfig.downloadInterval);
}

void NDPR::scheduleNextDownload(int intervalSec) {
    ll::thread::ServerThreadExecutor::getDefault().executeAfter(
        [this, intervalSec] {
            if (mShutdown || !mDownloadLoopRunning) return;
            mThreads.emplace_back([this, intervalSec] {
                try {
                    downloadBanDatabase(nullptr);
                } catch (...) {
                }
                scheduleNextDownload(intervalSec);
            });
        },
        std::chrono::seconds(intervalSec));
}

// ================= 玩家信息 =================

void NDPR::savePlayerInfo(std::string const& player, std::string const& ip, std::string const& uuid) {
    std::lock_guard lock(mPlayerInfoMutex);
    Json info = Json::object();
    if (std::filesystem::exists(mPlayerInfoPath)) {
        try {
            std::ifstream ifs(mPlayerInfoPath);
            ifs >> info;
        } catch (...) {
            info = Json::object();
        }
    }
    Json entry;
    entry["ip"] = ip;
    entry["uuid"] = uuid;
    entry["ipv6"] = Json(nullptr);
    entry["timestamp"] = std::chrono::duration<double>(std::chrono::system_clock::now().time_since_epoch()).count();
    info[player] = entry;
    try {
        std::ofstream ofs(mPlayerInfoPath);
        ofs << info.dump(2);
    } catch (...) {
    }
}

Json NDPR::loadPlayerInfo(std::string const& player) {
    std::lock_guard lock(mPlayerInfoMutex);
    if (!std::filesystem::exists(mPlayerInfoPath)) return Json::object();
    try {
        std::ifstream ifs(mPlayerInfoPath);
        Json info;
        ifs >> info;
        if (info.contains(player) && info[player].is_object()) return info[player];
    } catch (...) {
    }
    return Json::object();
}

Json NDPR::loadHwidTemp() {
    std::lock_guard lock(mPlayerInfoMutex);
    if (!std::filesystem::exists(mHwidTempPath)) return Json::object();
    try {
        std::ifstream ifs(mHwidTempPath);
        Json j;
        ifs >> j;
        return j.is_object() ? j : Json::object();
    } catch (...) {
        return Json::object();
    }
}

void NDPR::saveHwidTemp(std::string const& player, std::string const& ip) {
    std::lock_guard lock(mPlayerInfoMutex);
    Json records = loadHwidTemp();
    Json entry;
    entry["ip"] = ip;
    entry["time"] = std::chrono::duration<double>(std::chrono::system_clock::now().time_since_epoch()).count();
    records[player] = entry;
    try {
        std::ofstream ofs(mHwidTempPath);
        ofs << records.dump(2);
    } catch (...) {
    }
}

// ================= 游戏操作（主线程派发）==================

void NDPR::executeCommand(std::string const& cmd) {
    ll::thread::ServerThreadExecutor::getDefault().execute([cmd] {
        auto level = ll::service::getLevel();
        if (!level) return;
        auto& serverLevel = static_cast<ServerLevel&>(*level);
        ServerCommandOrigin origin("ndpr", serverLevel, CommandPermissionLevel::Host, DimensionType(0));
        ll::command::CommandRegistrar::getInstance(false).executeCommand(cmd, origin);
    });
}

void NDPR::kickPlayer(std::string const& name, std::string const& reason) {
    ll::thread::ServerThreadExecutor::getDefault().execute([this, name, reason] {
        auto level = ll::service::getLevel();
        if (!level) return;
        level->forEachPlayer([&](Player& p) {
            if (p.getRealName() == name) {
                p.disconnect(reason);
                return false;
            }
            return true;
        });
    });
}

void NDPR::tellPlayer(std::string const& name, std::string const& msg) {
    ll::thread::ServerThreadExecutor::getDefault().execute([this, name, msg] {
        auto level = ll::service::getLevel();
        if (!level) return;
        level->forEachPlayer([&](Player& p) {
            if (p.getRealName() == name) {
                p.sendMessage(msg);
                return false;
            }
            return true;
        });
    });
}

void NDPR::reportKick() {
    try {
        Http::postJson(mConfig.apiUrl + "/stats/a", Json::object(), authHeaders(), 5);
    } catch (...) {
    }
}

// ================= 进服 / 离服 =================

void NDPR::onPlayerJoin(Player& player) {
    std::string name = player.getRealName();
    std::string ip = stripPort(player.getIPAndPort());
    std::string xuid = player.getXuid();
    std::string uuid = normalizeUuid(player.getUuid().asString());
    std::string playerUuid = uuid.empty() ? xuid : uuid;

    mLogger->info(tr("ndpr.log.player_info",
                     {{"player", name}, {"ip", ip.empty() ? "?" : ip}, {"uuid", playerUuid.empty() ? "?" : playerUuid}, {"ipv6", "?"}}));
    if (playerUuid.empty() && ip.empty()) {
        mLogger->warn(tr("ndpr.warn.player_info_parse_failed", {{"player", name}}));
    }
    savePlayerInfo(name, ip, playerUuid);

    std::string const kickInitializing = "§c" + tr("ndpr.kick.system_initializing");

    if (!std::filesystem::exists(mBanDbPath)) {
        mLogger->warn(tr("ndpr.warn.db_missing"));
        asyncDownload(nullptr);
        if (mConfig.failClosed) {
            mLogger->warn(tr("ndpr.warn.fail_closed_rejected", {{"player", name}}));
            kickPlayer(name, kickInitializing);
            return;
        }
        mLogger->warn(tr("ndpr.warn.fail_open_allowed", {{"player", name}}));
    } else {
        try {
            auto row = queryBan(name, ip, "", playerUuid);
            if (row) {
                mLogger->info(tr("ndpr.log.banned_detected", {{"player", name}, {"table", row->table}}));
                kickPlayer(name, "§c" + tr("ndpr.kick.banned"));
                reportKick();
                return;
            }
        } catch (std::exception const& e) {
            if (mConfig.failClosed) {
                mLogger->warn(tr("ndpr.warn.fail_closed_query_error", {{"player", name}, {"error", e.what()}}));
                kickPlayer(name, kickInitializing);
                return;
            }
            mLogger->warn(tr("ndpr.warn.fail_open_query_error", {{"player", name}, {"error", e.what()}}));
        }
    }

    if (mConfig.checkHwid) {
        startHwidVerify(name, ip, false);
    }
}

void NDPR::onPlayerLeft(Player& player) {
    std::string name = player.getRealName();
    std::shared_ptr<VerifySession> session;
    {
        std::lock_guard lock(mVerifyMutex);
        auto it = mVerifySessions.find(name);
        if (it != mVerifySessions.end()) {
            session = it->second;
            mVerifySessions.erase(it);
        }
    }
    if (session) {
        session->cancel = true;
        if (!session->sessionId.empty()) {
            std::string sid = session->sessionId;
            mThreads.emplace_back([this, sid] { cancelApiSession(sid); });
        }
    }
}

// ================= HWID 验证 =================

std::optional<Json> NDPR::createVerifySession(std::string const& player, std::string const& ip) {
    Json payload;
    payload["player_id"] = player;
    if (!ip.empty()) payload["ip"] = ip;
    return postJson(mConfig.apiUrl + "/hwid/upd", payload, authHeaders(), 10);
}

std::optional<Json> NDPR::checkVerifyStatus(std::string const& sessionId) {
    Json payload;
    payload["session_id"] = sessionId;
    return postJson(mConfig.apiUrl + "/hwid/upd/check", payload, authHeaders(), 3);
}

std::optional<Json> NDPR::queryHasHwid(std::string const& player) {
    Json payload;
    payload["player_id"] = player;
    return postJson(mConfig.apiUrl + "/hwid/has", payload, authHeaders(), 5);
}

void NDPR::cancelApiSession(std::string const& sessionId) {
    try {
        Json payload;
        payload["session_id"] = sessionId;
        postJson(mConfig.apiUrl + "/hwid/upd/cancel", payload, authHeaders(), 5);
    } catch (...) {
    }
}

void NDPR::startHwidVerify(std::string const& player, std::string const& ip, bool force) {
    if (!force && !mConfig.checkHwid) return;
    if (mConfig.token.empty()) {
        mLogger->warn(tr("ndpr.warn.token_missing_hwid"));
        return;
    }
    bool firstVerify = true;
    if (!force) {
        Json records = loadHwidTemp();
        if (records.contains(player) && records[player].is_object()) {
            double lastTime = records[player].value("time", 0.0);
            double now = std::chrono::duration<double>(std::chrono::system_clock::now().time_since_epoch()).count();
            if (now - lastTime < (double)mConfig.checkInterval * 86400.0) return;
            firstVerify = false;
        }
    }
    std::shared_ptr<VerifySession> session = std::make_shared<VerifySession>();
    session->player = player;
    session->ip = ip;
    session->firstVerify = firstVerify;
    {
        std::lock_guard lock(mVerifyMutex);
        auto it = mVerifySessions.find(player);
        if (it != mVerifySessions.end()) {
            it->second->cancel = true;
            if (!it->second->sessionId.empty()) cancelApiSession(it->second->sessionId);
        }
        mVerifySessions[player] = session;
    }
    mThreads.emplace_back([this, session] { runVerify(session); });
}

void NDPR::runVerify(std::shared_ptr<VerifySession> session) {
    std::string const& name = session->player;

    // 在主线程安全采集玩家初始状态（坐标/模式）
    std::atomic<bool>   done{false};
    bool                found = false;
    int                 gameMode = 0;
    std::optional<std::array<double, 3>> freezePos;
    ll::thread::ServerThreadExecutor::getDefault().execute([&] {
        auto level = ll::service::getLevel();
        if (level) {
            level->forEachPlayer([&](Player& p) {
                if (p.getRealName() == name) {
                    found = true;
                    gameMode = (int)p.getPlayerGameType();
                    auto pos = p.getPosition();
                    freezePos = std::array<double, 3>{pos.x, pos.y, pos.z};
                    return false;
                }
                return true;
            });
        }
        done = true;
    });
    for (int i = 0; i < 200 && !done.load(); i++) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    if (!found) {
        std::lock_guard lock(mVerifyMutex);
        mVerifySessions.erase(name);
        return;
    }

    session->originalGameMode = gameMode;
    session->freezePos = freezePos;
    if (!session->freezePos) {
        mLogger->warn(tr("ndpr.warn.no_anchor_pos", {{"player", name}}));
    }

    executeCommand("gamerule sendcommandfeedback false");
    executeCommand("effect give \"" + name + "\" blindness 999999 0 true");
    executeCommand("gamemode adventure \"" + name + "\"");
    std::string titleText = tr("ndpr.title.verify");
    std::string subtitleText = tr("ndpr.subtitle.verify");
    titleText.erase(std::remove(titleText.begin(), titleText.end(), '"'), titleText.end());
    subtitleText.erase(std::remove(subtitleText.begin(), subtitleText.end(), '"'), subtitleText.end());
    Json titleJson;
    titleJson["rawtext"] = Json::array({Json{{"text", titleText}}});
    Json subtitleJson;
    subtitleJson["rawtext"] = Json::array({Json{{"text", subtitleText}}});
    executeCommand("title \"" + name + "\" title " + titleJson.dump());
    executeCommand("title \"" + name + "\" subtitle " + subtitleJson.dump());
    if (session->firstVerify) {
        tellPlayer(name, "§e" + tr("ndpr.tell.verify_enabled"));
    }

    auto result = createVerifySession(name, session->ip);
    if (!result || !result->contains("session_id")) {
        mLogger->error(tr("ndpr.error.verify_start_failed", {{"player", name}}));
        kickPlayer(name, "§c" + tr("ndpr.kick.verify_unavailable"));
        std::lock_guard lock(mVerifyMutex);
        mVerifySessions.erase(name);
        return;
    }
    session->sessionId = (*result)["session_id"].get<std::string>();

    int verifyTimeout = mConfig.verifyTimeout < 30 ? 60 : mConfig.verifyTimeout;
    long long now = (long long)(std::chrono::duration<double>(std::chrono::system_clock::now().time_since_epoch()).count());
    long long rawExpires = result->contains("expires_at") ? result->value("expires_at", 0LL) : 0LL;
    if (rawExpires <= 0) rawExpires = now + verifyTimeout;
    long long expiresAt = std::min(std::max(rawExpires, now + 30), now + verifyTimeout);
    std::string verifyUrl = result->value("verify_url", "");

    tellPlayer(name, "§e" + tr("ndpr.tell.click_verify") + verifyUrl);
    tellPlayer(name, "§7" + tr("ndpr.tell.verify_freeze_notice"));

    int freezeInterval = mConfig.freezeInterval < 1 ? 1 : mConfig.freezeInterval;
    long long lastFreeze = 0;
    bool kicked = false;

    while (!session->cancel.load() && !mShutdown) {
        long long cur = (long long)(std::chrono::duration<double>(std::chrono::system_clock::now().time_since_epoch()).count());
        if (cur >= expiresAt) break;
        if (session->freezePos && cur - lastFreeze >= freezeInterval) {
            auto const& p = *session->freezePos;
            std::ostringstream oss;
            oss << "tp \"" << name << "\" " << std::fixed << std::setprecision(2) << p[0] << " " << p[1] << " " << p[2];
            executeCommand(oss.str());
            lastFreeze = cur;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1000));
        if (session->cancel.load() || mShutdown) break;

        auto status = checkVerifyStatus(session->sessionId);
        if (!status) continue;
        if (status->value("completed", false)) {
            bool banned;
            std::string banReason;
            if (status->contains("banned")) {
                banned = status->value("banned", false);
                banReason = status->value("reason", "");
            } else {
                auto has = queryHasHwid(name);
                if (!has) {
                    kickPlayer(name, "§c" + tr("ndpr.kick.hwid_status_unknown"));
                    kicked = true;
                    break;
                }
                banned = has->value("banned", false);
                banReason = has->value("reason", "");
            }
            if (banned) {
                std::string reason = banReason.empty() ? tr("ndpr.word.hwid_banned") : banReason;
                kickPlayer(name, "§c" + tr("ndpr.kick.banned_with_reason", {{"reason", reason}}));
                reportKick();
            } else {
                saveHwidTemp(name, session->ip);
                tellPlayer(name, "§a" + tr("ndpr.tell.verify_done"));
            }
            kicked = true;
            break;
        }
        std::string st = status->value("status", "");
        if (st == "cancelled") {
            kicked = true;
            break;
        }
        if (st == "expired") break;
    }

    // 解冻
    executeCommand("effect clear \"" + name + "\" blindness");
    std::string gm = "survival";
    switch ((GameType)session->originalGameMode) {
        case GameType::Creative:
            gm = "creative";
            break;
        case GameType::Adventure:
            gm = "adventure";
            break;
        case GameType::Spectator:
            gm = "spectator";
            break;
        default:
            break;
    }
    executeCommand("gamemode " + gm + " \"" + name + "\"");
    executeCommand("gamerule sendcommandfeedback true");

    if (!kicked && !session->cancel.load() && !mShutdown) {
        kickPlayer(name, "§c" + tr("ndpr.kick.verify_timeout"));
    }
    std::lock_guard lock(mVerifyMutex);
    auto it = mVerifySessions.find(name);
    if (it != mVerifySessions.end() && it->second == session) mVerifySessions.erase(it);
}

// ================= 命令 =================

void NDPR::handleCommand(Player* player, bool isAdmin, std::string const& sub, std::string const& target,
                         std::string const& reason) {
    // 线程安全的回复：玩家按名在主线程派发；控制台走日志
    std::string replyName = player ? player->getRealName() : "";
    auto reply = [this, replyName](std::string const& msg) {
        if (!replyName.empty()) {
            tellPlayer(replyName, msg);
        } else {
            mLogger->info(msg);
        }
    };
    auto requireAdmin = [&]() -> bool {
        if (!isAdmin) {
            reply("§c" + tr("ndpr.reply.permission_denied"));
            return false;
        }
        return true;
    };

    std::string s = sub;
    std::transform(s.begin(), s.end(), s.begin(), ::tolower);
    try {
        if (s == "help" || s.empty()) {
            cmdHelp(reply);
        } else if (s == "d" || s == "download") {
            if (!requireAdmin()) return;
            reply("§e" + tr("ndpr.reply.downloading"));
            asyncDownload(reply);
        } else if (s == "ban") {
            if (!requireAdmin()) return;
            if (target.empty() || reason.empty()) {
                reply("§c" + tr("ndpr.reply.ban_reason_required"));
                reply("§7" + tr("ndpr.reply.ban_usage"));
                return;
            }
            cmdBan(reply, target, reason);
        } else if (s == "check") {
            if (target.empty()) {
                reply("§7" + tr("ndpr.reply.ban_usage"));
                return;
            }
            cmdCheck(reply, target);
        } else if (s == "reload") {
            if (!requireAdmin()) return;
            mThreads.emplace_back([this, reply] { cmdReload(reply); });
        } else if (s == "cu" || s == "checkupdate") {
            if (!requireAdmin()) return;
            mThreads.emplace_back([this, reply] { checkPluginUpdate(reply); });
        } else if (s == "auth") {
            if (!requireAdmin()) return;
            if (target.empty()) {
                reply("§7" + tr("ndpr.reply.auth_usage"));
                return;
            }
            reply("§e" + tr("ndpr.reply.auth_starting", {{"player", target}}));
            Json info = loadPlayerInfo(target);
            std::string ip = info.value("ip", "");
            startHwidVerify(target, ip, true);
        } else {
            cmdHelp(reply);
        }
    } catch (std::exception const& e) {
        reply("§c" + tr("ndpr.reply.query_failed", {{"error", e.what()}}));
    }
}

void NDPR::cmdHelp(std::function<void(std::string)> const& reply) {
    reply("§6========== §b" + tr("ndpr.help.title") + " §6==========");
    reply("§e" + tr("ndpr.help.version", {{"version", VERSION}}));
    reply("§e" + tr("ndpr.help.author"));
    reply(tr("ndpr.help.qq_group"));
    reply("");
    reply("§b" + tr("ndpr.help.commands"));
    reply("§f/ndpr help §7- " + tr("ndpr.help.desc.help"));
    reply("§f/ndpr d / download §7- " + tr("ndpr.help.desc.download"));
    reply("§f/ndpr ban <ID> <reason> §7- " + tr("ndpr.help.desc.ban"));
    reply("§f/ndpr check <ID/IP/UUID> §7- " + tr("ndpr.help.desc.check"));
    reply("§f/ndpr reload §7- " + tr("ndpr.help.desc.reload"));
    reply("§f/ndpr cu / checkupdate §7- " + tr("ndpr.help.desc.checkupdate"));
    reply("§f/ndpr auth <ID> §7- " + tr("ndpr.help.desc.auth"));
    reply("");
    reply(tr("ndpr.help.footer"));
}

void NDPR::cmdCheck(std::function<void(std::string)> const& reply, std::string const& target) {
    if (!std::filesystem::exists(mBanDbPath)) {
        reply("§c" + tr("ndpr.reply.no_data"));
        return;
    }
    try {
        std::string idType = detectIdentifierType(target);
        std::optional<BanRow> row;
        if (idType == "id") {
            row = lookupByPlayer(target);
        } else {
            row = lookupByIdentifier(idType, target);
        }
        if (row) {
            // 按玩家名查询时首列为 IP
            reply("§c" + tr("ndpr.reply.banned_in_table", {{"player", target}, {"table", row->table}}));
            reply("§7" + tr("ndpr.label.ip", {{"ip", row->player}}));
            reply("§7" + tr("ndpr.label.reason", {{"reason", row->reason}}));
            reply("§7" + tr("ndpr.label.ban_time", {{"time", row->time}}));
        } else if (idType == "id") {
            reply("§a" + tr("ndpr.reply.not_banned", {{"player", target}}));
            auto matches = fuzzySearch(target, 5);
            if (!matches.empty()) {
                reply("§7" + tr("ndpr.reply.fuzzy_suggestion"));
                for (auto const& nm : matches) reply("§8" + nm);
            }
        } else {
            reply(tr("ndpr.reply.record_not_found", {{"type", idType}, {"value", target}}));
        }
    } catch (std::exception const& e) {
        reply("§c" + tr("ndpr.reply.query_failed", {{"error", e.what()}}));
    }
}

void NDPR::cmdBan(std::function<void(std::string)> const& reply, std::string const& playerName,
                  std::string const& reason) {
    if (mConfig.token.empty()) {
        reply("§c" + tr("ndpr.reply.token_not_configured"));
        return;
    }
    reply("§e" + tr("ndpr.reply.getting_player_info", {{"player", playerName}}));
    Json info = loadPlayerInfo(playerName);
    if (info.empty()) {
        reply("§c" + tr("ndpr.reply.player_info_not_found"));
        reply("§7" + tr("ndpr.reply.player_info_hint"));
        return;
    }
    std::string playerIp = info.value("ip", "");
    std::string playerUuid = info.value("uuid", "");
    std::string infoList;
    if (!playerIp.empty()) infoList += "IP: " + playerIp;
    if (!playerUuid.empty()) {
        if (!infoList.empty()) infoList += ", ";
        infoList += "UUID: " + playerUuid;
    }
    if (!infoList.empty()) reply("§e" + tr("ndpr.reply.info_obtained", {{"info", infoList}}));
    reply("§e" + tr("ndpr.reply.ban_reason_echo", {{"reason", reason}}));
    reply("§e" + tr("ndpr.reply.submitting"));

    Json payload;
    payload["player_id"] = playerName;
    payload["ip"] = playerIp.empty() ? Json(nullptr) : Json(playerIp);
    payload["ipv6"] = Json(nullptr);
    payload["uuid"] = playerUuid.empty() ? Json(nullptr) : Json(playerUuid);
    payload["onlinemode"] = mConfig.onlinemode;
    payload["reason"] = reason;
    mThreads.emplace_back([this, reply, payload] {
        try {
            HttpResponse resp = Http::postJson(mConfig.apiUrl + "/check/uploader", payload.dump(), authHeaders(), 10);
            if (resp.status == 200) {
                try {
                    Json result = Json::parse(resp.body);
                    if (result.value("result", "") == "success") {
                        reply("§a" + tr("ndpr.reply.submit_success"));
                        reply("§7" + tr("ndpr.reply.check_id", {{"check_id", result.value("check_id", "")}}));
                        reply("§7" + tr("ndpr.reply.wait_review"));
                    } else {
                        reply("§c" + tr("ndpr.reply.submit_failed",
                                        {{"message", result.value("message", tr("ndpr.reply.unknown_error"))}}));
                    }
                } catch (...) {
                    reply("§c" + tr("ndpr.reply.unknown_error"));
                }
            } else if (resp.status == 403) {
                reply("§c" + tr("ndpr.reply.no_upload_permission"));
            } else {
                reply("§c" + tr("ndpr.reply.submit_failed_http", {{"code", std::to_string(resp.status)}}));
                try {
                    Json result = Json::parse(resp.body);
                    reply("§7" + tr("ndpr.reply.error_info",
                                    {{"error", result.value("error", tr("ndpr.reply.unknown_error"))}}));
                } catch (...) {
                }
            }
        } catch (...) {
            reply("§c" + tr("ndpr.reply.connection_error"));
        }
    });
}

void NDPR::cmdReload(std::function<void(std::string)> const& reply) {
    reply("§e" + tr("ndpr.reply.reloading"));
    loadConfig();
    downloadBanDatabase(reply);
    reply("§a" + tr("ndpr.reply.reloaded"));
}

// ================= UUID =================

void NDPR::obtainUuid() {
    if (!mConfig.uuid.empty()) return;
    if (mConfig.apiUrl.empty()) throw std::runtime_error("Error: " + tr("ndpr.error.api_url_missing"));
    mLogger->info(tr("ndpr.log.getting_uuid"));
    try {
        HttpResponse resp = Http::postJson(mConfig.apiUrl + "/uuid/getuuid", {}, {}, 10);
        if (resp.status != 200) {
            throw std::runtime_error("Error: " + tr("ndpr.error.get_uuid_http",
                                                    {{"code", std::to_string(resp.status)}, {"body", resp.body.substr(0, 200)}}));
        }
        Json data = Json::parse(resp.body);
        if (!data.contains("uuid")) {
            throw std::runtime_error("Error: " + tr("ndpr.error.get_uuid_invalid", {{"data", resp.body.substr(0, 200)}}));
        }
        mConfig.uuid = data["uuid"].get<std::string>();
        saveConfig();
        mLogger->info(tr("ndpr.log.uuid_obtained", {{"uuid", mConfig.uuid}}));
    } catch (std::exception const& e) {
        throw;
    }
}

// ================= 更新检查 =================

void NDPR::checkPluginUpdate(std::function<void(std::string)> const& reply) {
    if (reply) reply("§a" + tr("ndpr.reply.checking_update"));
    try {
        HttpResponse resp = Http::get("https://api.github.com/repos/NDPReforged/NDPR-BE/releases/latest", {}, 30);
        if (resp.status != 200) {
            if (reply) reply("§c" + tr("ndpr.reply.query_failed", {{"error", "HTTP " + std::to_string(resp.status)}}));
            return;
        }
        Json data = Json::parse(resp.body);
        std::string latest = data.value("tag_name", "");
        if (!latest.empty() && latest[0] == 'v') latest.erase(0, 1);
        // 版本比较
        bool hasUpdate = false;
        try {
            std::vector<int> cur, lat;
            std::stringstream sc(VERSION), sl(latest);
            std::string part;
            while (std::getline(sc, part, '.')) cur.push_back(std::stoi(part));
            while (std::getline(sl, part, '.')) lat.push_back(std::stoi(part));
            size_t n = std::max(cur.size(), lat.size());
            cur.resize(n, 0);
            lat.resize(n, 0);
            for (size_t i = 0; i < n; i++) {
                if (lat[i] > cur[i]) {
                    hasUpdate = true;
                    break;
                }
                if (lat[i] < cur[i]) break;
            }
        } catch (...) {
            hasUpdate = false;
        }
        if (hasUpdate) {
            if (reply) {
                reply("§a" + tr("ndpr.reply.update_found"));
                reply("§a" + tr("ndpr.reply.current_version", {{"version", VERSION}}));
                reply("§a" + tr("ndpr.reply.latest_version", {{"version", latest}}));
                std::string notes = data.value("body", "");
                if (!notes.empty()) {
                    if (notes.size() > 100) notes = notes.substr(0, 100) + "...";
                    reply("§a" + tr("ndpr.reply.update_notes", {{"notes", notes}}));
                }
                reply("§a" + tr("ndpr.reply.download_url", {{"url", data.value("html_url", "")}}));
            }
            mLogger->info(tr("ndpr.log.update_found",
                             {{"latest", latest}, {"current", VERSION}, {"url", data.value("html_url", "")}}));
        } else if (reply) {
            reply("§a" + tr("ndpr.reply.up_to_date", {{"version", VERSION}}));
        }
    } catch (std::exception const& e) {
        if (reply) reply("§c" + tr("ndpr.reply.query_failed", {{"error", e.what()}}));
    }
}

} // namespace ndpr
