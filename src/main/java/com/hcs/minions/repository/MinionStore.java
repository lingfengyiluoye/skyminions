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

    Optional<MinionData> select(UUID id);

    List<MinionData> selectAll();

    void delete(UUID id);

    @Override
    void close();
}
