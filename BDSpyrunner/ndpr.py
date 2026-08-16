# coding=utf-8
# =========================================================
#  NDPR - NDPReforged 封禁系统 - BDSpyrunner 客户端
#  API 兼容 BDSpyrunner / BDSpyrunnerW
#  安装：将本文件放入 BDSpyrunner 的 plugins 目录即可
# =========================================================
# NDPReforged©2026
import os
import re
import time
import json
import ipaddress
import sqlite3
import threading
import urllib.request
import urllib.error
from datetime import datetime, timedelta
from contextlib import contextmanager

try:
    import mc
except ImportError:
    raise SystemExit("[NDPR] 未找到 bdspyrunner 的 mc 模块，请确认运行在 BDSpyrunner/BDSpyrunnerW 环境")

__version__ = "2.1.0"
__plugin_name__ = "NDPR"

# ---------------- 全局状态 ----------------
config = {}
config_path = None
data_dir = None
ban_db_path = None
player_info_path = None
hwid_temp_path = None

_table_schema_cache = {}
player_info_lock = threading.Lock()
hwid_temp_lock = threading.Lock()
download_lock = threading.Lock()
verify_sessions = {}
verify_lock = threading.Lock()
download_stop = threading.Event()
download_task = None

DEFAULT_LANGUAGE = "zh_CN"
VERSION = "2.1"
ADMIN_PERM_LEVEL = 2  # 对应管理员权限（权限等级 2）

_PLUGIN_DIR = os.path.dirname(os.path.abspath(__file__))

