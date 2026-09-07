# Advanced Patterns & Best Practices

Lessons from experienced Minecraft plugin developers.

## Architecture Patterns

### Service Layer Pattern

Separate business logic from Bukkit API:

```java
// Service interface (testable)
public interface PlayerService {
    void giveReward(Player player, RewardType type);
    Optional<PlayerData> loadData(UUID uuid);
    void saveData(UUID uuid, PlayerData data);
}

// Implementation (Bukkit-aware)
public class PlayerServiceImpl implements PlayerService {
    private final MyPlugin plugin;
    private final DatabaseManager db;
    private final Cache<UUID, PlayerData> cache;

    public PlayerServiceImpl(MyPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();
    }

    @Override
    public void giveReward(Player player, RewardType type) {
        PlayerData data = cache.get(player.getUniqueId(), uuid -> {
            return db.loadData(uuid).orElse(new PlayerData());
        });
        data.addReward(type);
        cache.put(player.getUniqueId(), data);
        
        // Async save
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            db.saveData(player.getUniqueId(), data);
        });
    }
}
```

### Repository Pattern

Abstract data access:

```java
public interface PlayerRepository {
    Optional<PlayerData> findById(UUID uuid);
    void save(PlayerData data);
    void delete(UUID uuid);
}

public class MySQLPlayerRepository implements PlayerRepository {
    private final HikariDataSource dataSource;

    public MySQLPlayerRepository(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<PlayerData> findById(UUID uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM player_data WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load player", e);
        }
    }

    private PlayerData mapRow(ResultSet rs) throws SQLException {
        return new PlayerData(
            UUID.fromString(rs.getString("uuid")),
            rs.getInt("coins"),
            rs.getInt("level")
        );
    }
}
```

### Dependency Injection (Manual)

Avoid static access, use constructor injection:

```java
public final class MyPlugin extends JavaPlugin {
    private PlayerService playerService;
    private EconomyService economyService;

    @Override
    public void onEnable() {
        DatabaseManager db = new DatabaseManager(this);
        playerService = new PlayerServiceImpl(this, db);
        economyService = new EconomyServiceImpl(this);
        
        getServer().getPluginManager().registerEvents(
            new PlayerJoinListener(playerService, economyService), this
        );
        getCommand("reward").setExecutor(
            new RewardCommand(playerService)
        );
    }
}
```

## Cross-Plugin Communication

### Vault Integration (Economy)

```xml
<dependency>
    <groupId>com.github.MilkBowl</groupId>
    <artifactId>VaultAPI</artifactId>
    <version>1.7</version>
    <scope>provided</scope>
</dependency>
```

```java
public class EconomyManager {
    private static Economy economy;

    public static boolean setup() {
        RegisteredServiceProvider<Economy> rsp = 
            Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public static boolean deposit(Player player, double amount) {
        return economy.depositPlayer(player, amount).transactionSuccess();
    }

    public static boolean withdraw(Player player, double amount) {
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public static double getBalance(Player player) {
        return economy.getBalance(player);
    }
}

// In onEnable
if (getServer().getPluginManager().getPlugin("Vault") != null) {
    if (!EconomyManager.setup()) {
        getLogger().warning("Vault economy not found!");
    }
}
```

### Service Provider Pattern

Expose your plugin's API:

```java
// API interface (in separate package)
public interface MyPluginAPI {
    void giveReward(Player player, int amount);
    int getBalance(Player player);
}

// Implementation
public class MyPluginAPIImpl implements MyPluginAPI {
    private final PlayerService playerService;

    public MyPluginAPIImpl(PlayerService playerService) {
        this.playerService = playerService;
    }

    @Override
    public void giveReward(Player player, int amount) {
        playerService.giveReward(player, RewardType.COINS, amount);
    }

    @Override
    public int getBalance(Player player) {
        return playerService.getBalance(player.getUniqueId());
    }
}

// Register in onEnable
getServer().getServicesManager().register(
    MyPluginAPI.class,
    new MyPluginAPIImpl(playerService),
    this,
    ServicePriority.Normal
);

// Other plugins consume
RegisteredServiceProvider<MyPluginAPI> rsp = 
    Bukkit.getServicesManager().getRegistration(MyPluginAPI.class);
if (rsp != null) {
    MyPluginAPI api = rsp.getProvider();
    api.giveReward(player, 100);
}
```

