# SkyMinions — Hypixel 风格仆从系统

[![Build & Test](https://github.com/lingfengyiluoye/skyminions/actions/workflows/build.yml/badge.svg)](https://github.com/lingfengyiluoye/skyminions/actions/workflows/build.yml)
[![Paper 1.21+](https://img.shields.io/badge/Paper-1.21%2B-blue)](https://papermc.io/)
[![JDK 21](https://img.shields.io/badge/JDK-21%2B-orange)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Private-red)](#)

**Paper 1.21+ 仆从插件** — 一比一对齐 Hypixel SkyBlock Minions 玩法：盔甲架小人放置后自动工作，真实箱子仓库，Hypixel 式升级合成 / 燃料系统 / 模块槽 / 图鉴收集 / 里程碑奖励 / 离线收益，全部配置驱动、热重载生效。

> **44 种仆从类型** × **7 种行为原型** × **12 种升级模块** × **50+ 种附魔资源**

---

## 一、核心特性

### 仆从类型（44 种，配置驱动）

| 分类 | 数量 | 类型 |
|---|---|---|
| 采矿 MINING | 12 | 煤矿 / 铁矿 / 铜矿 / 金矿 / 红石 / 青金 / 钻石 / 绿宝石 / 石英 / 黑曜石 / 冰雪 / 蜂蜜 / 沙砾 / 沙漠 |
| 农业 FARMING | 10 | 小麦 / 胡萝卜 / 马铃薯 / 南瓜 / 西瓜 / 甜菜 / 可可 / 下界疣 / 甘蔗 / 仙人掌 / 花卉 / 蘑菇 |
| 伐木 FORAGING | 10 | 橡木 / 白桦 / 云杉 / 丛林 / 金合欢 / 深色橡木 / 樱花 / 红树 / 绯红 / 诡异 |
| 战斗 COMBAT | 7 | 僵尸 / 骷髅 / 苦力怕 / 蜘蛛 / 末影人 / 烈焰人 / 史莱姆 / 岩浆怪 / 凋灵骷髅 / 猪灵 |
| 钓鱼 FISHING | 1 | 钓鱼仆从 |
| 畜牧 RANCHING | 5 | 牛 / 羊 / 鸡 / 猪 / 兔 |

新增类型只需在 `config.yml` 的 `types:` 段写一段 YAML，零 Java 改动。

### 7 种行为原型（策略模式）

| 行为 | 工作方式 | 特点 |
|---|---|---|
| MINING | 模拟采集（只读方块类型，不破坏） | 摆什么矿产什么，产量与范围内矿石数量正比 |
| FARMING | 模拟收割（读取 Ageable 生长阶段） | 成熟作物反复产出，无需收割/补种 |
| FORAGING | 真实连锁砍伐 + 自动补种 | BFS 整棵树一次砍光，树根补种对应树苗 |
| FISHING | 模拟钓鱼（附近有水即工作） | 长冷却慢节奏，25% 概率双倍 |
| COMBAT | 真实击杀优先 + 模拟回退 | 范围内有怪则 `damage()` 击杀触发 EntityDeathEvent；无怪时模拟掉落 |
| RANCHING | 纯模拟畜牧（战利品表 roll） | 不生成实体，防大规模养殖卡服 |
| GENERATOR | 生成→采集循环 | 自动在固体方块上方生成圆石再采集 |

### 升级模块（12 种）

| 模块 | 效果 |
|---|---|
| 自动熔炼 | 矿石/原矿/沙子 → 熔炼产物 |
| 自动压缩 | 仓内散装 9:1 压成方块（仓储级结算） |
| 超级压缩 3000 | 仓内散装 160:1 压成附魔资源（存储效率核心） |
| 钻石散布 | 每次工作 ~2% 概率额外产出钻石 |
| 范围扩展 | 工作面积 +5%（对齐原版） |
| 自动售卖漏斗 | 仓库满时自动 Vault 卖钱 |
| 简易漏斗 | 产出即时折价售卖（50%） |
| 附魔漏斗 | 产出即时高价售卖（90%） |
| 腐化之土 | 概率额外产出硫磺 + 腐化碎片 |
| 小/中/大型储物箱 | 额外 +6/+12/+18 格仓库（占模块槽） |

模块槽位随 Tier 解锁（Tier 4/8 各开一个，共 4 槽）。

---

## 二、附魔资源系统（Hypixel Enchanted Resources）

**50+ 种附魔资源**，160:1 压缩换算（末影珍珠 32:1）。纯原版实现：原版材质 + 附魔光效 + PDC 身份标记 + 中文名。

- 附魔煤炭 = 160 煤炭 · 附魔铁锭 = 160 粗铁 · 附魔钻石 = 160 钻石 ……
- 超级压缩模块自动将仓内散装压成附魔资源
- 升级配方支持 `enchanted:coal` 作为材料（CraftEngine 自定义物品也支持：`craftengine:my_item`）
- 附魔资源按原价折算售卖（附魔煤炭 = 160 煤的价格），不会因压缩亏钱

---

## 三、燃料系统（双轴制）

### 速度燃料（限时加速）

| 燃料 | 加速 | 持续 |
|---|---|---|
| 煤炭 / 木炭 | +5% | 3 分钟 |
| 煤炭块 | +5% | 15 分钟 |
| 岩浆桶 | +25% | 60 分钟 |
| 烈焰棒 | +30% | 18 分钟 |

### 永久燃料（不衰减）

| 燃料 | 加速 |
|---|---|
| 岩浆膏 | +30% |
| 荧石粉 | +35% |
| 日光传感器 | +25% |

### 催化剂（产量倍率轴）

| 催化剂 | 倍率 | 持续 |
|---|---|---|
| 紫水晶碎片 | ×1.5 | 30 分钟 |
| 烈焰粉 | ×2.0 | 15 分钟 |
| 幻翼膜 | ×3.0 | 8 分钟 |

加燃料方式：空手点击燃料槽打开燃料选择 GUI / 手持燃料点击燃料槽 / 手持燃料右键小人。

---

## 四、其他核心系统

### 图鉴 GUI (`/minions`)
6 行收藏界面：顶行分类过滤（采矿/农业/伐木/战斗/钓鱼/特殊）+ 分页卡片（已解锁等级/已放置数/总产出/下一级材料/收集进度条）+ 底行翻页与总进度。未解锁类型显示 ???。

### 升级合成（Hypixel 式 3×3）
仓库内"升级"按钮打开合成界面：上一级仆从本体 + 升级材料放入 3×3 合成格，点击产物合成下一 Tier。材料来自玩家背包，支持潜行一键填充，关闭自动归还。配方支持 `upgrade-recipe-at` 按等级覆盖（稀有掉落回流载体）。

### Collection 里程碑
资源累计跨阈值发金币（第 n 个 = coins-base × n），命中指定里程碑额外仆从槽位 +1。多种资源可叠加，总加成受 `max-bonus-slots` 硬上限钳制。

### 离线收益（三道平衡锁）
主人上线时结算闲置窗产出：
1. **仓储即天花板**：仓库满 = 不再累计
2. **仅基础速度**：不吃燃料加速/倍率/布局加成
3. **燃料真实燃烧**：闲置时长从燃料剩余中等额扣除

### 收集解锁
类型默认需累计收集对应产物达到 `unlock-amount` 才解锁。`collection-unlock-enabled: false` 可整体关闭。管理员不受限。

### 稀有掉落
每类仆从专属稀有掉落，概率可配置。中奖默认全服广播（`rare-drop-broadcast: false` 改为仅通知主人）。配置 `upgrade-recipe-at` 可将稀有掉落设为高阶升级材料。

### 空岛联动（SuperiorSkyblock2）
仅本岛放置、工作校验。团队成员共享：同岛成员可共同操作仆从。

### 放置保护
同格禁止重复放置；仆从间最小间距 `min-placement-distance`；`player-scan-radius` 半径内无玩家时休眠。

---

## 五、使用

```
/minions                  — 打开仆从图鉴（分类过滤/翻页/收集进度/升级配方）
/minion give <类型> [数量] — 发放仆从生成物（如：煤矿仆从、铁矿仆从）
/minion collection        — 查看资源累计与下一里程碑进度
/minion upgrade <模块>     — 发放升级模块
/minion stats             — 运行时统计（运行时长/调度周期/已放置数/总产出）
/minion reload            — 热重载配置
/minion purge-orphans     — 清理孤儿盔甲架实体
```

放置：手持仆从生成物右键方块 → 生成盔甲架小人。  
交互：右键小人 → 打开仓库（取物/加燃料/升级/装备模块/切皮肤/拾取）。  
拾取：潜行+右键小人（仓库/模块/皮肤随生成物保留）。

---

## 六、权限（LuckPerms 联动）

```
hcs.minions.admin           # 管理权限（/minion 命令，默认 op）
hcs.minions.use             # 使用仆从（默认 true）
hcs.minions.type.<类型名>    # 按类型控制（如 hcs.minions.type.煤矿仆从）
hcs.minions.limit.<n>       # 数量上限（取最大 n，与里程碑槽位加成叠加）
```

示例：`/lp group vip permission set hcs.minions.limit.20 true`

---

## 七、构建与安装

```bash
# 需要 JDK 21+ 与 Maven
mvn clean package
# 产物：target/SkyMinions-1.0.0.jar（瘦 jar，约 100KB）
```

运行时依赖（fastutil / HikariCP / SQLite / MySQL）由 `paper-plugin.yml` 的 `libraries` 声明，Paper 首次加载时从 Maven Central 自动下载。

将 jar 放入 `plugins/` 目录，重启服务器即可。

---

## 八、可选依赖

| 依赖 | 类型 | 说明 |
|---|---|---|
| Paper API 1.21+ | 必需 | 服务端 API |
| Vault | 可选 | 经济系统（自动售卖/里程碑金币） |
| SuperiorSkyblock2 | 可选 | 空岛联动（放置/工作校验/团队共享） |
| CraftEngine | 可选 | 自定义物品作升级材料（反射接入，未安装自动降级） |
| LuckPerms | 可选 | 权限管理（无需硬依赖，Bukkit 权限自动联动） |

---

## 九、配置

所有配置文件修改后执行 `/minion reload` 热重载。

| 文件 | 说明 |
|---|---|
| `config.yml` | 全局参数 + 仆从类型定义 + 升级配方 + 燃料 + 里程碑 |
| `gui.yml` | GUI 文案模板（MiniMessage 颜色标签 + 占位符） + 布局（槽位/材质） |
| `messages.yml` | 游戏内消息模板 |
| `paper-plugin.yml` | 插件元数据 + 运行时依赖声明 |

GUI 文案与布局全部模板化，支持 `/minion reload` 热重载，禁止硬编码槽位常量。

---

## 十、架构

```
com.hcs.minions
├── MinionsPlugin            # 组合根：只装配，严禁业务逻辑
├── core/ServiceRegistry     # 服务注册表（全局只读共享）
├── config/                  # 强类型 record 配置（PluginConfig / MinionTypeConfig / CollectionConfig）
├── model/                   # Minion（运行时）/ MinionData（持久化快照）/ MinionType（注册表）
├── repository/              # 接口 + 内存缓存 + SQLite/MySQL（CachedMinionRepository）
├── service/                 # MinionManager（全局调度器）/ BlockSearcher / SellService / FuelService
│   └── hook/                # SkyblockHook（SuperiorSkyblock2 反射接入）
├── upgrade/                 # UpgradeService（模块效果）/ UpgradeRules / MinionUpgradeType
├── work/                    # 策略模式（7 种行为 × MinionWorkStrategy 接口）
├── gui/                     # 仓库 / 图鉴 / 燃料选择 / 升级合成 / 材料指南
├── listener/                # 放置 / 交互 / 猎魔死亡事件
├── event/                   # 自定义 Bukkit Event（MinionPlacedEvent / MinionRemovedEvent / MinionCollectEvent / MinionLevelUpEvent）
└── util/                    # AsyncExecutor / ItemCodec / EnchantedResource / ItemRef / GuiLayout / GuiText / Messages
```

**设计要点**：
- 全局单 `GlobalRegionScheduler` 每 20 tick 遍历一次（O(n)+O(1) 短路）
- 方块破坏与 `Inventory#addItem` 均在主线程，仅 Vault 售卖异步
- 虚拟线程执行器（Java 21）承载所有 IO
- Folia / RegionScheduler 兼容
- DB 写操作带指数退避重试，脏标记不丢数据

---

## 十一、测试

```bash
mvn test
# 纯逻辑测试（JUnit 5），不依赖 Bukkit 运行时
# 覆盖：配置加载 / 升级规则 / 方块搜索 / 燃料服务 / 仓储压缩 / GUI 布局 / 材料抽象
```

---

## 十二、许可

Private — 仅供个人服务器使用。
