/**
 * NDPR - NotDPR 封禁系统 - BDSX 客户端 (TypeScript)
 *
 * 安装：
 *   1. 将本目录复制到 bdsx 的 plugins/ 下（如 plugins/ndpr/）
 *   2. 在 bdsx 根目录执行 npm i better-sqlite3 或在本插件目录执行 npm install
 *   3. 重新构建 (npm run watch / tsc) 后启动 bdsx
 *
 * NDPReforged©2026
 */
import * as fs from "fs";
import * as path from "path";
import * as https from "https";
import * as http from "http";
import { URL } from "url";

import { events } from "bdsx/event";
import { command, CommandPermissionLevel } from "bdsx/command";
import { CxxString } from "bdsx/nativetype";
import { CommandRawText } from "bdsx/bds/commandparsertypes";
import { bedrockServer } from "bdsx/launcher";
import { CommandResultType } from "bdsx/commandresult";
import { Player } from "bdsx/bds/player";
import { CommandOrigin } from "bdsx/bds/commandorigin";
import { CommandOutput } from "bdsx/bds/command";
import { PlayerPermission } from "bdsx/bds/player";
import { GameType } from "bdsx/bds/gamemode";

const VERSION = "2.1";
const PLUGIN_DIR = __dirname;
const CONFIG_PATH = path.join(PLUGIN_DIR, "config.json");
const DATA_DIR = path.join(PLUGIN_DIR, "ndpr_data");
const BAN_DB_PATH = path.join(DATA_DIR, "ban_database.db");
const PLAYER_INFO_PATH = path.join(DATA_DIR, "player_info.json");
const HWID_TEMP_PATH = path.join(DATA_DIR, "hwid_temp.json");

