package fritty.dialogue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FrittyDialogue 1.2.0
 * Діалог = один файл. Усі вузли (гілки) лежать у секції "dialogue".
 * Перший вузол - стартовий. next: <вузол> переходить до вузла, без next діалог закривається.
 */
public final class FrittyDialogue extends JavaPlugin implements Listener, TabExecutor {

    // ------------------------------------------------------------------ модель

    enum State { BUSY, TYPING, CHOOSING, CONTINUE }

    record Answer(String text, Vector offset, List<String> commands, String next) {}

    record Node(String id, String text, int delay, Vector offset, List<Answer> answers, String next,
                String location, String camera, Float scale) {}

    record Settings(boolean hidePlayers, boolean oneTime, boolean seeThrough, String location, String camera,
                    boolean smooth, String sound, float pitch, boolean perChar, int bgRgb, int bgAlpha,
                    int lineWidth, boolean slowness, boolean invisible, boolean saveStart, float textScale) {}

    record Dialogue(String name, Settings cfg, LinkedHashMap<String, Node> nodes) {}

    static final class Session {
        final Player player;
        final Dialogue dialogue;
        Node node;
        int selected;
        State state = State.BUSY;
        boolean closed;
        Location start;
        Location base;
        String camStr;
        float scale = 1f;
        TextDisplay text;
        final List<TextDisplay> answers = new ArrayList<>();
        BukkitTask typeTask;
        BukkitTask moveTask;
        BukkitTask barTask;

        Session(Player player, Dialogue dialogue) {
            this.player = player;
            this.dialogue = dialogue;
        }
    }

    // ------------------------------------------------------------------ стан

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Pattern TOKEN =
            Pattern.compile("&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR]|.", Pattern.DOTALL);
    private static final String NAME_REGEX = "[^/\\\\\\s.:*?\"<>|]+";

    private final Map<String, Dialogue> dialogues = new LinkedHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private File dialoguesDir;
    private File dataFile;
    private YamlConfiguration data;

    // ------------------------------------------------------------------ життєвий цикл

    @Override
    public void onEnable() {
        setupConfig();

        dialoguesDir = new File(getDataFolder(), "dialogues");
        if (!dialoguesDir.exists()) {
            dialoguesDir.mkdirs();
            writeExample();
        }
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        loadAll();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("fritty-dialogue");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
    }

    private void setupConfig() {
        FileConfiguration cfg = getConfig();
        cfg.addDefault("log-dialogue-registration", true);
        cfg.addDefault("messages.camera-location", "&7The camera position in the {name} has been changed.");
        cfg.addDefault("messages.example-updated", "&7Example furniture has been updated");
        cfg.addDefault("messages.dialogue-created", "&aDialogue &f{name}&a created from &f{from}&a.");
        cfg.addDefault("messages.reloaded", "&aDialogues reloaded: &f{count}");
        cfg.addDefault("actionbar.space-to-continue", "&ePress F to continue");
        cfg.addDefault("actionbar.space-to-skip", "&7Press F to skip");
        cfg.addDefault("actionbar.space-to-choose", "&eScroll to select, F to choose");
        cfg.addDefault("errors.player-not-found", "&cPlayer not found");
        cfg.addDefault("errors.dialogue-not-found", "&cDialogue not found");
        cfg.addDefault("errors.dialogue-exists", "&cDialogue with this name already exists");
        cfg.addDefault("errors.invalid-name", "&cInvalid name (no dots, spaces or slashes)");
        cfg.addDefault("errors.one-time", "&cYou have already seen this dialogue");
        cfg.addDefault("errors.only-player", "&cOnly a player can do this");
        cfg.addDefault("errors.usage",
                "&7/fd start <dialogue> [player] | stop [player] | create <new> [copy-from] | edit <dialogue> | reload | load-example | set-camera-location <dialogue>");
        cfg.options().copyDefaults(true);
        saveConfig();
    }

    @Override
    public void onDisable() {
        for (Session s : new ArrayList<>(sessions.values())) {
            stopSession(s, true);
        }
    }

    // ------------------------------------------------------------------ завантаження файлів

    private void loadAll() {
        dialogues.clear();
        File[] files = dialoguesDir.listFiles((dir, n) -> n.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) loadFile(f);
    }

    private Dialogue loadFile(File f) {
        return loadFile(f, true);
    }

    private Dialogue loadFile(File f, boolean log) {
        String name = f.getName().substring(0, f.getName().length() - 4);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection d = y.getConfigurationSection("dialogue");
        if (d == null) {
            getLogger().warning("[" + name + "] немає секції 'dialogue' - пропускаю");
            return null;
        }

        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        for (String id : d.getKeys(false)) {
            ConfigurationSection n = d.getConfigurationSection(id);
            if (n == null) continue;
            List<Answer> answers = new ArrayList<>();
            ConfigurationSection as = n.getConfigurationSection("answers");
            if (as != null) {
                for (String aid : as.getKeys(false)) {
                    ConfigurationSection a = as.getConfigurationSection(aid);
                    if (a == null) continue;
                    List<String> cmds = new ArrayList<>(a.getStringList("commands"));
                    if (a.isString("command")) cmds.add(a.getString("command"));
                    answers.add(new Answer(a.getString("text", ""), parseVector(a.get("offset")),
                            cmds, clean(a.getString("next"))));
                }
            }
            nodes.put(id, new Node(id, n.getString("text", ""), n.getInt("char-delay", 2),
                    parseVector(n.get("location-offset")), answers, clean(n.getString("next")),
                    clean(n.getString("location")), clean(n.getString("camera-location")),
                    n.isSet("text-scale") ? clampScale(n.getDouble("text-scale", 1.0)) : null));
        }

        // попередження про next на неіснуючий вузол
        for (Node n : nodes.values()) {
            if (n.next() != null && !nodes.containsKey(n.next())) {
                getLogger().warning("[" + name + "] вузол '" + n.id() + "': next '" + n.next() + "' не існує");
            }
            for (Answer a : n.answers()) {
                if (a.next() != null && !nodes.containsKey(a.next())) {
                    getLogger().warning("[" + name + "] вузол '" + n.id() + "', відповідь '" + a.text()
                            + "': next '" + a.next() + "' не існує");
                }
            }
        }
        Dialogue dialogue = new Dialogue(name, parseSettings(y.getConfigurationSection("config")), nodes);
        dialogues.put(name, dialogue);
        if (log && getConfig().getBoolean("log-dialogue-registration", true)) {
            getLogger().info("Діалог зареєстровано: " + name + " (вузлів: " + nodes.size() + ")");
        }
        return dialogue;
    }

