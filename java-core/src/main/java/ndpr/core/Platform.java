package ndpr.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台适配接口：Java 三平台（gomint / Allay / Nukkit）各自实现。
 * 所有玩家句柄统一使用 Object，避免核心依赖平台类型。
 */
public interface Platform {

    // ---------- 基础 ----------

    /** 控制台日志。 */
    void log(String msg);

    /** 数据目录（config.json / ndpr_data 所在目录）。 */
    Path getDataDir();

    /** 提交异步任务。 */
    void runAsync(Runnable r);

    /** 延迟执行（毫秒）。 */
    void runLater(Runnable r, long delayMillis);

    /** 平台关闭时调用（停止定时任务/验证会话）。 */
    void onShutdown();

    // ---------- 玩家信息 ----------

    /** 按名称找在线玩家，未找到返回 null。 */
    Object getPlayer(String name);

    Object[] getOnlinePlayers();

    String playerName(Object player);

    /** IP 地址（不含端口），不可用返回 null。 */
    String playerIp(Object player);

    /** XUID，不可用返回 null。 */
    String playerXuid(Object player);

    /** UUID 字符串，不可用返回 null。 */
    String playerUuid(Object player);

    /** 坐标 {x,y,z}，不可用返回 null。 */
    double[] playerPosition(Object player);

    /** 游戏模式（survival/creative/adventure/spectator），不可用返回 null。 */
    String playerGameMode(Object player);

    // ---------- 操作 ----------

    /** 踢出玩家。 */
    void kick(Object player, String reason);

    /** 发送消息。 */
    void sendMessage(Object player, String msg);

    /** 标题。 */
    void sendTitle(Object player, String title, String subtitle);

    /** 传送。 */
    void teleport(Object player, double x, double y, double z);

    /** 设置游戏模式。 */
    void setGameMode(Object player, String gamemode);

    /** 给予效果。 */
    void giveEffect(Object player, String effectId, int seconds, int amplifier, boolean hideParticles);

    /** 清除效果。 */
    void clearEffect(Object player, String effectId);

    /** 执行控制台命令（平台不支持时可为空实现）。 */
    void execute(String command);

    // ---------- 命令源 ----------

    /** 命令来源是否为管理员（权限等级 2）。 */
    boolean isAdmin(Object src);

    /** 命令来源是否玩家。 */
    boolean isPlayerSource(Object src);

    /** 命令来源名称（玩家名或 "CONSOLE"）。 */
    String sourceName(Object src);

    /** 回复命令来源。 */
    void reply(Object src, String msg);

    /** 命令来源对应的玩家对象（非玩家来源返回 null）。 */
    Object sourcePlayer(Object src);
}