// ================= 翻译 =================
// 内置翻译（zh_CN / en_us）
const TRANSLATIONS: Record<string, Record<string, string>> = {
    zh_cn: {
        "ndpr.error.api_url_missing": "API URL未配置,无法获取UUID",
        "ndpr.error.config.field": "配置文件错误:字段{field}",
        "ndpr.error.config.field_hint": "配置文件错误:字段{field} ({hint})",
        "ndpr.error.config.onlinemode_missing": "请在ndpr配置文件里填写服务器类型(正版或离线),否则插件不会加载",
        "ndpr.error.db_api_unconfigured": "API未配置,无法下载数据库",
        "ndpr.error.db_download_http": "数据库下载失败 HTTP{code}: {body}",
        "ndpr.error.db_download_no_url": "数据库下载失败, API响应缺少url字段",
        "ndpr.error.db_file_download_http": "数据库文件下载失败 HTTP{code}",
        "ndpr.error.db_file_invalid": "下载的数据库文件无效: {error}",
        "ndpr.error.get_uuid_http": "获取UUID失败 HTTP {code}: {body}",
        "ndpr.error.get_uuid_invalid": "获取UUID失败: {data}",
        "ndpr.error.init_stage_failed": "初始化阶段[{stage}]失败: {error}",
        "ndpr.error.verify_start_failed": "Error: 玩家 {player} 发起 HWID 验证失败",
        "ndpr.help.author": "作者: EXE_autumnwind NDPReforged Team",
        "ndpr.help.brief": "NDPR主命令",
        "ndpr.help.commands": "命令列表:",
        "ndpr.help.desc.ban": "提交封禁审核(如有上传权限)",
        "ndpr.help.desc.check": "检查封禁状态",
        "ndpr.help.desc.checkupdate": "检查插件更新",
        "ndpr.help.desc.auth": "手动触发玩家身份验证",
        "ndpr.help.desc.download": "下载封禁数据库",
        "ndpr.help.desc.help": "显示此帮助信息",
        "ndpr.help.desc.reload": "重载插件",
        "ndpr.help.footer": "© 2026 NDPR Team",
        "ndpr.help.qq_group": "官方交流Q群:232760327",
        "ndpr.help.title": "NDPR 帮助",
        "ndpr.help.version": "版本: v{version}",
        "ndpr.hint.api_url_scheme": "api_url需以 http:// 或 https:// 开头",
        "ndpr.hint.check_hwid": "请填写 true 或 false",
        "ndpr.hint.download_interval": "需为非负整数, 0表示禁用自动更新",
        "ndpr.hint.freeze_interval": "需为1-60的整数,单位秒",
        "ndpr.hint.language": "需为已安装的语言,如 zh_CN / en_us",
        "ndpr.hint.verify_timeout": "需为30-600的整数,单位秒",
        "ndpr.hover.open_verify_page": "点击打开验证页面",
        "ndpr.kick.banned": "您已被NDPR封禁系统封禁",
        "ndpr.kick.banned_with_reason": "您已被NDPR封禁系统封禁: {reason}",
        "ndpr.kick.hwid_status_unknown": "无法确认设备封禁状态,请稍后再试",
        "ndpr.kick.system_initializing": "服务器防护系统初始化中,请稍后再试",
        "ndpr.kick.verify_timeout": "设备验证超时,请完成验证后重新加入",
        "ndpr.kick.verify_unavailable": "验证服务暂时不可用,请稍后再试",
        "ndpr.label.ban_time": "封禁时间: {time}",
        "ndpr.label.ip": "IP: {ip}",
        "ndpr.label.player": "玩家: {player}",
        "ndpr.label.reason": "原因: {reason}",
        "ndpr.log.auto_update_disabled": "自动更新已禁用 (download_interval <= 0)",
        "ndpr.log.auto_update_started": "封禁数据库自动更新已启动 (每 {interval} 秒)",
        "ndpr.log.banned_detected": "检测到被封禁玩家 {player} 在 {table} 表",
        "ndpr.log.custom_format": "使用自定义日志格式: {format}",
        "ndpr.log.db_updated": "封禁数据库已更新，共 {count} 条记录",
        "ndpr.log.getting_uuid": "正在获取UUID...",
        "ndpr.log.init_done": "NDPR插件初始化完成",
        "ndpr.log.kick_hwid": "因 HWID 验证踢出玩家 {player}: {reason}",
        "ndpr.log.player_info": "玩家 {player} - IP: {ip}, UUID: {uuid}, IPv6: {ipv6}",
        "ndpr.log.server_type": "服务器类型: {type}",
        "ndpr.log.unloaded": "NDPR插件已卸载",
        "ndpr.log.update_found": "发现新版本: v{latest} (当前版本: v{current}) 下载地址: {url}",
        "ndpr.log.uuid": "UUID: {uuid}",
        "ndpr.log.uuid_obtained": "获取到UUID: {uuid}",
        "ndpr.reply.api_not_configured": "API地址未配置",
        "ndpr.reply.ban_reason_echo": "封禁原因: {reason}",
        "ndpr.reply.ban_reason_required": "请提供封禁原因",
        "ndpr.reply.ban_usage": "用法: /ndpr ban <玩家ID> <封禁原因>",
        "ndpr.reply.banned_in_table": "玩家 {player} 已被封禁 ({table} 表)",
        "ndpr.reply.check_id": "审核编号: {check_id}",
        "ndpr.reply.checking_update": "[NDPR] 正在检查更新...",
        "ndpr.reply.config_not_loaded": "配置未加载",
        "ndpr.reply.connection_error": "无法连接到服务器，请检查API地址",
        "ndpr.reply.copy_install_command": "点击复制安装命令",
        "ndpr.reply.copy_install_hover": "点击复制安装命令到聊天栏",
        "ndpr.reply.current_version": "当前版本: v{version}",
        "ndpr.reply.db_download_success": "封禁数据库下载成功！",
        "ndpr.reply.download_inflight": "已有下载任务正在进行,请稍候",
        "ndpr.reply.download_url": "下载地址: {url}",
        "ndpr.reply.downloading": "正在下载封禁数据库...",
        "ndpr.reply.error_info": "错误信息: {error}",
        "ndpr.reply.getting_player_info": "正在获取玩家 {player} 的信息...",
        "ndpr.reply.info_obtained": "已获取信息: {info}",
        "ndpr.reply.latest_version": "最新版本: v{version}",
        "ndpr.reply.no_data": "无数据",
        "ndpr.reply.no_upload_permission": "无上传权限,请到官网获取",
        "ndpr.reply.not_banned": "玩家 {player} 未被封禁",
        "ndpr.reply.fuzzy_suggestion": "也许您说的是：",
        "ndpr.reply.fuzzy_expand": "展开",
        "ndpr.reply.fuzzy_hover": "点击查看 {player} 的封禁信息",
        "ndpr.reply.permission_denied": "权限不足, 需要权限等级 2",
        "ndpr.reply.player_info_hint": "请确保该玩家最近登录过服务器",
        "ndpr.reply.player_info_not_found": "未找到该玩家的信息",
        "ndpr.reply.query_failed": "查询失败: {error}",
        "ndpr.reply.record_not_found": "未找到封禁记录 ({type}: {value})",
        "ndpr.reply.reload_failed": "重载失败: {error}",
        "ndpr.reply.reloaded": "NDPR插件已重载",
        "ndpr.reply.reloading": "正在重载 NDPR 插件...",
        "ndpr.reply.response_body": "响应内容: {body}",
        "ndpr.reply.submit_failed": "提交失败: {message}",
        "ndpr.reply.submit_failed_http": "提交失败 HTTP {code}",
        "ndpr.reply.submit_success": "成功提交",
        "ndpr.reply.submitting": "正在提交封禁审核...",
        "ndpr.reply.timeout": "请求超时，请检查网络连接",
        "ndpr.reply.token_not_configured": "Token未配置",
        "ndpr.reply.unknown_error": "未知错误",
        "ndpr.reply.up_to_date": "[NDPR] 当前已是最新版本 (v{version})",
        "ndpr.reply.auth_starting": "正在对玩家 {player} 发起身份验证...",
        "ndpr.reply.auth_player_not_online": "玩家 {player} 当前不在线",
        "ndpr.reply.auth_usage": "用法: /ndpr auth <玩家ID>",
        "ndpr.reply.update_found": "[NDPR] 发现新版本!",
        "ndpr.reply.update_notes": "更新内容: {notes}",
        "ndpr.reply.wait_review": "等待管理员审核",
        "ndpr.subtitle.verify": "请不要移动，否则将被踢出",
        "ndpr.tell.click_verify": "请点击链接完成设备验证: ",
        "ndpr.tell.verify_done": "设备验证完成,祝您游戏愉快",
        "ndpr.tell.verify_enabled": "本服务器启用了设备验证,请完成验证后开始游戏",
        "ndpr.tell.verify_freeze_notice": "验证期间您将被固定在原地,超时将被请离服务器",
        "ndpr.title.verify": "身份验证",
        "ndpr.warn.db_missing": "封禁数据库不存在,正在后台尝试重新下载",
        "ndpr.warn.fail_closed_query_error": "fail_closed 模式: 玩家 {player} 因封禁库查询异常被拒绝加入 ({error})",
        "ndpr.warn.fail_closed_rejected": "fail_closed 模式: 玩家 {player} 未经检查被拒绝加入",
        "ndpr.warn.fail_open_allowed": "fail_open 模式: 玩家 {player} 未经本地封禁检查放行",
        "ndpr.warn.fail_open_query_error": "fail_open 模式: 玩家 {player} 因封禁库查询异常放行 ({error})",
        "ndpr.warn.no_anchor_pos": "无法获取玩家 {player} 锚点坐标,freeze 跳过",
        "ndpr.warn.player_info_parse_failed": "玩家 {player} 信息解析失败",
        "ndpr.warn.token_missing": "Token未配置请去官网获取https://ndpreforged.com",
        "ndpr.warn.token_missing_hwid": "Token未配置,跳过HWID机器验证",
        "ndpr.word.hwid_banned": "HWID 已被封禁",
        "ndpr.word.offline": "离线",
        "ndpr.word.online": "正版",
        "ndpr.word.stage_db": "封禁库下载",
        "ndpr.word.stage_update": "更新检查",
        "ndpr.word.stage_uuid": "UUID获取",
        "ndpr.word.unset": "未设置",
    },
    en_us: {
        "ndpr.error.api_url_missing": "API URL is not configured, cannot get UUID",
        "ndpr.error.config.field": "Config error: field {field}",
        "ndpr.error.config.field_hint": "Config error: field {field} ({hint})",
        "ndpr.error.config.onlinemode_missing": "Please fill in the server type (online or offline) in the ndpr config file",
        "ndpr.error.db_api_unconfigured": "API is not configured, cannot download the database",
        "ndpr.error.db_download_http": "Database download failed HTTP {code}: {body}",
        "ndpr.error.db_download_no_url": "Database download failed, API response is missing the url field",
        "ndpr.error.db_file_download_http": "Database file download failed HTTP {code}",
        "ndpr.error.db_file_invalid": "Downloaded database file is invalid: {error}",
        "ndpr.error.get_uuid_http": "Failed to get UUID HTTP {code}: {body}",
        "ndpr.error.get_uuid_invalid": "Failed to get UUID: {data}",
        "ndpr.error.init_stage_failed": "Init stage [{stage}] failed: {error}",
        "ndpr.error.verify_start_failed": "Error: failed to start HWID verification for player {player}",
        "ndpr.help.author": "Author: EXE_autumnwind NDPReforged Team",
        "ndpr.help.brief": "NDPR main command",
        "ndpr.help.commands": "Commands:",
        "ndpr.help.desc.ban": "Submit a ban for review (requires upload permission)",
        "ndpr.help.desc.check": "Check ban status",
        "ndpr.help.desc.checkupdate": "Check for plugin updates",
        "ndpr.help.desc.auth": "Manually trigger identity verification for a player",
        "ndpr.help.desc.download": "Download the ban database",
        "ndpr.help.desc.help": "Show this help message",
        "ndpr.help.desc.reload": "Reload the plugin",
        "ndpr.help.footer": "© 2026 NDPR Team",
        "ndpr.help.qq_group": "Official QQ Group: 232760327",
        "ndpr.help.title": "NDPR Help",
        "ndpr.help.version": "Version: v{version}",
        "ndpr.hint.api_url_scheme": "api_url must start with http:// or https://",
        "ndpr.hint.check_hwid": "must be true or false",
        "ndpr.hint.download_interval": "must be a non-negative integer, 0 disables auto update",
        "ndpr.hint.freeze_interval": "must be an integer between 1-60, in seconds",
        "ndpr.hint.language": "must be an installed language, e.g. zh_CN / en_us",
        "ndpr.hint.verify_timeout": "must be an integer between 30-600, in seconds",
        "ndpr.hover.open_verify_page": "Click to open the verification page",
        "ndpr.kick.banned": "You are banned by the NDPR ban system",
        "ndpr.kick.banned_with_reason": "You are banned by the NDPR ban system: {reason}",
        "ndpr.kick.hwid_status_unknown": "Cannot confirm device ban status, please try again later",
        "ndpr.kick.system_initializing": "Server protection system is initializing, please try again later",
        "ndpr.kick.verify_timeout": "Device verification timed out, please complete verification and rejoin",
        "ndpr.kick.verify_unavailable": "Verification service is temporarily unavailable, please try again later",
        "ndpr.label.ban_time": "Ban time: {time}",
        "ndpr.label.ip": "IP: {ip}",
        "ndpr.label.player": "Player: {player}",
        "ndpr.label.reason": "Reason: {reason}",
        "ndpr.log.auto_update_disabled": "Auto update disabled (download_interval <= 0)",
        "ndpr.log.auto_update_started": "Ban database auto update started (every {interval} seconds)",
        "ndpr.log.banned_detected": "Banned player {player} detected in table {table}",
        "ndpr.log.custom_format": "Using custom log format: {format}",
        "ndpr.log.db_updated": "Ban database updated, {count} records in total",
        "ndpr.log.getting_uuid": "Getting UUID...",
        "ndpr.log.init_done": "NDPR plugin initialization complete",
        "ndpr.log.kick_hwid": "Kicked player {player} for HWID verification: {reason}",
        "ndpr.log.player_info": "Player {player} - IP: {ip}, UUID: {uuid}, IPv6: {ipv6}",
        "ndpr.log.server_type": "Server type: {type}",
        "ndpr.log.unloaded": "NDPR plugin unloaded",
        "ndpr.log.update_found": "New version available: v{latest} (current: v{current}) download: {url}",
        "ndpr.log.uuid": "UUID: {uuid}",
        "ndpr.log.uuid_obtained": "UUID obtained: {uuid}",
        "ndpr.reply.api_not_configured": "API URL is not configured",
        "ndpr.reply.ban_reason_echo": "Ban reason: {reason}",
        "ndpr.reply.ban_reason_required": "Please provide a ban reason",
        "ndpr.reply.ban_usage": "Usage: /ndpr ban <player> <reason>",
        "ndpr.reply.banned_in_table": "Player {player} is banned (table {table})",
        "ndpr.reply.check_id": "Review ID: {check_id}",
        "ndpr.reply.checking_update": "[NDPR] Checking for updates...",
        "ndpr.reply.config_not_loaded": "Config not loaded",
        "ndpr.reply.connection_error": "Cannot connect to the server, please check the API URL",
        "ndpr.reply.copy_install_command": "Click to copy install command",
        "ndpr.reply.copy_install_hover": "Click to copy the install command to the chat bar",
        "ndpr.reply.current_version": "Current version: v{version}",
        "ndpr.reply.db_download_success": "Ban database downloaded successfully!",
        "ndpr.reply.download_inflight": "A download task is already running, please wait",
        "ndpr.reply.download_url": "Download: {url}",
        "ndpr.reply.downloading": "Downloading ban database...",
        "ndpr.reply.error_info": "Error: {error}",
        "ndpr.reply.getting_player_info": "Getting info of player {player}...",
        "ndpr.reply.info_obtained": "Info obtained: {info}",
        "ndpr.reply.latest_version": "Latest version: v{version}",
        "ndpr.reply.no_data": "No data",
        "ndpr.reply.no_upload_permission": "No upload permission, please get it on the official website",
        "ndpr.reply.not_banned": "Player {player} is not banned",
        "ndpr.reply.fuzzy_suggestion": "Did you mean:",
        "ndpr.reply.fuzzy_expand": "Expand",
        "ndpr.reply.fuzzy_hover": "Click to view {player}'s ban info",
        "ndpr.reply.permission_denied": "Permission denied, permission level 2 required",
        "ndpr.reply.player_info_hint": "Please make sure this player has joined the server recently",
        "ndpr.reply.player_info_not_found": "Player info not found",
        "ndpr.reply.query_failed": "Query failed: {error}",
        "ndpr.reply.record_not_found": "No ban record found ({type}: {value})",
        "ndpr.reply.reload_failed": "Reload failed: {error}",
        "ndpr.reply.reloaded": "NDPR plugin reloaded",
        "ndpr.reply.reloading": "Reloading NDPR plugin...",
        "ndpr.reply.response_body": "Response: {body}",
        "ndpr.reply.submit_failed": "Submit failed: {message}",
        "ndpr.reply.submit_failed_http": "Submit failed HTTP {code}",
        "ndpr.reply.submit_success": "Submitted successfully",
        "ndpr.reply.submitting": "Submitting ban for review...",
        "ndpr.reply.timeout": "Request timed out, please check the network connection",
        "ndpr.reply.token_not_configured": "Token is not configured",
        "ndpr.reply.unknown_error": "unknown error",
        "ndpr.reply.up_to_date": "[NDPR] Already the latest version (v{version})",
        "ndpr.reply.auth_starting": "Starting identity verification for player {player}...",
        "ndpr.reply.auth_player_not_online": "Player {player} is not online",
        "ndpr.reply.auth_usage": "Usage: /ndpr auth <player>",
        "ndpr.reply.update_found": "[NDPR] New version available!",
        "ndpr.reply.update_notes": "Update notes: {notes}",
        "ndpr.reply.wait_review": "Waiting for admin review",
        "ndpr.subtitle.verify": "Do not move, or you will be kicked",
        "ndpr.tell.click_verify": "Click the link to complete device verification: ",
        "ndpr.tell.verify_done": "Device verification completed, enjoy the game",
        "ndpr.tell.verify_enabled": "This server has device verification enabled, please complete verification before playing",
        "ndpr.tell.verify_freeze_notice": "You will be held in place during verification and kicked out on timeout",
        "ndpr.title.verify": "Identity Verification",
        "ndpr.warn.db_missing": "Ban database does not exist, retrying download in the background",
        "ndpr.warn.fail_closed_query_error": "fail_closed mode: player {player} rejected due to ban database query error ({error})",
        "ndpr.warn.fail_closed_rejected": "fail_closed mode: player {player} rejected before checking",
        "ndpr.warn.fail_open_allowed": "fail_open mode: player {player} allowed without local ban check",
        "ndpr.warn.fail_open_query_error": "fail_open mode: player {player} allowed despite ban database query error ({error})",
        "ndpr.warn.no_anchor_pos": "Cannot get anchor position of player {player}, freeze skipped",
        "ndpr.warn.player_info_parse_failed": "Failed to parse info of player {player}",
        "ndpr.warn.token_missing": "Token is not configured, please get one at https://ndpreforged.com",
        "ndpr.warn.token_missing_hwid": "Token is not configured, skipping HWID verification",
        "ndpr.word.hwid_banned": "HWID is banned",
        "ndpr.word.offline": "Offline",
        "ndpr.word.online": "Online",
        "ndpr.word.stage_db": "ban database download",
        "ndpr.word.stage_update": "update check",
        "ndpr.word.stage_uuid": "UUID fetch",
        "ndpr.word.unset": "not set",
    },
};