    private Settings parseSettings(ConfigurationSection c) {
        if (c == null) c = new YamlConfiguration();
        String bg = c.getString("background-color", "#1e1f22").replace("#", "").trim();
        int rgb;
        try {
            rgb = Integer.parseInt(bg, 16);
        } catch (NumberFormatException e) {
            rgb = 0x1e1f22;
        }
        int alpha = Math.max(0, Math.min(255, c.getInt("background-alpha", 110)));
        return new Settings(
                c.getBoolean("hide-players", true),
                c.getBoolean("one-time", false),
                c.getBoolean("see-through-blocks", false),
                c.getString("location", "0 0 0 world"),
                c.getString("camera-location", "0 0 0 0 0 world"),
                c.getBoolean("smooth-teleport", true),
                c.getString("char-sound", "ui.button.click"),
                (float) c.getDouble("char-sound-pitch", 1.0),
                c.getBoolean("per-char", true),
                rgb, alpha,
                c.getInt("line-width", 200),
                c.getBoolean("use-slowness", true),
                c.getBoolean("make-invisible", true),
                c.getBoolean("save-start-location", true),
                clampScale(c.getDouble("text-scale", 1.0)));
    }

    private static float clampScale(double v) {
        return (float) Math.max(0.1, Math.min(10.0, v));
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static Vector parseVector(Object o) {
        double[] v = new double[3];
        if (o != null) {
            String[] t = o.toString().trim().split("\\s+");
            for (int i = 0; i < 3 && i < t.length; i++) {
                try {
                    v[i] = Double.parseDouble(t[i]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new Vector(v[0], v[1], v[2]);
    }

    /** "x y z world" або "x y z yaw pitch world" */
    private static Location parseLoc(String s) {
        String[] t = s.trim().split("\\s+");
        World w = Bukkit.getWorlds().get(0);
        int n = t.length;
        if (n > 0 && !isNumber(t[n - 1])) {
            World x = Bukkit.getWorld(t[n - 1]);
            if (x != null) w = x;
            n--;
        }
        double[] v = new double[5];
        for (int i = 0; i < 5 && i < n; i++) {
            try {
                v[i] = Double.parseDouble(t[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return new Location(w, v[0], v[1], v[2], (float) v[3], (float) v[4]);
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ відображення

    private static Component render(String s) {
        return LEGACY.deserialize(s)
                .decoration(TextDecoration.ITALIC, false)
                .colorIfAbsent(NamedTextColor.WHITE);
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static boolean isCode(String token) {
        return token.length() > 1 && token.charAt(0) == '&';
    }

    private TextDisplay spawnDisplay(Session s, Location loc, Component text) {
        Settings c = s.dialogue.cfg();
        Color bg = Color.fromARGB(c.bgAlpha(), (c.bgRgb() >> 16) & 255, (c.bgRgb() >> 8) & 255, c.bgRgb() & 255);
        TextDisplay td = loc.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setLineWidth(c.lineWidth());
            d.setBackgroundColor(bg);
            d.setSeeThrough(c.seeThrough());
            d.text(text);
        });
        s.player.showEntity(this, td);
        return td;
    }

    private void setScale(TextDisplay td, float f, int ticks) {
        td.setInterpolationDelay(0);
        td.setInterpolationDuration(ticks);
        td.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                new Vector3f(f, f, f), new AxisAngle4f()));
    }

    private void select(Session s, int idx) {
        for (int i = 0; i < s.answers.size(); i++) {
            setScale(s.answers.get(i), i == idx ? s.scale * 1.2f : s.scale, 3);
        }
        s.selected = idx;
    }

    private void clearAnswers(Session s) {
        for (TextDisplay td : s.answers) td.remove();
        s.answers.clear();
    }

    private void sound(Player p, Settings c) {
        if (c.sound() == null || c.sound().isBlank()) return;
        p.playSound(p.getLocation(), c.sound(), SoundCategory.MASTER, 1f, c.pitch());
    }

    private void sendBar(Session s) {
        String key = switch (s.state) {
            case TYPING -> "actionbar.space-to-skip";
            case CHOOSING -> "actionbar.space-to-choose";
            case CONTINUE -> "actionbar.space-to-continue";
            default -> null;
        };
        if (key == null) return;
        s.player.sendActionBar(render(getConfig().getString(key, "")));
    }

    private static void cancel(BukkitTask t) {
        if (t != null) t.cancel();
    }

    // ------------------------------------------------------------------ логіка діалогу

    public boolean startDialogue(Dialogue d, Player p) {
        Settings c = d.cfg();
        if (c.oneTime() && isCompleted(p.getUniqueId(), d.name())) {
            msg(p, "errors.one-time");
            return false;
        }
        if (d.nodes().isEmpty()) return false;

        Location start = c.saveStart() ? p.getLocation().clone() : null;
        Session old = sessions.get(p.getUniqueId());
        if (old != null) {
            // діалог запущено з команди відповіді: лишаємо точку повернення
            if (old.start != null) start = old.start;
            stopSession(old, false);
        }

        Session s = new Session(p, d);
        s.start = start;
        s.base = parseLoc(c.location());
        sessions.put(p.getUniqueId(), s);

        if (c.invisible()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 60 * 60, 0, false, false, false));
        }
        if (c.slowness()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 60 * 60, 10, false, false, false));
        }
        if (c.hidePlayers()) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p)) p.hidePlayer(this, o);
            }
        }

        s.text = spawnDisplay(s, s.base.clone(), Component.empty());
        s.barTask = Bukkit.getScheduler().runTaskTimer(this, () -> sendBar(s), 0L, 20L);

