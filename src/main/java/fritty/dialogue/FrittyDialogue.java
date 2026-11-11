package fritty.dialogue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import io.papermc.paper.event.player.AsyncChatEvent;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FrittyDialogue 3.1.0
 *
 * Один діалог = один YAML-файл.
 * Усі вузли та розгалуження зберігаються в секції dialogue.
 *
 * Перший вузол є стартовим.
 * next визначає наступний вузол.
 * Якщо next відсутній, відповідна гілка завершується.
 */
public final class FrittyDialogue extends JavaPlugin implements Listener, TabExecutor {

    enum State {
        BUSY,
        TYPING,
        CHOOSING,
        CONTINUE
    }

    enum Screen {
        NODES,
        NODE,
        ANSWER,
        PICK,
        SETTINGS
    }

    record Answer(
            String text,
            Vector offset,
            List<String> commands,
            String next
    ) {}

    record Node(
            String id,
            String text,
            int delay,
            Vector offset,
            List<Answer> answers,
            String next,
            String location,
            String camera,
            Float scale
    ) {}

    record Settings(
            boolean hidePlayers,
            boolean oneTime,
            boolean seeThrough,
            String location,
            String camera,
            boolean smooth,
            String sound,
            float pitch,
            boolean perChar,
            int bgRgb,
            int bgAlpha,
            int lineWidth,
            boolean slowness,
            boolean invisible,
            boolean saveStart,
            float textScale
    ) {}

    record Dialogue(
            String name,
            Settings cfg,
            LinkedHashMap<String, Node> nodes
    ) {}


static final class Session {

    final Player player;
    final Dialogue dialogue;

    Node node;

    int selected;

    State state = State.BUSY;

    boolean closed;

    Location start;
    Location base;

    String camera;

    float scale = 1f;

    TextDisplay display;

    final List<TextDisplay> answers = new ArrayList<>();

    BukkitTask typeTask;
    BukkitTask moveTask;
    BukkitTask barTask;

    Session(Player player, Dialogue dialogue, Node node) {
        this.player = player;
        this.dialogue = dialogue;
        this.node = node;
    }
}

    static final class Gui implements InventoryHolder {

        Screen screen;

        String dialogue;
        String node;

        String targetType;

        int answer;
        int answerIndex;
        int page;

        Inventory inventory;

