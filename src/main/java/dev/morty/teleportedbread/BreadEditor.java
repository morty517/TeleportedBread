package dev.morty.teleportedbread;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Сундучный редактор хлебов. Всё, что меняется здесь, сразу пишется в config.yml и применяется.
 */
final class BreadEditor implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");
    private static final List<String> PARTICLE_CYCLE = List.of(
            "portal", "reverse_portal", "dust", "heart", "witch", "flame", "soul_fire_flame", "end_rod",
            "happy_villager", "angry_villager", "enchant", "crit", "snowflake", "cloud", "smoke", "note",
            "totem_of_undying", "glow", "electric_spark", "soul", "falling_obsidian_tear", "entity_effect");

    private final TeleportedBread plugin;
    private final Map<UUID, Prompt> prompts = new HashMap<>();

    private record Prompt(Consumer<String> onInput, Menu returnTo) {}

    BreadEditor(TeleportedBread plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    /** Сохранить + применить + перерисовать меню. */
    private void changed(Menu m) {
        plugin.applyChanges();
        m.viewer.playSound(m.viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
        m.refresh();
    }

    void openMain(Player p) {
        new MainMenu(p, 0).open();
    }

    // =====================================================================================
    //  каркас меню
    // =====================================================================================

    private abstract class Menu implements InventoryHolder {
        final Player viewer;
        final Inventory inv;
        final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

        Menu(Player viewer, int rows, String title) {
            this.viewer = viewer;
            this.inv = Bukkit.createInventory(this, rows * 9, LEGACY.deserialize(title));
        }

        abstract void build();

        void refresh() {
            inv.clear();
            actions.clear();
            build();
        }

        void open() {
            refresh();
            viewer.openInventory(inv);
        }

        void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
            inv.setItem(slot, item);
            if (action != null) actions.put(slot, action);
        }

        void back(int slot, Menu target) {
            set(slot, icon(Material.ARROW, "&7← Назад"), e -> target.open());
        }

        void filler() {
            ItemStack pane = icon(Material.BLACK_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
        int top = e.getView().getTopInventory().getSize();

        if (e.getRawSlot() >= top) {
            // в своём инвентаре можно брать предметы на курсор (нужно для выбора модели),
            // но нельзя закидывать их в меню
            if (e.isShiftClick() || e.getAction() == InventoryAction.COLLECT_TO_CURSOR) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Consumer<InventoryClickEvent> action = menu.actions.get(e.getRawSlot());
        if (action != null) action.accept(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu)) return;
        int top = e.getView().getTopInventory().getSize();
        for (int slot : e.getRawSlots()) {
            if (slot < top) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // =====================================================================================
    //  ввод из чата
    // =====================================================================================

    private void ask(Menu from, String question, String example, Consumer<String> onInput) {
        Player p = from.viewer;
        prompts.put(p.getUniqueId(), new Prompt(onInput, from));
        p.closeInventory();
        p.sendMessage(LEGACY.deserialize("&d✎ &f" + question));
        if (example != null) p.sendMessage(LEGACY.deserialize("&7  Пример: &f" + example));
        p.sendMessage(LEGACY.deserialize("&7  Напиши в чат. &cотмена&7 — вернуться без изменений"));
    }

    /** Кто только что ответил на вопрос — второе чат-событие с тем же сообщением тоже глушим. */
    private final Map<UUID, Long> swallow = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Paper шлёт сообщение через два события (старое AsyncPlayerChatEvent и новое AsyncChatEvent),
     * а чат-плагины типа EssentialsX Chat могут сидеть на любом из них. Глушим оба.
     * @return true если сообщение надо скрыть
     */
    private boolean consumeChat(Player p, String text) {
        UUID id = p.getUniqueId();
        Prompt prompt = prompts.remove(id);
        if (prompt == null) {
            Long until = swallow.get(id);
            return until != null && System.currentTimeMillis() < until;
        }
        swallow.put(id, System.currentTimeMillis() + 2000);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
                p.sendMessage(LEGACY.deserialize("&7Отменено"));
            } else {
                try {
                    prompt.onInput().accept(text);
                } catch (Exception ex) {
                    p.sendMessage(LEGACY.deserialize("&cНе получилось: " + ex.getMessage()));
                }
            }
            prompt.returnTo().open();
        });
        return true;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        if (consumeChat(e.getPlayer(), e.getMessage().trim())) {
            e.setCancelled(true);
            e.getRecipients().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (consumeChat(e.getPlayer(), text)) {
            e.setCancelled(true);
            e.viewers().clear();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
        swallow.remove(e.getPlayer().getUniqueId());
    }

    // =====================================================================================
    //  кнопки-помощники
    // =====================================================================================

    private void toggle(Menu m, int slot, String path, boolean def, String title, String desc) {
        boolean v = cfg().getBoolean(path, def);
        set(m, slot, icon(v ? Material.LIME_DYE : Material.GRAY_DYE,
                (v ? "&a✔ " : "&c✘ ") + title,
                "&7" + desc, "", "&7Сейчас: " + (v ? "&aвкл" : "&cвыкл"), "&eКлик — переключить"),
                e -> {
                    cfg().set(path, !v);
                    changed(m);
                });
    }

    private static void set(Menu m, int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        m.set(slot, item, action);
    }

    /** Числовая кнопка: ЛКМ/ПКМ ±step, Shift ±shiftStep, Q — ввести вручную. */
    private void number(Menu m, int slot, Material mat, String title, String path, double def,
                        double step, double shiftStep, double min, double max, String unit, String desc) {
        double v = cfg().getDouble(path, def);
        set(m, slot, icon(mat, "&f" + title + ": &e" + TeleportedBread.fmt(v) + unit,
                "&7" + desc, "",
                "&eЛКМ / ПКМ: &f+" + TeleportedBread.fmt(step) + " / -" + TeleportedBread.fmt(step),
                "&eShift+ЛКМ / Shift+ПКМ: &f+" + TeleportedBread.fmt(shiftStep) + " / -" + TeleportedBread.fmt(shiftStep),
                "&eQ: &fввести число вручную"),
                e -> {
                    double nv = v;
                    switch (e.getClick()) {
                        case LEFT -> nv += step;
                        case RIGHT -> nv -= step;
                        case SHIFT_LEFT -> nv += shiftStep;
                        case SHIFT_RIGHT -> nv -= shiftStep;
                        case DROP, CONTROL_DROP -> {
                            ask(m, title + " (от " + TeleportedBread.fmt(min) + " до " + TeleportedBread.fmt(max) + ")",
                                    TeleportedBread.fmt(v), s -> {
                                        double x = Double.parseDouble(s.replace(',', '.'));
                                        cfg().set(path, round(clamp(x, min, max)));
                                        plugin.applyChanges();
                                    });
                            return;
                        }
                        default -> { return; }
                    }
                    cfg().set(path, round(clamp(nv, min, max)));
                    changed(m);
                });
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(double v) {
        return new java.math.BigDecimal(v).setScale(10, java.math.RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();
    }

    private static ItemStack icon(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(LEGACY.deserialize(name)));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(noItalic(LEGACY.deserialize(s)));
            meta.lore(l);
        }
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack withLore(ItemStack item, String... extra) {
        ItemMeta meta = item.getItemMeta();
        List<Component> l = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (String s : extra) l.add(noItalic(LEGACY.deserialize(s)));
        meta.lore(l);
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack breadIcon(String id) {
        ItemStack it = plugin.create(id, 1);
        return it != null ? it : icon(Material.BARRIER, "&c??? " + id, "&7Такого хлеба нет в конфиге");
    }

    private String nameOf(String id) {
        return LEGACY.serialize(plugin.breadName(id));
    }

    private String branchesSummary(String path) {
        ConfigurationSection s = cfg().getConfigurationSection(path);
        if (s == null || s.getKeys(false).isEmpty()) return "&8нет (финальная стадия)";
        List<String> parts = new ArrayList<>();
        for (String k : s.getKeys(false)) parts.add("&f" + TeleportedBread.fmt(s.getDouble(k)) + "% " + nameOf(k));
        return String.join("&7, ", parts);
    }

    private void give(Player p, String id, int amount) {
        ItemStack it = plugin.create(id, amount);
        if (it == null) return;
        for (ItemStack over : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), over);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
    }

    // =====================================================================================
    //  главное меню
    // =====================================================================================

    private final class MainMenu extends Menu {
        static final int PER_PAGE = 36;
        final int page;

        MainMenu(Player p, int page) {
            super(p, 6, "&8🍞 Хлебная лаборатория");
            this.page = page;
        }

        @Override
        void build() {
            set(4, withLore(new ItemStack(Material.BREAD),
                    "&7Обычный хлеб, с него всё начинается", "",
                    "&7Мутирует в: " + branchesSummary("start"), "",
                    "&eКлик — настроить ветки"),
                    e -> new BranchMenu(viewer, "start", "Обычный хлеб", this).open());

            List<String> ids = plugin.breadIds();
            int pages = Math.max(1, (ids.size() + PER_PAGE - 1) / PER_PAGE);
            int from = page * PER_PAGE;
            for (int i = 0; i < PER_PAGE && from + i < ids.size(); i++) {
                String id = ids.get(from + i);
                set(9 + i, withLore(breadIcon(id), "",
                        "&8id: " + id,
                        "&7Дальше: " + branchesSummary("breads." + id + ".next"), "",
                        "&eЛКМ — редактировать",
                        "&eShift+ЛКМ — взять 1 шт",
                        "&eShift+ПКМ — взять стак"),
                        e -> {
                            switch (e.getClick()) {
                                case SHIFT_LEFT -> give(viewer, id, 1);
                                case SHIFT_RIGHT -> give(viewer, id, 64);
                                default -> new BreadMenu(viewer, id, this).open();
                            }
                        });
            }

            if (page > 0) set(45, icon(Material.ARROW, "&f← Страница " + page),
                    e -> new MainMenu(viewer, page - 1).open());
            set(46, icon(Material.COMPARATOR, "&fОбщие настройки", "&7Вкл/выкл, триггеры, сообщения, команды"),
                    e -> new SettingsMenu(viewer, this).open());
            set(47, icon(Material.OAK_SAPLING, "&fПоказать дерево в чате"), e -> {
                viewer.closeInventory();
                viewer.performCommand("tbread tree");
            });
            set(49, icon(Material.NETHER_STAR, "&a+ Новый хлеб", "&7Придумай id латиницей, например &fburnt"),
                    e -> ask(this, "id нового хлеба (a-z, 0-9, _)", "burnt", s -> {
                        String id = s.toLowerCase();
                        if (!ID_PATTERN.matcher(id).matches()) throw new IllegalArgumentException("id только из a-z, 0-9 и _");
                        if (id.equals("plain") || plugin.hasBread(id)) throw new IllegalArgumentException("такой id уже занят");
                        String base = "breads." + id;
                        cfg().set(base + ".name", "&fНовый хлеб");
                        cfg().set(base + ".lore", List.of("&7Свежеиспечённая мутация"));
                        cfg().set(base + ".model", "none");
                        cfg().set(base + ".glint", true);
                        cfg().set(base + ".effects", new ArrayList<String>());
                        plugin.applyChanges();
                        viewer.sendMessage(LEGACY.deserialize("&aХлеб &f" + id + "&a создан! Не забудь добавить ветку, которая в него ведёт 🌱"));
                    }));
            set(51, icon(Material.CLOCK, "&fПеречитать config.yml с диска", "&7Если правил файл руками"), e -> {
                plugin.reloadFromDisk();
                viewer.sendMessage(LEGACY.deserialize("&aКонфиг перечитан"));
                refresh();
            });
            set(52, icon(Material.BARRIER, "&cЗакрыть"), e -> viewer.closeInventory());
            if (page + 1 < pages) set(53, icon(Material.ARROW, "&fСтраница " + (page + 2) + " →"),
                    e -> new MainMenu(viewer, page + 1).open());
            filler();
        }
    }

    // =====================================================================================
    //  редактор одного хлеба
    // =====================================================================================

    private final class BreadMenu extends Menu {
        final String id;
        final String base;
        final Menu parent;

        BreadMenu(Player p, String id, Menu parent) {
            super(p, 6, "&8Хлеб: " + id);
            this.id = id;
            this.base = "breads." + id;
            this.parent = parent;
        }

        @Override
        void build() {
            if (!plugin.hasBread(id)) {
                set(22, icon(Material.BARRIER, "&cХлеба больше нет"), e -> parent.open());
                back(45, parent);
                return;
            }

            set(4, withLore(breadIcon(id), "", "&8id: " + id, "&eShift+ЛКМ — взять 1 шт"), e -> {
                if (e.getClick() == ClickType.SHIFT_LEFT) give(viewer, id, 1);
            });

            // ---- внешний вид
            set(19, icon(Material.NAME_TAG, "&fНазвание",
                    "&7Сейчас: " + cfg().getString(base + ".name", id), "", "&eКлик — ввести новое (цвета через &)"),
                    e -> ask(this, "Новое название хлеба", "&aЗелёный хлеб", s -> {
                        cfg().set(base + ".name", s);
                        plugin.applyChanges();
                    }));

            List<String> lore = cfg().getStringList(base + ".lore");
            List<String> loreView = new ArrayList<>();
            loreView.add("&7Сейчас:");
            if (lore.isEmpty()) loreView.add("&8  (пусто)");
            for (String l : lore) loreView.add("&8  » &r" + l);
            loreView.add("");
            loreView.add("&eКлик — ввести заново, строки через &f|");
            loreView.add("&eПКМ — очистить");
            set(20, icon(Material.WRITABLE_BOOK, "&fОписание (лор)", loreView.toArray(String[]::new)), e -> {
                if (e.getClick() == ClickType.RIGHT) {
                    cfg().set(base + ".lore", new ArrayList<String>());
                    changed(this);
                    return;
                }
                ask(this, "Описание хлеба, строки разделяй |", "&7Первая строка | &8Вторая строка", s -> {
                    List<String> lines = new ArrayList<>();
                    for (String part : s.split("\\|")) lines.add(part.trim());
                    cfg().set(base + ".lore", lines);
                    plugin.applyChanges();
                });
            });

            String model = cfg().getString(base + ".model", "none");
            set(21, icon(Material.ITEM_FRAME, "&fМоделька",
                    "&7Сейчас: &f" + model, "",
                    "&eВозьми любой предмет на курсор и кликни сюда —",
                    "&e  хлеб будет выглядеть как этот предмет",
                    "&eЛКМ без предмета — ввести id вручную",
                    "&eПКМ — вернуть обычный хлеб"),
                    e -> {
                        ItemStack cursor = e.getCursor();
                        if (cursor != null && !cursor.getType().isAir()) {
                            NamespacedKey key = cursor.hasItemMeta() && cursor.getItemMeta().hasItemModel()
                                    ? cursor.getItemMeta().getItemModel()
                                    : cursor.getType().getKey();
                            cfg().set(base + ".model", key.toString());
                            changed(this);
                        } else if (e.getClick() == ClickType.RIGHT) {
                            cfg().set(base + ".model", "none");
                            changed(this);
                        } else {
                            ask(this, "id модели (ванильный предмет)", "minecraft:slime_ball", s -> {
                                NamespacedKey k = NamespacedKey.fromString(s.toLowerCase());
                                if (k == null) throw new IllegalArgumentException("кривой id");
                                cfg().set(base + ".model", k.toString());
                                plugin.applyChanges();
                            });
                        }
                    });

            toggle(this, 22, base + ".glint", false, "Блеск зачарования", "Переливается как зачарованный");

            // ---- механика
            List<String> effects = cfg().getStringList(base + ".effects");
            set(23, icon(Material.POTION, "&fЭффекты при поедании",
                    "&7Сейчас: &f" + (effects.isEmpty() ? "нет" : String.join(", ", effects)), "", "&eКлик — открыть"),
                    e -> new EffectsMenu(viewer, base + ".effects", "Эффекты: " + id, this, false).open());

            set(24, icon(Material.OAK_SAPLING, "&fВо что мутирует дальше",
                    "&7" + branchesSummary(base + ".next"), "", "&eКлик — настроить ветки"),
                    e -> new BranchMenu(viewer, base + ".next", nameOf(id), this).open());

            // откуда в него можно попасть
            List<String> parents = new ArrayList<>();
            ConfigurationSection st = cfg().getConfigurationSection("start");
            if (st != null && st.contains(id)) parents.add("&fОбычный хлеб &7(" + TeleportedBread.fmt(st.getDouble(id)) + "%)");
            for (String other : plugin.breadIds()) {
                ConfigurationSection n = cfg().getConfigurationSection("breads." + other + ".next");
                if (n != null && n.contains(id)) parents.add(nameOf(other) + " &7(" + TeleportedBread.fmt(n.getDouble(id)) + "%)");
            }
            List<String> pl = new ArrayList<>();
            pl.add("&7Получается из:");
            if (parents.isEmpty()) pl.add("&c  ниоткуда! добавь ветку в другом хлебе");
            for (String s : parents) pl.add("&8  « " + s);
            if (cfg().contains(base + ".legacy-stage")) {
                pl.add("");
                pl.add("&8legacy-stage: " + cfg().getInt(base + ".legacy-stage"));
            }
            set(25, icon(Material.MAP, "&fПроисхождение", pl.toArray(String[]::new)), null);

            // ---- приколы при поедании
            boolean hasP = cfg().isConfigurationSection(base + ".on-eat.particles");
            boolean hasE = cfg().isConfigurationSection(base + ".on-eat.explosion");
            boolean hasC = cfg().isConfigurationSection(base + ".on-eat.contagion");
            set(29, icon(Material.BLAZE_POWDER, "&fАура частиц " + (hasP ? "&a(вкл)" : "&8(выкл)"),
                    "&7Частички вокруг игрока после поедания", "", "&eКлик — открыть"),
                    e -> new ParticlesMenu(viewer, base + ".on-eat.particles", id, this).open());
            set(31, icon(Material.TNT, "&fВзрыв " + (hasE ? "&a(вкл)" : "&8(выкл)"),
                    "&7Шанс бахнуть после поедания", "", "&eКлик — открыть"),
                    e -> new ExplosionMenu(viewer, base + ".on-eat.explosion", id, this).open());
            set(33, icon(Material.FERMENTED_SPIDER_EYE, "&fЗараза " + (hasC ? "&a(вкл)" : "&8(выкл)"),
                    "&7Передача эффектов через удары", "", "&eКлик — открыть"),
                    e -> new ContagionMenu(viewer, base + ".on-eat.contagion", id, this).open());

            back(45, parent);
            set(53, icon(Material.LAVA_BUCKET, "&cУдалить хлеб",
                    "&7Удалит хлеб и все ветки, которые в него ведут", "", "&cShift+ПКМ — удалить навсегда"),
                    e -> {
                        if (e.getClick() != ClickType.SHIFT_RIGHT) return;
                        cfg().set(base, null);
                        cfg().set("start." + id, null);
                        for (String other : plugin.breadIds()) cfg().set("breads." + other + ".next." + id, null);
                        plugin.applyChanges();
                        viewer.sendMessage(LEGACY.deserialize("&cХлеб &f" + id + "&c удалён. Предметы у игроков останутся, но мутировать не будут"));
                        parent.open();
                    });
            filler();
        }
    }

    // =====================================================================================
    //  эффекты
    // =====================================================================================

    private final class EffectsMenu extends Menu {
        final String path;
        final Menu parent;
        final boolean emptyMeansBread;

        EffectsMenu(Player p, String path, String title, Menu parent, boolean emptyMeansBread) {
            super(p, 4, "&8" + title);
            this.path = path;
            this.parent = parent;
            this.emptyMeansBread = emptyMeansBread;
        }

        @Override
        void build() {
            List<String> list = new ArrayList<>(cfg().getStringList(path));
            list.removeIf(s -> s == null || s.isBlank());

            for (int i = 0; i < list.size() && i < 27; i++) {
                final int idx = i;
                String raw = list.get(i);
                String[] p = raw.trim().split("\\s+");
                boolean valid = plugin.isValidEffect(raw);
                ItemStack it;
                if (valid) {
                    int level = Integer.parseInt(p[1]);
                    double sec = Double.parseDouble(p[2].replace(',', '.'));
                    it = icon(Material.POTION, "&d" + p[0] + " " + level,
                            "&7Длительность: &f" + timeStr(sec), "",
                            "&eЛКМ / ПКМ: &fуровень +1 / -1",
                            "&eShift+ЛКМ / Shift+ПКМ: &fвремя +10с / -10с",
                            "&eQ: &fввести строку заново",
                            "&cCtrl+Q: &fудалить");
                    PotionEffectType type = plugin.effectType(p[0]);
                    if (type != null && it.getItemMeta() instanceof PotionMeta pm) {
                        pm.setColor(type.getColor());
                        it.setItemMeta(pm);
                    }
                } else {
                    it = icon(Material.BARRIER, "&cСломанный эффект: &f" + raw,
                            "&eQ: &fввести заново", "&cCtrl+Q: &fудалить");
                }
                set(i, it, e -> {
                    List<String> cur = new ArrayList<>(cfg().getStringList(path));
                    cur.removeIf(s -> s == null || s.isBlank());
                    if (idx >= cur.size()) return;
                    if (e.getClick() == ClickType.CONTROL_DROP) {
                        cur.remove(idx);
                        cfg().set(path, cur);
                        changed(this);
                        return;
                    }
                    if (e.getClick() == ClickType.DROP) {
                        ask(this, "Эффект в виде: название уровень секунды", cur.get(idx), s -> {
                            if (!plugin.isValidEffect(s)) throw new IllegalArgumentException("не понял эффект '" + s + "'");
                            List<String> c2 = new ArrayList<>(cfg().getStringList(path));
                            c2.removeIf(x -> x == null || x.isBlank());
                            if (idx < c2.size()) c2.set(idx, s.trim().toLowerCase());
                            cfg().set(path, c2);
                            plugin.applyChanges();
                        });
                        return;
                    }
                    if (!valid) return;
                    int level = Integer.parseInt(p[1]);
                    double sec = Double.parseDouble(p[2].replace(',', '.'));
                    switch (e.getClick()) {
                        case LEFT -> level = Math.min(255, level + 1);
                        case RIGHT -> level = Math.max(1, level - 1);
                        case SHIFT_LEFT -> sec = sec + 10;
                        case SHIFT_RIGHT -> sec = Math.max(1, sec - 10);
                        default -> { return; }
                    }
                    cur.set(idx, p[0] + " " + level + " " + TeleportedBread.fmt(round(sec)));
                    cfg().set(path, cur);
                    changed(this);
                });
            }

            if (list.isEmpty()) {
                set(13, icon(Material.GLASS_BOTTLE, "&7Эффектов нет",
                        emptyMeansBread ? "&7Пусто = передаются эффекты самого хлеба" : "&7Хлеб просто съедается"), null);
            }

            back(27, parent);
            set(31, icon(Material.EMERALD, "&a+ Добавить эффект",
                    "&7Формат: &fназвание уровень секунды",
                    "&7Названия как в /effect: speed, poison,",
                    "&7  levitation, night_vision, nausea..."),
                    e -> ask(this, "Новый эффект: название уровень секунды", "speed 2 60", s -> {
                        if (!plugin.isValidEffect(s)) throw new IllegalArgumentException("не понял эффект '" + s + "'");
                        List<String> cur = new ArrayList<>(cfg().getStringList(path));
                        cur.removeIf(x -> x == null || x.isBlank());
                        cur.add(s.trim().toLowerCase());
                        cfg().set(path, cur);
                        plugin.applyChanges();
                    }));
            filler();
        }
    }

    private static String timeStr(double sec) {
        int s = (int) Math.round(sec);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    // =====================================================================================
    //  ветки
    // =====================================================================================

    private final class BranchMenu extends Menu {
        final String path;
        final Menu parent;

        BranchMenu(Player p, String path, String fromName, Menu parent) {
            super(p, 6, "&8Ветки: " + PlainTextComponentSerializer.plainText().serialize(LEGACY.deserialize(fromName)));
            this.path = path;
            this.parent = parent;
        }

        @Override
        void build() {
            ConfigurationSection s = cfg().getConfigurationSection(path);
            List<String> targets = s == null ? List.of() : new ArrayList<>(s.getKeys(false));
            double sum = 0;

            for (int i = 0; i < targets.size() && i < 36; i++) {
                String target = targets.get(i);
                double chance = s.getDouble(target);
                sum += chance;
                String tpath = path + "." + target;
                set(i, withLore(breadIcon(target), "",
                        "&fШанс: &e" + TeleportedBread.fmt(chance) + "%", "",
                        "&eЛКМ / ПКМ: &f+1% / -1%",
                        "&eShift+ЛКМ / Shift+ПКМ: &f+0.1% / -0.1%",
                        "&eQ: &fввести точный шанс",
                        "&cCtrl+Q: &fубрать ветку"),
                        e -> {
                            double nv = chance;
                            switch (e.getClick()) {
                                case LEFT -> nv += 1;
                                case RIGHT -> nv -= 1;
                                case SHIFT_LEFT -> nv += 0.1;
                                case SHIFT_RIGHT -> nv -= 0.1;
                                case CONTROL_DROP -> {
                                    cfg().set(tpath, null);
                                    changed(this);
                                    return;
                                }
                                case DROP -> {
                                    ask(this, "Шанс в % (от 0.000000001 до 100)", TeleportedBread.fmt(chance), str -> {
                                        double x = Double.parseDouble(str.replace(',', '.').replace("%", ""));
                                        cfg().set(tpath, round(clamp(x, 0.000000001, 100)));
                                        plugin.applyChanges();
                                    });
                                    return;
                                }
                                default -> { return; }
                            }
                            cfg().set(tpath, round(clamp(nv, 0.000000001, 100)));
                            changed(this);
                        });
            }

            double nothing = Math.max(0, 100 - sum);
            set(40, icon(sum > 100 ? Material.RED_STAINED_GLASS_PANE : Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                    "&fИтого: &e" + TeleportedBread.fmt(round(sum)) + "%",
                    "&7Шанс, что ничего не случится: &f" + TeleportedBread.fmt(round(nothing)) + "%",
                    sum > 100 ? "&cБольше 100%! Последние ветки будут выпадать реже" : "&7За один телепорт выпадает максимум одна ветка"),
                    null);

            back(45, parent);
            set(49, icon(Material.EMERALD, "&a+ Добавить ветку", "&7Выбрать, во что ещё может мутировать"),
                    e -> new PickerMenu(viewer, this, target -> {
                        if (!cfg().contains(path + "." + target)) cfg().set(path + "." + target, 1.0);
                        plugin.applyChanges();
                    }).open());
            filler();
        }
    }

    private final class PickerMenu extends Menu {
        final Menu parent;
        final Consumer<String> onPick;

        PickerMenu(Player p, Menu parent, Consumer<String> onPick) {
            super(p, 6, "&8Выбери хлеб");
            this.parent = parent;
            this.onPick = onPick;
        }

        @Override
        void build() {
            List<String> ids = plugin.breadIds();
            for (int i = 0; i < ids.size() && i < 45; i++) {
                String id = ids.get(i);
                set(i, withLore(breadIcon(id), "", "&eКлик — выбрать"), e -> {
                    onPick.accept(id);
                    parent.open();
                });
            }
            back(49, parent);
            filler();
        }
    }

    // =====================================================================================
    //  частицы / взрыв / зараза
    // =====================================================================================

    /** Кнопка вкл/выкл для целого раздела on-eat.* — при включении создаёт дефолты. */
    private void sectionToggle(Menu m, String path, String title, Map<String, Object> defaults) {
        boolean on = cfg().isConfigurationSection(path);
        set(m, 4, icon(on ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                (on ? "&a✔ " : "&c✘ ") + title,
                "&eКлик — " + (on ? "выключить (настройки удалятся)" : "включить")),
                e -> {
                    if (on) {
                        cfg().set(path, null);
                    } else {
                        for (Map.Entry<String, Object> d : defaults.entrySet()) cfg().set(path + "." + d.getKey(), d.getValue());
                    }
                    changed(m);
                });
    }

    private final class ParticlesMenu extends Menu {
        final String path;
        final Menu parent;

        ParticlesMenu(Player p, String path, String id, Menu parent) {
            super(p, 4, "&8Частицы: " + id);
            this.path = path;
            this.parent = parent;
        }

        @Override
        void build() {
            Map<String, Object> def = new java.util.LinkedHashMap<>();
            def.put("type", "portal");
            def.put("count", 10);
            def.put("spread", 0.5);
            def.put("duration-seconds", 10);
            sectionToggle(this, path, "Аура частиц", def);

            if (cfg().isConfigurationSection(path)) {
                String type = cfg().getString(path + ".type", "portal").toLowerCase();
                boolean usable = plugin.isUsableParticle(type);
                set(10, icon(usable ? Material.FIREWORK_STAR : Material.BARRIER, "&fТип: &e" + type,
                        usable ? "&7" : "&cЭту частицу нельзя использовать!", "",
                        "&eЛКМ / ПКМ: &fследующая / предыдущая",
                        "&eQ: &fввести название вручную",
                        "&7Цвет работает у &fdust&7 и &fentity_effect"),
                        e -> {
                            if (e.getClick() == ClickType.DROP) {
                                ask(this, "Название частицы", "dust", s -> {
                                    if (!plugin.isUsableParticle(s)) throw new IllegalArgumentException("такую частицу нельзя или её нет");
                                    cfg().set(path + ".type", s.toLowerCase());
                                    plugin.applyChanges();
                                });
                                return;
                            }
                            int dir = e.getClick().isRightClick() ? -1 : 1;
                            int idx = PARTICLE_CYCLE.indexOf(type);
                            for (int tries = 0; tries < PARTICLE_CYCLE.size(); tries++) {
                                idx = Math.floorMod(idx + dir, PARTICLE_CYCLE.size());
                                if (plugin.isUsableParticle(PARTICLE_CYCLE.get(idx))) break;
                            }
                            cfg().set(path + ".type", PARTICLE_CYCLE.get(idx));
                            changed(this);
                        });
                number(this, 11, Material.GLOWSTONE_DUST, "Кол-во за раз", path + ".count", 10, 1, 5, 1, 200, "",
                        "Сколько частиц вылетает каждые 0.2 сек");
                number(this, 12, Material.COMPASS, "Разброс", path + ".spread", 0.5, 0.1, 0.5, 0, 5, " бл.",
                        "Насколько далеко от игрока");
                number(this, 13, Material.CLOCK, "Длительность", path + ".duration-seconds", 10, 1, 10, 1, 3600, " сек",
                        "Сколько висит аура после поедания");

                String color = cfg().getString(path + ".color", "#ffffff");
                ItemStack dye = icon(Material.LEATHER_CHESTPLATE, "&fЦвет: &e" + color,
                        "&7Только для dust и entity_effect", "", "&eКлик — ввести #RRGGBB");
                try {
                    if (dye.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) {
                        lm.setColor(Color.fromRGB(Integer.parseInt(color.replace("#", ""), 16)));
                        dye.setItemMeta(lm);
                    }
                } catch (Exception ignored) { }
                set(15, dye, e -> ask(this, "Цвет в формате #RRGGBB", "#7CFC00", s -> {
                    String h = s.trim();
                    if (!h.startsWith("#")) h = "#" + h;
                    if (!h.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("нужно #RRGGBB");
                    cfg().set(path + ".color", h.toUpperCase());
                    plugin.applyChanges();
                }));
                number(this, 16, Material.SLIME_BALL, "Размер (dust)", path + ".size", 1.0, 0.1, 0.5, 0.1, 4, "",
                        "Размер пылинок, только для dust");
            }
            back(27, parent);
            filler();
        }
    }

    private final class ExplosionMenu extends Menu {
        final String path;
        final Menu parent;

        ExplosionMenu(Player p, String path, String id, Menu parent) {
            super(p, 4, "&8Взрыв: " + id);
            this.path = path;
            this.parent = parent;
        }

        @Override
        void build() {
            Map<String, Object> def = new java.util.LinkedHashMap<>();
            def.put("chance", 10.0);
            def.put("power", 2.0);
            def.put("delay-seconds", 1.5);
            def.put("break-blocks", false);
            def.put("fire", false);
            def.put("self-damage", true);
            sectionToggle(this, path, "Взрыв", def);

            if (cfg().isConfigurationSection(path)) {
                number(this, 10, Material.REDSTONE, "Шанс", path + ".chance", 10, 1, 0.1, 0, 100, "%",
                        "Шанс бахнуть после поедания");
                number(this, 11, Material.GUNPOWDER, "Сила", path + ".power", 2, 0.5, 0.1, 0.1, 20, "",
                        "Крипер = 3, ТНТ = 4, заряженный крипер = 6");
                number(this, 12, Material.CLOCK, "Задержка", path + ".delay-seconds", 1.5, 0.5, 0.1, 0, 60, " сек",
                        "Сколько шипит перед взрывом");
                toggle(this, 14, path + ".break-blocks", false, "Ломает блоки", "Осторожно на спавне 😅");
                toggle(this, 15, path + ".fire", false, "Поджигает", "Оставляет огонь вокруг");
                toggle(this, 16, path + ".self-damage", true, "Урон съевшему", "Выкл = бессмертный камикадзе");
            }
            back(27, parent);
            filler();
        }
    }

    private final class ContagionMenu extends Menu {
        final String path;
        final String id;
        final Menu parent;

        ContagionMenu(Player p, String path, String id, Menu parent) {
            super(p, 4, "&8Зараза: " + id);
            this.path = path;
            this.id = id;
            this.parent = parent;
        }

        @Override
        void build() {
            Map<String, Object> def = new java.util.LinkedHashMap<>();
            def.put("duration-seconds", 60);
            def.put("chance", 30.0);
            def.put("effects", new ArrayList<String>());
            def.put("transfer", false);
            def.put("spreads", false);
            def.put("mobs", false);
            sectionToggle(this, path, "Зараза", def);

            if (cfg().isConfigurationSection(path)) {
                number(this, 10, Material.CLOCK, "Заразный", path + ".duration-seconds", 60, 5, 30, 1, 3600, " сек",
                        "Сколько после поедания можно заражать");
                number(this, 11, Material.REDSTONE, "Шанс за удар", path + ".chance", 30, 1, 5, 0, 100, "%",
                        "Шанс передать эффекты одним ударом");
                List<String> eff = cfg().getStringList(path + ".effects");
                eff.removeIf(s -> s == null || s.isBlank());
                set(12, icon(Material.SPLASH_POTION, "&fЧто передаётся",
                        "&7Сейчас: &f" + (eff.isEmpty() ? "эффекты самого хлеба" : String.join(", ", eff)), "",
                        "&eКлик — открыть"),
                        e -> new EffectsMenu(viewer, path + ".effects", "Зараза: " + id, this, true).open());
                toggle(this, 14, path + ".transfer", false, "Передача", "Вкл = у тебя эффекты пропадают, у жертвы появляются");
                toggle(this, 15, path + ".spreads", false, "Цепная зараза", "Вкл = заражённый тоже становится заразным 🧟");
                toggle(this, 16, path + ".mobs", false, "Заражать мобов", "Выкл = только игроков");
            }
            back(27, parent);
            filler();
        }
    }

    // =====================================================================================
    //  общие настройки
    // =====================================================================================

    private final class SettingsMenu extends Menu {
        final Menu parent;

        SettingsMenu(Player p, Menu parent) {
            super(p, 4, "&8Общие настройки");
            this.parent = parent;
        }

        @Override
        void build() {
            toggle(this, 10, "enabled", true, "Плагин включён", "Выкл = хлеб вообще не мутирует");
            toggle(this, 11, "triggers.commands", true, "Мутация от команд", "/warp, /home, /spawn и т.д.");
            toggle(this, 12, "triggers.portals", true, "Мутация от порталов", "Ад, энд, энд-гейтвеи");
            number(this, 13, Material.CLOCK, "Ожидание тп", "command-timeout-seconds", 15, 1, 5, 1, 300, " сек",
                    "Сколько ждать телепорт после команды (больше warmup'а Essentials)");

            List<String> cmds = cfg().getStringList("commands");
            set(14, icon(Material.COMMAND_BLOCK, "&fКоманды-телепорты",
                    "&7Сейчас: &f" + String.join(", ", cmds), "", "&eКлик — ввести список через запятую"),
                    e -> ask(this, "Команды через запятую (без /)", "warp, home, spawn, tpa", s -> {
                        List<String> list = new ArrayList<>();
                        for (String c : s.split("[,\\s]+")) {
                            String t = c.trim().toLowerCase().replace("/", "");
                            if (!t.isEmpty()) list.add(t);
                        }
                        cfg().set("commands", list);
                        plugin.applyChanges();
                    }));

            set(15, icon(Material.OAK_SIGN, "&fСообщение о мутации",
                    "&7Сейчас: &r" + cfg().getString("mutation-message", ""), "",
                    "&eКлик — ввести новое", "&eПКМ — убрать сообщение"),
                    e -> {
                        if (e.getClick() == ClickType.RIGHT) {
                            cfg().set("mutation-message", "");
                            changed(this);
                            return;
                        }
                        ask(this, "Сообщение в actionbar при мутации", "&5&oЧто-то в инвентаре зашевелилось...", s -> {
                            cfg().set("mutation-message", s);
                            plugin.applyChanges();
                        });
                    });
            set(16, icon(Material.SPIDER_EYE, "&fСообщение о заражении",
                    "&7Сейчас: &r" + cfg().getString("contagion-message", ""), "&7%bread% = название хлеба", "",
                    "&eКлик — ввести новое", "&eПКМ — убрать сообщение"),
                    e -> {
                        if (e.getClick() == ClickType.RIGHT) {
                            cfg().set("contagion-message", "");
                            changed(this);
                            return;
                        }
                        ask(this, "Сообщение заражённому (%bread% = хлеб)", "&7Тебя заразили: %bread%", s -> {
                            cfg().set("contagion-message", s);
                            plugin.applyChanges();
                        });
                    });
            back(27, parent);
            filler();
        }
    }
}
