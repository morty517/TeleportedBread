package dev.morty.teleportedbread;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Хлеб, который мутирует при телепортации — теперь с древом мутаций.
 * Обычный хлеб -> start -> breads.<id>.next -> ...
 */
public final class TeleportedBread extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final int DEDUPE_TICKS = 10;
    private static final int AURA_STEP = 4;
    private static final String PLAIN = "plain";

    // ------------------------------------------------------------------ модель конфига

    private record Particles(Particle type, Object data, int count, double spread, int durationTicks) {}

    private record Explosion(double chance, float power, int delayTicks, boolean breakBlocks, boolean fire,
                             boolean selfDamage) {}

    private record Contagion(int durationTicks, double chance, List<PotionEffect> effects,
                             boolean transfer, boolean spreads, boolean mobs) {}

    private record Bread(String id, Component name, List<Component> lore, NamespacedKey model, boolean glint,
                         List<PotionEffect> effects, LinkedHashMap<String, Double> next,
                         Particles particles, Explosion explosion, Contagion contagion) {}

    private record Aura(Particles p, long untilTick) {}

    private record Infection(String breadId, long untilTick) {}

    // ------------------------------------------------------------------ состояние

    private NamespacedKey typeKey;
    private NamespacedKey stageKey;   // старые предметы TeleportedBread 1.0.0 (стадия-число)
    private final NamespacedKey warpPearlKey = NamespacedKey.fromString("warppearl:bread_stage"); // WarpPearl 1.3.0

    private final LinkedHashMap<String, Double> start = new LinkedHashMap<>();
    private final Map<String, Bread> breads = new LinkedHashMap<>();
    private final Map<Integer, String> legacyStages = new HashMap<>();

    private final Map<UUID, Integer> lastMutationTick = new HashMap<>();
    private final Map<UUID, Long> pendingCommand = new HashMap<>();
    private final Map<UUID, Aura> auras = new HashMap<>();
    private final Map<UUID, Infection> infections = new HashMap<>();

    private BukkitTask ticker;
    private BreadEditor editor;
    private long tick = 0;

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "bread_type");
        stageKey = new NamespacedKey(this, "bread_stage");
        loadBreads();
        getServer().getPluginManager().registerEvents(this, this);
        editor = new BreadEditor(this);
        getServer().getPluginManager().registerEvents(editor, this);
        ticker = getServer().getScheduler().runTaskTimer(this, this::tickAuras, 1L, AURA_STEP);
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        auras.clear();
        infections.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("gui") || args[0].equalsIgnoreCase("edit")) {
            if (sender instanceof Player player) {
                editor.openMain(player);
            } else {
                sender.sendMessage(Component.text("GUI только для игроков. /tbread reload | tree"));
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadBreads();
            sender.sendMessage(Component.text("TeleportedBread: конфиг перезагружен, хлебов: " + breads.size() + " 🍞"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("tree")) {
            sender.sendMessage(Component.text("Обычный хлеб"));
            printTree(sender, start, "", new ArrayList<>());
            return true;
        }
        // /tbread give <id> [кол-во]
        if (args.length >= 2 && args[0].equalsIgnoreCase("give") && sender instanceof Player player) {
            int amount = 1;
            if (args.length >= 3) {
                try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) { }
            }
            ItemStack item = create(args[1], Math.max(1, Math.min(64, amount)));
            if (item == null) {
                sender.sendMessage(Component.text("Нет такого хлеба. Есть: plain, " + String.join(", ", breads.keySet())));
                return true;
            }
            player.getInventory().addItem(item);
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("gui", "reload", "give", "tree")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            if (PLAIN.startsWith(args[1].toLowerCase())) out.add(PLAIN);
            for (String id : breads.keySet()) if (id.startsWith(args[1].toLowerCase())) out.add(id);
        }
        return out;
    }

    private void printTree(CommandSender sender, Map<String, Double> branch, String indent, List<String> seen) {
        for (Map.Entry<String, Double> e : branch.entrySet()) {
            Bread b = breads.get(e.getKey());
            Component name = b == null ? Component.text("??? " + e.getKey()) : b.name();
            sender.sendMessage(Component.text(indent + "└ " + fmt(e.getValue()) + "% ").append(name));
            if (b != null && !seen.contains(b.id())) {
                List<String> s2 = new ArrayList<>(seen);
                s2.add(b.id());
                printTree(sender, b.next(), indent + "   ", s2);
            }
        }
    }

    // ------------------------------------------------------------------ загрузка конфига

    private void loadBreads() {
        start.clear();
        breads.clear();
        legacyStages.clear();

        start.putAll(readChances(getConfig().getConfigurationSection("start"), "start"));

        ConfigurationSection all = getConfig().getConfigurationSection("breads");
        if (all != null) {
            for (String id : all.getKeys(false)) {
                ConfigurationSection c = all.getConfigurationSection(id);
                if (c == null) continue;
                try {
                    breads.put(id, readBread(id, c));
                    if (c.contains("legacy-stage")) legacyStages.put(c.getInt("legacy-stage"), id);
                } catch (Exception ex) {
                    getLogger().warning("Хлеб '" + id + "' не загрузился: " + ex.getMessage());
                }
            }
        }

        // проверяем, что все ветки ведут в существующие хлеба
        checkTargets("start", start);
        for (Bread b : breads.values()) checkTargets(b.id(), b.next());

        getLogger().info("Хлебов загружено: " + breads.size() + " 🍞");
    }

    private void checkTargets(String from, Map<String, Double> next) {
        double sum = 0;
        for (Map.Entry<String, Double> e : next.entrySet()) {
            sum += e.getValue();
            if (!breads.containsKey(e.getKey())) {
                getLogger().warning("'" + from + "' ведёт в несуществующий хлеб '" + e.getKey() + "'");
            }
        }
        if (sum > 100.0001) {
            getLogger().warning("'" + from + "': сумма шансов " + fmt(sum) + "% > 100, последние ветки будут выпадать реже");
        }
    }

    private Bread readBread(String id, ConfigurationSection c) {
        Component name = noItalic(LEGACY.deserialize(c.getString("name", id)));

        List<Component> lore = new ArrayList<>();
        for (String line : c.getStringList("lore")) lore.add(noItalic(LEGACY.deserialize(line)));

        NamespacedKey model = null;
        String m = c.getString("model", "none");
        if (m != null && !m.isBlank() && !m.equalsIgnoreCase("none")) {
            model = NamespacedKey.fromString(m.toLowerCase());
            if (model == null) getLogger().warning(id + ": кривая модель '" + m + "'");
        }

        List<PotionEffect> effects = parseEffects(id, c.getStringList("effects"));
        LinkedHashMap<String, Double> next = readChances(c.getConfigurationSection("next"), id + ".next");

        Particles particles = null;
        ConfigurationSection pc = c.getConfigurationSection("on-eat.particles");
        if (pc != null) particles = readParticles(id, pc);

        Explosion explosion = null;
        ConfigurationSection ec = c.getConfigurationSection("on-eat.explosion");
        if (ec != null) {
            explosion = new Explosion(
                    ec.getDouble("chance", 0),
                    (float) ec.getDouble("power", 2),
                    (int) Math.round(ec.getDouble("delay-seconds", 1.5) * 20),
                    ec.getBoolean("break-blocks", false),
                    ec.getBoolean("fire", false),
                    ec.getBoolean("self-damage", true));
        }

        Contagion contagion = null;
        ConfigurationSection cc = c.getConfigurationSection("on-eat.contagion");
        if (cc != null) {
            List<PotionEffect> ce = parseEffects(id, cc.getStringList("effects"));
            contagion = new Contagion(
                    (int) Math.round(cc.getDouble("duration-seconds", 60) * 20),
                    cc.getDouble("chance", 30),
                    ce.isEmpty() ? effects : ce,
                    cc.getBoolean("transfer", false),
                    cc.getBoolean("spreads", false),
                    cc.getBoolean("mobs", false));
        }

        return new Bread(id, name, lore, model, c.getBoolean("glint", false), effects, next,
                particles, explosion, contagion);
    }

    private LinkedHashMap<String, Double> readChances(ConfigurationSection s, String where) {
        LinkedHashMap<String, Double> map = new LinkedHashMap<>();
        if (s == null) return map;
        for (String k : s.getKeys(false)) {
            double v = s.getDouble(k, -1);
            if (v <= 0) {
                getLogger().warning(where + "." + k + ": шанс должен быть числом > 0");
                continue;
            }
            map.put(k, v);
        }
        return map;
    }

    private Particles readParticles(String id, ConfigurationSection pc) {
        String typeName = pc.getString("type", "portal").toLowerCase();
        Particle type = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(typeName));
        if (type == null) {
            getLogger().warning(id + ": неизвестная частица '" + typeName + "'");
            return null;
        }
        Object data = null;
        Class<?> dataType = type.getDataType();
        if (dataType == Particle.DustOptions.class) {
            data = new Particle.DustOptions(parseColor(pc.getString("color", "#ffffff")), (float) pc.getDouble("size", 1.0));
        } else if (dataType == Color.class) {
            data = parseColor(pc.getString("color", "#ffffff"));
        } else if (dataType != Void.class) {
            getLogger().warning(id + ": частица '" + typeName + "' требует особых данных, возьми другую (portal, heart, dust, ...)");
            return null;
        }
        return new Particles(type, data,
                Math.max(1, pc.getInt("count", 10)),
                pc.getDouble("spread", 0.5),
                (int) Math.round(pc.getDouble("duration-seconds", 5) * 20));
    }

    private Color parseColor(String hex) {
        try {
            String h = hex.trim().replace("#", "");
            return Color.fromRGB(Integer.parseInt(h, 16));
        } catch (Exception ex) {
            getLogger().warning("Кривой цвет '" + hex + "', ставлю белый");
            return Color.WHITE;
        }
    }

    private List<PotionEffect> parseEffects(String id, List<String> raw) {
        List<PotionEffect> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            String[] p = s.trim().split("\\s+");
            if (p.length < 3) {
                getLogger().warning(id + ": эффект '" + s + "' должен быть в виде 'название уровень секунды'");
                continue;
            }
            NamespacedKey key = NamespacedKey.fromString(p[0].toLowerCase());
            PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
            if (type == null) {
                getLogger().warning(id + ": неизвестный эффект '" + p[0] + "'");
                continue;
            }
            try {
                int level = Integer.parseInt(p[1]);
                double seconds = Double.parseDouble(p[2].replace(',', '.'));
                out.add(new PotionEffect(type, (int) Math.round(seconds * 20), Math.max(0, Math.min(255, level - 1))));
            } catch (NumberFormatException ex) {
                getLogger().warning(id + ": кривые числа в эффекте '" + s + "'");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ мутация

    /** Прогнать весь хлеб в инвентаре через телепорт. Каждый кусок роллится отдельно, максимум 1 шаг. */
    void mutate(Player player) {
        if (!getConfig().getBoolean("enabled", true)) return;

        int now = Bukkit.getCurrentTick();
        Integer last = lastMutationTick.get(player.getUniqueId());
        if (last != null && now - last < DEDUPE_TICKS) return;
        lastMutationTick.put(player.getUniqueId(), now);

        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        Map<String, Integer> results = new LinkedHashMap<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            String type = typeOf(item);
            if (type == null) continue;

            Map<String, Double> next = type.equals(PLAIN) ? start : nextOf(type);
            if (next == null || next.isEmpty()) continue; // финальная стадия или неизвестный хлеб

            int amount = item.getAmount();
            int changed = 0;
            for (int i = 0; i < amount; i++) {
                String target = roll(next, rnd);
                if (target == null || !breads.containsKey(target)) continue;
                results.merge(target, 1, Integer::sum);
                changed++;
            }
            if (changed == 0) continue;

            if (changed == amount) {
                inv.setItem(slot, null);
            } else {
                ItemStack rest = item.clone();
                rest.setAmount(amount - changed);
                inv.setItem(slot, rest);
            }
        }

        if (results.isEmpty()) return;

        for (Map.Entry<String, Integer> e : results.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(64, left);
                left -= n;
                for (ItemStack over : inv.addItem(create(e.getKey(), n)).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), over);
                }
            }
        }

        String msg = getConfig().getString("mutation-message", "");
        if (msg != null && !msg.isBlank()) player.sendActionBar(LEGACY.deserialize(msg));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) player.playSound(player.getLocation(), Sound.BLOCK_CHORUS_FLOWER_GROW, 0.7f, 1.3f);
        }, 1L);
    }

    /** Выбрать ветку: одно число 0..100 и накопительная сумма шансов. null = ничего не случилось. */
    private static String roll(Map<String, Double> next, ThreadLocalRandom rnd) {
        double r = rnd.nextDouble() * 100.0;
        double acc = 0;
        for (Map.Entry<String, Double> e : next.entrySet()) {
            acc += e.getValue();
            if (r < acc) return e.getKey();
        }
        return null;
    }

    private Map<String, Double> nextOf(String id) {
        Bread b = breads.get(id);
        return b == null ? null : b.next();
    }

    /** null = не хлеб, "plain" = обычный хлеб, иначе id мутации. */
    String typeOf(ItemStack item) {
        if (item == null || item.getType() != Material.BREAD) return null;
        if (!item.hasItemMeta()) return PLAIN;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(typeKey, PersistentDataType.STRING);
        if (id != null) return id;

        Integer stage = pdc.get(stageKey, PersistentDataType.INTEGER);
        if (stage == null && warpPearlKey != null) stage = pdc.get(warpPearlKey, PersistentDataType.INTEGER);
        if (stage != null) {
            String legacy = legacyStages.get(stage);
            return legacy != null ? legacy : "legacy-" + stage; // неизвестная старая стадия — просто не мутирует
        }
        return PLAIN;
    }

    ItemStack create(String id, int amount) {
        if (id.equalsIgnoreCase(PLAIN)) return new ItemStack(Material.BREAD, amount);
        Bread b = breads.get(id);
        if (b == null) return null;

        ItemStack item = new ItemStack(Material.BREAD, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(b.name());
        if (!b.lore().isEmpty()) meta.lore(b.lore());
        if (b.model() != null) meta.setItemModel(b.model());
        if (b.glint()) meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, b.id());
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ поедание

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        String id = typeOf(e.getItem());
        if (id == null || id.equals(PLAIN)) return;
        Bread b = breads.get(id);
        if (b == null) return;
        Player p = e.getPlayer();

        // через тик, чтобы ваниль сначала доела хлеб
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            if (!b.effects().isEmpty()) p.addPotionEffects(b.effects());

            if (b.particles() != null) {
                auras.put(p.getUniqueId(), new Aura(b.particles(), tick + b.particles().durationTicks()));
            }

            if (b.contagion() != null) {
                infections.put(p.getUniqueId(), new Infection(b.id(), tick + b.contagion().durationTicks()));
            }

            Explosion ex = b.explosion();
            if (ex != null && ThreadLocalRandom.current().nextDouble() * 100.0 < ex.chance()) {
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!p.isOnline() || p.isDead()) return;
                    Location loc = p.getLocation();
                    // последний флаг = исключить источник из урона; self-damage: true -> не исключаем
                    loc.getWorld().createExplosion(p, loc, ex.power(), ex.fire(), ex.breakBlocks(), !ex.selfDamage());
                }, Math.max(0, ex.delayTicks()));
            }
        });
    }

    // ------------------------------------------------------------------ аура частиц

    private void tickAuras() {
        tick += AURA_STEP;

        Iterator<Map.Entry<UUID, Aura>> it = auras.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Aura> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Aura a = e.getValue();
            if (p == null || !p.isOnline() || p.isDead() || tick >= a.untilTick()) {
                it.remove();
                continue;
            }
            Particles pt = a.p();
            Location loc = p.getLocation().add(0, 1, 0);
            p.getWorld().spawnParticle(pt.type(), loc, pt.count(), pt.spread(), pt.spread(), pt.spread(), 0.05, pt.data());
        }

        infections.values().removeIf(inf -> tick >= inf.untilTick());
    }

    // ------------------------------------------------------------------ зараза

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;

        Infection inf = infections.get(attacker.getUniqueId());
        if (inf == null || tick >= inf.untilTick()) return;
        Bread b = breads.get(inf.breadId());
        if (b == null || b.contagion() == null) return;
        Contagion c = b.contagion();

        if (!(victim instanceof Player) && !c.mobs()) return;
        if (ThreadLocalRandom.current().nextDouble() * 100.0 >= c.chance()) return;

        victim.addPotionEffects(c.effects());
        if (c.transfer()) {
            for (PotionEffect pe : c.effects()) attacker.removePotionEffect(pe.getType());
        }

        victim.getWorld().spawnParticle(Particle.PORTAL, victim.getLocation().add(0, 1, 0), 25, 0.3, 0.5, 0.3, 0.3);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.4f, 1.8f);

        if (victim instanceof Player vp) {
            String msg = getConfig().getString("contagion-message", "");
            if (msg != null && !msg.isBlank()) {
                vp.sendActionBar(LEGACY.deserialize(msg.replace("%bread%", LEGACY.serialize(b.name()))));
            }
            if (c.spreads()) {
                infections.put(vp.getUniqueId(), new Infection(b.id(), tick + c.durationTicks()));
            }
        }
    }

    // ------------------------------------------------------------------ триггеры телепорта

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!getConfig().getBoolean("triggers.commands", true)) return;
        String msg = e.getMessage();
        if (msg.length() < 2) return;
        String label = msg.substring(1).trim().split("\\s+")[0].toLowerCase();
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1); // essentials:warp -> warp
        for (String c : getConfig().getStringList("commands")) {
            if (c.equalsIgnoreCase(label)) {
                long timeoutMs = getConfig().getLong("command-timeout-seconds", 15) * 1000L;
                pendingCommand.put(e.getPlayer().getUniqueId(), System.currentTimeMillis() + timeoutMs);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandTeleport(PlayerTeleportEvent e) {
        switch (e.getCause()) {
            case COMMAND, PLUGIN -> {
                Long until = pendingCommand.remove(e.getPlayer().getUniqueId());
                if (until == null || System.currentTimeMillis() > until) return;
                mutate(e.getPlayer());
            }
            case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> {
                if (getConfig().getBoolean("triggers.portals", true)) mutate(e.getPlayer());
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (getConfig().getBoolean("triggers.portals", true)) mutate(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastMutationTick.remove(id);
        pendingCommand.remove(id);
        auras.remove(id);
        infections.remove(id);
    }

    // ------------------------------------------------------------------ для редактора

    /** Сохранить config.yml и перечитать хлеба. */
    void applyChanges() {
        saveConfig();
        loadBreads();
    }

    /** Перечитать config.yml с диска (если правил руками). */
    void reloadFromDisk() {
        reloadConfig();
        loadBreads();
    }

    List<String> breadIds() {
        return new ArrayList<>(breads.keySet());
    }

    boolean hasBread(String id) {
        return breads.containsKey(id);
    }

    Component breadName(String id) {
        Bread b = breads.get(id);
        return b == null ? Component.text(id) : b.name();
    }

    /** Проверить строку эффекта "speed 1 120" без спама в лог. */
    boolean isValidEffect(String s) {
        String[] p = s.trim().split("\\s+");
        if (p.length < 3) return false;
        NamespacedKey key = NamespacedKey.fromString(p[0].toLowerCase());
        if (key == null || Registry.EFFECT.get(key) == null) return false;
        try {
            Integer.parseInt(p[1]);
            Double.parseDouble(p[2].replace(',', '.'));
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    PotionEffectType effectType(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase());
        return key == null ? null : Registry.EFFECT.get(key);
    }

    /** Частицу можно использовать в ауре (без данных, dust или с цветом). */
    boolean isUsableParticle(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase());
        Particle type = key == null ? null : Registry.PARTICLE_TYPE.get(key);
        if (type == null) return false;
        Class<?> d = type.getDataType();
        return d == Void.class || d == Particle.DustOptions.class || d == Color.class;
    }

    // ------------------------------------------------------------------ утилиты

    /** Без научной записи: 6.9E-7 -> 0.00000069 */
    static String fmt(double d) {
        return new java.math.BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