        Gui(
                String dialogue,
                Screen screen,
                String node,
                int page
        ) {
            this.dialogue = dialogue;
            this.screen = screen;
            this.node = node;
            this.page = page;
            this.answer = page;
            this.answerIndex = 0;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    record Prompt(
            Player player,
            Consumer<String> action,
            Runnable back
    ) {}

    record Setting(
            String key,
            String title,
            char type
    ) {}

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .build();

    private static final Pattern TOKEN =
            Pattern.compile(
                    "&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR]|.",
                    Pattern.DOTALL
            );

    private static final String NAME_REGEX =
            "[^/\\\\.:*?\"<>|]+";

    private static final int PAGE_SIZE = 45;

    private static final int MAX_ANSWERS = 9;

    private final Map<String, Dialogue> dialogues =
            new LinkedHashMap<>();

    private final Map<UUID, Session> sessions =
            new HashMap<>();

    private final Map<UUID, Prompt> prompts =
            new ConcurrentHashMap<>();

    private final Map<UUID, Gui> openGuis =
            new HashMap<>();

    private File dialoguesDir;

    private File dataFile;

    private YamlConfiguration data;

    private static final String TEMPLATE = """
            config:
              hide-players: true
              one-time: false
              see-through-blocks: false
              location: "0 0 0 world"
              camera-location: "0 0 0 0 0 world"
              smooth-teleport: true
              char-sound: "ui.button.click"
              char-sound-pitch: 1.0
              per-char: true
              background-color: "#1e1f22"
              background-alpha: 110
              line-width: 200
              use-slowness: true
              make-invisible: true
              save-start-location: true
              text-scale: 1.0
              show-actionbar-hints: true

            dialogue:
              start:
                text: "&fВітаю! Це приклад діалогу."
                char-delay: 2
                location-offset: "0 0 0"
                answers:
                  "1":
                    text: "&aРозкажи більше"
                    offset: "0 -0.3 0"
                    next: more
                  "2":
                    text: "&cДо побачення"
                    offset: "0 -0.6 0"
                    next: bye

              more:
                text: "&fТут можна створити власну історію."
                answers:
                  "1":
                    text: "&eЗрозуміло"
                    next: bye

              bye:
                text: "&7Щасливої дороги!"
            """;

    @Override
    public void onEnable() {

        setupConfig();

        dialoguesDir = new File(
                getDataFolder(),
                "dialogues"
        );

        if (!dialoguesDir.exists()) {
            dialoguesDir.mkdirs();
            writeExample();
        }

        dataFile = new File(
                getDataFolder(),
                "data.yml"
        );

        data = YamlConfiguration.loadConfiguration(dataFile);

        loadAll();

        getServer()
                .getPluginManager()
                .registerEvents(this, this);

        PluginCommand command = getCommand("fritty-dialogue");

        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        getLogger().info("FrittyDialogue 3.1.0 увімкнено.");
    }

    @Override
    public void onDisable() {

        for (Session session : new ArrayList<>(sessions.values())) {
            stopSession(session, true);
        }

        saveData();
    }

    private void setupConfig() {

        FileConfiguration cfg = getConfig();

        cfg.addDefault("log-dialogue-registration", true);

        cfg.addDefault(
                "messages.camera-location",
                "&7Позицію камери діалогу &f{name}&7 змінено."
        );

        cfg.addDefault(
                "messages.example-updated",
                "&aПриклад діалогу оновлено."
        );

        cfg.addDefault(
                "messages.dialogue-created",
                "&aДіалог &f{name}&a створено."
        );

        cfg.addDefault(
                "messages.dialogue-copied",
                "&aДіалог &f{name}&a скопійовано з &f{from}&a."
        );

        cfg.addDefault(
                "messages.reloaded",
                "&aДіалоги перезавантажено: &f{count}"
        );

        cfg.addDefault(
                "actionbar.skip",
                "&eF — пропустити текст"
        );

        cfg.addDefault(
                "actionbar.continue",
                "&eF — продовжити"
        );

        cfg.addDefault(
                "actionbar.choose",
                "&eW / S — вибрати відповідь"
        );

        cfg.addDefault(
                "actionbar.confirm",
                "&eF — підтвердити вибір"
        );

        cfg.addDefault(
                "errors.player-not-found",
                "&cГравця не знайдено."
        );

        cfg.addDefault(
                "errors.dialogue-not-found",
                "&cДіалог не знайдено."
        );

        cfg.addDefault(
                "errors.dialogue-exists",
                "&cДіалог із такою назвою вже існує."
        );

        cfg.addDefault(
                "errors.invalid-name",
                "&cНекоректна назва діалогу."
        );

        cfg.addDefault(
                "errors.one-time",
                "&cТи вже проходив цей діалог."
        );

        cfg.addDefault(
                "errors.only-player",
                "&cЦю команду може виконати лише гравець."
        );

        cfg.addDefault(
                "errors.usage",
                "&7/fd start <діалог> [гравець] | stop [гравець] | create <назва> | copy <нова назва> <старий діалог> | edit <діалог> | reload | load-example | set-camera-location <діалог>"
        );

        cfg.options().copyDefaults(true);

        saveConfig();
    }

    private void loadAll() {

        dialogues.clear();

        File[] files = dialoguesDir.listFiles(
                (dir, name) -> name.endsWith(".yml")
        );

        if (files == null) return;

        Arrays.sort(files);

        for (File file : files) {
            loadFile(file);
        }
    }

    private Dialogue loadFile(File file) {
        return loadFile(file, true);
    }

    private Dialogue loadFile(File file, boolean log) {

        String name = file.getName();

        name = name.substring(0, name.length() - 4);

        YamlConfiguration yaml =
                YamlConfiguration.loadConfiguration(file);

        ConfigurationSection dialogueSection =
                yaml.getConfigurationSection("dialogue");

        if (dialogueSection == null) {
            getLogger().warning(
                    "[" + name + "] Відсутня секція dialogue."
            );
            return null;
        }

        LinkedHashMap<String, Node> nodes =
                new LinkedHashMap<>();

        for (String id : dialogueSection.getKeys(false)) {

            ConfigurationSection nodeSection =
                    dialogueSection.getConfigurationSection(id);

            if (nodeSection == null) continue;

            List<Answer> answers = new ArrayList<>();

            ConfigurationSection answerSection =
                    nodeSection.getConfigurationSection("answers");

            if (answerSection != null) {

                for (String answerId : answerSection.getKeys(false)) {

                    ConfigurationSection answer =
                            answerSection.getConfigurationSection(answerId);

                    if (answer == null) continue;

                    List<String> commands =
                            new ArrayList<>(answer.getStringList("commands"));

                    if (answer.isString("command")) {
                        commands.add(answer.getString("command"));
                    }

                    answers.add(new Answer(
                            answer.getString("text", ""),
                            parseVector(answer.get("offset")),
                            commands,
                            clean(answer.getString("next"))
                    ));
                }
            }

            nodes.put(id, new Node(
                    id,
                    nodeSection.getString("text", ""),
                    nodeSection.getInt("char-delay", 2),
                    parseVector(nodeSection.get("location-offset")),
                    answers,
                    clean(nodeSection.getString("next")),
                    clean(nodeSection.getString("location")),
                    clean(nodeSection.getString("camera-location")),
                    nodeSection.isSet("text-scale")
                            ? clampScale(nodeSection.getDouble("text-scale", 1.0))
                            : null
            ));
        }

        for (Node node : nodes.values()) {

            if (node.next() != null &&
                    !nodes.containsKey(node.next())) {

                getLogger().warning(
                        "[" + name + "] Вузол '" + node.id()
                                + "' посилається на неіснуючий вузол '"
                                + node.next() + "'."
                );
            }

            for (Answer answer : node.answers()) {

                if (answer.next() != null &&
                        !nodes.containsKey(answer.next())) {

                    getLogger().warning(
                            "[" + name + "] Відповідь '"
                                    + answer.text()
                                    + "' посилається на неіснуючий вузол '"
                                    + answer.next() + "'."
                    );
                }
            }
        }

        Dialogue loaded = new Dialogue(
                name,
                parseSettings(yaml.getConfigurationSection("config")),
                nodes
        );

        dialogues.put(name, loaded);

        if (log && getConfig().getBoolean(
                "log-dialogue-registration",
                true
        )) {
            getLogger().info(
                    "Зареєстровано діалог: "
                            + name
                            + " (вузлів: "
                            + nodes.size()
                            + ")"
            );
        }

        return loaded;
    }

    private Settings parseSettings(ConfigurationSection section) {

        if (section == null) {
            section = new YamlConfiguration();
        }

        String background = section.getString(
                "background-color",
                "#1e1f22"
        );

        background = background.replace("#", "").trim();

        int rgb;

        try {
            rgb = Integer.parseInt(background, 16);
        } catch (NumberFormatException exception) {
            rgb = 0x1e1f22;
        }

        int alpha = Math.max(
                0,
                Math.min(
                        255,
                        section.getInt("background-alpha", 110)
                )
        );

        return new Settings(
                section.getBoolean("hide-players", true),
                section.getBoolean("one-time", false),
                section.getBoolean("see-through-blocks", false),
                section.getString("location", "0 0 0 world"),
                section.getString("camera-location", "0 0 0 0 0 world"),
                section.getBoolean("smooth-teleport", true),
                section.getString("char-sound", "ui.button.click"),
                (float) section.getDouble("char-sound-pitch", 1.0),
                section.getBoolean("per-char", true),
                rgb,
                alpha,
                section.getInt("line-width", 200),
                section.getBoolean("use-slowness", true),
                section.getBoolean("make-invisible", true),
                section.getBoolean("save-start-location", true),
                clampScale(section.getDouble("text-scale", 1.0))
        );
    }

    private static float clampScale(double value) {

        return (float) Math.max(
                0.1,
                Math.min(10.0, value)
        );
    }

    private static String clean(String value) {

        if (value == null) return null;

        value = value.trim();

        return value.isEmpty() ? null : value;
    }

    private static Vector parseVector(Object value) {

        double[] coordinates = new double[3];

        if (value != null) {

            String[] parts = value.toString()
                    .trim()
                    .split("\\s+");

            for (int i = 0; i < 3 && i < parts.length; i++) {

                try {
                    coordinates[i] = Double.parseDouble(parts[i]);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return new Vector(
                coordinates[0],
                coordinates[1],
                coordinates[2]
        );
    }

    private static Location parseLoc(String value) {

        String[] parts = value.trim().split("\\s+");

        World world = Bukkit.getWorlds().get(0);

        int length = parts.length;

        if (length > 0 && !isNumber(parts[length - 1])) {

            World found = Bukkit.getWorld(parts[length - 1]);

            if (found != null) {
                world = found;
            }

            length--;
        }

        double[] coordinates = new double[5];

        for (int i = 0; i < 5 && i < length; i++) {

            try {
                coordinates[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException ignored) {
            }
        }

        return new Location(
                world,
                coordinates[0],
                coordinates[1],
                coordinates[2],
                (float) coordinates[3],
                (float) coordinates[4]
        );
    }

    private static boolean isNumber(String value) {

        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String locStr(Location location, boolean withDirection) {

        if (withDirection) {
            return String.format(
                    Locale.US,
                    "%.3f %.3f %.3f %.2f %.2f %s",
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch(),
                    location.getWorld().getName()
            );
        }

        return String.format(
                Locale.US,
                "%.3f %.3f %.3f %s",
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getWorld().getName()
        );
    }

    private static Component render(String value) {

        return LEGACY.deserialize(value)
                .decoration(TextDecoration.ITALIC, false)
                .colorIfAbsent(NamedTextColor.WHITE);
    }

    private static List<String> tokenize(String value) {

        List<String> result = new ArrayList<>();

        Matcher matcher = TOKEN.matcher(value);

        while (matcher.find()) {
            result.add(matcher.group());
        }

        return result;
    }

    private static boolean isCode(String token) {

        return token.length() > 1
                && token.charAt(0) == '&';
    }

    private static void cancel(BukkitTask task) {

        if (task != null) {
            task.cancel();
        }
    }

    private TextDisplay spawnDisplay(
            Session session,
            Location location,
            Component text
    ) {

        Settings settings = session.dialogue.cfg();

        Color background = Color.fromARGB(
                settings.bgAlpha(),
                (settings.bgRgb() >> 16) & 255,
                (settings.bgRgb() >> 8) & 255,
                settings.bgRgb() & 255
        );

        TextDisplay display = location.getWorld().spawn(
                location,
                TextDisplay.class,
                entity -> {
                    entity.setVisibleByDefault(false);
                    entity.setPersistent(false);
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setLineWidth(settings.lineWidth());
                    entity.setBackgroundColor(background);
                    entity.setSeeThrough(settings.seeThrough());
                    entity.text(text);
                }
        );

        session.player.showEntity(this, display);

        return display;
    }

    private void setScale(TextDisplay display, float scale, int ticks) {

        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ticks);

        display.setTransformation(
                new Transformation(
                        new Vector3f(),
                        new AxisAngle4f(),
                        new Vector3f(scale, scale, scale),
                        new AxisAngle4f()
                )
        );
    }

    private void select(Session session, int index) {

        for (int i = 0; i < session.answers.size(); i++) {

            setScale(
                    session.answers.get(i),
                    i == index ? session.scale * 1.2f : session.scale,
                    3
            );
        }

        session.selected = index;
    }

    private void clearAnswers(Session session) {

        for (TextDisplay display : session.answers) {
            display.remove();
        }

        session.answers.clear();
    }

    private void sound(Player player, Settings settings) {

        if (settings.sound() == null || settings.sound().isBlank()) {
            return;
        }

        player.playSound(
                player.getLocation(),
                settings.sound(),
                SoundCategory.MASTER,
                1f,
                settings.pitch()
        );
    }

    private void sendBar(Session session) {

        if (!getConfig().getBoolean("show-actionbar-hints", true)) {
            return;
        }

        String key = switch (session.state) {
            case TYPING -> "actionbar.skip";
            case CHOOSING -> "actionbar.choose";
            case CONTINUE -> "actionbar.continue";
            default -> null;
        };

        if (key == null) {
            session.player.sendActionBar(Component.empty());
            return;
        }

        String message = getConfig().getString(key, "");

        if (session.state == State.CHOOSING) {
            message += "  " + getConfig().getString(
                    "actionbar.confirm",
                    "&eF — підтвердити вибір"
            );
        }

        session.player.sendActionBar(render(message));
    }
    private void startDialogue(Player player, Dialogue dialogue) {

        if (dialogue.nodes().isEmpty()) {
            player.sendMessage(render("&cУ цьому діалозі немає вузлів."));
            return;
        }

        if (sessions.containsKey(player.getUniqueId())) {
            stopSession(player, false);
        }

        Settings settings = dialogue.cfg();

        if (settings.oneTime() && isCompleted(player, dialogue.name())) {
            player.sendMessage(render("&cТи вже проходив цей діалог."));
            return;
        }

        Node first = dialogue.nodes().values().iterator().next();

        Session session = new Session(player, dialogue, first);

        session.start = player.getLocation().clone();
        session.base = player.getLocation().clone();
        session.camera = first.camera() != null
                ? first.camera()
                : settings.camera();
        session.scale = first.scale() != null
                ? first.scale()
                : settings.textScale();

        sessions.put(player.getUniqueId(), session);

        if (settings.hidePlayers()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    player.hidePlayer(this, online);
                }
            }
        }

        if (settings.invisible()) {
            player.addPotionEffect(
                    new PotionEffect(
                            PotionEffectType.INVISIBILITY,
                            Integer.MAX_VALUE,
                            0,
                            false,
                            false,
                            false
                    )
            );
        }

        if (settings.slowness()) {
            player.addPotionEffect(
                    new PotionEffect(
                            PotionEffectType.SLOWNESS,
                            Integer.MAX_VALUE,
                            10,
                            false,
                            false,
                            false
                    )
            );
        }

        player.setInvulnerable(true);

        showNode(session, first);
    }

    private void moveCamera(Session session) {

        if (session.closed) {
            return;
        }

        Player player = session.player;

        String camera = session.camera;

        if (camera == null || camera.isBlank()) {
            return;
        }

        Location target = parseLoc(camera);

        if (target == null || target.getWorld() == null) {
            return;
        }

        if (!target.getWorld().equals(player.getWorld())) {
            return;
        }

        if (!session.dialogue.cfg().smooth()) {
            player.teleport(
                    target,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );
            return;
        }

        cancel(session.moveTask);

        Location start = player.getLocation().clone();

        session.moveTask = Bukkit.getScheduler().runTaskTimer(
                this,
                new Runnable() {

                    private int tick;

                    private final int duration = 20;

                    @Override
                    public void run() {

                        if (session.closed || !player.isOnline()) {
                            cancel(session.moveTask);
                            return;
                        }

                        tick++;

                        double progress = Math.min(
                                1.0,
                                (double) tick / duration
                        );

                        double eased = progress * progress
                                * (3.0 - 2.0 * progress);

                        double x = start.getX()
                                + (target.getX() - start.getX()) * eased;

                        double y = start.getY()
                                + (target.getY() - start.getY()) * eased;

                        double z = start.getZ()
                                + (target.getZ() - start.getZ()) * eased;

                        float yaw = start.getYaw()
                                + (target.getYaw() - start.getYaw())
                                * (float) eased;

                        float pitch = start.getPitch()
                                + (target.getPitch() - start.getPitch())
                                * (float) eased;

                        Location current = new Location(
                                player.getWorld(),
                                x,
                                y,
                                z,
                                yaw,
                                pitch
                        );

                        player.teleport(
                                current,
                                PlayerTeleportEvent.TeleportCause.PLUGIN
                        );

                        if (tick >= duration) {
                            cancel(session.moveTask);
                        }
                    }
                },
                0L,
                1L
        );
    }

    private void showNode(Session session, Node node) {

        if (session.closed) {
            return;
        }

        session.node = node;
        session.state = State.BUSY;
        session.selected = 0;

        cancel(session.typeTask);
        clearAnswers(session);

        if (session.display != null) {
            session.display.remove();
            session.display = null;
        }

        Settings settings = session.dialogue.cfg();

        String camera = node.camera() != null
                ? node.camera()
                : settings.camera();

        if (camera != null && !camera.isBlank()) {
            session.camera = camera;
            moveCamera(session);
        }

        int delay = Math.max(0, node.delay());

        Bukkit.getScheduler().runTaskLater(
                this,
                () -> {
                    if (!session.closed) {
                        placeText(session, node);
                        typeNode(session, node);
                    }
                },
                delay
        );
    }

    private void placeText(Session session, Node node) {

        Settings settings = session.dialogue.cfg();

        String locationValue = node.location() != null
                ? node.location()
                : settings.location();

        Location location = parseLoc(locationValue);

        if (location == null || location.getWorld() == null) {
            location = session.player.getLocation().clone();
            location.add(0, 2.2, 0);
        }

        if (!location.getWorld().equals(session.player.getWorld())) {
            location = session.player.getLocation().clone();
            location.add(0, 2.2, 0);
        }

        Component initial = Component.empty();

        session.display = spawnDisplay(
                session,
                location,
                initial
        );

        setScale(
                session.display,
                session.scale,
                0
        );
    }

    private void typeNode(Session session, Node node) {

        if (session.closed || session.display == null) {
            return;
        }

        session.state = State.TYPING;
        sendBar(session);

        List<String> tokens = tokenize(node.text());

        StringBuilder visible = new StringBuilder();

        int speed = Math.max(
                1,
                getConfig().getInt("typing-speed", 2)
        );

        boolean perCharacter = session.dialogue.cfg().perChar();

        session.typeTask = Bukkit.getScheduler().runTaskTimer(
                this,
                new Runnable() {

                    private int index;

                    @Override
                    public void run() {

                        if (session.closed
                                || session.display == null
                                || !session.player.isOnline()) {
                            cancel(session.typeTask);
                            return;
                        }

                        int amount = perCharacter ? 1 : speed;

                        for (int i = 0; i < amount; i++) {

                            if (index >= tokens.size()) {
                                finishTyping(session);
                                return;
                            }

                            String token = tokens.get(index++);

                            visible.append(token);

                            if (!isCode(token)
                                    && session.dialogue.cfg().sound() != null
                                    && !session.dialogue.cfg().sound().isBlank()) {
                                sound(
                                        session.player,
                                        session.dialogue.cfg()
                                );
                            }
                        }

                        session.display.text(render(visible.toString()));

                        if (index >= tokens.size()) {
                            finishTyping(session);
                        }
                    }
                },
                0L,
                1L
        );
    }

    private void finishTyping(Session session) {

        if (session.closed || session.state != State.TYPING) {
            return;
        }

        cancel(session.typeTask);
        session.typeTask = null;

        if (session.display != null) {
            session.display.text(render(session.node.text()));
        }

        if (session.node.answers().isEmpty()) {
            session.state = State.CONTINUE;
            sendBar(session);
            return;
        }

        session.state = State.CHOOSING;
        session.selected = 0;

        showAnswers(session);
        sendBar(session);
    }

    private void showAnswers(Session session) {

        clearAnswers(session);

        List<Answer> answers = session.node.answers();

        if (answers.isEmpty()) {
            return;
        }

        Location base = session.display.getLocation().clone();

        double yOffset = -0.35;

        for (int i = 0; i < answers.size(); i++) {

            Answer answer = answers.get(i);

            Vector offset = answer.offset() == null
                    ? new Vector(0, yOffset * (i + 1), 0)
                    : answer.offset();

            Location answerLocation = base.clone().add(offset);

            TextDisplay display = spawnDisplay(
                    session,
                    answerLocation,
                    render(answer.text())
            );

            setScale(
                    display,
                    session.scale,
                    0
            );

            session.answers.add(display);
        }

        select(session, 0);
    }

    private void choose(Session session) {

        if (session.closed) {
            return;
        }

        if (session.state == State.TYPING) {
            finishTyping(session);
            return;
        }

        if (session.state == State.CONTINUE) {
            goTo(session, session.node.next());
            return;
        }

        if (session.state != State.CHOOSING) {
            return;
        }

        List<Answer> answers = session.node.answers();

        if (answers.isEmpty()
                || session.selected < 0
                || session.selected >= answers.size()) {
            return;
        }

        Answer answer = answers.get(session.selected);

        for (String command : answer.commands()) {
            dispatch(session.player, command);
        }

        goTo(session, answer.next());
    }

    private void goTo(Session session, String target) {

        if (session.closed) {
            return;
        }

        if (target == null || target.isBlank()) {
            finish(session);
            return;
        }

        Node next = session.dialogue.nodes().get(target);

        if (next == null) {
            session.player.sendMessage(
                    render("&cНе знайдено вузол: &f" + target)
            );
            finish(session);
            return;
        }

        showNode(session, next);
    }

    private void finish(Session session) {

        if (session.closed) {
            return;
        }

        markCompleted(session.player, session.dialogue.name());

        stopSession(session.player, true);
    }

    private void stopSession(Player player, boolean completed) {

        Session session = sessions.remove(player.getUniqueId());

        if (session == null) {
            return;
        }

        session.closed = true;

        cancel(session.typeTask);
        cancel(session.moveTask);
        cancel(session.barTask);

        clearAnswers(session);

        if (session.display != null) {
            session.display.remove();
            session.display = null;
        }

        player.setInvulnerable(false);

        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.SLOWNESS);

        if (session.dialogue.cfg().hidePlayers()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    player.showPlayer(this, online);
                }
            }
        }

        if (session.dialogue.cfg().saveStart()
                && session.start != null
                && session.start.getWorld() != null) {
            player.teleport(
                    session.start,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );
        }

        player.sendActionBar(Component.empty());

        if (completed) {
            player.sendMessage(render("&aДіалог завершено."));
        }
    }

