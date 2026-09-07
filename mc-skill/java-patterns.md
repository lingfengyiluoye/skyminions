# Java Patterns for MC Plugin Development

Modern Java (17+) patterns and practices tailored for Minecraft plugin development.

## Records for Immutable Data

Use records for player data, configuration DTOs, and event payloads:

```java
// Player data transfer object
public record PlayerData(UUID uuid, int coins, int level, String rank) {
    public PlayerData {
        if (coins < 0) throw new IllegalArgumentException("coins < 0: " + coins);
        if (level < 0) throw new IllegalArgumentException("level < 0: " + level);
        rank = rank != null ? rank : "default";
    }

    public PlayerData addCoins(int amount) {
        return new PlayerData(uuid, coins + amount, level, rank);
    }

    public PlayerData levelUp() {
        return new PlayerData(uuid, coins, level + 1, rank);
    }
}

// Usage
PlayerData data = new PlayerData(player.getUniqueId(), 0, 1, "default");
PlayerData updated = data.addCoins(100).levelUp();
```

```java
// Command result
public record CommandResult(boolean success, Component message) {
    public static CommandResult ok(String msg) {
        return new CommandResult(true, Component.text(msg).color(NamedTextColor.GREEN));
    }
    public static CommandResult fail(String msg) {
        return new CommandResult(false, Component.text(msg).color(NamedTextColor.RED));
    }
}
```

## Sealed Classes for Event Hierarchies

Model finite sets of outcomes (reward types, shop categories, permission results):

```java
public sealed interface RewardResult permits RewardSuccess, RewardFailed, RewardCooldown {
    Component message();
}

public record RewardSuccess(String rewardName, int amount) implements RewardResult {
    @Override public Component message() {
        return Component.text("Received " + rewardName + " x" + amount, NamedTextColor.GREEN);
    }
}

public record RewardFailed(String reason) implements RewardResult {
    @Override public Component message() {
        return Component.text("Failed: " + reason, NamedTextColor.RED);
    }
}

public record RewardCooldown(Duration remaining) implements RewardResult {
    @Override public Component message() {
        return Component.text("Cooldown: " + remaining.toSeconds() + "s", NamedTextColor.YELLOW);
    }
}

// Pattern matching (Java 21+)
public void showRewardResult(Player player, RewardResult result) {
    player.sendMessage(switch (result) {
        case RewardSuccess s -> Component.text("+", NamedTextColor.GREEN).append(s.message());
        case RewardFailed f -> Component.text("!", NamedTextColor.RED).append(s.message());
        case RewardCooldown c -> Component.text("...", NamedTextColor.YELLOW).append(c.message());
    });
}
```

## Switch Expressions & Pattern Matching

```java
// Command routing with pattern matching
public CommandResult handleCommand(CommandSender sender, String[] args) {
    return switch (args.length) {
        case 0 -> CommandResult.fail("Usage: /shop <buy|sell|info>");
        case 1 -> switch (args[0].toLowerCase()) {
            case "buy" -> handleBuy(sender);
            case "sell" -> handleSell(sender);
            case "info" -> handleInfo(sender);
            default -> CommandResult.fail("Unknown: " + args[0]);
        };
        default -> CommandResult.fail("Too many arguments");
    };
}

// Type-based dispatch
public String formatValue(Object value) {
    return switch (value) {
        case Integer i -> "int:" + i;
        case Double d -> "double:" + d;
        case String s -> "string:" + s;
        case null, default -> "unknown";
    };
}

// Null-safe with pattern matching (Java 21+)
public Component getPlayerDisplayName(Player player) {
    return switch (player) {
        case null -> Component.text("Unknown", NamedTextColor.GRAY);
        case Player p when p.hasPermission("vip.gold") ->
            Component.text(p.getName()).color(NamedTextColor.GOLD);
        case Player p when p.hasPermission("vip.silver") ->
            Component.text(p.getName()).color(NamedTextColor.WHITE);
        case Player p ->
            Component.text(p.getName()).color(NamedTextColor.GRAY);
    };
}
```

