# SkyMinions —— 硬核生存仆从系统（Hypixel 风格）

Paper 1.21+ 仆从插件：放置后是一个**盔甲架小人**，在指定范围内自动工作，
右键小人**打开真实仓库（箱子 GUI）**；支持 Skyblock 式**升级系统**、**燃料系统**、
离线收益、自动售卖、空岛联动，权限与 **LuckPerms** 联动。

可选依赖：Vault（经济）、SuperiorSkyblock2（空岛）、LuckPerms（权限，无需硬依赖）、
CraftEngine（升级配方可用其自定义物品作材料，软依赖反射接入，未安装自动降级）。

---

## 一、功能

| 功能 | 说明 |
|---|---|
| 盔甲架小人 | 小号盔甲架 + 固定贴图头颅 + 染色皮革盔甲身体 + 手持工具，锁定装备防扒 |
| 七类仆从 | 矿工/农夫（**模拟采集**：只统计范围内可采方块/成熟作物数量，产量与数量成正比但不破坏方块，摆得越多产得越快）/伐木工（连锁整棵树，砍完自动在树根补种对应树苗）/钓鱼（长冷却慢节奏）/猎魔（范围杀敌：检测并击杀工作范围内真实怪物，按种类给掉落；无怪时模拟产出） + **牧民**（畜牧牛羊鸡猪：引种→繁殖→宰杀）+ **圆石**（生成器玩法：自动生成圆石再采，免搭刷石机） |
| 仓库 | 右键小人打开真实箱子，直接取/放物品（掉落入仓、仓库满溢出到世界） |
| 图鉴 GUI | `/minions` 打开 6 行图鉴：顶行分类过滤（采矿/农业/伐木/战斗/钓鱼/特殊）+ 分页卡片（已解锁等级/已放置数/总产出/下一级材料/收集进度条）+ 底行翻页与总进度 x/y；未解锁类型显示 ??? 与收集进度；点击卡片在聊天栏查看升级配方 |
| 升级系统 | 仓库内"升级"按钮，**多材料配方 + 陡增曲线**（每级材料量 ×growth，config.yml 自定义配方，支持 CraftEngine 自定义物品作材料），**并消耗 1 个当前等级仆从本体**（对齐 Hypixel 合成升级；`upgrade-require-previous-body: false` 可关），Lv.1→12 解锁更多槽位、工作更快 |
| 收集解锁 | 类型默认需累计收集对应产物达到 `unlock-amount` 才解锁（图鉴显示 ???，放置时提示进度）；`collection-unlock-enabled: false` 可整体关闭；管理员不受限 |
| 升级模块 | 2 个模块槽（Tier 4/8 解锁）：自动熔炼 / 自动压缩 / 超级压缩 3000 / 钻石散布 / 范围扩展 / 自动售卖漏斗（Hypixel 原版玩法） |
| 燃料系统 | 限时燃料（煤炭/岩浆桶/烈焰棒）+ 永久燃料（岩浆膏/荧石粉/日光传感器）；**空手点击燃料槽打开燃料选择 GUI**（列出背包装备的燃料，显示加速%/持续时间/背包数量，点击即装，状态卡潜行点击卸下）；也可手持燃料点击燃料槽或直接右键小人；状态卡实时显示加速与剩余时长 |
| 皮肤系统 | 默认/黄金/钻石/万圣节皮肤，GUI 点击切换 |
| 稀有掉落 | 每类仆从专属稀有掉落（矿工→绿宝石、牧民→鞍、圆石→黑曜石等），概率可配置，中奖时在线提醒 |
| Collection 里程碑 | 资源累计跨阈值发金币（第 n 个 = coins-base × n），命中指定里程碑额外 **仆从槽位 +1**（多资源叠加，对齐 Hypixel 里程碑解锁仆从位玩法） |
| 产出速率 | 仆从信息卡展示"≈ N 件/小时"估算（对齐 Hypixel GUI items/hour） |
| 理想布局 | GUI 内 Ideal Layout 按钮：**圆石仆从实际生效**（开启后自动在生成点摆水/岩浆产圆石，关闭/拾取自动还原，只占用空位不破坏建筑）；其余类型展示布局指引（5x5 固定范围、光照、共享边界） |
| 离线收益 | 下线按时间差批量结算入仓（上限 48h） |
| 自动售卖 | 仓库满自动 Vault 卖钱（可开关，或装备「自动售卖漏斗」模块） |
| 空岛联动 | SuperiorSkyblock2：仅本岛放置、工作校验（缓存降频）；**团队成员共享**：同岛成员（岛主/队友）可共同操作仆从（开仓库/加燃料/升级/拾取），离线收益仍归主人 |
| LuckPerms 权限 | 类型/数量上限/管理权限，见下 |

**一比一对齐 Hypixel 原版的关键机制**：工作范围固定 5x5（不随等级增长）、
模块槽位随 Tier 解锁、永久燃料、超级压缩（散装→附魔形态）；GUI 采用 Hypixel 界面
设计：头颅居中作视觉锚点、信息书含完整产出统计（速度/件每小时/稀有掉落/累计）、
升级按钮带“当前→下一级”速度对比与“需要/已有”材料实时比对、模块槽位于存储区下方、
收集全部居中，卡片内用 ▬ 分隔线分节。所有物品名/Lore 均已关闭原版默认斜体。
燃料槽为状态卡按钮（图标随状态变化：无燃料=煤炭 / 限时生效中=烈焰棒 / 永久生效中=岩浆桶），
GUI 打开期间每秒自动刷新燃料剩余秒数与下次工作倒计时。

