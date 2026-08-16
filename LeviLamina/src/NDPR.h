#pragma once
// NDPR - LeviLamina 业务核心（C++）
#include <array>
#include <atomic>
#include <filesystem>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "ll/api/io/Logger.h"
#include "mc/world/actor/player/Player.h"

#include "vendor/nlohmann/json.hpp"

namespace ndpr {

// 简单 JSON 值（复用 nlohmann）
using Json = nlohmann::json;

struct NDPRConfig {
    std::string              apiUrl            = "https://api.ndpreforged.com";
    std::string              language          = "zh_CN";
    std::string              token             = "";
    std::string              uuid              = "";
    bool                     onlinemode        = true;
    std::string              logPath           = "server/logs/latest.log"; // 兼容保留
    std::string              loggerMode        = "default";                // 兼容保留
    std::string              loggerFormat      = "<[%n%]%name%>%s%<%message%>"; // 兼容保留
    int                      downloadInterval  = 900;
    bool                     checkHwid         = false;
    int                      checkInterval     = 3;
    bool                     failClosed        = false;
    int                      verifyTimeout     = 60;
    int                      freezeInterval    = 1;
    std::vector<std::string> admins;
};

struct BanRow {
    std::string table;
    std::string player;
    std::string reason;
    std::string time;
};

class NDPR {
public:
    static NDPR& getInstance();

    void init(ll::io::Logger& logger, std::filesystem::path const& configDir,
              std::filesystem::path const& dataDir);
    void shutdown();

    // ===== 事件（Mod 层调用）=====
    void onPlayerJoin(Player& player);
    void onPlayerLeft(Player& player);

    // ===== 命令（Mod 层调用）=====
    // player 为 nullptr 表示控制台（回复经日志输出）
    void handleCommand(Player* player, bool isAdmin, std::string const& sub, std::string const& target,
                       std::string const& reason);

    // ===== 工具 =====
    std::string tr(std::string const& key,
                   std::map<std::string, std::string> const& kwargs = {}) const;

private:
    NDPR() = default;
    ~NDPR() = default;
    NDPR(NDPR const&) = delete;
    NDPR& operator=(NDPR const&) = delete;

    // ---------- 配置 ----------
    bool loadConfig();
    void saveConfig();

    // ---------- HTTP ----------
    std::map<std::string, std::string> authHeaders() const;
    std::optional<Json> postJson(std::string const& url, Json const& payload,
                                 std::map<std::string, std::string> const& headers, int timeoutSec);
    std::optional<Json> getJson(std::string const& url, std::map<std::string, std::string> const& headers,
                                int timeoutSec);

    // ---------- 数据库 ----------
    void clearSchemaCache();
    std::optional<BanRow> queryBan(std::string const& player, std::string const& ip, std::string const& ipv6,
                                   std::string const& mcuuid);
    std::optional<BanRow> lookupByPlayer(std::string const& player);
    std::optional<BanRow> lookupByIdentifier(std::string const& type, std::string const& value);
    std::vector<std::string> fuzzySearch(std::string const& query, int limit);
    int validateDb(std::string const& dbPath);

    // ---------- 下载 ----------
    void downloadBanDatabase(std::function<void(std::string)> const& reply);
    void asyncDownload(std::function<void(std::string)> const& reply);
    void startDownloadTask();
    void scheduleNextDownload(int intervalSec);

    // ---------- 初始化 ----------
    void obtainUuid();
    void checkPluginUpdate(std::function<void(std::string)> const& reply);
    void asyncInit();

    // ---------- 玩家信息 ----------
    void savePlayerInfo(std::string const& player, std::string const& ip, std::string const& uuid);
    Json loadPlayerInfo(std::string const& player);
    Json loadHwidTemp();
    void saveHwidTemp(std::string const& player, std::string const& ip);

    // ---------- HWID 验证 ----------
    struct VerifySession {
        std::atomic<bool> cancel{false};
        std::string       sessionId;
        std::string       player;
        std::string       ip;
        bool              firstVerify = true;
        int               originalGameMode = 0; // GameType
        std::optional<std::array<double, 3>> freezePos;
    };
    void startHwidVerify(std::string const& player, std::string const& ip, bool force);
    void runVerify(std::shared_ptr<VerifySession> session);
    std::optional<Json> createVerifySession(std::string const& player, std::string const& ip);
    std::optional<Json> checkVerifyStatus(std::string const& sessionId);
    std::optional<Json> queryHasHwid(std::string const& player);
    void cancelApiSession(std::string const& sessionId);

    // ---------- 命令实现 ----------
    void cmdHelp(std::function<void(std::string)> const& reply);
    void cmdCheck(std::function<void(std::string)> const& reply, std::string const& target);
    void cmdBan(std::function<void(std::string)> const& reply, std::string const& player,
                std::string const& reason);
    void cmdReload(std::function<void(std::string)> const& reply);

    // ---------- 游戏操作（主线程派发，按玩家名解析，避免悬垂引用）----------
    void executeCommand(std::string const& cmd);
    void kickPlayer(std::string const& name, std::string const& reason);
    void tellPlayer(std::string const& name, std::string const& msg);
    void reportKick();

    // ---------- 状态 ----------
    ll::io::Logger*             mLogger = nullptr;
    std::filesystem::path       mConfigDir;
    std::filesystem::path       mDataDir;
    std::filesystem::path       mConfigPath;
    std::filesystem::path       mBanDbPath;
    std::filesystem::path       mPlayerInfoPath;
    std::filesystem::path       mHwidTempPath;
    NDPRConfig                  mConfig;
    std::mutex                  mConfigMutex;
    std::mutex                  mDbSchemaMutex;
    std::map<std::string, std::set<std::string>> mSchemaCache;
    std::mutex                  mPlayerInfoMutex;
    std::atomic<bool>           mDownloadInflight{false};
    std::atomic<bool>           mDownloadLoopRunning{false};
    std::atomic<bool>           mShutdown{false};
    std::mutex                  mVerifyMutex;
    std::map<std::string, std::shared_ptr<VerifySession>> mVerifySessions;
    std::vector<std::thread>    mThreads;
};

} // namespace ndpr
