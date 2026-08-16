package ndpr.allay;

import ndpr.core.NdpCore;
import ndpr.core.Platform;
import org.allaymc.api.command.Command;
import org.allaymc.api.command.CommandSender;
import org.allaymc.api.command.tree.CommandTree;
import org.allaymc.api.entity.effect.EffectInstance;
import org.allaymc.api.entity.effect.EffectType;
import org.allaymc.api.entity.effect.EffectTypes;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.api.eventbus.event.server.PlayerDisconnectEvent;
import org.allaymc.api.eventbus.event.server.PlayerJoinEvent;
import org.allaymc.api.math.location.Location3d;
import org.allaymc.api.permission.OpPermissionCalculator;
import org.allaymc.api.player.GameMode;
import org.allaymc.api.player.Player;
import org.allaymc.api.registry.Registries;
import org.allaymc.api.server.Server;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;

/**
 * NDPR - Allay 适配层。
 * 业务逻辑全部在 ndpr.core.NdpCore（与 gomint / Nukkit 共享）。
 * Allay 无"执行控制台命令"API，因此冻结/效果/模式/传送全部走原生 API（execute 为空实现）。
 */
public final class NDPRAllay extends org.allaymc.api.plugin.Plugin implements Platform {

    private NdpCore core;
    private final Object listener = new NDPRListener();

    public final class NDPRListener {
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            core.onPlayerJoin(event.getPlayer());
        }

