package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.BlockLocation;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionStatus;
import com.hcs.minions.service.hook.SkyblockHook;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.Bars;
import com.hcs.minions.util.Messages;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkStrategyRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 仆从诊断（回答「我的仆从为什么不干活」——此前只能翻日志）。
 *
 * <p><b>只读</b>：不修改任何状态、不推进指针、不消耗燃料。可随意对任意玩家的
 * 任意仆从调用（管理员诊断他人，玩家诊断自己）。</p>
 *
 * <p>判定优先级与 {@code MinionManager#processMinion} 的实际执行顺序一致，
 * 这样诊断结论就是「这一周期它为什么没产出」的答案，而不是一串并列可能性：</p>
 * <ol>
 *   <li>类型配置缺失（热重载后删了该类型）</li>
 *   <li>区块未加载（附近无玩家活动，或区块被卸载）</li>
 *   <li>休眠（扫描半径内无玩家）</li>
 *   <li>满仓停工（仓库满且无售卖出口）</li>
 *   <li>空岛/世界校验不通过</li>
 *   <li>范围内无可用目标（布局问题：没矿/没成熟作物/没水…）</li>
 *   <li>冷却中（正常等待）</li>
 *   <li>工作中</li>
 * </ol>
 */
public final class MinionDiagnostics {

    /** 诊断结论。 */
    public enum Verdict {
        CONFIG_MISSING("类型配置缺失"),
        CHUNK_UNLOADED("区块未加载"),
        DORMANT("休眠中"),
        HALTED_FULL("满仓停工"),
        BLOCKED_BY_ISLAND("空岛校验不通过"),
        NO_TARGET("范围内无可用目标"),
        COOLDOWN("冷却中"),
        WORKING("工作中");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isProducing() {
            return this == WORKING || this == COOLDOWN;
        }
    }

    private final ConfigProvider config;
    private final WorkStrategyRegistry strategies;
    private final SkyblockHook skyblock;
    private final UpgradeService upgrades;
    private final BlockSearcher searcher;

    public MinionDiagnostics(ConfigProvider config, WorkStrategyRegistry strategies,
                             SkyblockHook skyblock, UpgradeService upgrades, BlockSearcher searcher) {
        this.config = config;
        this.strategies = strategies;
        this.skyblock = skyblock;
        this.upgrades = upgrades;
        this.searcher = searcher;
    }

    /** 单个仆从的诊断结果（结论 + 逐项事实）。 */
    public record Report(Minion minion, Verdict verdict, MinionStatus status, List<Component> facts) {

        /** 与 GUI 信息卡 / 头顶名牌同一口径的中文状态标签。 */
        public Component statusLine() {
            return Messages.diagLine("当前状态：" + status.label()
                    + (status.isProducing() ? " <gray>（正常产出）</gray>" : " <red>（未在产出）</red>"));
        }
    }

    /** 诊断单个仆从（只读）。 */
    public Report inspect(Minion minion) {
        List<Component> facts = new ArrayList<>();
        MinionTypeConfig cfg = config.get().type(minion.type());
        if (cfg == null) {
            facts.add(Messages.diagLine("类型 " + minion.type().key() + " 未注册（config.yml types 段已删除？）"));
            return new Report(minion, Verdict.CONFIG_MISSING, statusOf(minion, Verdict.CONFIG_MISSING), facts);
        }

        World world = minion.location().bukkitWorld();
        Location center = minion.location().toLocation();
        if (world == null || center == null) {
            facts.add(Messages.diagLine("所在世界不存在或坐标无效"));
            return new Report(minion, Verdict.CHUNK_UNLOADED, statusOf(minion, Verdict.CHUNK_UNLOADED), facts);
        }
        int cx = minion.location().x() >> 4;
        int cz = minion.location().z() >> 4;
        boolean chunkLoaded = world.isChunkLoaded(cx, cz);
        facts.add(Messages.diagLine(chunkLoaded ? "区块已加载" : "区块未加载（附近无玩家活动）"));
        if (!chunkLoaded) {
            return new Report(minion, Verdict.CHUNK_UNLOADED, statusOf(minion, Verdict.CHUNK_UNLOADED), facts);
        }

        // 休眠：扫描半径内是否有玩家
        double scanRadius = config.get().playerScanRadius();
        if (scanRadius > 0) {
            Player nearest = nearestPlayer(world, center, scanRadius);
            if (nearest == null) {
                facts.add(Messages.diagLine("扫描半径 " + (int) scanRadius + " 格内无玩家 → 休眠"
                        + "（产出在主人上线/返回时一次性结算）"));
                facts.add(storageFact(minion, cfg));
                return new Report(minion, Verdict.DORMANT, statusOf(minion, Verdict.DORMANT), facts);
            }
            facts.add(Messages.diagLine("最近玩家 " + nearest.getName() + "（"
                    + (int) nearest.getLocation().distance(center) + " 格）"));
        } else {
            facts.add(Messages.diagLine("玩家扫描半径 = 0（永不休眠）"));
        }

        // 满仓停工
        long stored = minion.storageCount();
        long capacity = (long) minion.unlockedSlots() * Math.max(1, cfg.product().getMaxStackSize());
        boolean full = minion.isStorageFull();
        boolean autoSell = minion.autoSell() || upgrades.hasAutoSell(minion);
        boolean hopper = upgrades.hasInstantHopper(minion);
        facts.add(Messages.diagLine("存储 " + stored + "/" + capacity + " 件（"
                + minion.unlockedSlots() + " 格）" + (full ? " <red>已满</red>" : "")));
        if (full && !autoSell && !hopper) {
            facts.add(Messages.diagLine("<red>无售卖出口</red>：自动售卖未开启，也未装漏斗模块"
                    + "（取货 / 开自动售卖 / 装漏斗均可恢复）"));
            facts.add(fuelFact(minion));
            return new Report(minion, Verdict.HALTED_FULL, statusOf(minion, Verdict.HALTED_FULL), facts);
        }
        if (autoSell) {
            facts.add(Messages.diagLine("售卖出口：自动售卖（满仓全价）"));
        }
        if (hopper) {
            facts.add(Messages.diagLine("售卖出口：即时漏斗（折价 "
                    + (int) (upgrades.instantSellRatio(minion) * 100) + "%）"));
        }

        // 空岛/世界校验
        if (!skyblock.canWorkAt(minion)) {
            facts.add(Messages.diagLine("<red>空岛校验不通过</red>（仆从不在可用岛屿范围内，或 SuperiorSkyblock2 未就绪）"));
            return new Report(minion, Verdict.BLOCKED_BY_ISLAND, statusOf(minion, Verdict.BLOCKED_BY_ISLAND), facts);
        }

        // 范围内目标（布局问题）——与 performWork 同口径构造上下文
        MinionWorkStrategy strategy = strategies.get(minion.type().behavior());
        boolean hasTarget = true;
        if (strategy != null) {
            Block anchor = world.getBlockAt(minion.location().x(), minion.location().y(), minion.location().z());
            WorkContext ctx = new WorkContext(minion, world, anchor, cfg,
                    upgrades.radiusFor(minion, cfg.radiusFor(minion.level())),
                    ThreadLocalRandom.current(), searcher);
            hasTarget = strategy.canWork(ctx);
        }
        if (!hasTarget) {
            facts.add(Messages.diagLine("<red>范围内没有可用目标</red>：按布局补充（如矿工需要在工作区内放对应矿石，"
                    + "且达到当前 Tier 的解锁目标）"));
            facts.add(targetHint(minion, cfg));
            facts.add(fuelFact(minion));
            return new Report(minion, Verdict.NO_TARGET, statusOf(minion, Verdict.NO_TARGET), facts);
        }

        facts.add(fuelFact(minion));
        // 冷却 vs 工作中
        if (!minion.canWorkNow(Bukkit.getCurrentTick())) {
            facts.add(Messages.diagLine("下次工作 " + minion.nextWorkSeconds() + " 秒后（冷却中，属正常）"));
            return new Report(minion, Verdict.COOLDOWN, statusOf(minion, Verdict.COOLDOWN), facts);
        }
        facts.add(Messages.diagLine("下次工作：本周期"));
        return new Report(minion, Verdict.WORKING, statusOf(minion, Verdict.WORKING), facts);
    }

    /** Verdict -> MinionStatus 映射（诊断结论与运行时状态卡共用同一口径）。 */
    private static MinionStatus statusOf(Minion minion, Verdict v) {
        return switch (v) {
            case WORKING, COOLDOWN -> MinionStatus.WORKING;
            case DORMANT -> MinionStatus.DORMANT;
            case HALTED_FULL -> MinionStatus.HALTED_FULL;
            case NO_TARGET -> MinionStatus.NO_TARGET;
            case BLOCKED_BY_ISLAND -> MinionStatus.BLOCKED_BY_ISLAND;
            case CONFIG_MISSING -> MinionStatus.CONFIG_MISSING;
            case CHUNK_UNLOADED -> MinionStatus.CHUNK_UNLOADED;
        };
    }

    /** 一次诊断多只仆从（按结论排序：异常的排前面）。 */
    public List<Report> inspectAll(List<Minion> minions) {
        List<Report> out = new ArrayList<>();
        for (Minion m : minions) {
            out.add(inspect(m));
        }
        out.sort((a, b) -> {
            int pa = severity(a.verdict());
            int pb = severity(b.verdict());
            if (pa != pb) {
                return Integer.compare(pa, pb);
            }
            return a.minion().id().compareTo(b.minion().id());
        });
        return out;
    }

    /** 结论严重度（越小越需要关注）。 */
    private static int severity(Verdict v) {
        return switch (v) {
            case CONFIG_MISSING, HALTED_FULL, NO_TARGET, BLOCKED_BY_ISLAND -> 0;
            case CHUNK_UNLOADED -> 1;
            case DORMANT -> 2;
            case COOLDOWN -> 3;
            case WORKING -> 4;
        };
    }

    private static Component storageFact(Minion minion, MinionTypeConfig cfg) {
        long capacity = (long) minion.unlockedSlots() * Math.max(1, cfg.product().getMaxStackSize());
        return Messages.diagLine("存储 " + minion.storageCount() + "/" + capacity + " 件");
    }

    private static Component fuelFact(Minion minion) {
        StringBuilder sb = new StringBuilder("燃料 ");
        if (minion.fuelTicks() > 0) {
            long total = Math.max(minion.fuelTotalTicks(), minion.fuelTicks());
            sb.append("剩余 ").append(Bars.fractionTicks(minion.fuelTicks(), total))
                    .append(' ').append(Bars.colored(minion.fuelTicks(), total));
        } else {
            sb.append("无（仆从仍会工作，燃料只加速）");
        }
        if (minion.permanentBoost() > 1.0) {
            sb.append(" · 永久加速 +").append((int) ((minion.permanentBoost() - 1) * 100)).append('%');
        }
        if (minion.prodMultiplier() > 1.0) {
            sb.append(" · 产量倍率 ×").append(minion.prodMultiplier());
        }
        return Messages.diagLine(sb.toString());
    }

    /** 按行为给出「该放什么」的提示。 */
    private static Component targetHint(Minion minion, MinionTypeConfig cfg) {
        String hint = switch (minion.type().behavior()) {
            case MINING -> "在工作区内放置当前 Tier 已解锁的矿石";
            case FARMING -> "种植并等待作物成熟（成熟才计入）";
            case FORAGING -> "在工作区内种树（砍完会自动补种）";
            case FISHING -> "在工作区周边放水";
            case COMBAT -> "确保工作范围内能刷出敌对怪物（光线暗、无方块阻挡）";
            case RANCHING -> "在工作区内放养对应动物";
            case GENERATOR -> "工作区内需要固体方块上方的空位";
        };
        return Messages.diagLine("提示：" + hint);
    }

    private static Player nearestPlayer(World world, Location center, double radius) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : world.getNearbyPlayers(center, radius)) {
            double d = p.getLocation().distanceSquared(center);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }
}