## Stream API for Player Data

```java
// Filter and collect online players
public List<Player> getOnlineVIPs() {
    return Bukkit.getOnlinePlayers().stream()
        .filter(p -> p.hasPermission("myplugin.vip"))
        .filter(Player::isOnline)
        .toList();
}

// Aggregate stats
public DoubleAverageResult getAveragePlayerLevel() {
    return Bukkit.getOnlinePlayers().stream()
        .map(p -> playerDataService.getLevel(p))
        .mapToInt(Integer::intValue)
        .summaryStatistics()
        .getAverage();
}

// Group players by world
public Map<String, List<Player>> getPlayersByWorld() {
    return Bukkit.getOnlinePlayers().stream()
        .collect(Collectors.groupingBy(p -> p.getWorld().getName()));
}

// Find top N players by score
public List<Player> getTopPlayers(int count) {
    return Bukkit.getOnlinePlayers().stream()
        .sorted(Comparator.comparingInt(
            (Player p) -> playerDataService.getScore(p)).reversed())
        .limit(count)
        .toList();
}

// Transform to map
public Map<UUID, String> getOnlinePlayerNames() {
    return Bukkit.getOnlinePlayers().stream()
        .collect(Collectors.toMap(Player::getUniqueId, Player::getName));
}

// Partition players into chunks (for batch operations)
public List<List<Player>> partitionPlayers(int chunkSize) {
    List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
    List<List<Player>> chunks = new ArrayList<>();
    for (int i = 0; i < players.size(); i += chunkSize) {
        chunks.add(players.subList(i, Math.min(i + chunkSize, players.size())));
    }
    return chunks;
}
```

## CompletableFuture for Async Pipelines

```java
// Chain async operations
public CompletableFuture<Void> processPlayerJoin(Player player) {
    return loadPlayerData(player.getUniqueId())
        .thenApplyAsync(data -> {
            if (data == null) return new PlayerData(player.getUniqueId());
            return data;
        })
        .thenAcceptAsync(data -> {
            playerCache.put(player.getUniqueId(), data);
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(Component.text("Data loaded!").color(NamedTextColor.GREEN));
            });
        });
}

// Parallel async loads
public CompletableFuture<PlayerProfile> loadFullProfile(Player player) {
    CompletableFuture<PlayerData> dataFuture = loadPlayerData(player.getUniqueId());
    CompletableFuture<List<Pet>> petsFuture = loadPlayerPets(player.getUniqueId());
    CompletableFuture<Map<String, Integer>> statsFuture = loadPlayerStats(player.getUniqueId());

    return CompletableFuture.allOf(dataFuture, petsFuture, statsFuture)
        .thenApply(v -> new PlayerProfile(
            dataFuture.join(),
            petsFuture.join(),
            statsFuture.join()
        ));
}

// Error recovery
public CompletableFuture<PlayerData> loadPlayerDataSafe(UUID uuid) {
    return loadPlayerData(uuid)
        .exceptionally(ex -> {
            plugin.getLogger().warning("Failed to load data for " + uuid + ": " + ex.getMessage());
            return PlayerData.defaultFor(uuid);
        });
}

// Timeout (Java 9+)
public CompletableFuture<PlayerData> loadWithTimeout(UUID uuid) {
    return loadPlayerData(uuid)
        .orTimeout(5, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            if (ex instanceof TimeoutException) {
                plugin.getLogger().warning("Data load timed out for " + uuid);
            }
            return PlayerData.defaultFor(uuid);
        });
}
```

## Exception Handling Strategies

