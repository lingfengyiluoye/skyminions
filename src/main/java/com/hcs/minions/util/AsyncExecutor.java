package com.hcs.minions.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 全局异步执行器：所有 IO（数据库落库、Vault 售卖加款、离线结算写库）都经由
 * Java 21 的 {@link Executors#newVirtualThreadPerTaskExecutor()} 执行。
 *
 * <p>线程边界绝对分离：方块破坏/掉落物生成只能在主线程/区域线程执行，
 * 此处只承担“写入虚拟背包 Map”“Vault 加款”等允许离主线程的任务。
 */
public final class AsyncExecutor implements AutoCloseable {

    private final ExecutorService virtual;
    private final ScheduledExecutorService scheduler;

    public AsyncExecutor() {
        this.virtual = Executors.newVirtualThreadPerTaskExecutor();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "hcs-minions-scheduler");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    /** 提交一个异步任务，返回可组合的 CompletableFuture（调用方必须处理异常，不得吞掉）。 */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                Logs.error("异步任务执行失败", e);
                throw new RuntimeException(e);
            }
        }, virtual);
    }

    /** 提交一个无返回值的异步任务。 */
    public CompletableFuture<Void> run(Runnable task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Logs.error("异步任务执行失败", e);
                throw new RuntimeException(e);
            }
        }, virtual);
    }

    /** 周期调度（用于脏数据批量落库、自动售卖轮询）。单线程调度器，绝不为单个仆从开定时器。 */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return scheduler.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Logs.error("周期任务执行失败", e);
            }
        }, initialDelay, period, unit);
    }

    /** 暴露虚拟线程池，供数据库连接池等需要 Executor 的组件使用。 */
    public ExecutorService virtual() {
        return virtual;
    }

    @Override
    public void close() {
        // 先优雅停止，避免 shutdownNow 中断正在执行的 JDBC/Vault 调用。
        scheduler.shutdown();
        virtual.shutdown();
        if (!await("virtual", virtual)) {
            virtual.shutdownNow();
        }
        if (!await("scheduler", scheduler)) {
            scheduler.shutdownNow();
        }
    }

    private static boolean await(String name, ExecutorService pool) {
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                Logs.warn("异步执行器 {} 在 5 秒内未完全终止（可能有任务卡死）", name);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logs.warn("等待异步执行器 {} 终止时被中断", name);
            return false;
        }
    }
}
