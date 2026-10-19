package net.gawhara.gate;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** تخزين الربط بين حسابات ماينكرافت وحسابات ديسكورد، ورموز الربط المؤقتة. */
final class LinkStore {

    record Pending(UUID uuid, String name, long expiresAt) {
    }

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final File file;
    private final Map<UUID, String> links = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();

    LinkStore(File file) {
        this.file = file;
    }

    synchronized void load() {
        links.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("links");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            try {
                String id = sec.getString(key, "");
                if (!id.isBlank()) {
                    links.put(UUID.fromString(key), id);
                }
            } catch (IllegalArgumentException ignored) {
                // مفتاح تالف: نتجاهله
            }
        }
    }

    private void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : links.entrySet()) {
            y.set("links." + e.getKey(), e.getValue());
        }
        file.getParentFile().mkdirs();
        y.save(file);
    }

    synchronized String discordOf(UUID uuid) {
        return links.get(uuid);
    }

    synchronized int countFor(String discordId) {
        int n = 0;
        for (String v : links.values()) {
            if (v.equals(discordId)) {
                n++;
            }
        }
        return n;
    }

    synchronized int total() {
        return links.size();
    }

    synchronized void link(UUID uuid, String discordId) throws IOException {
        links.put(uuid, discordId);
        save();
    }

    synchronized boolean unlink(UUID uuid) throws IOException {
        boolean had = links.remove(uuid) != null;
        if (had) {
            save();
        }
        return had;
    }

    /** ينشئ رمزًا جديدًا للاعب، أو يعيد رمزه الحالي إن كان صالحًا. */
    synchronized String codeFor(UUID uuid, String name, long ttlMillis) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expiresAt() < now);
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            if (e.getValue().uuid().equals(uuid)) {
                return e.getKey();
            }
        }
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            code = sb.toString();
        } while (pending.containsKey(code));
        pending.put(code, new Pending(uuid, name, now + ttlMillis));
        return code;
    }

    /** يعيد الطلب المعلّق لهذا الرمز دون حذفه (يُحذف بعد نجاح الربط). */
    synchronized Pending peek(String code) {
        Pending p = pending.get(code.trim().toUpperCase());
        if (p == null || p.expiresAt() < System.currentTimeMillis()) {
            pending.remove(code.trim().toUpperCase());
            return null;
        }
        return p;
    }

    synchronized void consume(String code) {
        pending.remove(code.trim().toUpperCase());
    }
}