    private void dispatch(Player player, String command) {

        if (command == null || command.isBlank()) {
            return;
        }

        String value = command
                .replace("%player%", player.getName())
                .replace("{player}", player.getName());

        if (value.startsWith("/")) {
            value = value.substring(1);
        }

        Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                value
        );
    }

    private boolean isCompleted(Player player, String dialogue) {

        return getConfig().getBoolean(
                "completed." + player.getUniqueId() + "." + dialogue,
                false
        );
    }

    private void markCompleted(Player player, String dialogue) {

        getConfig().set(
                "completed." + player.getUniqueId() + "." + dialogue,
                true
        );

        saveConfig();
    }
    private final Map<UUID, Boolean> forwardPressed = new HashMap<>();
    private final Map<UUID, Boolean> backwardPressed = new HashMap<>();

    @EventHandler
    public void onInput(io.papermc.paper.event.player.PlayerInputEvent event) {

        Player player = event.getPlayer();

        Session session = sessions.get(player.getUniqueId());

        if (session == null || session.closed) {
            return;
        }

        boolean forward = event.getInput().isForward();
        boolean backward = event.getInput().isBackward();

        boolean wasForward = forwardPressed.getOrDefault(
                player.getUniqueId(),
                false
        );

        boolean wasBackward = backwardPressed.getOrDefault(
                player.getUniqueId(),
                false
        );

        forwardPressed.put(player.getUniqueId(), forward);
        backwardPressed.put(player.getUniqueId(), backward);

        if (session.state != State.CHOOSING) {
            return;
        }

        int answerCount = session.node.answers().size();

        if (answerCount == 0) {
            return;
        }

        if (forward && !wasForward) {
            int next = session.selected - 1;

            if (next < 0) {
                next = answerCount - 1;
            }

            select(session, next);
        }

        if (backward && !wasBackward) {
            int next = session.selected + 1;

            if (next >= answerCount) {
                next = 0;
            }

            select(session, next);
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {

        Player player = event.getPlayer();

        Session session = sessions.get(player.getUniqueId());

        if (session == null || session.closed) {
            return;
        }

        event.setCancelled(true);

        if (session.state == State.TYPING
                || session.state == State.CHOOSING
                || session.state == State.CONTINUE) {
            choose(session);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {

        Player player = event.getPlayer();

        Session session = sessions.get(player.getUniqueId());

        if (session == null || session.closed) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) {
            return;
        }

        if (from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()) {

            Location locked = from.clone();

            locked.setYaw(to.getYaw());
            locked.setPitch(to.getPitch());

            event.setTo(locked);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {

        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        Player player = event.getPlayer();

        forwardPressed.remove(player.getUniqueId());
        backwardPressed.remove(player.getUniqueId());

        if (sessions.containsKey(player.getUniqueId())) {
            stopSession(player, false);
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!command.getName().equalsIgnoreCase("fd")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage(render(
                    "&e/fd start <діалог>"
            ));
            sender.sendMessage(render(
                    "&e/fd stop"
            ));
            sender.sendMessage(render(
                    "&e/fd create <назва>"
            ));
            sender.sendMessage(render(
                    "&e/fd copy <нова назва> <існуючий діалог>"
            ));
            sender.sendMessage(render(
                    "&e/fd edit <діалог>"
            ));
            sender.sendMessage(render(
                    "&e/fd reload"
            ));
            sender.sendMessage(render(
                    "&e/fd load-example"
            ));
            sender.sendMessage(render(
                    "&e/fd set-camera-location"
            ));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {

            case "start" -> {

                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Цю команду може виконати лише гравець.");
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(render(
                            "&cВикористання: /fd start <діалог>"
                    ));
                    return true;
                }

                Dialogue dialogue = dialogues.get(args[1]);

                if (dialogue == null) {
                    player.sendMessage(render(
                            "&cДіалог не знайдено."
                    ));
                    return true;
                }

                startDialogue(player, dialogue);
                return true;
            }

            case "stop" -> {

                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Цю команду може виконати лише гравець.");
                    return true;
                }

                if (!sessions.containsKey(player.getUniqueId())) {
                    player.sendMessage(render(
                            "&cТи зараз не проходиш діалог."
                    ));
                    return true;
                }

                stopSession(player, false);
                player.sendMessage(render(
                        "&eДіалог зупинено."
                ));
                return true;
            }

            case "create" -> {

                if (!sender.hasPermission("frittydialogue.admin")) {
                    sender.sendMessage(render("&cНедостатньо прав."));
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(render(
                            "&cВикористання: /fd create <назва>"
                    ));
                    return true;
                }

                String name = String.join(
                        " ",
                        Arrays.copyOfRange(args, 1, args.length)
                );

                return cmdCreate(sender, name);
            }

            case "copy" -> {

                if (!sender.hasPermission("frittydialogue.admin")) {
                    sender.sendMessage(render("&cНедостатньо прав."));
                    return true;
                }

                if (args.length < 3) {
                    sender.sendMessage(render(
                            "&cВикористання: /fd copy <нова назва> <існуючий діалог>"
                    ));
                    return true;
                }

                String oldName = args[args.length - 1];

                String newName = String.join(
                        " ",
                        Arrays.copyOfRange(args, 1, args.length - 1)
                );

                return cmdCopy(sender, newName, oldName);
            }

            case "edit" -> {

                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Цю команду може виконати лише гравець.");
                    return true;
                }

                if (!player.hasPermission("frittydialogue.admin")) {
                    player.sendMessage(render("&cНедостатньо прав."));
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(render(
                            "&cВикористання: /fd edit <діалог>"
                    ));
                    return true;
                }

                Dialogue dialogue = dialogues.get(args[1]);

                if (dialogue == null) {
                    player.sendMessage(render(
                            "&cДіалог не знайдено."
                    ));
                    return true;
                }

                openNodes(player, dialogue.name());
                return true;
            }

            case "reload" -> {

                if (!sender.hasPermission("frittydialogue.admin")) {
                    sender.sendMessage(render("&cНедостатньо прав."));
                    return true;
                }

                loadAll();

                sender.sendMessage(render(
                        "&aДіалоги перезавантажено."
                ));
                return true;
            }

            case "load-example" -> {

                if (!sender.hasPermission("frittydialogue.admin")) {
                    sender.sendMessage(render("&cНедостатньо прав."));
                    return true;
                }

                File example = new File(
                        getDataFolder(),
                        "example.yml"
                );

                if (example.exists()) {
                    sender.sendMessage(render(
                            "&cПриклад уже існує."
                    ));
                    return true;
                }

                try {
                    Files.writeString(
                            example.toPath(),
                            TEMPLATE,
                            StandardCharsets.UTF_8
                    );

                    loadFile(example, true);

                    sender.sendMessage(render(
                            "&aПриклад діалогу створено."
                    ));

                } catch (IOException exception) {

                    sender.sendMessage(render(
                            "&cНе вдалося створити приклад."
                    ));

                    getLogger().severe(
                            "Не вдалося створити example.yml: "
                                    + exception.getMessage()
                    );
                }

                return true;
            }

            case "set-camera-location" -> {

                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Цю команду може виконати лише гравець.");
                    return true;
                }

                if (!player.hasPermission("frittydialogue.admin")) {
                    player.sendMessage(render("&cНедостатньо прав."));
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(render(
                            "&cВикористання: /fd set-camera-location <діалог>"
                    ));
                    return true;
                }

                Dialogue dialogue = dialogues.get(args[1]);

                if (dialogue == null) {
                    player.sendMessage(render(
                            "&cДіалог не знайдено."
                    ));
                    return true;
                }

                String location = locStr(
                        player.getLocation(),
                        true
                );

                edit(
                        dialogue,
                        yaml -> cfgSec(yaml).set(
                                "camera-location",
                                location
                        )
                );

                player.sendMessage(render(
                        "&aГлобальну позицію камери збережено."
                ));

                return true;
            }

            default -> {
                sender.sendMessage(render(
                        "&cНевідома підкоманда."
                ));
                return true;
            }
        }
    }

    private boolean cmdCreate(CommandSender sender, String name) {

        if (!validName(name)) {
            sender.sendMessage(render(
                    "&cНазва містить недопустимі символи."
            ));
            return true;
        }

        if (dialogues.containsKey(name)) {
            sender.sendMessage(render(
                    "&cДіалог із такою назвою вже існує."
            ));
            return true;
        }

        File file = new File(
            dialoguesDir,
            name + ".yml"
        );

        if (file.exists()) {
            sender.sendMessage(render(
                    "&cФайл із такою назвою вже існує."
            ));
            return true;
        }

        try {

            Files.writeString(
                    file.toPath(),
                    TEMPLATE,
                    StandardCharsets.UTF_8
            );

            loadFile(file, true);

            sender.sendMessage(render(
                    "&aДіалог &f" + name + " &aстворено."
            ));

            if (sender instanceof Player player) {
                openNodes(player, name);
            }

        } catch (IOException exception) {

            sender.sendMessage(render(
                    "&cНе вдалося створити діалог."
            ));

            getLogger().severe(
                    "Помилка створення діалогу "
                            + name + ": "
                            + exception.getMessage()
            );
        }

        return true;
    }

    private boolean cmdCopy(
            CommandSender sender,
            String newName,
            String oldName
    ) {

        if (!validName(newName)) {
            sender.sendMessage(render(
                    "&cНова назва містить недопустимі символи."
            ));
            return true;
        }

        Dialogue source = dialogues.get(oldName);

        if (source == null) {
            sender.sendMessage(render(
                    "&cПочатковий діалог не знайдено."
            ));
            return true;
        }

        if (dialogues.containsKey(newName)) {
            sender.sendMessage(render(
                    "&cДіалог із такою назвою вже існує."
            ));
            return true;
        }

        File destination = new File(
            dialoguesDir,
            newName + ".yml"
        );

        if (destination.exists()) {
            sender.sendMessage(render(
                    "&cФайл із такою назвою вже існує."
            ));
            return true;
        }

        File original = new File(
            dialoguesDir,
            oldName + ".yml"
        );
        try {

            Files.copy(
                    original.toPath(),
                    destination.toPath()
            );

            YamlConfiguration yaml =
                    YamlConfiguration.loadConfiguration(destination);

            yaml.set("name", newName);
            yaml.save(destination);

            loadFile(destination, true);

            sender.sendMessage(render(
                    "&aДіалог &f" + oldName
                            + " &aскопійовано як &f"
                            + newName + "&a."
            ));

            if (sender instanceof Player player) {
                openNodes(player, newName);
            }

        } catch (IOException exception) {

            sender.sendMessage(render(
                    "&cНе вдалося скопіювати діалог."
            ));

            getLogger().severe(
                    "Помилка копіювання діалогу: "
                            + exception.getMessage()
            );
        }

        return true;
    }

    private boolean validName(String name) {

        if (name == null || name.isBlank()) {
            return false;
        }

        if (name.equals(".") || name.equals("..")) {
            return false;
        }

        return !name.matches(".*[./\\\\:*?\"<>|].*");
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {

        if (!command.getName().equalsIgnoreCase("fd")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList(
                    "start",
                    "stop",
                    "create",
                    "copy",
                    "edit",
                    "reload",
                    "load-example",
                    "set-camera-location"
            ).stream()
                    .filter(value -> value.startsWith(
                            args[0].toLowerCase(Locale.ROOT)
                    ))
                    .toList();
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("start")
                || args[0].equalsIgnoreCase("edit")
                || args[0].equalsIgnoreCase("set-camera-location"))) {

            return dialogues.keySet().stream()
                    .filter(value -> value.startsWith(args[1]))
                    .toList();
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("copy")) {

            return dialogues.keySet().stream()
                    .filter(value -> value.startsWith(args[2]))
                    .toList();
        }

        return Collections.emptyList();
            }

    private void openNodes(Player player, String dialogueName) {

        Dialogue dialogue = dialogues.get(dialogueName);

        if (dialogue == null) {
            player.sendMessage(render("&cДіалог не знайдено."));
            return;
        }

        openNodes(player, dialogueName, 0);
    }

    private void openNodes(
            Player player,
            String dialogueName,
            int page
    ) {

        Dialogue dialogue = dialogues.get(dialogueName);

        if (dialogue == null) {
            return;
        }

        List<Node> nodes = new ArrayList<>(
                dialogue.nodes().values()
        );

        int pages = Math.max(
                1,
                (nodes.size() + PAGE_SIZE - 1) / PAGE_SIZE
        );

        page = Math.max(0, Math.min(page, pages - 1));

        Gui gui = new Gui(
                dialogueName,
                Screen.NODES,
                null,
                page
        );

        Inventory inventory = Bukkit.createInventory(
                gui,
                54,
                render("&8Діалог: &e" + dialogueName)
        );

        gui.inventory = inventory;

        int start = page * PAGE_SIZE;
        int end = Math.min(
                nodes.size(),
                start + PAGE_SIZE
        );

        for (int i = start; i < end; i++) {

            Node node = nodes.get(i);

            ItemStack item = new ItemStack(
                    Material.PAPER
            );

            ItemMeta meta = item.getItemMeta();

            meta.displayName(render(
                    "&e" + node.id()
            ));

            meta.lore(List.of(
                    render("&7Натисни, щоб редагувати"),
                    render("&8Відповідей: &f" + node.answers().size())
            ));

            item.setItemMeta(meta);

            inventory.setItem(
                    i - start,
                    item
            );
        }

        inventory.setItem(
                45,
                menuItem(
                        Material.BOOK_AND_QUILL,
                        "&6Загальні налаштування",
                        "&7Налаштування всього діалогу"
                )
        );

        inventory.setItem(
                49,
                menuItem(
                        Material.EMERALD,
                        "&aСтворити вузол",
                        "&7Додати новий вузол діалогу"
                )
        );

        inventory.setItem(
                53,
                menuItem(
                        Material.LIME_DYE,
                        "&aПеревірити діалог",
                        "&7Запустити діалог для себе"
                )
        );

        if (page > 0) {
            inventory.setItem(
                    48,
                    menuItem(
                            Material.ARROW,
                            "&eПопередня сторінка",
                            "&7Сторінка " + page
                    )
            );
        }

        if (page + 1 < pages) {
            inventory.setItem(
                    50,
                    menuItem(
                            Material.ARROW,
                            "&eНаступна сторінка",
                            "&7Сторінка " + (page + 2)
                    )
            );
        }

        player.openInventory(inventory);
    }

    private void openNode(
            Player player,
            String dialogueName,
            String nodeId
    ) {

        Dialogue dialogue = dialogues.get(dialogueName);

        if (dialogue == null) {
            return;
        }

        Node node = dialogue.nodes().get(nodeId);

        if (node == null) {
            return;
        }

        Gui gui = new Gui(
                dialogueName,
                Screen.NODE,
                nodeId,
                0
        );

        Inventory inventory = Bukkit.createInventory(
                gui,
                54,
                render("&8Вузол: &e" + nodeId)
        );

        gui.inventory = inventory;

        inventory.setItem(
                10,
                menuItem(
                        Material.WRITABLE_BOOK,
                        "&eТекст вузла",
                        "&7" + shortText(node.text()),
                        "&7Натисни, щоб змінити"
                )
        );

        inventory.setItem(
                11,
                menuItem(
                        Material.CLOCK,
                        "&eЗатримка",
                        "&7Поточне значення: &f" + node.delay(),
                        "&7Тиків перед показом"
                )
        );

        inventory.setItem(
                12,
                menuItem(
                        Material.GLOWSTONE_DUST,
                        "&eМасштаб тексту",
                        "&7" + (node.scale() == null
                                ? "Загальне налаштування"
                                : node.scale())
                )
        );

        inventory.setItem(
                13,
                menuItem(
                        Material.COMPASS,
                        "&eЗміщення тексту",
                        "&7" + vectorString(node.offset())
                )
        );

        inventory.setItem(
                14,
                menuItem(
                        Material.ENDER_EYE,
                        "&eПозиція тексту",
                        "&7" + valueOrGlobal(node.location())
                )
        );

        inventory.setItem(
                15,
                menuItem(
                        Material.ENDER_PEARL,
                        "&eПозиція камери",
                        "&7" + valueOrGlobal(node.camera()),
                        "&7Натисни, щоб зберегти поточну позицію"
                )
        );

        inventory.setItem(
                16,
                menuItem(
                        Material.OAK_SIGN,
                        "&eНаступний вузол",
                        "&7" + valueOrGlobal(node.next())
                )
        );

        for (int i = 0; i < 9; i++) {

            int slot = 27 + i;

            if (i < node.answers().size()) {

                Answer answer = node.answers().get(i);

                inventory.setItem(
                        slot,
                        menuItem(
                                Material.LIGHT_BLUE_DYE,
                                "&bВідповідь " + (i + 1),
                                "&7" + shortText(answer.text()),
                                "&7Натисни, щоб редагувати"
                        )
                );

            } else if (i == node.answers().size()
                    && node.answers().size() < 9) {

                inventory.setItem(
                        slot,
                        menuItem(
                                Material.LIME_DYE,
                                "&aДодати відповідь",
                                "&7Максимум 9 відповідей"
                        )
                );
            }
        }

        inventory.setItem(
                45,
                menuItem(
                        Material.ARROW,
                        "&eНазад",
                        "&7До списку вузлів"
                )
        );

        inventory.setItem(
                53,
                menuItem(
                        Material.BARRIER,
                        "&cВидалити вузол",
                        "&7Видалити цей вузол"
                )
        );

        player.openInventory(inventory);
    }

    private void openAnswer(
            Player player,
            String dialogueName,
            String nodeId,
            int answerIndex
    ) {

        Dialogue dialogue = dialogues.get(dialogueName);

        if (dialogue == null) {
            return;
        }

        Node node = dialogue.nodes().get(nodeId);

        if (node == null
                || answerIndex < 0
                || answerIndex >= node.answers().size()) {
            return;
        }

        Answer answer = node.answers().get(answerIndex);

        Gui gui = new Gui(
                dialogueName,
                Screen.ANSWER,
                nodeId,
                answerIndex
        );

        Inventory inventory = Bukkit.createInventory(
                gui,
                27,
                render("&8Редагування відповіді")
        );

        gui.inventory = inventory;

        inventory.setItem(
                10,
                menuItem(
                        Material.WRITABLE_BOOK,
                        "&eТекст відповіді",
                        "&7" + shortText(answer.text())
                )
        );

        inventory.setItem(
                12,
                menuItem(
                        Material.COMPASS,
                        "&eЗміщення відповіді",
                        "&7" + vectorString(answer.offset())
                )
        );

        inventory.setItem(
                14,
                menuItem(
                        Material.OAK_SIGN,
                        "&eНаступний вузол",
                        "&7" + valueOrGlobal(answer.next())
                )
        );

        inventory.setItem(
                16,
                menuItem(
                        Material.COMMAND_BLOCK,
                        "&eКоманди відповіді",
                        "&7Команд: &f" + answer.commands().size()
                )
        );

        inventory.setItem(
                22,
                menuItem(
                        Material.ARROW,
                        "&eНазад",
                        "&7До редагування вузла"
                )
        );

        player.openInventory(inventory);
    }

    private void openPick(
            Player player,
            String dialogueName,
            String nodeId,
            String targetType,
            int answerIndex,
            int page
    ) {

        Dialogue dialogue = dialogues.get(dialogueName);

        if (dialogue == null) {
            return;
        }

        List<String> names = new ArrayList<>(
                dialogue.nodes().keySet()
        );

        int pages = Math.max(
                1,
                (names.size() + PAGE_SIZE - 1) / PAGE_SIZE
        );

        page = Math.max(0, Math.min(page, pages - 1));

        Gui gui = new Gui(
                dialogueName,
                Screen.PICK,
                nodeId,
                page
        );

        gui.targetType = targetType;
        gui.answerIndex = answerIndex;

        Inventory inventory = Bukkit.createInventory(
                gui,
                54,
                render("&8Вибір наступного вузла")
        );

        gui.inventory = inventory;

        int start = page * PAGE_SIZE;
        int end = Math.min(
                names.size(),
                start + PAGE_SIZE
        );

        for (int i = start; i < end; i++) {

            String name = names.get(i);

            inventory.setItem(
                    i - start,
                    menuItem(
                            Material.PAPER,
                            "&e" + name,
                            "&7Натисни, щоб вибрати"
                    )
            );
        }

        inventory.setItem(
                45,
                menuItem(
                        Material.BARRIER,
                        "&cБез наступного вузла",
                        "&7Завершити діалог"
                )
        );

        inventory.setItem(
                49,
                menuItem(
                        Material.ARROW,
                        "&eНазад",
                        "&7До редактора"
                )
        );

        if (page > 0) {
            inventory.setItem(
                    48,
                    menuItem(
                            Material.ARROW,
                            "&eПопередня сторінка"
                    )
            );
        }

        if (page + 1 < pages) {
            inventory.setItem(
                    50,
                    menuItem(
                            Material.ARROW,
                            "&eНаступна сторінка"
                    )
            );
        }

        player.openInventory(inventory);
          }
    private void openSettings(Player player, String dialogueName) {

        Dialogue dialogue = dialogues.get(dialogueName);

        if (dialogue == null) {
            return;
        }

        Gui gui = new Gui(
                dialogueName,
                Screen.SETTINGS,
                null,
                0
        );

        Inventory inventory = Bukkit.createInventory(
                gui,
                54,
                render("&8Загальні налаштування")
        );

        gui.inventory = inventory;

        inventory.setItem(
                10,
                menuItem(
                        Material.PLAYER_HEAD,
                        "&eПриховувати інших гравців",
                        "&7" + boolText(dialogue.cfg().hidePlayers())
                )
        );

        inventory.setItem(
                11,
                menuItem(
                        Material.NAME_TAG,
                        "&eОдноразове проходження",
                        "&7" + boolText(dialogue.cfg().oneTime())
                )
        );

        inventory.setItem(
                12,
                menuItem(
                        Material.GLASS,
                        "&eТекст крізь блоки",
                        "&7" + boolText(dialogue.cfg().seeThrough())
                )
        );

        inventory.setItem(
                13,
                menuItem(
                        Material.ENDER_EYE,
                        "&eГлобальна позиція тексту",
                        "&7" + valueOrGlobal(dialogue.cfg().location())
                )
        );

        inventory.setItem(
                14,
                menuItem(
                        Material.ENDER_PEARL,
                        "&eГлобальна позиція камери",
                        "&7" + valueOrGlobal(dialogue.cfg().camera()),
                        "&7Зберігається з поточної позиції"
                )
        );

        inventory.setItem(
                15,
                menuItem(
                        Material.SPYGLASS,
                        "&eПлавний рух камери",
                        "&7" + boolText(dialogue.cfg().smooth())
                )
        );

        inventory.setItem(
                16,
                menuItem(
                        Material.NOTE_BLOCK,
                        "&eЗвук друку",
                        "&7" + valueOrGlobal(dialogue.cfg().sound())
                )
        );

        inventory.setItem(
                19,
                menuItem(
                        Material.REPEATER,
                        "&eШвидкість друку",
                        "&7" + getConfig().getInt("typing-speed", 2)
                )
        );

        inventory.setItem(
                20,
                menuItem(
                        Material.PAPER,
                        "&eДрукувати по символах",
                        "&7" + boolText(dialogue.cfg().perChar())
                )
        );

        inventory.setItem(
                21,
                menuItem(
                        Material.NOTE_BLOCK,
                        "&eГучність / висота звуку",
                        "&7Pitch: &f" + dialogue.cfg().pitch()
                )
        );

        inventory.setItem(
                22,
                menuItem(
                        Material.GLOWSTONE_DUST,
                        "&eМасштаб тексту",
                        "&7" + dialogue.cfg().textScale()
                )
        );

        inventory.setItem(
                23,
                menuItem(
                        Material.PAINTING,
                        "&eШирина текстового вікна",
                        "&7" + dialogue.cfg().lineWidth()
                )
        );

        inventory.setItem(
                24,
                menuItem(
                        Material.GRAY_STAINED_GLASS_PANE,
                        "&eФон текстового вікна",
                        "&7Alpha: &f" + dialogue.cfg().bgAlpha()
                )
        );

        inventory.setItem(
                25,
                menuItem(
                        Material.POTION,
                        "&eНевидимість",
                        "&7" + boolText(dialogue.cfg().invisible())
                )
        );

        inventory.setItem(
                28,
                menuItem(
                        Material.SOUL_SAND,
                        "&eУповільнення",
                        "&7" + boolText(dialogue.cfg().slowness())
                )
        );

        inventory.setItem(
                29,
                menuItem(
                        Material.RECOVERY_COMPASS,
                        "&eПовернути на початкову позицію",
                        "&7" + boolText(dialogue.cfg().saveStart())
                )
        );

        inventory.setItem(
                30,
                menuItem(
                        Material.MAP,
                        "&eПідказки в actionbar",
                        "&7" + boolText(
                                getConfig().getBoolean(
                                        "show-actionbar-hints",
                                        true
                                )
                        )
                )
        );

        inventory.setItem(
                49,
                menuItem(
                        Material.ARROW,
                        "&eНазад",
                        "&7До списку вузлів"
                )
        );

        player.openInventory(inventory);
    }

    private ItemStack menuItem(
            Material material,
            String name,
            String... lore
    ) {

        ItemStack item = new ItemStack(material);

        ItemMeta meta = item.getItemMeta();

        meta.displayName(render(name));

        if (lore.length > 0) {

            List<Component> lines = new ArrayList<>();

            for (String line : lore) {
                lines.add(render(line));
            }

            meta.lore(lines);
        }

        item.setItemMeta(meta);

        return item;
    }

    private String shortText(String value) {

        if (value == null || value.isBlank()) {
            return "&7Порожньо";
        }

        String clean = value.replace('\n', ' ');

        if (clean.length() > 35) {
            clean = clean.substring(0, 32) + "...";
        }

        return clean;
    }

    private String vectorString(Vector vector) {

        if (vector == null) {
            return "0 0 0";
        }

        return String.format(
                Locale.US,
                "%.2f %.2f %.2f",
                vector.getX(),
                vector.getY(),
                vector.getZ()
        );
    }

    private String valueOrGlobal(String value) {

        if (value == null || value.isBlank()) {
            return "&7Загальне налаштування";
        }

        return value;
    }

    private String boolText(boolean value) {

        return value ? "&aУвімкнено" : "&cВимкнено";
    }

    private void ask(
            Player player,
            String message,
            Consumer<String> action,
            Runnable back
    ) {

        prompts.put(
                player.getUniqueId(),
                new Prompt(
                        player,
                        action,
                        back
                )
        );

        player.closeInventory();

        player.sendMessage(render(
                "&e" + message
        ));

        player.sendMessage(render(
                "&7Введи значення в чат. Напиши &ccancel &7для скасування."
        ));
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {

        Player player = event.getPlayer();

        Prompt prompt = prompts.remove(player.getUniqueId());

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);

        String input = PlainTextComponentSerializer.plainText()
                .serialize(event.message())
                .trim();

        Bukkit.getScheduler().runTask(
                this,
                () -> {

                    if (input.equalsIgnoreCase("cancel")) {
                        prompt.back().run();
                        player.sendMessage(render(
                                "&cЗміни скасовано."
                        ));
                        return;
                    }

                    try {
                        prompt.action().accept(input);

                    } catch (IllegalArgumentException exception) {

                        player.sendMessage(render(
                                "&cНекоректне значення: "
                                        + exception.getMessage()
                        ));
                    }

                    if (player.isOnline()) {
                        prompt.back().run();
                    }
                }
        );
    }

    private void edit(
            Dialogue dialogue,
            Consumer<YamlConfiguration> action
    ) {

        File file = new File(
            dialoguesDir,
            dialogue.name() + ".yml"
        );

        YamlConfiguration yaml =
                YamlConfiguration.loadConfiguration(file);

        action.accept(yaml);

        try {

            yaml.save(file);

            loadFile(file, false);

        } catch (IOException exception) {

            getLogger().severe(
                    "Не вдалося зберегти "
                            + dialogue.name() + ": "
                            + exception.getMessage()
            );
        }
    }

    private ConfigurationSection cfgSec(YamlConfiguration yaml) {

        ConfigurationSection section = yaml.getConfigurationSection("config");

        if (section == null) {
            section = yaml.createSection("config");
        }

        return section;
    }

    private boolean validVector(String value) {

        if (value == null) {
            return false;
        }

        String[] parts = value.trim().split("\\s+");

        if (parts.length != 3) {
            return false;
        }

        for (String part : parts) {

            try {
                Double.parseDouble(part);

            } catch (NumberFormatException exception) {
                return false;
            }
        }

        return true;
    }

    private String requireVector(String value) {

        if (!validVector(value)) {
            throw new IllegalArgumentException(
                    "потрібно вказати три числа через пробіл"
            );
        }

        return value.trim();
    }

    private String requireNumber(String value) {

        try {

            Double.parseDouble(value);

            return value.trim();

        } catch (NumberFormatException exception) {

            throw new IllegalArgumentException(
                    "потрібно вказати число"
            );
        }
    }

    private String requireBoolean(String value) {

        if (value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("false")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("no")
                || value.equalsIgnoreCase("on")
                || value.equalsIgnoreCase("off")) {

            return Boolean.toString(
                    value.equalsIgnoreCase("true")
                            || value.equalsIgnoreCase("yes")
                            || value.equalsIgnoreCase("on")
            );
        }

        throw new IllegalArgumentException(
                "введи true або false"
        );
    }

    private String requireColor(String value) {

        String color = value.trim();

        if (color.matches("#[0-9a-fA-F]{6}")) {
            return color;
        }

        if (color.matches("&[0-9a-fA-F]")) {
            return color;
        }

        if (color.matches("§[0-9a-fA-F]")) {
            return color;
        }

        Set<String> names = Set.of(
                "white",
                "black",
                "red",
                "dark_red",
                "green",
                "dark_green",
                "blue",
                "dark_blue",
                "yellow",
                "gold",
                "aqua",
                "dark_aqua",
                "light_purple",
                "dark_purple",
                "gray",
                "dark_gray"
        );

        if (names.contains(color.toLowerCase(Locale.ROOT))) {
            return color.toLowerCase(Locale.ROOT);
        }

        throw new IllegalArgumentException(
                "невідомий колір"
        );
    }
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!(event.getView().getTopInventory().getHolder()
                instanceof Gui gui)) {
            return;
        }

        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(
                        event.getView().getTopInventory()
                )) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getSlot();

