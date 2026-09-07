package com.example.firestreak;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FireStreak extends JavaPlugin implements Listener {

    private File dataFile;
    private FileConfiguration dataConfig;

    private record StreakData(int streak, LocalDate lastLogin) {}
    private final Map<UUID, StreakData> playerData = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("FireStreak успешно запущен!");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var uuid = player.getUniqueId();
        var zoneId = getZoneId();
        var today = LocalDate.now(zoneId);

        // Получаем текущие данные или создаем пустые, если игрок зашел впервые
        var data = playerData.getOrDefault(uuid, new StreakData(0, null));
        int currentStreak = data.streak();
        var lastLoginDate = data.lastLogin();

        boolean streakIncreased = false;

        if (lastLoginDate == null) {
            currentStreak = 1;
            streakIncreased = true;
        } else if (!lastLoginDate.isEqual(today)) { // Если сегодня еще не заходил
            if (lastLoginDate.isEqual(today.minusDays(1))) {
                currentStreak++;
            } else {
                currentStreak = 1; // Стрик сброшен
            }
            streakIncreased = true;
        }

        playerData.put(uuid, new StreakData(currentStreak, today));

        if (streakIncreased) {
            int interval = getConfig().getInt("rewards.weekly-interval", 7);

            if (currentStreak > 1 && currentStreak % interval == 0) {
                giveWeeklyReward(player, currentStreak);
            } else {
                String soundName = getConfig().getString("sounds.daily-login", "entity.player.levelup");
                playSoundSafe(player, soundName);
            }
        }

        sendActionBarTimer(player, currentStreak);
    }

    private ZoneId getZoneId() {
        try {
            return ZoneId.of(getConfig().getString("settings.timezone", "Europe/Moscow"));
        } catch (Exception e) {
            getLogger().warning("Неверный часовой пояс в конфиге! Использую UTC.");
            return ZoneId.of("UTC");
        }
    }

    private void giveWeeklyReward(Player player, int streak) {
        String soundName = getConfig().getString("sounds.weekly-reward", "ui.toast.challenge_complete");
        playSoundSafe(player, soundName);

        String title = getConfig().getString("messages.weekly-title", "&6🔥 СТРИК!");
        String sub = getConfig().getString("messages.weekly-subtitle", "&eНаграда выдана!");

        player.sendMessage("");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', title.replace("{days}", String.valueOf(streak))));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', sub));
        player.sendMessage("");

        for (String itemStr : getConfig().getStringList("rewards.items")) {
            try {
                String[] parts = itemStr.split(":");
                var mat = Material.matchMaterial(parts[0]);
                int amount = Integer.parseInt(parts[1]);

                if (mat != null) {
                    var stack = new ItemStack(mat, amount);
                    var left = player.getInventory().addItem(stack);
                    left.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
                } else {
                    getLogger().warning("Неизвестный материал в конфиге: " + parts[0]);
                }
            } catch (Exception e) {
                getLogger().warning("Ошибка выдачи предмета: " + itemStr);
            }
        }

        spawnFirework(player);
    }

    private void playSoundSafe(Player p, String soundName) {
        if (soundName == null || soundName.isBlank()) return;

        // 1. Попытка получить звук через Bukkit Enum (например, ENTITY_PLAYER_LEVELUP)
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
            return;
        } catch (IllegalArgumentException ignored) {}

        // 2. Попытка найти через Registry.SOUNDS (например, entity.player.levelup или minecraft:entity.player.levelup)
        try {
            NamespacedKey key = soundName.contains(":")
                    ? NamespacedKey.fromString(soundName.toLowerCase())
                    : NamespacedKey.minecraft(soundName.toLowerCase());
            if (key != null) {
                Sound sound = Registry.SOUNDS.get(key);
                if (sound != null) {
                    p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
                    return;
                }
            }
        } catch (Exception ignored) {}

        // 3. Автоматическая замена подчеркиваний на точки для реестра (например, entity_player_levelup -> entity.player.levelup)
        try {
            String dotted = soundName.toLowerCase().replace('_', '.');
            NamespacedKey key = dotted.contains(":")
                    ? NamespacedKey.fromString(dotted)
                    : NamespacedKey.minecraft(dotted);
            if (key != null) {
                Sound sound = Registry.SOUNDS.get(key);
                if (sound != null) {
                    p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
                    return;
                }
            }
        } catch (Exception ignored) {}

        // 4. Прямой вызов по строковому ключу (включая кастомные звуки ресурспака)
        try {
            p.playSound(p.getLocation(), soundName.toLowerCase(), 1.0f, 1.0f);
            return;
        } catch (Exception ignored) {}

        getLogger().warning("Ошибка: Звук '" + soundName + "' не найден!");
    }

    private void spawnFirework(Player player) {
        var fw = player.getWorld().spawn(player.getLocation(), Firework.class);
        var meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.ORANGE, Color.RED)
                .withFade(Color.YELLOW)
                .with(FireworkEffect.Type.BALL_LARGE)
                .withTrail()
                .build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
    }

    private void sendActionBarTimer(Player player, int currentStreak) {
        int duration = getConfig().getInt("settings.action-bar-duration", 5);

        new BukkitRunnable() {
            int secondsLeft = duration;
            @Override
            public void run() {
                if (!player.isOnline() || secondsLeft <= 0) {
                    this.cancel();
                    return;
                }
                sendFireActionBar(player, currentStreak);
                secondsLeft--;
            }
        }.runTaskTimer(this, 10L, 20L);
    }

    private void sendFireActionBar(Player player, int days) {
        String cFire = getConfig().getString("colors.fire", "#FF4500");
        String cNum = getConfig().getString("colors.number", "#FFA500");
        String cText = getConfig().getString("colors.text", "#FFD700");

        try {
            var builder = new ComponentBuilder()
                    .append("🔥 ").color(ChatColor.of(cFire)).bold(true)
                    .append(String.valueOf(days)).color(ChatColor.of(cNum)).bold(true)
                    .append(" дн. подряд").color(ChatColor.of(cText)).bold(false);

            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, builder.create());
        } catch (Exception e) {
            player.sendMessage("Ошибка цвета в конфиге!");
        }
    }

    // === СОХРАНЕНИЕ И ЗАГРУЗКА ДАННЫХ ===
    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        if (dataConfig.contains("players")) {
            for (String key : dataConfig.getConfigurationSection("players").getKeys(false)) {
                try {
                    var uuid = UUID.fromString(key);
                    int streak = dataConfig.getInt("players." + key + ".streak");
                    String dateStr = dataConfig.getString("players." + key + ".lastDate");

                    LocalDate lastDate = dateStr != null ? LocalDate.parse(dateStr) : null;
                    playerData.put(uuid, new StreakData(streak, lastDate));
                } catch (IllegalArgumentException | DateTimeParseException e) {
                    getLogger().warning("Ошибка загрузки данных для игрока: " + key);
                }
            }
        }
    }

    private void saveData() {
        dataConfig.set("players", null); // Очищаем старые данные перед сохранением
        for (var entry : playerData.entrySet()) {
            var uuid = entry.getKey();
            var data = entry.getValue();
            dataConfig.set("players." + uuid + ".streak", data.streak());
            dataConfig.set("players." + uuid + ".lastDate", data.lastLogin() != null ? data.lastLogin().toString() : null);
        }
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }
}
