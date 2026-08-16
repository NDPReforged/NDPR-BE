# NDPR - BDSpyrunner 客户端

NDPReforged 封禁系统的 BDSpyrunner / BDSpyrunnerW（基岩版 BDS）Python 插件。。

## 功能

- 多维封禁检查（玩家名 / XUID / UUID / IP / IPv6）
- 云端封禁数据库自动下载（SQLite，可配置间隔）
- 玩家进服自动检查 + 踢出 + 拦截统计上报
- 封禁审核提交（`/ndpr ban`）
- HWID 设备验证（冻结 + 网页验证 + 超时踢出）
- 封禁查询（含 IP/UUID 识别与模糊建议）
- 插件更新检查（GitHub Releases）
- 中英双语

## 安装

1. 将 `ndpr.py` 放入 BDSpyrunner（或 BDSpyrunnerW）的 `plugins` 目录
2. 重启加载器（或使用其重载功能）
3. 首次启动自动生成 `config.json`（与 ndpr.py 同目录）与 `ndpr_data/`
4. 获取 Token：
   - 启动后查看控制台输出中的 UUID（`获取到UUID: xxxx`）
   - 前往 https://ndpreforged.com 绑定邮箱，自动发放 Token
   - 填入 `config.json` 的 `token` 字段，执行 `/ndpr reload`

## 配置（config.json）

| 键 | 默认 | 说明 |
|---|---|---|
| api_url | `https://api.ndpreforged.com` | 后端 API |
| language | `zh_CN` | `zh_CN` / `en_us` |
| token | `""` | 必填（启用封禁功能） |
| uuid | `""` | 首次启动自动获取 |
| onlinemode | `true` | 上传审核时透传（基岩版语义为 Xbox 登录） |
| download_interval | `900` | 封禁库更新间隔（秒），0=禁用 |
| check_hwid | `false` | 启用 HWID 验证 |
| check_interval | `3` | HWID 免验天数 |
| fail_closed | `false` | 封禁库缺失时拒绝进服 |
| verify_timeout | `60` | 验证超时（30~600 秒） |
| freeze_interval | `1` | 冻结传送间隔（秒） |
| admins | `[]` | 管理员名单（玩家名或 XUID）；无法读取玩家权限等级时使用 |

> `log_path` / `logger_mode` / `logger_format` 为兼容旧版配置保留，基岩版无需日志解析，**忽略**。

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/ndpr` / `/ndpr help` | 所有人 | 帮助 |
| `/ndpr d` / `/ndpr download` | 管理员 | 手动下载封禁库 |
| `/ndpr ban <玩家> <原因>` | 管理员 | 提交封禁审核 |
| `/ndpr check <ID/IP/UUID>` | 所有人 | 查询封禁状态 |
| `/ndpr reload` | 管理员 | 重载配置并重新下载 |
| `/ndpr cu` / `/ndpr checkupdate` | 管理员 | 检查插件更新 |
| `/ndpr auth <玩家>` | 管理员 | 强制触发 HWID 验证 |

## 兼容性说明

- 同时兼容**原版 BDSpyrunner**（事件 `onJoin/onLeft/onPlayerCmd`，`data["Player"]`）与
  **BDSpyrunnerW**（事件 `onPlayerJoin/onPlayerLeft/onInputCommand`，`mc.Entity` 直传），
  插件启动时会自动注册可用的事件名。
- 玩家 IP 通过 `mc.Entity.IP` 属性获取（不可用时降级为空，仅影响按 IP 封禁查询）。
- 命令通过聊天命令事件拦截实现（返回 False 拦截，避免"未知命令"提示），
  因此不会出现在命令补全列表中；控制台不可执行插件命令（与 BDSpyrunner 平台限制一致）。
- 验证链接以纯文本发送（BDSpyrunner 无可点击文本 API）。
- 需 Python 3.7+；仅使用标准库（urllib / sqlite3），无第三方依赖。

## 目录结构

```
plugins/
├── ndpr.py          # 本插件（单文件）
├── config.json      # 自动生成
└── ndpr_data/
    ├── ban_database.db
    ├── player_info.json
    └── hwid_temp.json
```
