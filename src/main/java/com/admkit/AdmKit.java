package com.admkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * /adm          -> abre um menu (tela) para escolher qual shulker box receber
 * /adm todas    -> recebe todas as boxes direto, sem abrir o menu
 *
 * No menu: clique numa box para receber só ela, ou use os botões
 * "Receber todas desta página" / "Receber TODAS as boxes".
 *
 * Nenhum item se repete entre as boxes: cada material aparece em uma única box.
 */
public class AdmKit extends JavaPlugin implements Listener {

    private static final int LEVEL = 255;
    private static final int POTION_TICKS = 3 * 60 * 60 * 20; // 3 horas
    private static final int POTION_AMP = 4;                  // nível V
    /** Máximo de boxes no kit: o inventário do Minecraft tem 36 espaços. */
    private static final int MAX_BOXES = 36;
    private static final int MENU_BOXES = 45;                 // boxes por página do menu
    private static final Map<String, Integer> CAPS = Map.of(
            "quick_charge", 5, "riptide", 5, "lunge", 5);

    /** Dono do inventário do menu (serve para reconhecer a tela no clique). */
    private static class AdmMenu implements InventoryHolder {
        final int page;
        Inventory inv;

        AdmMenu(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** Boxes prontas (montadas uma vez, na primeira vez que alguém usa). */
    private List<ItemStack> cache;

    /** Materiais que já foram colocados em alguma box (evita repetição). */
    private final Set<Material> used = new HashSet<>();
    /** Nome de cada box, na mesma ordem da lista de boxes. */
    private final List<String> titles = new ArrayList<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AdmKit ativado. Use /adm.");
    }

    private List<ItemStack> getBoxes() {
        if (cache == null) cache = buildBoxes();
        return cache;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Só jogadores podem usar /adm.");
            return true;
        }
        if (!p.hasPermission("admkit.use")) {
            p.sendMessage(Component.text("Você não tem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("todas")) {
            giveAll(p, getBoxes());
            return true;
        }
        openMenu(p, 0);
        return true;
    }

    // ------------------------------------------------------------------- menu

    private static ItemStack button(Material m, String title, NamedTextColor color, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name(title, color));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(name(line, NamedTextColor.GRAY));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void openMenu(Player p, int page) {
        List<ItemStack> boxes = getBoxes();
        int pages = Math.max(1, (boxes.size() + MENU_BOXES - 1) / MENU_BOXES);
        page = Math.max(0, Math.min(page, pages - 1));

        AdmMenu holder = new AdmMenu(page);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("AdmKit - Página " + (page + 1) + "/" + pages));
        holder.inv = inv;

        int from = page * MENU_BOXES;
        int to = Math.min(boxes.size(), from + MENU_BOXES);
        for (int i = from; i < to; i++) {
            ItemStack show = boxes.get(i).clone();
            ItemMeta meta = show.getItemMeta();
            meta.lore(List.of(name("Clique para receber esta box", NamedTextColor.GRAY)));
            show.setItemMeta(meta);
            inv.setItem(i - from, show);
        }

        ItemStack filler = button(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK);
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        if (page > 0) inv.setItem(45, button(Material.ARROW, "Página anterior", NamedTextColor.YELLOW));
        inv.setItem(47, button(Material.CHEST, "Receber todas desta página", NamedTextColor.GREEN,
                "Recebe as boxes que aparecem nesta tela."));
        inv.setItem(49, button(Material.NETHER_STAR, "Receber TODAS as boxes", NamedTextColor.GOLD,
                "Recebe todas as " + boxes.size() + " boxes (1 por espaço do inventário).",
                "Esvazie o inventário antes de clicar."));
        inv.setItem(51, button(Material.BARRIER, "Fechar", NamedTextColor.RED));
        if (page < pages - 1) inv.setItem(53, button(Material.ARROW, "Próxima página", NamedTextColor.YELLOW));

        p.openInventory(inv);
    }

    private void give(Player p, ItemStack box) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(box.clone());
        left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
    }

