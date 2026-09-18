package com.khangsmp.discordbridge;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class DiscordBridgePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private String apiUrl;
    private String postUrl;
    private String webhookUrl;
    private String apiKey;
    private int pollInterval;
    private String chatFormat;
    private int maxMessageLength;
    private boolean sendMcToDiscord;

    private BukkitTask pollTask;
    private ExecutorService sendExecutor;

    private final ConcurrentHashMap<String, String> skinUrlCache = new ConcurrentHashMap<>();

    private static final String DEFAULT_STEVE_HASH = "31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";

    private static final Pattern EMOJI_PATTERN = Pattern.compile(
        "[\\x{1F600}-\\x{1F64F}" +
        "\\x{1F300}-\\x{1F5FF}" +
        "\\x{1F680}-\\x{1F6FF}" +
        "\\x{1F1E0}-\\x{1F1FF}" +
        "\\x{2600}-\\x{26FF}" +
        "\\x{2700}-\\x{27BF}" +
        "\\x{FE00}-\\x{FE0F}" +
        "\\x{1F900}-\\x{1F9FF}" +
        "\\x{1FA00}-\\x{1FA6F}" +
        "\\x{1FA70}-\\x{1FAFF}" +
        "\\x{200D}" +
        "\\x{20E3}" +
        "\\x{FE0F}" +
        "]+",
        Pattern.UNICODE_CHARACTER_CLASS
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        sendExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DiscordBridge-Sender");
            t.setDaemon(true);
            return t;
        });

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("discordbridge") != null) {
            getCommand("discordbridge").setExecutor(this);
        }

        getLogger().info("========================================");
        getLogger().info(" DiscordBridge v2.8 (Direct Geyser In-Memory Skin Extractor)");
        getLogger().info(" Discord -> MC : " + apiUrl + " (" + pollInterval + "s)");
        getLogger().info(" MC -> Discord : " + (sendMcToDiscord ? "Webhook Active" : "Off"));
        getLogger().info("========================================");

        startPollingTask();
    }

    private void loadConfigValues() {
        reloadConfig();
        apiUrl = getConfig().getString("api.poll-url", 
            getConfig().getString("api.url", "https://bot-production-53d8.up.railway.app/api/discord-messages/latest"));
        
        postUrl = getConfig().getString("api.post-url", 
            "https://bot-production-53d8.up.railway.app/api/minecraft-messages");

        webhookUrl = getConfig().getString("api.webhook-url", 
            "https://discord.com/api/webhooks/1550457214580559912/_PuohPUiSxkxgU3yYJFDHvbuUs4Shous3dkAu9IpPtpwYssPDdoht14FJF-bAlNjne3X");

        apiKey = getConfig().getString("api.key", "khangsmp_mcbridge_key_2026");
        pollInterval = Math.max(1, getConfig().getInt("poll-interval", 1));
        chatFormat = getConfig().getString("chat-format", "&9[Discord] &f{name}&7: &f{message}");
        maxMessageLength = getConfig().getInt("max-message-length", 200);
        sendMcToDiscord = getConfig().getBoolean("send-mc-to-discord", true);
    }

    private void startPollingTask() {
        if (pollTask != null && !pollTask.isCancelled()) {
            pollTask.cancel();
        }

        pollTask = new BukkitRunnable() {
            @Override
            public void run() {
                fetchAndBroadcastMessages();
            }
        }.runTaskTimerAsynchronously(this, 20L * pollInterval, 20L * pollInterval);
    }

    // ================= CHIỀU 1: DISCORD -> MINECRAFT =================
    private void fetchAndBroadcastMessages() {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "DiscordBridge/2.8");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return;
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();

            JsonObject json = new JsonParser().parse(sb.toString()).getAsJsonObject();
            JsonArray messages = json.getAsJsonArray("messages");

            if (messages == null || messages.size() == 0) {
                return;
            }

            for (JsonElement elem : messages) {
                JsonObject msg = elem.getAsJsonObject();
                String author = msg.has("author") ? msg.get("author").getAsString() : "Unknown";
                String content = msg.has("content") ? msg.get("content").getAsString() : "";

                content = filterMessage(content);
                if (content.isEmpty()) {
                    continue;
                }

                if (content.length() > maxMessageLength) {
                    content = content.substring(0, maxMessageLength) + "...";
                }

                String formatted = ChatColor.translateAlternateColorCodes('&',
                    chatFormat.replace("{name}", author).replace("{message}", content));

                Bukkit.getScheduler().runTask(this, () -> {
                    Bukkit.broadcastMessage(formatted);
                });
            }

        } catch (Exception ignored) {
        }
    }

    // ================= CHIỀU 2: MINECRAFT -> DISCORD =================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!sendMcToDiscord) return;

        String rawMsg = event.getMessage();
        if (rawMsg == null || rawMsg.trim().isEmpty() || rawMsg.startsWith("/")) {
            return;
        }

        Player p = event.getPlayer();
        String playerName = p.getName();
        String cleanMsg = ChatColor.stripColor(rawMsg).trim();

        if (cleanMsg.isEmpty()) return;

        if (sendExecutor != null && !sendExecutor.isShutdown()) {
            sendExecutor.submit(() -> {
                String skinUrl = resolvePlayerSkinUrl(p);
                getLogger().info("[Skin-Trace] " + playerName + " -> " + skinUrl);
                sendChatToDiscord(playerName, skinUrl, cleanMsg);
            });
        }
    }

    public String resolvePlayerSkinUrl(Player player) {
        String name = player.getName();
        if (skinUrlCache.containsKey(name)) {
            return skinUrlCache.get(name);
        }

        String resolved = fetchSkinDirectly(player);
        if (resolved != null && !resolved.isEmpty()) {
            skinUrlCache.put(name, resolved);
            return resolved;
        }

        String cleanName = name.startsWith("PE_") ? name.substring(3) : name;
        String fallback = "https://mc-heads.net/head/" + cleanName + "/128.png";
        skinUrlCache.put(name, fallback);
        return fallback;
    }

    private String fetchSkinDirectly(Player player) {
        String name = player.getName();

        // 1. Nếu là người chơi Bedrock/PE -> Trích xuất trực tiếp Pixel Skin từ bộ nhớ Geyser
        if (name.startsWith("PE_") || (Bukkit.getPluginManager().isPluginEnabled("floodgate") 
                && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId()))) {
            byte[] headPng = BedrockSkinExtractor.extractHeadPng(player, getLogger());
            if (headPng != null && headPng.length > 0) {
                String catboxUrl = BedrockSkinExtractor.uploadToCatbox(headPng, getLogger());
                if (catboxUrl != null) {
                    return catboxUrl;
                }
            }
        }

        // 2. Paper PlayerProfile Textures (cho Java premium hoặc SkinRestorer nếu có)
        try {
            Object profile = player.getClass().getMethod("getPlayerProfile").invoke(player);
            if (profile != null) {
                Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
                if (textures != null) {
                    URL skin = (URL) textures.getClass().getMethod("getSkin").invoke(textures);
                    if (skin != null) {
                        String s = skin.toString();
                        if (s.contains("/texture/")) {
                            String hash = s.substring(s.lastIndexOf('/') + 1);
                            if (!hash.equals(DEFAULT_STEVE_HASH)) {
                                return "https://mc-heads.net/head/" + hash + "/128.png";
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 3. TLauncher cho Java cracked
        if (!name.startsWith("PE_")) {
            String tlSkin = fetchTLauncherSkin(name);
            if (tlSkin != null) {
                return tlSkin;
            }
        }

        // 4. Fallback theo tên không có PE_
        String cleanName = name.startsWith("PE_") ? name.substring(3) : name;
        return "https://mc-heads.net/head/" + cleanName + "/128.png";
    }

    private String fetchTLauncherSkin(String username) {
        try {
            URL url = new URL("https://auth.tlauncher.org/skin/profile/texture/login/" + username);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JsonObject json = new JsonParser().parse(sb.toString()).getAsJsonObject();
                if (json.has("SKIN")) {
                    String skinUrl = json.getAsJsonObject("SKIN").get("url").getAsString();
                    if (skinUrl != null && !skinUrl.isEmpty()) {
                        return skinUrl;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void sendChatToDiscord(String player, String skinUrl, String message) {
        if (webhookUrl != null && webhookUrl.startsWith("https://discord.com/api/webhooks/")) {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("User-Agent", "DiscordBridge/2.8");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);

                String displayName = player + " [In-Game]";

                JsonObject payload = new JsonObject();
                payload.addProperty("username", displayName);
                payload.addProperty("avatar_url", skinUrl);
                payload.addProperty("content", message);

                byte[] out = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }

                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200 || code == 204) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String filterMessage(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "";
        }
        String filtered = EMOJI_PATTERN.matcher(input).replaceAll("");
        filtered = filtered.replaceAll("[^\\x20-\\x7E\\u00C0-\\u024F\\u1EA0-\\u1EF9]", "");
        filtered = filtered.replaceAll("\\s+", " ").trim();
        return filtered;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("discordbridge.admin")) {
                sender.sendMessage(ChatColor.RED + "Bạn không có quyền dùng lệnh này!");
                return true;
            }
            loadConfigValues();
            skinUrlCache.clear();
            startPollingTask();
            sender.sendMessage(ChatColor.GREEN + "[DiscordBridge] Đã tải lại cấu hình thành công!");
            return true;
        }

        if (args.length > 1 && args[0].equalsIgnoreCase("testskin")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player không online: " + args[1]);
                return true;
            }
            skinUrlCache.remove(target.getName());
            String skin = resolvePlayerSkinUrl(target);
            sender.sendMessage(ChatColor.GREEN + "[TestSkin] " + target.getName() + " -> " + skin);
            return true;
        }

        sender.sendMessage(ChatColor.AQUA + "[DiscordBridge] v2.8 - Dùng /" + label + " reload hoặc /" + label + " testskin <player>");
        return true;
    }

    @Override
    public void onDisable() {
        if (pollTask != null) {
            pollTask.cancel();
        }
        if (sendExecutor != null) {
            sendExecutor.shutdown();
            try {
                if (!sendExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    sendExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                sendExecutor.shutdownNow();
            }
        }
        getLogger().info("DiscordBridge disabled.");
    }
}
