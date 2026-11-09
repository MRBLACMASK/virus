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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

public class VirusPlugin extends JavaPlugin implements Listener, TabExecutor {

    enum State { IDLE, STARTING, RUNNING, ENDING }

    private State state = State.IDLE;
    private final Set<UUID> hunters = new HashSet<>();
    private final Set<UUID> runners = new HashSet<>();
    private UUID lastConverted;

    private World world;
    private Location oldCenter;
    private double oldSize;
    private BukkitTask reapplyTask, fireworkTask, startTask, endTask;
    private final Random rnd = new Random();

    @Override
    public void onEnable() {
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
            sender.sendMessage(Component.text("Kullanim: /virus <baslat|durdur>", NamedTextColor.YELLOW));
            return true;
        }
        if (args[0].equalsIgnoreCase("baslat")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Bu komutu oyun icinden kullan.");
                return true;
            }
            if (state != State.IDLE) {
                sender.sendMessage(Component.text("Zaten bir oyun var. /virus durdur", NamedTextColor.RED));
                return true;
            }
            if (Bukkit.getOnlinePlayers().size() < 2) {
                sender.sendMessage(Component.text("En az 2 oyuncu lazim.", NamedTextColor.RED));
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

    // ---------------- OYUN ----------------

    private void startGame(Player starter) {
        world = starter.getWorld();
        int cx = starter.getLocation().getBlockX();
        int cz = starter.getLocation().getBlockZ();

        WorldBorder wb = world.getWorldBorder();
        oldCenter = wb.getCenter();
        oldSize = wb.getSize();
        wb.setCenter(cx + 0.5, cz + 0.5);
        wb.setSize(100);
        wb.setWarningDistance(0);

        state = State.STARTING;
        hunters.clear();
        runners.clear();
        lastConverted = null;

        for (Player p : Bukkit.getOnlinePlayers()) {
            resetPlayer(p);
            p.teleport(randomLocation(cx, cz));
            runners.add(p.getUniqueId());
            p.showTitle(Title.title(
                    Component.text("OYUN BASLIYOR", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Roller birazdan verilecek...", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(500))));
        }

        startTask = Bukkit.getScheduler().runTaskLater(this, this::assignRoles, 100L);
    }

    private Location randomLocation(int cx, int cz) {
        Location result = null;
        for (int i = 0; i < 30; i++) {
            int x = cx + rnd.nextInt(97) - 48;
            int z = cz + rnd.nextInt(97) - 48;
            int y = world.getHighestBlockYAt(x, z);
            Material top = world.getBlockAt(x, y, z).getType();
            result = new Location(world, x + 0.5, y + 1, z + 0.5);
            if (top != Material.WATER && top != Material.LAVA) break;
        }
        return result;
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
        if (state != State.STARTING) return;
        runners.removeIf(id -> Bukkit.getPlayer(id) == null);
        if (runners.size() < 2) {
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
                        Component.text("KACAN", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("Avcidan kac! Seni yakalarsa o da avci olursun.", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(7), Duration.ofSeconds(1))));
                p.sendMessage(Component.text(
                        "Sen KACANSIN. Aranizda gizli bir avci var, kim oldugunu bilmiyorsun. "
                                + "Yakalanirsan avcilara katilirsin. Sona kalan kazanir. "
                                + "Konusmak icin sadece /msg kullanabilirsin.", NamedTextColor.GREEN));
            }
        }

        reapplyTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID id : hunters) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && !p.hasPotionEffect(PotionEffectType.RESISTANCE)) giveResistance(p);
            }
        }, 40L, 40L);
    }

    private void giveResistance(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                PotionEffect.INFINITE_DURATION, 4, false, false, true));
    }

    private void makeHunter(Player p, boolean first) {
        UUID id = p.getUniqueId();
        runners.remove(id);
        hunters.add(id);
        lastConverted = id;

        if (!p.getInventory().contains(Material.IRON_SWORD)) {
            p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        }
        giveResistance(p);

        if (first) {
            p.showTitle(Title.title(
                    Component.text("AVCISIN", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Component.text("Olumsuzsun! Herkesi oldur, oldurdukun sana katilir.", NamedTextColor.RED),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(1))));
            p.sendMessage(Component.text(
                    "Sen AVCISIN. Demir kilicin ve sonsuz direncin (olumsuzluk) var. "
                            + "Tum kacanlari oldur. Oldurdukun kisi gizlice senin takimina katilir. "
                            + "Kimse bilmemeli!", NamedTextColor.RED));
        } else {
            p.showTitle(Title.title(
                    Component.text("ARTIK AVCISIN", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Component.text("Avci takimina katildin. Kalanlari avla, kimse bilmemeli!", NamedTextColor.RED),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(6), Duration.ofSeconds(1))));
            p.sendMessage(Component.text(
                    "Yakalandin ve AVCI oldun. Demir kilicin ve olumsuzlugun verildi. "
                            + "Kalan kacanlari avla!", NamedTextColor.RED));
        }
    }

    private void checkWin() {
        if (state != State.RUNNING) return;
        if (runners.isEmpty()) win(lastConverted);
    }

    private void win(UUID winnerId) {
        state = State.ENDING;
        if (reapplyTask != null) reapplyTask.cancel();
        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);
        String name = winner != null ? winner.getName() : "Bilinmiyor";

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(
                    Component.text("KAZANAN: " + name, NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Oyun bitti!", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(1))));
        }

        if (winner != null) {
            fireworkTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (!winner.isOnline()) return;
                Location base = winner.getLocation();
                for (int i = 0; i < 2; i++) {
                    Location l = base.clone().add(rnd.nextInt(9) - 4, 0, rnd.nextInt(9) - 4);
                    spawnFirework(l);
                }
            }, 0L, 8L);
        }

        endTask = Bukkit.getScheduler().runTaskLater(this, this::stopGame, 300L);
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
        for (BukkitTask t : new BukkitTask[]{reapplyTask, fireworkTask, startTask, endTask}) {
            if (t != null) t.cancel();
        }
        reapplyTask = fireworkTask = startTask = endTask = null;

        if (world != null && oldCenter != null) {
            WorldBorder wb = world.getWorldBorder();
            wb.setCenter(oldCenter);
            wb.setSize(oldSize);
        }
        for (UUID id : hunters) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.removePotionEffect(PotionEffectType.RESISTANCE);
        }
        hunters.clear();
        runners.clear();
        lastConverted = null;
        state = State.IDLE;
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
            // olum yok: avciya donusur, oldugu yerden devam eder
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
        if (state != State.IDLE) e.joinMessage(null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (state == State.IDLE) return;
        e.quitMessage(null);
        UUID id = e.getPlayer().getUniqueId();
        boolean wasHunter = hunters.remove(id);
        runners.remove(id);
        if (lastConverted != null && lastConverted.equals(id)) lastConverted = null;
        if (state != State.RUNNING) return;

        if (hunters.isEmpty() && !runners.isEmpty()) {
            List<UUID> list = new ArrayList<>(runners);
            Player np = Bukkit.getPlayer(list.get(rnd.nextInt(list.size())));
            if (np != null) makeHunter(np, true);
        } else if (wasHunter || runners.isEmpty()) {
            checkWin();
        }
        if (runners.isEmpty() && hunters.isEmpty()) stopGame();
    }
}
