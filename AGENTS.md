# AGENTS.md — SkyMinions 代理引导

> 本文件为 AI 代理首次接触项目时的引导入口。

## 构建

```bash
# 需要 JDK 21+ 与 Maven
mvn clean package
# 产物：target/SkyMinions-1.0.0.jar（瘦 jar，约 100KB）
# 运行时依赖由 paper-plugin.yml 的 libraries 声明，Paper 自动从 Maven Central 下载
```

## 测试

```bash
mvn test
# 纯逻辑测试（JUnit 5），不依赖 Bukkit 运行时
# 测试位于 src/test/java/com/hcs/minions/
```

## 项目架构

```
com.hcs.minions
├── MinionsPlugin            # 组合根：只装配，严禁业务逻辑
├── core/ServiceRegistry     # 服务注册表
├── config/                  # 强类型 record 配置
├── model/                   # Minion（运行时）、MinionData（持久化快照）
├── repository/              # 接口 + 缓存 + SQLite/MySQL
├── service/                 # MinionManager（全局调度器）+ 各业务服务
├── work/                    # 策略模式（7 种仆从工作策略）
├── gui/                     # MinionGUIListener（仓库）/ CollectionGui（图鉴）/ FuelGui（燃料选择）/ UpgradeCraftGui（升级合成）
├── listener/                # 放置/交互事件
├── event/                   # 自定义 Bukkit Event
└── util/                    # AsyncExecutor / ItemCodec / Logs / Messages / GuiText / GuiLayout
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
  （本体 + 材料放入合成格合成下一 Tier，配方沿用 config.yml upgrade-recipe）
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
升级配方材料使用 `ItemRef` 抽象类统一处理：支持原版 `Material` 与 CraftEngine 自定义物品，通过 `matches()`/`displayName()`/`icon()` 实现统一逻辑。

## 依赖与集成

| 依赖 | 类型 | 说明 |
|---|---|---|
| Paper API 1.21+ | 必需 | 服务端 API（provided） |
| SLF4J | 必需 | 日志（Paper 已提供，统一走 `Logs` 工具类） |
| Vault | 可选 | 经济系统（softdepend） |
| SuperiorSkyblock2 | 可选 | 空岛联动（softdepend） |
| CraftEngine | 可选 | 自定义物品作升级材料（softdepend，反射接入） |
| HikariCP / SQLite / MySQL | 运行时 | 由 paper-plugin.yml libraries 自动挂载 |

## 权限结构

```
hcs.minions.admin           # 管理权限（/minion 命令，默认 op）
hcs.minions.use             # 使用仆从（默认人人有）
hcs.minions.type.<type>     # 按类型控制
hcs.minions.limit.<n>       # 数量上限（取最大 n）
```

## 注意事项

- 全局单 `GlobalRegionScheduler` 每 20 tick 遍历一次，O(n)+O(1) 短路；真正触碰方块/实体的逻辑委派到仆从所在 region 线程
- 矿工/农夫采用**模拟采集**（只读方块类型，不破坏方块）
- 伐木工连锁整棵树，砍完自动在树根补种树苗
- 猎魔仆从以 `damage()` 击杀怪物触发 EntityDeathEvent（任务/统计插件联动），`SlayerKills` 标记 + 监听器清自然掉落避免双份
- 仓库满且无自动售卖时**停工**（不产出），头顶名牌追加红字告警，取货/开售卖后自动恢复
- 放置限制：同格禁放 + 仆从间最小间距（`min-placement-distance`）；玩家休眠半径 `player-scan-radius` 内无人则停产
- DB 写操作（upsert/delete）带指数退避重试，重试耗尽后保留脏标记下轮再试，不丢数据
- 方块破坏与 `Inventory#addItem` 均在主线程，仅 Vault 售卖异步
- **严禁在业务代码中空 catch 吞异常**——捕获后必须记录并优雅降级
