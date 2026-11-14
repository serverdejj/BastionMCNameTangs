package mc.bastion.nametangs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BastionMCNameTangs extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private LuckPerms luckPerms;
    private BukkitTask updater;

    @Override public void onEnable() {
        saveDefaultConfig();
        luckPerms = LuckPermsProvider.get();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("nametangs") != null) {
            getCommand("nametangs").setExecutor((sender, command, alias, args) -> {
                if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                    sender.sendMessage("§7Uso: §a/nametangs reload");
                    return true;
                }
                if (!sender.hasPermission("bastionnametangs.admin")) {
                    sender.sendMessage("§cNo tienes permiso.");
                    return true;
                }
                reloadConfig();
                restartUpdater();
                sender.sendMessage("§aBastionMCNameTangs recargado.");
                return true;
            });
        }
        // Esperar un tick para garantizar que los jugadores ya esten presentes.
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) createLabel(player);
        });
        restartUpdater();
    }

    @Override public void onDisable() {
        if (updater != null) updater.cancel();
        for (TextDisplay label : labels.values()) if (label.isValid()) label.remove();
        labels.clear();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) createLabel(event.getPlayer());
        }, 2L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        removeLabel(event.getPlayer().getUniqueId());
    }

    private void restartUpdater() {
        if (updater != null) updater.cancel();
        long period = Math.max(2, getConfig().getLong("update-ticks", 10));
        updater = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (getConfig().getBoolean("nametag.enabled", true)) {
                    TextDisplay label = labels.get(player.getUniqueId());
                    if (label == null || !label.isValid() || !player.getPassengers().contains(label)) {
                        createLabel(player);
                        label = labels.get(player.getUniqueId());
                    }
                    if (label != null) {
                        label.text(render(player, String.join("\n",
                            getConfig().getString("nametag.line-one", "%prefix% &f%player%"),
                            getConfig().getString("nametag.line-two", "&c❤ %health%/%max_health%"),
                            getConfig().getString("nametag.line-three", "%clan%"))));
                        label.setShadowed(getConfig().getBoolean("nametag.shadow", true));
                        label.setDefaultBackground(getConfig().getBoolean("nametag.background", false));
                    }
                } else removeLabel(player.getUniqueId());
                if (getConfig().getBoolean("self-actionbar.enabled", true)) {
                    player.sendActionBar(render(player, getConfig().getString("self-actionbar.text", "%prefix% &f%player% &8| &c❤ %health%/%max_health% &8| %clan%")));
                }
            }
        }, period, period);
    }

    private void createLabel(Player player) {
        removeLabel(player.getUniqueId());
        if (!player.isOnline() || !getConfig().getBoolean("nametag.enabled", true)) return;
        TextDisplay display = player.getWorld().spawn(player.getLocation(), TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setLineWidth(250);
            entity.setViewRange(1.0f);
            entity.text(Component.empty());
        });
        if (!player.addPassenger(display)) {
            display.remove();
            return;
        }
        labels.put(player.getUniqueId(), display);
    }

    private void removeLabel(UUID id) {
        TextDisplay previous = labels.remove(id);
        if (previous != null && previous.isValid()) previous.remove();
    }

    private Component render(Player player, String format) {
        String prefix = "";
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user != null) {
            String cachedPrefix = user.getCachedData().getMetaData().getPrefix();
            if (cachedPrefix != null) prefix = cachedPrefix;
        }
        if (prefix.isBlank()) prefix = getConfig().getString("nametag.rank-fallback", "&7[Jugador]");
        double health = Math.max(0.0, player.getHealth());
        double maxHealth = Math.max(1.0, player.getMaxHealth());
        String processed = format
            .replace("%prefix%", prefix)
            .replace("%player%", player.getName())
            .replace("%health%", Integer.toString((int)Math.ceil(health)))
            .replace("%max_health%", Integer.toString((int)Math.ceil(maxHealth)))
            .replace("%clan%", getConfig().getString("nametag.clan", "&7Sin clan"));
        return LEGACY.deserialize(processed);
    }
}
