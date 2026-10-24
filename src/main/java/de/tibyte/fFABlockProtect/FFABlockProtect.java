package de.tibyte.fFABlockProtect;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.Levelled;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class FFABlockProtect extends JavaPlugin implements Listener {


    private final Set<String> placedBlocks = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> waterloggedBlocks = Collections.synchronizedSet(new HashSet<>());

    private final Set<UUID> editPlayers = new HashSet<>();
    private final Set<UUID> waitingForTimeInput = new HashSet<>();
    private final Map<String, Material> arenaOres = new HashMap<>();
    private boolean oresEnabled = true;


    private volatile boolean blocksDirty = false;
    private volatile boolean waterDirty = false;
    private BukkitTask autoSaveTask;
    private Thread shutdownHook;

    private File blocksFile, arenaFile, waterFile, oresFile, stoneFile;
    private FileConfiguration blocksCfg, arenaCfg, waterCfg, oresCfg, stoneCfg;

    private Location corner1, corner2;
    private Location stoneCorner1, stoneCorner2;
    private int resetSeconds = 600;
    private int remainingSeconds = 0;
    private BukkitRunnable timer;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        blocksFile = new File(getDataFolder(), "blocks.yml");
        arenaFile = new File(getDataFolder(), "arena.yml");
        waterFile = new File(getDataFolder(), "waterlogged.yml");
        oresFile = new File(getDataFolder(), "ores.yml");
        stoneFile = new File(getDataFolder(), "stonebox.yml");

        try {
            if (!blocksFile.exists()) blocksFile.createNewFile();
            if (!arenaFile.exists()) arenaFile.createNewFile();
            if (!waterFile.exists()) waterFile.createNewFile();
            if (!oresFile.exists()) oresFile.createNewFile();
            if (!stoneFile.exists()) stoneFile.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }

        blocksCfg = YamlConfiguration.loadConfiguration(blocksFile);
        arenaCfg = YamlConfiguration.loadConfiguration(arenaFile);
        waterCfg = YamlConfiguration.loadConfiguration(waterFile);
        oresCfg = YamlConfiguration.loadConfiguration(oresFile);
        stoneCfg = YamlConfiguration.loadConfiguration(stoneFile);

        List<String> loaded = blocksCfg.getStringList("blocks");
        if (loaded != null) placedBlocks.addAll(loaded);

        List<String> waterLoaded = waterCfg.getStringList("waterlogged");
        if (waterLoaded != null) waterloggedBlocks.addAll(waterLoaded);

        if (arenaCfg.contains("corner1")) corner1 = loadLoc(arenaCfg.getConfigurationSection("corner1"));
        if (arenaCfg.contains("corner2")) corner2 = loadLoc(arenaCfg.getConfigurationSection("corner2"));

        if (stoneCfg.contains("corner1")) stoneCorner1 = loadLoc(stoneCfg.getConfigurationSection("corner1"));
        if (stoneCfg.contains("corner2")) stoneCorner2 = loadLoc(stoneCfg.getConfigurationSection("corner2"));

        if (arenaCfg.contains("resetSeconds")) {
            resetSeconds = arenaCfg.getInt("resetSeconds");
        } else {
            resetSeconds = arenaCfg.getInt("resetMinutes", 10) * 60;
        }

        oresEnabled = oresCfg.getBoolean("enabled", true);

        ConfigurationSection sec = oresCfg.getConfigurationSection("ores");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                String matStr = sec.getString(k);
                if (matStr != null) {
                    try {
                        arenaOres.put(k, Material.valueOf(matStr));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        if (corner1 != null && corner2 != null) {
            startTimer();
        }

        // JVM Shutdown-Hook für plötzliche Server-Kills (SIGTERM / Systemd / Pterodactyl-Stopp)
        shutdownHook = new Thread(this::emergencySave, "FFABlockProtect-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // Alle 3 Sekunden (60 Ticks) ungespeicherte Blöcke asynchron auf die Platte sichern
        autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            saveDirtyData();
        }, 60L, 60L);
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }

        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {}
        }

        // Abschließender Save aller Daten
        emergencySave();
        saveArena();
        saveOres();
        saveStonebox();

        if (timer != null) timer.cancel();
    }



    private synchronized void saveDirtyData() {
        if (blocksDirty) {
            saveBlocksDirect();
            blocksDirty = false;
        }
        if (waterDirty) {
            saveWaterloggedDirect();
            waterDirty = false;
        }
    }

    private synchronized void emergencySave() {
        saveBlocksDirect();
        saveWaterloggedDirect();
        blocksDirty = false;
        waterDirty = false;
    }

    private void saveBlocksDirect() {
        List<String> snapshot;
        synchronized (placedBlocks) {
            snapshot = new ArrayList<>(placedBlocks);
        }

        YamlConfiguration tempCfg = new YamlConfiguration();
        tempCfg.set("blocks", snapshot);
        writeYamlSafely(tempCfg, blocksFile);
    }

    private void saveWaterloggedDirect() {
        List<String> snapshot;
        synchronized (waterloggedBlocks) {
            snapshot = new ArrayList<>(waterloggedBlocks);
        }

        YamlConfiguration tempCfg = new YamlConfiguration();
        tempCfg.set("waterlogged", snapshot);
        writeYamlSafely(tempCfg, waterFile);
    }


    private void writeYamlSafely(YamlConfiguration cfg, File targetFile) {
        File tmpFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
        try {
            cfg.save(tmpFile);
            Files.move(tmpFile.toPath(), targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {

            try {
                cfg.save(targetFile);
            } catch (IOException ex) {
                getLogger().warning("Failed to save " + targetFile.getName() + ": " + ex.getMessage());
            }
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (inStonebox(b)) {
            placedBlocks.add(key(b));
            blocksDirty = true;
            return;
        }
        if (!inArena(b)) return;

        Player p = e.getPlayer();
        if (editPlayers.contains(p.getUniqueId())) {
            if (isOre(b.getType())) {
                arenaOres.put(key(b), b.getType());
                saveOres();
                p.sendMessage(toSmallCaps("§aOre registered as Arena Ore: §e" + b.getType().name()));
            }
            return;
        }

        placedBlocks.add(key(b));
        blocksDirty = true;
    }

    private boolean isOre(Material material) {
        return material.name().endsWith("_ORE") ||
                material == Material.ANCIENT_DEBRIS ||
                material == Material.RAW_GOLD_BLOCK ||
                material == Material.RAW_IRON_BLOCK ||
                material == Material.RAW_COPPER_BLOCK;
    }

    private String toSmallCaps(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        char[] chars = input.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '§' && i + 1 < chars.length) {
                sb.append('§');
                sb.append(chars[i + 1]);
                i++;
                continue;
            }
            char lower = Character.toLowerCase(c);
            switch (lower) {
                case 'a': sb.append('ᴀ'); break;
                case 'b': sb.append('ʙ'); break;
                case 'c': sb.append('ᴄ'); break;
                case 'd': sb.append('ᴅ'); break;
                case 'e': sb.append('ᴇ'); break;
                case 'f': sb.append('ꜰ'); break;
                case 'g': sb.append('ɢ'); break;
                case 'h': sb.append('ʜ'); break;
                case 'i': sb.append('ɪ'); break;
                case 'j': sb.append('ᴊ'); break;
                case 'k': sb.append('ᴋ'); break;
                case 'l': sb.append('ʟ'); break;
                case 'm': sb.append('ᴍ'); break;
                case 'n': sb.append('ɴ'); break;
                case 'o': sb.append('ᴏ'); break;
                case 'p': sb.append('ᴘ'); break;
                case 'q': sb.append('ǫ'); break;
                case 'r': sb.append('ʀ'); break;
                case 's': sb.append('ꜱ'); break;
                case 't': sb.append('ᴛ'); break;
                case 'u': sb.append('ᴜ'); break;
                case 'v': sb.append('ᴠ'); break;
                case 'w': sb.append('ᴡ'); break;
                case 'x': sb.append('x'); break;
                case 'y': sb.append('ʏ'); break;
                case 'z': sb.append('ᴢ'); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    private void giveTokenItem(Player p) {
        ItemStack token = new ItemStack(Material.FIREWORK_STAR);
        ItemMeta meta = token.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(toSmallCaps("§7§lᴍɪɴɪɴɢ ᴛᴏᴋᴇɴ"));
            meta.setLore(Arrays.asList(toSmallCaps("§8» §7ᴄᴀɴ ʙᴇ ᴇxᴄʜᴀɴɢᴇᴅ ғᴏʀ ᴋɪʟʟ ᴛᴏᴋᴇɴs.")));
            token.setItemMeta(meta);
        }

        HashMap<Integer, ItemStack> remaining = p.getInventory().addItem(token);
        if (!remaining.isEmpty()) {
            for (ItemStack item : remaining.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), item);
            }
        }
    }

    private void broadcastPlayersOnly(String message) {
        if (message == null) return;
        Component comp = Component.text(message);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(comp);
        }
    }

    @EventHandler
    public void onOreLeftClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (b.getType() == Material.BEDROCK && arenaOres.containsKey(key(b))) {
            e.getPlayer().sendActionBar(Component.text(toSmallCaps("§7This ore regenerates on the next map reset.")));
        }
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        GameMode newGm = e.getNewGameMode();
        if (newGm == GameMode.SURVIVAL || newGm == GameMode.ADVENTURE) {
            if (editPlayers.remove(p.getUniqueId())) {
                p.sendMessage(toSmallCaps("§cEditmode disabled because you left Creative/Spectator mode."));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        waitingForTimeInput.remove(uuid);
        editPlayers.remove(uuid);
    }

    @EventHandler
    public void onConcreteForm(BlockFormEvent e) {
        if (e.getBlock().getType().name().endsWith("_CONCRETE_POWDER") &&
                e.getNewState().getType().name().endsWith("_CONCRETE")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWandInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPermission("arena.edit")) return;

        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType() != Material.GOLDEN_AXE) return;
        if (!item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        if (meta.getDisplayName().equals(toSmallCaps("§d§lStonebox Wand"))) {
            Block b = e.getClickedBlock();
            if (b == null) return;

            e.setCancelled(true);
            if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                stoneCorner1 = b.getLocation();
                saveStonebox();
                p.sendMessage(toSmallCaps("§aFirst stonebox position set to §e" + b.getX() + ", " + b.getY() + ", " + b.getZ()));
                fillStoneboxWithStone();
            } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                stoneCorner2 = b.getLocation();
                saveStonebox();
                p.sendMessage(toSmallCaps("§aSecond stonebox position set to §e" + b.getX() + ", " + b.getY() + ", " + b.getZ()));
                fillStoneboxWithStone();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWaterlog(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

        Player p = e.getPlayer();
        Block b = e.getClickedBlock();

        if (!inArena(b)) return;
        if (editPlayers.contains(p.getUniqueId())) return;

        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.WATER_BUCKET) return;

        if (b.getBlockData() instanceof Waterlogged) {
            Waterlogged wl = (Waterlogged) b.getBlockData();
            if (!wl.isWaterlogged()) {
                if (!placedBlocks.contains(key(b))) {
                    e.setCancelled(true);
                    p.sendActionBar(Component.text(toSmallCaps("§c❌ You can't do this!")));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogStrip(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

        Player p = e.getPlayer();
        Block b = e.getClickedBlock();

        if (!inArena(b)) return;
        if (editPlayers.contains(p.getUniqueId())) return;

        Material item = p.getInventory().getItemInMainHand().getType();
        Material block = b.getType();

        if (!item.name().endsWith("_AXE")) return;

        if (block.name().endsWith("_LOG") || block.name().endsWith("_WOOD") || block.name().endsWith("_HYPHAE")) {
            e.setCancelled(true);
            p.sendActionBar(Component.text(toSmallCaps("§c❌ You can't do this!")));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType() == Material.GOLDEN_AXE && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(toSmallCaps("§d§lStonebox Wand"))) {
                e.setCancelled(true);
                return;
            }
        }

        Block b = e.getBlock();
        String k = key(b);

        if (inStonebox(b)) {
            placedBlocks.remove(k);
            waterloggedBlocks.remove(k);
            blocksDirty = true;
            waterDirty = true;
            return;
        }
        if (!inArena(b)) return;

        if (editPlayers.contains(p.getUniqueId())) {
            if (arenaOres.containsKey(k)) {
                arenaOres.remove(k);
                saveOres();
                p.sendMessage(toSmallCaps("§cArena Ore removed."));
            }
            return;
        }

        if (arenaOres.containsKey(k)) {
            e.setCancelled(true);
            if (oresEnabled) {
                if (b.getType() != Material.BEDROCK) {
                    giveTokenItem(p);
                    b.setType(Material.BEDROCK);
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                } else {
                    p.sendActionBar(Component.text(toSmallCaps("§c❌ This ore is already mined!")));
                }
            } else {
                p.sendActionBar(Component.text(toSmallCaps("§c❌ You can't break these blocks!")));
            }
            return;
        }

        if (!placedBlocks.contains(k)) {
            e.setCancelled(true);
            p.sendActionBar(Component.text(toSmallCaps("§c❌ You can't break these blocks!")));
        } else {
            placedBlocks.remove(k);
            waterloggedBlocks.remove(k);
            blocksDirty = true;
            waterDirty = true;
        }
    }

    @EventHandler
    public void onSnowReplace(BlockPlaceEvent e) {
        if (e.getBlockReplacedState().getType() == Material.SNOW) {
            if (!editPlayers.contains(e.getPlayer().getUniqueId())) {
                e.setCancelled(true);
            }
        }
    }



    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWaterPhysics(BlockPhysicsEvent e) {
        if (!inArena(e.getBlock())) return;

        Block block = e.getBlock();
        Material type = block.getType();

        if (type == Material.WATER) {
            Block blockBelow = block.getLocation().subtract(0, 1, 0).getBlock();
            if (blockBelow.getType() == Material.MAGMA_BLOCK || blockBelow.getType() == Material.BUBBLE_COLUMN) {
                if (block.getBlockData() instanceof Levelled waterData) {
                    if (waterData.getLevel() > 0) {

                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockWaterlog(BlockPlaceEvent e) {
        Block block = e.getBlock();
        if (!inArena(block)) return;

        BlockState blockState = e.getBlockPlaced().getState();
        if (blockState instanceof Waterlogged waterlogged) {
            if (waterlogged.isWaterlogged()) {
                waterloggedBlocks.add(key(block));
                waterDirty = true;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        if (!inArena(b)) return;

        Player p = e.getPlayer();
        if (editPlayers.contains(p.getUniqueId())) return;

        String k = key(b);
        if (b.getBlockData() instanceof Waterlogged) {
            if (!placedBlocks.contains(k)) {
                e.setCancelled(true);
                p.sendActionBar(Component.text(toSmallCaps("§c❌ You can't do this!")));
                return;
            }
            waterloggedBlocks.add(k);
            waterDirty = true;
        } else {
            placedBlocks.add(k);
            blocksDirty = true;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Block b = e.getBlock();
        if (!inArena(b)) return;

        Player p = e.getPlayer();
        if (editPlayers.contains(p.getUniqueId())) return;

        String k = key(b);
        if (!placedBlocks.contains(k) && !waterloggedBlocks.contains(k)) {
            e.setCancelled(true);
            p.sendActionBar(Component.text(toSmallCaps("§c❌ You can't do this!")));
        } else {
            if (b.getBlockData() instanceof Waterlogged) {
                waterloggedBlocks.remove(k);
                waterDirty = true;
            } else {
                placedBlocks.remove(k);
                blocksDirty = true;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent e) {
        Block to = e.getToBlock();
        if (!inArena(to)) return;

        String k = key(to);
        if (to.getBlockData() instanceof Waterlogged) {
            waterloggedBlocks.add(k);
            waterDirty = true;
        } else {
            placedBlocks.add(k);
            blocksDirty = true;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockForm(BlockFormEvent e) {
        if (!inArena(e.getBlock())) return;

        Block block = e.getBlock();
        BlockState newState = e.getNewState();

        if (newState.getType() == Material.BUBBLE_COLUMN) {
            e.setCancelled(true);
            block.setType(Material.WATER, false);
        }
    }



    @Override
    public boolean onCommand(CommandSender s, Command cmd, String l, String[] a) {
        if (!(s instanceof Player p)) return true;

        switch (cmd.getName().toLowerCase()) {
            case "arenaedit" -> {
                if (!p.hasPermission("arena.edit")) return true;
                GameMode gm = p.getGameMode();
                if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) {
                    p.sendMessage(toSmallCaps("§cYou must be in Creative or Spectator mode to use edit mode!"));
                    return true;
                }
                if (editPlayers.remove(p.getUniqueId())) {
                    p.sendMessage(toSmallCaps("§cEditmode disabled."));
                } else {
                    editPlayers.add(p.getUniqueId());
                    p.sendMessage(toSmallCaps("§aEditmode enabled."));
                }
            }
            case "setcorner1" -> {
                corner1 = p.getLocation();
                saveArena();
                p.sendMessage(toSmallCaps("§aCorner 1 set."));
            }
            case "setcorner2" -> {
                corner2 = p.getLocation();
                saveArena();
                p.sendMessage(toSmallCaps("§aCorner 2 set."));
            }
            case "arenareset" -> {
                resetArena();
                broadcastPlayersOnly(toSmallCaps("§b🧹 Map Reset!"));
            }
            case "arenatime" -> {
                if (a.length != 1) return true;
                try {
                    resetSeconds = Integer.parseInt(a[0]) * 60;
                    saveArena();
                    p.sendMessage(toSmallCaps("§aReset interval set to " + (resetSeconds / 60) + " minutes."));
                    startTimer();
                } catch (NumberFormatException e) {
                    p.sendMessage(toSmallCaps("§cInvalid number."));
                }
            }
            case "arenastart" -> {
                startTimer();
                p.sendMessage(toSmallCaps("§aArena timer started."));
            }
            case "clearwater" -> {
                p.sendMessage(toSmallCaps("§aRemoving all water blocks..."));
                clearWaterMagmaFix();
                p.sendMessage(toSmallCaps("§a✅ All water blocks removed!"));
            }
            case "stonebox" -> {
                if (!p.hasPermission("arena.edit")) return true;
                if (a.length != 1) {
                    p.sendMessage(toSmallCaps("§cUsage: /stonebox <pos1|pos2|wand>"));
                    return true;
                }
                String sub = a[0].toLowerCase();
                if (sub.equals("pos1")) {
                    stoneCorner1 = p.getLocation().getBlock().getLocation();
                    saveStonebox();
                    p.sendMessage(toSmallCaps("§aFirst stonebox position set to your location."));
                    fillStoneboxWithStone();
                } else if (sub.equals("pos2")) {
                    stoneCorner2 = p.getLocation().getBlock().getLocation();
                    saveStonebox();
                    p.sendMessage(toSmallCaps("§aSecond stonebox position set to your location."));
                    fillStoneboxWithStone();
                } else if (sub.equals("wand")) {
                    ItemStack wand = new ItemStack(Material.GOLDEN_AXE);
                    ItemMeta meta = wand.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(toSmallCaps("§d§lStonebox Wand"));
                        meta.setLore(Arrays.asList(
                                toSmallCaps("§7Left-click: Set pos1"),
                                toSmallCaps("§7Right-click: Set pos2")
                        ));
                        wand.setItemMeta(meta);
                    }
                    p.getInventory().addItem(wand);
                    p.sendMessage(toSmallCaps("§aStonebox Wand tool received."));
                } else {
                    p.sendMessage(toSmallCaps("§cUsage: /stonebox <pos1|pos2|wand>"));
                }
            }
            case "arena" -> openArenaGUI(p);
        }
        return true;
    }


    private void resetArena() {
        synchronized (waterloggedBlocks) {
            for (String k : waterloggedBlocks) {
                String[] p = k.split(":");
                World w = Bukkit.getWorld(p[0]);
                if (w == null) continue;

                String[] c = p[1].split(",");
                Block b = w.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));

                if (b.getBlockData() instanceof Waterlogged wl) {
                    if (wl.isWaterlogged()) {
                        wl.setWaterlogged(false);
                        b.setBlockData(wl, false);
                    }
                }
            }
        }

        synchronized (placedBlocks) {
            for (String k : placedBlocks) {
                String[] p = k.split(":");
                World w = Bukkit.getWorld(p[0]);
                if (w == null) continue;

                String[] c = p[1].split(",");
                Block b = w.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
                b.setType(Material.AIR, false);
            }
        }

        for (Map.Entry<String, Material> entry : arenaOres.entrySet()) {
            String[] p = entry.getKey().split(":");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) continue;

            String[] c = p[1].split(",");
            Block b = w.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
            b.setType(entry.getValue(), false);
        }


        resetStoneboxLayerByLayer();

        placedBlocks.clear();
        waterloggedBlocks.clear();
        blocksDirty = false;
        waterDirty = false;

        Bukkit.getScheduler().runTaskAsynchronously(this, this::emergencySave);
    }

    private void resetStoneboxLayerByLayer() {
        if (stoneCorner1 == null || stoneCorner2 == null) return;

        World world = stoneCorner1.getWorld();
        int minX = Math.min(stoneCorner1.getBlockX(), stoneCorner2.getBlockX());
        int maxX = Math.max(stoneCorner1.getBlockX(), stoneCorner2.getBlockX());
        int minY = Math.min(stoneCorner1.getBlockY(), stoneCorner2.getBlockY());
        int maxY = Math.max(stoneCorner1.getBlockY(), stoneCorner2.getBlockY());
        int minZ = Math.min(stoneCorner1.getBlockZ(), stoneCorner2.getBlockZ());
        int maxZ = Math.max(stoneCorner1.getBlockZ(), stoneCorner2.getBlockZ());

        new BukkitRunnable() {
            int currentY = minY;

            @Override
            public void run() {
                if (currentY > maxY) {
                    cancel();
                    return;
                }

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block b = world.getBlockAt(x, currentY, z);
                        b.setType(Material.STONE, false);
                    }
                }

                Location center = new Location(world, (minX + maxX) / 2.0, currentY, (minZ + maxZ) / 2.0);
                world.playSound(center, Sound.BLOCK_STONE_PLACE, 1.0f, 1.0f);
                world.spawnParticle(Particle.BLOCK, center, 20, 1.0, 0.1, 1.0, Material.STONE.createBlockData());

                currentY++;
            }
        }.runTaskTimer(this, 0, 3);
    }

    private void clearWaterMagmaFix() {
        if (corner1 == null || corner2 == null) return;

        World world = corner1.getWorld();
        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int minY = Math.min(corner1.getBlockY(), corner2.getBlockY());
        int maxY = Math.max(corner1.getBlockY(), corner2.getBlockY());
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();

                    if (type == Material.WATER || type == Material.LAVA ||
                            type == Material.WATER_CAULDRON || type == Material.LAVA_CAULDRON ||
                            type == Material.BUBBLE_COLUMN || type == Material.KELP ||
                            type == Material.KELP_PLANT || type == Material.SEAGRASS ||
                            type == Material.TALL_SEAGRASS) {
                        block.setType(Material.AIR, false);
                        continue;
                    }

                    if (block.getBlockData() instanceof Waterlogged wl) {
                        if (wl.isWaterlogged()) {
                            wl.setWaterlogged(false);
                            block.setBlockData(wl, false);
                        }
                    }
                }
            }
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.BUBBLE_COLUMN) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void startTimer() {
        if (timer != null) timer.cancel();
        remainingSeconds = resetSeconds;
        timer = new BukkitRunnable() {
            public void run() {
                remainingSeconds--;
                if (remainingSeconds <= 0) {
                    resetArena();
                    broadcastPlayersOnly(toSmallCaps("§b🧹 The map got cleaned!"));
                    remainingSeconds = resetSeconds;
                    return;
                }

                if (remainingSeconds == 60) {
                    broadcastPlayersOnly(toSmallCaps("§b🧹 Map reset in §f1 minute§b!"));
                } else if (remainingSeconds == 30) {
                    broadcastPlayersOnly(toSmallCaps("§b🧹 Map reset in §f30 seconds§b!"));
                } else if (remainingSeconds == 15) {
                    broadcastPlayersOnly(toSmallCaps("§b🧹 Map reset in §f15 seconds§b!"));
                } else if (remainingSeconds <= 10 && remainingSeconds > 0) {
                    broadcastPlayersOnly(toSmallCaps("§b🧹 Map reset in §f") + remainingSeconds + toSmallCaps(" seconds§b!"));
                }
            }
        };
        timer.runTaskTimer(this, 20, 20);
    }

    /* ================= GUI & INTERACTIVE LOGIC ================= */

    public void openArenaGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, toSmallCaps("§8» Arena Settings"));

        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        if (bgMeta != null) {
            bgMeta.setDisplayName(" ");
            bg.setItemMeta(bgMeta);
        }
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, bg);
        }

        boolean hasEdit = p.hasPermission("arena.edit");

        if (hasEdit) {
            ItemStack resetItem = new ItemStack(Material.REDSTONE_BLOCK);
            ItemMeta resetMeta = resetItem.getItemMeta();
            if (resetMeta != null) {
                resetMeta.setDisplayName(toSmallCaps("§c§l🧹 Reset Arena"));
                resetMeta.setLore(Arrays.asList(
                        toSmallCaps("§7Resets the entire arena immediately."),
                        toSmallCaps("§7- Removes placed blocks"),
                        toSmallCaps("§7- Regenerates ores"),
                        toSmallCaps("§7- Removes liquids"),
                        "",
                        toSmallCaps("§e▶ Click to reset!")
                ));
                resetItem.setItemMeta(resetMeta);
            }
            inv.setItem(11, resetItem);
        } else {
            ItemStack noPerm = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta noPermMeta = noPerm.getItemMeta();
            if (noPermMeta != null) {
                noPermMeta.setDisplayName(toSmallCaps("§cNo Permission"));
                noPerm.setItemMeta(noPermMeta);
            }
            inv.setItem(11, noPerm);
        }

        ItemStack infoItem = new ItemStack(Material.CLOCK);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName(toSmallCaps("§b§l⏱ Arena Info & Status"));
            String intervalStr = formatTime(resetSeconds);
            String remainingStr = (timer != null) ? formatTime(remainingSeconds) : toSmallCaps("§cInactive");
            String c1Str = (corner1 != null) ? toSmallCaps("§aSet") : toSmallCaps("§cNot set");
            String c2Str = (corner2 != null) ? toSmallCaps("§aSet") : toSmallCaps("§cNot set");
            String s1Str = (stoneCorner1 != null) ? toSmallCaps("§aSet") : toSmallCaps("§cNot set");
            String s2Str = (stoneCorner2 != null) ? toSmallCaps("§aSet") : toSmallCaps("§cNot set");
            infoMeta.setLore(Arrays.asList(
                    toSmallCaps("§7Interval: §e" + intervalStr),
                    toSmallCaps("§7Remaining: §e" + remainingStr),
                    "",
                    toSmallCaps("§7Corner 1: " + c1Str),
                    toSmallCaps("§7Corner 2: " + c2Str),
                    "",
                    toSmallCaps("§7Stonebox 1: " + s1Str),
                    toSmallCaps("§7Stonebox 2: " + s2Str),
                    "",
                    (timer != null) ? toSmallCaps("§a⚡ Timer running...") : toSmallCaps("§c❌ Timer stopped.")
            ));
            infoItem.setItemMeta(infoMeta);
        }
        inv.setItem(13, infoItem);

        if (hasEdit) {
            ItemStack anvilItem = new ItemStack(Material.ANVIL);
            ItemMeta anvilMeta = anvilItem.getItemMeta();
            if (anvilMeta != null) {
                anvilMeta.setDisplayName(toSmallCaps("§e§l⚙ Change Reset Time"));
                anvilMeta.setLore(Arrays.asList(
                        toSmallCaps("§7Click here to adjust the automatic"),
                        toSmallCaps("§7reset interval."),
                        "",
                        toSmallCaps("§7Possible chat inputs:"),
                        toSmallCaps("§e- 10m §7(for 10 minutes)"),
                        toSmallCaps("§e- 30s §7(for 30 seconds)"),
                        "",
                        toSmallCaps("§e▶ Click to enter time!")
                ));
                anvilItem.setItemMeta(anvilMeta);
            }
            inv.setItem(15, anvilItem);
        } else {
            ItemStack noPerm = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta noPermMeta = noPerm.getItemMeta();
            if (noPermMeta != null) {
                noPermMeta.setDisplayName(toSmallCaps("§cNo Permission"));
                noPerm.setItemMeta(noPermMeta);
            }
            inv.setItem(15, noPerm);
        }

        ItemStack oreInfoItem = new ItemStack(Material.GOLD_ORE);
        ItemMeta oreInfoMeta = oreInfoItem.getItemMeta();
        if (oreInfoMeta != null) {
            oreInfoMeta.setDisplayName(toSmallCaps("§6§l🪙 Ore Mining System"));
            oreInfoMeta.setLore(Arrays.asList(
                    toSmallCaps("§7Mine ores placed in edit mode"),
                    toSmallCaps("§7to receive a"),
                    toSmallCaps("§7§lMining Token§7!"),
                    "",
                    toSmallCaps("§7Currently registered ores: §e" + arenaOres.size()),
                    "",
                    toSmallCaps("§e▶ Click to configure!")
            ));
            oreInfoItem.setItemMeta(oreInfoMeta);
        }
        inv.setItem(22, oreInfoItem);

        p.openInventory(inv);
    }

    private void openOreMiningGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, toSmallCaps("§8» Ore Mining Settings"));

        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        if (bgMeta != null) {
            bgMeta.setDisplayName(" ");
            bg.setItemMeta(bgMeta);
        }
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, bg);
        }

        ItemStack oreInfoItem = new ItemStack(Material.GOLD_ORE);
        ItemMeta oreInfoMeta = oreInfoItem.getItemMeta();
        if (oreInfoMeta != null) {
            oreInfoMeta.setDisplayName(toSmallCaps("§6§l🪙 Ore Mining System"));
            oreInfoMeta.setLore(Arrays.asList(
                    toSmallCaps("§7Mine ores placed in edit mode"),
                    toSmallCaps("§7to receive a"),
                    toSmallCaps("§7§lMining Token§7!"),
                    "",
                    toSmallCaps("§7Currently registered ores: §e" + arenaOres.size())
            ));
            oreInfoItem.setItemMeta(oreInfoMeta);
        }
        inv.setItem(11, oreInfoItem);

        boolean hasEdit = p.hasPermission("arena.edit");
        if (hasEdit) {
            ItemStack toggleItem = new ItemStack(oresEnabled ? Material.LIME_DYE : Material.GRAY_DYE);
            ItemMeta toggleMeta = toggleItem.getItemMeta();
            if (toggleMeta != null) {
                toggleMeta.setDisplayName(oresEnabled ? toSmallCaps("§a§lStatus: Enabled") : toSmallCaps("§c§lStatus: Disabled"));
                toggleMeta.setLore(Arrays.asList(
                        oresEnabled ? toSmallCaps("§7Click to §c§lDeactivate§7 the mining system.") : toSmallCaps("§7Click to §a§lActivate§7 the mining system."),
                        "",
                        toSmallCaps("§7Ores list remains saved in the configuration file.")
                ));
                toggleItem.setItemMeta(toggleMeta);
            }
            inv.setItem(15, toggleItem);
        } else {
            ItemStack noPerm = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta noPermMeta = noPerm.getItemMeta();
            if (noPermMeta != null) {
                noPermMeta.setDisplayName(toSmallCaps("§cNo Permission"));
                noPerm.setItemMeta(noPermMeta);
            }
            inv.setItem(15, noPerm);
        }

        ItemStack backItem = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = backItem.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(toSmallCaps("§c§l◀ Back"));
            backMeta.setLore(Arrays.asList(toSmallCaps("§7Return to main settings page.")));
            backItem.setItemMeta(backMeta);
        }
        inv.setItem(22, backItem);

        p.openInventory(inv);
    }

    private String formatTime(int totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        if (m > 0) {
            return toSmallCaps(m + "m " + s + "s");
        } else {
            return toSmallCaps(s + "s");
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (title.equals(toSmallCaps("§8» Arena Settings"))) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            if (e.getCurrentItem() == null) return;

            ItemStack clicked = e.getCurrentItem();
            if (clicked.getType() == Material.REDSTONE_BLOCK) {
                if (!p.hasPermission("arena.edit")) return;
                p.closeInventory();
                p.performCommand("arenareset");
            } else if (clicked.getType() == Material.ANVIL) {
                if (!p.hasPermission("arena.edit")) return;
                p.closeInventory();
                waitingForTimeInput.add(p.getUniqueId());
                p.sendMessage("§b========================================");
                p.sendMessage(toSmallCaps("§e§l⏱ Set Reset Time"));
                p.sendMessage(toSmallCaps("§7Please enter the new reset time in chat."));
                p.sendMessage(toSmallCaps("§7Use §em§7 for minutes or §es§7 for seconds."));
                p.sendMessage(toSmallCaps("§7Example: §e10m§7 or §e30s"));
                p.sendMessage(toSmallCaps("§7Type §ccancel§7 to cancel."));
                p.sendMessage("§b========================================");
            } else if (clicked.getType() == Material.GOLD_ORE) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                openOreMiningGUI(p);
            }
        } else if (title.equals(toSmallCaps("§8» Ore Mining Settings"))) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            if (e.getCurrentItem() == null) return;

            ItemStack clicked = e.getCurrentItem();
            if (clicked.getType() == Material.LIME_DYE || clicked.getType() == Material.GRAY_DYE) {
                if (!p.hasPermission("arena.edit")) return;
                oresEnabled = !oresEnabled;
                saveOres();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                openOreMiningGUI(p);
            } else if (clicked.getType() == Material.BARRIER) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                openArenaGUI(p);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!waitingForTimeInput.contains(p.getUniqueId())) return;

        e.setCancelled(true);
        String msg = e.getMessage().trim().toLowerCase();

        if (msg.equals("cancel")) {
            waitingForTimeInput.remove(p.getUniqueId());
            p.sendMessage(toSmallCaps("§cInput cancelled."));
            Bukkit.getScheduler().runTask(this, () -> openArenaGUI(p));
            return;
        }

        if (msg.matches("\\d+[ms]")) {
            char unit = msg.charAt(msg.length() - 1);
            int val;
            try {
                val = Integer.parseInt(msg.substring(0, msg.length() - 1));
            } catch (NumberFormatException ex) {
                p.sendMessage(toSmallCaps("§cInvalid number!"));
                return;
            }

            int seconds = (unit == 'm') ? val * 60 : val;
            if (seconds <= 0) {
                p.sendMessage(toSmallCaps("§cTime must be greater than 0!"));
                return;
            }

            waitingForTimeInput.remove(p.getUniqueId());
            resetSeconds = seconds;
            saveArena();
            startTimer();

            p.sendMessage(toSmallCaps("§aReset time successfully set to §e") + formatTime(seconds) + toSmallCaps("§a and timer restarted!"));
            Bukkit.getScheduler().runTask(this, () -> openArenaGUI(p));
        } else {
            p.sendMessage(toSmallCaps("§cInvalid format! Please enter e.g. §e10m§c or §e30s§c (or §ccancel§c to cancel)."));
        }
    }

    /* ================= UTILS ================= */

    private boolean inStonebox(Block b) {
        if (stoneCorner1 == null || stoneCorner2 == null) return false;
        if (!b.getWorld().equals(stoneCorner1.getWorld())) return false;

        int x = b.getX(), y = b.getY(), z = b.getZ();
        return x >= Math.min(stoneCorner1.getBlockX(), stoneCorner2.getBlockX()) &&
                x <= Math.max(stoneCorner1.getBlockX(), stoneCorner2.getBlockX()) &&
                y >= Math.min(stoneCorner1.getBlockY(), stoneCorner2.getBlockY()) &&
                y <= Math.max(stoneCorner1.getBlockY(), stoneCorner2.getBlockY()) &&
                z >= Math.min(stoneCorner1.getBlockZ(), stoneCorner2.getBlockZ()) &&
                z <= Math.max(stoneCorner1.getBlockZ(), stoneCorner2.getBlockZ());
    }

    private void saveStonebox() {
        if (stoneCorner1 != null) saveLoc(stoneCfg, "corner1", stoneCorner1);
        if (stoneCorner2 != null) saveLoc(stoneCfg, "corner2", stoneCorner2);
        try { stoneCfg.save(stoneFile); } catch (IOException ignored) {}
    }

    private void fillStoneboxWithStone() {
        if (stoneCorner1 == null || stoneCorner2 == null) return;
        World world = stoneCorner1.getWorld();
        int minX = Math.min(stoneCorner1.getBlockX(), stoneCorner2.getBlockX());
        int maxX = Math.max(stoneCorner1.getBlockX(), stoneCorner2.getBlockX());
        int minY = Math.min(stoneCorner1.getBlockY(), stoneCorner2.getBlockY());
        int maxY = Math.max(stoneCorner1.getBlockY(), stoneCorner2.getBlockY());
        int minZ = Math.min(stoneCorner1.getBlockZ(), stoneCorner2.getBlockZ());
        int maxZ = Math.max(stoneCorner1.getBlockZ(), stoneCorner2.getBlockZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE, false);
                }
            }
        }
    }

    private boolean inArena(Block b) {
        if (corner1 == null || corner2 == null) return false;
        if (!b.getWorld().equals(corner1.getWorld())) return false;

        int x = b.getX(), y = b.getY(), z = b.getZ();
        return x >= Math.min(corner1.getBlockX(), corner2.getBlockX()) &&
                x <= Math.max(corner1.getBlockX(), corner2.getBlockX()) &&
                y >= Math.min(corner1.getBlockY(), corner2.getBlockY()) &&
                y <= Math.max(corner1.getBlockY(), corner2.getBlockY()) &&
                z >= Math.min(corner1.getBlockZ(), corner2.getBlockZ()) &&
                z <= Math.max(corner1.getBlockZ(), corner2.getBlockZ());
    }

    private String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private void saveArena() {
        if (corner1 != null) saveLoc(arenaCfg, "corner1", corner1);
        if (corner2 != null) saveLoc(arenaCfg, "corner2", corner2);
        arenaCfg.set("resetSeconds", resetSeconds);
        try { arenaCfg.save(arenaFile); } catch (IOException ignored) {}
    }

    private void saveOres() {
        oresCfg.set("enabled", oresEnabled);
        oresCfg.set("ores", null);
        for (Map.Entry<String, Material> entry : arenaOres.entrySet()) {
            oresCfg.set("ores." + entry.getKey(), entry.getValue().name());
        }
        try { oresCfg.save(oresFile); } catch (IOException ignored) {}
    }

    private void saveLoc(FileConfiguration c, String path, Location l) {
        c.set(path + ".world", l.getWorld().getName());
        c.set(path + ".x", l.getX());
        c.set(path + ".y", l.getY());
        c.set(path + ".z", l.getZ());
    }

    private Location loadLoc(ConfigurationSection s) {
        if (s == null) return null;
        World w = Bukkit.getWorld(s.getString("world", ""));
        if (w == null) return null;
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
    }
}