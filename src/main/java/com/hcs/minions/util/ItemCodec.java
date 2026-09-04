package com.hcs.minions.util;

import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 物品序列化工具。仓库内容（真实箱子 Inventory 的 ItemStack 列表）用
 * {@link ItemStack#serializeAsBytes()} 逐项序列化；任何进出仓库/GUI 的
 * ItemStack 都必须经 {@link #deepCopy(ItemStack)} 深拷贝，杜绝引用泄漏刷物。
 */
public final class ItemCodec {

    private static final int MAX_ITEMS = 512;
    /** 单项字节上限：正常物品几百字节，带满 NBT 的书/烟花约几十 KB，64KB 足够且防内存放大。 */
    private static final int MAX_ITEM_BYTES = 65_536;

    private ItemCodec() {
    }

    /** 深拷贝：返回与入参无共享可变状态的独立 ItemStack。 */
    public static ItemStack deepCopy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    /** 批量深拷贝。 */
    public static List<ItemStack> deepCopyAll(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null) {
                out.add(item.clone());
            }
        }
        return out;
    }

    /** 序列化 ItemStack 列表（用于仓库持久化）：条目数 + (长度 + bytes)。 */
    public static byte[] serializeStacks(List<ItemStack> items) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeInt(items.size());
            for (ItemStack item : items) {
                byte[] bytes = (item == null) ? new byte[0] : item.serializeAsBytes();
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            Logs.error("物品列表序列化失败", e);
            return new byte[0];
        }
    }

    /** 反序列化 ItemStack 列表。 */
    public static List<ItemStack> deserializeStacks(byte[] data) {
        List<ItemStack> out = new ArrayList<>();
        if (data == null || data.length == 0) {
            return out;
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int n = dis.readInt();
            if (n < 0 || n > MAX_ITEMS) {
                throw new IOException("invalid item count: " + n);
            }
            for (int i = 0; i < n; i++) {
                int len = dis.readInt();
                if (len < 0 || len > MAX_ITEM_BYTES) {
                    throw new IOException("invalid item payload length: " + len);
                }
                byte[] bytes = new byte[len];
                dis.readFully(bytes);
                ItemStack item;
                try {
                    item = ItemStack.deserializeBytes(bytes);
                } catch (RuntimeException ex) {
                    Logs.warn("物品反序列化失败，已跳过第 {} 项", i, ex);
                    continue;
                }
                if (item != null && item.getType() != org.bukkit.Material.AIR) {
                    out.add(item);
                }
            }
        } catch (IOException | RuntimeException e) {
            Logs.error("物品列表反序列化失败，已降级返回空列表", e);
        }
        return out;
    }
}
