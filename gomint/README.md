# NDPR - gomint 客户端

NDPReforged 封禁系统的 gomint（GoMint 2.x，基岩版 Java 服务端）插件，。

业务逻辑位于共享核心 `../java-core`（ndpr.core 包），与 **Allay / Nukkit 三平台共用同一份核心代码**，
本目录只包含平台适配层（事件 / 命令 / 玩家对象）。

> ⚠️ gomint 官方已停止维护（[github.com/gomint/gomint](https://github.com/gomint/gomint)），
> 且其 Maven 仓库（gomint.io/maven）已不可达。构建前需先自行准备 `gomint-api` jar：
> - 方式 A：克隆 gomint 仓库执行 `mvn install`（产出 `io.gomint:gomint-api:1.0.0-SNAPSHOT`）
> - 方式 B：将 gomint 发行包中的服务端 jar（内含 io.gomint API 类）放入 `libs/`，
>   并在 `build.gradle` 中改用 `flatDir` 仓库或直接 `compileOnly files('libs/gomint.jar')`

## 构建

```bash
gradle shadowJar
# 产物：build/libs/ndpr-gomint-2.1.0.jar
```

将 jar 放入 gomint 的 `plugins/` 目录，重启服务端。
首次启动生成 `plugins/NDPR/config.json`（数据在 `plugins/NDPR/ndpr_data/`）。

## 配置（config.json）

配置字段：`api_url` / `language` / `token` / `uuid` / `onlinemode` /
`download_interval` / `check_hwid` / `check_interval` / `fail_closed` / `verify_timeout` /
`freeze_interval`，另加 `admins`（管理员名单，玩家名备用）。
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

- 插件元数据：`@PluginName("NDPR")` + `@Version(major=2, minor=1)`，继承 `io.gomint.plugin.Plugin`。
- 事件：`@EventHandler` + `PlayerJoinEvent` / `PlayerQuitEvent`（`event.player()`）。
- 玩家信息：`name()`、`address()`（IP）、`uuid()`（gomint 无 XUID API，使用 UUID）。
- 命令：`registerCommand(new Command("ndpr"))` + `CommandOverload.param(...)`，
  原因参数使用 `TextValidator`（多词）。
- 踢出：`player.disconnect(reason)`；命令执行：`GoMint.instance().dispatchCommand(cmd)`。
- SQLite 驱动（sqlite-jdbc）已随插件打包（relocate 到 `ndpr.shadow.sqlite`）。