### PlaceholderAPI Integration

```xml
<dependency>
    <groupId>me.clip</groupId>
    <artifactId>placeholderapi</artifactId>
    <version>2.11.6</version>
    <scope>provided</scope>
</dependency>
```

```java
public class MyPlaceholderExpansion extends PlaceholderExpansion {
    private final MyPlugin plugin;

    public MyPlaceholderExpansion(MyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() { return "myplugin"; }

    @Override
    public String getAuthor() { return plugin.getDescription().getAuthors().get(0); }

    @Override
    public String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) return "";
        
        return switch (params.toLowerCase()) {
            case "balance" -> String.valueOf(plugin.getPlayerService().getBalance(player));
            case "level" -> String.valueOf(plugin.getPlayerService().getLevel(player));
            case "rank" -> plugin.getPlayerService().getRank(player);
            default -> null;
        };
    }
}

// In onEnable
if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
    new MyPlaceholderExpansion(this).register();
}
```

## Performance Optimization

### Caching Strategies

```java
// Caffeine cache (add dependency)
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.1.8</version>
</dependency>

// Cache player data
private final Cache<UUID, PlayerData> playerCache = Caffeine.newBuilder()
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .maximumSize(1000)
    .build();

// Load with cache
public PlayerData getPlayerData(UUID uuid) {
    return playerCache.get(uuid, id -> {
        return database.loadPlayerData(id).orElse(new PlayerData(id));
    });
}

// Invalidate on update
public void updatePlayerData(UUID uuid, PlayerData data) {
    playerCache.put(uuid, data);
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
        database.savePlayerData(data);
    });
}
```

### Avoid Common Performance Pitfalls

```java
// ❌ BAD: Repeated expensive lookups
for (Player p : Bukkit.getOnlinePlayers()) {
    if (p.hasPermission("myplugin.vip")) {
        if (Bukkit.getPlayer(p.getUniqueId()) != null) { // redundant!
            // ...
        }
    }
}

// ✅ GOOD: Cache and reuse
for (Player p : Bukkit.getOnlinePlayers()) {
    if (p.hasPermission("myplugin.vip")) {
        // p is already non-null, no need to check again
        // ...
    }
}

// ❌ BAD: String concatenation in loops
String message = "";
for (String line : lines) {
    message += line + "\n"; // Creates new String each time
}

// ✅ GOOD: StringBuilder
StringBuilder sb = new StringBuilder();
for (String line : lines) {
    sb.append(line).append("\n");
}
String message = sb.toString();

// ❌ BAD: Sync database on main thread
public void onPlayerJoin(PlayerJoinEvent event) {
    PlayerData data = database.load(event.getPlayer().getUniqueId()); // LAG!
}

// ✅ GOOD: Async load
public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
        PlayerData data = database.load(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.sendMessage(Component.text("Welcome back!"));
        });
    });
}
```

### Batch Operations

```java
// ❌ BAD: Individual saves
for (Player p : Bukkit.getOnlinePlayers()) {
    database.save(p.getUniqueId(), getData(p));
}

// ✅ GOOD: Batch save
public void saveAllPlayers() {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO player_data (uuid, data) VALUES (?, ?)")) {
            conn.setAutoCommit(false);
            for (Player p : Bukkit.getOnlinePlayers()) {
                ps.setString(1, p.getUniqueId().toString());
                ps.setString(2, serialize(getData(p)));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            plugin.getLogger().severe("Batch save failed: " + e.getMessage());
        }
    });
}
```

## Anti-Patterns to Avoid

### 1. Static Plugin Instance Abuse

```java
// ❌ BAD: Static everything
public class MyPlugin extends JavaPlugin {
    private static MyPlugin instance;
    public static MyPlugin getInstance() { return instance; }
}

// Later in code
MyPlugin.getInstance().getSomeManager().doSomething();

// ✅ GOOD: Pass instances explicitly
public class MyListener implements Listener {
    private final MyPlugin plugin;
    
    public MyListener(MyPlugin plugin) {
        this.plugin = plugin;
    }
}
```

