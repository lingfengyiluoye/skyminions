# Spigot/Paper API Reference

## Common Events

### Player Events

```java
// Join/Quit
PlayerJoinEvent         // Player joins the server
PlayerQuitEvent         // Player leaves the server
PlayerLoginEvent        // Before player fully joins (can kick)
PlayerKickEvent         // Player is kicked

// Movement
PlayerMoveEvent         // Player moves (high frequency, use carefully)
PlayerTeleportEvent     // Player teleports
PlayerToggleSneakEvent  // Player sneaks/unsneaks
PlayerToggleSprintEvent // Player sprints/stops

// Interaction
PlayerInteractEvent     // Right/left click block/air
PlayerInteractEntityEvent // Right-click entity
PlayerInteractAtEntityEvent // Right-click at specific point on entity
PlayerItemHeldEvent     // Player changes held slot
PlayerDropItemEvent     // Player drops item
PlayerPickupItemEvent   // Player picks up item (deprecated, use EntityPickupItemEvent)

// Chat
AsyncPlayerChatEvent    // Player sends chat message (async context!)
// Paper: use ChatEvent or adventure chat listeners instead

// Combat
EntityDamageByEntityEvent // Entity damages entity (check if damager is Player)
EntityDeathEvent          // Entity dies
PlayerDeathEvent          // Player dies
PlayerRespawnEvent        // Player respawns

// Inventory
InventoryClickEvent     // Click in inventory
InventoryDragEvent      // Drag items across slots
InventoryOpenEvent      // Open inventory
InventoryCloseEvent     // Close inventory
InventoryCreativeEvent  // Creative mode item set

// Block
BlockBreakEvent         // Block broken
BlockPlaceEvent         // Block placed
BlockDamageEvent        // Block being mined
BlockPhysicsEvent       // Block physics update (high frequency)
```

### Entity Events

```java
EntitySpawnEvent        // Entity spawns
EntityDeathEvent        // Entity dies
EntityDamageEvent       // Entity takes damage
EntityTargetEvent       // Entity targets another entity
CreatureSpawnEvent      // Mob spawns (check SpawnReason)
EntityExplodeEvent      // Entity explodes (TNT, Creeper)
```

### World Events

```java
WorldLoadEvent          // World loaded
WorldUnloadEvent        // World unloaded
ChunkLoadEvent          // Chunk loaded
ChunkUnloadEvent        // Chunk unloaded
WeatherChangeEvent      // Weather changes
TimeSkipEvent           // Time skips (bed, command)
```

## Event Handler Details

```java
@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
public void onBlockBreak(BlockBreakEvent event) {
    Player player = event.getPlayer();
    Block block = event.getBlock();

    // Cancel event
    event.setCancelled(true);

    // Drop custom item
    block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(Material.DIAMOND));
}
```

**Priority order**: LOWEST → LOW → NORMAL → HIGH → HIGHEST → MONITOR
- MONITOR: Read-only, use for logging, don't modify/cancel
- ignoreCancelled=true: Skip already-cancelled events

## Player Operations

```java
Player player = Bukkit.getPlayer(uuid);
Player player = Bukkit.getPlayerExact("name");

// Location
Location loc = player.getLocation();
player.teleport(new Location(world, x, y, z, yaw, pitch));

// Inventory
PlayerInventory inv = player.getInventory();
inv.addItem(new ItemStack(Material.DIAMOND_SWORD));
inv.setItem(0, new ItemStack(Material.STONE, 64));
inv.clear();

// Health & Food
player.setHealth(20.0);
player.setMaxHealth(20.0);
player.setFoodLevel(20);
player.setSaturation(5.0f);

// Experience
player.setExp(0.5f);      // Progress bar (0.0-1.0)
player.setLevel(30);       // Level number
player.setTotalExperience(1000);
player.giveExp(100);

// Game mode
player.setGameMode(GameMode.SURVIVAL);
player.setAllowFlight(true);
player.setFlying(true);

// Effects
player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1));
player.removePotionEffect(PotionEffectType.SPEED);

// World & Weather
player.setPlayerWeather(WeatherType.DOWNFALL);
player.setPlayerTime(6000, false);
player.setPlayerListName(Component.text("CustomName"));
```

## World Operations

