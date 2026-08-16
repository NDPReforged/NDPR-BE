package ndpr.nukkit;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Position;
import cn.nukkit.plugin.PluginBase;
import ndpr.core.NdpCore;
import ndpr.core.Platform;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * NDPR - Nukkit 适配层（兼容 Cloudburst Nukkit / PowerNukkitX）。
 * 业务逻辑全部在 ndpr.core.NdpCore（与 Allay / gomint 共享）。
 */
public final class NDPRNukkit extends PluginBase implements Platform, Listener {

    private NdpCore core;

    @Override
    public void onEnable() {
        core = new NdpCore(this);
        core.init();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getCommandMap().register("ndpr", new NdprCommand("ndpr"));
        getLogger().info("NDPR Nukkit 客户端已启用 (v" + NdpCore.VERSION + ")");
    }

    @Override
    public void onDisable() {
        if (core != null) core.shutdown();
        getLogger().info("NDPR Nukkit 客户端已停用");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerJoin(PlayerJoinEvent e) {
        core.onPlayerJoin(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent e) {
        core.onPlayerLeft(e.getPlayer());
    }

    // ================= 命令 =================

    private final class NdprCommand extends Command {
        NdprCommand(String name) {
            super(name, "NDPR主命令", "/ndpr help");
            this.setPermission("ndpr.command");
            this.setPermissionMessage("§c" + "权限不足");
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            String sub = args.length > 0 ? args[0] : "help";
            String target = args.length > 1 ? args[1] : null;
            String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
            core.handleCommand(sender, sub, target, reason);
            return true;
        }
    }

    // ================= Platform =================

    @Override
    public void log(String msg) {
        getLogger().info(msg);
    }

    @Override
    public Path getDataDir() {
        return getDataFolder().toPath();
    }

    @Override
    public void runAsync(Runnable r) {
        getServer().getScheduler().scheduleAsyncTask(this, new cn.nukkit.scheduler.AsyncTask() {
            @Override
            public void onRun() {
                r.run();
            }
        });
    }

    @Override
    public void runLater(Runnable r, long delayMillis) {
        int ticks = (int) Math.max(1, delayMillis / 50);
        getServer().getScheduler().scheduleDelayedTask(r, ticks);
    }

    @Override
    public void onShutdown() {
        // 核心已置关闭标志；残留调度任务无害
    }

    @Override
    public Object getPlayer(String name) {
        return getServer().getPlayer(name);
    }

    @Override
    public Object[] getOnlinePlayers() {
        return getServer().getOnlinePlayers().values().toArray();
    }

    @Override
    public String playerName(Object player) {
        return ((Player) player).getName();
    }

    @Override
    public String playerIp(Object player) {
        return ((Player) player).getAddress();
    }

    @Override
    public String playerXuid(Object player) {
        try {
            return ((Player) player).getLoginChainData().getXUID();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String playerUuid(Object player) {
        try {
            return ((Player) player).getUniqueId() != null ? ((Player) player).getUniqueId().toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public double[] playerPosition(Object player) {
        Player p = (Player) player;
        return new double[]{p.getX(), p.getY(), p.getZ()};
    }

    @Override
    public String playerGameMode(Object player) {
        // Nukkit getGamemode() 返回 int：0=生存 1=创造 2=冒险 3=旁观
        switch (((Player) player).getGamemode()) {
            case 1:
                return "creative";
            case 2:
                return "adventure";
            case 3:
                return "spectator";
            default:
                return "survival";
        }
    }

    @Override
    public void kick(Object player, String reason) {
        ((Player) player).kick(reason, false);
    }

    @Override
    public void sendMessage(Object player, String msg) {
        ((Player) player).sendMessage(msg);
    }

    @Override
    public void sendTitle(Object player, String title, String subtitle) {
        Player p = (Player) player;
        try {
            p.sendTitle(title, subtitle, 5, 60, 5);
        } catch (Throwable t) {
            try {
                p.sendTitle(title);
            } catch (Throwable t2) {
                // 旧版本 API 无标题支持，忽略
            }
        }
    }

    @Override
    public void teleport(Object player, double x, double y, double z) {
        Player p = (Player) player;
        try {
            p.teleport(new Position(x, y, z, p.getLevel()));
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void setGameMode(Object player, String gamemode) {
        Player p = (Player) player;
        int gm = 0;
        if ("creative".equalsIgnoreCase(gamemode)) gm = 1;
        else if ("adventure".equalsIgnoreCase(gamemode)) gm = 2;
        else if ("spectator".equalsIgnoreCase(gamemode)) gm = 3;
        try {
            p.setGamemode(gm);
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
            Server.getInstance().dispatchCommand(Server.getInstance().getConsoleSender(), command);
        } catch (Exception e) {
            log("执行命令失败 [" + command + "]: " + e);
        }
    }

    @Override
    public boolean isAdmin(Object src) {
        if (!(src instanceof Player)) return true; // 控制台
        Player p = (Player) src;
        if (p.isOp()) return true;
        List<String> admins = core.getAdmins();
        return admins.contains(p.getName()) || admins.contains(String.valueOf(p.getLoginChainData().getXUID()));
    }

    @Override
    public boolean isPlayerSource(Object src) {
        return src instanceof Player;
    }

    @Override
    public String sourceName(Object src) {
        return src instanceof Player ? ((Player) src).getName() : "CONSOLE";
    }

    @Override
    public void reply(Object src, String msg) {
        if (src instanceof Player) {
            ((Player) src).sendMessage(msg);
        } else {
            log(msg.replaceAll("§.", ""));
        }
    }

    @Override
    public Object sourcePlayer(Object src) {
        return src instanceof Player ? src : null;
    }
}