**GUI 文案完全可自定义（gui.yml）**：全部卡片/按钮的标题与逐行 lore 均由
`gui.yml` 模板驱动，支持 MiniMessage 颜色标签（`<gold>` `<green>` …）与 `{占位符}`
（如 `{speed}`、`{rate}`、`{rare}`）；lore 行内含“当前不适用”的占位符时整行自动
隐藏（如未配置稀有掉落的类型不显示稀有行）。修改后 `/minion reload` 热重载。

## 二、使用

1. `/minions` 打开仆从图鉴（分类过滤/翻页/收集进度/升级配方一览）。
2. `/minion give miner 1`（或 `farmer` / `lumberjack` / `fisher` / `slayer` / `rancher` / `cobble`）发放生成物。
3. 右键方块放置 → 生成盔甲架小人（固定面向放置者）。
4. 右键小人 → 打开仓库（取物、加燃料、点"升级"、装备模块、切皮肤、点"拾取仆从"）。
   - **加燃料**：空手点击左上角「燃料槽」打开燃料选择界面（背包装备、一键安装/卸下）；手持燃料点击燃料槽立即生效（限时燃料整组生效、永久燃料消耗 1 个）；也可直接手持燃料右键小人添加。
   - **升级**：材料 + 1 个当前等级仆从本体（本体放仆从仓库或背包均可识别）。
5. 潜行+右键小人 → 拾取（仓库/模块/皮肤随生成物保留）。
6. `/minion upgrade <模块>` 发放模块，手持模块点击模块槽装备。
7. `/minion collection` 查看资源累计与下一里程碑进度（含已解锁的里程碑槽位加成）。

模块列表：`auto_smelter`(自动熔炼) `compactor`(自动压缩) `super_compactor`(超级压缩 3000)
`diamond_spreading`(钻石散布) `minion_expander`(范围扩展+5%面积) `auto_seller`(自动售卖漏斗)

## 三、LuckPerms 权限

LuckPerms 会自动接管这些 Bukkit 权限，可在 LP 里给组或玩家：

```
hcs.minions.admin           # 管理权限（/minion 命令，默认 op）
hcs.minions.use             # 使用仆从（默认人人有）
hcs.minions.type.miner      # 可用矿工仆从
hcs.minions.type.farmer     # 可用农夫仆从
hcs.minions.type.lumberjack # 可用伐木工仆从
hcs.minions.type.rancher    # 可用牧民仆从（畜牧）
hcs.minions.type.cobble     # 可用圆石仆从（生成器）
hcs.minions.limit.<n>       # 仆从数量上限（取拥有的最大 n，如 hcs.minions.limit.20）
                            # 上限可与 Collection 里程碑槽位加成叠加
```

示例（LP 命令）：`/lp group vip permission set hcs.minions.limit.20 true`

## 四、构建

```bash
# 需要 JDK 21+ 与 Maven
mvn clean package
# 产物 target/SkyMinions-1.0.0.jar（约 100 KB 瘦 jar）
```

运行时依赖（fastutil/HikariCP/sqlite-jdbc/mysql-connector-j）声明在 `paper-plugin.yml`
的 `libraries`，由 Paper 首次加载时从 Maven Central 自动下载。

---

## 五、架构与包结构

**设计要点**：全局单 `GlobalRegionScheduler` 每 20 tick 遍历一次（O(n)+O(1) 短路）；
矿工/农夫采用模拟采集（只读方块类型，不破坏方块，无光照/物理/更新包开销，
产量与范围内可采数量正比，`harvest-cap` 控制单次上限）；
`BlockSearcher` 限流搜索（邻接+随机，硬上限 <50）；方块破坏与 `Inventory#addItem`
均在主线程，仅 Vault 售卖异步；GUI 用真实 `Inventory`（Bukkit 内部深拷贝，无刷物）。

```
com.hcs.minions
├── MinionsPlugin            # 组合根：只装配
├── core/ServiceRegistry     # 服务注册表
├── config/                  # 强类型 record 配置（product/upgrade-recipe/max-level/head-texture…）
├── model/
│   ├── Minion               # 运行时仆从：真实仓库 + 槽位布局 + 升级/燃料/拾取
│   ├── MinionData           # 持久化快照（仓库序列化进 BLOB）
│   ├── MinionType / BlockLocation
├── repository/              # 接口 + 内存缓存 + SQLite/MySQL
├── service/
│   ├── MinionManager        # 全局调度器 + 掉落入仓 + 权限上限
│   ├── MinionEntityService  # 盔甲架小人生成/销毁/反查
│   ├── BlockSearcher        # 限流搜索
│   ├── EconomyService / SellService / FuelService / PermissionService
│   ├── OfflineRewardService
│   └── hook/SkyblockHook
├── work/                    # 策略模式（Miner/Farmer/Lumberjack/Fisher/Slayer/Rancher/Generator）
├── gui/                     # MinionGUIListener（仓库）/ CollectionGui（图鉴）/ FuelGui（燃料选择）
├── listener/                # 放置/交互/上线
├── event/                   # 自定义 Bukkit Event
└── util/                    # AsyncExecutor / ItemCodec / Logs
```