### 2. Memory Leaks

```java
// ❌ BAD: Static map that grows forever
public static Map<UUID, PlayerData> data = new HashMap<>();

// ✅ GOOD: Clean up on disconnect
@EventHandler
public void onQuit(PlayerQuitEvent event) {
    PlayerData data = playerDataMap.remove(event.getPlayer().getUniqueId());
    if (data != null) {
        database.save(data); // Save before removing
    }
}

// ✅ GOOD: Use weak references for caches
private final Map<UUID, WeakReference<Player>> playerRefs = new ConcurrentHashMap<>();
```

### 3. Ignoring Event Cancellation

```java
// ❌ BAD: Not checking if event is already cancelled
@EventHandler
public void onBlockBreak(BlockBreakEvent event) {
    event.getPlayer().sendMessage("You broke a block!");
    // Runs even if another plugin cancelled it
}

// ✅ GOOD: Check cancellation
@EventHandler(ignoreCancelled = true)
public void onBlockBreak(BlockBreakEvent event) {
    event.getPlayer().sendMessage("You broke a block!");
}
```

### 4. Blocking the Main Thread

```java
// ❌ BAD: File I/O on main thread
@EventHandler
public void onJoin(PlayerJoinEvent event) {
    try {
        String data = Files.readString(Paths.get("data.txt")); // LAG!
    } catch (IOException e) {
        e.printStackTrace();
    }
}

// ✅ GOOD: Async file I/O
@EventHandler
public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
        try {
            String data = Files.readString(Paths.get("data.txt"));
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(Component.text(data));
            });
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to read data: " + e.getMessage());
        }
    });
}
```

### 5. Hardcoded Values

```java
// ❌ BAD: Magic numbers
player.setHealth(20.0);
Bukkit.getScheduler().runTaskLater(plugin, task, 100L);

// ✅ GOOD: Constants or config
private static final double MAX_HEALTH = 20.0;
private static final long DELAY_TICKS = 100L;

player.setHealth(MAX_HEALTH);
Bukkit.getScheduler().runTaskLater(plugin, task, DELAY_TICKS);
```

## Testing with MockBukkit

```xml
<dependency>
    <groupId>com.github.seeseemelk</groupId>
    <artifactId>MockBukkit-v1.21</artifactId>
    <version>3.0.0</version>
    <scope>test</scope>
</dependency>
```

```java
class MyCommandTest {
    private ServerMock server;
    private MyPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.loadPlugin(MyPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testCommandExecution() {
        PlayerMock player = server.addPlayer();
        server.executeCommand(player, "mycommand sub1");
        
        player.assertSucceeded();
        player.assertSaid("You executed sub1!");
    }

    @Test
    void testPermissionDenied() {
        PlayerMock player = server.addPlayer();
        player.removePermission("myplugin.use");
        
        server.executeCommand(player, "mycommand");
        
        player.assertSaid("You don't have permission!");
    }

    @Test
    void testEventHandling() {
        PlayerMock player = server.addPlayer();
        Block block = server.addSimpleWorld("world").getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        
        player.simulateBlockBreak(block);
        
        // Verify your listener handled it
        assertTrue(plugin.getListener().wasBlockBroken());
    }
}
```

## Multi-Version Compatibility

```java
// Check version at runtime
public class VersionUtil {
    private static final int[] VERSION;

    static {
        String ver = Bukkit.getBukkitVersion().split("-")[0];
        VERSION = Arrays.stream(ver.split("\\."))
            .mapToInt(Integer::parseInt)
            .toArray();
    }

    public static boolean isAtLeast(int major, int minor) {
        if (VERSION[0] > major) return true;
        if (VERSION[0] == major) return VERSION[1] >= minor;
        return false;
    }
}

// Usage
if (VersionUtil.isAtLeast(1, 21)) {
    // Use 1.21+ features
    player.sendEquipmentChange(...);
} else {
    // Fallback for older versions
    // ...
}
```

## Packet Handling (Advanced)

### ProtocolLib

