# 📋 更新日志

本文件记录 SkyMinions 的主要版本变更。

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
