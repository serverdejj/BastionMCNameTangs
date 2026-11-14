package mc.bastion.nametangs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

public final class BastionMCNameTangs extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final Map<Team, Team.OptionStatus> previousVisibility = new IdentityHashMap<>();
    private final Set<Team> createdTeams = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final String LABEL_TAG = "bastionmc_nametangs_label";
    private LuckPerms luckPerms;
    private Economy economy;
    private BukkitTask updater;
    private long cycles = 0;

    @Override public void onEnable() {
        saveDefaultConfig();
        migrateOldConfig();
        removeOrphanLabels();
        luckPerms = LuckPermsProvider.get();
        refreshEconomy();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("nametangs") != null) getCommand("nametangs").setExecutor((sender, command, alias, args) -> {
            if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                sender.sendMessage("§7Uso: §a/nametangs reload");
                return true;
            }
            if (!sender.hasPermission("bastionnametangs.admin")) {
                sender.sendMessage("§cNo tienes permiso.");
                return true;
            }
            reloadConfig();
            migrateOldConfig();
            removeOrphanLabels();
            refreshEconomy();
            restartUpdater();
            sender.sendMessage("§aBastionMCNameTangs recargado.");
            return true;
        });
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) createLabel(player);
        });
        restartUpdater();
    }

    @Override public void onDisable() {
        if (updater != null) updater.cancel();
        for (UUID id : new ArrayList<>(labels.keySet())) removeLabel(id);
        restoreTeams();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) createLabel(event.getPlayer());
        }, 2L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { removeLabel(event.getPlayer().getUniqueId()); }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) createLabel(player);
        }, 2L);
    }

    private void refreshEconomy() {
        economy = null;
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return;
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider != null) economy = provider.getProvider();
    }

    private void restartUpdater() {
        if (updater != null) updater.cancel();
        restoreTeams();
        long period = Math.max(2L, getConfig().getLong("update-ticks", 2L));
        updater = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (++cycles % Math.max(1L, 100L / period) == 0) removeOrphanLabels();
            if (economy == null && Bukkit.getPluginManager().isPluginEnabled("Vault")) refreshEconomy();
            boolean enabled = getConfig().getBoolean("nametag.enabled", true);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (enabled) {
                    TextDisplay label = labels.get(player.getUniqueId());
                    if (label == null || !label.isValid() || !label.getWorld().equals(player.getWorld()) || !player.getPassengers().contains(label)) {
                        createLabel(player);
                        label = labels.get(player.getUniqueId());
                    }
                    if (label != null) {
                        label.text(render(player, String.join("\n",
                            getConfig().getString("nametag.line-one", "%prefix% &f%player%"),
                            getConfig().getString("nametag.line-two", "&c❤ %health%/%max_health%"),
                            getConfig().getString("nametag.line-three", "%clan% &8| &aDinero: &f$%money%"))));
                        label.setShadowed(getConfig().getBoolean("nametag.shadow", true));
                        label.setDefaultBackground(getConfig().getBoolean("nametag.background", false));
                    }
                } else removeLabel(player.getUniqueId());
                if (getConfig().getBoolean("self-actionbar.enabled", true))
                    player.sendActionBar(render(player, getConfig().getString("self-actionbar.text", "%prefix% &f%player% &8| &c❤ %health%/%max_health% &8| %clan%")));
            }
            if (enabled && getConfig().getBoolean("nametag.hide-vanilla", true)) hideVanillaNametags();
            else if (!previousVisibility.isEmpty() || !createdTeams.isEmpty()) restoreTeams();
        }, 2L, period);
    }

    private void createLabel(Player player) {
        removeLabel(player.getUniqueId());
        if (!player.isOnline() || !getConfig().getBoolean("nametag.enabled", true)) return;
        // Spawn at the player and mount the display: the CLIENT moves it synchronously
        // with the player, without any server-side teleport / 1-second chase.
        Location position = player.getLocation();
        TextDisplay display = player.getWorld().spawn(position, TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.addScoreboardTag(LABEL_TAG);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setLineWidth(250);
            entity.setViewRange(1.0f);
            entity.setTeleportDuration(0);
            entity.setInterpolationDuration(0);
            // This is relative to the vanilla passenger mounting point (above head),
            // NOT the player's feet. A modest offset prevents excessive height.
            Transformation transform = entity.getTransformation();
            transform.getTranslation().set(0f, (float) getConfig().getDouble("nametag.height-offset", 0.25), 0f);
            entity.setTransformation(transform);
            entity.text(Component.empty());
        });
        if (!player.addPassenger(display)) {
            getLogger().warning("No se pudo montar el holograma de " + player.getName());
            display.remove();
            return;
        }
        labels.put(player.getUniqueId(), display);
    }

    private void removeLabel(UUID id) {
        TextDisplay previous = labels.remove(id);
        if (previous != null && previous.isValid()) previous.remove();
    }

    private void hideVanillaNametags() {
        // A scoreboard is client-side per viewer; cover each viewer's active board.
        // Existing teams are preserved, and their visibility setting restored on disable.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            for (Player subject : Bukkit.getOnlinePlayers()) {
                String entry = subject.getName();
                Team team = board.getEntryTeam(entry);
                if (team == null) {
                    String name = "bmnt_" + subject.getUniqueId().toString().substring(0, 8);
                    team = board.getTeam(name);
                    if (team == null) {
                        team = board.registerNewTeam(name);
                        createdTeams.add(team);
                    }
                    team.addEntry(entry);
                } else if (!createdTeams.contains(team)) {
                    previousVisibility.putIfAbsent(team, team.getOption(Team.Option.NAME_TAG_VISIBILITY));
                }
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
        }
    }

    private void restoreTeams() {
        for (Map.Entry<Team, Team.OptionStatus> entry : previousVisibility.entrySet()) {
            try { entry.getKey().setOption(Team.Option.NAME_TAG_VISIBILITY, entry.getValue()); }
            catch (IllegalStateException ignored) { }
        }
        previousVisibility.clear();
        for (Team team : createdTeams) {
            try { team.unregister(); } catch (IllegalStateException ignored) { }
        }
        createdTeams.clear();
    }

    /** Upgrade only the original clan-only line from the older versions. */
    private void migrateOldConfig() {
        String line = getConfig().getString("nametag.line-three", "");
        if (line.equals("%clan%") || line.equals("&7Sin clan") || line.equals("%clan% &7")) {
            getConfig().set("nametag.line-three", "%clan% &8| &aDinero: &f$%money%");
            saveConfig();
        }
    }

    /** Clean remnants from older versions, including entities without a marker.
     * This is intentionally limited to text displays using our unique three-line layout.
     */
    private void removeOrphanLabels() {
        Set<UUID> active = new HashSet<>();
        for (TextDisplay display : labels.values()) if (display.isValid()) active.add(display.getUniqueId());
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (active.contains(display.getUniqueId())) continue;
                boolean ours = display.getScoreboardTags().contains(LABEL_TAG);
                if (!ours) {
                    Component component = display.text();
                    String plain = component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
                    // Old versions always used exactly 3 lines with a heart and "Sin clan".
                    ours = plain.contains("\n") && plain.contains("❤") && plain.contains("Sin clan")
                           && (plain.contains("20/") || plain.matches("(?s).*\\d+/\\d+.*"));
                }
                if (ours) { display.remove(); removed++; }
            }
        }
        if (removed > 0) getLogger().info("Se eliminaron " + removed + " hologramas antiguos duplicados.");
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
        String balance = getConfig().getString("economy.unavailable", "N/D");
        if (economy != null) {
            double amount = economy.getBalance(player);
            if (Double.isFinite(amount)) {
                DecimalFormat numberFormat = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
                balance = numberFormat.format(amount);
            }
        }
        String processed = format
            .replace("%prefix%", prefix)
            .replace("%player%", player.getName())
            .replace("%health%", Integer.toString((int)Math.ceil(health)))
            .replace("%max_health%", Integer.toString((int)Math.ceil(maxHealth)))
            .replace("%clan%", getConfig().getString("nametag.clan", "&7Sin clan"))
            .replace("%money%", balance);
        return LEGACY.deserialize(processed);
    }
}