function tr(key: string, kwargs?: Record<string, unknown>): string {
    const lang = currentLanguage();
    let text = TRANSLATIONS[lang]?.[key] ?? TRANSLATIONS["zh_cn"][key] ?? key;
    if (kwargs) {
        for (const [k, v] of Object.entries(kwargs)) {
            text = text.split(`{${k}}`).join(String(v));
        }
    }
    return text;
}

function currentLanguage(): string {
    const lang = String(config?.language ?? "zh_CN").trim().toLowerCase().replace("-", "_");
    return lang in TRANSLATIONS ? lang : "zh_cn";
}

// ================= 配置 =================
interface Config {
    api_url: string;
    language: string;
    token: string;
    uuid: string;
    onlinemode: boolean;
    log_path: string;
    logger_mode: string;
    logger_format: string;
    download_interval: number;
    check_hwid: boolean;
    check_interval: number;
    fail_closed: boolean;
    verify_timeout: number;
    freeze_interval: number;
    admins: string[];
}

const DEFAULT_CONFIG: Config = {
    api_url: "https://api.ndpreforged.com",
    language: "zh_CN",
    token: "",
    uuid: "",
    onlinemode: true,
    log_path: "server/logs/latest.log",
    logger_mode: "default",
    logger_format: "<[%n%]%name%>%s%<%message%>",
    download_interval: 900,
    check_hwid: false,
    check_interval: 3,
    fail_closed: false,
    verify_timeout: 60,
    freeze_interval: 1,
    admins: [],
};

let config: Config = { ...DEFAULT_CONFIG };

function log(msg: string): void {
    console.log(`[NDPR] ${msg}`);
}

