# AGENTS.md — SkyMinions 代理引导

> 本文件为 AI 代理首次接触项目时的引导入口。遵循以下约定进行开发。

## 构建 & 测试

```bash
# 构建（需要 JDK 21+ 与 Maven）
mvn clean package
# 产物：target/SkyMinions-1.0.0.jar（瘦 jar，约 100KB）

# 测试（纯逻辑，不依赖 Bukkit 运行时）
mvn test
# 测试位于 src/test/java/com/hcs/minions/
```

## 项目架构

```
com.hcs.minions
├── MinionsPlugin              ← 组合根（只装配，零业务）
├── core/
│   ├── ServiceRegistry        ← 服务注册表（全局只读共享）
│   └── PermissionRegistry     ← 权限节点程序化注册
├── config/                    ← 强类型 record 配置
│   ├── PluginConfig           ← 全局配置根（不可变快照）
│   ├── MinionTypeConfig       ← 单类型配置（配方/目标/冷却/稀有掉落）
│   └── CollectionConfig       ← 里程碑配置
├── model/                     ← 领域模型
│   ├── Minion                 ← 运行时仆从（54 格 GUI + 状态管理）
│   ├── MinionData             ← 持久化快照（BLOB 序列化）
│   ├── MinionType             ← 类型注册表（config.yml 驱动，volatile 整体替换）
│   └── MinionBehavior         ← 行为枚举（7 种策略原型）
├── repository/                ← 数据访问层
│   ├── MinionRepository       ← 接口（CompletableFuture 异步契约）
│   └── CachedMinionRepository ← 内存缓存 + CAS 脏标记 + 按 id 条带锁
├── service/                   ← 业务服务
│   ├── MinionManager          ← 全局调度器（O(n)+O(1) 短路）
│   ├── BlockSearcher          ← 限流方块搜索（两阶段螺旋，硬上限 <50）
│   ├── SellService            ← 自动售卖（先扣物后加款，防刷钱）
│   ├── FuelService            ← 燃料定义（双轴制：速度 + 催化剂）
│   ├── CollectionService      ← 里程碑系统（跨阈值发金币+槽位加成）
│   ├── OfflineSettlement      ← 离线收益结算（三道平衡锁）
│   └── hook/SkyblockHook      ← SuperiorSkyblock2 反射接入
├── upgrade/                   ← 模块系统
│   ├── UpgradeService         ← 模块效果结算（掉落处理+仓储压缩）
│   └── MinionUpgradeType      ← 12 种模块枚举
├── work/                      ← 工作策略（策略模式）
│   ├── MinionWorkStrategy     ← 策略接口（canWork / performWork / offlineYield）
│   ├── SimHarvest             ← 模拟采集通用逻辑
│   ├── miner/ farmer/ lumberjack/ fisher/ slayer/ rancher/ generator/
├── gui/                       ← GUI 系统
│   ├── MinionGUIListener      ← 仓库界面（54 格 Hypixel 布局）
│   ├── CollectionGui          ← 图鉴收藏（分类过滤/翻页/进度条）
│   ├── FuelGui                ← 燃料选择
│   ├── UpgradeCraftGui        ← 升级合成（3×3）
│   └── GuideListGui           ← 材料指南
├── listener/                  ← 事件监听（放置/交互/猎魔死亡）
├── event/                     ← 自定义 Bukkit Event
└── util/                      ← 工具类
    ├── AsyncExecutor          ← 虚拟线程执行器（Java 21）
    ├── EnchantedResource      ← 附魔资源（50+ 种，160:1 压缩）
    ├── ItemRef                ← 材料抽象（原版 Material / CraftEngine / 附魔资源）
    ├── GuiLayout              ← GUI 布局配置（槽位区间语法）
    ├── GuiText                ← GUI 文案模板（MiniMessage + 占位符）
    ├── Messages               ← 消息模板
    ├── MaterialNames          ← 物料中文名映射
    ├── ItemCodec              ← 物品序列化（BLOB）
    ├── Logs                   ← SLF4J 日志封装
    └── Fx                     ← 音效/标题/粒子特效
```

## 关键开发约定

### 1. 全量中文化
游戏内所有显示内容（GUI 文案、消息提示、实体名牌等）必须统一映射为中文，禁止出现英文字样。

### 2. Adventure 文本去斜体
Paper/Adventure 框架下渲染物品名或 Lore 时，**必须**显式调用 `decoration(TextDecoration.ITALIC, false)` 关闭斜体样式，否则客户端按原版默认斜体渲染。适用于所有 GUI 卡片、手持物品及模块物品的文本渲染出口。

### 3. GUI 设计遵循 Hypixel 规范
- 头颅居中作为视觉锚点
- 信息书合并完整产出统计（速度/件每小时/范围/存储/稀有掉落）
- 升级按钮展示"当前→下一级"对比及仓库已有材料数；点击打开 Hypixel 式 3×3 合成界面
- 模块槽位于存储区下方，收集按钮居中
- 卡片内用 ▬ 分隔线分节