    /** Entrega várias boxes só se couberem no inventário. */
    private boolean giveMany(Player p, List<ItemStack> list) {
        int free = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) free++;
        }
        if (free < list.size()) {
            p.sendMessage(Component.text("Esvazie o inventário: você precisa de " + list.size()
                    + " espaços livres e tem " + free + ".", NamedTextColor.RED));
            return false;
        }
        for (ItemStack b : list) give(p, b);
        return true;
    }

    private void giveAll(Player p, List<ItemStack> boxes) {
        if (giveMany(p, boxes)) {
            p.sendMessage(Component.text("Você recebeu todas as " + boxes.size() + " boxes.", NamedTextColor.GOLD));
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof AdmMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("admkit.use")) {
            p.closeInventory();
            return;
        }
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getInventory())) return;

        List<ItemStack> boxes = getBoxes();
        int slot = e.getSlot();
        int from = menu.page * MENU_BOXES;

        if (slot < MENU_BOXES) {
            int idx = from + slot;
            if (idx < boxes.size() && e.getCurrentItem() != null) {
                give(p, boxes.get(idx));
                p.sendMessage(Component.text("Box recebida!", NamedTextColor.GREEN));
            }
            return;
        }
        switch (slot) {
            case 45 -> {
                if (menu.page > 0) Bukkit.getScheduler().runTask(this, () -> openMenu(p, menu.page - 1));
            }
            case 47 -> {
                int to = Math.min(boxes.size(), from + MENU_BOXES);
                if (giveMany(p, boxes.subList(from, to))) {
                    p.sendMessage(Component.text("Você recebeu as boxes desta página.", NamedTextColor.GREEN));
                }
            }
            case 49 -> giveAll(p, boxes);
            case 51 -> Bukkit.getScheduler().runTask(this, () -> p.closeInventory());
            case 53 -> Bukkit.getScheduler().runTask(this, () -> openMenu(p, menu.page + 1));
            default -> { }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof AdmMenu) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- helpers

    private static int levelFor(String key) {
        return CAPS.getOrDefault(key, LEVEL);
    }

    private static Enchantment ench(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    private static Component name(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static boolean obtainable(Material m) {
        return m.isItem() && !m.isAir() && !m.isLegacy() && !m.name().contains("SHULKER_BOX");
    }

    private static ItemStack one(String material) {
        Material m = Material.matchMaterial(material);
        if (m == null || !obtainable(m)) return null;
        return new ItemStack(m, 1);
    }

    /**
     * Item inquebrável com TODOS os encantamentos que o item aceita (sem maldições),
     * incluindo os que conflitam entre si. O parâmetro "ignored" é mantido só por compatibilidade.
     */
    private static ItemStack gear(String material, String... ignored) {
        ItemStack it = one(material);
        if (it == null) return null;
        ItemMeta meta = it.getItemMeta();
        meta.setUnbreakable(true);
        it.setItemMeta(meta);
        for (Enchantment e : Registry.ENCHANTMENT) {
            String k = e.getKey().getKey();
            if (k.endsWith("_curse")) continue;
            if (e.canEnchantItem(it)) {
                it.addUnsafeEnchantment(e, levelFor(k));
            }
        }
        return it;
    }

    private static ItemStack potion(Material type, String title, Color color, String... effects) {
        ItemStack it = new ItemStack(type);
        PotionMeta pm = (PotionMeta) it.getItemMeta();
        pm.setColor(color);
        pm.displayName(name(title, NamedTextColor.LIGHT_PURPLE));
        for (String e : effects) {
            PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(e));
            if (t != null) {
                pm.addCustomEffect(new PotionEffect(t, POTION_TICKS, POTION_AMP), true);
            }
        }
        it.setItemMeta(pm);
        return it;
    }

    private static ItemStack box(String title, NamedTextColor color, List<ItemStack> items) {
        ItemStack box = new ItemStack(Material.BLACK_SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) meta.getBlockState();
        Inventory inv = state.getInventory();
        for (ItemStack i : items) {
            if (i != null) inv.addItem(i);
        }
        meta.setBlockState(state);
        meta.displayName(name(title, color));
        box.setItemMeta(meta);
        return box;
    }

    private static List<ItemStack> list(ItemStack... items) {
        List<ItemStack> l = new ArrayList<>();
        for (ItemStack i : items) if (i != null) l.add(i);
        return l;
    }

    /** Pega pilhas cheias dos materiais citados que ainda não foram usados. */
    private List<ItemStack> take(String... names) {
        List<ItemStack> l = new ArrayList<>();
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m == null || !obtainable(m) || used.contains(m)) continue;
            used.add(m);
            l.add(new ItemStack(m, m.getMaxStackSize()));
        }
        return l;
    }

    /** Pega pilhas cheias de todos os materiais não usados cujo nome passa no teste. */
    private List<ItemStack> takeBy(Predicate<String> test) {
        List<ItemStack> l = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!obtainable(m) || used.contains(m) || !test.test(m.name())) continue;
            used.add(m);
            l.add(new ItemStack(m, m.getMaxStackSize()));
        }
        return l;
    }

    private static boolean has(String n, String... parts) {
        for (String p : parts) if (n.contains(p)) return true;
        return false;
    }

    private static boolean endsAny(String n, String... suffixes) {
        for (String s : suffixes) if (n.endsWith(s)) return true;
        return false;
    }

    /** Limita uma lista a no máximo n boxes (27 itens cada). */
    private static List<ItemStack> cap(List<ItemStack> l, int boxes) {
        return l.size() <= boxes * 27 ? l : new ArrayList<>(l.subList(0, boxes * 27));
    }

    /** Cria uma ou mais boxes (27 itens cada) e marca os materiais como usados. */
    private void add(List<ItemStack> out, String title, NamedTextColor color, List<ItemStack> items) {
        List<ItemStack> all = new ArrayList<>(items);
        all.removeIf(Objects::isNull);
        if (all.isEmpty() || out.size() >= MAX_BOXES) return;
        int parts = (all.size() + 26) / 27;
        for (int i = 0; i < parts; i++) {
            if (out.size() >= MAX_BOXES) break;
            List<ItemStack> chunk = new ArrayList<>(all.subList(i * 27, Math.min(all.size(), (i + 1) * 27)));
            for (ItemStack s : chunk) used.add(s.getType());
            String t = parts > 1 ? title + " (" + (i + 1) + "/" + parts + ")" : title;
            out.add(box(t, color, chunk));
            titles.add(t);
        }
    }

    // ------------------------------------------------------------------ boxes

    private static final String[][] WOODS = {
            {"OAK", "Carvalho"}, {"SPRUCE", "Pinheiro"}, {"BIRCH", "Bétula"}, {"JUNGLE", "Selva"},
            {"ACACIA", "Acácia"}, {"DARK_OAK", "Carvalho Escuro"}, {"MANGROVE", "Mangue"},
            {"CHERRY", "Cerejeira"}, {"PALE_OAK", "Carvalho Pálido"}, {"BAMBOO", "Bambu"},
            {"CRIMSON", "Carmesim"}, {"WARPED", "Distorcido"}};

    private static final String[] WOOD_PARTS = {
            "%_PLANKS", "%_LOG", "%_STEM", "%_BLOCK", "STRIPPED_%_LOG", "STRIPPED_%_STEM",
            "STRIPPED_%_BLOCK", "%_SLAB", "%_STAIRS", "%_DOOR"};

    private static final String[][] COLORS = {
            {"WHITE", "Branco"}, {"ORANGE", "Laranja"}, {"MAGENTA", "Magenta"},
            {"LIGHT_BLUE", "Azul Claro"}, {"YELLOW", "Amarelo"}, {"LIME", "Verde-Limão"},
            {"PINK", "Rosa"}, {"GRAY", "Cinza"}, {"LIGHT_GRAY", "Cinza Claro"},
            {"CYAN", "Ciano"}, {"PURPLE", "Roxo"}, {"BLUE", "Azul"}, {"BROWN", "Marrom"},
            {"GREEN", "Verde"}, {"RED", "Vermelho"}, {"BLACK", "Preto"}};

    private static final String[] COLOR_PARTS = {
            "%_WOOL", "%_CONCRETE", "%_TERRACOTTA", "%_STAINED_GLASS"};

    private static String[] fill(String[] templates, String value) {
        String[] r = new String[templates.length];
        for (int i = 0; i < templates.length; i++) r[i] = templates[i].replace("%", value);
        return r;
    }

    private List<ItemStack> buildBoxes() {
        used.clear();
        titles.clear();
        List<ItemStack> boxes = new ArrayList<>();

        // 1) Minérios
        add(boxes, "Minérios", NamedTextColor.AQUA, take(
                "NETHERITE_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK", "GOLD_BLOCK", "IRON_BLOCK",
                "COPPER_BLOCK", "LAPIS_BLOCK", "REDSTONE_BLOCK", "COAL_BLOCK",
                "NETHERITE_INGOT", "DIAMOND", "EMERALD", "GOLD_INGOT", "IRON_INGOT",
                "COPPER_INGOT", "LAPIS_LAZULI", "REDSTONE", "COAL", "QUARTZ",
                "AMETHYST_SHARD", "ANCIENT_DEBRIS", "NETHERITE_SCRAP", "RAW_IRON",
                "RAW_GOLD", "RAW_COPPER", "GLOWSTONE_DUST", "OBSIDIAN"));

        // 2) Comida
        add(boxes, "Comida", NamedTextColor.GREEN, take(
                "ENCHANTED_GOLDEN_APPLE", "GOLDEN_APPLE", "GOLDEN_CARROT", "COOKED_BEEF",
                "COOKED_PORKCHOP", "COOKED_MUTTON", "COOKED_CHICKEN", "COOKED_SALMON",
                "COOKED_COD", "COOKED_RABBIT", "BREAD", "BAKED_POTATO", "PUMPKIN_PIE",
                "CARROT", "APPLE", "MELON_SLICE", "SWEET_BERRIES", "GLOW_BERRIES",
                "COOKIE", "DRIED_KELP", "HONEY_BOTTLE", "BEETROOT", "CAKE",
                "MUSHROOM_STEW", "RABBIT_STEW", "BEETROOT_SOUP", "SUSPICIOUS_STEW"));

        // 3) Armadura netherite 255 + voo + escudo + totem
        String[] armorEnch = {"protection", "unbreaking", "mending", "thorns", "respiration",
                "aqua_affinity", "feather_falling", "depth_strider", "soul_speed", "swift_sneak"};
        add(boxes, "Armadura Netherite 255", NamedTextColor.DARK_PURPLE, list(
                gear("NETHERITE_HELMET", armorEnch),
                gear("NETHERITE_CHESTPLATE", armorEnch),
                gear("NETHERITE_LEGGINGS", armorEnch),
                gear("NETHERITE_BOOTS", armorEnch),
                gear("ELYTRA", "unbreaking", "mending"),
                gear("SHIELD", "unbreaking", "mending"),
                gear("TURTLE_HELMET", armorEnch),
                gear("WOLF_ARMOR"),
                gear("NETHERITE_HORSE_ARMOR"),
                one("TOTEM_OF_UNDYING")));

        // 4) Armas e ferramentas 255
        List<ItemStack> weapons = list(
                gear("NETHERITE_SWORD", "sharpness", "looting", "fire_aspect", "sweeping_edge", "unbreaking", "mending"),
                gear("NETHERITE_AXE", "sharpness", "efficiency", "unbreaking", "mending"),
                gear("NETHERITE_PICKAXE", "efficiency", "fortune", "unbreaking", "mending"),
                gear("NETHERITE_SHOVEL", "efficiency", "fortune", "unbreaking", "mending"),
                gear("NETHERITE_HOE", "efficiency", "fortune", "unbreaking", "mending"),
                gear("NETHERITE_SPEAR", "sharpness", "lunge", "fire_aspect", "looting", "unbreaking", "mending"),
                gear("MACE", "density", "breach", "wind_burst", "fire_aspect", "unbreaking", "mending"),
                gear("BOW", "power", "punch", "flame", "infinity", "unbreaking", "mending"),
                gear("CROSSBOW", "quick_charge", "piercing", "unbreaking", "mending"),
                gear("TRIDENT", "impaling", "loyalty", "channeling", "unbreaking", "mending"),
                gear("FISHING_ROD", "luck_of_the_sea", "lure", "unbreaking", "mending"),
                gear("SHEARS", "efficiency", "unbreaking", "mending"),
                gear("FLINT_AND_STEEL", "unbreaking", "mending"),
                gear("BRUSH", "unbreaking", "mending"));
        weapons.addAll(take("ARROW", "SPECTRAL_ARROW", "TIPPED_ARROW"));
        add(boxes, "Armas e Ferramentas 255", NamedTextColor.RED, weapons);

        // 5) Explosivos
        add(boxes, "Explosivos", NamedTextColor.GOLD, take(
                "TNT", "TNT_MINECART", "END_CRYSTAL", "WIND_CHARGE", "FIRE_CHARGE", "GUNPOWDER",
                "RESPAWN_ANCHOR", "GLOWSTONE", "LAVA_BUCKET", "CREEPER_HEAD", "SLIME_BLOCK",
                "HONEY_BLOCK", "PISTON", "STICKY_PISTON", "OBSERVER", "DISPENSER",
                "REDSTONE_TORCH", "LEVER", "STONE_BUTTON", "STONE_PRESSURE_PLATE",
                "TRIPWIRE_HOOK", "STRING", "FIREWORK_STAR"));

        // 6) Poções (3 horas, nível V)
        String[] all = {"speed", "strength", "haste", "resistance", "regeneration", "jump_boost",
                "fire_resistance", "night_vision", "water_breathing", "absorption",
                "health_boost", "saturation", "slow_falling", "luck", "dolphins_grace",
                "conduit_power"};
        List<ItemStack> potions = list(
                potion(Material.POTION, "Velocidade", Color.AQUA, "speed"),
                potion(Material.POTION, "Força", Color.RED, "strength"),
                potion(Material.POTION, "Pressa", Color.YELLOW, "haste"),
                potion(Material.POTION, "Resistência", Color.GRAY, "resistance"),
                potion(Material.POTION, "Regeneração", Color.FUCHSIA, "regeneration"),
                potion(Material.POTION, "Super Pulo", Color.LIME, "jump_boost"),
                potion(Material.POTION, "Resistência ao Fogo", Color.ORANGE, "fire_resistance"),
                potion(Material.POTION, "Visão Noturna", Color.NAVY, "night_vision"),
                potion(Material.POTION, "Respirar na Água", Color.BLUE, "water_breathing"),
                potion(Material.POTION, "Absorção", Color.fromRGB(0xFFD700), "absorption"),
                potion(Material.POTION, "Vida Extra", Color.fromRGB(0xFF5555), "health_boost"),
                potion(Material.POTION, "Saturação", Color.fromRGB(0xFFAA00), "saturation"),
                potion(Material.POTION, "Queda Lenta", Color.WHITE, "slow_falling"),
                potion(Material.POTION, "Invisibilidade", Color.SILVER, "invisibility"),
                potion(Material.POTION, "Sorte", Color.GREEN, "luck"),
                potion(Material.POTION, "Graça do Golfinho", Color.TEAL, "dolphins_grace"),
                potion(Material.POTION, "Poder do Conduíte", Color.AQUA, "conduit_power"),
                potion(Material.POTION, "MEGA Poção", Color.BLACK, all),
                potion(Material.SPLASH_POTION, "MEGA Poção Arremessável", Color.BLACK, all),
                potion(Material.LINGERING_POTION, "MEGA Poção Persistente", Color.BLACK,
                        "speed", "strength", "regeneration", "resistance", "absorption"));
        potions.addAll(take("EXPERIENCE_BOTTLE", "GLASS_BOTTLE", "DRAGON_BREATH"));
        add(boxes, "Poções 3 Horas", NamedTextColor.LIGHT_PURPLE, potions);

        // 7) Utilidades (com fogos de artifício de voo)
        ItemStack fireworks = new ItemStack(Material.FIREWORK_ROCKET, 64);
        FireworkMeta fm = (FireworkMeta) fireworks.getItemMeta();
        fm.setPower(5);
        fireworks.setItemMeta(fm);
        List<ItemStack> util = list(fireworks);
        util.addAll(take(
                "ENDER_PEARL", "ENDER_EYE", "BEACON", "CONDUIT", "ENCHANTING_TABLE", "ANVIL",
                "ENDER_CHEST", "CRAFTING_TABLE", "FURNACE", "BREWING_STAND", "WATER_BUCKET",
                "LADDER", "TORCH", "SCAFFOLDING", "SADDLE", "NAME_TAG", "LEAD", "BOOK",
                "COMPASS", "CLOCK", "SPYGLASS", "MAP", "BONE_MEAL", "COBWEB"));
        add(boxes, "Utilidades", NamedTextColor.YELLOW, util);

        // 8) Redstone
        add(boxes, "Redstone e Mecanismos", NamedTextColor.DARK_RED, take(
                "REPEATER", "COMPARATOR", "HOPPER", "DROPPER", "DAYLIGHT_DETECTOR", "TARGET",
                "NOTE_BLOCK", "REDSTONE_LAMP", "RAIL", "POWERED_RAIL", "DETECTOR_RAIL",
                "ACTIVATOR_RAIL", "MINECART", "LIGHTNING_ROD", "CHEST_MINECART",
                "HOPPER_MINECART", "FURNACE_MINECART", "CRAFTER", "BELL", "JUKEBOX",
                "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR", "SCULK_SHRIEKER", "COPPER_BULB"));

        // 9) Itens raros
        add(boxes, "Itens Raros", NamedTextColor.DARK_AQUA, take(
                "DRAGON_EGG", "NETHER_STAR", "HEART_OF_THE_SEA", "WITHER_SKELETON_SKULL",
                "DRAGON_HEAD", "SPONGE", "WET_SPONGE", "SHULKER_SHELL", "ECHO_SHARD",
                "NAUTILUS_SHELL", "PHANTOM_MEMBRANE", "BLAZE_ROD", "GHAST_TEAR", "BREEZE_ROD",
                "HEAVY_CORE", "OMINOUS_TRIAL_KEY", "TRIAL_KEY", "NETHERITE_UPGRADE_SMITHING_TEMPLATE",
                "BUNDLE", "GOAT_HORN", "MACE", "RECOVERY_COMPASS", "OMINOUS_BOTTLE",
                "ENCHANTED_GOLDEN_APPLE", "SNIFFER_EGG", "TURTLE_SCUTE", "ARMADILLO_SCUTE"));

        // 10) Admin (blocos e itens de comando)
        add(boxes, "Admin", NamedTextColor.DARK_GRAY, take(
                "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
                "COMMAND_BLOCK_MINECART", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
                "BARRIER", "LIGHT", "DEBUG_STICK", "KNOWLEDGE_BOOK", "BEDROCK", "SPAWNER",
                "TRIAL_SPAWNER", "VAULT", "END_PORTAL_FRAME", "REINFORCED_DEEPSLATE",
                "TEST_BLOCK", "TEST_INSTANCE_BLOCK"));

        // 11) Livros encantados nível 255 (um de cada encantamento, sem maldições)
        List<ItemStack> books = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) {
            String key = e.getKey().getKey();
            if (key.endsWith("_curse")) continue;
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta m = (EnchantmentStorageMeta) book.getItemMeta();
            m.addStoredEnchant(e, levelFor(key), true);
            book.setItemMeta(m);
            books.add(book);
        }
        add(boxes, "Livros 255", NamedTextColor.BLUE, books);

        // 12) Madeira (todos os tipos juntos: tábua, tronco, descascado, laje, escada, porta)
        List<ItemStack> wood = new ArrayList<>();
        for (String part : WOOD_PARTS) {
            for (String[] w : WOODS) wood.addAll(take(part.replace("%", w[0])));
        }
        add(boxes, "Madeira", NamedTextColor.GOLD, cap(wood, 3));

        // 13) Cores (lã, concreto, terracota, vidro nas 16 cores)
        List<ItemStack> colors = new ArrayList<>();
        for (String part : COLOR_PARTS) {
            for (String[] c : COLORS) colors.addAll(take(part.replace("%", c[0])));
        }
        add(boxes, "Cores", NamedTextColor.WHITE, cap(colors, 3));

        // 14) Categorias automáticas (só o que ainda não foi usado), cada uma com limite de boxes
        add(boxes, "Discos de Música", NamedTextColor.GOLD, cap(
                takeBy(n -> n.startsWith("MUSIC_DISC_") || n.startsWith("DISC_FRAGMENT")), 1));
        add(boxes, "Ovos de Spawn", NamedTextColor.GREEN,
                cap(takeBy(n -> n.endsWith("_SPAWN_EGG")), 3));
        add(boxes, "Moldes de Ferraria", NamedTextColor.GRAY,
                cap(takeBy(n -> n.endsWith("_SMITHING_TEMPLATE")), 1));
        add(boxes, "Padrões de Bandeira", NamedTextColor.AQUA,
                cap(takeBy(n -> n.endsWith("_BANNER_PATTERN")), 1));
        add(boxes, "Fragmentos de Cerâmica", NamedTextColor.RED,
                cap(takeBy(n -> n.endsWith("_POTTERY_SHERD")), 1));
        add(boxes, "Cabeças", NamedTextColor.YELLOW,
                cap(takeBy(n -> endsAny(n, "_HEAD", "_SKULL")), 1));
        add(boxes, "Corais", NamedTextColor.LIGHT_PURPLE,
                cap(takeBy(n -> n.contains("CORAL")), 1));
        add(boxes, "Cobre", NamedTextColor.GOLD,
                cap(takeBy(n -> n.contains("COPPER")), 1));
        add(boxes, "Armaduras e Ferramentas Comuns", NamedTextColor.GRAY, cap(takeBy(n ->
                (n.startsWith("LEATHER_") || n.startsWith("CHAINMAIL_") || n.startsWith("IRON_")
                        || n.startsWith("GOLDEN_") || n.startsWith("DIAMOND_") || n.startsWith("STONE_")
                        || n.startsWith("WOODEN_"))
                        && endsAny(n, "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "SWORD", "AXE",
                        "PICKAXE", "SHOVEL", "HOE", "SPEAR", "HORSE_ARMOR")), 1));
        add(boxes, "Nether e End", NamedTextColor.DARK_RED, cap(takeBy(n ->
                has(n, "NETHER", "SOUL", "CRIMSON", "WARPED", "BASALT", "BLACKSTONE", "MAGMA",
                        "SHROOMLIGHT", "END_", "CHORUS", "PURPUR", "ENDER", "GLOWSTONE", "QUARTZ")), 2));
        add(boxes, "Pedras e Tijolos", NamedTextColor.GRAY, cap(takeBy(n ->
                has(n, "STONE", "DEEPSLATE", "TUFF", "ANDESITE", "DIORITE", "GRANITE", "CALCITE",
                        "DRIPSTONE", "SANDSTONE", "BRICK", "PRISMARINE", "OBSIDIAN", "MUD", "CLAY")), 3));
        add(boxes, "Plantas e Flores", NamedTextColor.GREEN, cap(takeBy(n ->
                has(n, "SAPLING", "FLOWER", "TULIP", "ROSE", "ORCHID", "ALLIUM", "AZALEA", "LILAC",
                        "PEONY", "DANDELION", "POPPY", "CORNFLOWER", "BLUET", "DAISY", "LILY",
                        "TORCHFLOWER", "PITCHER", "FERN", "GRASS", "VINE", "MOSS", "MUSHROOM",
                        "BAMBOO", "KELP", "CACTUS", "SUGAR_CANE", "PUMPKIN", "MELON", "SEEDS",
                        "WHEAT", "LEAVES", "BUSH", "DRIPLEAF", "PROPAGULE", "WILDFLOWERS")), 2));

        // O kit nunca passa de MAX_BOXES (36) boxes, para caber no inventário.
        return boxes;
    }
}