function loadConfig(): void {
    try {
        fs.mkdirSync(DATA_DIR, { recursive: true });
    } catch {
        /* ignore */
    }
    let loaded: Partial<Config> = {};
    try {
        if (fs.existsSync(CONFIG_PATH)) {
            loaded = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf-8"));
        }
    } catch (err) {
        log(`配置解析失败, 使用默认配置: ${err}`);
        loaded = {};
    }
    config = { ...DEFAULT_CONFIG, ...loaded };
    // 规范化
    const cfg = config as unknown as Record<string, unknown>;
    if (typeof cfg.onlinemode === "string") cfg.onlinemode = cfg.onlinemode.trim().toLowerCase() === "true";
    if (typeof cfg.onlinemode !== "boolean") cfg.onlinemode = true;
    for (const k of ["check_hwid", "fail_closed"] as const) {
        const v = cfg[k];
        if (typeof v === "string") cfg[k] = v.trim().toLowerCase() === "true";
        if (typeof cfg[k] !== "boolean") cfg[k] = DEFAULT_CONFIG[k];
    }
    for (const k of ["download_interval", "check_interval", "verify_timeout", "freeze_interval"] as const) {
        const v = cfg[k];
        if (typeof v === "string") {
            const n = parseInt(v, 10);
            if (!Number.isNaN(n)) cfg[k] = n;
        }
        if (typeof cfg[k] !== "number") cfg[k] = DEFAULT_CONFIG[k];
    }
    if (!Array.isArray(config.admins)) config.admins = [];
    saveConfig();
    // 校验
    const errors: string[] = [];
    const apiUrl = config.api_url;
    if (!apiUrl || typeof apiUrl !== "string") errors.push(tr("ndpr.error.config.field", { field: "api_url" }));
    else if (!/^https?:\/\//.test(apiUrl)) errors.push(tr("ndpr.error.config.field_hint", { field: "api_url", hint: tr("ndpr.hint.api_url_scheme") }));
    if (config.token == null || typeof config.token !== "string") errors.push(tr("ndpr.error.config.field", { field: "token" }));
    if (typeof config.onlinemode !== "boolean") errors.push(tr("ndpr.error.config.field", { field: "onlinemode" }));
    if (errors.length > 0) throw new Error("Error: " + errors.join("; "));
    log(tr("ndpr.log.server_type", { type: tr(config.onlinemode ? "ndpr.word.online" : "ndpr.word.offline") }));
    log(tr("ndpr.log.uuid", { uuid: config.uuid || tr("ndpr.word.unset") }));
}

function saveConfig(): void {
    try {
        fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2), "utf-8");
    } catch (err) {
        log(`保存配置失败: ${err}`);
    }
}

// ================= HTTP (Node 内置, 零依赖) =================
function httpRequest(method: string, urlStr: string, payload?: unknown, headers?: Record<string, string>, timeout = 10): Promise<{ status: number; body: Buffer }> {
    return new Promise((resolve, reject) => {
        let u: URL;
        try {
            u = new URL(urlStr);
        } catch (err) {
            reject(err);
            return;
        }
        const mod = u.protocol === "https:" ? https : http;
        const body = payload !== undefined ? Buffer.from(JSON.stringify(payload), "utf-8") : null;
        const reqHeaders: Record<string, string> = { ...(headers ?? {}) };
        if (body) reqHeaders["Content-Type"] = "application/json";
        const req = mod.request(
            u,
            {
                method,
                headers: reqHeaders,
                timeout: timeout * 1000,
            },
            (res) => {
                const chunks: Buffer[] = [];
                res.on("data", (c: Buffer) => chunks.push(c));
                res.on("end", () => resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks) }));
            }
        );
        req.on("timeout", () => {
            req.destroy(new Error("timeout"));
        });
        req.on("error", reject);
        if (body) req.write(body);
        req.end();
    });
}

function authHeaders(): Record<string, string> {
    return { Authorization: `Bearer ${config.token}` };
}

function parseJson<T = Record<string, unknown>>(buf: Buffer): T {
    try {
        return JSON.parse(buf.toString("utf-8")) as T;
    } catch {
        return {} as T;
    }
}

// ================= SQLite =================
// 使用 better-sqlite3（npm 原生模块，bdsx 支持）
// eslint-disable-next-line @typescript-eslint/no-var-requires
const Database = require("better-sqlite3");
const tableSchemaCache: Record<string, Set<string>> = {};

function loadTableSchema(db: unknown, table: string): Set<string> {
    if (tableSchemaCache[table]) return tableSchemaCache[table];
    const cols = new Set<string>();
    try {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const rows = (db as any).prepare(`PRAGMA table_info(${table})`).all();
        for (const r of rows) cols.add(String(r.name).toLowerCase());
    } catch {
        /* ignore */
    }
    tableSchemaCache[table] = cols;
    return cols;
}

function tableTimeCol(table: string, db?: unknown): string {
    if (db) {
        const cols = loadTableSchema(db, table);
        if (cols.has("ban_time")) return "ban_time";
        if (cols.has("last_seen")) return "last_seen";
    }
    return table === "offline" ? "ban_time" : "last_seen";
}

function tableHasMcuuid(table: string, db?: unknown): boolean {
    if (db) {
        return loadTableSchema(db, table).has("mcuuid");
    }
    return table === "online";
}

function queryBanDb(player: string | null, ip: string | null, ipv6: string | null, uuid: string | null, dbPath = BAN_DB_PATH): { table: string; reason: string; time: string } | null {
    if (!fs.existsSync(dbPath)) return null;
    let db;
    try {
        db = new Database(dbPath, { readonly: true });
        for (const table of ["online", "offline"]) {
            const timeCol = tableTimeCol(table, db);
            let sql: string;
            const params: unknown[] = [];
            if (tableHasMcuuid(table, db)) {
                sql = `SELECT ban_reason, ${timeCol} FROM ${table} WHERE mcuuid = ? OR player = ? OR ip = ? OR ipv6 = ?`;
                params.push(uuid ?? null, player, ip ?? null, ipv6 ?? null);
            } else {
                sql = `SELECT ban_reason, ${timeCol} FROM ${table} WHERE player = ? OR ip = ? OR ipv6 = ?`;
                params.push(player, ip ?? null, ipv6 ?? null);
            }
            const row = db.prepare(sql).get(...params);
            if (row) return { table, reason: String(row.ban_reason ?? ""), time: String(row[timeCol] ?? "") };
        }
        return null;
    } catch {
        return null;
    } finally {
        try {
            db?.close();
        } catch {
            /* ignore */
        }
    }
}

function countBanDb(dbPath = BAN_DB_PATH): number {
    let db;
    try {
        db = new Database(dbPath, { readonly: true });
        let count = 0;
        for (const table of ["online", "offline"]) {
            const row = db.prepare(`SELECT COUNT(*) AS c FROM ${table}`).get();
            count += Number(row?.c ?? 0);
        }
        return count;
    } catch {
        return -1;
    } finally {
        try {
            db?.close();
        } catch {
            /* ignore */
        }
    }
}

function isDbValid(dbPath: string): boolean {
    return countBanDb(dbPath) >= 0;
}

// ================= 封禁库下载 =================
let downloadInflight = false;

async function downloadBanDatabase(reply?: (msg: string) => void): Promise<void> {
    if (!config.token) {
        const msg = tr("ndpr.warn.token_missing");
        log(msg);
        if (reply) reply(`§c${msg}`);
        return;
    }
    if (!config.api_url) {
        const msg = tr("ndpr.error.db_api_unconfigured");
        log(msg);
        if (reply) reply(`§c${msg}`);
        return;
    }
    let resp: { status: number; body: Buffer };
    try {
        resp = await httpRequest("GET", `${config.api_url}/bans/download`, undefined, authHeaders(), 30);
    } catch (err) {
        const msg = `${tr("ndpr.reply.connection_error")} (${err})`;
        log(msg);
        if (reply) reply(`§c${msg}`);
        return;
    }
    if (resp.status !== 200) {
        const msg = tr("ndpr.error.db_download_http", { code: resp.status, body: resp.body.toString("utf-8").slice(0, 200) });
        log(msg);
        if (reply) reply(`§c${msg}`);
        return;
    }
    const data = parseJson<{ url?: string }>(resp.body);
    const downloadUrl = data.url;
    if (!downloadUrl) {
        const msg = tr("ndpr.error.db_download_no_url");
        log(msg);
        if (reply) reply(`§c${msg}`);
        return;
    }
    let fileResp: { status: number; body: Buffer };
    try {
        fileResp = await httpRequest("GET", downloadUrl, undefined, {}, 60);
    } catch (err) {
        const msg = `${tr("ndpr.reply.connection_error")} (${err})`;
        log(msg);
        if (reply) reply(`§c${msg}`);
        return;
    }
    if (fileResp.status !== 200) {
        const msg = tr("ndpr.error.db_file_download_http", { code: fileResp.status });
        log(msg);
        if (reply) reply(`§c${msg}`);
        return;
    }
    const tmpPath = BAN_DB_PATH + ".tmp";
    try {
        fs.writeFileSync(tmpPath, fileResp.body);
        if (!isDbValid(tmpPath)) throw new Error("invalid sqlite");
        fs.renameSync(tmpPath, BAN_DB_PATH);
        for (const k of Object.keys(tableSchemaCache)) delete tableSchemaCache[k];
    } catch (err) {
        try {
            fs.unlinkSync(tmpPath);
        } catch {
            /* ignore */
        }
        const msg = tr("ndpr.error.db_file_invalid", { error: err });
        log(msg);
        if (reply) reply(`§c${msg}`);
        return;
    }
    const count = countBanDb();
    const detailMsg = tr("ndpr.log.db_updated", { count });
    log(detailMsg);
    if (reply) {
        reply(`§a${tr("ndpr.reply.db_download_success")}`);
        reply(`§7${detailMsg}`);
    }
    try {
        await httpRequest("POST", `${config.api_url}/bans/download/done`, {}, authHeaders(), 10);
    } catch {
        /* ignore */
    }
}

