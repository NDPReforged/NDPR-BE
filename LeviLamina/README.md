# NDPR - LeviLamina 客户端（C++ / LLAPI）

NDPReforged 封禁系统的 LeviLamina（基岩版 BDS 插件加载器）原生 C++ 插件，。

## 功能

- 多维封禁检查（玩家名 / XUID / UUID / IP）
- 云端封禁数据库自动下载（SQLite，可配置间隔）
- 玩家进服自动检查 + 踢出 + 拦截统计上报
- 封禁审核提交（`/ndpr ban`）
- HWID 设备验证（冻结 + 网页验证 + 超时踢出）
- 封禁查询（含 IP/UUID 自动识别与模糊建议）
- 插件更新检查（GitHub Releases）
- 中英双语

## 构建（需要 xmake + Visual Studio 2022）

```bash
# 1. 安装 xmake、VS2022（含 C++ 桌面开发）、git
# 2. 克隆 LeviLamina 并安装 lip 依赖（参考官方文档安装 LeviLamina 本体）
# 3. 在本目录构建（与社区插件惯例一致）
xmake f -y -p windows -a x64 -m release
xmake build ndpr
```

构建产物在 `bin/ndpr/`（含 `ndpr.dll` 与 `manifest.json`），整体复制到 LeviLamina 的
`plugins/` 目录后启动 `bedrock_server_mod.exe`（发布布局与 Phantom 等社区插件一致：
`plugins/ndpr/{ndpr.dll, manifest.json}`）。

> 依赖：xmake 会自动拉取 `levilamina`（liteldev-repo）与 `levibuildscript` 包；
> 网络不佳时可先 `xmake f --pkg_searchdirs=...` 离线放置包缓存。
> 本项目内嵌了 `sqlite3`（amalgamation）与 `nlohmann/json`（单头），HTTP 使用系统
> WinHTTP，无其他第三方依赖。

## 配置

首次启动自动生成 `plugins/ndpr/config/config.json`（数据在 `plugins/ndpr/data/`）：

| 键 | 默认 | 说明 |
|---|---|---|
| api_url | `https://api.ndpreforged.com` | 后端 API |
| language | `zh_CN` | `zh_CN` / `en_us` |
| token | `""` | 必填（启用封禁功能） |
| uuid | `""` | 首次启动自动获取 |
| onlinemode | `true` | 上传审核时透传 |
| download_interval | `900` | 封禁库更新间隔（秒），0=禁用 |
| check_hwid | `false` | 启用 HWID 验证 |
| check_interval | `3` | HWID 免验天数 |
| fail_closed | `false` | 封禁库缺失时拒绝进服 |
| verify_timeout | `60` | 验证超时（30~600 秒） |
| freeze_interval | `1` | 冻结传送间隔（秒） |
| admins | `[]` | 管理员名单（玩家名或 XUID）备用 |

> `log_path` / `logger_mode` / `logger_format` 为兼容旧版配置保留，基岩版忽略。

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/ndpr` / `/ndpr help` | 所有人 | 帮助 |
| `/ndpr d` / `/ndpr download` | OP（权限等级≥2） | 手动下载封禁库 |
| `/ndpr ban <玩家> <原因...>` | OP | 提交封禁审核 |
| `/ndpr check <ID/IP/UUID>` | 所有人 | 查询封禁状态 |
| `/ndpr reload` | OP | 重载配置并重新下载 |
| `/ndpr cu` / `/ndpr checkupdate` | OP | 检查插件更新 |
| `/ndpr auth <玩家>` | OP | 强制触发 HWID 验证 |

控制台同样可执行 `/ndpr ...`。

## 实现说明

- 事件：`ll::event::player::PlayerJoinEvent` / `PlayerDisconnectEvent`。
- 命令：`ll::command::CommandRegistrar` 单 overload（sub/target/reason 全可选）分发，
  reason 使用 `CommandRawText`（贪婪参数，支持多词原因）。
- 游戏操作（执行命令/踢出/消息）经 `ll::thread::ServerThreadExecutor` 派发到主线程，
  踢出与消息按玩家名在主线程重新解析，避免悬垂引用。
- HTTP：WinHTTP（系统自带）；SQLite：内嵌 amalgamation（只读查询）。
- 版本要求：LeviLamina（develop 分支 / 近期 release，API 与 `ll::mod`/`ll::command` 一致）。

## 目录结构

```
LeviLamina/
├── xmake.lua
├── manifest.json
└── src/
    ├── Mod.h / Mod.cpp        # 插件入口（LL_REGISTER_MOD）
    ├── NDPR.h / NDPR.cpp      # 业务核心（配置/翻译/下载/进服检查/HWID 验证/命令）
    ├── Http.h / Http.cpp      # WinHTTP 客户端
    ├── Translations.h         # 内置翻译（自动生成自 lang/*.json）
    └── vendor/
        ├── sqlite3.c / sqlite3.h
        └── nlohmann/json.hpp
```
