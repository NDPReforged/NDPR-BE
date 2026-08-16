# NDPR-BE

<div align="center">

![Version](https://img.shields.io/badge/version-2.1.0-blue.svg)
![License](https://img.shields.io/badge/license-MIT-red.svg)

**NDPReforged 封禁系统 · 基岩版服务端客户端全家桶**

[官网](https://ndpreforged.com) • [文档](#-快速开始) • [QQ群](https://qm.qq.com/cgi-bin/qm/qr?k=232760327)

</div>

---

## 📖 简介

NDPR（全称 NDPReforged）是一个强大的 Minecraft 服务器玩家封禁系统，通过云端封禁数据库实现跨服联防。
本仓库提供 **基岩版（Bedrock Edition）** 六个主流服务端平台的客户端插件，同一版本的所有
服务端客户端打包在同一个 [Release](https://github.com/NDPReforged/NDPR-BE/releases) 中。

### 主要功能

- **多维封禁**：支持 ID、UUID/XUID、IPv4、IPv6 四种方式封禁
- **云端同步**：实时更新封禁数据库，跨服联防
- **智能检测**：玩家加入时自动检查封禁状态并踢出
- **封禁统计**：自动统计拦截次数，实时上报服务器
- **封禁审核**：游戏内提交封禁申请，等待人工审核
- **HWID 验证**：网页设备验证 + 冻结防逃逸 + 超时踢出
- **自动更新**：可配置自动更新封禁列表
- **多语言**：简体中文 / English

## 🎯 支持平台

| 平台 | 语言 | 插件形式 | 目录 |
|------|------|----------|------|
| [LeviLamina](https://github.com/LiteLDev/LeviLamina) | C++ (LLAPI) | DLL + manifest | `LeviLamina/` |
| [BDSX](https://github.com/bdsx/bdsx) | TypeScript | npm 插件 | `BDSX/` |
| [BDSpyrunner](https://github.com/ExtcanaRy/BDSpyrunnerW) | Python | 单文件 .py | `BDSpyrunner/` |
| [Nukkit](https://github.com/CloudburstMC/Nukkit) | Java | jar（兼容 PowerNukkitX） | `Nukkit/` |
| [Allay](https://github.com/AllayMC/Allay) | Java | jar | `Allay/` |
| [gomint](https://github.com/gomint/gomint) | Java | jar | `gomint/` |

> Nukkit / Allay / gomint 三个 Java 平台共享同一份业务核心（`java-core/`，ndpr.core 包），
> 各平台仅保留薄薄的适配层，维护成本最低。

## 🚀 快速开始

### 1. 下载

前往 [Releases](https://github.com/NDPReforged/NDPR-BE/releases) 下载与你服务端对应的
`v2.1.0` 客户端文件（同一版本的所有平台客户端都放在同一个 Release 里）。

### 2. 安装

| 平台 | 安装方式 |
|------|----------|
| LeviLamina | 将 `ndpr` 插件目录放入 `plugins/`，重启 `bedrock_server_mod.exe`（需先按 `LeviLamina/README.md` 用 xmake 构建） |
| BDSX | 将插件目录放入 `plugins/`，`npm install` 后 `npm run build` |
| BDSpyrunner | 将 `ndpr.py` 放入 `plugins/` 目录 |
| Nukkit / Allay / gomint | 将 jar 放入 `plugins/` 目录 |

### 3. 获取 Token

1. 启动插件，控制台日志会输出服务器 UUID（`获取到UUID: xxxx`）
2. 前往 [https://ndpreforged.com](https://ndpreforged.com) 绑定邮箱，系统自动发放 Token
3. 将 Token 填入 `config.json` 的 `token` 字段
4. 游戏内执行 `/ndpr reload` 生效

## ⚙️ 配置（config.json）

首次启动自动生成，各平台字段一致：

| 键 | 默认 | 说明 |
|---|---|---|
| api_url | `https://api.ndpreforged.com` | 后端 API 地址 |
| language | `zh_CN` | 语言（`zh_CN` / `en_us`） |
| token | `""` | 必填（启用封禁功能） |
| uuid | `""` | 首次启动自动获取 |
| onlinemode | `true` | 服务器类型（上传审核时透传） |
| download_interval | `900` | 封禁库更新间隔（秒），0=禁用 |
| check_hwid | `false` | 启用 HWID 设备验证 |
| check_interval | `3` | HWID 免验天数 |
| fail_closed | `false` | 封禁库缺失时拒绝玩家进服 |
| verify_timeout | `60` | 设备验证超时（30~600 秒） |
| freeze_interval | `1` | 验证期间冻结传送间隔（秒） |
| admins | `[]` | 管理员名单（玩家名 / XUID，各平台权限判定兜底） |

> `log_path` / `logger_mode` / `logger_format` 为兼容旧版配置保留，基岩版无需日志解析，可忽略。

## 📖 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/ndpr` / `/ndpr help` | 所有人 | 帮助 |
| `/ndpr d` / `/ndpr download` | 管理员 | 手动下载封禁库 |
| `/ndpr ban <玩家> <原因>` | 管理员 | 提交封禁审核 |
| `/ndpr check <ID/IP/UUID>` | 所有人 | 查询封禁状态 |
| `/ndpr reload` | 管理员 | 重载配置并重新下载 |
| `/ndpr cu` / `/ndpr checkupdate` | 管理员 | 检查插件更新 |
| `/ndpr auth <玩家>` | 管理员 | 强制触发 HWID 验证 |

## 📦 目录结构

```
NDPR-BE/
├── README.md
├── BDSpyrunner/      # BDSpyrunner / BDSpyrunnerW 客户端（Python 单文件）
├── BDSX/             # BDSX 客户端（TypeScript）
├── LeviLamina/       # LeviLamina 客户端（C++，含构建脚本）
├── java-core/        # Java 共享业务核心（Nukkit / Allay / gomint 复用）
├── Nukkit/           # Nukkit 客户端（Gradle 工程）
├── Allay/            # Allay 客户端（Gradle 工程）
└── gomint/           # gomint 客户端（Gradle 工程）
```

各平台详细说明见对应目录内的 README。

## 🔒 安全性与隐私

- 所有数据传输使用 HTTPS 加密
- UUID 仅用于身份识别，不包含敏感信息
- IP 地址仅用于封禁验证
- 不收集玩家聊天记录，不记录非封禁玩家信息

## 🛠 常见问题

**插件无法加载 / 配置错误**
1. 检查 `config.json` 的 `onlinemode` 是否为 `true` 或 `false`
2. 检查 `api_url` 是否以 `http://` 或 `https://` 开头
3. 检查 Token 是否已配置

**无法获取 UUID（API响应错误）**
1. 检查网络连接
2. 确认 `api_url` 正确
3. 检查防火墙设置

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目采用 MIT 许可证。

## 📞 联系方式

- **官网**：https://ndpreforged.com
- **QQ群**：232760327
- **GitHub**：https://github.com/NDPReforged/NDPR-BE