async function asyncDownload(reply?: (msg: string) => void): Promise<void> {
    if (downloadInflight) {
        if (reply) reply(`§e${tr("ndpr.reply.download_inflight")}`);
        return;
    }
    downloadInflight = true;
    try {
        await downloadBanDatabase(reply);
    } finally {
        downloadInflight = false;
    }
}

let downloadTimer: NodeJS.Timeout | null = null;

function startDownloadTask(): void {
    const interval = config.download_interval;
    if (!Number.isInteger(interval) || interval <= 0) {
        log(tr("ndpr.log.auto_update_disabled"));
        return;
    }
    if (downloadTimer) return;
    downloadTimer = setInterval(() => {
        asyncDownload().catch((err) => log(`自动更新失败: ${err}`));
    }, interval * 1000);
    log(tr("ndpr.log.auto_update_started", { interval }));
}

// ================= 玩家信息持久化 =================
function savePlayerInfo(player: string, ip: string | null, uuid: string | null, ipv6: string | null): void {
    try {
        let info: Record<string, unknown> = {};
        if (fs.existsSync(PLAYER_INFO_PATH)) {
            try {
                info = JSON.parse(fs.readFileSync(PLAYER_INFO_PATH, "utf-8"));
            } catch {
                info = {};
            }
        }
        info[player] = { ip, uuid, ipv6, timestamp: Date.now() / 1000 };
        fs.writeFileSync(PLAYER_INFO_PATH, JSON.stringify(info, null, 2), "utf-8");
    } catch {
        /* ignore */
    }
}

function loadPlayerInfo(player: string): Record<string, unknown> {
    try {
        if (!fs.existsSync(PLAYER_INFO_PATH)) return {};
        const info = JSON.parse(fs.readFileSync(PLAYER_INFO_PATH, "utf-8"));
        return (info?.[player] ?? {}) as Record<string, unknown>;
    } catch {
        return {};
    }
}

function loadHwidTemp(): Record<string, { ip: string; time: number }> {
    try {
        if (!fs.existsSync(HWID_TEMP_PATH)) return {};
        const data = JSON.parse(fs.readFileSync(HWID_TEMP_PATH, "utf-8"));
        return typeof data === "object" && data !== null ? data : {};
    } catch {
        return {};
    }
}

function saveHwidTemp(player: string, ip: string | null): void {
    try {
        const records = loadHwidTemp();
        records[player] = { ip: ip ?? "", time: Date.now() / 1000 };
        fs.writeFileSync(HWID_TEMP_PATH, JSON.stringify(records, null, 2), "utf-8");
    } catch {
        /* ignore */
    }
}

// ================= 工具 =================
function normalizeUuid(value: unknown): string | null {
    if (!value) return null;
    const s = String(value);
    if (/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(s)) return s;
    const m = s.match(/^\[I?;?\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\]$/);
    if (m) {
        const ints = [1, 2, 3, 4].map((i) => (parseInt(m[i], 10) >>> 0));
        return (
            ints[0].toString(16).padStart(8, "0") +
            "-" +
            ((ints[1] >> 16) & 0xffff).toString(16).padStart(4, "0") +
            "-" +
            (ints[1] & 0xffff).toString(16).padStart(4, "0") +
            "-" +
            ((ints[2] >> 16) & 0xffff).toString(16).padStart(4, "0") +
            "-" +
            ((((ints[2] & 0xffff) << 32) | ints[3]) >>> 0).toString(16).padStart(12, "0")
        );
    }
    return s;
}

function detectIdentifierType(target: string): "ip" | "ipv6" | "uuid" | "id" {
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(target)) return "ip";
    if (target.includes(":") && /^[0-9a-fA-F:.]+$/.test(target)) return "ipv6";
    if (target.length === 36 && target.split("-").length === 5) return "uuid";
    return "id";
}

function runCmd(cmd: string): void {
    try {
        bedrockServer.executeCommand(cmd, CommandResultType.Data);
    } catch (err) {
        log(`执行命令失败 [${cmd}]: ${err}`);
    }
}

function kickPlayer(player: Player, reason: string): void {
    // BDSX 无原生 disconnect 包装，使用基岩 kick 命令
    runCmd(`kick "${player.getName()}" ${reason}`);
}

function getPlayerPos(player: Player): { x: number; y: number; z: number } | null {
    try {
        const pos = player.getPosition();
        return { x: pos.x, y: pos.y, z: pos.z };
    } catch {
        return null;
    }
}

function playerIsAdmin(player: Player): boolean {
    try {
        const perm = player.getPermissionLevel();
        if (perm === PlayerPermission.OPERATOR || perm === PlayerPermission.CUSTOM) return true;
        if (perm !== PlayerPermission.VISITOR) {
            // MEMBER 也按非管理员处理
        }
    } catch {
        /* ignore */
    }
    const admins = config.admins ?? [];
    if (admins.length > 0) {
        return admins.includes(player.getName()) || admins.includes(player.getXuid());
    }
    log("警告: 无法获取玩家权限且未配置 admins, 管理员命令默认开放(请在 config.json 配置 admins)");
    return true;
}

function reportKick(): void {
    try {
        httpRequest("POST", `${config.api_url}/stats/a`, {}, authHeaders(), 5).catch(() => undefined);
    } catch {
        /* ignore */
    }
}

// ================= HWID 验证 =================
interface VerifySession {
    cancel: boolean;
    sessionId: string | null;
    player: string;
    ip: string | null;
    firstVerify: boolean;
}

const verifySessions = new Map<string, VerifySession>();

function createVerifySession(player: string, ip: string | null): Promise<{ session_id?: string; verify_url?: string; expires_at?: number } | null> {
    const payload: Record<string, unknown> = { player_id: player };
    if (ip) payload.ip = ip;
    return httpRequest("POST", `${config.api_url}/hwid/upd`, payload, authHeaders(), 10)
        .then((resp) => (resp.status === 200 ? parseJson(resp.body) : null))
        .catch(() => null);
}

function checkVerifyStatus(sessionId: string): Promise<Record<string, unknown> | null> {
    return httpRequest("POST", `${config.api_url}/hwid/upd/check`, { session_id: sessionId }, authHeaders(), 3)
        .then((resp) => (resp.status === 200 ? parseJson(resp.body) : null))
        .catch(() => null);
}

function queryHasHwid(player: string): Promise<Record<string, unknown> | null> {
    return httpRequest("POST", `${config.api_url}/hwid/has`, { player_id: player }, authHeaders(), 5)
        .then((resp) => (resp.status === 200 ? parseJson(resp.body) : null))
        .catch(() => null);
}

function cancelApiSession(sessionId: string): void {
    httpRequest("POST", `${config.api_url}/hwid/upd/cancel`, { session_id: sessionId }, authHeaders(), 5).catch(() => undefined);
}

function clearVerifySession(player: string, session: VerifySession): void {
    if (verifySessions.get(player) === session) verifySessions.delete(player);
}

function getOriginalGameType(player: Player): GameType {
    try {
        return player.getGameType();
    } catch {
        return GameType.SURVIVAL;
    }
}

