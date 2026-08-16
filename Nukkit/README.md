# NDPR - Nukkit 客户端

NDPReforged 封禁系统的 Nukkit（基岩版 Java 服务端）插件，。
兼容 **Cloudburst Nukkit** 与 **PowerNukkitX**（API 兼容）。

业务逻辑位于共享核心 `../java-core`（ndpr.core 包），与 **Allay / gomint 三平台共用同一份核心代码**，
本目录只包含平台适配层（事件 / 命令 / 玩家对象）。

## 构建

```bash
# 需要 JDK 8+ 与 Gradle（或使用 gradlew）
gradle shadowJar
# 产物：build/libs/ndpr-nukkit-2.1.0.jar
```

将 jar 放入 Nukkit 的 `plugins/` 目录，重启服务端。
首次启动生成 `plugins/NDPR/config.json`（数据在 `plugins/NDPR/ndpr_data/`）。

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
| `/ndpr ban <玩家> <原因>` | OP | 提交封禁审核 |
| `/ndpr check <ID/IP/UUID>` | 所有人 | 查询封禁状态 |
| `/ndpr reload` | OP | 重载配置并重新下载 |
| `/ndpr cu` / `/ndpr checkupdate` | OP | 检查插件更新 |
| `/ndpr auth <玩家>` | OP | 强制触发 HWID 验证 |

## 实现说明

- 进服/离服：`PlayerJoinEvent` / `PlayerQuitEvent`（`@EventHandler`）。
- 玩家信息：`getAddress()`（IP）、`getLoginChainData().getXUID()`（XUID）、`getUniqueId()`（UUID）。
- 踢出：`player.kick(reason)`；命令执行：`server.dispatchCommand(consoleSender, cmd)`；
  冻结/效果/模式经游戏内命令实现。
- SQLite 驱动（sqlite-jdbc）已随插件打包（relocate 到 `ndpr.shadow.sqlite`）。
