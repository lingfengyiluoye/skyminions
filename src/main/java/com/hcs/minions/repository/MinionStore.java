package com.hcs.minions.repository;

import com.hcs.minions.model.MinionData;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 底层存储接口（阻塞式，仅被 CachedMinionRepository 通过虚拟线程调用）。
 * SQLite 与 MySQL 分别实现；业务层绝不直接接触本接口。
 */
public interface MinionStore extends AutoCloseable {

    void init();

    void upsert(MinionData data);

    /**
     * 批量落库（单事务）。
     *
     * <p>默认实现逐条 {@link #upsert}（语义等价）；实现类应覆盖为真正的单事务——
     * N 个脏仆从一次 flush 从 N 次自动提交变成 1 次，这是写入侧最实的一项提升。</p>
     */
    default void upsertAll(List<MinionData> batch) {
        for (MinionData data : batch) {
            upsert(data);
        }
    }

    Optional<MinionData> select(UUID id);

    List<MinionData> selectAll();

    void delete(UUID id);

    @Override
    void close();
}