async function runVerify(session: VerifySession): Promise<void> {
    const name = session.player;
    const player = findPlayer(name);
    if (!player) {
        clearVerifySession(name, session);
        return;
    }
    const originalGameType = getOriginalGameType(player);
    const pos0 = getPlayerPos(player);
    if (!pos0) log(tr("ndpr.warn.no_anchor_pos", { player: name }));

    runCmd("gamerule sendcommandfeedback false");
    runCmd(`effect give "${name}" blindness 999999 0 true`);
    runCmd(`gamemode adventure "${name}"`);
    player.sendTitle(tr("ndpr.title.verify"), tr("ndpr.subtitle.verify"));
    if (session.firstVerify) player.sendMessage(`§e${tr("ndpr.tell.verify_enabled")}`);

    try {
        const result = await createVerifySession(name, session.ip);
        if (!result || !result.session_id) {
            log(tr("ndpr.error.verify_start_failed", { player: name }));
            kickPlayer(player, `§c${tr("ndpr.kick.verify_unavailable")}`);
            return;
        }
        session.sessionId = result.session_id;
        let verifyTimeout = config.verify_timeout;
        if (!Number.isInteger(verifyTimeout) || verifyTimeout < 30) verifyTimeout = 60;
        const now = Math.floor(Date.now() / 1000);
        const rawExpires = Number(result.expires_at ?? 0) || now + verifyTimeout;
        const expiresAt = Math.min(Math.max(rawExpires, now + 30), now + verifyTimeout);
        const verifyUrl = String(result.verify_url ?? "");
        player.sendMessage(`§e${tr("ndpr.tell.click_verify")}${verifyUrl}`);
        player.sendMessage(`§7${tr("ndpr.tell.verify_freeze_notice")}`);

        let freezeInterval = config.freeze_interval;
        if (!Number.isInteger(freezeInterval) || freezeInterval < 1) freezeInterval = 1;
        let lastFreeze = 0;

        while (!session.cancel && Date.now() / 1000 < expiresAt) {
            if (pos0 && Date.now() / 1000 - lastFreeze >= freezeInterval) {
                runCmd(`tp "${name}" ${pos0.x.toFixed(2)} ${pos0.y.toFixed(2)} ${pos0.z.toFixed(2)}`);
                lastFreeze = Date.now() / 1000;
            }
            await sleep(1000);
            if (session.cancel) return;
            const status = await checkVerifyStatus(session.sessionId);
            if (!status) continue;
            if (status.completed) {
                let banned = false;
                let banReason: unknown = null;
                if ("banned" in status) {
                    banned = Boolean(status.banned);
                    banReason = status.reason;
                } else {
                    const has = await queryHasHwid(name);
                    if (!has) {
                        kickPlayer(player, `§c${tr("ndpr.kick.hwid_status_unknown")}`);
                        return;
                    }
                    banned = Boolean(has.banned);
                    banReason = has.reason;
                }
                if (banned) {
                    const reason = banReason || tr("ndpr.word.hwid_banned");
                    kickPlayer(player, `§c${tr("ndpr.kick.banned_with_reason", { reason })}`);
                } else {
                    saveHwidTemp(name, session.ip);
                    player.sendMessage(`§a${tr("ndpr.tell.verify_done")}`);
                }
                return;
            }
            if (status.status === "cancelled") return;
            if (status.status === "expired") break;
        }
        // 超时
        if (!session.cancel) {
            kickPlayer(player, `§c${tr("ndpr.kick.verify_timeout")}`);
        }
    } finally {
        runCmd(`effect clear "${name}" blindness`);
        runCmd(`gamemode ${originalGameType === GameType.CREATIVE ? "creative" : originalGameType === GameType.ADVENTURE ? "adventure" : originalGameType === GameType.SPECTATOR ? "spectator" : "survival"} "${name}"`);
        runCmd("gamerule sendcommandfeedback true");
        clearVerifySession(name, session);
    }
}

function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function findPlayer(name: string): Player | null {
    try {
        const level = bedrockServer.level;
        if (!level) return null;
        // 遍历在线玩家（bdsx 通过 server 遍历，这里保守处理）
        const players = (level as unknown as { getPlayers?: () => Player[] }).getPlayers?.();
        if (players) {
            for (const p of players) {
                if (p.getName() === name) return p;
            }
        }
    } catch {
        /* ignore */
    }
    return null;
}

function startHwidVerify(name: string, ip: string | null, force: boolean): void {
    if (!force && !config.check_hwid) return;
    if (!config.token) {
        log(tr("ndpr.warn.token_missing_hwid"));
        return;
    }
    let firstVerify = true;
    if (!force) {
        const record = loadHwidTemp()[name];
        if (record) {
            const intervalDays = config.check_interval ?? 3;
            if (Date.now() / 1000 - (record.time ?? 0) < intervalDays * 86400) return;
            firstVerify = false;
        }
    }
    const old = verifySessions.get(name);
    if (old && !old.cancel) old.cancel = true;
    if (old?.sessionId) cancelApiSession(old.sessionId);
    const session: VerifySession = { cancel: false, sessionId: null, player: name, ip, firstVerify };
    verifySessions.set(name, session);
    runVerify(session).catch((err) => log(`验证流程异常: ${err}`));
}

function onPlayerLeft(player: Player): void {
    const name = player.getName();
    const session = verifySessions.get(name);
    if (session) {
        verifySessions.delete(name);
        session.cancel = true;
        if (session.sessionId) cancelApiSession(session.sessionId);
    }
}

// ================= 进服检查 =================
function onPlayerJoin(player: Player): void {
    const name = player.getName();
    let ip: string | null = null;
    try {
        const addr = player.getNetworkIdentifier().getAddress();
        if (addr) ip = addr.split(":")[0];
    } catch {
        /* ignore */
    }
    let xuid: string | null = null;
    let uuid: string | null = null;
    try {
        xuid = player.getXuid() || null;
    } catch {
        /* ignore */
    }
    try {
        uuid = normalizeUuid(String(player.getUuid()));
    } catch {
        /* ignore */
    }
    const playerUuid = uuid || xuid;
    log(tr("ndpr.log.player_info", { player: name, ip: ip ?? "?", uuid: playerUuid ?? "?", ipv6: "?" }));
    if (!playerUuid && !ip) log(tr("ndpr.warn.player_info_parse_failed", { player: name }));

    savePlayerInfo(name, ip, playerUuid, null);

    const kickInitializing = `§c${tr("ndpr.kick.system_initializing")}`;

    if (!fs.existsSync(BAN_DB_PATH)) {
        log(tr("ndpr.warn.db_missing"));
        asyncDownload().catch(() => undefined);
        if (config.fail_closed) {
            log(tr("ndpr.warn.fail_closed_rejected", { player: name }));
            kickPlayer(player, kickInitializing);
            return;
        }
        log(tr("ndpr.warn.fail_open_allowed", { player: name }));
    } else {
        let banned: { table: string; reason: string; time: string } | null = null;
        try {
            banned = queryBanDb(name, ip, null, playerUuid);
        } catch (err) {
            if (config.fail_closed) {
                log(tr("ndpr.warn.fail_closed_query_error", { player: name, error: err }));
                kickPlayer(player, kickInitializing);
                return;
            }
            log(tr("ndpr.warn.fail_open_query_error", { player: name, error: err }));
        }
        if (banned) {
            log(tr("ndpr.log.banned_detected", { player: name, table: banned.table }));
            kickPlayer(player, `§c${tr("ndpr.kick.banned")}`);
            reportKick();
            return;
        }
    }

    if (config.check_hwid) {
        startHwidVerify(name, ip, false);
    }
}

// ================= 命令 =================
function replyTo(player: Player, msg: string): void {
    try {
        player.sendMessage(msg);
    } catch {
        /* ignore */
    }
}

function helpCallback(player: Player): void {
    replyTo(player, `§6========== §b${tr("ndpr.help.title")} §6==========`);
    replyTo(player, `§e${tr("ndpr.help.version", { version: VERSION })}`);
    replyTo(player, `§e${tr("ndpr.help.author")}`);
    replyTo(player, tr("ndpr.help.qq_group"));
    replyTo(player, "");
    replyTo(player, `§b${tr("ndpr.help.commands")}`);
    replyTo(player, `§f/ndpr help §7- ${tr("ndpr.help.desc.help")}`);
    replyTo(player, `§f/ndpr d / download §7- ${tr("ndpr.help.desc.download")}`);
    replyTo(player, `§f/ndpr ban <ID> <reason> §7- ${tr("ndpr.help.desc.ban")}`);
    replyTo(player, `§f/ndpr check <ID/IP/UUID> §7- ${tr("ndpr.help.desc.check")}`);
    replyTo(player, `§f/ndpr reload §7- ${tr("ndpr.help.desc.reload")}`);
    replyTo(player, `§f/ndpr cu / checkupdate §7- ${tr("ndpr.help.desc.checkupdate")}`);
    replyTo(player, `§f/ndpr auth <ID> §7- ${tr("ndpr.help.desc.auth")}`);
    replyTo(player, "");
    replyTo(player, tr("ndpr.help.footer"));
}