        @EventHandler
        public void onPlayerDisconnect(PlayerDisconnectEvent event) {
            core.onPlayerLeft(event.getPlayer());
        }
    }

    @Override
    public void onEnable() {
        core = new NdpCore(this);
        core.init();
        Server.getInstance().getEventBus().registerListener(listener);
        Registries.COMMANDS.register(new NdprCommand());
        getPluginLogger().info("NDPR Allay 客户端已启用 (v" + NdpCore.VERSION + ")");
    }

    @Override
    public void onDisable() {
        if (core != null) core.shutdown();
    }

    // ================= 命令 =================

    private final class NdprCommand extends Command {
        NdprCommand() {
            super("ndpr", "NDPR主命令", "ndpr.command");
            // 允许非 OP 使用 /ndpr（help / check 面向所有玩家；管理员子命令在核心内二次校验）
            OpPermissionCalculator.NON_OP_PERMISSIONS.addAll(this.permissions);
        }

        @Override
        public void prepareCommandTree(CommandTree tree) {
            tree.getRoot()
                    .str("sub").optional()
                    .str("target").optional()
                    .msg("reason").optional()
                    .exec(context -> {
                        CommandSender sender = context.getSender();
                        String sub = context.getResult(0);
                        String target = context.getResult(1);
                        String reason = context.getResult(2);
                        core.handleCommand(sender, sub, target, reason);
                        return context.success();
                    });
        }
    }

    // ================= 平台辅助 =================

    private Player playerOf(Object h) {
        if (h instanceof Player) return (Player) h;
        if (h instanceof EntityPlayer) {
            try {
                Player c = ((EntityPlayer) h).getController();
                if (c != null) return c;
            } catch (Exception e) {
                // ignore
            }
        }
        return null;
    }

    private EntityPlayer entityOf(Object h) {
        if (h instanceof EntityPlayer) return (EntityPlayer) h;
        Player p = playerOf(h);
        if (p == null) return null;
        try {
            return p.getControlledEntity();
        } catch (Exception e) {
            return null;
        }
    }

    private GameMode toAllayGameMode(String gm) {
        try {
            return GameMode.valueOf(gm.toUpperCase());
        } catch (Exception e) {
            return GameMode.SURVIVAL;
        }
    }

    // ================= Platform =================

    @Override
    public void log(String msg) {
        getPluginLogger().info(msg);
    }

    @Override
    public Path getDataDir() {
        return getPluginContainer().dataFolder();
    }

    @Override
    public void runAsync(Runnable r) {
        Server.getInstance().getVirtualThreadPool().execute(r);
    }

    @Override
    public void runLater(Runnable r, long delayMillis) {
        int ticks = (int) Math.max(1, delayMillis / 50);
        Server.getInstance().getScheduler().scheduleDelayed(this, r, ticks, true);
    }

    @Override
    public void onShutdown() {
        // Allay 调度器随服务端关闭，无需额外清理
    }

    @Override
    public Object getPlayer(String name) {
        return Server.getInstance().getPlayerManager().getPlayerByName(name);
    }

    @Override
    public Object[] getOnlinePlayers() {
        return Server.getInstance().getPlayerManager().getPlayers().values().toArray();
    }

    @Override
    public String playerName(Object player) {
        Player p = playerOf(player);
        if (p != null) return p.getOriginName();
        return player instanceof CommandSender ? ((CommandSender) player).getCommandSenderName() : "?";
    }

    @Override
    public String playerIp(Object player) {
        Player p = playerOf(player);
        if (p == null) return null;
        try {
            if (p.getSocketAddress() instanceof InetSocketAddress) {
                java.net.InetAddress addr = ((InetSocketAddress) p.getSocketAddress()).getAddress();
                return addr == null ? null : addr.getHostAddress();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    @Override
    public String playerXuid(Object player) {
        Player p = playerOf(player);
        if (p == null) return null;
        try {
            return p.getLoginData().getXuid();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String playerUuid(Object player) {
        Player p = playerOf(player);
        if (p == null) return null;
        try {
            java.util.UUID uuid = p.getLoginData().getUuid();
            return uuid == null ? null : uuid.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public double[] playerPosition(Object player) {
        EntityPlayer e = entityOf(player);
        if (e == null) return null;
        try {
            org.joml.Vector3dc pos = e.getLocation();
            return new double[]{pos.x(), pos.y(), pos.z()};
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public String playerGameMode(Object player) {
        EntityPlayer e = entityOf(player);
        if (e == null) return null;
        try {
            return e.getGameMode().name().toLowerCase();
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public void kick(Object player, String reason) {
        Player p = playerOf(player);
        if (p != null) {
            try {
                p.disconnect(reason);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    @Override
    public void sendMessage(Object player, String msg) {
        Player p = playerOf(player);
        if (p != null) p.sendMessage(msg);
        else if (player instanceof CommandSender) ((CommandSender) player).sendMessage(msg);
    }

    @Override
    public void sendTitle(Object player, String title, String subtitle) {
        Player p = playerOf(player);
        if (p == null) return;
        try {
            p.sendTitle(title);
            if (subtitle != null && !subtitle.isEmpty()) p.sendSubtitle(subtitle);
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void teleport(Object player, double x, double y, double z) {
        EntityPlayer e = entityOf(player);
        if (e == null) return;
        try {
            e.teleport(new Location3d(x, y, z, e.getLocation().dimension()));
        } catch (Exception ex) {
            // ignore
        }
    }

    @Override
    public void setGameMode(Object player, String gamemode) {
        EntityPlayer e = entityOf(player);
        if (e == null) return;
        try {
            e.setGameMode(toAllayGameMode(gamemode));
        } catch (Exception ex) {
            // ignore
        }
    }

    private static EffectType effectTypeOf(String name) {
        if (name == null) return null;
        switch (name.toLowerCase()) {
            case "blindness":
                return EffectTypes.BLINDNESS;
            case "slowness":
                return EffectTypes.SLOWNESS;
            case "weakness":
                return EffectTypes.WEAKNESS;
            case "nausea":
                return EffectTypes.NAUSEA;
            default:
                return null;
        }
    }

    @Override
    public void giveEffect(Object player, String effectId, int seconds, int amplifier, boolean hideParticles) {
        EntityPlayer e = entityOf(player);
        if (e == null) return;
        try {
            EffectType type = effectTypeOf(effectId);
            if (type == null) return;
            long durationTicks = (long) seconds * 20L;
            int dur = (int) Math.min(Integer.MAX_VALUE, durationTicks);
            // EffectInstance(EffectType, amplifier, duration(ticks), ambient, visible)
            e.addEffect(new EffectInstance(type, amplifier, dur, true, !hideParticles));
        } catch (Exception ex) {
            // ignore
        }
    }

    @Override
    public void clearEffect(Object player, String effectId) {
        EntityPlayer e = entityOf(player);
        if (e == null) return;
        try {
            EffectType type = effectTypeOf(effectId);
            if (type != null) e.removeEffect(type);
        } catch (Exception ex) {
            // ignore
        }
    }

    @Override
    public void execute(String command) {
        // Allay 无插件执行控制台命令 API；本插件已全部使用原生 API，此方法为空实现
    }

    @Override
    public boolean isAdmin(Object src) {
        if (src == Server.getInstance()) return true; // 控制台
        if (src instanceof CommandSender) {
            try {
                return ((CommandSender) src).hasPermission("ndpr.command.admin").asBoolean();
            } catch (Exception e) {
                // ignore
            }
        }
        Player p = playerOf(src);
        if (p != null) {
            List<String> admins = core.getAdmins();
            return admins.contains(p.getOriginName()) || admins.contains(p.getLoginData().getXuid());
        }
        return false;
    }

    @Override
    public boolean isPlayerSource(Object src) {
        return src instanceof CommandSender && ((CommandSender) src).isPlayer();
    }

    @Override
    public String sourceName(Object src) {
        if (src instanceof CommandSender) {
            if (((CommandSender) src).isPlayer()) {
                EntityPlayer e = ((CommandSender) src).asPlayer();
                if (e != null) {
                    Player c = playerOf(e);
                    if (c != null) return c.getOriginName();
                }
            }
            return ((CommandSender) src).getCommandSenderName();
        }
        return "CONSOLE";
    }

    @Override
    public void reply(Object src, String msg) {
        if (src instanceof CommandSender) {
            ((CommandSender) src).sendMessage(msg);
        } else {
            log(msg.replaceAll("§.", ""));
        }
    }

    @Override
    public Object sourcePlayer(Object src) {
        if (src instanceof CommandSender && ((CommandSender) src).isPlayer()) {
            return ((CommandSender) src).asPlayer();
        }
        return null;
    }
}
