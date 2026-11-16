package mc.bastion.nametangs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
import org.bukkit.GameMode;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

public final class BastionMCNameTangs extends JavaPlugin implements Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final Map<Team, Team.OptionStatus> previousVisibility = new IdentityHashMap<>();
    private final Set<Team> createdTeams = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final String LABEL_TAG = "bastionmc_nametangs_label";
    private LuckPerms luckPerms;
    private Economy economy;
    private BukkitTask updater;
    private long cycles = 0;
    private final Set<UUID> dungeonIntent = new HashSet<>();
    private final Set<UUID> dungeonMode = new HashSet<>();
    private final Set<UUID> awaitingDungeonExit = new HashSet<>();
    private final Map<UUID, Long> detachedUntil = new HashMap<>();
    private final Map<UUID, Integer> travelGeneration = new HashMap<>();

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
        detachedUntil.clear();
        travelGeneration.clear();
        dungeonIntent.clear();
        dungeonMode.clear();
        awaitingDungeonExit.clear();
        restoreTeams();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) createLabel(event.getPlayer());
        }, 2L);
    }

    private boolean showLabel(Player player) {
        if (!player.isOnline() || player.isDead()) return false;
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.CREATIVE;
    }

    @EventHandler public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.isCancelled()) return;
        GameMode next = event.getNewGameMode();
        if (next == GameMode.ADVENTURE || next == GameMode.SPECTATOR) {
            removeLabel(event.getPlayer().getUniqueId());
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (showLabel(event.getPlayer())) createLabel(event.getPlayer());
            }, 2L);
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        removeLabel(event.getEntity().getUniqueId());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        removeLabel(id);
        detachedUntil.remove(id);
        travelGeneration.remove(id);
        dungeonIntent.remove(id);
        dungeonMode.remove(id);
        awaitingDungeonExit.remove(id);
    }

    // Desmontar ANTES de ejecutar /dungeon go; el evento de teleport puede llegar
    // demasiado tarde si Paper rechaza viajar con pasajeros.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim().toLowerCase(Locale.ROOT);
        String[] parts = raw.split("\\s+");
        if (parts.length == 0) return;
        String cmd = parts[0];
        boolean dungeon = (cmd.equals("/dungeon") || cmd.endsWith(":dungeon"))
                && parts.length > 1 && parts[1].equals("go");
        boolean teleport = dungeon || Arrays.asList("/tp", "/teleport", "/tpa", "/tpaccept", "/home", "/back", "/warp", "/world", "/mvtp", "/rtp", "/wild").contains(cmd)
                || cmd.endsWith(":tp") || cmd.endsWith(":teleport") || cmd.endsWith(":world");
        if (!teleport) return;
        UUID id = event.getPlayer().getUniqueId();
        if (dungeon) dungeonIntent.add(id);
        detachForTravel(event.getPlayer(), dungeon ? 20L * 30L : 20L * 3L);
    }

    // RTP por GUI: el PlayerTeleportEvent puede llegar demasiado tarde cuando
    // el jugador tiene un TextDisplay como pasajero. Desmontar ANTES del click.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRtpMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("BastionMCRTP")) return;
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.replaceAll("\\s+", "").equalsIgnoreCase("BASTION|RTP")) return;
        if (event.getCurrentItem() == null) return;
        Material type = event.getCurrentItem().getType();
        if (type != Material.GRASS_BLOCK && type != Material.NETHERRACK && type != Material.END_STONE) return;
        // La busqueda puede tardar varios segundos. Evita volver a montar
        // el holograma mientras el RTP busca y ejecuta teleportAsync.
        detachForTravel(player, 20L * 30L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        detachForTravel(event.getPlayer(), dungeonIntent.contains(event.getPlayer().getUniqueId()) ? 20L * 30L : 20L * 3L);
    }

    // Restaurar el nametag tras un teletransporte, incluso dentro del mismo mundo.
    // MONITOR se ejecuta despues de las comprobaciones de otros plugins.
    // La generacion impide que una tarea antigua interrumpa un viaje posterior.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportCompleted(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        int generation = travelGeneration.getOrDefault(id, 0);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || player.isDead()) return;
            if (travelGeneration.getOrDefault(id, 0) != generation) return;
            if (awaitingDungeonExit.contains(id) || dungeonIntent.contains(id)) return;
            detachedUntil.remove(id);
            if (showLabel(player) && getConfig().getBoolean("nametag.enabled", true)) createLabel(player);
        }, 20L);
    }

    /** Called by BastionDungeons BEFORE its return teleport. No dependency is needed. */
    public void prepareDungeonReturn(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        awaitingDungeonExit.add(id);
        removeLabel(id);
        detachedUntil.put(id, (long) Bukkit.getCurrentTick() + 20L * 30L);
    }

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        // Detectar el mundo de la dungeon para CADA participante, no solo quien uso /dungeon go.
        if (player.getWorld().getName().startsWith("bd_")) dungeonMode.add(id);
        else dungeonMode.remove(id);
        dungeonIntent.remove(id);
        awaitingDungeonExit.remove(id);
        detachedUntil.remove(id);
        travelGeneration.merge(id, 1, Integer::sum);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) createLabel(player);
        }, 2L);
    }

    /** Public integration hook for BastionMCRTP. Must be called on the server thread. */
    public void prepareRtpTravel(Player player) {
        if (player == null || !player.isOnline()) return;
        detachForTravel(player, 20L * 60L);
    }

    private void detachForTravel(Player player, long ticks) {
        UUID id = player.getUniqueId();
        removeLabel(id);
        travelGeneration.merge(id, 1, Integer::sum);
        detachedUntil.put(id, Math.max(detachedUntil.getOrDefault(id, 0L), Bukkit.getCurrentTick() + ticks));
    }

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
        long period = Math.max(1L, getConfig().getLong("update-ticks", 1L));
        updater = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (++cycles % Math.max(1L, 100L / period) == 0) removeOrphanLabels();
            if (economy == null && Bukkit.getPluginManager().isPluginEnabled("Vault")) refreshEconomy();
            boolean enabled = getConfig().getBoolean("nametag.enabled", true);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!showLabel(player)) {
                    removeLabel(player.getUniqueId());
                    continue;
                }
                // Detectar tambien jugadores que ya estaban en el mundo al activar el plugin.
                if (player.getWorld().getName().startsWith("bd_")) dungeonMode.add(player.getUniqueId());
                else dungeonMode.remove(player.getUniqueId());
                if (awaitingDungeonExit.contains(player.getUniqueId())) continue;
                Long until = detachedUntil.get(player.getUniqueId());
                if (until != null && Bukkit.getCurrentTick() < until) continue;
                if (until != null) {
                    detachedUntil.remove(player.getUniqueId());
                    dungeonIntent.remove(player.getUniqueId());
                }
                if (enabled) {
                    TextDisplay label = labels.get(player.getUniqueId());
                    if (label == null || !label.isValid() || !label.getWorld().equals(player.getWorld())) {
                        createLabel(player);
                        label = labels.get(player.getUniqueId());
                    }
                    if (label != null) {
                        if (!player.getPassengers().contains(label)) {
                            removeLabel(player.getUniqueId());
                            createLabel(player);
                            label = labels.get(player.getUniqueId());
                        }
                        if (label != null) {
                            boolean dungeon = getConfig().getBoolean("dungeon.enabled", true) && dungeonMode.contains(player.getUniqueId());
                            label.text(render(player, dungeon ? String.join("\n",
                                getConfig().getString("dungeon.line-one", "%prefix% &f%player%"),
                                getConfig().getString("dungeon.line-two", "&c❤ %health%/%max_health%"),
                                getConfig().getString("dungeon.line-three", "&6⚔ ESTÁS EN DUNGEON")) : String.join("\n",
                                getConfig().getString("nametag.line-one", "%prefix% &f%player%"),
                                getConfig().getString("nametag.line-two", "%clan% &8| &aDinero: &f$%money%"),
                                getConfig().getString("nametag.line-three", "&c❤ %health%/%max_health%"))));
                        }
                        // Aplicar la altura real del TextDisplay montado, tambien tras cambios de config.
                        Transformation current = label.getTransformation();
                        float desiredY = (float) getConfig().getDouble("nametag.passenger-height-offset", -0.90);
                        if (Math.abs(current.getTranslation().y - desiredY) > 0.001f) {
                            current.getTranslation().y = desiredY;
                            label.setTransformation(current);
                        }
                        label.setShadowed(getConfig().getBoolean("nametag.shadow", true));
                        label.setDefaultBackground(getConfig().getBoolean("nametag.background", false));
                    }
                } else removeLabel(player.getUniqueId());
                if (getConfig().getBoolean("self-actionbar.enabled", true))
                    player.sendActionBar(render(player, getConfig().getString("self-actionbar.text", "%prefix% &f%player% &8| &c❤ %health%/%max_health% &8| %clan%")));
            }
            if (enabled && getConfig().getBoolean("nametag.hide-vanilla", true)) hideVanillaNametags();
            else if (!previousVisibility.isEmpty() || !createdTeams.isEmpty()) restoreTeams();
        }, 1L, period);
    }

    private void createLabel(Player player) {
        removeLabel(player.getUniqueId());
        removeDuplicateLabelsFor(player);
        if (!showLabel(player) || !getConfig().getBoolean("nametag.enabled", true)) return;
        // Montaje directo: el holograma se desmonta antes de los viajes.
        Location position = player.getLocation().clone().add(0, player.getHeight() + getConfig().getDouble("nametag.height-offset", 0.25), 0);
        TextDisplay display = player.getWorld().spawn(position, TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.addScoreboardTag(LABEL_TAG);
            entity.addScoreboardTag(ownerTag(player.getUniqueId()));
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setLineWidth(250);
            entity.setViewRange(1.0f);
            entity.setTeleportDuration(1);
            entity.setInterpolationDuration(0);
            // Como pasajero, la posición inicial se reemplaza al montar.
            // La traslación del display SÍ permite elevar el texto sobre la cabeza.
            Transformation transform = entity.getTransformation();
            transform.getTranslation().set(0f, (float) getConfig().getDouble("nametag.passenger-height-offset", -0.90), 0f);
            entity.setTransformation(transform);
            entity.text(Component.empty());
        });
        if (!player.addPassenger(display)) {
            display.remove();
            getLogger().warning("No se pudo montar el nametag de " + player.getName());
            return;
        }
        labels.put(player.getUniqueId(), display);
    }


    // A label is unique per owner, even if an earlier plugin instance left it behind.
    private String ownerTag(UUID id) {
        return "bmnt_owner_" + id.toString().replace("-", "");
    }

    private void removeDuplicateLabelsFor(Player player) {
        String owner = ownerTag(player.getUniqueId());
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getScoreboardTags().contains(owner)) display.remove();
            }
        }
    }

    private void removeLabel(UUID id) {
        TextDisplay previous = labels.remove(id);
        if (previous != null && previous.isValid()) {
            if (previous.getVehicle() != null) previous.getVehicle().removePassenger(previous);
            previous.remove();
        }
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

    /** Migrate standard three-line layouts from versions 1.0.3-1.0.5.
     * Custom layouts are preserved unless they match the old standard layout.
     */
    private void migrateOldConfig() {
        String second = getConfig().getString("nametag.line-two", "");
        String third = getConfig().getString("nametag.line-three", "");
        String heart = "&c❤ %health%/%max_health%";
        String money = "%clan% &8| &aDinero: &f$%money%";
        boolean changed = false;
        if (second.equals(heart) && (third.equals(money) || third.equals("%clan%") || third.equals("&7Sin clan"))) {
            getConfig().set("nametag.line-two", money);
            getConfig().set("nametag.line-three", heart);
            changed = true;
        }
        if (changed) saveConfig();
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
        return MINI.deserialize(legacyToMiniMessage(processed));
    }
    // Convierte codigos tradicionales (&a, &l, &#RRGGBB) sin eliminar etiquetas
    // MiniMessage como <b>, <gradient:...> y <rainbow>.
    private static String legacyToMiniMessage(String input) {
        StringBuilder out = new StringBuilder();
        String colors = "0123456789abcdef";
        String[] names = {"black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue",
            "green", "aqua", "red", "light_purple", "yellow", "white"};
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                char next = Character.toLowerCase(input.charAt(i + 1));
                if (next == '#' && i + 7 < input.length()) {
                    String hex = input.substring(i + 2, i + 8);
                    if (hex.matches("(?i)[0-9a-f]{6}")) {
                        out.append("<color:#").append(hex).append('>');
                        i += 7;
                        continue;
                    }
                }
                int index = colors.indexOf(next);
                if (index >= 0) {
                    out.append("<color:").append(names[index]).append('>');
                    i++;
                    continue;
                }
                String tag = switch (next) {
                    case 'l' -> "<bold>";
                    case 'o' -> "<italic>";
                    case 'n' -> "<underlined>";
                    case 'm' -> "<strikethrough>";
                    case 'k' -> "<obfuscated>";
                    case 'r' -> "<reset>";
                    default -> null;
                };
                if (tag != null) {
                    out.append(tag);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

}
