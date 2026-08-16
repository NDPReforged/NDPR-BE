# NDPR - Allay 客户端

NDPReforged 封禁系统的 Allay（基岩版 Java 服务端）插件，。

业务逻辑位于共享核心 `../java-core`（ndpr.core 包），与 **gomint / Nukkit 三平台共用同一份核心代码**，
本目录只包含平台适配层（事件 / 命令 / 玩家对象）。

## 构建

```bash
# 需要 JDK 21 与 Gradle
./gradlew shadowJar
# 产物：build/libs/ndpr-allay-2.1.0.jar（或 *-shaded.jar）
```

将 jar 放入 Allay 的 `plugins/` 目录，重启服务端。
首次启动生成 `plugins/NDPR/config.json`（数据在 `plugins/NDPR/ndpr_data/`）。

> Allay API 版本在 `build.gradle.kts` 的 `allay { api = "0.29.0" }` 中配置，
> 可到 https://central.sonatype.com/artifact/org.allaymc.allay/api 查看最新版本。

## 配置（config.json）

配置字段：`api_url` / `language` / `token` / `uuid` / `onlinemode` /
`download_interval` / `check_hwid` / `check_interval` / `fail_closed` / `verify_timeout` /
`freeze_interval`，另加 `admins`（管理员名单，玩家名或 XUID 备用）。
`log_path` / `logger_mode` / `logger_format` 为兼容保留，基岩版忽略。

获取 Token：控制台日志查看 UUID → https://ndpreforged.com 绑定邮箱 → 填入 `token` → `/ndpr reload`。

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/ndpr` / `/ndpr help` | 所有人 | 帮助 |
| `/ndpr d` / `/ndpr download` | OP | 手动下载封禁库 |
| `/ndpr ban <玩家> <原因...>` | OP | 提交封禁审核 |
| `/ndpr check <ID/IP/UUID>` | 所有人 | 查询封禁状态 |
| `/ndpr reload` | OP | 重载配置并重新下载 |
| `/ndpr cu` / `/ndpr checkupdate` | OP | 检查插件更新 |
| `/ndpr auth <玩家>` | OP | 强制触发 HWID 验证 |

## 实现说明

- 事件：`PlayerJoinEvent` / `PlayerDisconnectEvent`（`org.allaymc.api.eventbus.event.server`），
  经 `Server.getInstance().getEventBus().registerListener(...)` 注册。
- 玩家信息：`getOriginName()`、`getSocketAddress()`（IP）、`getLoginData().getXuid()/getUuid()`。
- **Allay 无插件执行控制台命令的 API**，因此冻结/效果/模式/传送全部使用原生 API：
  `player.disconnect()`、`sendTitle()/sendSubtitle()`、`entity.teleport(Location3d)`、
  `setGameMode(GameMode)`、`addEffect(new EffectInstance(...))`、`removeEffect(...)`。
- 异步任务使用 `Server.getInstance().getVirtualThreadPool()` 与调度器。
- SQLite 驱动（sqlite-jdbc）已随插件打包（shadowJar）。