```java
// Custom exception hierarchy for plugin errors
public class PluginException extends RuntimeException {
    public PluginException(String message) { super(message); }
    public PluginException(String message, Throwable cause) { super(message, cause); }
}

public class DataLoadException extends PluginException {
    private final UUID playerId;
    public DataLoadException(UUID playerId, Throwable cause) {
        super("Failed to load data for player " + playerId, cause);
        this.playerId = playerId;
    }
    public UUID getPlayerId() { return playerId; }
}

public class InsufficientFundsException extends PluginException {
    private final double required, available;
    public InsufficientFundsException(double required, double available) {
        super("Need " + required + " but only have " + available);
        this.required = required;
        this.available = available;
    }
}

// Boundary exception handling pattern
public class SafeExecutor {
    private final Logger logger;

    public SafeExecutor(Logger logger) { this.logger = logger; }

    public void runAsync(MyPlugin plugin, Runnable task, String context) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.severe("Error in async task [" + context + "]: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public CommandResult executeCommand(Supplier<CommandResult> task, String context) {
        try {
            return task.get();
        } catch (InsufficientFundsException e) {
            return CommandResult.fail("Not enough funds (need " + e.getRequired() + ")");
        } catch (PluginException e) {
            logger.warning("Plugin error in " + context + ": " + e.getMessage());
            return CommandResult.fail("Error: " + e.getMessage());
        } catch (Exception e) {
            logger.severe("Unexpected error in " + context + ": " + e.getMessage());
            return CommandResult.fail("Internal error");
        }
    }
}
```

## Text Blocks for Messages

```java
// Multi-line messages (Java 15+)
public class Messages {
    public static final String HELP_MESSAGE = """
        ─── MyPlugin Help ───
        /shop buy <item> - Buy items
        /shop sell <item> - Sell items
        /shop balance - Check balance
        ─────────────────────
        """;

    public static Component formatWelcome(String playerName, int coins) {
        String template = """
            Welcome, %s!
            Your balance: %d coins
            Type /shop help for commands
            """;
        return Component.text(String.format(template, playerName, coins));
    }
}
```

## Builder Pattern for Complex Objects

```java
// Builder for ItemStack creation
public class ItemBuilder {
    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
        this.meta = item.getItemMeta();
    }

    public ItemBuilder name(String name) {
        meta.displayName(Component.text(name).color(NamedTextColor.GOLD));
        return this;
    }

    public ItemBuilder lore(String... lines) {
        List<Component> lore = Arrays.stream(lines)
            .map(l -> Component.text(l).color(NamedTextColor.GRAY))
            .toList();
        meta.lore(lore);
        return this;
    }

    public ItemBuilder enchant(Enchantment enchantment, int level) {
        meta.addEnchant(enchantment, level, true);
        return this;
    }

    public ItemBuilder customModelData(int data) {
        meta.setCustomModelData(data);
        return this;
    }

    public ItemBuilder unbreakable() {
        meta.setUnbreakable(true);
        return this;
    }

    public ItemStack build() {
        item.setItemMeta(meta);
        return item;
    }
}

// Usage
ItemStack sword = new ItemBuilder(Material.DIAMOND_SWORD)
    .name("Legendary Blade")
    .lore("A powerful weapon", "Deals extra damage")
    .enchant(Enchantment.SHARPNESS, 5)
    .enchant(Enchantment.UNBREAKING, 3)
    .unbreakable()
    .build();
```

## Strategy Pattern for Dynamic Behavior

```java
// Reward calculation strategies
public interface RewardStrategy {
    int calculateReward(Player player, String action);
}

public class NormalRewardStrategy implements RewardStrategy {
    @Override
    public int calculateReward(Player player, String action) {
        return switch (action) {
            case "kill_mob" -> 10;
            case "mine_ore" -> 5;
            default -> 1;
        };
    }
}

public class EventRewardStrategy implements RewardStrategy {
    private final double multiplier;
    public EventRewardStrategy(double multiplier) { this.multiplier = multiplier; }

    @Override
    public int calculateReward(Player player, String action) {
        int base = new NormalRewardStrategy().calculateReward(player, action);
        return (int) (base * multiplier);
    }
}

// Context that uses strategy
public class RewardManager {
    private RewardStrategy strategy;

    public void setStrategy(RewardStrategy strategy) {
        this.strategy = strategy;
    }

    public void giveReward(Player player, String action) {
        int amount = strategy.calculateReward(player, action);
        economyManager.deposit(player, amount);
        player.sendMessage(Component.text("+" + amount + " coins", NamedTextColor.GREEN));
    }
}
```