```xml
<dependency>
    <groupId>com.comphenix.protocol</groupId>
    <artifactId>ProtocolLib</artifactId>
    <version>5.3.0</version>
    <scope>provided</scope>
</dependency>
```

```java
public class PacketListener {
    private final MyPlugin plugin;

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(plugin, ListenerPriority.NORMAL, 
                PacketType.Play.Server.CHAT) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    // Modify outgoing chat packets
                    PacketContainer packet = event.getPacket();
                    // ... modify packet data
                }
            }
        );
    }
}
```

### PacketEvents (Modern Alternative)

```xml
<dependency>
    <groupId>com.github.retrooper</groupId>
    <artifactId>packetevents-spigot</artifactId>
    <version>2.7.0</version>
    <scope>provided</scope>
</dependency>
```

```java
public class MyPacketListener extends PacketListenerAbstract {
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == ClientboundPackets.ServerData) {
            // Handle packet
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == ServerboundPackets.ChatCommand) {
            // Intercept player commands before server processes
        }
    }
}

// Register
PacketEvents.getAPI().getEventManager().registerListener(new MyPacketListener());
```

## GUI Framework Patterns

### Inventory GUI Builder

```java
public class GUIMenu {
    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    public GUIMenu(String title, int rows) {
        this.inventory = Bukkit.createInventory(null, rows * 9, 
            Component.text(title));
    }

    public GUIMenu setItem(int slot, ItemStack item, Consumer<Player> action) {
        inventory.setItem(slot, item);
        actions.put(slot, action);
        return this;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    public static class Listener implements org.bukkit.event.Listener {
        private static final Map<Inventory, GUIMenu> menus = new WeakHashMap<>();

        public static void register(GUIMenu menu) {
            menus.put(menu.inventory, menu);
        }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            GUIMenu menu = menus.get(event.getInventory());
            if (menu == null) return;
            
            event.setCancelled(true);
            Consumer<Player> action = menu.actions.get(event.getSlot());
            if (action != null && event.getWhoClicked() instanceof Player player) {
                action.accept(player);
            }
        }
    }
}

// Usage
GUIMenu menu = new GUIMenu("Shop", 3)
    .setItem(10, diamond, p -> p.sendMessage("Bought diamond!"))
    .setItem(12, gold, p -> p.sendMessage("Bought gold!"));
GUIMenu.Listener.register(menu);
menu.open(player);
```

## Folia Compatibility

Folia uses regionalized threading. Consider these patterns:

```java
// ❌ BAD: Global scheduler (doesn't work on Folia)
Bukkit.getScheduler().runTaskTimer(plugin, task, 0L, 100L);

// ✅ GOOD: Entity/region-aware scheduling
entity.getScheduler().run(plugin, t -> {
    // Runs in entity's region thread
}, () -> {
    // Cancelled
}, 100L);

// ✅ GOOD: Use Folia-aware libraries
// Or check if Folia and adapt
public static boolean isFolia() {
    try {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
        return true;
    } catch (ClassNotFoundException e) {
        return false;
    }
}
```

## Key Takeaways from Top Developers

1. **Separate concerns**: Service layer, repository pattern, don't mix Bukkit API with business logic
2. **Cache aggressively**: Use Caffeine for player data, avoid repeated DB/file reads
3. **Async everything**: DB, file I/O, HTTP → never on main thread
4. **Clean up resources**: Remove from maps on quit, close connections in onDisable
5. **Use modern APIs**: Adventure Components, Paper's enhanced API, avoid NMS
6. **Test with MockBukkit**: Unit test commands, events, managers
7. **Expose APIs**: Use service provider pattern for cross-plugin communication
8. **Batch operations**: Use batch SQL, avoid individual saves in loops
9. **Version check**: Support multiple MC versions with runtime checks
10. **Profile before optimizing**: Use Timings, Spark, or VisualVM to find real bottlenecks

## Resources

- [MockBukkit GitHub](https://github.com/MockBukkit/MockBukkit)
- [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/)
- [PacketEvents](https://github.com/retrooper/packetevents)
- [Caffeine Cache](https://github.com/ben-manes/caffeine)
- [Inventory Framework](https://github.com/stefvanschie/IF)
