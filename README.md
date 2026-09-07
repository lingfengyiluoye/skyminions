<div align="center">

# 🏰 SkyMinions

**Hypixel SkyBlock 风格 · Paper 1.21+ 仆从插件**

[![Build & Test](https://github.com/lingfengyiluoye/skyminions/actions/workflows/build.yml/badge.svg)](https://github.com/lingfengyiluoye/skyminions/actions/workflows/build.yml)
[![Paper 1.21+](https://img.shields.io/badge/Paper-1.21+-blue?logo=appveyor)](https://papermc.io/)
[![Java 21](https://img.shields.io/badge/Java-21+-orange?logo=openjdk)](https://adoptium.net/)
[![Version](https://img.shields.io/badge/Version-1.0.0-green)](https://github.com/lingfengyiluoye/skyminions/releases)
[![License](https://img.shields.io/badge/License-Private-red)](#)

*盔甲架小人放置后自动工作，真实箱子仓库，Hypixel 式升级合成 / 燃料系统 / 模块槽 / 图鉴收集 / 里程碑奖励 / 离线收益 — 全部配置驱动、热重载生效。*

**44 种仆从** · **7 种行为** · **12 种模块** · **50+ 附魔资源** · **零硬编码**

</div>

---

## 📑 目录

- [✨ 特性总览](#-特性总览)
- [🎮 快速开始](#-快速开始)
- [📖 命令参考](#-命令参考)
- [🔐 权限系统](#-权限系统)
- [🪣 燃料系统](#-燃料系统)
- [💎 附魔资源](#-附魔资源)
- [📦 升级模块](#-升级模块)
- [🏆 Collection 里程碑](#-collection-里程碑)
- [😴 离线收益](#-离线收益)
- [⚙️ 配置说明](#️-配置说明)
- [🏗️ 架构设计](#️-架构设计)
- [🔧 构建与安装](#-构建与安装)
- [🤝 可选依赖](#-可选依赖)
- [❓ FAQ](#-faq)
- [📄 许可证](#-许可证)

---

## ✨ 特性总览

<table>
<tr>
<td width="50%">

### 🤖 仆从系统
- **44 种仆从类型**，config.yml 写 YAML 即可新增
- 盔甲架小人 + 固定贴图头颅 + 染色皮革身体
- 7 种行为原型（策略模式）
- 分级解锁目标（低 Tier 采不了高价值矿）
- 稀有掉落 + 全服广播 + 回流升级配方

</td>
<td width="50%">

### 📦 仓库 & GUI
- 真实箱子仓库（54 格 Hypixel 布局）
- 图鉴收藏界面（分类过滤/翻页/进度条）
- Hypixel 式 3×3 合成升级界面
- 燃料选择 GUI（一键安装/卸下）
- 全模板化，`/minion reload` 热重载

</td>
</tr>
<tr>
<td width="50%">

### ⚡ 性能 & 安全
- 模拟采集（只读方块，不破坏）
- 虚拟线程 IO（Java 21）
- Folia / RegionScheduler 兼容
- DB 写操作指数退避重试
- 脏标记不丢数据

</td>
<td width="50%">

### 🔌 集成 & 扩展
- Vault 经济（自动售卖/里程碑金币）
- SuperiorSkyblock2（空岛联动/团队共享）
- CraftEngine（自定义物品作升级材料）
- LuckPerms（权限自动联动）
- Collection 里程碑槽位加成

</td>
</tr>
</table>

---

## 🎮 快速开始

### 1️⃣ 安装

```bash
# 构建
mvn clean package

# 复制到服务器
cp target/SkyMinions-1.0.0.jar /path/to/server/plugins/

# 重启服务器
```

### 2️⃣ 获取仆从

```bash
# 管理员发放
/minion give 煤矿仆从 1
/minion give 铁矿仆从 5

# 或打开图鉴查看所有类型
/minions
```

### 3️⃣ 使用

1. **放置** — 手持仆从生成物右键方块 → 生成盔甲架小人
2. **工作** — 仆从自动在 5×5 范围内采集资源
3. **收取** — 右键小人打开仓库，取出产物
4. **升级** — 点击"升级"按钮，放入材料合成下一 Tier
5. **加燃料** — 空手点击燃料槽，选择燃料加速产出
6. **拾取** — 潜行+右键小人（仓库/模块/皮肤随生成物保留）

---

## 📖 命令参考

| 命令 | 说明 | 权限 |
|---|---|---|
| `/minions` | 打开仆从图鉴（分类/翻页/收集进度） | `hcs.minions.use` |
| `/minion give <类型> [数量]` | 发放仆从生成物 | `hcs.minions.admin` |
| `/minion upgrade <模块>` | 发放升级模块 | `hcs.minions.admin` |
| `/minion collection` | 查看资源累计与里程碑进度 | `hcs.minions.use` |
| `/minion stats` | 运行时统计（运行时长/已放置/总产出） | `hcs.minions.admin` |
| `/minion reload` | 热重载配置文件 | `hcs.minions.admin` |
| `/minion purge-orphans` | 清理孤儿盔甲架实体 | `hcs.minions.admin` |

---

## 🔐 权限系统

> 与 LuckPerms 联动，无需硬依赖。

```
hcs.minions.admin              # 管理权限（默认 op）
hcs.minions.use                # 使用仆从（默认 true）
hcs.minions.type.<类型名>       # 按类型控制（如 hcs.minions.type.煤矿仆从）
hcs.minions.limit.<n>          # 数量上限（取最大 n，与里程碑加成叠加）
```

**示例：**
```bash
# VIP 组最多 20 个仆从
/lp group vip permission set hcs.minions.limit.20 true

# 禁止某组使用钻石仆从
/lp group builder permission set hcs.minions.type.钻石仆从 false
```

---

## 🪣 燃料系统

> 双轴制：速度燃料 + 催化剂（产量倍率），无燃料时仆从仍工作（只加速）。

### ⏱ 速度燃料

| 燃料 | 加速 | 持续 | 说明 |
|:---:|:---:|:---:|---|
| 🪨 煤炭 | +5% | 3 min | 基础燃料 |
| 🪵 木炭 | +5% | 3 min | 基础燃料 |
| 🧱 煤炭块 | +5% | 15 min | 批量燃料 |
| 🫗 岩浆桶 | +25% | 60 min | 高级燃料（返还空桶） |
| 🔥 烈焰棒 | +30% | 18 min | 高级燃料 |

### ♾ 永久燃料（不衰减）

| 燃料 | 加速 |
|:---:|:---:|
| 🧪 岩浆膏 | +30% |
| ✨ 荧石粉 | +35% |
| ☀ 日光传感器 | +25% |

### 🧪 催化剂（产量倍率）

| 催化剂 | 倍率 | 持续 | 说明 |
|:---:|:---:|:---:|---|
| 💜 紫水晶碎片 | ×1.5 | 30 min | 温和催化剂 |
| 🔥 烈焰粉 | ×2.0 | 15 min | 中等催化剂 |
| 👻 幻翼膜 | ×3.0 | 8 min | 强力催化剂（短时爆发） |

**加燃料方式：**
- 空手点击燃料槽 → 打开燃料选择 GUI
- 手持燃料点击燃料槽 → 立即生效
- 手持燃料右键小人 → 每次消耗 1 个

---

## 💎 附魔资源

> Hypixel 式浓缩材料：160 个基础资源 → 1 个附魔资源（PDC 身份标记 + 附魔光效 + 中文名）。

```
🪨 煤炭 ×160  →  ✨ 附魔煤炭
⛏ 铁锭 ×160   →  ✨ 附魔铁锭
💎 钻石 ×160   →  ✨ 附魔钻石
🔮 末影珍珠 ×32 →  ✨ 附魔末影珍珠
```

- **50+ 种**覆盖全部仆从产物
- 超级压缩模块自动压缩
- 升级配方用 `enchanted:coal` 语法引用
- 附魔资源按原价折算售卖（不会因压缩亏钱）

---

## 📦 升级模块

> 模块槽位随 Tier 解锁（Tier 4/8 各开一个，共 4 槽）。

| 模块 | 效果 | 类型 |
|---|---|---|
| 🏭 自动熔炼 | 矿石/原矿/沙子 → 熔炼产物 | 掉落处理 |
| 📦 自动压缩 | 散装 9:1 压成方块 | 仓储压缩 |
| ⭐ 超级压缩 3000 | 散装 160:1 压成附魔资源 | 仓储压缩 |
| 💎 钻石散布 | ~2% 概率额外产出钻石 | 掉落处理 |
| 📐 范围扩展 | 工作面积 +5% | 范围增强 |
| 🏪 自动售卖漏斗 | 仓库满时自动 Vault 卖钱 | 经济 |
| 🛒 简易漏斗 | 产出即时售卖（50% 价） | 经济 |
| 💰 附魔漏斗 | 产出即时售卖（90% 价） | 经济 |
| 🦠 腐化之土 | 概率额外产出硫磺+腐化碎片 | 掉落处理 |
| 📦 小型储物箱 | 额外 +6 格仓库 | 存储 |
| 📦 中型储物箱 | 额外 +12 格仓库 | 存储 |
| 📦 大型储物箱 | 额外 +18 格仓库 | 存储 |

---

## 🏆 Collection 里程碑

> 资源累计跨阈值 → 金币奖励 + 仆从槽位 +1。

- **里程碑阈值：** 50 → 100 → 250 → 500 → 1000 → 2500 → 5000 → 10000
- **金币奖励：** 第 n 个里程碑 = `coins-base × n`
- **槽位加成：** 第 3/5/7 个里程碑各 +1 槽位
- **多资源叠加：** 总加成受 `max-bonus-slots` 硬上限钳制（默认 5）

```bash
# 查看自己的收集进度
/minion collection
```

---

## 😴 离线收益

> 主人上线时结算闲置窗产出（三道平衡锁）。

| 锁 | 说明 | 目的 |
|---|---|---|
| 🔒 仓储天花板 | 仓库满 = 不再累计 | 防无限囤积 |
| 🔒 基础速度 | 不吃燃料/倍率/布局加成 | 在线照料有奖励 |
| 🔒 燃料燃烧 | 闲置时长从燃料扣除 | 燃料真实消耗 |

**配置：**
```yaml
offline-production:
  enabled: true
  max-hours: 24        # 最长回溯时长
  rate-percent: 100    # 基础速度折扣
  min-seconds: 180     # 低于此秒数不结算
```

---

## ⚙️ 配置说明

> 所有配置修改后执行 `/minion reload` 热重载。

| 文件 | 说明 |
|---|---|
| `config.yml` | 全局参数 + 仆从类型 + 升级配方 + 燃料 + 里程碑 |
| `gui.yml` | GUI 文案模板（MiniMessage + 占位符）+ 布局（槽位/材质） |
| `messages.yml` | 游戏内消息模板 |
| `paper-plugin.yml` | 插件元数据 + 运行时依赖声明 |

### 新增仆从类型（示例）

```yaml
# config.yml → types: 段
钻石仆从:
  behavior: MINING
  display-name: "钻石仆从"
  icon: DIAMOND
  max-level: 12
  cooldown-ticks: [960, 900, 840, 760, 660, 540, ...]
  product: DIAMOND
  targets: [DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE]
  sell-price-per-unit: 3.0
  upgrade-recipe:
    DIAMOND: 64
    'enchanted:diamond': 2
  rare-drop: NETHERITE_SCRAP
  rare-drop-chance: 0.003
  unlock-amount: 200
```

---

## 🏗️ 架构设计

```
com.hcs.minions
├── MinionsPlugin              ← 组合根（只装配，零业务）
├── core/
│   ├── ServiceRegistry        ← 服务注册表（全局只读）
│   └── PermissionRegistry     ← 权限节点注册
├── config/                    ← 强类型 record 配置
│   ├── PluginConfig           ← 全局配置根
│   ├── MinionTypeConfig       ← 单类型配置（配方/目标/冷却）
│   └── CollectionConfig       ← 里程碑配置
├── model/                     ← 领域模型
│   ├── Minion                 ← 运行时仆从（54 格 GUI + 状态）
│   ├── MinionData             ← 持久化快照（BLOB 序列化）
│   └── MinionType             ← 类型注册表（config 驱动）
├── repository/                ← 数据访问层
│   ├── MinionRepository       ← 接口（CompletableFuture 异步）
│   └── CachedMinionRepository ← 内存缓存 + 脏标记批量落库
├── service/                   ← 业务服务
│   ├── MinionManager          ← 全局调度器（O(n)+O(1) 短路）
│   ├── BlockSearcher          ← 限流方块搜索（两阶段螺旋）
│   ├── SellService            ← 自动售卖（先扣物后加款）
│   ├── FuelService            ← 燃料定义（双轴制）
│   ├── CollectionService      ← 里程碑系统
│   └── OfflineSettlement      ← 离线收益结算
├── upgrade/                   ← 模块系统
│   ├── UpgradeService         ← 模块效果结算
│   └── MinionUpgradeType      ← 12 种模块枚举
├── work/                      ← 工作策略（策略模式）
│   ├── MinionWorkStrategy     ← 策略接口
│   ├── miner/                 ← 模拟采集（只读方块）
│   ├── farmer/                ← 模拟收割（Ageable 判定）
│   ├── lumberjack/            ← 连锁砍伐 + 补种
│   ├── fisher/                ← 模拟钓鱼
│   ├── slayer/                ← 真实击杀 + 模拟回退
│   ├── rancher/               ← 纯模拟畜牧
│   └── generator/             ← 生成→采集循环
├── gui/                       ← GUI 系统
│   ├── MinionGUIListener      ← 仓库界面
│   ├── CollectionGui          ← 图鉴收藏
│   ├── FuelGui                ← 燃料选择
│   ├── UpgradeCraftGui        ← 升级合成（3×3）
│   └── GuideListGui           ← 材料指南
├── listener/                  ← 事件监听
├── event/                     ← 自定义事件
└── util/                      ← 工具类
    ├── AsyncExecutor          ← 虚拟线程执行器
    ├── EnchantedResource      ← 附魔资源（50+ 种）
    ├── ItemRef                ← 材料抽象（原版/CE/附魔）
    ├── GuiLayout              ← GUI 布局配置
    ├── GuiText                ← GUI 文案模板
    └── Messages               ← 消息模板
```

### 设计原则

| 原则 | 实践 |
|---|---|
| **组合根只装配** | `MinionsPlugin` 只做依赖注入，零业务逻辑 |
| **策略模式解耦行为** | 7 种 `MinionWorkStrategy`，类型→策略映射查表 |
| **不可变配置** | `PluginConfig` 为 record，启动时一次性反序列化 |
| **并发安全** | `ConcurrentHashMap` + `volatile` + `AtomicBoolean` + CAS 脏标记 |
| **主线程安全** | 方块操作/Inventory 在主线程，IO 走虚拟线程 |
| **Folia 兼容** | `RegionScheduler` 替代全局调度 |
| **零空 catch** | 捕获后必须记录 + 优雅降级 |
| **GUI 模板化** | 槽位/材质/文案全部走 `gui.yml`，禁止硬编码 |

---

## 🔧 构建与安装

### 环境要求

- **JDK 21+**（虚拟线程支持）
- **Maven 3.8+**
- **Paper 1.21+** 服务端

### 构建

```bash
git clone https://github.com/lingfengyiluoye/skyminions.git
cd skyminions
mvn clean package
```

产物：`target/SkyMinions-1.0.0.jar`（瘦 jar，约 100KB）

### 安装

```bash
cp target/SkyMinions-1.0.0.jar /path/to/server/plugins/
# 重启服务器
```

运行时依赖（fastutil / HikariCP / SQLite / MySQL）由 Paper 从 Maven Central 自动下载。

### 测试

```bash
mvn test
# 纯逻辑测试（JUnit 5），不依赖 Bukkit 运行时
```

---

## 🤝 可选依赖

| 依赖 | 类型 | 说明 | 配置 |
|---|---|---|---|
| **Paper API 1.21+** | ✅ 必需 | 服务端 API | — |
| **Vault** | 🔶 可选 | 经济系统 | `economy.enabled: true` |
| **SuperiorSkyblock2** | 🔶 可选 | 空岛联动 | 自动检测 |
| **CraftEngine** | 🔶 可选 | 自定义物品 | `craftengine:<id>` 语法 |
| **LuckPerms** | 🔶 可选 | 权限管理 | 无需硬依赖 |

---

## ❓ FAQ

<details>
<summary><b>Q: 如何新增仆从类型？</b></summary>

在 `config.yml` 的 `types:` 段添加新条目即可，无需改代码。参见 [配置说明](#⚙️-配置说明)。
</details>

<details>
<summary><b>Q: 如何自定义升级配方？</b></summary>

修改 `config.yml` 中对应类型的 `upgrade-recipe` 段。支持多材料、CraftEngine 自定义物品（`craftengine:my_item`）、附魔资源（`enchanted:coal`）。`upgrade-recipe-at` 可按等级覆盖配方。
</details>

<details>
<summary><b>Q: 仆从不工作怎么办？</b></summary>

检查：① 燃料是否耗尽 ② 范围内是否有可采集目标 ③ 仓库是否已满（满仓停工）④ 半径内是否有玩家（`player-scan-radius`）。用 `/minion stats` 查看运行状态。
</details>

<details>
<summary><b>Q: 如何备份数据？</b></summary>

备份 `plugins/SkyMinions/` 目录下的 `minions.db`（SQLite）和 `collection.yml`。
</details>

<details>
<summary><b>Q: 支持 Folia 吗？</b></summary>

支持。代码使用 `Bukkit.getRegionScheduler()` 替代全局调度，兼容 Folia 的区域化线程模型。
</details>

---

## 📄 许可证

Private — 仅供个人服务器使用。

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给个 Star！**

[![GitHub stars](https://img.shields.io/github/stars/lingfengyiluoye/skyminions?style=social)](https://github.com/lingfengyiluoye/skyminions/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/lingfengyiluoye/skyminions?style=social)](https://github.com/lingfengyiluoye/skyminions/issues)

</div>