```java
World world = Bukkit.getWorld("world");

// Blocks
Block block = world.getBlockAt(x, y, z);
block.setType(Material.STONE);
BlockData data = block.getBlockData();
block.setBlockData(data);

// Entities
world.spawnEntity(location, EntityType.ZOMBIE);
world.dropItem(location, new ItemStack(Material.DIAMOND));

// Particles
world.spawnParticle(Particle.FLAME, location, 50, 0.5, 0.5, 0.5, 0.01);

// Sounds
world.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

// Explosion
world.createExplosion(x, y, z, 4.0f, false, true);
```

## Item & ItemStack

```java
// Basic item
ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
ItemMeta meta = item.getItemMeta();
meta.displayName(Component.text("Excalibur").color(NamedTextColor.GOLD));
meta.lore(List.of(
    Component.text("Legendary sword").color(NamedTextColor.GRAY),
    Component.text("+10 Attack").color(NamedTextColor.RED)
));
meta.addEnchant(Enchantment.SHARPNESS, 5, true);
meta.addEnchant(Enchantment.UNBREAKING, 3, true);
meta.setCustomModelData(1001);
item.setItemMeta(meta);

// Potion
ItemStack potion = new ItemStack(Material.POTION);
PotionMeta potionMeta = (PotionMeta) potion.getItemMeta();
potionMeta.addCustomEffect(new PotionEffect(PotionEffectType.HEAL, 1, 0), true);
potionMeta.displayName(Component.text("Healing Potion"));
potion.setItemMeta(potionMeta);

// Player head
ItemStack head = new ItemStack(Material.PLAYER_HEAD);
SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer("Notch"));
head.setItemMeta(skullMeta);

// Written book
ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
BookMeta bookMeta = (BookMeta) book.getItemMeta();
bookMeta.title(Component.text("My Book"));
bookMeta.author(Component.text("Author"));
bookMeta.addPage(Component.text("Page 1 content"));
book.setItemMeta(bookMeta);
```

## Inventory & GUI

```java
// Chest GUI
Inventory inv = Bukkit.createInventory(null, 27, Component.text("My Menu"));
inv.setItem(0, new ItemStack(Material.DIAMOND));
inv.setItem(26, new ItemStack(Material.BARRIER));
player.openInventory(inv);

// Listener for GUI clicks
@EventHandler
public void onInventoryClick(InventoryClickEvent event) {
    if (!event.getView().title().equals(Component.text("My Menu"))) return;
    event.setCancelled(true);

    int slot = event.getSlot();
    Player player = (Player) event.getWhoClicked();

    switch (slot) {
        case 0 -> player.sendMessage(Component.text("You clicked diamond!"));
        case 26 -> player.closeInventory();
    }
}
```

## Scheduler & Async

```java
// Run async (off main thread)
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    // DB, HTTP, file I/O
});

// Run on main thread
Bukkit.getScheduler().runTask(plugin, () -> {
    // World/player operations
});

// Delayed (ticks: 20 = 1 second)
Bukkit.getScheduler().runTaskLater(plugin, () -> {
    // Runs once after delay
}, 100L); // 5 seconds

// Repeating
BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
    // Runs every interval
}, 0L, 200L); // Every 10 seconds

// Cancel task
task.cancel();

// Async then sync
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    String data = loadFromDatabase();
    Bukkit.getScheduler().runTask(plugin, () -> {
        player.sendMessage(Component.text(data));
    });
});
```

## Scoreboard

```java
Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
Objective obj = scoreboard.registerNewObjective("sidebar", Criteria.DUMMY, Component.text("Title"));
obj.setDisplaySlot(DisplaySlot.SIDEBAR);
obj.getScore("Kills").setScore(10);
obj.getScore("Deaths").setScore(5);
player.setScoreboard(scoreboard);
```

## Boss Bar

```java
BossBar bar = Bukkit.createBossBar("Boss Name", BarColor.RED, BarStyle.SOLID);
bar.addPlayer(player);
bar.setProgress(0.5);
bar.setColor(BarColor.GREEN);
bar.setStyle(BarStyle.SEGMENTED_10);
```

## NMS & Packets (Advanced)

Avoid NMS when possible. Use Paper's API or ProtocolLib.

```java
// ProtocolLib (third-party dependency)
PacketAdapter adapter = new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.CHAT) {
    @Override
    public void onPacketSending(PacketEvent event) {
        // Modify outgoing chat packets
    }
};
ProtocolLibrary.getProtocolManager().addPacketListener(adapter);
```