function checkByIdentifier(target: string, idType: "ip" | "ipv6" | "uuid", player: Player): void {
    if (!fs.existsSync(BAN_DB_PATH)) {
        replyTo(player, `§c${tr("ndpr.reply.no_data")}`);
        return;
    }
    try {
        const db = new Database(BAN_DB_PATH, { readonly: true });
        try {
            let found = false;
            for (const table of ["online", "offline"]) {
                const timeCol = tableTimeCol(table, db);
                let row: unknown;
                if (idType === "ip") {
                    row = db.prepare(`SELECT player, ban_reason, ${timeCol} AS t FROM ${table} WHERE ip = ?`).get(target);
                } else if (idType === "ipv6") {
                    row = db.prepare(`SELECT player, ban_reason, ${timeCol} AS t FROM ${table} WHERE ipv6 = ?`).get(target);
                } else {
                    if (!tableHasMcuuid(table, db)) continue;
                    row = db.prepare(`SELECT player, ban_reason, ${timeCol} AS t FROM ${table} WHERE mcuuid = ?`).get(target);
                }
                if (row) {
                    found = true;
                    const r = row as { player: string; ban_reason: string; t: string };
                    replyTo(player, `§7${tr("ndpr.label.player", { player: r.player })}`);
                    replyTo(player, `§7${tr("ndpr.label.reason", { reason: r.ban_reason })}`);
                    replyTo(player, `§7${tr("ndpr.label.ban_time", { time: r.t })}`);
                    break;
                }
            }
            if (!found) replyTo(player, tr("ndpr.reply.record_not_found", { type: idType, value: target }));
        } finally {
            db.close();
        }
    } catch (err) {
        replyTo(player, `§c${tr("ndpr.reply.query_failed", { error: err })}`);
    }
}

function checkBanStatus(target: string, player: Player): void {
    if (!fs.existsSync(BAN_DB_PATH)) {
        replyTo(player, `§c${tr("ndpr.reply.no_data")}`);
        return;
    }
    try {
        const db = new Database(BAN_DB_PATH, { readonly: true });
        try {
            let found = false;
            for (const table of ["online", "offline"]) {
                const timeCol = tableTimeCol(table, db);
                const row = db.prepare(`SELECT ip, ban_reason, ${timeCol} AS t FROM ${table} WHERE player = ?`).get(target) as
                    | { ip: string; ban_reason: string; t: string }
                    | undefined;
                if (row) {
                    replyTo(player, `§c${tr("ndpr.reply.banned_in_table", { player: target, table })}`);
                    replyTo(player, `§7${tr("ndpr.label.ip", { ip: row.ip })}`);
                    replyTo(player, `§7${tr("ndpr.label.reason", { reason: row.ban_reason })}`);
                    replyTo(player, `§7${tr("ndpr.label.ban_time", { time: row.t })}`);
                    found = true;
                    break;
                }
            }
            if (!found) {
                replyTo(player, `§a${tr("ndpr.reply.not_banned", { player: target })}`);
                // 模糊建议
                const matches: string[] = [];
                const seen = new Set<string>();
                for (const table of ["online", "offline"]) {
                    const rows = db
                        .prepare(`SELECT player FROM ${table} WHERE LOWER(player) LIKE ? LIMIT 5`)
                        .all(`%${target.toLowerCase()}%`) as { player: string }[];
                    for (const r of rows) {
                        if (!seen.has(r.player)) {
                            seen.add(r.player);
                            matches.push(r.player);
                        }
                    }
                    if (matches.length >= 5) break;
                }
                if (matches.length > 0) {
                    replyTo(player, `§7${tr("ndpr.reply.fuzzy_suggestion")}`);
                    for (const nm of matches) replyTo(player, `§8${nm}`);
                }
            }
        } finally {
            db.close();
        }
    } catch (err) {
        replyTo(player, `§c${tr("ndpr.reply.query_failed", { error: err })}`);
    }
}

function banCallback(player: Player, target: string, reason: string): void {
    if (!config.token) {
        replyTo(player, `§c${tr("ndpr.reply.token_not_configured")}`);
        return;
    }
    if (!reason) {
        replyTo(player, `§c${tr("ndpr.reply.ban_reason_required")}`);
        replyTo(player, `§7${tr("ndpr.reply.ban_usage")}`);
        return;
    }
    if (!config.api_url) {
        replyTo(player, `§c${tr("ndpr.reply.api_not_configured")}`);
        return;
    }
    replyTo(player, `§e${tr("ndpr.reply.getting_player_info", { player: target })}`);
    const info = loadPlayerInfo(target);
    if (!info || Object.keys(info).length === 0) {
        replyTo(player, `§c${tr("ndpr.reply.player_info_not_found")}`);
        replyTo(player, `§7${tr("ndpr.reply.player_info_hint")}`);
        return;
    }
    const playerIp = (info.ip as string) ?? null;
    const playerUuid = (info.uuid as string) ?? null;
    const infoList: string[] = [];
    if (playerIp) infoList.push(`IP: ${playerIp}`);
    if (playerUuid) infoList.push(`UUID: ${playerUuid}`);
    if (infoList.length > 0) replyTo(player, `§e${tr("ndpr.reply.info_obtained", { info: infoList.join(", ") })}`);
    replyTo(player, `§e${tr("ndpr.reply.ban_reason_echo", { reason })}`);
    replyTo(player, `§e${tr("ndpr.reply.submitting")}`);
    httpRequest(
        "POST",
        `${config.api_url}/check/uploader`,
        {
            player_id: target,
            ip: playerIp,
            ipv6: info.ipv6 ?? null,
            uuid: playerUuid,
            onlinemode: config.onlinemode,
            reason,
        },
        authHeaders(),
        10
    )
        .then((resp) => {
            if (resp.status === 200) {
                const result = parseJson<{ result?: string; check_id?: string; message?: string }>(resp.body);
                if (result.result === "success") {
                    replyTo(player, `§a${tr("ndpr.reply.submit_success")}`);
                    replyTo(player, `§7${tr("ndpr.reply.check_id", { check_id: result.check_id })}`);
                    replyTo(player, `§7${tr("ndpr.reply.wait_review")}`);
                } else {
                    replyTo(player, `§c${tr("ndpr.reply.submit_failed", { message: result.message ?? tr("ndpr.reply.unknown_error") })}`);
                }
            } else if (resp.status === 403) {
                replyTo(player, `§c${tr("ndpr.reply.no_upload_permission")}`);
            } else {
                replyTo(player, `§c${tr("ndpr.reply.submit_failed_http", { code: resp.status })}`);
                const err = parseJson<{ error?: string }>(resp.body).error ?? tr("ndpr.reply.unknown_error");
                replyTo(player, `§7${tr("ndpr.reply.error_info", { error: err })}`);
            }
        })
        .catch(() => {
            replyTo(player, `§c${tr("ndpr.reply.connection_error")}`);
        });
}

function reloadCallback(player: Player): void {
    try {
        replyTo(player, `§e${tr("ndpr.reply.reloading")}`);
        loadConfig();
        downloadBanDatabase((msg) => replyTo(player, msg)).catch(() => undefined);
        replyTo(player, `§a${tr("ndpr.reply.reloaded")}`);
    } catch (err) {
        replyTo(player, `§c${tr("ndpr.reply.reload_failed", { error: err })}`);
    }
}

function authCallback(player: Player, target: string): void {
    const info = loadPlayerInfo(target);
    replyTo(player, `§e${tr("ndpr.reply.auth_starting", { player: target })}`);
    startHwidVerify(target, (info.ip as string) ?? null, true);
}