## Observer Pattern with Custom Events

```java
// Custom event for plugin-specific actions
public class PlayerRewardEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final RewardType type;
    private int amount;
    private boolean cancelled;

    public PlayerRewardEvent(Player player, RewardType type, int amount) {
        this.player = player;
        this.type = type;
        this.amount = amount;
    }

    public Player getPlayer() { return player; }
    public RewardType getType() { return type; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

// Fire the event
public void giveReward(Player player, RewardType type, int amount) {
    PlayerRewardEvent event = new PlayerRewardEvent(player, type, amount);
    Bukkit.getPluginManager().callEvent(event);

    if (event.isCancelled()) return;

    economyManager.deposit(player, event.getAmount());
}

// Other plugins can listen
@EventHandler
public void onReward(PlayerRewardEvent event) {
    if (event.getType() == RewardType.DAILY) {
        event.setAmount((int) (event.getAmount() * 1.5)); // 50% bonus during events
    }
}
```

## Optional Patterns

```java
// Return Optional instead of null
public Optional<PlayerData> loadData(UUID uuid) {
    PlayerData cached = cache.getIfPresent(uuid);
    if (cached != null) return Optional.of(cached);

    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT * FROM data WHERE uuid = ?")) {
        ps.setString(1, uuid.toString());
        ResultSet rs = ps.executeQuery();
        return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
    } catch (SQLException e) {
        throw new DataLoadException(uuid, e);
    }
}

// Chain Optional operations
public Component getGreeting(UUID uuid) {
    return loadData(uuid)
        .map(data -> Component.text("Welcome back, " + data.name() + "!"))
        .orElse(Component.text("Welcome, new player!"));
}

// Optional with orElseThrow for required data
public PlayerData requireData(UUID uuid) {
    return loadData(uuid)
        .orElseThrow(() -> new IllegalStateException("No data for player " + uuid));
}
```

## Enum with Behavior

```java
public enum RewardType {
    DAILY("Daily Reward", 100, "myplugin.reward.daily", Duration.ofHours(24)),
    WEEKLY("Weekly Reward", 500, "myplugin.reward.weekly", Duration.ofDays(7)),
    ACHIEVEMENT("Achievement", 1000, "myplugin.reward.achievement", Duration.ZERO);

    private final String displayName;
    private final int baseAmount;
    private final String permission;
    private final Duration cooldown;

    RewardType(String displayName, int baseAmount, String permission, Duration cooldown) {
        this.displayName = displayName;
        this.baseAmount = baseAmount;
        this.permission = permission;
        this.cooldown = cooldown;
    }

    public boolean canClaim(Player player) {
        return player.hasPermission(permission);
    }

    public int calculateAmount(Player player) {
        int multiplier = player.hasPermission("myplugin.reward.double") ? 2 : 1;
        return baseAmount * multiplier;
    }

    public static Optional<RewardType> fromString(String name) {
        try {
            return Optional.of(valueOf(name.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

## Configuration Migration

```java
public class ConfigMigrator {
    private final MyPlugin plugin;

    public ConfigMigrator(MyPlugin plugin) { this.plugin = plugin; }

    public void migrate() {
        FileConfiguration config = plugin.getConfig();
        int version = config.getInt("config-version", 0);

        if (version < 1) migrateToV1(config);
        if (version < 2) migrateToV2(config);
        if (version < 3) migrateToV3(config);

        config.set("config-version", 3);
        plugin.saveConfig();
    }

    private void migrateToV1(FileConfiguration config) {
        // Rename old key
        if (config.contains("old-key")) {
            config.set("settings.new-key", config.get("old-key"));
            config.set("old-key", null);
        }
    }

    private void migrateToV2(FileConfiguration config) {
        // Add new defaults
        config.addDefault("settings.max-retries", 3);
        config.addDefault("settings.timeout-ms", 5000);
    }