        // камеру і місце тексту для першого вузла виставляє showNode
        showNode(s, d.nodes().values().iterator().next());
        return true;
    }

    private void moveCamera(Session s, Location to, Runnable done) {
        Player p = s.player;
        Location from = p.getLocation();
        if (!s.dialogue.cfg().smooth() || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            p.teleport(to);
            done.run();
            return;
        }
        final int steps = 20;
        final int[] i = {0};
        s.moveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (s.closed) {
                cancel(s.moveTask);
                return;
            }
            i[0]++;
            double t = i[0] / (double) steps;
            t = t * t * (3 - 2 * t);
            float dyaw = ((to.getYaw() - from.getYaw() + 540f) % 360f) - 180f;
            Location l = new Location(to.getWorld(),
                    from.getX() + (to.getX() - from.getX()) * t,
                    from.getY() + (to.getY() - from.getY()) * t,
                    from.getZ() + (to.getZ() - from.getZ()) * t,
                    (float) (from.getYaw() + dyaw * t),
                    (float) (from.getPitch() + (to.getPitch() - from.getPitch()) * t));
            p.teleport(l);
            if (i[0] >= steps) {
                cancel(s.moveTask);
                s.moveTask = null;
                p.teleport(to);
                done.run();
            }
        }, 1L, 1L);
    }

    /** Показує вузол: виставляє місце тексту, камеру (якщо змінилась), розмір шрифту і запускає друк. */
    private void showNode(Session s, Node node) {
        cancel(s.typeTask);
        s.typeTask = null;
        clearAnswers(s);
        s.node = node;
        s.selected = 0;
        s.state = State.BUSY;

        Settings c = s.dialogue.cfg();
        s.scale = node.scale() != null ? node.scale() : c.textScale();
        s.base = parseLoc(node.location() != null ? node.location() : c.location());
        placeText(s, s.base.clone().add(node.offset()));
        setScale(s.text, s.scale, 0);

        String cam = node.camera() != null ? node.camera() : c.camera();
        if (!cam.equals(s.camStr)) {
            s.camStr = cam;
            moveCamera(s, parseLoc(cam), () -> {
                if (!s.closed && s.node == node) typeNode(s, node);
            });
        } else {
            typeNode(s, node);
        }
    }

    private void placeText(Session s, Location loc) {
        s.text.text(Component.empty());
        if (s.text.getWorld().equals(loc.getWorld())) {
            s.text.teleport(loc);
        } else {
            s.text.remove();
            s.text = spawnDisplay(s, loc, Component.empty());
        }
    }

    private void typeNode(Session s, Node node) {
        if (s.closed) return;
        s.state = State.TYPING;
        Settings c = s.dialogue.cfg();
        List<String> tokens = tokenize(node.text());
        if (!c.perChar() || tokens.isEmpty()) {
            finishTyping(s);
            return;
        }

        StringBuilder sb = new StringBuilder();
        final int[] pos = {0};
        s.typeTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (s.closed || s.state != State.TYPING) return;
            while (pos[0] < tokens.size()) {
                String t = tokens.get(pos[0]++);
                sb.append(t);
                if (!isCode(t)) {
                    if (!t.isBlank()) sound(s.player, c);
                    break;
                }
            }
            s.text.text(render(sb.toString()));
            if (pos[0] >= tokens.size()) finishTyping(s);
        }, 0L, Math.max(1, node.delay()));
    }

    private void finishTyping(Session s) {
        if (s.closed || s.state != State.TYPING) return;
        cancel(s.typeTask);
        s.typeTask = null;

        Node n = s.node;
        s.text.text(render(n.text()));

        if (n.answers().isEmpty()) {
            s.state = State.CONTINUE;
        } else {
            for (Answer a : n.answers()) {
                Location loc = s.base.clone().add(a.offset());
                s.answers.add(spawnDisplay(s, loc, render(a.text())));
            }
            s.state = State.CHOOSING;
            select(s, 0);
        }
        sendBar(s);
    }

    private void choose(Session s) {
        Answer a = s.node.answers().get(s.selected);
        s.state = State.BUSY;
        for (String cmd : a.commands()) dispatch(s.player, cmd);
        // команда могла запустити інший діалог або закрити цей
        if (s.closed || sessions.get(s.player.getUniqueId()) != s) return;
        goTo(s, a.next());
    }

    /** next: вузол - перехід; null (немає next) - діалог закривається. */
    private void goTo(Session s, String id) {
        if (id == null) {
            finish(s);
            return;
        }
        Node n = s.dialogue.nodes().get(id);
        if (n == null) {
            getLogger().warning("[" + s.dialogue.name() + "] next '" + id + "' не існує - закриваю діалог");
            finish(s);
            return;
        }
        showNode(s, n);
    }

    private void finish(Session s) {
        if (s.dialogue.cfg().oneTime()) markCompleted(s.player.getUniqueId(), s.dialogue.name());
        stopSession(s, true);
    }

    private void stopSession(Session s, boolean restore) {
        if (s.closed) return;
        s.closed = true;
        cancel(s.typeTask);
        cancel(s.moveTask);
        cancel(s.barTask);
        clearAnswers(s);
        if (s.text != null) s.text.remove();
        sessions.remove(s.player.getUniqueId(), s);

        Player p = s.player;
        Settings c = s.dialogue.cfg();
        if (c.invisible()) p.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (c.slowness()) p.removePotionEffect(PotionEffectType.SLOWNESS);
        if (c.hidePlayers()) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p)) p.showPlayer(this, o);
            }
        }
        p.sendActionBar(Component.empty());
        if (restore && s.start != null) p.teleport(s.start);
    }

    private void dispatch(Player p, String cmd) {
        try {
            cmd = cmd.replace("%player%", p.getName()).replace("{player}", p.getName());
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                try {
                    Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                    Method m = papi.getMethod("setPlaceholders", Player.class, String.class);
                    cmd = (String) m.invoke(null, p, cmd);
                } catch (Throwable ignored) {
                }
            }
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Exception e) {
            getLogger().warning("Помилка виконання команди '" + cmd + "': " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ одноразові діалоги

    private boolean isCompleted(UUID u, String name) {
        return data.getStringList("completed." + u).contains(name);
    }

    private void markCompleted(UUID u, String name) {
        List<String> l = new ArrayList<>(data.getStringList("completed." + u));
        if (!l.contains(name)) {
            l.add(name);
            data.set("completed." + u, l);
            try {
                data.save(dataFile);
            } catch (IOException e) {
                getLogger().warning("Не вдалося зберегти data.yml: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ події

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        switch (s.state) {
            case TYPING -> finishTyping(s);
            case CHOOSING -> choose(s);
            case CONTINUE -> goTo(s, s.node.next());
            default -> {
            }
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (s.state != State.CHOOSING || s.answers.isEmpty()) return;
        int diff = e.getNewSlot() - e.getPreviousSlot();
        if (diff == -8) diff = 1;
        if (diff == 8) diff = -1;
        if (diff == 0) return;
        int n = s.answers.size();
        select(s, ((s.selected + (diff > 0 ? 1 : -1)) % n + n) % n);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) stopSession(s, true);
    }

    // ------------------------------------------------------------------ команди

    private void msg(CommandSender to, String key, String... pairs) {
        String s = getConfig().getString(key, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) s = s.replace(pairs[i], pairs[i + 1]);
        to.sendMessage(LEGACY.deserialize(s));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length == 0) {
            msg(sender, "errors.usage");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start" -> cmdStart(sender, a);
            case "stop" -> cmdStop(sender, a);
            case "create" -> cmdCreate(sender, a);
            case "set-camera-location" -> cmdCamera(sender, a);
            case "edit" -> cmdEdit(sender, a);
            case "reload" -> {
                reloadConfig();
                setupConfig();
                data = YamlConfiguration.loadConfiguration(dataFile);
                loadAll();
                msg(sender, "messages.reloaded", "{count}", String.valueOf(dialogues.size()));
            }
            case "load-example" -> {
                writeExample();
                loadAll();
                msg(sender, "messages.example-updated");
            }
            default -> msg(sender, "errors.usage");
        }
        return true;
    }

    private void cmdStart(CommandSender sender, String[] a) {
        if (a.length < 2) {
            msg(sender, "errors.usage");
            return;
        }
        Dialogue d = dialogues.get(a[1]);
        if (d == null) {
            msg(sender, "errors.dialogue-not-found");
            return;
        }
        Player target = a.length >= 3 ? Bukkit.getPlayer(a[2]) : (sender instanceof Player p ? p : null);
        if (target == null) {
            msg(sender, "errors.player-not-found");
            return;
        }
        startDialogue(d, target);
    }

    private void cmdStop(CommandSender sender, String[] a) {
        Player target = a.length >= 2 ? Bukkit.getPlayer(a[1]) : (sender instanceof Player p ? p : null);
        if (target == null) {
            msg(sender, "errors.player-not-found");
            return;
        }
        Session s = sessions.get(target.getUniqueId());
        if (s != null) stopSession(s, true);
    }

    /** /fd create <новий> [старий] - копія старого діалогу (повністю, з config) або шаблон. */
    private void cmdCreate(CommandSender sender, String[] a) {
        if (a.length < 2) {
            msg(sender, "errors.usage");
            return;
        }
        String name = a[1];
        String from = a.length >= 3 ? a[2] : null;
        if (!name.matches(NAME_REGEX) || (from != null && !from.matches(NAME_REGEX))) {
            msg(sender, "errors.invalid-name");
            return;
        }
        File target = new File(dialoguesDir, name + ".yml");
        if (target.exists()) {
            msg(sender, "errors.dialogue-exists");
            return;
        }
        try {
            if (from != null) {
                File src = new File(dialoguesDir, from + ".yml");
                if (!src.exists()) {
                    msg(sender, "errors.dialogue-not-found");
                    return;
                }
                Files.copy(src.toPath(), target.toPath());
            } else {
                Files.writeString(target.toPath(), TEMPLATE, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            sender.sendMessage("Error: " + e.getMessage());
            return;
        }
        loadFile(target);
        msg(sender, "messages.dialogue-created", "{name}", name, "{from}", from == null ? "template" : from);
    }

    private void cmdCamera(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            msg(sender, "errors.only-player");
            return;
        }
        if (a.length < 2) {
            msg(sender, "errors.usage");
            return;
        }
        File f = new File(dialoguesDir, a[1] + ".yml");
        if (!a[1].matches(NAME_REGEX) || !f.exists()) {
            msg(sender, "errors.dialogue-not-found");
            return;
        }
        Location l = p.getLocation();
        String s = String.format(Locale.US, "%.3f %.3f %.3f %.1f %.1f %s",
                l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), l.getWorld().getName());
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            y.set("config.camera-location", s);
            y.save(f);
        } catch (IOException e) {
            sender.sendMessage("Error: " + e.getMessage());
            return;
        }
        loadFile(f);
        msg(sender, "messages.camera-location", "{name}", a[1]);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("start", "stop", "create", "edit", "reload", "load-example", "set-camera-location"));
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "start", "edit", "set-camera-location" -> out.addAll(dialogues.keySet());
                case "stop" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                default -> {
                }
            }
        } else if (a.length == 3) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "start" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "create" -> out.addAll(dialogues.keySet());
                default -> {
                }
            }
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ GUI-редактор (/fd edit <діалог>)

    enum Screen { NODES, NODE, ANSWER, PICK, SETTINGS }

    static final class Gui implements InventoryHolder {
        final Screen screen;
        final String dialogue;
        final String node;
        final String answer;
        Inventory inv;

        Gui(Screen screen, String dialogue, String node, String answer) {
            this.screen = screen;
            this.dialogue = dialogue;
            this.node = node;
            this.answer = answer;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private record Prompt(Consumer<String> handler, Runnable back) {}

    /** type: b = так/ні, i = ціле, d = дробове, s = текст, L = позиція, C = позиція камери */
    private record Setting(String key, String title, Material icon, char type,
                           double step, double min, double max, Object def) {}

    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    private static final List<Setting> SETTINGS = List.of(
            new Setting("hide-players", "Ховати інших гравців", Material.ENDER_PEARL, 'b', 0, 0, 0, true),
            new Setting("one-time", "Показати лише один раз", Material.CLOCK, 'b', 0, 0, 0, false),
            new Setting("see-through-blocks", "Текст видно крізь блоки", Material.GLASS, 'b', 0, 0, 0, false),
            new Setting("smooth-teleport", "Плавний рух камери", Material.FEATHER, 'b', 0, 0, 0, true),
            new Setting("per-char", "Друк по літерах", Material.WRITABLE_BOOK, 'b', 0, 0, 0, true),
            new Setting("use-slowness", "Сповільнення гравця", Material.COBWEB, 'b', 0, 0, 0, true),
            new Setting("make-invisible", "Невидимість гравця", Material.POTION, 'b', 0, 0, 0, true),
            new Setting("save-start-location", "Повернення на старе місце", Material.COMPASS, 'b', 0, 0, 0, true),
            new Setting("text-scale", "Розмір шрифту", Material.SPYGLASS, 'd', 0.1, 0.1, 10, 1.0),
            new Setting("line-width", "Ширина рядка", Material.PAPER, 'i', 10, 20, 1000, 200),
            new Setting("background-alpha", "Прозорість фону (0-255)", Material.BLACK_STAINED_GLASS, 'i', 10, 0, 255, 110),
            new Setting("char-sound-pitch", "Тон звуку літер", Material.NOTE_BLOCK, 'd', 0.1, 0.5, 2, 1.0),
            new Setting("char-sound", "Звук літер", Material.JUKEBOX, 's', 0, 0, 0, "ui.button.click"),
            new Setting("background-color", "Колір фону (#rrggbb)", Material.INK_SAC, 's', 0, 0, 0, "#1e1f22"),
            new Setting("location", "Місце тексту (location)", Material.FILLED_MAP, 'L', 0, 0, 0, "-"),
            new Setting("camera-location", "Камера (camera-location)", Material.ENDER_EYE, 'C', 0, 0, 0, "-"));

    private static int settingSlot(int i) {
        return 10 + i + (i / 7) * 2;
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ---- допоміжні

    private static Component guiText(String s) {
        return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(guiText(name));
        List<Component> l = new ArrayList<>();
        for (String line : lore) l.add(guiText("&7" + line));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private Inventory createInv(Gui g, int size, String title) {
        Inventory inv = Bukkit.createInventory(g, size, Component.text(title));
        g.inv = inv;
        return inv;
    }

    private static String preview(String t) {
        String x = t.replace("\n", " ");
        if (x.length() > 70) x = x.substring(0, 70) + "...";
        return x;
    }

    private YamlConfiguration loadYaml(String dlg) {
        return YamlConfiguration.loadConfiguration(new File(dialoguesDir, dlg + ".yml"));
    }

    private boolean edit(String dlg, Consumer<YamlConfiguration> change) {
        File f = new File(dialoguesDir, dlg + ".yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        change.accept(y);
        try {
            y.save(f);
        } catch (IOException e) {
            getLogger().warning("Не вдалося зберегти " + f.getName() + ": " + e.getMessage());
            return false;
        }
        loadFile(f, false);
        return true;
              }
              private static ConfigurationSection dsec(YamlConfiguration y) {
        ConfigurationSection d = y.getConfigurationSection("dialogue");
        return d != null ? d : y.createSection("dialogue");
    }

    private static ConfigurationSection cfgSec(YamlConfiguration y) {
        ConfigurationSection c = y.getConfigurationSection("config");
        return c != null ? c : y.createSection("config");
    }

    private static List<String> nodeIds(YamlConfiguration y) {
        List<String> out = new ArrayList<>();
        ConfigurationSection d = y.getConfigurationSection("dialogue");
        if (d != null) {
            for (String k : d.getKeys(false)) {
                if (d.isConfigurationSection(k)) out.add(k);
            }
        }
        return out;
    }

    private static List<String> answerKeys(ConfigurationSection n) {
        List<String> out = new ArrayList<>();
        ConfigurationSection as = n.getConfigurationSection("answers");
        if (as != null) {
            for (String k : as.getKeys(false)) {
                if (as.isConfigurationSection(k)) out.add(k);
            }
        }
        return out;
    }

    private void editNode(String dlg, String id, Consumer<ConfigurationSection> change) {
        edit(dlg, y -> {
            ConfigurationSection n = dsec(y).getConfigurationSection(id);
            if (n != null) change.accept(n);
        });
    }

    private void editAnswer(String dlg, String id, String key, Consumer<ConfigurationSection> change) {
        editNode(dlg, id, n -> {
            ConfigurationSection as = n.getConfigurationSection("answers");
            ConfigurationSection a = as == null ? null : as.getConfigurationSection(key);
            if (a != null) change.accept(a);
        });
    }

    private static String locStr(Location l, boolean withDir) {
        String w = l.getWorld() == null ? "world" : l.getWorld().getName();
        if (withDir) {
            return String.format(Locale.US, "%.3f %.3f %.3f %.1f %.1f %s",
                    l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), w);
        }
        return String.format(Locale.US, "%.2f %.2f %.2f %s", l.getX(), l.getY(), l.getZ(), w);
    }

    private static boolean validVec(String t) {
        String[] parts = t.trim().split("\\s+");
        if (parts.length < 2 || parts.length > 3) return false;
        for (String x : parts) {
            if (!isNumber(x)) return false;
        }
        return true;
    }

    private void tell(Player p, String text) {
        p.sendMessage(LEGACY.deserialize(text));
    }

    /** Закриває меню й чекає відповідь у чаті. "cancel" - скасувати. */
    private void ask(Player p, String hint, Consumer<String> handler, Runnable back) {
        p.closeInventory();
        prompts.put(p.getUniqueId(), new Prompt(handler, back));
        tell(p, "&e" + hint + " &7(напиши в чат, або &fcancel&7 щоб скасувати)");
    }

    // ---- екрани

    private void openNodes(Player p, String dlg) {
        YamlConfiguration y = loadYaml(dlg);
        ConfigurationSection d = y.getConfigurationSection("dialogue");
        Gui g = new Gui(Screen.NODES, dlg, null, null);
        Inventory inv = createInv(g, 54, "Діалог: " + dlg);
        List<String> ids = nodeIds(y);
        for (int i = 0; i < ids.size() && i < 45; i++) {
            ConfigurationSection n = d.getConfigurationSection(ids.get(i));
            int cnt = answerKeys(n).size();
            inv.setItem(i, item(i == 0 ? Material.BOOK : Material.PAPER,
                    "&e" + ids.get(i) + (i == 0 ? " &a(старт)" : ""),
                    preview(n.getString("text", "")),
                    "",
                    "Відповідей: &f" + cnt,
                    "next: &f" + n.getString("next", "-"),
                    "&aКлік - редагувати"));
        }
        inv.setItem(45, item(Material.COMPARATOR, "&6Налаштування діалогу", "&aКлік - відкрити"));
        inv.setItem(49, item(Material.EMERALD, "&aДодати вузол", "&aКлік - ввести назву в чат"));
        inv.setItem(53, item(Material.NETHER_STAR, "&bЗапустити діалог", "&aПеревірити, як виглядає"));
        p.openInventory(inv);
    }

    private void openNode(Player p, String dlg, String id) {
        YamlConfiguration y = loadYaml(dlg);
        ConfigurationSection n = dsec(y).getConfigurationSection(id);
        if (n == null) {
            openNodes(p, dlg);
            return;
        }
        Gui g = new Gui(Screen.NODE, dlg, id, null);
        Inventory inv = createInv(g, 54, "Вузол: " + id);
        inv.setItem(10, item(Material.BOOK, "&eТекст", preview(n.getString("text", "")), "",
                "&aКлік - змінити (пишеш у чат)"));
        inv.setItem(11, item(Material.CLOCK, "&eШвидкість друку: &f" + n.getInt("char-delay", 2),
                "Менше число = швидше", "&aЛКМ +1 / ПКМ -1"));
        inv.setItem(12, item(Material.SPYGLASS, "&eРозмір шрифту: &f"
                        + (n.isSet("text-scale") ? n.getDouble("text-scale") : "як у діалозі"),
                "&aЛКМ +0.1 / ПКМ -0.1", "&aShift+клік - скинути"));
        inv.setItem(13, item(Material.COMPASS, "&eЗсув тексту: &f" + n.getString("location-offset", "0 0 0"),
                "&aКлік - ввести \"x y z\""));
        inv.setItem(14, item(Material.FILLED_MAP, "&eМісце тексту (location)",
                n.getString("location", "як у діалозі"),
                "&aЛКМ - поставити на мою позицію", "&aShift+клік - скинути"));
        inv.setItem(15, item(Material.ENDER_EYE, "&eКамера (camera-location)",
                n.getString("camera-location", "як у діалозі"),
                "&aЛКМ - поставити на мою позицію і погляд", "&aShift+клік - скинути"));
        inv.setItem(16, item(Material.ARROW, "&eNext вузла: &f" + n.getString("next", "-"),
                "Працює, коли у вузла немає відповідей", "&aКлік - вибрати"));
        ConfigurationSection as = n.getConfigurationSection("answers");
        List<String> keys = answerKeys(n);
        for (int i = 0; i < keys.size() && i < 9; i++) {
            ConfigurationSection a = as.getConfigurationSection(keys.get(i));
            inv.setItem(27 + i, item(Material.OAK_SIGN, a.getString("text", "?"),
                    "next: &f" + a.getString("next", "-"),
                    "offset: &f" + a.getString("offset", "0 0 0"),
                    "команд: &f" + a.getStringList("commands").size(),
                    "",
                    "&aЛКМ - редагувати", "&cShift+ПКМ - видалити"));
        }
        inv.setItem(45, item(Material.ARROW, "&7Назад"));
        inv.setItem(49, item(Material.EMERALD, "&aДодати відповідь", "Максимум 9"));
        inv.setItem(53, item(Material.BARRIER, "&cВидалити вузол", "&cShift+клік"));
        p.openInventory(inv);
    }

    private void openAnswer(Player p, String dlg, String id, String key) {
        ConfigurationSection n = dsec(loadYaml(dlg)).getConfigurationSection(id);
        ConfigurationSection as = n == null ? null : n.getConfigurationSection("answers");
        ConfigurationSection a = as == null ? null : as.getConfigurationSection(key);
        if (a == null) {
            openNode(p, dlg, id);
            return;
        }
        Gui g = new Gui(Screen.ANSWER, dlg, id, key);
        Inventory inv = createInv(g, 27, "Відповідь: " + id + " / " + key);
        inv.setItem(10, item(Material.OAK_SIGN, "&eТекст відповіді", a.getString("text", ""), "",
                "&aКлік - змінити (пишеш у чат)"));
        inv.setItem(12, item(Material.COMPASS, "&eПозиція (offset): &f" + a.getString("offset", "0 0 0"),
                "&aКлік - ввести \"x y z\""));
        inv.setItem(14, item(Material.ARROW, "&eNext: &f" + a.getString("next", "-"),
                "Куди веде ця відповідь", "&aКлік - вибрати вузол"));
        List<String> lore = new ArrayList<>();
        List<String> cmds = a.getStringList("commands");
        for (int i = 0; i < cmds.size() && i < 5; i++) lore.add("&f" + cmds.get(i));
        lore.add("");
        lore.add("&aЛКМ - додати команду");
        lore.add("&aПКМ - видалити останню");
        lore.add("&cShift+клік - очистити всі");
        inv.setItem(16, item(Material.COMMAND_BLOCK, "&eКоманди: &f" + cmds.size(), lore.toArray(new String[0])));
        inv.setItem(22, item(Material.ARROW, "&7Назад"));
        p.openInventory(inv);
    }

    private void openPick(Player p, String dlg, String id, String answerKey) {
        Gui g = new Gui(Screen.PICK, dlg, id, answerKey);
        Inventory inv = createInv(g, 54, "Куди веде next?");
        List<String> ids = nodeIds(loadYaml(dlg));
        for (int i = 0; i < ids.size() && i < 45; i++) {
            inv.setItem(i, item(Material.PAPER, "&e" + ids.get(i), "&aКлік - вибрати"));
        }
        inv.setItem(45, item(Material.ARROW, "&7Назад (без змін)"));
        inv.setItem(49, item(Material.BARRIER, "&cБез next", "Діалог закриється"));
        p.openInventory(inv);
    }

    private void openSettings(Player p, String dlg) {
        ConfigurationSection c = loadYaml(dlg).getConfigurationSection("config");
        Gui g = new Gui(Screen.SETTINGS, dlg, null, null);
        Inventory inv = createInv(g, 45, "Налаштування: " + dlg);
        for (int i = 0; i < SETTINGS.size(); i++) {
            Setting st = SETTINGS.get(i);
            Object cur = c == null ? st.def() : c.get(st.key(), st.def());
            ItemStack it;
            switch (st.type()) {
                case 'b' -> {
                    boolean v = Boolean.parseBoolean(String.valueOf(cur));
                    it = item(v ? Material.LIME_DYE : Material.GRAY_DYE,
                            "&f" + st.title() + ": " + (v ? "&aувімкнено" : "&cвимкнено"),
                            "&aКлік - перемкнути");
                }
                case 'i', 'd' -> it = item(st.icon(), "&f" + st.title() + ": &e" + cur,
                        "&aЛКМ: +" + fmt(st.step()), "&aПКМ: -" + fmt(st.step()), "Shift - у 5 разів більше");
                case 's' -> it = item(st.icon(), "&f" + st.title() + ": &e" + cur,
                        "&aКлік - ввести значення в чат");
                default -> it = item(st.icon(), "&f" + st.title(), String.valueOf(cur),
                        "&aКлік - поставити на мою позицію");
            }
            inv.setItem(settingSlot(i), it);
        }
        inv.setItem(40, item(Material.ARROW, "&7Назад"));
        p.openInventory(inv);
    }

    // ---- команда

    private void cmdEdit(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            msg(sender, "errors.only-player");
            return;
        }
        if (a.length < 2 || !dialogues.containsKey(a[1])) {
            msg(sender, "errors.dialogue-not-found");
            return;
        }
        openNodes(p, a[1]);
    }

    // ---- події меню і чату

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Prompt pr = prompts.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!text.equalsIgnoreCase("cancel") && !text.equalsIgnoreCase("скасувати")) {
                pr.handler().accept(text);
            }
            pr.back().run();
        });
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui g)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        ClickType click = e.getClick();
        switch (g.screen) {
            case NODES -> clickNodes(p, g, slot);
            case NODE -> clickNode(p, g, slot, click);
            case ANSWER -> clickAnswer(p, g, slot, click);
            case PICK -> clickPick(p, g, slot);
            case SETTINGS -> clickSettings(p, g, slot, click);
        }
    }

    private void clickNodes(Player p, Gui g, int slot) {
        String dlg = g.dialogue;
        List<String> ids = nodeIds(loadYaml(dlg));
        if (slot >= 0 && slot < 45 && slot < ids.size()) {
            openNode(p, dlg, ids.get(slot));
        } else if (slot == 45) {
            openSettings(p, dlg);
        } else if (slot == 49) {
            ask(p, "Введи назву нового вузла (латиниця, цифри, _ або -)", t -> {
                if (!t.matches("[A-Za-z0-9_\\-]+")) {
                    tell(p, "&cНевірна назва вузла.");
                    return;
                }
                edit(dlg, y -> {
                    ConfigurationSection d = dsec(y);
                    if (d.isConfigurationSection(t)) return;
                    ConfigurationSection n = d.createSection(t);
                    n.set("text", "&fНовий вузол");
                    n.set("char-delay", 2);
                    n.set("location-offset", "0 0 0");
                });
            }, () -> openNodes(p, dlg));
        } else if (slot == 53) {
            Dialogue d = dialogues.get(dlg);
            p.closeInventory();
            if (d != null) startDialogue(d, p);
        }
    }

    private void clickNode(Player p, Gui g, int slot, ClickType click) {
        String dlg = g.dialogue;
        String id = g.node;
        boolean shift = click.isShiftClick();
        boolean right = click.isRightClick();
        YamlConfiguration y = loadYaml(dlg);
        ConfigurationSection n = dsec(y).getConfigurationSection(id);
        if (n == null) {
            openNodes(p, dlg);
            return;
        }
        Runnable back = () -> openNode(p, dlg, id);
        List<String> keys = answerKeys(n);

        switch (slot) {
            case 10 -> ask(p, "Введи новий текст вузла (можна &-кольори)",
                    t -> editNode(dlg, id, s -> s.set("text", t)), back);
            case 11 -> {
                int nv = Math.max(1, n.getInt("char-delay", 2) + (right ? -1 : 1));
                editNode(dlg, id, s -> s.set("char-delay", nv));
                back.run();
            }
            case 12 -> {
                double base = n.isSet("text-scale") ? n.getDouble("text-scale") : y.getDouble("config.text-scale", 1.0);
                double nv = Math.max(0.1, Math.round((base + (right ? -0.1 : 0.1)) * 10) / 10.0);
                editNode(dlg, id, s -> s.set("text-scale", shift ? null : nv));
                back.run();
            }
            case 13 -> ask(p, "Введи зсув тексту у форматі: x y z (наприклад 0 0.5 0)", t -> {
                if (validVec(t)) editNode(dlg, id, s -> s.set("location-offset", t.trim()));
                else tell(p, "&cНевірний формат. Приклад: 0 0.5 0");
            }, back);
            case 14 -> {
                editNode(dlg, id, s -> s.set("location", shift ? null : locStr(p.getEyeLocation(), false)));
                back.run();
            }
            case 15 -> {
                editNode(dlg, id, s -> s.set("camera-location", shift ? null : locStr(p.getEyeLocation(), true)));
                back.run();
            }
            case 16 -> openPick(p, dlg, id, null);
            case 45 -> openNodes(p, dlg);
            case 49 -> {
                if (keys.size() >= 9) {
                    tell(p, "&cМаксимум 9 відповідей у вузлі.");
                    return;
                }
                editNode(dlg, id, s -> {
                    ConfigurationSection as = s.getConfigurationSection("answers");
                    if (as == null) as = s.createSection("answers");
                    int max = 0;
                    for (String k : as.getKeys(false)) {
                        try {
                            max = Math.max(max, Integer.parseInt(k));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    int count = as.getKeys(false).size();
                    ConfigurationSection a = as.createSection(String.valueOf(max + 1));
                    a.set("text", "&a[Нова відповідь]");
                    a.set("offset", String.format(Locale.US, "0 %.1f 0", -0.5 - 0.3 * count));
                });
                back.run();
            }
            case 53 -> {
                if (!shift) {
                    tell(p, "&cЩоб видалити вузол, натисни Shift+клік.");
                    return;
                }
                edit(dlg, y2 -> {
                    ConfigurationSection d = dsec(y2);
                    d.set(id, null);
                    for (String k : d.getKeys(false)) {
                        ConfigurationSection other = d.getConfigurationSection(k);
                        if (other == null) continue;
                        if (id.equals(other.getString("next"))) other.set("next", null);
                        ConfigurationSection as = other.getConfigurationSection("answers");
                        if (as == null) continue;
                        for (String ak : as.getKeys(false)) {
                            ConfigurationSection a = as.getConfigurationSection(ak);
                            if (a != null && id.equals(a.getString("next"))) a.set("next", null);
                        }
                    }
                });
                openNodes(p, dlg);
            }
            default -> {
                int idx = slot - 27;
                if (idx < 0 || idx >= keys.size() || idx >= 9) return;
                String key = keys.get(idx);
                if (shift && right) {
                    editNode(dlg, id, s -> {
                        ConfigurationSection as = s.getConfigurationSection("answers");
                        if (as != null) as.set(key, null);
                    });
                    back.run();
                } else {
                    openAnswer(p, dlg, id, key);
                }
            }
        }
    }

    private void clickAnswer(Player p, Gui g, int slot, ClickType click) {
        String dlg = g.dialogue;
        String id = g.node;
        String key = g.answer;
        Runnable back = () -> openAnswer(p, dlg, id, key);
        switch (slot) {
            case 10 -> ask(p, "Введи текст відповіді (можна &-кольори)",
                    t -> editAnswer(dlg, id, key, a -> a.set("text", t)), back);
            case 12 -> ask(p, "Введи позицію відповіді: x y z (наприклад 0 -0.5 0)", t -> {
                if (validVec(t)) editAnswer(dlg, id, key, a -> a.set("offset", t.trim()));
                else tell(p, "&cНевірний формат. Приклад: 0 -0.5 0");
            }, back);
            case 14 -> openPick(p, dlg, id, key);
            case 16 -> {
                if (click.isShiftClick()) {
                    editAnswer(dlg, id, key, a -> a.set("commands", null));
                    back.run();
                } else if (click.isRightClick()) {
                    editAnswer(dlg, id, key, a -> {
                        List<String> l = new ArrayList<>(a.getStringList("commands"));
                        if (!l.isEmpty()) l.remove(l.size() - 1);
                        a.set("commands", l.isEmpty() ? null : l);
                    });
                    back.run();
                } else {
                    ask(p, "Введи команду без / (можна %player%)", t -> editAnswer(dlg, id, key, a -> {
                        List<String> l = new ArrayList<>(a.getStringList("commands"));
                        l.add(t.startsWith("/") ? t.substring(1) : t);
                        a.set("commands", l);
                    }), back);
                }
            }
            case 22 -> openNode(p, dlg, id);
            default -> {
            }
        }
    }

    private void clickPick(Player p, Gui g, int slot) {
        String dlg = g.dialogue;
        String id = g.node;
        String key = g.answer;
        if (slot != 45) {
            List<String> ids = nodeIds(loadYaml(dlg));
            final String target = slot == 49 ? null
                    : (slot >= 0 && slot < 45 && slot < ids.size() ? ids.get(slot) : null);
            if (slot != 49 && target == null) return;
            if (key == null) editNode(dlg, id, s -> s.set("next", target));
            else editAnswer(dlg, id, key, a -> a.set("next", target));
        }
        if (key == null) openNode(p, dlg, id);
        else openAnswer(p, dlg, id, key);
    }

    private void clickSettings(Player p, Gui g, int slot, ClickType click) {
        String dlg = g.dialogue;
        if (slot == 40) {
            openNodes(p, dlg);
            return;
        }
        int idx = -1;
        for (int i = 0; i < SETTINGS.size(); i++) {
            if (settingSlot(i) == slot) idx = i;
        }
        if (idx < 0) return;
        Setting st = SETTINGS.get(idx);
        Runnable back = () -> openSettings(p, dlg);
        switch (st.type()) {
            case 'b' -> {
                edit(dlg, y -> {
                    ConfigurationSection c = cfgSec(y);
                    c.set(st.key(), !c.getBoolean(st.key(), (Boolean) st.def()));
                });
                back.run();
            }
            case 'i', 'd' -> {
                double step = st.step() * (click.isShiftClick() ? 5 : 1) * (click.isRightClick() ? -1 : 1);
                edit(dlg, y -> {
                    ConfigurationSection c = cfgSec(y);
                    double cur = c.getDouble(st.key(), ((Number) st.def()).doubleValue());
                    double nv = Math.max(st.min(), Math.min(st.max(), cur + step));
                    if (st.type() == 'i') c.set(st.key(), (int) Math.round(nv));
                    else c.set(st.key(), Math.round(nv * 100) / 100.0);
                });
                back.run();
            }
            case 's' -> ask(p, "Введи нове значення для " + st.key(),
                    t -> edit(dlg, y -> cfgSec(y).set(st.key(), t)), back);
            default -> {
                boolean dir = st.type() == 'C';
                edit(dlg, y -> cfgSec(y).set(st.key(), locStr(p.getEyeLocation(), dir)));
                back.run();
            }
        }
    }

    // ------------------------------------------------------------------ шаблон / приклад

    private void writeExample() {
        try {
            Files.writeString(new File(dialoguesDir, "example.yml").toPath(), TEMPLATE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("Не вдалося створити example.yml: " + e.getMessage());
        }
    }

    private static final String TEMPLATE = """
            config:
              hide-players: true
              one-time: false
              see-through-blocks: false
              location: 0 64 0 world
              camera-location: 0 64 4 180 0 world
              smooth-teleport: true
              char-sound: ui.button.click
              char-sound-pitch: 1
              per-char: true
              background-color: '#1e1f22'
              background-alpha: 110
              line-width: 200
              use-slowness: true
              make-invisible: true
              save-start-location: true
              text-scale: 1

            # Перший вузол у списку - стартовий.
            # next: <назва вузла> - перейти до вузла (будь-якого, і назад теж).
            # Якщо next немає - діалог закривається.
            # commands: - команди від консолі (можна %player%).
            # У config: text-scale - розмір шрифту для всього діалогу (1 = звичайний, 1.5 більший, 0.7 менший).
            # У вузлі можна перевизначити: text-scale, location, camera-location
            # (камера тоді переміститься; вузли без цього беруть значення з config).
            dialogue:

              start:
                text: '&fПривіт, мандрівнику! Чим допомогти?'
                char-delay: 2
                location-offset: 0 0 0
                answers:
                  '1':
                    text: '&6[Розкажи більше]'
                    offset: 0 -0.5 0
                    next: more
                  '2':
                    text: '&4[Бувай]'
                    offset: 0 -0.8 0
                    next: bye

              more:
                text: '&fТут багато цікавого. Заходь ще!'
                char-delay: 2
                location-offset: 0 0 0
                answers:
                  '1':
                    text: '&7[Повернутися назад]'
                    offset: 0 -0.5 0
                    next: start

              bye:
                text: '&fЩасливої дороги!'
                char-delay: 2
                location-offset: 0 0 0
            """;
}
