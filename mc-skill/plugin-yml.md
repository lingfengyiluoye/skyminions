# plugin.yml Schema Reference

## Required Fields

```yaml
name: MyPlugin              # Plugin name (no spaces, alphanumeric + -_)
version: '${project.version}' # Version string (use Maven filtering)
main: com.example.MyPlugin  # Main class (extends JavaPlugin)
api-version: '1.21'         # MC version (required for 1.13+)
```

## Optional Fields

```yaml
description: My awesome plugin
authors: [Author1, Author2]     # or author: Author1 (single)
website: https://example.com
prefix: MP                      # Chat prefix (deprecated, use Adventure)
load: STARTUP                   # STARTUP or POSTWORLD (default)
loadbefore: [OtherPlugin]       # Load before these plugins
depend: [Vault]                 # Required dependencies (soft fail)
softdepend: [PlaceholderAPI]    # Optional dependencies
provides: [OldPluginName]       # Alias names
```

## Commands

```yaml
commands:
  mycommand:
    description: Base command
    usage: /mycommand <sub>
    aliases: [mc, mycmd]
    permission: myplugin.use
    permission-message: You don't have permission!
  
  admin:
    description: Admin command
    usage: /admin <reload|status>
    permission: myplugin.admin
```

### Command Fields

- `description`: Help text shown in /help
- `usage`: Usage message (deprecated, use in-code messages)
- `aliases`: Alternative command names
- `permission`: Required permission node
- `permission-message`: Shown when lacking permission

## Permissions

```yaml
permissions:
  myplugin.use:
    description: Allows using basic commands
    default: true           # true, false, op, not_op
    children:
      myplugin.command.help: true
      myplugin.command.list: true
  
  myplugin.admin:
    description: Admin access
    default: op
    children:
      myplugin.use: true
      myplugin.command.reload: true
      myplugin.bypass.*: true
  
  myplugin.*:
    description: All permissions
    default: op
    children:
      myplugin.admin: true
```

### Permission Defaults

- `true`: Everyone has it
- `false`: No one has it (must be granted)
- `op`: Only ops have it
- `not_op`: Everyone except ops

### Permission Patterns

```yaml
# Wildcard pattern
permissions:
  myplugin.bypass.cooldown:
    default: false
  myplugin.bypass.cost:
    default: false
  myplugin.bypass.*:
    default: op
    children:
      myplugin.bypass.cooldown: true
      myplugin.bypass.cost: true
```

## Dependencies

```yaml
# Required (plugin won't load without)
depend:
  - Vault
  - WorldGuard

# Optional (load after if present)
softdepend:
  - PlaceholderAPI
  - Essentials

# Load before
loadbefore:
  - SomeOtherPlugin
```

## Paper Plugin YAML (paper-plugin.yml)

Paper 1.19.4+ supports `paper-plugin.yml` for Paper-specific features:

```yaml
name: MyPlugin
version: '${project.version}'
main: com.example.MyPlugin
api-version: '1.21'

# Paper-specific
loader: com.example.MyPluginLoader  # Custom loader
load-order: BEFORE_WORLD            # STARTUP, BEFORE_WORLD, AFTER_WORLD

# Dependencies (Paper format)
dependencies:
  server:
    Vault:
      load-order: BEFORE
      required: true
    PlaceholderAPI:
      load-order: BEFORE
      required: false
  bootstrap: []
```

## Complete Example

```yaml
name: EconomyPlus
version: '${project.version}'
main: com.example.economyplus.EconomyPlus
api-version: '1.21'
authors: [YourName]
description: Advanced economy plugin
website: https://github.com/you/economyplus
prefix: EP

load: POSTWORLD
loadbefore: [ShopPlugin]
depend: [Vault]
softdepend: [PlaceholderAPI, Essentials]

commands:
  balance:
    description: Check balance
    usage: /balance [player]
    aliases: [bal, money]
    permission: economyplus.balance
  
  pay:
    description: Pay another player
    usage: /pay <player> <amount>
    permission: economyplus.pay
  
  economyplus:
    description: Admin commands
    usage: /economyplus <reload|give|take|set>
    permission: economyplus.admin

permissions:
  economyplus.balance:
    description: Check your balance
    default: true
  
  economyplus.pay:
    description: Pay other players
    default: true
  
  economyplus.admin:
    description: Admin commands
    default: op
    children:
      economyplus.balance: true
      economyplus.pay: true
      economyplus.command.reload: true
      economyplus.command.give: true
      economyplus.command.take: true
      economyplus.command.set: true
  
  economyplus.bypass.cooldown:
    description: Bypass transaction cooldown
    default: false
  
  economyplus.bypass.tax:
    description: Bypass transaction tax
    default: false
  
  economyplus.*:
    description: All permissions
    default: op
    children:
      economyplus.admin: true
      economyplus.bypass.*: true
```

## Best Practices

1. **Use Maven filtering**: `version: '${project.version}'` auto-updates from pom.xml
2. **api-version required**: Always set for 1.13+ to enable modern features
3. **Permission hierarchy**: Use children for inheritance, `*` for wildcards
4. **Command aliases**: Provide common aliases for better UX
5. **Soft dependencies**: Use `softdepend` for optional integrations
6. **Load order**: Most plugins use default POSTWORLD, use STARTUP only if needed
7. **No usage field**: Handle messages in code, not in plugin.yml
8. **Prefix deprecated**: Use Adventure Components for formatting