    private void migrateToV3(FileConfiguration config) {
        // Restructure section
        ConfigurationSection oldSection = config.getConfigurationSection("rewards");
        if (oldSection != null) {
            for (String key : oldSection.getKeys(false)) {
                config.set("rewards.types." + key, oldSection.get(key));
            }
            config.set("rewards.types", oldSection);
        }
    }
}
```

## bStats Metrics Integration

```xml
<dependency>
    <groupId>org.bstats</groupId>
    <artifactId>bstats-bukkit</artifactId>
    <version>3.0.2</version>
</dependency>
```

```java
public class MetricsManager {
    private final Metrics metrics;

    public MetricsManager(MyPlugin plugin) {
        this.metrics = new Metrics(plugin, 12345); // Replace with your plugin ID

        // Simple pie chart
        metrics.addCustomChart(new DrilldownPie("database_type", () -> {
            String type = plugin.getConfig().getString("database.type", "sqlite");
            return Map.of(type, Map.of("version", type.equals("mysql") ? "8.0" : "3.39"));
        }));

        // Player count
        metrics.addCustomChart(new SingleLineChart("active_players", () -> {
            return Bukkit.getOnlinePlayers().size();
        }));

        // Multi-line chart
        metrics.addCustomChart(new MultiLineChart("server_stats", () -> {
            return Map.of(
                "players", Bukkit.getOnlinePlayers().size(),
                "worlds", Bukkit.getWorlds().size()
            );
        }));
    }
}
```

## Validation with Preconditions

```java
public class Validation {
    public static <T> T requireNonNull(T obj, String paramName) {
        if (obj == null) {
            throw new IllegalArgumentException("'" + paramName + "' must not be null");
        }
        return obj;
    }

    public static int requirePositive(int value, String paramName) {
        if (value <= 0) {
            throw new IllegalArgumentException("'" + paramName + "' must be positive, got: " + value);
        }
        return value;
    }

    public static int requireRange(int value, int min, int max, String paramName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                "'" + paramName + "' must be between " + min + " and " + max + ", got: " + value);
        }
        return value;
    }

    public static void requirePermission(Player player, String permission) {
        if (!player.hasPermission(permission)) {
            throw new PermissionDeniedException(player, permission);
        }
    }
}

// Usage in service
public void giveReward(Player player, RewardType type, int amount) {
    Validation.requireNonNull(player, "player");
    Validation.requireNonNull(type, "type");
    Validation.requirePositive(amount, "amount");
    Validation.requirePermission(player, type.getPermission());

    // ... proceed
}
```

## Logging Patterns

```java
public class PluginLogger {
    private final Logger logger;
    private final String prefix;
    private final boolean debug;

    public PluginLogger(MyPlugin plugin) {
        this.logger = plugin.getLogger();
        this.prefix = "[" + plugin.getDescription().getName() + "] ";
        this.debug = plugin.getConfig().getBoolean("debug", false);
    }

    public void info(String msg, Object... args) {
        logger.info(format(msg, args));
    }

    public void warn(String msg, Object... args) {
        logger.warning(format(msg, args));
    }

    public void error(String msg, Throwable ex) {
        logger.severe(msg + ": " + ex.getMessage());
        if (debug) ex.printStackTrace();
    }

    public void debug(String msg, Object... args) {
        if (debug) logger.info("[DEBUG] " + format(msg, args));
    }

    private String format(String msg, Object... args) {
        return prefix + String.format(msg, args);
    }
}
```

## Key Takeaways

1. **Records** for immutable data (player data, command results, DTOs)
2. **Sealed classes** for finite outcome sets (reward results, shop operations)
3. **Switch expressions** for cleaner command routing and type dispatch
4. **Stream API** for player collection processing and aggregation
5. **CompletableFuture** for chaining async operations with error recovery
6. **Optional** instead of null returns for data lookups
7. **Builder pattern** for complex object construction (ItemStack, Configuration)
8. **Strategy pattern** for swappable game logic (reward calculations, event modifiers)
9. **Custom events** for extensibility and cross-plugin communication
10. **Text blocks** for readable multi-line messages
11. **Config migration** for smooth version upgrades
12. **bStats** for anonymous usage metrics to guide development priorities
