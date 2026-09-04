package com.hcs.minions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 升级配方材料抽象：原版 Material 或 CraftEngine 自定义物品。
 *
 * <p>配置写法（config.yml upgrade-recipe 键）：</p>
 * <ul>
 *   <li>原版材料：{@code COBBLESTONE: 64}（大写 Material 名，不含冒号）</li>
 *   <li>CraftEngine 自定义物品：{@code "craftengine:my_item": 8}（键含冒号即按自定义物品解析，
 *       整个键作为 CraftEngine 物品 id，支持 {@code namespace:path} 形式）</li>
 * </ul>
 *
 * <p>自定义物品原型惰性构建并缓存：配置解析可能早于 CraftEngine 物品注册表就绪，
 * 原型在首次匹配/展示时才向 CraftEngine 请求。</p>
 */
public abstract class ItemRef {

    private ItemRef() {
    }

    /** 配置解析入口：{@code enchanted:<key>} → 附魔资源；含冒号 → CraftEngine 自定义物品；否则原版 Material（未知返回 null）。 */
    public static ItemRef parse(String configKey) {
        if (configKey == null || configKey.isEmpty()) {
            return null;
        }
        // 附魔资源（Hypixel 式中间层浓缩材料）：enchanted:coal 等
        if (configKey.regionMatches(true, 0, "enchanted:", 0, 10)) {
            String rk = configKey.substring(10);
            return EnchantedResource.ofKey(rk).map(EnchantedRef::new).orElse(null);
        }
        if (configKey.indexOf(':') >= 0) {
            return new CustomRef(configKey);
        }
        try {
            return new VanillaRef(Material.valueOf(configKey.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 仓库中的该物品是否匹配本材料（用于计数与扣除）。 */
    public abstract boolean matches(ItemStack stack);

    /** GUI 展示名（原版=中文名映射；自定义=id 的 path 段）。 */
    public abstract String displayName();

    /** GUI 图标材质（自定义物品取原型材质，未就绪时回退 BARRIER）。 */
    public abstract Material icon();

    /** 配置键（日志/回写用）。 */
    public abstract String configKey();

    /** 指南查询用的代表材质（原版=自身；自定义物品=原型图标）。 */
    public abstract Material guideMaterial();

    /** /minion reload 后清空原型缓存（CraftEngine 物品可能被重定义）。 */
    public static void clearCache() {
        CustomRef.PROTOTYPES.clear();
    }

    /** 原版材料引用：按 Material 匹配，且排除 CraftEngine 自定义物品（防同材质误计）。 */
    public static final class VanillaRef extends ItemRef {

        private final Material material;

        public VanillaRef(Material material) {
            this.material = material;
        }

        public Material material() {
            return material;
        }

        @Override
        public boolean matches(ItemStack stack) {
            if (stack == null || stack.getType() != material) {
                return false;
            }
            // 附魔资源与基础材质同 Material（如附魔煤炭=COAL+PDC），必须排除，
            // 否则混合配方（enchanted:coal + COAL）里附魔物品会被散装需求误吞 → 永远无法合成
            if (EnchantedResource.parse(stack).isPresent()) {
                return false;
            }
            // CraftEngine 自定义物品可能与原版材料同材质（如 PAPER），需排除；
            // minecraft: 命名空间一律视为原版（双保险，防 CE 端 id 形态变化）
            String ceId = CraftEngineHook.customItemId(stack);
            return ceId == null || ceId.startsWith("minecraft:");
        }

        @Override
        public String displayName() {
            return MaterialNames.of(material);
        }

        @Override
        public Material icon() {
            return material;
        }

        @Override
        public String configKey() {
            return material.name();
        }

        @Override
        public Material guideMaterial() {
            return material;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VanillaRef other && material == other.material;
        }

        @Override
        public int hashCode() {
            return material.hashCode();
        }

        @Override
        public String toString() {
            return material.name();
        }
    }

    /** CraftEngine 自定义物品引用：按物品 id 精确匹配。 */
    public static final class CustomRef extends ItemRef {
        /** 原型缓存（id -> 原型）；未解析成功不缓存，下次再试。 */
        private static final Map<String, ItemStack> PROTOTYPES = new ConcurrentHashMap<>();

        private final String id;

        public CustomRef(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        @Override
        public boolean matches(ItemStack stack) {
            if (stack == null) {
                return false;
            }
            return id.equals(CraftEngineHook.customItemId(stack));
        }

        @Override
        public String displayName() {
            // 优先取 CraftEngine 物品自身配置的显示名（通常为中文），未就绪时回退 id 的 path 段
            ItemStack proto = prototype();
            if (proto != null && proto.hasItemMeta()) {
                Component name = proto.getItemMeta().displayName();
                if (name != null) {
                    String plain = PlainTextComponentSerializer.plainText().serialize(name);
                    if (!plain.isBlank()) {
                        return plain;
                    }
                }
            }
            int i = id.indexOf(':');
            return i >= 0 ? id.substring(i + 1) : id;
        }

        @Override
        public Material icon() {
            ItemStack prototype = prototype();
            return prototype == null ? Material.BARRIER : prototype.getType();
        }

        @Override
        public String configKey() {
            return id;
        }

        @Override
        public Material guideMaterial() {
            return icon();
        }

        /** 惰性构建原型（缓存）；CraftEngine 未安装/物品未注册返回 null。 */
        public ItemStack prototype() {
            ItemStack cached = PROTOTYPES.get(id);
            if (cached != null) {
                return cached;
            }
            ItemStack built = CraftEngineHook.build(id);
            if (built != null) {
                PROTOTYPES.put(id, built);
            }
            return built;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CustomRef other && id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * 附魔资源引用（Hypixel 式浓缩材料）：按 PDC 身份精确匹配，原版同材质物品不误计。
     */
    public static final class EnchantedRef extends ItemRef {

        private final EnchantedResource resource;

        public EnchantedRef(EnchantedResource resource) {
            this.resource = resource;
        }

        public EnchantedResource resource() {
            return resource;
        }

        @Override
        public boolean matches(ItemStack stack) {
            return EnchantedResource.parse(stack)
                    .map(r -> r.resourceKey().equals(resource.resourceKey()))
                    .orElse(false);
        }

        @Override
        public String displayName() {
            return resource.displayName();
        }

        @Override
        public Material icon() {
            return resource.base();
        }

        @Override
        public String configKey() {
            return "enchanted:" + resource.resourceKey();
        }

        @Override
        public Material guideMaterial() {
            return resource.base();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof EnchantedRef other && resource.resourceKey().equals(other.resource.resourceKey());
        }

        @Override
        public int hashCode() {
            return resource.resourceKey().hashCode();
        }

        @Override
        public String toString() {
            return "enchanted:" + resource.resourceKey();
        }
    }
}
