# 📋 更新日志

本文件记录 SkyMinions 的主要版本变更。

## [未发布] - 2026-09-21

### ✨ 新增
- **仆从诊断命令** `/minion diagnose [玩家]`：只读诊断「仆从为何不产出」，
  覆盖类型配置缺失 / 区块未加载 / 休眠 / 满仓停工 / 空岛校验 / 无可用目标 /
  冷却 / 工作中 8 类结论，按严重度排序，异常排前面
- **运行时状态统一口径** `MinionStatus`：信息卡状态行、头顶名牌徽标、
  诊断结论三处共用同一份状态，玩家不打命令也能在 GUI 看到停机原因
- **事件音效层** `Sounds`：9 类事件音效（放置/拾取/工作/里程碑/售卖/
  GUI 点击/装模块/燃料耗尽/解锁），音量集中在 `sounds:` 段配置，
  单项置 0 即关闭
- **PlaceholderAPI 扩展**：`%skyminions_count%`、`%skyminions_limit%`、
  `%skyminions_count_<类型>%`、`%skyminions_produced_<类型>%`、
  `%skyminions_collection_<材质>%`、`%skyminions_progress%` 等占位符
- **bStats 用量统计**：仆从数 / 类型数 / 存储后端三个聚合图表

### ⚡ 性能
- **tick() 按 region 合批**：同一 region 的仆从合并为一个 region 任务，
  调度提交从 O(仆从数) 降到 O(region 数)，零额外延迟
- **单事务批量落库**：N 个脏仆从从 N 次自动提交变成 1 次（SQLite/MySQL 双路径）
- **放置间距空间索引**：按世界+区块分桶，只查候选点 3×3 邻域，
  代价与仆从总数脱钩（原来是 O(全部仆从) 建表）
- **purgeOrphans 按 region 合批移除**

### 🐞 修复
- **在途快照批次关闭时强制冲刷**：region 任务挂起导致批次永不结算时，
  已认领脏标记的快照不再 stranded（此前会静默丢失）
- **collection 分片崩溃残局清理**：启动时扫描 `.tmp`，正式文件缺失则扶正、
  已存在则清理，防止 JVM 崩溃后垃圾无限累积
- **解锁扣款原子化**：去掉 `has() + withdraw()` 两次探测的余额窗口，
  改为单次 withdraw 判定
- **MySQL/SQLite 重试加抖动**：`base + random(0, base)`，避免大量仆从
  同时失败时重试共振

### 🔧 重构
- **单一脏标记**：删掉仓库层冗余的「脏 ID 集合」与 Minion 的通知钩子，
  `Minion.dirty`（AtomicBoolean）成为唯一真相源，`collectDirtyIds` 扫缓存收集
- **并发不变量抽取为纯单元**：`SnapshotBatcher` / `OfflineWindow` /
  `PlacementGuard` 三个类承载最微妙的并发规则，均有回归测试
- **删除 vestigial 的 placementLock**：place() 改走 PlacementGuard 后已无人使用
- **collection 分片归档**：超过 60 天未活跃的分片移入 `archive/`，
  玩家下次产出时按需扶回，防止分片数无限增长拖慢启动

## [1.0.0] - 2026-09-07

### ✨ 新增
- **44 种仆从类型**：配置驱动，写 YAML 即可新增
- **7 种行为原型**：MINING / FARMING / FORAGING / FISHING / COMBAT / RANCHING / GENERATOR
- **12 种升级模块**：自动熔炼 / 压缩 / 超级压缩 / 钻石散布 / 范围扩展 / 售卖漏斗 / 腐化之土 / 储物箱
- **50+ 种附魔资源**：Hypixel 式浓缩材料，160:1 压缩
- **催化剂燃料**：紫水晶碎片(×1.5) / 烈焰粉(×2.0) / 幻翼膜(×3.0)
- **离线收益**：三道平衡锁（仓储天花板 / 基础速度 / 燃料燃烧）
- **Collection 里程碑**：资源累计跨阈值发金币 + 仆从槽位加成
- **收集解锁**：累计收集产物达阈值才解锁对应类型
- **图鉴 GUI**：分类过滤 / 翻页 / 收集进度 / 升级配方
- **升级合成 GUI**：Hypixel 式 3×3 合成界面
- **燃料选择 GUI**：一键安装/卸下燃料
- **材料指南 GUI**：升级配方预览
- **稀有掉落**：每类仆从专属稀有掉落 + 全服广播
- **空岛联动**：SuperiorSkyblock2 放置/工作校验/团队共享
- **CraftEngine 集成**：自定义物品作升级材料
- **Folia 兼容**：RegionScheduler 区域化线程

### 🏗️ 架构
- 策略模式：7 种行为解耦，类型→策略映射查表
- 虚拟线程 IO：Java 21 `Executors.newVirtualThreadPerTaskExecutor()`
- 脏标记批量落库：ConcurrentHashMap + CAS + 按 id 条带锁
- 配置强类型：record 不可变快照，全局只读共享
- GUI 模板化：`gui.yml` 驱动，`/minion reload` 热重载
