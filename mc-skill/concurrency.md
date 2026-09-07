# Concurrency Patterns for MC Plugins

Advanced threading patterns for Minecraft plugin development, including virtual threads and Folia compatibility.

## The Threading Model

**Paper/Spigot**: Single main thread for game logic. Async tasks run on a shared pool.
**Folia**: Regionalized threading — each region has its own thread. No global scheduler.
**Velocity**: Fully async — all operations run on Netty event loop threads.

```
Main Thread (game logic)
├── World ticking
├── Entity updates
├── Player interactions
├── Most Bukkit API calls
└── Event handlers

Async Pool (background work)
├── Database operations
├── File I/O
├── HTTP requests
├── JSON parsing
└── Heavy computation
```

## CompletableFuture Patterns

### Basic Async → Sync Bridge

```java
public class AsyncBridge {
    private final MyPlugin plugin;

    public AsyncBridge(MyPlugin plugin) { this.plugin = plugin; }

    public <T> void runAsyncThenSync(
            Supplier<T> asyncWork,
            Consumer<T> syncCallback) {
        CompletableFuture.supplyAsync(asyncWork)
            .thenAccept(result -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    syncCallback.accept(result);
                });
            })
            .exceptionally(ex -> {
                plugin.getLogger().severe("Async error: " + ex.getMessage());
                return null;
            });
    }
}

// Usage
asyncBridge.runAsyncThenSync(
    () -> database.loadPlayerData(player.getUniqueId()),
    data -> player.sendMessage(Component.text("Loaded: " + data))
);
```

### Parallel Data Loading

```java
public CompletableFuture<PlayerProfile> loadProfile(UUID uuid) {
    CompletableFuture<PlayerData> data = CompletableFuture.supplyAsync(() ->
        database.loadPlayerData(uuid).orElse(PlayerData.defaultFor(uuid)));

    CompletableFuture<List<Pet>> pets = CompletableFuture.supplyAsync(() ->
        database.loadPets(uuid));

    CompletableFuture<Map<String, Integer>> stats = CompletableFuture.supplyAsync(() ->
        database.loadStats(uuid));

    return CompletableFuture.allOf(data, pets, stats)
        .thenApply(v -> new PlayerProfile(data.join(), pets.join(), stats.join()));
}
```

### Chained Async Operations

```java
public CompletableFuture<Void> processPurchase(Player player, ShopItem item, int qty) {
    return checkBalance(player, item.getPrice() * qty)
        .thenCompose(balance -> {
            if (balance < item.getPrice() * qty) {
                return CompletableFuture.failedFuture(
                    new InsufficientFundsException(item.getPrice() * qty, balance));
            }
            return CompletableFuture.completedFuture(balance);
        })
        .thenCompose(balance -> withdrawAsync(player, item.getPrice() * qty))
        .thenCompose(txId -> giveItemAsync(player, item, qty)
            .thenApply(inv -> new Transaction(txId, player.getUniqueId(), item, qty)))
        .thenAccept(tx -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(Component.text("Purchased " + item.getName() + " x" + qty,
                    NamedTextColor.GREEN));
            });
            database.saveTransaction(tx);
        });
}
```

### Timeout and Retry

```java
public <T> CompletableFuture<T> withRetry(
        Supplier<CompletableFuture<T>> action,
        int maxRetries,
        Duration delay) {
    return action.get().exceptionallyCompose(ex -> {
        if (maxRetries <= 0) return CompletableFuture.failedFuture(ex);
        return CompletableFuture.supplyAsync(() -> null)
            .thenCompose(v -> {
                try { Thread.sleep(delay.toMillis()); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return withRetry(action, maxRetries - 1, delay);
            });
    });
}

// Usage: retry DB load up to 3 times with 1s delay
withRetry(() -> loadPlayerData(uuid), 3, Duration.ofSeconds(1))
    .thenAccept(data -> { /* use data */ })
    .exceptionally(ex -> {
        plugin.getLogger().severe("All retries failed for " + uuid);
        return null;
    });
```

## Virtual Threads (Java 21+)

Virtual threads are lightweight, OS-thread-like concurrency — ideal for blocking I/O tasks like DB and HTTP calls.

