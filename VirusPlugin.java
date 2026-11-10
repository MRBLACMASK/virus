package com.virus;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.time.Duration;
import java.util.*;

public class VirusPlugin extends JavaPlugin implements Listener, TabExecutor {

    enum State { IDLE, PREP, RUNNING, BETWEEN }

    // ================= AYARLAR (istersen buradan degistir) =================
    private static final int AREA_SIZE = 300;        // alan boyutu (300 = 300x300)
    private static final int TOTAL_ROUNDS = 5;       // toplam tur sayisi
    private static final int PREP_SECONDS = 60;      // her tur basinda hazirlik suresi (saniye)
    private static final int STRENGTH_LEVEL = 0;     // 0 = Kuvvet I, 1 = Kuvvet II
    private static final int SPREAD_CANDIDATES = 20; // uzak yer secerken denenen nokta sayisi
    // =======================================================================

    private State state = State.IDLE;
    private final Set<UUID> hunters = new HashSet<>();
    private final Set<UUID> runners = new HashSet<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private UUID lastConverted;
    private int round = 0;
    private int prepLeft = 0;

    private World world;
    private int centerX, centerZ;
    private Location oldCenter;
    private double oldSize;

    private Scoreboard board;
    private Objective obj;

    private BukkitTask prepTask, reapplyTask, fireworkTask, nextTask;
    private final Random rnd = new Random();
    private NamespacedKey swordKey;

    @Override
    public void onEnable() {
        swordKey = new NamespacedKey(this, "virus_sword");
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("virus");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (state != State.IDLE) stopGame();
    }

