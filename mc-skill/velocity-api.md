# Velocity API Reference

Velocity is a modern Minecraft proxy server. Plugins run on the proxy, not on backend servers.

## Plugin Lifecycle

```java
@Plugin(
    id = "myplugin",
    name = "MyPlugin",
    version = "1.0.0",
    description = "My Velocity plugin",
    url = "https://example.com",
    authors = {"Author"}
)
public class MyPlugin {
    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public MyPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Plugin enabled
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        // Plugin disabled
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        // /velocity reload executed
    }
}
```

## Dependency Injection

Velocity uses Guice for DI. Inject services:

```java
@Inject
public MyPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
    this.server = server;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
}
```

Available injections:
- `ProxyServer` - Main server instance
- `Logger` - Plugin logger
- `@DataDirectory Path` - Plugin data folder
- `EventManager` - Event system
- `CommandManager` - Command registration
- `Scheduler` - Task scheduler

## Events

### Connection Events

```java
@Subscribe
public void onLogin(LoginEvent event) {
    // Player logging in (can cancel)
    event.setResult(LoginEvent.ComponentResult.denied(Component.text("Banned!")));
}

@Subscribe
public void onPostLogin(PostLoginEvent event) {
    Player player = event.getPlayer();
    logger.info(player.getUsername() + " logged in");
}

@Subscribe
public void onDisconnect(DisconnectEvent event) {
    Player player = event.getPlayer();
    logger.info(player.getUsername() + " disconnected");
}

@Subscribe
public void onKicked(PlayerKickedEvent event) {
    Player player = event.getPlayer();
    Component reason = event.getReason().orElse(Component.text("No reason"));
}
```

### Server Switch Events

```java
@Subscribe
public void onServerPreConnect(ServerPreConnectEvent event) {
    Player player = event.getPlayer();
    // Redirect to different server
    event.setResult(ServerPreConnectEvent.ServerResult.allowed(
        server.getServer("lobby").orElse(null)
    ));
}

@Subscribe
public void onServerPostConnect(ServerPostConnectEvent event) {
    Player player = event.getPlayer();
    RegisteredServer currentServer = player.getCurrentServer()
        .map(ServerConnection::getServer)
        .orElse(null);
}

@Subscribe
public void onServerConnected(ServerConnectedEvent event) {
    Player player = event.getPlayer();
    RegisteredServer server = event.getServer();
}
```

### Chat Events

```java
@Subscribe
public void onPlayerChat(PlayerChatEvent event) {
    Player player = event.getPlayer();
    String message = event.getMessage();

    // Cancel and send custom format
    event.setResult(PlayerChatEvent.ChatResult.denied());
    player.sendMessage(Component.text("[Chat] " + player.getUsername() + ": " + message));
}
```

### Tab Complete

```java
@Subscribe
public void onTabComplete(TabCompleteEvent event) {
    // Custom tab completion
}
```

## Commands

```java
public class MyCommand implements SimpleCommand {
    private final MyPlugin plugin;

    public MyCommand(MyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (source instanceof Player player) {
            player.sendMessage(Component.text("Hello!"));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("sub1", "sub2", "help");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("myplugin.command");
    }
}
```

### Registration

```java
// In onProxyInitialization
server.getCommandManager().register(
    server.getCommandManager().metaBuilder("mycommand")
        .aliases("mc")
        .build(),
    new MyCommand(this)
);
```

## Player Operations

```java
Player player = server.getPlayer("username").orElse(null);
Player player = server.getPlayer(uuid).orElse(null);

// Send message
player.sendMessage(Component.text("Hello!"));

// Kick
player.disconnect(Component.text("Kicked!"));

// Switch server
server.getServer("lobby").ifPresent(lobby -> {
    player.createConnectionRequest(lobby).fireAndForget();
});

// Permissions
boolean hasPerm = player.hasPermission("myplugin.admin");

// Connection info
Optional<ServerConnection> conn = player.getCurrentServer();
conn.ifPresent(c -> {
    RegisteredServer server = c.getServer();
    logger.info("Player is on: " + server.getServerInfo().getName());
});
```

## Server Operations

```java
// Get server
Optional<RegisteredServer> lobby = server.getServer("lobby");

// All servers
Collection<RegisteredServer> servers = server.getAllServers();

// Register server
ServerInfo info = server.getServerInfoBuilder()
    .name("survival")
    .address(new InetSocketAddress("localhost", 25566))
    .build();
server.registerServer(info);

// Send message to all players on server
lobby.ifPresent(s -> {
    s.sendPlayerMessage(Component.text("Server message!"));
});
```

## Scheduler

```java
// Delayed task
server.getScheduler()
    .buildTask(plugin, () -> {
        logger.info("Delayed task");
    })
    .delay(5, TimeUnit.SECONDS)
    .schedule();

// Repeating task
ScheduledTask task = server.getScheduler()
    .buildTask(plugin, () -> {
        // Periodic work
    })
    .repeat(10, TimeUnit.SECONDS)
    .schedule();

// Cancel task
task.cancel();

// Async task (all Velocity tasks are async by default)
server.getScheduler()
    .buildTask(plugin, () -> {
        // Database, HTTP, etc.
    })
    .schedule();
```

## Configuration

```java
// velocity.toml is for proxy config
// Use custom config for plugin config

private final Path configDir;

@Inject
public MyPlugin(@DataDirectory Path dataDirectory) {
    this.configDir = dataDirectory;
}

public void loadConfig() {
    Path configFile = configDir.resolve("config.conf");
    if (!Files.exists(configFile)) {
        try (InputStream in = getClass().getResourceAsStream("/config.conf")) {
            Files.copy(in, configFile);
        } catch (IOException e) {
            logger.error("Failed to save default config", e);
        }
    }
    // Use HOCON format (Velocity standard)
    // Add Sponge Config dependency
}
```

### HOCON Config (Sponge Config)

```xml
<!-- Maven dependency -->
<dependency>
    <groupId>org.spongepowered</groupId>
    <artifactId>configurate-hocon</artifactId>
    <version>4.1.2</version>
</dependency>
```

```java
// Load HOCON config
Loader<ConfigurationNode> loader = HoconConfigurationLoader.builder()
    .path(configDir.resolve("config.conf"))
    .build();

ConfigurationNode root = loader.load();
String value = root.node("settings", "max-players").getString("100");
```

## Plugin Messaging (BungeeCord/Velocity)

Communicate between proxy and backend servers:

```java
// Register channel
server.getChannelRegistrar().register(MinecraftChannelIdentifier.from("myplugin:main"));

// Send to server
player.getCurrentServer().ifPresent(conn -> {
    conn.sendPluginMessage(
        MinecraftChannelIdentifier.from("myplugin:main"),
        ByteArrayDataOutputStream // serialize data
    );
});

// Listen for messages
@Subscribe
public void onPluginMessage(PluginMessageEvent event) {
    if (!event.getIdentifier().equals(MinecraftChannelIdentifier.from("myplugin:main"))) return;
    // Handle message
}
```

## Best Practices

1. **All tasks are async**: Velocity runs on Netty event loop, never block
2. **Use CompletableFuture**: For async operations
3. **Null safety**: Use Optional for nullable returns
4. **Component API**: Use Adventure Components, not legacy strings
5. **Event priorities**: Use `@Subscribe(order = PostOrder.LATE)` for ordering
6. **Permission defaults**: Define in `@Plugin` or check programmatically
7. **Server switching**: Handle failures, don't assume server exists
8. **Player tracking**: Use PostLoginEvent/DisconnectEvent, not join/quit