        switch (gui.screen) {

            case NODES -> clickNodes(player, gui, slot);

            case NODE -> clickNode(player, gui, slot);

            case ANSWER -> clickAnswer(player, gui, slot);

            case PICK -> clickPick(player, gui, slot);

            case SETTINGS -> clickSettings(player, gui, slot);
        }
    }

    private void clickNodes(Player player, Gui gui, int slot) {

        Dialogue dialogue = dialogues.get(gui.dialogue);

        if (dialogue == null) {
            player.closeInventory();
            return;
        }

        List<Node> nodes = new ArrayList<>(
                dialogue.nodes().values()
        );

        int start = gui.page * PAGE_SIZE;
        int end = Math.min(
                nodes.size(),
                start + PAGE_SIZE
        );

        if (slot >= 0 && slot < PAGE_SIZE) {

            int index = start + slot;

            if (index >= 0 && index < end) {

                openNode(
                        player,
                        gui.dialogue,
                        nodes.get(index).id()
                );
            }

            return;
        }

        switch (slot) {

            case 45 -> openSettings(
                    player,
                    gui.dialogue
            );

            case 48 -> openNodes(
                    player,
                    gui.dialogue,
                    gui.page - 1
            );

            case 50 -> openNodes(
                    player,
                    gui.dialogue,
                    gui.page + 1
            );

            case 49 -> ask(
                    player,
                    "Введи назву нового вузла:",
                    input -> {

                        String id = input.trim();

                        if (!validName(id)) {
                            throw new IllegalArgumentException(
                                    "назва містить недопустимі символи"
                            );
                        }

                        Dialogue current = dialogues.get(gui.dialogue);

                        if (current == null) {
                            throw new IllegalArgumentException(
                                    "діалог більше не існує"
                            );
                        }

                        if (current.nodes().containsKey(id)) {
                            throw new IllegalArgumentException(
                                    "вузол із такою назвою вже існує"
                            );
                        }

                        edit(
                                current,
                                yaml -> {

                                    ConfigurationSection section =
                                            yaml.getConfigurationSection(
                                                    "dialogue"
                                            );

                                    if (section == null) {
                                        section = yaml.createSection(
                                                "dialogue"
                                        );
                                    }

                                    ConfigurationSection node =
                                            section.createSection(id);

                                    node.set("text", "");
                                    node.set("char-delay", 2);
                                    node.set("location-offset", "0 0 0");
                                    node.createSection("answers");
                                }
                        );

                        openNode(
                                player,
                                gui.dialogue,
                                id
                        );
                    },
                    () -> openNodes(
                            player,
                            gui.dialogue,
                            gui.page
                    )
            );

            case 53 -> {

                Dialogue current = dialogues.get(gui.dialogue);

                if (current != null) {
                    startDialogue(player, current);
                }
            }

            default -> {
            }
        }
    }

    private void clickNode(Player player, Gui gui, int slot) {

        Dialogue dialogue = dialogues.get(gui.dialogue);

        if (dialogue == null) {
            player.closeInventory();
            return;
        }

        Node node = dialogue.nodes().get(gui.node);

        if (node == null) {
            openNodes(player, gui.dialogue);
            return;
        }

        switch (slot) {

            case 10 -> ask(
                    player,
                    "Введи текст вузла:",
                    input -> edit(
                            dialogue,
                            yaml -> yaml.set(
                                    "dialogue." + gui.node + ".text",
                                    input
                            )
                    ),
                    () -> openNode(
                            player,
                            gui.dialogue,
                            gui.node
                    )
            );

            case 11 -> ask(
                    player,
                    "Введи затримку в тиках:",
                    input -> {

                        int delay;

                        try {
                            delay = Integer.parseInt(input.trim());
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException(
                                    "потрібно вказати ціле число"
                            );
                        }

                        if (delay < 0) {
                            throw new IllegalArgumentException(
                                    "затримка не може бути від'ємною"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "dialogue." + gui.node + ".char-delay",
                                        delay
                                )
                        );
                    },
                    () -> openNode(
                            player,
                            gui.dialogue,
                            gui.node
                    )
            );

            case 12 -> ask(
                    player,
                    "Введи масштаб тексту:",
                    input -> {

                        double scale;

                        try {
                            scale = Double.parseDouble(input.trim());
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException(
                                    "потрібно вказати число"
                            );
                        }

                        if (!Double.isFinite(scale)
                                || scale <= 0
                                || scale > 10) {
                            throw new IllegalArgumentException(
                                    "масштаб має бути більшим за 0 і не більшим за 10"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "dialogue." + gui.node + ".text-scale",
                                        scale
                                )
                        );
                    },
                    () -> openNode(
                            player,
                            gui.dialogue,
                            gui.node
                    )
            );

            case 13 -> ask(
                    player,
                    "Введи зміщення X Y Z:",
                    input -> {

                        String vector = requireVector(input);

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "dialogue." + gui.node + ".location-offset",
                                        vector
                                )
                        );
                    },
                    () -> openNode(
                            player,
                            gui.dialogue,
                            gui.node
                    )
            );

            case 14 -> ask(
                    player,
                    "Введи координати тексту або none для загального налаштування:",
                    input -> {

                        String value = input.trim();

                        if (!value.equalsIgnoreCase("none")
                                && parseLoc(value) == null) {
                            throw new IllegalArgumentException(
                                    "не вдалося розпізнати координати"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "dialogue." + gui.node + ".location",
                                        value.equalsIgnoreCase("none")
                                                ? null
                                                : value
                                )
                        );
                    },
                    () -> openNode(
                            player,
                            gui.dialogue,
                            gui.node
                    )
            );

            case 15 -> {

                String location = locStr(
                        player.getLocation(),
                        true
                );

                edit(
                        dialogue,
                        yaml -> yaml.set(
                                "dialogue." + gui.node + ".camera-location",
                                location
                        )
                );

                openNode(
                        player,
                        gui.dialogue,
                        gui.node
                );

                player.sendMessage(render(
                        "&aПозицію камери збережено."
                ));
            }

            case 16 -> openPick(
                    player,
                    gui.dialogue,
                    gui.node,
                    "node",
                    0,
                    0
            );

            case 45 -> openNodes(
                    player,
                    gui.dialogue
            );

            case 53 -> ask(
                    player,
                    "Для видалення вузла введи його назву:",
                    input -> {

                        if (!input.trim().equals(gui.node)) {
                            throw new IllegalArgumentException(
                                    "назва не збігається"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "dialogue." + gui.node,
                                        null
                                )
                        );

                        openNodes(
                                player,
                                gui.dialogue
                        );
                    },
                    () -> openNode(
                            player,
                            gui.dialogue,
                            gui.node
                    )
            );

            default -> {

                if (slot >= 27 && slot <= 35) {

                    int answerIndex = slot - 27;

                    if (answerIndex < node.answers().size()) {

                        openAnswer(
                                player,
                                gui.dialogue,
                                gui.node,
                                answerIndex
                        );

                    } else if (answerIndex == node.answers().size()
                            && node.answers().size() < 9) {

                        addAnswer(
                                player,
                                gui.dialogue,
                                gui.node
                        );
                    }
                }
            }
        }
    }

    private void clickAnswer(Player player, Gui gui, int slot) {

        Dialogue dialogue = dialogues.get(gui.dialogue);

        if (dialogue == null) {
            player.closeInventory();
            return;
        }

        Node node = dialogue.nodes().get(gui.node);

        if (node == null || gui.page < 0
                || gui.page >= node.answers().size()) {
            openNode(player, gui.dialogue, gui.node);
            return;
        }

        int answerIndex = gui.page;

        String path = "dialogue." + gui.node
                + ".answers." + answerIndex;

        switch (slot) {

            case 10 -> ask(
                    player,
                    "Введи текст відповіді:",
                    input -> edit(
                            dialogue,
                            yaml -> yaml.set(
                                    path + ".text",
                                    input
                            )
                    ),
                    () -> openAnswer(
                            player,
                            gui.dialogue,
                            gui.node,
                            answerIndex
                    )
            );

            case 11 -> ask(
                    player,
                    "Введи назву наступного вузла або none:",
                    input -> {

                        String value = input.trim();

                        if (!value.equalsIgnoreCase("none")
                                && !dialogue.nodes().containsKey(value)) {
                            throw new IllegalArgumentException(
                                    "вузол із такою назвою не існує"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        path + ".next",
                                        value.equalsIgnoreCase("none")
                                                ? null
                                                : value
                                )
                        );
                    },
                    () -> openAnswer(
                            player,
                            gui.dialogue,
                            gui.node,
                            answerIndex
                    )
            );

            case 12 -> ask(
                    player,
                    "Введи команди через |. Використовуй {player} для імені гравця:",
                    input -> {

                        List<String> commands = new ArrayList<>();

                        for (String command : input.split("\\|")) {

                            String cleaned = command.trim();

                            if (!cleaned.isEmpty()) {
                                commands.add(cleaned);
                            }
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        path + ".commands",
                                        commands
                                )
                        );
                    },
                    () -> openAnswer(
                            player,
                            gui.dialogue,
                            gui.node,
                            answerIndex
                    )
            );

            case 13 -> openPick(
                    player,
                    gui.dialogue,
                    gui.node,
                    "answer",
                    answerIndex,
                    0
            );

            case 45 -> openNode(
                    player,
                    gui.dialogue,
                    gui.node
            );

            case 53 -> ask(
                    player,
                    "Для видалення відповіді введи DELETE:",
                    input -> {

                        if (!input.trim().equalsIgnoreCase("DELETE")) {
                            throw new IllegalArgumentException(
                                    "підтвердження не збігається"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> {

                                    List<Map<?, ?>> answers =
                                            yaml.getMapList(
                                                    "dialogue." + gui.node + ".answers"
                                            );

                                    if (answerIndex < 0
                                            || answerIndex >= answers.size()) {
                                        return;
                                    }

                                    answers.remove(answerIndex);

                                    yaml.set(
                                            "dialogue." + gui.node + ".answers",
                                            answers
                                    );
                                }
                        );

                        openNode(
                                player,
                                gui.dialogue,
                                gui.node
                        );
                    },
                    () -> openAnswer(
                            player,
                            gui.dialogue,
                            gui.node,
                            answerIndex
                    )
            );

            default -> {
            }
        }
    }

    private void addAnswer(
            Player player,
            String dialogueName,
            String nodeId
    ) {

        Dialogue dialogue = dialogues.get(dialogueName);

        if (dialogue == null) {
            player.closeInventory();
            return;
        }

        Node node = dialogue.nodes().get(nodeId);

        if (node == null) {
            openNodes(player, dialogueName);
            return;
        }

        if (node.answers().size() >= 9) {
            player.sendMessage(render(
                    "&cУ вузлі не може бути більше 9 відповідей."
            ));

            openNode(player, dialogueName, nodeId);
            return;
        }

        int newIndex = node.answers().size();

        edit(
                dialogue,
                yaml -> {

                    List<Map<?, ?>> answers =
                            yaml.getMapList(
                                    "dialogue." + nodeId + ".answers"
                            );

                    Map<String, Object> answer = new LinkedHashMap<>();

                    answer.put("text", "Нова відповідь");
                    answer.put("next", null);
                    answer.put("commands", new ArrayList<String>());

                    answers.add(answer);

                    yaml.set(
                            "dialogue." + nodeId + ".answers",
                            answers
                    );
                }
        );

        openAnswer(
                player,
                dialogueName,
                nodeId,
                newIndex
        );
    }

    private void clickPick(Player player, Gui gui, int slot) {

        Dialogue dialogue = dialogues.get(gui.dialogue);

        if (dialogue == null) {
            player.closeInventory();
            return;
        }

        List<String> nodeIds = new ArrayList<>(
                dialogue.nodes().keySet()
        );

        int start = gui.page * PAGE_SIZE;
        int end = Math.min(
                nodeIds.size(),
                start + PAGE_SIZE
        );

        if (slot >= 0 && slot < PAGE_SIZE) {

            int index = start + slot;

            if (index >= 0 && index < end) {

                String selected = nodeIds.get(index);

                if (gui.targetType == null) {
                    player.closeInventory();
                    return;
                }

                String path;

                if (gui.targetType.equals("node")) {

                    path = "dialogue." + gui.node + ".next";

                } else if (gui.targetType.equals("answer")) {

                    path = "dialogue." + gui.node
                            + ".answers." + gui.answerIndex
                            + ".next";

                } else {
                    player.closeInventory();
                    return;
                }

                edit(
                        dialogue,
                        yaml -> yaml.set(path, selected)
                );

                if (gui.targetType.equals("answer")) {

                    openAnswer(
                            player,
                            gui.dialogue,
                            gui.node,
                            gui.answerIndex
                    );

                } else {

                    openNode(
                            player,
                            gui.dialogue,
                            gui.node
                    );
                }
            }

            return;
        }

        switch (slot) {

            case 45 -> {

                if ("answer".equals(gui.targetType)) {

                    openAnswer(
                            player,
                            gui.dialogue,
                            gui.node,
                            gui.answerIndex
                    );

                } else {

                    openNode(
                            player,
                            gui.dialogue,
                            gui.node
                    );
                }
            }

            case 48 -> openPick(
                    player,
                    gui.dialogue,
                    gui.node,
                    gui.targetType,
                    gui.answerIndex,
                    gui.page - 1
            );

            case 50 -> openPick(
                    player,
                    gui.dialogue,
                    gui.node,
                    gui.targetType,
                    gui.answerIndex,
                    gui.page + 1
            );

            case 53 -> {

                String path;

                if ("answer".equals(gui.targetType)) {

                    path = "dialogue." + gui.node
                            + ".answers." + gui.answerIndex
                            + ".next";

                } else {

                    path = "dialogue." + gui.node + ".next";
                }

                edit(
                        dialogue,
                        yaml -> yaml.set(path, null)
                );

                if ("answer".equals(gui.targetType)) {

                    openAnswer(
                            player,
                            gui.dialogue,
                            gui.node,
                            gui.answerIndex
                    );

                } else {

                    openNode(
                            player,
                            gui.dialogue,
                            gui.node
                    );
                }
            }

            default -> {
            }
        }
    }

    private void clickSettings(Player player, Gui gui, int slot) {

        Dialogue dialogue = dialogues.get(gui.dialogue);

        if (dialogue == null) {
            player.closeInventory();
            return;
        }

        switch (slot) {

            case 10 -> ask(
                    player,
                    "Введи масштаб тексту за замовчуванням:",
                    input -> {

                        double scale;

                        try {
                            scale = Double.parseDouble(input.trim());
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException(
                                    "потрібно вказати число"
                            );
                        }

                        if (!Double.isFinite(scale)
                                || scale <= 0
                                || scale > 10) {
                            throw new IllegalArgumentException(
                                    "масштаб має бути більшим за 0 і не більшим за 10"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set("settings.scale", scale)
                        );
                    },
                    () -> openSettings(player, gui.dialogue)
            );

            case 11 -> ask(
                    player,
                    "Введи координати тексту або none:",
                    input -> {

                        String value = input.trim();

                        if (!value.equalsIgnoreCase("none")
                                && parseLoc(value) == null) {
                            throw new IllegalArgumentException(
                                    "не вдалося розпізнати координати"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "settings.location",
                                        value.equalsIgnoreCase("none")
                                                ? null
                                                : value
                                )
                        );
                    },
                    () -> openSettings(player, gui.dialogue)
            );

            case 12 -> {

                String location = locStr(
                        player.getLocation(),
                        true
                );

                edit(
                        dialogue,
                        yaml -> yaml.set(
                                "settings.camera-location",
                                location
                        )
                );

                openSettings(player, gui.dialogue);

                player.sendMessage(render(
                        "&aГлобальну позицію камери збережено."
                ));
            }

            case 13 -> ask(
                    player,
                    "Введи швидкість друку в мілісекундах:",
                    input -> {

                        long speed;

                        try {
                            speed = Long.parseLong(input.trim());
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException(
                                    "потрібно вказати ціле число"
                            );
                        }

                        if (speed < 0 || speed > 10000) {
                            throw new IllegalArgumentException(
                                    "значення має бути від 0 до 10000"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "settings.typing-speed",
                                        speed
                                )
                        );
                    },
                    () -> openSettings(player, gui.dialogue)
            );

            case 14 -> {

                boolean current = dialogue.cfg().smooth();
                edit(
                        dialogue,
                        yaml -> yaml.set(
                                "settings.smooth-camera",
                                !current
                        )
                );

                openSettings(player, gui.dialogue);
            }

            case 15 -> {

                boolean current = dialogue.yaml().getBoolean(
                        "settings.typing-sound",
                        false
                );

                edit(
                        dialogue,
                        yaml -> yaml.set(
                                "settings.typing-sound",
                                !current
                        )
                );

                openSettings(player, gui.dialogue);
            }

            case 16 -> ask(
                    player,
                    "Введи гучність звуку від 0 до 2:",
                    input -> {

                        double volume;

                        try {
                            volume = Double.parseDouble(input.trim());
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException(
                                    "потрібно вказати число"
                            );
                        }

                        if (!Double.isFinite(volume)
                                || volume < 0
                                || volume > 2) {
                            throw new IllegalArgumentException(
                                    "гучність має бути від 0 до 2"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "settings.sound-volume",
                                        volume
                                )
                        );
                    },
                    () -> openSettings(player, gui.dialogue)
            );

            case 19 -> ask(
                    player,
                    "Введи ширину текстового вікна:",
                    input -> {

                        int width;

                        try {
                            width = Integer.parseInt(input.trim());
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException(
                                    "потрібно вказати ціле число"
                            );
                        }

                        if (width < 1 || width > 300) {
                            throw new IllegalArgumentException(
                                    "ширина має бути від 1 до 300"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "settings.window-width",
                                        width
                                )
                        );
                    },
                    () -> openSettings(player, gui.dialogue)
            );

            case 20 -> ask(
                    player,
                    "Введи колір фону (наприклад #000000 або none):",
                    input -> {

                        String value = input.trim();

                        if (!value.equalsIgnoreCase("none")
                                && !value.matches(
                                        "(?i)#[0-9a-f]{6}([0-9a-f]{2})?"
                                )) {
                            throw new IllegalArgumentException(
                                    "використовуй HEX-колір або none"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "settings.background-color",
                                        value.equalsIgnoreCase("none")
                                                ? null
                                                : value
                                )
                        );
                    },
                    () -> openSettings(player, gui.dialogue)
            );

            case 21 -> ask(
                    player,
                    "Введи прозорість фону від 0 до 255:",
                    input -> {

                        int alpha;

                        try {
                            alpha = Integer.parseInt(input.trim());
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException(
                                    "потрібно вказати ціле число"
                            );
                        }

                        if (alpha < 0 || alpha > 255) {
                            throw new IllegalArgumentException(
                                    "прозорість має бути від 0 до 255"
                            );
                        }

                        edit(
                                dialogue,
                                yaml -> yaml.set(
                                        "settings.background-alpha",
                                        alpha
                                )
                        );
                    },
                    () -> openSettings(player, gui.dialogue)
            );

            case 22 -> {

                boolean current = dialogue.cfg().seeThrough();
                edit(
                        dialogue,
                        yaml -> yaml.set(
                                "settings.see-through-blocks",
                                !current
                        )
                );

                openSettings(player, gui.dialogue);
            }

            case 23 -> {

                boolean current = dialogue.cfg().oneTime();
                edit(
                        dialogue,
                        yaml -> yaml.set(
                                "settings.one-time",
                                !current
                        )
                );

                openSettings(player, gui.dialogue);
            }

            case 45 -> openNodes(
                    player,
                    gui.dialogue
            );

            default -> {
            }
        }
    }



