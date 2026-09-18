package com.khangsmp.discordbridge;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class DiscordBridgePlugin extends JavaPlugin implements Listener, CommandExecutor {

    // Config options
    private String apiUrl;
    private String postUrl;
    private String apiKey;
    private int pollInterval;
    private String chatFormat;
    private int maxMessageLength;
    private boolean sendMcToDiscord;

    private BukkitTask pollTask;
    private ExecutorService sendExecutor;

    // Pattern to filter emojis and unwanted Unicode symbols
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
        "[\\x{1F600}-\\x{1F64F}" +  // Emoticons
        "\\x{1F300}-\\x{1F5FF}" +   // Symbols & Pictographs
        "\\x{1F680}-\\x{1F6FF}" +   // Transport & Map
        "\\x{1F1E0}-\\x{1F1FF}" +   // Flags
        "\\x{2600}-\\x{26FF}" +     // Misc symbols
        "\\x{2700}-\\x{27BF}" +     // Dingbats
        "\\x{FE00}-\\x{FE0F}" +     // Variation Selectors
        "\\x{1F900}-\\x{1F9FF}" +   // Supplemental Symbols
        "\\x{1FA00}-\\x{1FA6F}" +   // Chess Symbols
        "\\x{1FA70}-\\x{1FAFF}" +   // Symbols Extended-A
        "\\x{200D}" +                  // Zero Width Joiner
        "\\x{20E3}" +                  // Combining Enclosing Keycap
        "\\x{FE0F}" +                  // Variation Selector-16
        "]+",
        Pattern.UNICODE_CHARACTER_CLASS
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        // Executor cho HTTP POST gửi tin từ MC lên Discord (không block main thread)
        sendExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DiscordBridge-Sender");
            t.setDaemon(true);
            return t;
        });

        // Đăng ký sự kiện chat người chơi
        getServer().getPluginManager().registerEvents(this, this);

        // Đăng ký lệnh reload
        if (getCommand("discordbridge") != null) {
            getCommand("discordbridge").setExecutor(this);
        }

        getLogger().info("========================================");
        getLogger().info(" DiscordBridge v2.0 (Two-Way Bridge)");
        getLogger().info(" Discord -> MC : " + apiUrl + " (" + pollInterval + "s)");
        getLogger().info(" MC -> Discord : " + (sendMcToDiscord ? postUrl : "Tắt"));
        getLogger().info("========================================");

        startPollingTask();
    }

    private void loadConfigValues() {
        reloadConfig();
        apiUrl = getConfig().getString("api.poll-url", 
            getConfig().getString("api.url", "https://bot-production-53d8.up.railway.app/api/discord-messages/latest"));
        
        postUrl = getConfig().getString("api.post-url", 
            "https://bot-production-53d8.up.railway.app/api/minecraft-messages");

        apiKey = getConfig().getString("api.key", "khangsmp_mcbridge_key_2026");
        pollInterval = Math.max(1, getConfig().getInt("poll-interval", 1));
        chatFormat = getConfig().getString("chat-format", "&bDiscord &7| &f{name}&7: &f{message}");
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

            JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
            JsonArray messages = json.getAsJsonArray("messages");

            if (messages == null || messages.size() == 0) {
                return;
            }

            for (JsonElement elem : messages) {
                JsonObject msg = elem.getAsJsonObject();
                String author = msg.has("author") ? msg.get("author").getAsString() : "Unknown";
                String content = msg.has("content") ? msg.get("content").getAsString() : "";

                // Lọc bỏ tin nhắn rác hoặc chỉ có icon
                content = filterMessage(content);
                if (content.isEmpty()) {
                    continue;
                }

                if (content.length() > maxMessageLength) {
                    content = content.substring(0, maxMessageLength) + "...";
                }

                // Format và broadcast vào game
                String formatted = ChatColor.translateAlternateColorCodes('&',
                    chatFormat.replace("{name}", author).replace("{message}", content));

                Bukkit.getScheduler().runTask(this, () -> {
                    Bukkit.broadcastMessage(formatted);
                });
            }

        } catch (Exception ignored) {
            // Không spam console khi bot đang restart/deploy
        }
    }

    // ================= CHIỀU 2: MINECRAFT -> DISCORD =================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!sendMcToDiscord) return;

        String rawMsg = event.getMessage();
        if (rawMsg == null || rawMsg.trim().isEmpty() || rawMsg.startsWith("/")) {
            return; // Bỏ qua lệnh
        }

        String playerName = event.getPlayer().getName();
        String cleanMsg = ChatColor.stripColor(rawMsg).trim();

        if (cleanMsg.isEmpty()) return;

        // Đẩy sang thread riêng để gửi HTTP POST, không ảnh hưởng chat ingame
        if (sendExecutor != null && !sendExecutor.isShutdown()) {
            sendExecutor.submit(() -> sendChatToDiscord(playerName, cleanMsg));
        }
    }

    private void sendChatToDiscord(String player, String message) {
        try {
            URL url = new URL(postUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);

            JsonObject payload = new JsonObject();
            payload.addProperty("player", player);
            payload.addProperty("message", message);

            byte[] out = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }

            int code = conn.getResponseCode();
            conn.disconnect();

            if (code != 200 && code != 204) {
                getLogger().fine("POST to Discord returned code " + code);
            }
        } catch (Exception ignored) {
            // Bỏ qua lỗi mạng nhất thời
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
            startPollingTask();
            sender.sendMessage(ChatColor.GREEN + "[DiscordBridge] Đã tải lại cấu hình thành công!");
            return true;
        }
        sender.sendMessage(ChatColor.AQUA + "[DiscordBridge] v2.0 - Dùng /" + label + " reload để tải lại.");
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