```java
// Create a virtual thread executor for the plugin
public class VirtualThreadManager {
    private final ExecutorService executor;

    public VirtualThreadManager(MyPlugin plugin) {
        this.executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("myplugin-", 0).factory()
        );
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    public CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, executor);
    }

    public void shutdown() {
        executor.shutdown();
    }
}

// Usage: thousands of concurrent DB lookups without thread pool exhaustion
public CompletableFuture<Map<UUID, PlayerData>> loadAllPlayerData(List<UUID> uuids) {
    Map<UUID, CompletableFuture<PlayerData>> futures = uuids.stream()
        .collect(Collectors.toMap(
            uuid -> uuid,
            uuid -> virtualThreadManager.supplyAsync(() ->
                database.loadPlayerData(uuid).orElse(PlayerData.defaultFor(uuid)))
        ));

    return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new))
        .thenApply(v -> futures.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().join())));
}
```

**When to use virtual threads vs platform threads:**
- **Virtual threads**: Many concurrent blocking I/O operations (DB, HTTP, file reads)
- **Platform threads**: CPU-intensive work (pathfinding, world generation calculations)
- **Bukkit scheduler**: When you need to return to the main thread for game API calls

## Structured Concurrency (Java 21+ Preview)

```java
// Structured concurrency for related async tasks
public PlayerProfile loadProfileStructured(UUID uuid) throws Exception {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        Subtask<PlayerData> dataTask = scope.fork(() ->
            database.loadPlayerData(uuid).orElse(PlayerData.defaultFor(uuid)));
        Subtask<List<Pet>> petsTask = scope.fork(() ->
            database.loadPets(uuid));
        Subtask<Map<String, Integer>> statsTask = scope.fork(() ->
            database.loadStats(uuid));

        scope.join();
        scope.throwIfFailed();

        return new PlayerProfile(dataTask.get(), petsTask.get(), statsTask.get());
    }
}
```

## Folia-Specific Patterns

### Entity-Aware Scheduling

```java
// Folia: schedule on the entity's region thread
public void scheduleForEntity(Entity entity, Runnable task) {
    entity.getScheduler().run(plugin, scheduledTask -> {
        task.run();
    }, () -> {
        // Called if the task is cancelled (entity removed, etc.)
        plugin.getLogger().info("Task cancelled for entity " + entity.getName());
    }, 0L);
}

// Delayed entity task
public void scheduleDelayedForEntity(Entity entity, Runnable task, long delayTicks) {
    entity.getScheduler().runDelayed(plugin, scheduledTask -> {
        task.run();
    }, () -> {}, delayTicks);
}

// Repeating entity task
public ScheduledTask scheduleRepeatingForEntity(Entity entity, Runnable task,
        long delayTicks, long periodTicks) {
    return entity.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
        task.run();
    }, () -> {}, delayTicks, periodTicks);
}
```

### Region-Aware Scheduling

```java
// Schedule on a specific region (by location)
public void scheduleForRegion(Location location, Runnable task) {
    Bukkit.getRegionScheduler().run(plugin, location, scheduledTask -> {
        task.run();
    });
}

// Repeating region task
public ScheduledTask scheduleRepeatingForRegion(Location location, Runnable task,
        long delayTicks, long periodTicks) {
    return Bukkit.getRegionScheduler().runAtFixedRate(plugin, location,
        scheduledTask -> task.run(), delayTicks, periodTicks);
}

// Global (non-region-specific) async task
public void scheduleGlobalAsync(Runnable task) {
    Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> {
        task.run();
    });
}
```

### Folia Compatibility Layer

```java
public class SchedulerAdapter {
    private final MyPlugin plugin;
    private final boolean folia;

    public SchedulerAdapter(MyPlugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public void runTask(Runnable task) {
        if (folia) {
            // On Folia, must specify location or entity
            throw new UnsupportedOperationException(
                "Use runAtLocation() or runForEntity() on Folia");
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    public void runAtLocation(Location loc, Runnable task) {
        if (folia) {
            Bukkit.getRegionScheduler().run(plugin, loc, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runForEntity(Entity entity, Runnable task) {
        if (folia) {
            entity.getScheduler().run(plugin, t -> task.run(), () -> {}, 0L);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runAsync(Runnable task) {
        if (folia) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public void runDelayed(Runnable task, long delayTicks) {
        if (folia) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(),
                delayTicks * 50, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }
}
```

