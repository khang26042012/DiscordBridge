package com.khangsmp.discordbridge;

import org.bukkit.entity.Player;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

public class BedrockSkinExtractor {

    private static final String GH_REPO = "khang26042012/khangsmp-player-avatars";
    private static String ghToken = "";

    public static void setGithubToken(String token) {
        if (token != null) {
            ghToken = token.trim();
        }
    }

    public static byte[] extractHeadFromImage(BufferedImage skinImg) {
        if (skinImg == null) return null;
        try {
            int width = skinImg.getWidth();
            int height = skinImg.getHeight();
            int scale = width / 64;
            if (scale < 1) scale = 1;

            BufferedImage face = skinImg.getSubimage(8 * scale, 8 * scale, 8 * scale, 8 * scale);
            BufferedImage hat = skinImg.getSubimage(40 * scale, 8 * scale, 8 * scale, 8 * scale);

            BufferedImage head = new BufferedImage(8 * scale, 8 * scale, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = head.createGraphics();
            g.drawImage(face, 0, 0, null);
            g.drawImage(hat, 0, 0, null);
            g.dispose();

            BufferedImage scaledHead = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaledHead.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(head, 0, 0, 128, 128, null);
            g2.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(scaledHead, "PNG", baos);
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String resolveAndUploadCustomSkin(String skinUrl, String playerName, Logger logger) {
        if (skinUrl == null || skinUrl.isEmpty()) return null;
        if (skinUrl.contains("/texture/")) {
            String hash = skinUrl.substring(skinUrl.lastIndexOf('/') + 1);
            return "https://mc-heads.net/head/" + hash + "/128.png";
        }
        try {
            URL sUrl = new URL(skinUrl);
            HttpURLConnection sConn = (HttpURLConnection) sUrl.openConnection();
            sConn.setRequestProperty("User-Agent", "Mozilla/5.0");
            sConn.setConnectTimeout(3000);
            sConn.setReadTimeout(3000);
            if (sConn.getResponseCode() == 200) {
                BufferedImage skinImg = ImageIO.read(sConn.getInputStream());
                byte[] headPng = extractHeadFromImage(skinImg);
                if (headPng != null && headPng.length > 0) {
                    String cdnUrl = uploadToGitHubCDN(playerName, headPng, logger);
                    if (cdnUrl != null && !cdnUrl.isEmpty()) {
                        return cdnUrl;
                    }
                }
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.warning("[CustomSkin] Error cropping skin from " + skinUrl + ": " + t.getMessage());
            }
        }
        return null;
    }

    public static byte[] extractHeadPng(Player player, Logger logger) {
        try {
            Class<?> geyserImplClass = Class.forName("org.geysermc.geyser.GeyserImpl");
            Method getInstanceMethod = geyserImplClass.getMethod("getInstance");
            Object geyserInstance = getInstanceMethod.invoke(null);
            if (geyserInstance == null) {
                if (logger != null) logger.warning("[BedrockSkin] GeyserImpl instance is null");
                return null;
            }

            Method connMethod = geyserInstance.getClass().getMethod("connectionByUuid", java.util.UUID.class);
            Object session = connMethod.invoke(geyserInstance, player.getUniqueId());
            if (session == null) {
                if (logger != null) logger.info("[BedrockSkin] No GeyserSession for UUID: " + player.getUniqueId());
                return null;
            }

            Field clientDataField = session.getClass().getDeclaredField("clientData");
            clientDataField.setAccessible(true);
            Object clientData = clientDataField.get(session);
            if (clientData == null) {
                if (logger != null) logger.warning("[BedrockSkin] clientData is null for " + player.getName());
                return null;
            }

            Field skinDataField = clientData.getClass().getDeclaredField("skinData");
            skinDataField.setAccessible(true);
            byte[] rawBytes = (byte[]) skinDataField.get(clientData);
            if (rawBytes == null || rawBytes.length < 64 * 32 * 4) {
                if (logger != null) logger.warning("[BedrockSkin] rawBytes null or too small: " + (rawBytes == null ? 0 : rawBytes.length));
                return null;
            }

            Field wField = clientData.getClass().getDeclaredField("skinImageWidth");
            wField.setAccessible(true);
            int width = (int) wField.get(clientData);

            Field hField = clientData.getClass().getDeclaredField("skinImageHeight");
            hField.setAccessible(true);
            int height = (int) hField.get(clientData);

            if (logger != null) {
                logger.info("[BedrockSkin] Extracting skin for " + player.getName() + " (size: " + width + "x" + height + ")");
            }

            // BẢO VỆ CHỐNG BIẾN DẠNG 2D CHO BEDROCK:
            // Với skin Persona / Marketplace / Character Creator (256x256),
            // việc cắt subimage (32, 32) sẽ cắt trúng thân, chân hoặc dải pixel phụ kiện.
            // Trả về null để chuyển giao sang GeyserMC Global API chuyển đổi sang chuẩn 3D!
            if (width > 128 || height > 128) {
                if (logger != null) {
                    logger.info("[BedrockSkin] Skin of " + player.getName() + " is " + width + "x" + height + " (Persona/HD), skipping naive pixel extraction to prevent distorted 2D body parts.");
                }
                return null;
            }

            BufferedImage skinImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int idx = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int r = rawBytes[idx] & 0xFF;
                    int g = rawBytes[idx + 1] & 0xFF;
                    int b = rawBytes[idx + 2] & 0xFF;
                    int a = rawBytes[idx + 3] & 0xFF;
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    skinImg.setRGB(x, y, argb);
                    idx += 4;
                }
            }

            byte[] res = extractHeadFromImage(skinImg);
            if (logger != null) {
                logger.info("[BedrockSkin] Successfully extracted 128x128 head PNG (" + res.length + " bytes)");
            }
            return res;

        } catch (Throwable t) {
            if (logger != null) {
                logger.warning("[BedrockSkin] Extract error: " + t.getMessage());
            }
            return null;
        }
    }

    public static String uploadToGitHubCDN(String playerName, byte[] pngData, Logger logger) {
        if (pngData == null || pngData.length == 0 || ghToken.isEmpty()) {
            return null;
        }
        try {
            String safeName = playerName.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
            String path = "avatars/" + safeName + ".png";
            String apiUrl = "https://api.github.com/repos/" + GH_REPO + "/contents/" + path;

            String existingSha = null;
            try {
                HttpURLConnection getConn = (HttpURLConnection) new URL(apiUrl).openConnection();
                getConn.setRequestMethod("GET");
                getConn.setRequestProperty("Authorization", "Bearer " + ghToken);
                getConn.setRequestProperty("User-Agent", "KhangSMP-Bridge");
                getConn.setConnectTimeout(4000);
                getConn.setReadTimeout(4000);
                if (getConn.getResponseCode() == 200) {
                    try (InputStream is = getConn.getInputStream()) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] b = new byte[512];
                        int l;
                        while ((l = is.read(b)) != -1) baos.write(b, 0, l);
                        String jsonResp = baos.toString(StandardCharsets.UTF_8);
                        int shaIdx = jsonResp.indexOf("\"sha\":\"");
                        if (shaIdx != -1) {
                            int endIdx = jsonResp.indexOf("\"", shaIdx + 7);
                            if (endIdx != -1) {
                                existingSha = jsonResp.substring(shaIdx + 7, endIdx);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            String b64 = Base64.getEncoder().encodeToString(pngData);
            StringBuilder payload = new StringBuilder();
            payload.append("{\"message\":\"Update avatar for ").append(safeName).append("\",");
            payload.append("\"content\":\"").append(b64).append("\"");
            if (existingSha != null) {
                payload.append(",\"sha\":\"").append(existingSha).append("\"");
            }
            payload.append("}");

            HttpURLConnection putConn = (HttpURLConnection) new URL(apiUrl).openConnection();
            putConn.setRequestMethod("PUT");
            putConn.setDoOutput(true);
            putConn.setRequestProperty("Authorization", "Bearer " + ghToken);
            putConn.setRequestProperty("User-Agent", "KhangSMP-Bridge");
            putConn.setRequestProperty("Content-Type", "application/json");
            putConn.setConnectTimeout(6000);
            putConn.setReadTimeout(6000);

            try (OutputStream os = putConn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = putConn.getResponseCode();
            if (code == 200 || code == 201) {
                String cdnUrl = "https://raw.githubusercontent.com/" + GH_REPO + "/main/" + path + "?v=" + System.currentTimeMillis();
                if (logger != null) {
                    logger.info("[BedrockSkin] Uploaded avatar to GitHub CDN: " + cdnUrl);
                }
                return cdnUrl;
            } else {
                if (logger != null) {
                    logger.warning("[BedrockSkin] GitHub API returned HTTP " + code);
                }
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.warning("[BedrockSkin] GitHub upload error: " + t.getMessage());
            }
        }
        return null;
    }

    public static String uploadToCatbox(byte[] pngData, Logger logger) {
        if (pngData == null || pngData.length == 0) return null;
        try {
            String boundary = "----CatboxUploadBoundary" + System.currentTimeMillis();
            URL url = new URL("https://litterbox.catbox.moe/resources/internals/api.php");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                os.write("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                os.write("fileupload\r\n".getBytes(StandardCharsets.UTF_8));

                os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                os.write("Content-Disposition: form-data; name=\"time\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                os.write("72h\r\n".getBytes(StandardCharsets.UTF_8));

                os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                os.write("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"avatar.png\"\r\n".getBytes(StandardCharsets.UTF_8));
                os.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                os.write(pngData);
                os.write("\r\n".getBytes(StandardCharsets.UTF_8));

                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                try (InputStream is = conn.getInputStream()) {
                    ByteArrayOutputStream res = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = is.read(buf)) != -1) res.write(buf, 0, len);
                    String fileUrl = res.toString(StandardCharsets.UTF_8).trim();
                    if (fileUrl.startsWith("http")) {
                        if (logger != null) {
                            logger.info("[BedrockSkin] Uploaded avatar to Catbox: " + fileUrl);
                        }
                        return fileUrl;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