    // ---------------- KOMUT ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Kullanım: /virus <baslat|durdur>", NamedTextColor.YELLOW));
            return true;
        }
        if (args[0].equalsIgnoreCase("baslat")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Bu komutu oyun içinden kullan.");
                return true;
            }
            if (state != State.IDLE) {
                sender.sendMessage(Component.text("Zaten bir oyun var. /virus durdur", NamedTextColor.RED));
                return true;
            }
            if (Bukkit.getOnlinePlayers().size() < 2) {
                sender.sendMessage(Component.text("En az 2 oyuncu lazım.", NamedTextColor.RED));
                return true;
            }
            startGame(p);
            return true;
        }
        if (args[0].equalsIgnoreCase("durdur")) {
            if (state == State.IDLE) {
                sender.sendMessage(Component.text("Aktif oyun yok.", NamedTextColor.RED));
                return true;
            }
            stopGame();
            sender.sendMessage(Component.text("Oyun durduruldu.", NamedTextColor.GREEN));
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("baslat", "durdur");
        return List.of();
    }

    // ---------------- OYUN AKISI ----------------

    private void startGame(Player starter) {
        world = starter.getWorld();
        centerX = starter.getLocation().getBlockX();
        centerZ = starter.getLocation().getBlockZ();

        WorldBorder wb = world.getWorldBorder();
        oldCenter = wb.getCenter();
        oldSize = wb.getSize();
        wb.setCenter(centerX + 0.5, centerZ + 0.5);
        wb.setSize(AREA_SIZE);
        wb.setWarningDistance(0);

        scores.clear();
        names.clear();
        round = 0;
        setupBoard();
        startRound();
    }

    private void setupBoard() {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        obj = board.registerNewObjective("virus", Criteria.DUMMY,
                Component.text("SKOR", NamedTextColor.GOLD, TextDecoration.BOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    private void updateBoardTitle() {
        if (obj == null) return;
        obj.displayName(Component.text("SKOR - Tur " + round + "/" + TOTAL_ROUNDS,
                NamedTextColor.GOLD, TextDecoration.BOLD));
    }

    private void registerOnBoard(Player p) {
        UUID id = p.getUniqueId();
        scores.putIfAbsent(id, 0);
        names.put(id, p.getName());
        if (obj != null) obj.getScore(p.getName()).setScore(scores.get(id));
        if (board != null) p.setScoreboard(board);
    }

    private void startRound() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.size() < 2) {
            for (Player p : players) {
                p.sendMessage(Component.text("Yeterli oyuncu kalmadı, oyun bitti.", NamedTextColor.RED));
            }
            stopGame();
            return;
        }

        round++;
        state = State.PREP;
        hunters.clear();
        runners.clear();
        lastConverted = null;
        updateBoardTitle();

        List<Location> spots = spreadLocations(players.size());
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            removeVirusSwords(p);
            resetPlayer(p);
            p.teleport(spots.get(i));
            runners.add(p.getUniqueId());
            registerOnBoard(p);

            p.showTitle(Title.title(
                    Component.text("TUR " + round + "/" + TOTAL_ROUNDS, NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Hazırlan! " + PREP_SECONDS + " saniye içinde eşya topla, kasıl.", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofMillis(600))));
        }

        prepLeft = PREP_SECONDS;
        prepTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (state != State.PREP) return;
            if (prepLeft <= 0) {
                prepTask.cancel();
                assignRoles();
                return;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendActionBar(Component.text("Tur " + round + "/" + TOTAL_ROUNDS
                        + " - roller " + prepLeft + " saniye sonra verilecek", NamedTextColor.YELLOW));
                if (prepLeft <= 5) {
                    p.showTitle(Title.title(
                            Component.text(String.valueOf(prepLeft), NamedTextColor.RED, TextDecoration.BOLD),
                            Component.empty(),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ZERO)));
                }
            }
            prepLeft--;
        }, 0L, 20L);
    }

    private List<Location> spreadLocations(int count) {
        List<Location> placed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location best = null;
            double bestDist = -1;
            for (int k = 0; k < SPREAD_CANDIDATES; k++) {
                Location c = randomSafe();
                double d = Double.MAX_VALUE;
                for (Location o : placed) d = Math.min(d, dist2(c, o));
                if (best == null || d > bestDist) {
                    best = c;
                    bestDist = d;
                }
            }
            placed.add(best);
        }
        return placed;
    }

    private double dist2(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private Location randomSafe() {
        Location loc = null;
        int half = AREA_SIZE / 2 - 5;
        for (int i = 0; i < 10; i++) {
            int x = centerX + rnd.nextInt(half * 2 + 1) - half;
            int z = centerZ + rnd.nextInt(half * 2 + 1) - half;
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Material top = world.getBlockAt(x, y, z).getType();
            loc = new Location(world, x + 0.5, y + 1, z + 0.5);
            if (top != Material.WATER && top != Material.LAVA) break;
        }
        return loc;
    }

    private void resetPlayer(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect e : new ArrayList<>(p.getActiveEffects())) p.removePotionEffect(e.getType());
    }

    private void assignRoles() {
        if (state != State.PREP) return;
        runners.removeIf(id -> Bukkit.getPlayer(id) == null);
        if (runners.size() < 2) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(Component.text("Yeterli oyuncu kalmadı, oyun bitti.", NamedTextColor.RED));
            }
            stopGame();
            return;
        }
        List<UUID> list = new ArrayList<>(runners);
        UUID hunterId = list.get(rnd.nextInt(list.size()));
        state = State.RUNNING;

        for (UUID id : list) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (id.equals(hunterId)) {
                makeHunter(p, true);
            } else {
                p.showTitle(Title.title(
                        Component.text("KAÇAN", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("Avcıdan kaç! Seni yakalarsa o da avcı olursun.", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(7), Duration.ofSeconds(1))));
                p.sendMessage(Component.text(
                        "Sen KAÇANSIN. Aranızda gizli bir avcı var, kim olduğunu bilmiyorsun. "
                                + "Yakalanırsan avcılara katılırsın. Turun sonuna kalan puan alır. "
                                + "Konuşmak için sadece /msg kullanabilirsin.", NamedTextColor.GREEN));
            }
        }

        reapplyTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID id : hunters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                if (!p.hasPotionEffect(PotionEffectType.RESISTANCE)
                        || !p.hasPotionEffect(PotionEffectType.STRENGTH)) {
                    giveEffects(p);
                }
            }
        }, 40L, 40L);
    }

    private void giveEffects(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                PotionEffect.INFINITE_DURATION, 4, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                PotionEffect.INFINITE_DURATION, STRENGTH_LEVEL, false, false, true));
    }

    private void makeHunter(Player p, boolean first) {
        UUID id = p.getUniqueId();
        runners.remove(id);
        hunters.add(id);
        lastConverted = id;

        if (!hasVirusSword(p)) {
            p.getInventory().addItem(makeSword());
        }
        giveEffects(p);

        if (first) {
            p.showTitle(Title.title(
                    Component.text("AVCISIN", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Component.text("Ölümsüzsün! Herkesi öldür, öldürdüklerin sana katılır.", NamedTextColor.RED),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(1))));
            p.sendMessage(Component.text(
                    "Sen AVCISIN. Demir kılıcın, sonsuz direncin (ölümsüzlük) ve sonsuz kuvvetin var. "
                            + "Tüm kaçanları öldür. Öldürdüğün kişi gizlice senin takımına katılır. "
                            + "Kimse bilmemeli!", NamedTextColor.RED));
        } else {
            p.showTitle(Title.title(
                    Component.text("ARTIK AVCISIN", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Component.text("Avcı takımına katıldın. Kalanları avla, kimse bilmemeli!", NamedTextColor.RED),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(6), Duration.ofSeconds(1))));
            p.sendMessage(Component.text(
                    "Yakalandın ve AVCI oldun. Demir kılıcın, ölümsüzlüğün ve kuvvetin verildi. "
                            + "Kalan kaçanları avla!", NamedTextColor.RED));
        }
    }

    private void checkWin() {
        if (state != State.RUNNING) return;
        if (runners.isEmpty()) endRound(lastConverted);
    }

    private void endRound(UUID winnerId) {
        if (state != State.RUNNING) return;
        state = State.BETWEEN;
        if (reapplyTask != null) reapplyTask.cancel();

        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);
        String winnerName = null;
        if (winnerId != null) {
            scores.merge(winnerId, 1, Integer::sum);
            winnerName = names.getOrDefault(winnerId, winner != null ? winner.getName() : "?");
            if (obj != null) obj.getScore(winnerName).setScore(scores.get(winnerId));
        }

        Component sub = winnerName != null
                ? Component.text("Son kalan: " + winnerName + "  (+1 puan)", NamedTextColor.YELLOW)
                : Component.text("Bu turda puan yok.", NamedTextColor.GRAY);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(
                    Component.text("TUR " + round + " BİTTİ", NamedTextColor.GOLD, TextDecoration.BOLD),
                    sub,
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofSeconds(1))));
        }
        if (winner != null) celebrate(List.of(winner), 100);

        if (round >= TOTAL_ROUNDS) {
            nextTask = Bukkit.getScheduler().runTaskLater(this, this::finishGame, 160L);
        } else {
            nextTask = Bukkit.getScheduler().runTaskLater(this, this::startRound, 160L);
        }
    }

    private void finishGame() {
        state = State.BETWEEN;
        int max = 0;
        for (int v : scores.values()) max = Math.max(max, v);

        List<Player> winners = new ArrayList<>();
        List<String> winnerNames = new ArrayList<>();
        if (max > 0) {
            for (Map.Entry<UUID, Integer> e : scores.entrySet()) {
                if (e.getValue() == max) {
                    winnerNames.add(names.getOrDefault(e.getKey(), "?"));
                    Player p = Bukkit.getPlayer(e.getKey());
                    if (p != null) winners.add(p);
                }
            }
        }

        String text = winnerNames.isEmpty() ? "Kazanan yok" : String.join(", ", winnerNames);
        String head = winnerNames.size() > 1 ? "BERABERLİK" : "KAZANAN";
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(
                    Component.text(head, NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text(text + "  (" + max + " tur)", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(1))));
        }
        celebrate(winners, 300);
        nextTask = Bukkit.getScheduler().runTaskLater(this, this::stopGame, 340L);
    }

    private void celebrate(List<Player> winners, int ticks) {
        if (fireworkTask != null) fireworkTask.cancel();
        final int[] t = {0};
        fireworkTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            t[0] += 8;
            if (t[0] > ticks) {
                fireworkTask.cancel();
                return;
            }
            for (Player w : winners) {
                if (!w.isOnline()) continue;
                Location base = w.getLocation();
                for (int i = 0; i < 2; i++) {
                    spawnFirework(base.clone().add(rnd.nextInt(9) - 4, 0, rnd.nextInt(9) - 4));
                }
            }
        }, 0L, 8L);
    }

    private void spawnFirework(Location loc) {
        loc.getWorld().spawn(loc, Firework.class, fw -> {
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.fromRGB(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
                    .withFade(Color.fromRGB(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
                    .flicker(true).trail(true).build());
            meta.setPower(1);
            fw.setFireworkMeta(meta);
        });
    }

    private void stopGame() {
        for (BukkitTask t : new BukkitTask[]{prepTask, reapplyTask, fireworkTask, nextTask}) {
            if (t != null) t.cancel();
        }
        prepTask = reapplyTask = fireworkTask = nextTask = null;

        if (world != null && oldCenter != null) {
            WorldBorder wb = world.getWorldBorder();
            wb.setCenter(oldCenter);
            wb.setSize(oldSize);
        }
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (hunters.contains(p.getUniqueId())) {
                p.removePotionEffect(PotionEffectType.RESISTANCE);
                p.removePotionEffect(PotionEffectType.STRENGTH);
            }
            removeVirusSwords(p);
            p.setScoreboard(main);
        }
        if (obj != null) {
            try {
                obj.unregister();
            } catch (Exception ignored) {
            }
        }
        obj = null;
        board = null;
        hunters.clear();
        runners.clear();
        scores.clear();
        names.clear();
        lastConverted = null;
        round = 0;
        state = State.IDLE;
    }

    // ---------------- KILIC ISARETLEME ----------------

    private ItemStack makeSword() {
        ItemStack s = new ItemStack(Material.IRON_SWORD);
        ItemMeta m = s.getItemMeta();
        m.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    private boolean isVirusSword(ItemStack it) {
        if (it == null || it.getType() != Material.IRON_SWORD || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    private boolean hasVirusSword(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isVirusSword(it)) return true;
        }
        return false;
    }

    private void removeVirusSwords(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isVirusSword(contents[i])) p.getInventory().setItem(i, null);
        }
    }

    // ---------------- OLAYLAR ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (state == State.IDLE) return;
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        if (!hunters.contains(id) && !runners.contains(id)) return;

        if (state != State.RUNNING) {
            e.setCancelled(true);
            return;
        }
        if (runners.contains(id) && p.getHealth() - e.getFinalDamage() <= 0) {
            // ölüm yok: avcıya dönüşür, öldüğü yerden devam eder
            e.setCancelled(true);
            p.setHealth(20.0);
            p.setFireTicks(0);
            p.setFallDistance(0f);
            makeHunter(p, false);
            checkWin();
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (state == State.IDLE) return;
        if (e.getEntity() instanceof Player) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (state == State.IDLE) return;
        if (isVirusSword(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (state != State.IDLE) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (state != State.IDLE) e.deathMessage(null);
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (state != State.IDLE) e.message(null);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (state == State.IDLE) return;
        e.joinMessage(null);
        Player p = e.getPlayer();
        registerOnBoard(p);
        if (state == State.PREP) {
            resetPlayer(p);
            p.teleport(randomSafe());
            runners.add(p.getUniqueId());
        } else {
            p.setGameMode(GameMode.SPECTATOR);
            p.sendMessage(Component.text("Tur devam ediyor, bir sonraki turda oyuna katılacaksın.",
                    NamedTextColor.GRAY));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (state == State.IDLE) return;
        e.quitMessage(null);
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boolean wasHunter = hunters.remove(id);
        runners.remove(id);
        if (lastConverted != null && lastConverted.equals(id)) lastConverted = null;

        if (wasHunter) {
            p.removePotionEffect(PotionEffectType.RESISTANCE);
            p.removePotionEffect(PotionEffectType.STRENGTH);
            removeVirusSwords(p);
        }
        if (state != State.RUNNING) return;

        if (runners.isEmpty() && hunters.isEmpty()) {
            stopGame();
            return;
        }
        if (hunters.isEmpty()) {
            List<UUID> list = new ArrayList<>(runners);
            Player np = Bukkit.getPlayer(list.get(rnd.nextInt(list.size())));
            if (np != null) makeHunter(np, true);
        }
        if (runners.isEmpty()) endRound(null);
    }
}
