package org.tuplugin.plataforma;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlatformPlugin extends JavaPlugin implements Listener {

    // Puntos de selección para cada jugador.
    private final Map<UUID, Location> firstPoint = new HashMap<>();
    private final Map<UUID, Location> secondPoint = new HashMap<>();
    // Múltiples plataformas por jugador, identificadas por nombre.
    private final Map<UUID, Map<String, Platform>> platforms = new HashMap<>();
    private final List<Platform> activePlatforms = new ArrayList<>();
    private Material platformMaterial = Material.LIME_CONCRETE;
    private ItemStack selectionTool;

    // Valores globales para partículas (por defecto para nuevas plataformas)
    public boolean globalParticleArmorEnabled;
    public Particle globalParticleArmorParticle;
    public int globalParticleArmorCount;
    public double globalParticleArmorOffsetX;
    public double globalParticleArmorOffsetY;
    public double globalParticleArmorOffsetZ;
    public double globalParticleArmorSpeed;

    // Mapa para almacenar los mensajes del idioma cargado.
    private final Map<String, String> langMessages = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("platform").setExecutor(new PlatformCommand());
        getCommand("platform").setTabCompleter(new PlatformTabCompleter());

        saveDefaultConfig();
        loadConfigValues();
        loadLanguage();
        createSelectionTool();

        getLogger().info(getMessage("pluginEnabled", "&aPlatformPlugin has been enabled!"));
    }

    @Override
    public void onDisable() {
        for (Map<String, Platform> playerPlatforms : platforms.values()) {
            for (Platform p : playerPlatforms.values()) {
                p.deactivate();
            }
        }
        activePlatforms.clear();
        getLogger().info(getMessage("pluginDisabled", "&cPlatformPlugin has been disabled!"));
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();

        // Valores básicos y de partículas
        config.addDefault("platformMaterial", "LIME_CONCRETE");
        config.addDefault("selectionTool", "BLAZE_ROD");
        config.addDefault("defaultTimerDuration", 60);
        config.addDefault("defaultCooldownTime", 30);
        config.addDefault("defaultPlayerLimit", 0);
        config.addDefault("defaultTeleportDestination", "");
        config.addDefault("language", "es");

        config.addDefault("particleArmor.enabled", true);
        config.addDefault("particleArmor.particle", "SPELL_WITCH");
        config.addDefault("particleArmor.count", 10);
        config.addDefault("particleArmor.offsetX", 0.5);
        config.addDefault("particleArmor.offsetY", 1.0);
        config.addDefault("particleArmor.offsetZ", 0.5);
        config.addDefault("particleArmor.speed", 0.1);

        // Offsets para hologramas
        config.addDefault("holograms.counter.offsetX", 0.0);
        config.addDefault("holograms.counter.offsetY", 2.0);
        config.addDefault("holograms.counter.offsetZ", 0.0);
        config.addDefault("holograms.timer.offsetX", 0.0);
        config.addDefault("holograms.timer.offsetY", 0.5);
        config.addDefault("holograms.timer.offsetZ", 0.0);
        config.addDefault("holograms.cooldown.offsetX", 0.0);
        config.addDefault("holograms.cooldown.offsetY", 3.0);
        config.addDefault("holograms.cooldown.offsetZ", 0.0);

        config.options().copyDefaults(true);
        saveConfig();

        try {
            platformMaterial = Material.valueOf(config.getString("platformMaterial"));
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid material in config.yml, using LIME_CONCRETE as default");
            platformMaterial = Material.LIME_CONCRETE;
        }
        globalParticleArmorEnabled = config.getBoolean("particleArmor.enabled", true);
        try {
            globalParticleArmorParticle = Particle.valueOf(config.getString("particleArmor.particle", "SPELL_WITCH"));
        } catch (Exception e) {
            globalParticleArmorParticle = Particle.SPELL_WITCH;
        }
        globalParticleArmorCount = config.getInt("particleArmor.count", 10);
        globalParticleArmorOffsetX = config.getDouble("particleArmor.offsetX", 0.5);
        globalParticleArmorOffsetY = config.getDouble("particleArmor.offsetY", 1.0);
        globalParticleArmorOffsetZ = config.getDouble("particleArmor.offsetZ", 0.5);
        globalParticleArmorSpeed = config.getDouble("particleArmor.speed", 0.1);
    }

    // Carga el archivo de idioma según la opción "language" en config.yml.
    private void loadLanguage() {
        String lang = getConfig().getString("language", "es");
        File langFile = new File(getDataFolder(), "lang_" + lang + ".yml");
        if (!langFile.exists()) {
            saveResource("lang_" + lang + ".yml", false);
        }
        YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
        langMessages.clear();
        for (String key : langConfig.getKeys(false)) {
            langMessages.put(key, ChatColor.translateAlternateColorCodes('&', langConfig.getString(key)));
        }
    }

    // Obtiene un mensaje por clave desde el archivo de idioma cargado.
    // Si la clave no existe, se utiliza el mensaje por defecto (sin comandos en su interior).
    private String getMessage(String key, String defaultMsg) {
        return langMessages.getOrDefault(key, defaultMsg);
    }

    // Crea la herramienta de selección (no se modifica en el YAML ya que es parte del funcionamiento del plugin)
    private void createSelectionTool() {
        Material toolMaterial = Material.valueOf(getConfig().getString("selectionTool", "BLAZE_ROD"));
        selectionTool = new ItemStack(toolMaterial);
        ItemMeta meta = selectionTool.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Platform Selection Tool");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.YELLOW + "Left click: First point");
            lore.add(ChatColor.YELLOW + "Right click: Second point");
            meta.setLore(lore);
            selectionTool.setItemMeta(meta);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("platform.admin")) return;
        if (event.getItem() == null || !event.getItem().isSimilar(selectionTool)) return;
        if (event.getClickedBlock() == null) return;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            firstPoint.put(player.getUniqueId(), event.getClickedBlock().getLocation());
            String msg = getMessage("firstPoint", "&aFirst point selected at %location%")
                    .replace("%location%", formatLocation(event.getClickedBlock().getLocation()));
            player.sendMessage(msg);
            event.setCancelled(true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            secondPoint.put(player.getUniqueId(), event.getClickedBlock().getLocation());
            String msg = getMessage("secondPoint", "&aSecond point selected at %location%")
                    .replace("%location%", formatLocation(event.getClickedBlock().getLocation()));
            player.sendMessage(msg);
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        Location from = event.getFrom();
        for (Platform platform : activePlatforms) {
            if (platform.isActive() && platform.isInCooldown()) continue;

            // Se chequea si el jugador ha saltado dentro de la plataforma y sigue dentro
            boolean wasOnTop = platform.isOnTopOfPlatform(from);
            boolean isOnTop = platform.isOnTopOfPlatform(to);
            if (wasOnTop && !isOnTop) {
                // Si el jugador sale de la plataforma (no por salto), lo quitamos
                platform.removePlayer(player);
                player.sendMessage(ChatColor.RED + getMessage("leftPlatform", "You have left the platform."));
            } else if (!wasOnTop && isOnTop) {
                // Si entra en la plataforma, lo agregamos
                platform.addPlayer(player);
                player.sendMessage(ChatColor.GREEN + getMessage("enteredPlatform", "You have entered the platform!"));
                if (platform.getPlayerCount() == 1) {
                    platform.startTimer();
                }
            }
        }
    }


    private String formatLocation(Location loc) {
        return loc.getWorld().getName() + " (" + loc.getBlockX() + ", " +
                loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }

    // Método auxiliar para convertir una cadena tipo "1h30m20s" a segundos.
    private int parseCooldown(String input) {
        int totalSeconds = 0;
        input = input.toLowerCase();
        Pattern pattern = Pattern.compile("(\\d+)([hms])");
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()){
            int value = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            switch(unit) {
                case "h":
                    totalSeconds += value * 3600;
                    break;
                case "m":
                    totalSeconds += value * 60;
                    break;
                case "s":
                    totalSeconds += value;
                    break;
            }
        }
        return totalSeconds;
    }

    // ====================================================
    // Clase interna: Plataforma
    // ====================================================
    public class Platform {
        private final Location pos1;
        private final Location pos2;
        private final UUID owner;
        private final List<Block> originalBlocks = new ArrayList<>();
        private final Map<Block, Material> originalTypes = new HashMap<>();
        private final Set<UUID> playersInside = new HashSet<>();
        private ArmorStand counterHologram;
        private ArmorStand timerHologram;
        private ArmorStand cooldownHologram;
        private BukkitTask timerTask;
        private boolean isActive = false;
        private boolean inCooldown = false;
        private String teleportDestination = "";
        private int playerLimit = getConfig().getInt("defaultPlayerLimit", 0);
        private int cooldownTime = getConfig().getInt("defaultCooldownTime", 30);
        private int initialTimerDuration = getConfig().getInt("defaultTimerDuration", 60);
        private int remainingTime = initialTimerDuration;

        // Parámetros de partículas específicos para esta plataforma.
        private boolean particleArmorEnabledInstance = globalParticleArmorEnabled;
        private Particle particleArmorParticleInstance = globalParticleArmorParticle;
        private int particleArmorCountInstance = globalParticleArmorCount;
        private double particleArmorOffsetXInstance = globalParticleArmorOffsetX;
        private double particleArmorOffsetYInstance = globalParticleArmorOffsetY;
        private double particleArmorOffsetZInstance = globalParticleArmorOffsetZ;
        private double particleArmorSpeedInstance = globalParticleArmorSpeed;

        // Offsets de hologramas (por defecto se toman del config al activar)
        private double counterOffsetX, counterOffsetY, counterOffsetZ;
        private double timerOffsetX, timerOffsetY, timerOffsetZ;
        private double cooldownOffsetX, cooldownOffsetY, cooldownOffsetZ;

        // Mapa para gestionar el efecto de partículas por jugador.
        private final Map<UUID, BukkitTask> particleArmorTasks = new HashMap<>();

        public Platform(Location pos1, Location pos2, UUID owner) {
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.owner = owner;
        }

        public void activate() {
            if (isActive) return;
            World world = pos1.getWorld();
            int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
            int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
            int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
            int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
            int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
            int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        originalBlocks.add(block);
                        originalTypes.put(block, block.getType());
                        block.setType(platformMaterial);
                    }
                }
            }
            // Inicializar offsets desde el config
            this.counterOffsetX = getConfig().getDouble("holograms.counter.offsetX", 0.0);
            this.counterOffsetY = getConfig().getDouble("holograms.counter.offsetY", 2.0);
            this.counterOffsetZ = getConfig().getDouble("holograms.counter.offsetZ", 0.0);
            this.timerOffsetX = getConfig().getDouble("holograms.timer.offsetX", 0.0);
            this.timerOffsetY = getConfig().getDouble("holograms.timer.offsetY", 0.5);
            this.timerOffsetZ = getConfig().getDouble("holograms.timer.offsetZ", 0.0);
            this.cooldownOffsetX = getConfig().getDouble("holograms.cooldown.offsetX", 0.0);
            this.cooldownOffsetY = getConfig().getDouble("holograms.cooldown.offsetY", 3.0);
            this.cooldownOffsetZ = getConfig().getDouble("holograms.cooldown.offsetZ", 0.0);

            // Crear holograma del contador
            Location counterLoc = new Location(world, (minX + maxX) / 2.0 + counterOffsetX, maxY + counterOffsetY, (minZ + maxZ) / 2.0 + counterOffsetZ);
            counterHologram = (ArmorStand) world.spawnEntity(counterLoc, EntityType.ARMOR_STAND);
            counterHologram.setCustomName(ChatColor.GREEN + "Players: 0");
            counterHologram.setCustomNameVisible(true);
            counterHologram.setGravity(false);
            counterHologram.setVisible(false);

            // Crear holograma del temporizador
            Location timerLoc = counterLoc.clone().add(timerOffsetX, timerOffsetY, timerOffsetZ);
            timerHologram = (ArmorStand) world.spawnEntity(timerLoc, EntityType.ARMOR_STAND);
            timerHologram.setCustomName(ChatColor.YELLOW + "Time: " + remainingTime + "s");
            timerHologram.setCustomNameVisible(true);
            timerHologram.setGravity(false);
            timerHologram.setVisible(false);

            isActive = true;
            activePlatforms.add(this);
        }

        public void deactivate() {
            if (!isActive) return;
            restoreBlocks();
            removeHolograms();
            isActive = false;
            activePlatforms.remove(this);
            if (timerTask != null) {
                timerTask.cancel();
                timerTask = null;
            }
            for (BukkitTask task : particleArmorTasks.values()) {
                task.cancel();
            }
            particleArmorTasks.clear();
        }

        public void restoreBlocks() {
            for (Block block : originalBlocks) {
                block.setType(originalTypes.getOrDefault(block, Material.AIR));
            }
            originalBlocks.clear();
            originalTypes.clear();
        }

        public void removeHolograms() {
            if (counterHologram != null) counterHologram.remove();
            if (timerHologram != null) timerHologram.remove();
            if (cooldownHologram != null) cooldownHologram.remove();
        }

        public int getCooldownTime() {
            return cooldownTime;
        }

        public int getRemainingTime() {
            return remainingTime;
        }

        public String getTeleportDestination() {
            return teleportDestination;
        }

        public boolean isOnTopOfPlatform(Location loc) {
            if (!loc.getWorld().equals(pos1.getWorld())) return false;
            int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
            int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
            int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
            int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
            if (loc.getX() < minX || loc.getX() > maxX + 1 ||
                    loc.getZ() < minZ || loc.getZ() > maxZ + 1) {
                return false;
            }
            Block blockBelow = loc.clone().subtract(0, 1, 0).getBlock();
            return blockBelow.getType() == platformMaterial;
        }

        public void addPlayer(Player player) {
            if (playerLimit > 0 && playersInside.size() >= playerLimit) {
                player.sendMessage(getMessage("platformFull", "&cThe platform is full!"));
                Location teleportLoc = player.getLocation().clone();
                if (teleportLoc.getBlockX() <= pos1.getBlockX()) {
                    teleportLoc.setX(teleportLoc.getX() - 1);
                } else if (teleportLoc.getBlockX() >= pos2.getBlockX()) {
                    teleportLoc.setX(teleportLoc.getX() + 1);
                } else if (teleportLoc.getBlockZ() <= pos1.getBlockZ()) {
                    teleportLoc.setZ(teleportLoc.getZ() - 1);
                } else {
                    teleportLoc.setZ(teleportLoc.getZ() + 1);
                }
                player.teleport(teleportLoc);
                return;
            }
            playersInside.add(player.getUniqueId());
            updateCounterHologram();
            if (particleArmorEnabledInstance && !particleArmorTasks.containsKey(player.getUniqueId())) {
                BukkitTask task = new BukkitRunnable() {
                    @Override
                    public void run() {
                        Player p = Bukkit.getPlayer(player.getUniqueId());
                        if (p == null || !p.isOnline()) {
                            this.cancel();
                            return;
                        }
                        p.getWorld().spawnParticle(
                                particleArmorParticleInstance,
                                p.getLocation().add(0, 1, 0),
                                particleArmorCountInstance,
                                particleArmorOffsetXInstance,
                                particleArmorOffsetYInstance,
                                particleArmorOffsetZInstance,
                                particleArmorSpeedInstance
                        );
                    }
                }.runTaskTimer(PlatformPlugin.this, 0L, 10L);
                particleArmorTasks.put(player.getUniqueId(), task);
            }
        }

        public void removePlayer(Player player) {
            playersInside.remove(player.getUniqueId());
            updateCounterHologram();
            if (particleArmorTasks.containsKey(player.getUniqueId())) {
                particleArmorTasks.get(player.getUniqueId()).cancel();
                particleArmorTasks.remove(player.getUniqueId());
            }
        }

        public int getPlayerCount() {
            return playersInside.size();
        }

        public void updateCounterHologram() {
            if (counterHologram != null) {
                counterHologram.setCustomName(ChatColor.GREEN + "Players: " + getPlayerCount() +
                        (playerLimit > 0 ? "/" + playerLimit : ""));
            }
        }

        // El temporizador decrementa remainingTime; cuando llega a 0 se teletransporta y se inicia el cooldown.
        public void startTimer() {
            if (timerTask != null) return;
            timerTask = new BukkitRunnable() {
                @Override
                public void run() {
                    remainingTime--;
                    if (timerHologram != null) {
                        timerHologram.setCustomName(ChatColor.YELLOW + "Time: " + remainingTime + "s");
                    }
                    if (remainingTime <= 0) {
                        teleportPlayers();
                        startCooldown();
                        timerTask = null;
                        this.cancel();
                    }
                }
            }.runTaskTimer(PlatformPlugin.this, 20L, 20L);
        }

        // Teletransporta a los jugadores y remueve el efecto de partículas.
        public void teleportPlayers() {
            if (teleportDestination.isEmpty()) return;
            for (UUID uuid : playersInside) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    if (particleArmorTasks.containsKey(uuid)) {
                        particleArmorTasks.get(uuid).cancel();
                        particleArmorTasks.remove(uuid);
                    }
                    String command = "mvtp " + player.getName() + " " + teleportDestination;
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
            }
            playersInside.clear();
            updateCounterHologram();
        }

        // Al finalizar el cooldown se reinicia remainingTime y se vuelve a iniciar el temporizador.
        public void startCooldown() {
            inCooldown = true;
            if (cooldownHologram == null) {
                int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
                int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
                int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
                int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
                World world = pos1.getWorld();
                Location cooldownLoc = new Location(world, (minX + maxX) / 2.0 + cooldownOffsetX, Math.max(pos1.getBlockY(), pos2.getBlockY()) + cooldownOffsetY, (minZ + maxZ) / 2.0 + cooldownOffsetZ);
                cooldownHologram = (ArmorStand) world.spawnEntity(cooldownLoc, EntityType.ARMOR_STAND);
                cooldownHologram.setGravity(false);
                cooldownHologram.setVisible(false);
            }
            cooldownHologram.setCustomNameVisible(true);
            new BukkitRunnable() {
                int remainingCooldown = cooldownTime;
                @Override
                public void run() {
                    if (remainingCooldown <= 0) {
                        inCooldown = false;
                        cooldownHologram.setCustomNameVisible(false);
                        remainingTime = initialTimerDuration;
                        startTimer();
                        this.cancel();
                        return;
                    }
                    cooldownHologram.setCustomName(ChatColor.RED + "Cooldown: " + remainingCooldown + "s");
                    remainingCooldown--;
                }
            }.runTaskTimer(PlatformPlugin.this, 0L, 20L);
        }

        public boolean isActive() {
            return isActive;
        }

        public boolean isInCooldown() {
            return inCooldown;
        }

        public void setPlayerLimit(int limit) {
            this.playerLimit = limit;
            updateCounterHologram();
        }

        public void setTeleportDestination(String destination) {
            this.teleportDestination = destination;
        }

        public void setCooldownTime(int seconds) {
            this.cooldownTime = seconds;
        }

        // Setter para modificar el tiempo de la plataforma.
        public void setTimerDuration(int duration) {
            this.initialTimerDuration = duration;
            this.remainingTime = duration;
            if (timerHologram != null) {
                timerHologram.setCustomName(ChatColor.YELLOW + "Time: " + remainingTime + "s");
            }
        }

        // SETTERS para actualizar dinámicamente la posición de los hologramas.
        public void setCounterOffset(double x, double y, double z) {
            this.counterOffsetX = x;
            this.counterOffsetY = y;
            this.counterOffsetZ = z;
            if (counterHologram != null) {
                int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
                int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
                int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
                int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
                int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
                World world = pos1.getWorld();
                Location newLoc = new Location(world, (minX + maxX) / 2.0 + counterOffsetX, maxY + counterOffsetY, (minZ + maxZ) / 2.0 + counterOffsetZ);
                counterHologram.teleport(newLoc);
                // Actualizar también el timer, que es relativo al counter.
                if (timerHologram != null) {
                    Location timerLoc = newLoc.clone().add(timerOffsetX, timerOffsetY, timerOffsetZ);
                    timerHologram.teleport(timerLoc);
                }
            }
        }

        public void setTimerOffset(double x, double y, double z) {
            this.timerOffsetX = x;
            this.timerOffsetY = y;
            this.timerOffsetZ = z;
            if (counterHologram != null && timerHologram != null) {
                int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
                int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
                int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
                int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
                int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
                World world = pos1.getWorld();
                Location baseLoc = new Location(world, (minX + maxX) / 2.0 + counterOffsetX, maxY + counterOffsetY, (minZ + maxZ) / 2.0 + counterOffsetZ);
                Location newTimerLoc = baseLoc.clone().add(timerOffsetX, timerOffsetY, timerOffsetZ);
                timerHologram.teleport(newTimerLoc);
            }
        }

        public void setCooldownOffset(double x, double y, double z) {
            this.cooldownOffsetX = x;
            this.cooldownOffsetY = y;
            this.cooldownOffsetZ = z;
            if (cooldownHologram != null) {
                int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
                int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
                int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
                int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
                int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
                World world = pos1.getWorld();
                Location newLoc = new Location(world, (minX + maxX) / 2.0 + cooldownOffsetX, maxY + cooldownOffsetY, (minZ + maxZ) / 2.0 + cooldownOffsetZ);
                cooldownHologram.teleport(newLoc);
            }
        }

        // SETTERS para los parámetros de partículas.
        public void setParticleArmorEnabled(boolean enabled) {
            this.particleArmorEnabledInstance = enabled;
        }

        public void setParticleArmorParticle(Particle particle) {
            this.particleArmorParticleInstance = particle;
        }

        public void setParticleArmorCount(int count) {
            this.particleArmorCountInstance = count;
        }

        public void setParticleArmorOffsetX(double offsetX) {
            this.particleArmorOffsetXInstance = offsetX;
        }

        public void setParticleArmorOffsetY(double offsetY) {
            this.particleArmorOffsetYInstance = offsetY;
        }

        public void setParticleArmorOffsetZ(double offsetZ) {
            this.particleArmorOffsetZInstance = offsetZ;
        }

        public void setParticleArmorSpeed(double speed) {
            this.particleArmorSpeedInstance = speed;
        }
    }

    // ====================================================
    // Comando para gestionar plataformas.
    // ====================================================
    private class PlatformCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be executed by players.");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("platform.admin")) {
                player.sendMessage(getMessage("noPermission", "&cYou do not have permission to use this command."));
                return true;
            }
            if (args.length == 0) {
                sendHelp(player);
                return true;
            }
            UUID playerId = player.getUniqueId();
            Map<String, Platform> playerPlatforms = platforms.getOrDefault(playerId, new HashMap<>());
            platforms.putIfAbsent(playerId, playerPlatforms);
            switch (args[0].toLowerCase()) {
                case "reload":
                    reloadConfig();
                    loadConfigValues();
                    loadLanguage();
                    player.sendMessage(ChatColor.GREEN + getMessage("pluginReloaded", "&aPlugin reloaded!"));
                    return true;
                case "hologram":
                    if (args.length < 6) {
                        player.sendMessage(ChatColor.RED + "Usage: /platform hologram <type> <offsetX> <offsetY> <offsetZ> <name>");
                        return true;
                    }
                    String type = args[1].toLowerCase();
                    double offX, offY, offZ;
                    try {
                        offX = Double.parseDouble(args[2]);
                        offY = Double.parseDouble(args[3]);
                        offZ = Double.parseDouble(args[4]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Invalid offsets.");
                        return true;
                    }
                    String platName = args[5];
                    if (!playerPlatforms.containsKey(platName)) {
                        player.sendMessage(getMessage("platformNotFound", "&cYou do not have a platform with that name."));
                        return true;
                    }
                    Platform platform = playerPlatforms.get(platName);
                    if (type.equals("counter")) {
                        platform.setCounterOffset(offX, offY, offZ);
                        player.sendMessage(ChatColor.GREEN + "Counter hologram offset for '" + platName + "' updated.");
                    } else if (type.equals("timer")) {
                        platform.setTimerOffset(offX, offY, offZ);
                        player.sendMessage(ChatColor.GREEN + "Timer hologram offset for '" + platName + "' updated.");
                    } else if (type.equals("cooldown")) {
                        platform.setCooldownOffset(offX, offY, offZ);
                        player.sendMessage(ChatColor.GREEN + "Cooldown hologram offset for '" + platName + "' updated.");
                    } else {
                        player.sendMessage(ChatColor.RED + "Invalid type. Options: counter, timer, cooldown");
                    }
                    return true;
                case "tool":
                    player.getInventory().addItem(selectionTool);
                    player.sendMessage(getMessage("toolGiven", "&aSelection tool given!"));
                    return true;
                case "create":
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /platform create <name>");
                        return true;
                    }
                    String createName = args[1];
                    if (playerPlatforms.containsKey(createName)) {
                        player.sendMessage(getMessage("platformExists", "&cYou already have a platform with that name."));
                        return true;
                    }
                    if (!firstPoint.containsKey(playerId) || !secondPoint.containsKey(playerId)) {
                        player.sendMessage(getMessage("selectPoints", "&cFirst, select two points with the selection tool."));
                        return true;
                    }
                    Platform newPlatform = new Platform(firstPoint.get(playerId), secondPoint.get(playerId), playerId);
                    playerPlatforms.put(createName, newPlatform);
                    newPlatform.activate();
                    player.sendMessage(getMessage("platformCreated", "&aPlatform '%name%' created successfully!").replace("%name%", createName));
                    return true;
                case "remove":
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /platform remove <name>");
                        return true;
                    }
                    String removeName = args[1];
                    if (!playerPlatforms.containsKey(removeName)) {
                        player.sendMessage(getMessage("platformNotFound", "&cYou do not have a platform with that name."));
                        return true;
                    }
                    Platform platformToRemove = playerPlatforms.get(removeName);
                    platformToRemove.deactivate();
                    playerPlatforms.remove(removeName);
                    player.sendMessage(getMessage("platformRemoved", "&aPlatform '%name%' removed successfully!").replace("%name%", removeName));
                    return true;
                case "limit":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /platform limit <number> <name>");
                        return true;
                    }
                    String limitName = args[2];
                    if (!playerPlatforms.containsKey(limitName)) {
                        player.sendMessage(getMessage("platformNotFound", "&cYou do not have a platform with that name."));
                        return true;
                    }
                    try {
                        int limit = Integer.parseInt(args[1]);
                        playerPlatforms.get(limitName).setPlayerLimit(limit);
                        player.sendMessage(ChatColor.GREEN + "Player limit for '" + limitName + "' set to: " + limit);
                    } catch (NumberFormatException e) {
                        player.sendMessage(getMessage("invalidNumber", "&cPlease enter a valid number."));
                    }
                    return true;
                case "destination":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /platform destination <destination> <name>");
                        return true;
                    }
                    String destination = args[1];
                    String destName = args[2];
                    if (!playerPlatforms.containsKey(destName)) {
                        player.sendMessage(getMessage("platformNotFound", "&cYou do not have a platform with that name."));
                        return true;
                    }
                    playerPlatforms.get(destName).setTeleportDestination(destination);
                    player.sendMessage(ChatColor.GREEN + "Teleport destination for '" + destName + "' set to: " + destination);
                    return true;
                case "cooldown":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /platform cooldown <time> <name> (e.g., 1h30m20s)");
                        return true;
                    }
                    String cdString = args[1];
                    String cdName = args[2];
                    if (!playerPlatforms.containsKey(cdName)) {
                        player.sendMessage(getMessage("platformNotFound", "&cYou do not have a platform with that name."));
                        return true;
                    }
                    try {
                        int cooldown = parseCooldown(cdString);
                        playerPlatforms.get(cdName).setCooldownTime(cooldown);
                        player.sendMessage(ChatColor.GREEN + "Cooldown for '" + cdName + "' set to: " + cdString);
                    } catch (NumberFormatException e) {
                        player.sendMessage(getMessage("invalidNumber", "&cPlease enter a valid number."));
                    }
                    return true;
                case "particle":
                    if (args.length < 4) {
                        player.sendMessage(ChatColor.RED + "Usage: /platform particle <option> <value> <name>");
                        return true;
                    }

                    String option = args[1].toLowerCase();
                    String value = args[2];
                    platName = args[3];
                    if (!playerPlatforms.containsKey(platName)) {
                        player.sendMessage(getMessage("platformNotFound", "&cYou do not have a platform with that name."));
                        return true;
                    }
                    Platform plat = playerPlatforms.get(platName);
                    switch (option) {
                        case "enabled":
                            boolean enabled = Boolean.parseBoolean(value);
                            plat.setParticleArmorEnabled(enabled);
                            player.sendMessage(ChatColor.GREEN + "Particle armor 'enabled' for '" + platName + "' set to: " + enabled);
                            break;
                        case "particle":
                            try {
                                Particle particle = Particle.valueOf(value.toUpperCase());
                                plat.setParticleArmorParticle(particle);
                                player.sendMessage(ChatColor.GREEN + "Particle type for '" + platName + "' set to: " + particle);
                            } catch (Exception e) {
                                player.sendMessage(ChatColor.RED + "Invalid particle type.");
                            }
                            break;
                        case "count":
                            try {
                                int count = Integer.parseInt(value);
                                plat.setParticleArmorCount(count);
                                player.sendMessage(ChatColor.GREEN + "Particle count for '" + platName + "' set to: " + count);
                            } catch (NumberFormatException e) {
                                player.sendMessage(ChatColor.RED + "Invalid count.");
                            }
                            break;
                        case "offsetx":
                            try {
                                double offsetx = Double.parseDouble(value);
                                plat.setParticleArmorOffsetX(offsetx);
                                player.sendMessage(ChatColor.GREEN + "Particle offsetX for '" + platName + "' set to: " + offsetx);
                            } catch (NumberFormatException e) {
                                player.sendMessage(ChatColor.RED + "Invalid value for offsetX.");
                            }
                            break;
                        case "offsety":
                            try {
                                double offsety = Double.parseDouble(value);
                                plat.setParticleArmorOffsetY(offsety);
                                player.sendMessage(ChatColor.GREEN + "Particle offsetY for '" + platName + "' set to: " + offsety);
                            } catch (NumberFormatException e) {
                                player.sendMessage(ChatColor.RED + "Invalid value for offsetY.");
                            }
                            break;
                        case "offsetz":
                            try {
                                double offsetz = Double.parseDouble(value);
                                plat.setParticleArmorOffsetZ(offsetz);
                                player.sendMessage(ChatColor.GREEN + "Particle offsetZ for '" + platName + "' set to: " + offsetz);
                            } catch (NumberFormatException e) {
                                player.sendMessage(ChatColor.RED + "Invalid value for offsetZ.");
                            }
                            break;
                        case "speed":
                            try {
                                double speed = Double.parseDouble(value);
                                plat.setParticleArmorSpeed(speed);
                                player.sendMessage(ChatColor.GREEN + "Particle speed for '" + platName + "' set to: " + speed);
                            } catch (NumberFormatException e) {
                                player.sendMessage(ChatColor.RED + "Invalid value for speed.");
                            }
                            break;
                        default:
                            player.sendMessage(ChatColor.RED + "Option not recognized. Options: enabled, particle, count, offsetx, offsety, offsetz, speed");
                            break;
                    }
                    return true;
                case "timer":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /platform timer <seconds> <name>");
                        return true;
                    }
                    try {
                        int time = Integer.parseInt(args[1]);
                        String timerName = args[2];
                        if (!playerPlatforms.containsKey(timerName)) {
                            player.sendMessage(getMessage("platformNotFound", "&cYou do not have a platform with that name."));
                            return true;
                        }
                        playerPlatforms.get(timerName).setTimerDuration(time);
                        player.sendMessage(ChatColor.GREEN + "Platform timer for '" + timerName + "' set to: " + time + " seconds.");
                    } catch (NumberFormatException e) {
                        player.sendMessage(getMessage("invalidNumber", "&cPlease enter a valid number."));
                    }
                    return true;
                case "wiki":
                    openWiki(player);
                    return true;
                case "list":
                    if (playerPlatforms.isEmpty()) {
                        player.sendMessage(ChatColor.RED + "You don't have any platforms created.");
                        return true;
                    }
                    player.sendMessage(ChatColor.GOLD + "=== Your Platforms ===");
                    for (Map.Entry<String, Platform> entry : playerPlatforms.entrySet()) {
                        String nombre = entry.getKey();
                        Platform platEntry = entry.getValue();
                        player.sendMessage(ChatColor.YELLOW + nombre + ChatColor.WHITE +
                                " | Players: " + platEntry.getPlayerCount() +
                                " | Cooldown: " + platEntry.getCooldownTime() + "s" +
                                " | Timer: " + platEntry.getRemainingTime() + "s" +
                                " | Destination: " + (platEntry.getTeleportDestination().isEmpty() ? "Not set" : platEntry.getTeleportDestination())
                        );
                    }
                    return true;
                default:
                    sendHelp(player);
                    return true;
            }
        }

        // Los mensajes de ayuda con uso de comandos se muestran de forma fija (no se cargan desde YAML).
        private void sendHelp(Player player) {
            player.sendMessage(ChatColor.GOLD + "=== Platform Commands ===");
            player.sendMessage(ChatColor.YELLOW + "/platform reload" + ChatColor.WHITE + " - Reload configuration and language files");
            player.sendMessage(ChatColor.YELLOW + "/platform hologram <type> <offsetX> <offsetY> <offsetZ> <name>" + ChatColor.WHITE + " - Change hologram position (counter, timer or cooldown)");
            player.sendMessage(ChatColor.YELLOW + "/platform tool" + ChatColor.WHITE + " - Get the selection tool");
            player.sendMessage(ChatColor.YELLOW + "/platform create <name>" + ChatColor.WHITE + " - Create a platform with the selected points");
            player.sendMessage(ChatColor.YELLOW + "/platform remove <name>" + ChatColor.WHITE + " - Remove an active platform");
            player.sendMessage(ChatColor.YELLOW + "/platform limit <number> <name>" + ChatColor.WHITE + " - Set the player limit for a platform");
            player.sendMessage(ChatColor.YELLOW + "/platform destination <destination> <name>" + ChatColor.WHITE + " - Set teleport destination for a platform");
            player.sendMessage(ChatColor.YELLOW + "/platform cooldown <time> <name>" + ChatColor.WHITE + " - Set the cooldown (e.g., 1h30m20s)");
            player.sendMessage(ChatColor.YELLOW + "/platform particle <option> <value> <name>" + ChatColor.WHITE + " - Modify particle settings");
            player.sendMessage(ChatColor.YELLOW + "/platform timer <seconds> <name>" + ChatColor.WHITE + " - Modify the platform's timer");
            player.sendMessage(ChatColor.YELLOW + "/platform wiki" + ChatColor.WHITE + " - Open the plugin wiki");
            player.sendMessage(ChatColor.YELLOW + "/platform list" + ChatColor.WHITE + " - List your platforms");
        }
    }

    // ====================================================
    // Método para abrir la Wiki en forma de libro escrito.
    // ====================================================
    private void openWiki(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(ChatColor.DARK_PURPLE + "Plugin Wiki");
        meta.setAuthor(ChatColor.DARK_AQUA + "Jhosmel");
        meta.setPages(
                    ChatColor.DARK_AQUA + "Page 1:\n" +
                            ChatColor.DARK_GRAY + "• " + ChatColor.GRAY + "Create platforms using:\n" +
                            ChatColor.DARK_RED + "  /platform create <name>\n" +
                            ChatColor.DARK_GRAY + "• Use the selection tool to select two points.",

                    ChatColor.DARK_BLUE + "Page 2:\n" +
                            ChatColor.DARK_GRAY + "• " + ChatColor.GRAY + "Configure your platforms:\n" +
                            ChatColor.DARK_RED + "  /platform limit <number> <name>\n" +
                            ChatColor.DARK_RED + "  /platform destination <destination> <name>",

                    ChatColor.DARK_PURPLE + "Page 3:\n" +
                            ChatColor.DARK_GRAY + "• " + ChatColor.GRAY + "Timer and cooldown:\n" +
                            ChatColor.DARK_RED + "  /platform cooldown <time> <name>\n" +
                            ChatColor.DARK_GRAY + "  Example: 1h30m20s",

                    ChatColor.DARK_GREEN + "Page 4:\n" +
                            ChatColor.DARK_GRAY + "• " + ChatColor.GRAY + "Particle effects:\n" +
                            ChatColor.DARK_RED + "  /platform particle <option> <value> <name>\n" +
                            ChatColor.DARK_GRAY + "  Options: enabled, particle, count, offsetx, offsety, offsetz, speed",

                    ChatColor.DARK_BLUE + "Page 5:\n" +
                            ChatColor.DARK_GRAY + "• " + ChatColor.GRAY + "Other commands:\n" +
                            ChatColor.DARK_RED + "  /platform reload, /platform hologram, /platform tool, /platform remove, /platform list",

                    ChatColor.DARK_RED + "Page 6:\n" +
                            ChatColor.DARK_GRAY + "• " + ChatColor.GRAY + "Available Particles:\n" +
                            ChatColor.YELLOW + "1. BARRIER\n" +
                            ChatColor.YELLOW + "2. BLOCK_DUST\n" +
                            ChatColor.YELLOW + "3. CAMPFIRE_COSY_SMOKE\n" +
                            ChatColor.YELLOW + "4. CAMPFIRE_SIGNAL_SMOKE\n" +
                            ChatColor.YELLOW + "5. CLOUD\n" +
                            ChatColor.YELLOW + "6. COMPOSTER\n" +
                            ChatColor.YELLOW + "7. CRIT\n" +
                            ChatColor.YELLOW + "8. DRIP_LAVA\n" +
                            ChatColor.YELLOW + "9. DRIP_WATER\n" +
                            ChatColor.YELLOW + "10. ENCHANTMENT_TABLE\n" +
                            ChatColor.YELLOW + "11. END_ROD\n" +
                            ChatColor.YELLOW + "12. EXPLOSION_LARGE\n" +
                            ChatColor.YELLOW + "13. EXPLOSION_NORMAL\n" +
                            ChatColor.YELLOW + "14. EXPLOSION_HUGE\n" +
                            ChatColor.YELLOW + "15. FALLING_DUST\n" +
                            ChatColor.YELLOW + "16. FIREWORKS_SPARK\n" +
                            ChatColor.YELLOW + "17. FLAME\n" +
                            ChatColor.YELLOW + "18. HAPPY_VILLAGER\n" +
                            ChatColor.YELLOW + "19. HEART\n" +
                            ChatColor.YELLOW + "20. ITEM_CRACK\n" +
                            ChatColor.YELLOW + "21. LAVA\n" +
                            ChatColor.YELLOW + "22. LAVA\n" +
                            ChatColor.YELLOW + "23. MYCELIUM\n" +
                            ChatColor.YELLOW + "24. PARTICLE\n" +
                            ChatColor.YELLOW + "25. POOF\n" +
                            ChatColor.YELLOW + "26. PORTAL\n" +
                            ChatColor.YELLOW + "27. RAINBOW\n" +
                            ChatColor.YELLOW + "28. SMOKE_NORMAL\n" +
                            ChatColor.YELLOW + "29. SMOKE_LARGE\n" +
                            ChatColor.YELLOW + "30. SPELL\n" +
                            ChatColor.YELLOW + "31. SPLASH\n" +
                            ChatColor.YELLOW + "32. TOTEM\n" +
                            ChatColor.YELLOW + "33. VILLAGER_ANGRY\n" +
                            ChatColor.YELLOW + "34. VILLAGER_HAPPY\n" +
                            ChatColor.YELLOW + "35. WATER_BUBBLE\n" +
                            ChatColor.YELLOW + "36. WATER_DROP\n"
                    );
        book.setItemMeta(meta);
        player.openBook(book);
    }

    // ====================================================
    // Tab completer para el comando /platform.
    // ====================================================
    private class PlatformTabCompleter implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>(Arrays.asList("reload", "hologram", "tool", "create", "remove", "limit", "destination", "cooldown", "particle", "timer", "wiki", "list"));
                return filterStartingWith(completions, args[0]);
            }
            return null;
        }

        private List<String> filterStartingWith(List<String> list, String prefix) {
            List<String> result = new ArrayList<>();
            for (String s : list) {
                if (s.toLowerCase().startsWith(prefix.toLowerCase())) {
                    result.add(s);
                }
            }
            return result;
        }
    }
}