## Thread Safety Patterns

### Immutable Data Sharing

```java
// Share data between threads safely using immutable records
public record PlayerSnapshot(UUID uuid, String name, int level, long lastSeen) {}

// Create snapshot on main thread, process on async thread
public void snapshotAndProcess(Player player) {
    PlayerSnapshot snapshot = new PlayerSnapshot(
        player.getUniqueId(),
        player.getName(),
        levelManager.getLevel(player),
        System.currentTimeMillis()
    );

    CompletableFuture.runAsync(() -> {
        // Safe: snapshot is immutable
        database.saveSnapshot(snapshot);
    });
}
```

### Concurrent Collections

```java
// Thread-safe player data map
private final ConcurrentMap<UUID, PlayerData> playerData = new ConcurrentHashMap<>();

// Atomic operations
public PlayerData getOrCreate(UUID uuid) {
    return playerData.computeIfAbsent(uuid, id -> PlayerData.defaultFor(id));
}

// Atomic update
public void updateCoins(UUID uuid, int delta) {
    playerData.compute(uuid, (id, data) -> {
        if (data == null) return PlayerData.defaultFor(id);
        return data.withCoins(data.coins() + delta);
    });
}

// AtomicLong for counters
private final AtomicLong totalRewardsGiven = new AtomicLong(0);

public void recordReward(int amount) {
    totalRewardsGiven.addAndGet(amount);
}
```

### ReadWriteLock for Mixed Access

```java
public class ConfigCache {
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<String, Object> cache = new HashMap<>();

    public Object get(String key) {
        lock.readLock().lock();
        try {
            return cache.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void reload() {
        lock.writeLock().lock();
        try {
            cache = new HashMap<>(loadFromDisk());
        } finally {
            lock.writeLock().unlock();
        }
    }
}
```

## Synchronization Between Async and Main Thread

### Batch Sync Callbacks

Instead of scheduling many individual main-thread tasks, batch them:

```java
public class SyncCallbackBatcher {
    private final MyPlugin plugin;
    private final Queue<Runnable> pendingCallbacks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    public SyncCallbackBatcher(MyPlugin plugin) { this.plugin = plugin; }

    public void submit(Runnable callback) {
        pendingCallbacks.add(callback);
        if (scheduled.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTask(plugin, this::flush);
        }
    }

    private void flush() {
        scheduled.set(false);
        Runnable callback;
        int processed = 0;
        while ((callback = pendingCallbacks.poll()) != null && processed < 100) {
            try {
                callback.run();
            } catch (Exception e) {
                plugin.getLogger().severe("Callback error: " + e.getMessage());
            }
            processed++;
        }
        if (!pendingCallbacks.isEmpty()) {
            submit(() -> {}); // Re-schedule if more remain
        }
    }
}
```

### CompletableFuture → Main Thread Bridge

```java
public class MainThreadBridge {
    private final MyPlugin plugin;

    public MainThreadBridge(MyPlugin plugin) { this.plugin = plugin; }

    public <T> CompletableFuture<T> thenOnMainThread(CompletableFuture<T> future,
            Consumer<T> callback) {
        return future.thenApply(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
            return result;
        });
    }
}

// Usage
mainThreadBridge.thenOnMainThread(
    database.loadPlayerData(uuid),
    data -> player.sendMessage(Component.text("Welcome, " + data.name()))
);
```

## Key Takeaways

1. **Never block the main thread** — DB, file I/O, HTTP → always async
2. **Use CompletableFuture** for chaining async operations with error handling
3. **Virtual threads (Java 21+)** for massive concurrent I/O without thread pool limits
4. **Immutable records** for safe data sharing between threads
5. **ConcurrentHashMap** for thread-safe player data maps
6. **Batch sync callbacks** to avoid flooding the main thread scheduler
7. **Folia**: Use entity/region schedulers, not global Bukkit scheduler
8. **SchedulerAdapter**: Abstract scheduler differences for cross-platform support
9. **ReadWriteLock** when reads outnumber writes significantly
10. **Structured concurrency** (preview) for related async task groups