### 4. GUI 文案与布局模板化
GUI 文案由 `gui.yml` 模板驱动，支持 MiniMessage 颜色标签（`<gold>` 等）和 `{占位符}` 注入数据。
**布局（槽位/图标材质）同样配置驱动**：`gui.yml` 的 `layout:` 段由 `GuiLayout` 解析，
槽位支持区间语法（`[9-44]`），越界/无效值告警并回退内置默认。新增 GUI 时：
槽位/材质一律经 `GuiLayout.slot()/slots()/material()` 读取并在 `GuiLayout.Defaults` 登记默认值，
**禁止硬编码槽位常量**。修改后 `/minion reload` 热重载生效。

### 5. 配置热重载与兼容性
配置文件修改后执行 `/minion reload` 热重载；已放置仆从下次打开 GUI 时读取新配置。ConfigLoader 对无效配置回退默认值并在启动日志告警。

### 6. 材料抽象（ItemRef）
升级配方材料使用 `ItemRef` 抽象类统一处理三种来源：
- `VanillaRef` — 原版 Material（排除 CraftEngine 同材质物品和附魔资源）
- `CustomRef` — CraftEngine 自定义物品（`craftengine:<id>` 语法，原型惰性缓存）
- `EnchantedRef` — 附魔资源（PDC 身份精确匹配）

通过 `matches()`/`displayName()`/`icon()` 实现统一逻辑。

### 7. 附魔资源系统（EnchantedResource）
Hypixel 式浓缩材料：160 个基础资源 → 1 个附魔资源（末影珍珠 32:1）。
- 纯原版实现：原版材质 + 附魔光效 + PDC 身份标记 + 中文名
- 超级压缩模块自动压缩（仓储级结算）
- 升级配方用 `enchanted:coal` 语法引用
- `EnchantedResource.init(plugin)` 在组合根启动时调用一次

### 8. 仆从类型配置驱动
`MinionType` 由 `config.yml` 的 `types:` 段注册，新类型只需写 YAML 零 Java 改动。
- `MinionBehavior`（7 种）决定工作方式
- `MinionCategory`（6 种）决定图鉴分类
- 旧英文 key 通过 `LEGACY_ALIASES` 兼容，存档零迁移
- 注册表 `volatile` 整体替换，热重载安全

### 9. 并发模型
- `ConcurrentHashMap` 缓存仆从，O(1) 读取
- `AtomicBoolean` 脏标记 + CAS 认领快照权
- 按 id 条带锁串行化 upsert/delete，杜绝「先删后被旧快照覆盖」复活
- `volatile` 保证跨线程可见性
- 虚拟线程执行器承载所有 IO（`Executors.newVirtualThreadPerTaskExecutor()`）

### 10. 线程边界
- 方块破坏/Inventory 读写 → 主线程或 region 线程
- DB 写操作/Vault 加款 → 虚拟线程
- 快照生成 → region 线程（读 Inventory）
- 快照落库 → 虚拟线程（写 SQL）

## 依赖与集成

| 依赖 | 类型 | 说明 |
|---|---|---|
| Paper API 1.21+ | 必需 | 服务端 API（provided） |
| SLF4J | 必需 | 日志（Paper 已提供，统一走 `Logs` 工具类） |
| Vault | 可选 | 经济系统（自动售卖/里程碑金币） |
| SuperiorSkyblock2 | 可选 | 空岛联动（放置/工作校验/团队共享） |
| CraftEngine | 可选 | 自定义物品作升级材料（反射接入，未安装自动降级） |
| HikariCP / SQLite / MySQL | 运行时 | 由 paper-plugin.yml libraries 自动挂载 |

## 权限结构

```
hcs.minions.admin              # 管理权限（/minion 命令，默认 op）
hcs.minions.use                # 使用仆从（默认 true）
hcs.minions.type.<类型中文名>    # 按类型控制
hcs.minions.limit.<n>          # 数量上限（取最大 n，与里程碑槽位加成叠加）
```

## 注意事项

- 全局单 `GlobalRegionScheduler` 每 20 tick 遍历一次，O(n)+O(1) 短路；真正触碰方块/实体的逻辑委派到仆从所在 region 线程
- 矿工/农夫采用**模拟采集**（只读方块类型，不破坏方块）
- 伐木工连锁整棵树，砍完自动在树根补种树苗
- 猎魔仆从以 `damage()` 击杀怪物触发 EntityDeathEvent，`SlayerKills` 标记 + 监听器清自然掉落避免双份
- 仓库满且无自动售卖时**停工**（不产出），头顶名牌追加红字告警
- 放置限制：同格禁放 + 仆从间最小间距；玩家休眠半径内无人则停产
- DB 写操作带指数退避重试，重试耗尽后保留脏标记下轮再试，不丢数据
- 售卖路径：先扣物（region 线程）后加款（虚拟线程），失败自动回滚
- **严禁在业务代码中空 catch 吞异常**——捕获后必须记录并优雅降级