# ---------------- 内置翻译（zh_CN / en_us） ----------------
_TRANSLATIONS = {
    "zh_cn": {
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
    "en_us": {
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
}

_DEFAULT_CONFIG = {
    "api_url": "https://api.ndpreforged.com",
    "language": "zh_CN",
    "token": "",
    "uuid": "",
    "onlinemode": True,
    "log_path": "server/logs/latest.log",
    "logger_mode": "default",
    "logger_format": "<[%n%]%name%>%s%<%message%>",
    "download_interval": 900,
    "check_hwid": False,
    "check_interval": 3,
    "fail_closed": False,
    "verify_timeout": 60,
    "freeze_interval": 1,
    "admins": [],
}


def _log(msg):
    try:
        mc.logout(f"[NDPR] {msg}")
    except Exception:
        print(f"[NDPR] {msg}")


# ---------------- 翻译 ----------------
def _current_language():
    lang = str(config.get("language") or DEFAULT_LANGUAGE).strip().lower().replace("-", "_")
    if lang in _TRANSLATIONS:
        return lang
    return DEFAULT_LANGUAGE.lower() if DEFAULT_LANGUAGE.lower() in _TRANSLATIONS else lang


def tr(key, **kwargs):
    text = _TRANSLATIONS.get(_current_language(), {}).get(key)
    if text is None:
        text = _TRANSLATIONS.get(DEFAULT_LANGUAGE.lower(), {}).get(key)
    if text is None:
        text = key
    if kwargs:
        try:
            text = text.format(**kwargs)
        except (KeyError, IndexError, ValueError):
            pass
    return text


# ---------------- 配置 ----------------
def init_config():
    global config, config_path, data_dir, ban_db_path, player_info_path, hwid_temp_path
    config_path = os.path.join(_PLUGIN_DIR, "config.json")
    data_dir = os.path.join(_PLUGIN_DIR, "ndpr_data")
    ban_db_path = os.path.join(data_dir, "ban_database.db")
    player_info_path = os.path.join(data_dir, "player_info.json")
    hwid_temp_path = os.path.join(data_dir, "hwid_temp.json")
    try:
        os.makedirs(data_dir, exist_ok=True)
    except Exception:
        pass

    loaded = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                loaded = json.load(f)
        except (OSError, ValueError):
            loaded = {}

    # 补齐缺失键并规范化
    config = dict(_DEFAULT_CONFIG)
    config.update(loaded)
    changed = False
    for k in _DEFAULT_CONFIG:
        if k not in loaded:
            changed = True

    if isinstance(config.get("onlinemode"), str):
        config["onlinemode"] = config["onlinemode"].strip().lower() == "true"
        changed = True
    if not isinstance(config.get("onlinemode"), bool):
        config["onlinemode"] = True
        changed = True
    for k in ("check_hwid", "fail_closed"):
        if isinstance(config.get(k), str):
            config[k] = config[k].strip().lower() == "true"
            changed = True
    for k in ("check_interval", "download_interval", "verify_timeout", "freeze_interval"):
        v = config.get(k)
        if isinstance(v, str):
            try:
                config[k] = int(v)
                changed = True
            except (ValueError, TypeError):
                pass
        elif not isinstance(v, int):
            config[k] = _DEFAULT_CONFIG[k]
            changed = True

    if changed:
        save_config()

    # 校验关键字段
    errors = []
    api_url = config.get("api_url")
    if not api_url or not isinstance(api_url, str):
        errors.append(tr("ndpr.error.config.field", field="api_url"))
    elif not (api_url.startswith("http://") or api_url.startswith("https://")):
        errors.append(tr("ndpr.error.config.field_hint", field="api_url", hint=tr("ndpr.hint.api_url_scheme")))
    token = config.get("token")
    if token is None or not isinstance(token, str):
        errors.append(tr("ndpr.error.config.field", field="token"))
    onlinemode = config.get("onlinemode")
    if not isinstance(onlinemode, bool):
        errors.append(tr("ndpr.error.config.field", field="onlinemode"))
    if errors:
        raise Exception("Error: " + "; ".join(errors))

    _log(tr("ndpr.log.server_type", type=tr("ndpr.word.online") if config.get("onlinemode") else tr("ndpr.word.offline")))
    _log(tr("ndpr.log.uuid", uuid=config.get("uuid") or tr("ndpr.word.unset")))


def save_config():
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=2, ensure_ascii=False)
    except OSError as e:
        _log(f"保存配置失败: {e}")


# ---------------- HTTP (urllib, 零依赖) ----------------
def _http_request(method, url, payload=None, headers=None, timeout=10):
    """返回 (status, body_bytes)。网络错误抛异常。"""
    data = None
    req_headers = {}
    if headers:
        req_headers.update(headers)
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        req_headers.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except urllib.error.URLError as e:
        raise RuntimeError(str(e.reason))


def _api_post(url, payload=None, headers=None, timeout=10):
    try:
        status, body = _http_request("POST", url, payload, headers, timeout)
        return status, body
    except Exception:
        return None, None


def _auth_headers():
    return {"Authorization": "Bearer " + str(config.get("token", ""))}


def _json(body):
    try:
        return json.loads(body.decode("utf-8")) if body else {}
    except (ValueError, UnicodeDecodeError):
        return {}


# ---------------- SQLite ----------------
def _load_table_schema(conn, table):
    if table in _table_schema_cache:
        return _table_schema_cache[table]
    cols = set()
    try:
        cursor = conn.execute("PRAGMA table_info(%s)" % table)
        for row in cursor.fetchall():
            cols.add(str(row[1]).lower())
    except Exception:
        pass
    _table_schema_cache[table] = cols
    return cols


def _table_time_col(table, conn=None):
    if conn is not None:
        cols = _load_table_schema(conn, table)
        if "ban_time" in cols:
            return "ban_time"
        if "last_seen" in cols:
            return "last_seen"
    return "ban_time" if table == "offline" else "last_seen"


def _table_has_mcuuid(table, conn=None):
    if conn is not None:
        cols = _load_table_schema(conn, table)
        return "mcuuid" in cols
    return table == "online"


@contextmanager
def _db_conn(db_path=None):
    conn = sqlite3.connect(db_path or ban_db_path)
    try:
        yield conn
    finally:
        conn.close()


# ---------------- 封禁库下载 ----------------
def download_ban_database(src=None):
    token = config.get("token")
    if not token:
        msg = tr("ndpr.warn.token_missing")
        _log(msg)
        if src:
            src(msg)
        return
    api_url = config.get("api_url")
    if not api_url:
        msg = tr("ndpr.error.db_api_unconfigured")
        _log(msg)
        if src:
            src(msg)
        return

    try:
        status, body = _http_request("GET", api_url + "/bans/download", headers=_auth_headers(), timeout=30)
    except Exception as e:
        msg = f"{tr('ndpr.reply.connection_error')} ({e})"
        _log(msg)
        if src:
            src(msg)
        return
    if status != 200:
        msg = tr("ndpr.error.db_download_http", code=status, body=(body or b"").decode("utf-8", "ignore")[:200])
        _log(msg)
        if src:
            src(msg)
        return

    data = _json(body)
    download_url = data.get("url")
    if not download_url:
        msg = tr("ndpr.error.db_download_no_url")
        _log(msg)
        if src:
            src(msg)
        return

    try:
        status, content = _http_request("GET", download_url, timeout=60)
    except Exception as e:
        msg = f"{tr('ndpr.reply.connection_error')} ({e})"
        _log(msg)
        if src:
            src(msg)
        return
    if status != 200:
        msg = tr("ndpr.error.db_file_download_http", code=status)
        _log(msg)
        if src:
            src(msg)
        return

    tmp_path = ban_db_path + ".tmp"
    try:
        with open(tmp_path, "wb") as f:
            f.write(content)
        count = 0
        with _db_conn(tmp_path) as test_conn:
            for table_name in ("online", "offline"):
                test_conn.execute("SELECT COUNT(*) FROM %s" % table_name)
                count += test_conn.execute("SELECT COUNT(*) FROM %s" % table_name).fetchone()[0]
        os.replace(tmp_path, ban_db_path)
        _table_schema_cache.clear()
    except Exception as e:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:
                pass
        msg = tr("ndpr.error.db_file_invalid", error=e)
        _log(msg)
        if src:
            src(msg)
        return

    detail_msg = tr("ndpr.log.db_updated", count=count)
    _log(detail_msg)
    if src:
        src("§a" + tr("ndpr.reply.db_download_success"))
        src("§7" + detail_msg)

    try:
        _api_post(api_url + "/bans/download/done", headers=_auth_headers(), timeout=10)
    except Exception:
        pass


def _async_download(src=None):
    if not download_lock.acquire(blocking=False):
        if src:
            src("§e" + tr("ndpr.reply.download_inflight"))
        return
    try:
        download_ban_database(src)
    finally:
        download_lock.release()


def start_download_task():
    global download_task
    interval = config.get("download_interval", 900)
    if not isinstance(interval, int) or interval <= 0:
        _log(tr("ndpr.log.auto_update_disabled"))
        return
    if download_task is not None and download_task.is_alive():
        return
    download_stop.clear()
    download_task = threading.Thread(target=_download_loop, args=(interval,), daemon=True, name="ndpr_download")
    download_task.start()
    _log(tr("ndpr.log.auto_update_started", interval=interval))


def _download_loop(interval):
    while not download_stop.wait(interval):
        try:
            download_ban_database()
        except Exception:
            pass


# ---------------- 玩家信息持久化 ----------------
def save_player_info(player, ip, uuid, ipv6):
    with player_info_lock:
        player_info = {}
        if os.path.exists(player_info_path):
            try:
                with open(player_info_path, "r", encoding="utf-8") as f:
                    player_info = json.load(f)
            except (json.JSONDecodeError, ValueError):
                player_info = {}
        player_info[player] = {"ip": ip, "uuid": uuid, "ipv6": ipv6, "timestamp": time.time()}
        try:
            with open(player_info_path, "w", encoding="utf-8") as f:
                json.dump(player_info, f, indent=2, ensure_ascii=False)
        except OSError:
            pass


def load_player_info(player):
    with player_info_lock:
        if not os.path.exists(player_info_path):
            return {}
        try:
            with open(player_info_path, "r", encoding="utf-8") as f:
                player_info = json.load(f)
        except (json.JSONDecodeError, ValueError):
            return {}
        return player_info.get(player, {}) or {}


# ---------------- 工具 ----------------
def _normalize_uuid(value):
    if not value:
        return None
    if isinstance(value, str):
        if re.match(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", value):
            return value
        m = re.match(r"^\[I?;?\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\]$", value)
        if m:
            ints = [int(m.group(i)) & 0xFFFFFFFF for i in range(1, 5)]
            return "%08x-%04x-%04x-%04x-%012x" % (
                ints[0], (ints[1] >> 16) & 0xFFFF, ints[1] & 0xFFFF,
                (ints[2] >> 16) & 0xFFFF, ((ints[2] & 0xFFFF) << 32) | ints[3]
            )
    elif isinstance(value, (list, tuple)) and len(value) == 4:
        ints = [int(x) & 0xFFFFFFFF for x in value]
        return "%08x-%04x-%04x-%04x-%012x" % (
            ints[0], (ints[1] >> 16) & 0xFFFF, ints[1] & 0xFFFF,
            (ints[2] >> 16) & 0xFFFF, ((ints[2] & 0xFFFF) << 32) | ints[3]
        )
    return str(value)


def _detect_identifier_type(target):
    try:
        ipaddress.ip_address(target)
        return "ipv6" if ":" in target else "ip"
    except ValueError:
        pass
    if len(target) == 36 and target.count("-") == 4:
        return "uuid"
    return "id"


def _player_get(player, method, default=None):
    try:
        fn = getattr(player, method, None)
        if fn is None:
            return default
        v = fn()
        return v if v is not None and v != "" else default
    except Exception:
        return default


def _player_prop(player, prop, default=None):
    try:
        v = getattr(player, prop, default)
        return v if v is not None and v != "" else default
    except Exception:
        return default


def _player_name(player):
    return _player_get(player, "getName") or _player_prop(player, "name") or "?"


def _player_xuid(player):
    return _player_get(player, "getXuid") or _player_prop(player, "xuid")


def _player_uuid(player):
    return _player_get(player, "getUuid") or _player_prop(player, "uuid")


def _player_ip(player):
    return _player_prop(player, "IP") or _player_prop(player, "ip")


def _send_text(player, msg, mode=0):
    try:
        fn = getattr(player, "sendTextPacket", None) or getattr(player, "sendText", None)
        if fn is None:
            return
        try:
            fn(msg, mode)
        except TypeError:
            fn(msg)
    except Exception:
        pass


def _kick(player, reason):
    try:
        fn = getattr(player, "disconnect", None)
        if fn is not None:
            fn(reason)
    except Exception:
        pass


def _run_cmd(cmd):
    try:
        fn = getattr(mc, "runCommand", None) or getattr(mc, "runcmd", None)
        if fn is not None:
            fn(cmd)
    except Exception:
        pass


def _is_admin(player, name):
    try:
        perm = getattr(player, "perm", None)
        if perm is not None:
            try:
                if int(perm) >= ADMIN_PERM_LEVEL:
                    return True
            except (ValueError, TypeError):
                pass
    except Exception:
        pass
    admins = config.get("admins") or []
    if admins:
        return name in admins or (_player_xuid(player) or "") in [str(a) for a in admins]
    _log("警告: 无法获取玩家权限且未配置 admins, 管理员命令对 " + name + " 开放(请配置 config.json 的 admins)")
    return True


def _report_kick():
    try:
        _api_post(config["api_url"] + "/stats/a", payload={}, headers=_auth_headers(), timeout=5)
    except Exception:
        pass


def _kick_player(player, name, reason, report=True):
    _kick(player, reason)
    _log(tr("ndpr.log.kick_hwid", player=name, reason=reason))
    if report:
        _report_kick()


def _title_cmd(name, title_text, subtitle_text=None):
    try:
        # BDS title 命令的 JSON 参数需原样传递（不要 URL 编码）
        payload = {"rawtext": [{"text": title_text}]}
        _run_cmd("title " + name + " title " + json.dumps(payload, ensure_ascii=False))
        if subtitle_text:
            payload2 = {"rawtext": [{"text": subtitle_text}]}
            _run_cmd("title " + name + " subtitle " + json.dumps(payload2, ensure_ascii=False))
    except Exception:
        pass


# ---------------- 进服 / 离服 ----------------
def _get_player_position(player):
    try:
        pos = _player_get(player, "getPosition")
        if pos is None:
            return None
        return {"x": float(pos[0]), "y": float(pos[1]), "z": float(pos[2])}
    except Exception:
        return None


def _freeze_tp(name, pos):
    try:
        _run_cmd("tp %s %.2f %.2f %.2f" % (name, pos["x"], pos["y"], pos["z"]))
    except Exception:
        pass


def _freeze_feedback_off():
    _run_cmd("gamerule sendcommandfeedback false")


def _freeze_feedback_restore():
    _run_cmd("gamerule sendcommandfeedback true")


def on_player_joined(data):
    # 原版 BDSpyrunner 传 dict（含 "Player"），BDSpyrunnerW 直接传 mc.Entity
    player = data if not isinstance(data, dict) else (data.get("Player") or data.get("player"))
    if player is None:
        return
    name = _player_name(player)
    player_ip = _player_ip(player)
    player_xuid = _player_xuid(player)
    player_uuid = _player_uuid(player)
    if player_uuid:
        player_uuid = _normalize_uuid(player_uuid)
    player_ipv6 = None

    _log(tr("ndpr.log.player_info", player=name, ip=player_ip or "?", uuid=player_uuid or player_xuid or "?", ipv6="?"))
    if not (player_uuid or player_xuid or player_ip):
        _log(tr("ndpr.warn.player_info_parse_failed", player=name))

    save_player_info(name, player_ip, player_uuid or player_xuid, player_ipv6)

    kick_initializing = "§c" + tr("ndpr.kick.system_initializing")

    if not os.path.exists(ban_db_path):
        _log(tr("ndpr.warn.db_missing"))
        _async_download()
        if config.get("fail_closed", False):
            _log(tr("ndpr.warn.fail_closed_rejected", player=name))
            _kick(player, kick_initializing)
            return
        _log(tr("ndpr.warn.fail_open_allowed", player=name))
    else:
        try:
            with _db_conn() as conn:
                cursor = conn.cursor()
                for table in ("online", "offline"):
                    if _table_has_mcuuid(table, conn):
                        cursor.execute(
                            "SELECT 1 FROM %s WHERE mcuuid = ? OR player = ? OR ip = ? OR ipv6 = ?"
                            % table,
                            (player_uuid or player_xuid, name, player_ip, player_ipv6),
                        )
                    else:
                        cursor.execute(
                            "SELECT 1 FROM %s WHERE player = ? OR ip = ? OR ipv6 = ?" % table,
                            (name, player_ip, player_ipv6),
                        )
                    if cursor.fetchone():
                        _log(tr("ndpr.log.banned_detected", player=name, table=table))
                        _kick(player, "§c" + tr("ndpr.kick.banned"))
                        _report_kick()
                        return
        except Exception as e:
            if config.get("fail_closed", False):
                _log(tr("ndpr.warn.fail_closed_query_error", player=name, error=e))
                _kick(player, kick_initializing)
                return
            _log(tr("ndpr.warn.fail_open_query_error", player=name, error=e))

    if config.get("check_hwid"):
        start_hwid_verify(name, player_ip, force=False)


def on_player_left(data):
    # BDSpyrunnerW 的 onPlayerLeft 可能不携带玩家信息
    player = data if not isinstance(data, dict) else (data.get("Player") or data.get("player"))
    name = _player_name(player) if player else None
    if name and name in verify_sessions:
        session = None
        with verify_lock:
            session = verify_sessions.pop(name, None)
        if session:
            session["cancel"].set()
            if session.get("session_id"):
                _cancel_api_session_async(session["session_id"])


# ---------------- HWID 验证 ----------------
def load_hwid_temp():
    with hwid_temp_lock:
        if os.path.exists(hwid_temp_path):
            try:
                with open(hwid_temp_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    return data if isinstance(data, dict) else {}
            except (json.JSONDecodeError, ValueError, OSError):
                return {}
        return {}


def save_hwid_temp(player, ip):
    with hwid_temp_lock:
        records = load_hwid_temp()
        records[player] = {"ip": ip or "", "time": time.time()}
        try:
            os.makedirs(os.path.dirname(hwid_temp_path), exist_ok=True)
            with open(hwid_temp_path, "w", encoding="utf-8") as f:
                json.dump(records, f, indent=2, ensure_ascii=False)
        except OSError:
            pass


def _create_verify_session(player, ip):
    url = config.get("api_url") + "/hwid/upd"
    payload = {"player_id": player}
    if ip:
        payload["ip"] = ip
    status, body = _api_post(url, payload, headers=_auth_headers(), timeout=10)
    if status == 200:
        data = _json(body)
        return {
            "session_id": data.get("session_id"),
            "verify_url": data.get("verify_url"),
            "expires_at": int(data.get("expires_at") or 0),
        }
    return None


def _check_verify_status(session_id):
    url = config.get("api_url") + "/hwid/upd/check"
    status, body = _api_post(url, {"session_id": session_id}, headers=_auth_headers(), timeout=3)
    if status == 200:
        return _json(body)
    return None


def _query_has_hwid(player):
    url = config.get("api_url") + "/hwid/has"
    status, body = _api_post(url, {"player_id": player}, headers=_auth_headers(), timeout=5)
    if status == 200:
        return _json(body)
    return None


def _cancel_api_session(session_id):
    try:
        url = config.get("api_url") + "/hwid/upd/cancel"
        _api_post(url, {"session_id": session_id}, headers=_auth_headers(), timeout=5)
    except Exception:
        pass


def _cancel_api_session_async(session_id):
    threading.Thread(target=_cancel_api_session, args=(session_id,), daemon=True).start()


def _clear_verify_session(player, session):
    with verify_lock:
        if verify_sessions.get(player) is session:
            verify_sessions.pop(player, None)


def _get_player_gamemode(player):
    try:
        gm = getattr(player, "gamemode", None) or getattr(player, "getGameMode", None)
        if callable(gm):
            v = gm()
            return str(v).lower()
    except Exception:
        pass
    return None


def start_hwid_verify(name, ip, force=False):
    if not force and not config.get("check_hwid"):
        return
    if not config.get("token"):
        _log(tr("ndpr.warn.token_missing_hwid"))
        return

    first_verify = True
    if not force:
        record = load_hwid_temp().get(name)
        if record is not None:
            interval_days = config.get("check_interval", 3)
            last_time = record.get("time", 0) or 0
            if time.time() - last_time < interval_days * 86400:
                return
            first_verify = False

    with verify_lock:
        old = verify_sessions.get(name)
        if old and not old["cancel"].is_set():
            old["cancel"].set()
        session = {"cancel": threading.Event(), "session_id": None, "player": name, "ip": ip}
        verify_sessions[name] = session

    if old and old.get("session_id"):
        _cancel_api_session(old["session_id"])

    threading.Thread(target=_run_verify, args=(session,), daemon=True, name="ndpr_verify").start()


def _run_verify(session):
    name = session["player"]
    ip = session["ip"]
    player = None
    try:
        for p in (mc.getPlayerList() or []):
            if _player_name(p) == name:
                player = p
                break
    except Exception:
        pass

    if player is None:
        _clear_verify_session(name, session)
        return

    pos0 = _get_player_position(player)
    if pos0 is None:
        _log(tr("ndpr.warn.no_anchor_pos", player=name))

    _freeze_feedback_off()
    _run_cmd("effect give %s blindness 999999 0 true" % name)
    _run_cmd("gamemode adventure %s" % name)

    title_text = tr("ndpr.title.verify").replace('"', "")
    subtitle_text = tr("ndpr.subtitle.verify").replace('"', "")
    _title_cmd(name, title_text, subtitle_text)
    if session.get("first_verify", True):
        _send_text(player, "§e" + tr("ndpr.tell.verify_enabled"))

    try:
        result = _create_verify_session(name, ip)
        if result is None:
            _log(tr("ndpr.error.verify_start_failed", player=name))
            _kick_player(player, name, "§c" + tr("ndpr.kick.verify_unavailable"), report=False)
            return

        session["session_id"] = result["session_id"]
        verify_timeout = config.get("verify_timeout", 60)
        if not isinstance(verify_timeout, int) or verify_timeout < 30:
            verify_timeout = 60
        now = int(time.time())
        raw_expires = result["expires_at"] or now + verify_timeout
        expires_at = min(max(raw_expires, now + 30), now + verify_timeout)
        verify_url = result["verify_url"]
        _send_text(player, "§e" + tr("ndpr.tell.click_verify") + str(verify_url))
        _send_text(player, "§7" + tr("ndpr.tell.verify_freeze_notice"))

        freeze_interval = config.get("freeze_interval", 1)
        if not isinstance(freeze_interval, int) or freeze_interval < 1:
            freeze_interval = 1
        last_freeze = 0.0
        while not session["cancel"].is_set() and time.time() < expires_at:
            if pos0 is not None and time.time() - last_freeze >= freeze_interval:
                _freeze_tp(name, pos0)
                last_freeze = time.time()
            if session["cancel"].wait(1):
                return
            status = _check_verify_status(session["session_id"])
            if status is None:
                continue
            if status.get("completed"):
                if "banned" in status:
                    banned = bool(status.get("banned"))
                    ban_reason = status.get("reason")
                else:
                    has = _query_has_hwid(name)
                    if has is None:
                        _kick_player(player, name, "§c" + tr("ndpr.kick.hwid_status_unknown"), report=False)
                        return
                    banned = bool(has.get("banned"))
                    ban_reason = has.get("reason")
                if banned:
                    reason = ban_reason or tr("ndpr.word.hwid_banned")
                    _kick_player(player, name, "§c" + tr("ndpr.kick.banned_with_reason", reason=reason))
                else:
                    save_hwid_temp(name, ip)
                    _send_text(player, "§a" + tr("ndpr.tell.verify_done"))
                return
            if status.get("status") == "cancelled":
                return
            if status.get("status") == "expired":
                break
    finally:
        _run_cmd("effect clear %s blindness" % name)
        _run_cmd("gamemode survival %s" % name)
        _freeze_feedback_restore()
        _clear_verify_session(name, session)

    if not session["cancel"].is_set():
        _kick_player(player, name, "§c" + tr("ndpr.kick.verify_timeout"), report=False)
    _clear_verify_session(name, session)


# ---------------- 初始化阶段 ----------------
def obtain_uuid():
    if config.get("uuid"):
        return
    api_url = config.get("api_url")
    if not api_url:
        raise Exception("Error: " + tr("ndpr.error.api_url_missing"))
    _log(tr("ndpr.log.getting_uuid"))
    status, body = _api_post(api_url + "/uuid/getuuid", timeout=10)
    if status != 200:
        raise Exception("Error: " + tr("ndpr.error.get_uuid_http", code=status, body=(body or b"").decode("utf-8", "ignore")[:200]))
    data = _json(body)
    if "uuid" not in data:
        raise Exception("Error: " + tr("ndpr.error.get_uuid_invalid", data=data))
    config["uuid"] = data.get("uuid")
    save_config()
    _log(tr("ndpr.log.uuid_obtained", uuid=config["uuid"]))


def check_plugin_update(src=None):
    current_version = VERSION
    api_url = "https://api.github.com/repos/NDPReforged/NDPR-BE/releases/latest"
    if src:
        src("§a" + tr("ndpr.reply.checking_update"))
    try:
        status, body = _http_request("GET", api_url, timeout=30)
        if status != 200:
            if src:
                src("§c" + tr("ndpr.reply.query_failed", error="HTTP %d" % status))
            return
        data = _json(body)
        latest_version = str(data.get("tag_name", "")).lstrip("v")
        has_update = False
        try:
            cur = [int(x) for x in current_version.split(".")]
            lat = [int(x) for x in latest_version.split(".")]
            n = max(len(cur), len(lat))
            cur += [0] * (n - len(cur))
            lat += [0] * (n - len(lat))
            has_update = any(lat[i] > cur[i] for i in range(n))
        except (ValueError, TypeError):
            has_update = False
        if has_update:
            if src:
                src("§a" + tr("ndpr.reply.update_found"))
                src("§a" + tr("ndpr.reply.current_version", version=current_version))
                src("§a" + tr("ndpr.reply.latest_version", version=latest_version))
                notes = data.get("body") or ""
                if notes:
                    src("§a" + tr("ndpr.reply.update_notes", notes=notes[:100] + ("..." if len(notes) > 100 else "")))
                src("§a" + tr("ndpr.reply.download_url", url=data.get("html_url", "")))
            _log(tr("ndpr.log.update_found", latest=latest_version, current=current_version, url=data.get("html_url", "")))
        elif src:
            src("§a" + tr("ndpr.reply.up_to_date", version=current_version))
    except Exception as e:
        if src:
            src("§c" + tr("ndpr.reply.query_failed", error=e))


# ---------------- 命令 ----------------
def _reply(player, msg):
    _send_text(player, msg)


def help_callback(player):
    _reply(player, "§6========== §b" + tr("ndpr.help.title") + " §6==========")
    _reply(player, "§e" + tr("ndpr.help.version", version=VERSION))
    _reply(player, "§e" + tr("ndpr.help.author"))
    _reply(player, tr("ndpr.help.qq_group"))
    _reply(player, "")
    _reply(player, "§b" + tr("ndpr.help.commands"))
    _reply(player, "§f/ndpr help §7- " + tr("ndpr.help.desc.help"))
    _reply(player, "§f/ndpr d / download §7- " + tr("ndpr.help.desc.download"))
    _reply(player, "§f/ndpr ban <ID> <reason> §7- " + tr("ndpr.help.desc.ban"))
    _reply(player, "§f/ndpr check <ID/IP/UUID> §7- " + tr("ndpr.help.desc.check"))
    _reply(player, "§f/ndpr reload §7- " + tr("ndpr.help.desc.reload"))
    _reply(player, "§f/ndpr cu / checkupdate §7- " + tr("ndpr.help.desc.checkupdate"))
    _reply(player, "§f/ndpr auth <ID> §7- " + tr("ndpr.help.desc.auth"))
    _reply(player, "")
    _reply(player, tr("ndpr.help.footer"))


def check_ban_by_identifier(src, identifier_type, value):
    if not os.path.exists(ban_db_path):
        _reply(src, "§c" + tr("ndpr.reply.no_data"))
        return
    try:
        with _db_conn() as conn:
            cursor = conn.cursor()
            found = False
            for table in ("online", "offline"):
                time_col = _table_time_col(table, conn)
                if identifier_type == "ip":
                    cursor.execute("SELECT player, ban_reason, %s FROM %s WHERE ip = ?" % (time_col, table), (value,))
                elif identifier_type == "ipv6":
                    cursor.execute("SELECT player, ban_reason, %s FROM %s WHERE ipv6 = ?" % (time_col, table), (value,))
                elif identifier_type == "uuid":
                    if not _table_has_mcuuid(table, conn):
                        continue
                    cursor.execute("SELECT player, ban_reason, %s FROM %s WHERE mcuuid = ?" % (time_col, table), (value,))
                result = cursor.fetchone()
                if result:
                    found = True
                    _reply(src, "§7" + tr("ndpr.label.player", player=result[0]))
                    _reply(src, "§7" + tr("ndpr.label.reason", reason=result[1]))
                    _reply(src, "§7" + tr("ndpr.label.ban_time", time=result[2]))
                    break
            if not found:
                _reply(src, tr("ndpr.reply.record_not_found", type=identifier_type, value=value))
    except Exception as e:
        _reply(src, "§c" + tr("ndpr.reply.query_failed", error=e))


def check_ban_status(src, player_name):
    if not os.path.exists(ban_db_path):
        _reply(src, "§c" + tr("ndpr.reply.no_data"))
        return
    try:
        with _db_conn() as conn:
            cursor = conn.cursor()
            found = False
            for table in ("online", "offline"):
                time_col = _table_time_col(table, conn)
                cursor.execute("SELECT ip, ban_reason, %s FROM %s WHERE player = ?" % (time_col, table), (player_name,))
                result = cursor.fetchone()
                if result:
                    _reply(src, "§c" + tr("ndpr.reply.banned_in_table", player=player_name, table=table))
                    _reply(src, "§7" + tr("ndpr.label.ip", ip=result[0]))
                    _reply(src, "§7" + tr("ndpr.label.reason", reason=result[1]))
                    _reply(src, "§7" + tr("ndpr.label.ban_time", time=result[2]))
                    found = True
                    break
            if not found:
                _reply(src, "§a" + tr("ndpr.reply.not_banned", player=player_name))
                # 模糊建议（BDSpyrunner 无可点击文本，纯文本提示）
                matches = []
                seen = set()
                pattern = "%" + player_name.lower() + "%"
                for table in ("online", "offline"):
                    cursor2 = conn.cursor()
                    cursor2.execute("SELECT player FROM %s WHERE LOWER(player) LIKE ? LIMIT 5" % table, (pattern,))
                    for row in cursor2.fetchall():
                        nm = row[0]
                        if nm not in seen:
                            seen.add(nm)
                            matches.append(nm)
                if matches:
                    _reply(src, "§7" + tr("ndpr.reply.fuzzy_suggestion"))
                    for nm in matches[:5]:
                        _reply(src, "§8" + nm)
    except Exception as e:
        _reply(src, "§c" + tr("ndpr.reply.query_failed", error=e))


def ban_callback(player, name, player_name, reason):
    token = config.get("token")
    if not token:
        _reply(player, "§c" + tr("ndpr.reply.token_not_configured"))
        return
    if not reason:
        _reply(player, "§c" + tr("ndpr.reply.ban_reason_required"))
        _reply(player, "§7" + tr("ndpr.reply.ban_usage"))
        return
    api_url = config.get("api_url")
    if not api_url:
        _reply(player, "§c" + tr("ndpr.reply.api_not_configured"))
        return
    _reply(player, "§e" + tr("ndpr.reply.getting_player_info", player=player_name))
    player_info = load_player_info(player_name)
    if not player_info:
        _reply(player, "§c" + tr("ndpr.reply.player_info_not_found"))
        _reply(player, "§7" + tr("ndpr.reply.player_info_hint"))
        return
    player_ip = player_info.get("ip")
    player_uuid = player_info.get("uuid")
    info_list = []
    if player_ip:
        info_list.append("IP: " + str(player_ip))
    if player_uuid:
        info_list.append("UUID: " + str(player_uuid))
    if info_list:
        _reply(player, "§e" + tr("ndpr.reply.info_obtained", info=", ".join(info_list)))
    _reply(player, "§e" + tr("ndpr.reply.ban_reason_echo", reason=reason))
    _reply(player, "§e" + tr("ndpr.reply.submitting"))
    try:
        status, body = _http_request(
            "POST",
            api_url + "/check/uploader",
            payload={
                "player_id": player_name,
                "ip": player_ip,
                "ipv6": player_info.get("ipv6"),
                "uuid": player_uuid,
                "onlinemode": config.get("onlinemode", False),
                "reason": reason,
            },
            headers=_auth_headers(),
            timeout=10,
        )
        if status == 200:
            result = _json(body)
            if result.get("result") == "success":
                check_id = result.get("check_id")
                _reply(player, "§a" + tr("ndpr.reply.submit_success"))
                _reply(player, "§7" + tr("ndpr.reply.check_id", check_id=check_id))
                _reply(player, "§7" + tr("ndpr.reply.wait_review"))
            else:
                _reply(player, "§c" + tr("ndpr.reply.submit_failed", message=result.get("message") or tr("ndpr.reply.unknown_error")))
        elif status == 403:
            _reply(player, "§c" + tr("ndpr.reply.no_upload_permission"))
        else:
            _reply(player, "§c" + tr("ndpr.reply.submit_failed_http", code=status))
            try:
                err = _json(body).get("error") or tr("ndpr.reply.unknown_error")
                _reply(player, "§7" + tr("ndpr.reply.error_info", error=err))
            except Exception:
                _reply(player, "§7" + tr("ndpr.reply.response_body", body=(body or b"").decode("utf-8", "ignore")[:200]))
    except Exception as e:
        _reply(player, "§c" + tr("ndpr.reply.timeout") if "timed out" in str(e).lower() else "§c" + tr("ndpr.reply.connection_error"))


def reload_callback(player, name):
    try:
        _reply(player, "§e" + tr("ndpr.reply.reloading"))
        init_config()
        download_ban_database(lambda msg: _reply(player, msg))
        _reply(player, "§a" + tr("ndpr.reply.reloaded"))
    except Exception as e:
        _reply(player, "§c" + tr("ndpr.reply.reload_failed", error=e))


def auth_callback(player, name, player_name):
    info = load_player_info(player_name)
    ip = info.get("ip")
    _reply(player, "§e" + tr("ndpr.reply.auth_starting", player=player_name))
    start_hwid_verify(player_name, ip, force=True)


def _parse_args(cmdline):
    """把命令字符串拆成参数（支持双引号）"""
    args = []
    buf = ""
    in_q = False
    for ch in cmdline:
        if ch == '"':
            in_q = not in_q
        elif ch.isspace() and not in_q:
            if buf:
                args.append(buf)
                buf = ""
        else:
            buf += ch
    if buf:
        args.append(buf)
    return args


def on_player_command(data):
    """监听 onPlayerCmd / onInputCommand，返回 False 表示拦截该命令"""
    if isinstance(data, dict):
        player = data.get("Player") or data.get("player")
        cmdline = data.get("Command") or data.get("cmd") or ""
    else:
        player = data
        cmdline = ""
    if player is None:
        return True
    cmdline = cmdline.strip()
    if cmdline.startswith("/"):
        cmdline = cmdline[1:]
    if not (cmdline == "ndpr" or cmdline.startswith("ndpr ")):
        return True

    name = _player_name(player)
    args = _parse_args(cmdline[len("ndpr"):].strip())
    sub = args[0].lower() if args else "help"

    try:
        if sub in ("help", ""):
            help_callback(player)
        elif sub in ("d", "download"):
            if not _is_admin(player, name):
                _reply(player, "§c" + tr("ndpr.reply.permission_denied"))
                return False
            _reply(player, "§e" + tr("ndpr.reply.downloading"))
            threading.Thread(target=_async_download, args=(lambda msg: _reply(player, msg),), daemon=True).start()
        elif sub == "ban":
            if not _is_admin(player, name):
                _reply(player, "§c" + tr("ndpr.reply.permission_denied"))
                return False
            if len(args) < 3:
                _reply(player, "§c" + tr("ndpr.reply.ban_reason_required"))
                _reply(player, "§7" + tr("ndpr.reply.ban_usage"))
                return False
            ban_callback(player, name, args[1], " ".join(args[2:]))
        elif sub == "check":
            if len(args) < 2:
                _reply(player, "§7" + tr("ndpr.reply.ban_usage"))
                return False
            target = args[1]
            id_type = _detect_identifier_type(target)
            if id_type in ("ip", "ipv6", "uuid"):
                check_ban_by_identifier(player, id_type, target)
            else:
                check_ban_status(player, target)
        elif sub == "reload":
            if not _is_admin(player, name):
                _reply(player, "§c" + tr("ndpr.reply.permission_denied"))
                return False
            threading.Thread(target=reload_callback, args=(player, name), daemon=True).start()
        elif sub in ("cu", "checkupdate"):
            if not _is_admin(player, name):
                _reply(player, "§c" + tr("ndpr.reply.permission_denied"))
                return False
            threading.Thread(target=check_plugin_update, args=(lambda msg: _reply(player, msg),), daemon=True).start()
        elif sub == "auth":
            if not _is_admin(player, name):
                _reply(player, "§c" + tr("ndpr.reply.permission_denied"))
                return False
            if len(args) < 2:
                _reply(player, "§7" + tr("ndpr.reply.auth_usage"))
                return False
            threading.Thread(target=auth_callback, args=(player, name, args[1]), daemon=True).start()
        else:
            help_callback(player)
    except Exception as e:
        _reply(player, "§c" + tr("ndpr.reply.query_failed", error=e))
    return False  # 拦截，避免 BDS 报未知命令


# ---------------- 启动 ----------------
def _register_listeners():
    """同时兼容 BDSpyrunner(原版) 与 BDSpyrunnerW 的事件名"""
    registered = set()
    # 进服：onJoin(原版, dict) / onPlayerJoin(W, entity)
    for ev in ("onJoin", "onPlayerJoin"):
        try:
            mc.setListener(ev, on_player_joined)
            registered.add(ev)
        except Exception:
            pass
    # 离服：onLeft(原版) / onPlayerLeft(W)
    for ev in ("onLeft", "onPlayerLeft"):
        try:
            mc.setListener(ev, on_player_left)
            registered.add(ev)
        except Exception:
            pass
    # 命令：onPlayerCmd(原版) / onInputCommand(W)
    for ev in ("onPlayerCmd", "onInputCommand"):
        try:
            mc.setListener(ev, on_player_command)
            registered.add(ev)
        except Exception:
            pass
    _log("已注册监听器: " + ", ".join(sorted(registered)))


def _async_init():
    stages = []
    if not config.get("uuid"):
        stages.append((tr("ndpr.word.stage_uuid"), obtain_uuid))
    stages.append((tr("ndpr.word.stage_db"), download_ban_database))
    stages.append((tr("ndpr.word.stage_update"), check_plugin_update))
    for stage_name, fn in stages:
        try:
            fn()
        except Exception as e:
            _log(tr("ndpr.error.init_stage_failed", stage=stage_name, error=e))
    start_download_task()
    _log(tr("ndpr.log.init_done"))


def startup():
    try:
        init_config()
    except Exception as e:
        _log(f"配置错误，插件未启动: {e}")
        return
    _register_listeners()
    threading.Thread(target=_async_init, daemon=True, name="ndpr_init").start()


startup()
