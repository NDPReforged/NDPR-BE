# NDPR - BDSX 客户端

NDPReforged 封禁系统的 BDSX（基岩版 BDS + Node.js）TypeScript 插件，。

## 功能

- 多维封禁检查（玩家名 / XUID / UUID / IP）
- 云端封禁数据库自动下载（SQLite，可配置间隔）
- 玩家进服自动检查 + 踢出 + 拦截统计上报
- 封禁审核提交（`/ndpr ban`）
- HWID 设备验证（冻结 + 网页验证 + 超时踢出）
- 封禁查询（含 IP/UUID 自动识别与模糊建议）
- 插件更新检查（GitHub Releases）
- 中英双语

## 安装

1. 将 `index.ts`（及 `package.json`、`tsconfig.json`）放入 BDSX 项目的 `plugins/ndpr/` 目录
2. 在插件目录执行 `npm install`（安装 `better-sqlite3` 及构建依赖）
   - 依赖 bdsx 类型：`devDependencies` 中 `"bdsx": "file:../bdsx"`，请按实际目录调整
3. 构建：`npm run build`（或 BDSX 根目录 `npm run watch`）
4. 启动 bdsx，插件自动生成 `config.json` 与 `ndpr_data/`
5. 获取 Token：控制台日志查看 UUID → https://ndpreforged.com 绑定邮箱 → 填入 `config.json` → `/ndpr reload`

## 配置（config.json）

配置字段（`log_path` / `logger_mode` / `logger_format` 为兼容保留，基岩版忽略）：
`api_url` / `language` / `token` / `uuid` / `onlinemode` / `download_interval` / `check_hwid` /
`check_interval` / `fail_closed` / `verify_timeout` / `freeze_interval`，另加 `admins`（管理员名单，玩家名或 XUID）。

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

> 权限判定：玩家权限等级 ≥ OPERATOR，或命中 `admins` 名单。

## 说明

- 依赖 `better-sqlite3`（Node 原生模块，用于读取云端下发的 SQLite 封禁库）。
- 踢出使用基岩 `kick` 命令；冻结/标题/效果/模式使用 `bedrockServer.executeCommand` 与
  `player.sendTitle` 原生 API。
- 验证链接以普通消息发送（基岩客户端可直接点击 URL 文本）。
