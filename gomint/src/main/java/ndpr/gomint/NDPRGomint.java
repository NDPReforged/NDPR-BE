package ndpr.gomint;

import io.gomint.GoMint;
import io.gomint.command.Command;
import io.gomint.command.CommandOutput;
import io.gomint.command.CommandOverload;
import io.gomint.command.CommandSender;
import io.gomint.command.validator.StringValidator;
import io.gomint.command.validator.TextValidator;
import io.gomint.entity.EntityPlayer;
import io.gomint.event.EventHandler;
import io.gomint.event.EventListener;
import io.gomint.event.player.PlayerJoinEvent;
import io.gomint.event.player.PlayerQuitEvent;
import io.gomint.math.Location;
import io.gomint.plugin.Plugin;
import io.gomint.plugin.PluginName;
import io.gomint.plugin.Version;
import ndpr.core.NdpCore;
import ndpr.core.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NDPR - gomint 适配层。
 * 业务逻辑全部在 ndpr.core.NdpCore（与 Allay / Nukkit 共享）。
 */
@PluginName("NDPR")
@Version(major = 2, minor = 1)
public final class NDPRGomint extends Plugin implements Platform, EventListener {

    private NdpCore core;

    @Override
    public void onStartup() {
        // 非游戏操作放这里：目录准备（核心初始化在 onInstall 中完成）
    }

    @Override
    public void onInstall() {
        core = new NdpCore(this);
        core.init();
        registerListener(this);
        registerCommand(new NdprCommand());
        logger().info("NDPR gomint 客户端已启用 (v" + NdpCore.VERSION + ")");
    }

    @Override
    public void onUninstall() {
        if (core != null) core.shutdown();
    }

    // ================= 事件 =================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        core.onPlayerJoin(event.player());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        core.onPlayerLeft(event.player());
    }

    // ================= 命令 =================

    private final class NdprCommand extends Command {
        NdprCommand() {
            super("ndpr");
            description("NDPR主命令");
            CommandOverload overload = overload();
            overload.param("sub", new StringValidator(".*"), true);
            overload.param("target", new StringValidator(".*"), true);
            overload.param("reason", new TextValidator(), true);
        }

        @Override
        public CommandOutput execute(CommandSender<?> sender, String alias, Map<String, Object> arguments) {
            String sub = (String) arguments.get("sub");
            String target = (String) arguments.get("target");
            String reason = (String) arguments.get("reason");
            core.handleCommand(sender, sub, target, reason);
            return CommandOutput.successful();
        }
    }

    // ================= Platform =================

    @Override
    public void log(String msg) {
        logger().info(msg);
    }

    @Override
    public Path getDataDir() {
        return dataFolder().toPath();
    }

    @Override
    public void runAsync(Runnable r) {
        new Thread(r, "ndpr-async").start();
    }

    @Override
    public void runLater(Runnable r, long delayMillis) {
        new Thread(() -> {
            try {
                Thread.sleep(Math.max(1, delayMillis));
                r.run();
            } catch (InterruptedException e) {
                // ignore
            }
        }, "ndpr-delay").start();
    }

    @Override
    public void onShutdown() {
        // gomint 插件卸载时无需额外清理
    }

    @Override
    public Object getPlayer(String name) {
        return GoMint.instance().findPlayerByName(name);
    }

    @Override
    public Object[] getOnlinePlayers() {
        return GoMint.instance().onlinePlayers().toArray();
    }

    @Override
    public String playerName(Object player) {
        return ((EntityPlayer) player).name();
    }

    @Override
    public String playerIp(Object player) {
        try {
            java.net.InetSocketAddress addr = ((EntityPlayer) player).address();
            return addr == null || addr.getAddress() == null ? null : addr.getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String playerXuid(Object player) {
        // gomint API 未暴露 XUID，返回 null（使用 UUID 兜底）
        return null;
    }

    @Override
    public String playerUuid(Object player) {
        try {
            UUID uuid = ((EntityPlayer) player).uuid();
            return uuid == null ? null : uuid.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public double[] playerPosition(Object player) {
        try {
            Location loc = ((EntityPlayer) player).location();
            return new double[]{loc.x(), loc.y(), loc.z()};
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String playerGameMode(Object player) {
        try {
            return ((EntityPlayer) player).gamemode().name().toLowerCase();
        } catch (Exception e) {
            return "survival";
        }
    }

    @Override
    public void kick(Object player, String reason) {
        try {
            ((EntityPlayer) player).disconnect(reason);
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void sendMessage(Object player, String msg) {
        ((EntityPlayer) player).sendMessage(msg);
    }

    @Override
    public void sendTitle(Object player, String title, String subtitle) {
        try {
            ((EntityPlayer) player).sendTitle(title, subtitle);
        } catch (Exception e) {
            try {
                ((EntityPlayer) player).sendTitle(title);
            } catch (Exception e2) {
                // ignore
            }
        }
    }

    @Override
    public void teleport(Object player, double x, double y, double z) {
        try {
            Location cur = ((EntityPlayer) player).location();
            Location loc = new Location(cur.world(), (float) x, (float) y, (float) z);
            ((EntityPlayer) player).teleport(loc);
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void setGameMode(Object player, String gamemode) {
        try {
            ((EntityPlayer) player).gamemode(io.gomint.world.Gamemode.valueOf(gamemode.toUpperCase()));
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void giveEffect(Object player, String effectId, int seconds, int amplifier, boolean hideParticles) {
        execute("effect " + playerName(player) + " " + effectId + " " + seconds + " " + amplifier + (hideParticles ? " true" : ""));
    }

    @Override
    public void clearEffect(Object player, String effectId) {
        execute("effect " + playerName(player) + " clear");
    }

    @Override
    public void execute(String command) {
        try {
            GoMint.instance().dispatchCommand(command);
        } catch (Exception e) {
            log("执行命令失败 [" + command + "]: " + e);
        }
    }

    @Override
    public boolean isAdmin(Object src) {
        if (!(src instanceof EntityPlayer)) return true; // 控制台
        EntityPlayer p = (EntityPlayer) src;
        if (p.op()) return true;
        List<String> admins = core.getAdmins();
        return admins.contains(p.name());
    }

    @Override
    public boolean isPlayerSource(Object src) {
        return src instanceof EntityPlayer;
    }

    @Override
    public String sourceName(Object src) {
        return src instanceof EntityPlayer ? ((EntityPlayer) src).name() : "CONSOLE";
    }

    @Override
    public void reply(Object src, String msg) {
        if (src instanceof EntityPlayer) {
            ((EntityPlayer) src).sendMessage(msg);
        } else {
            log(msg.replaceAll("§.", ""));
        }
    }

    @Override
    public Object sourcePlayer(Object src) {
        return src instanceof EntityPlayer ? src : null;
    }
}