function checkUpdateCallback(player: Player): void {
    replyTo(player, `§a${tr("ndpr.reply.checking_update")}`);
    httpRequest("GET", "https://api.github.com/repos/NDPReforged/NDPR-BE/releases/latest", undefined, {}, 30)
        .then((resp) => {
            if (resp.status !== 200) {
                replyTo(player, `§c${tr("ndpr.reply.query_failed", { error: `HTTP ${resp.status}` })}`);
                return;
            }
            const data = parseJson<{ tag_name?: string; html_url?: string; body?: string }>(resp.body);
            const latest = String(data.tag_name ?? "").replace(/^v/, "");
            let hasUpdate = false;
            try {
                const cur = VERSION.split(".").map(Number);
                const lat = latest.split(".").map(Number);
                const n = Math.max(cur.length, lat.length);
                while (cur.length < n) cur.push(0);
                while (lat.length < n) lat.push(0);
                for (let i = 0; i < n; i++) {
                    if (lat[i] > cur[i]) {
                        hasUpdate = true;
                        break;
                    }
                }
            } catch {
                hasUpdate = false;
            }
            if (hasUpdate) {
                replyTo(player, `§a${tr("ndpr.reply.update_found")}`);
                replyTo(player, `§a${tr("ndpr.reply.current_version", { version: VERSION })}`);
                replyTo(player, `§a${tr("ndpr.reply.latest_version", { version: latest })}`);
                if (data.body) {
                    const notes = data.body.length > 100 ? data.body.slice(0, 100) + "..." : data.body;
                    replyTo(player, `§a${tr("ndpr.reply.update_notes", { notes })}`);
                }
                replyTo(player, `§a${tr("ndpr.reply.download_url", { url: data.html_url ?? "" })}`);
            } else {
                replyTo(player, `§a${tr("ndpr.reply.up_to_date", { version: VERSION })}`);
            }
        })
        .catch((err) => replyTo(player, `§c${tr("ndpr.reply.query_failed", { error: err })}`));
}

function registerCommands(): void {
    // 单 overload 分发：避免多个同签名 overload 被 BDS 按注册顺序误配
    const cmd = command.register("ndpr", tr("ndpr.help.brief"), CommandPermissionLevel.Normal);
    cmd.overload(
        (params: { sub?: string; target?: string; reason?: string }, origin: CommandOrigin, output: CommandOutput) => {
            const player = origin.getEntity() as Player | null;
            if (!player) {
                output.error("This command is only available for players");
                return;
            }
            const sub = (params.sub ?? "help").toLowerCase();
            try {
                if (sub === "help" || sub === "") {
                    helpCallback(player);
                } else if (sub === "d" || sub === "download") {
                    if (!playerIsAdmin(player)) {
                        replyTo(player, `§c${tr("ndpr.reply.permission_denied")}`);
                        return;
                    }
                    replyTo(player, `§e${tr("ndpr.reply.downloading")}`);
                    asyncDownload((msg) => replyTo(player, msg)).catch(() => undefined);
                } else if (sub === "ban") {
                    if (!playerIsAdmin(player)) {
                        replyTo(player, `§c${tr("ndpr.reply.permission_denied")}`);
                        return;
                    }
                    if (!params.target || !params.reason) {
                        replyTo(player, `§c${tr("ndpr.reply.ban_reason_required")}`);
                        replyTo(player, `§7${tr("ndpr.reply.ban_usage")}`);
                        return;
                    }
                    banCallback(player, params.target, params.reason);
                } else if (sub === "check") {
                    if (!params.target) {
                        replyTo(player, `§7${tr("ndpr.reply.ban_usage")}`);
                        return;
                    }
                    const idType = detectIdentifierType(params.target);
                    if (idType === "ip" || idType === "ipv6" || idType === "uuid") checkByIdentifier(params.target, idType, player);
                    else checkBanStatus(params.target, player);
                } else if (sub === "reload") {
                    if (!playerIsAdmin(player)) {
                        replyTo(player, `§c${tr("ndpr.reply.permission_denied")}`);
                        return;
                    }
                    reloadCallback(player);
                } else if (sub === "cu" || sub === "checkupdate") {
                    if (!playerIsAdmin(player)) {
                        replyTo(player, `§c${tr("ndpr.reply.permission_denied")}`);
                        return;
                    }
                    checkUpdateCallback(player);
                } else if (sub === "auth") {
                    if (!playerIsAdmin(player)) {
                        replyTo(player, `§c${tr("ndpr.reply.permission_denied")}`);
                        return;
                    }
                    if (!params.target) {
                        replyTo(player, `§7${tr("ndpr.reply.auth_usage")}`);
                        return;
                    }
                    authCallback(player, params.target);
                } else {
                    helpCallback(player);
                }
            } catch (err) {
                replyTo(player, `§c${tr("ndpr.reply.query_failed", { error: err })}`);
            }
        },
        {
            sub: [CxxString, true],
            target: [CxxString, true],
            reason: [CommandRawText, true],
        }
    );
}

// ================= 初始化 =================
async function obtainUuid(): Promise<void> {
    if (config.uuid) return;
    if (!config.api_url) throw new Error("Error: " + tr("ndpr.error.api_url_missing"));
    log(tr("ndpr.log.getting_uuid"));
    const resp = await httpRequest("POST", `${config.api_url}/uuid/getuuid`, {}, {}, 10);
    if (resp.status !== 200) {
        throw new Error("Error: " + tr("ndpr.error.get_uuid_http", { code: resp.status, body: resp.body.toString("utf-8").slice(0, 200) }));
    }
    const data = parseJson<{ uuid?: string }>(resp.body);
    if (!data.uuid) throw new Error("Error: " + tr("ndpr.error.get_uuid_invalid", { data: JSON.stringify(data) }));
    config.uuid = data.uuid;
    saveConfig();
    log(tr("ndpr.log.uuid_obtained", { uuid: config.uuid }));
}

async function asyncInit(): Promise<void> {
    const stages: Array<[string, () => Promise<void>]> = [];
    if (!config.uuid) stages.push([tr("ndpr.word.stage_uuid"), obtainUuid]);
    stages.push([tr("ndpr.word.stage_db"), () => downloadBanDatabase()]);
    stages.push([tr("ndpr.word.stage_update"), () => checkUpdateCallbackStub()]);
    for (const [stageName, fn] of stages) {
        try {
            await fn();
        } catch (err) {
            log(tr("ndpr.error.init_stage_failed", { stage: stageName, error: err }));
        }
    }
    startDownloadTask();
    log(tr("ndpr.log.init_done"));
}

function checkUpdateCallbackStub(): Promise<void> {
    return httpRequest("GET", "https://api.github.com/repos/NDPReforged/NDPR-BE/releases/latest", undefined, {}, 30)
        .then((resp) => {
            if (resp.status !== 200) return;
            const data = parseJson<{ tag_name?: string; html_url?: string }>(resp.body);
            const latest = String(data.tag_name ?? "").replace(/^v/, "");
            try {
                const cur = VERSION.split(".").map(Number);
                const lat = latest.split(".").map(Number);
                const n = Math.max(cur.length, lat.length);
                while (cur.length < n) cur.push(0);
                while (lat.length < n) lat.push(0);
                for (let i = 0; i < n; i++) {
                    if (lat[i] > cur[i]) {
                        log(tr("ndpr.log.update_found", { latest, current: VERSION, url: data.html_url ?? "" }));
                        return;
                    }
                }
            } catch {
                /* ignore */
            }
        })
        .catch(() => undefined);
}

// ================= 入口 =================
events.serverOpen.on(() => {
    try {
        loadConfig();
        registerCommands();
        events.playerJoin.on((ev) => {
            onPlayerJoin(ev.player);
        });
        events.playerLeft.on((ev) => {
            onPlayerLeft(ev.player);
        });
        asyncInit().catch((err) => log(`初始化失败: ${err}`));
    } catch (err) {
        log(`插件启动失败: ${err}`);
    }
});

events.serverClose.on(() => {
    if (downloadTimer) clearInterval(downloadTimer);
    for (const session of verifySessions.values()) {
        session.cancel = true;
    }
    log(tr("ndpr.log.unloaded"));
});

log("[NDPR] loaded (BDSX client v" + VERSION + ")");
