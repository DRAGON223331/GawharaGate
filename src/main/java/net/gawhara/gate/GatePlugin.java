package net.gawhara.gate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GawharaGate - يشترط عضوية اللاعب في خادم ديسكورد للدخول إلى خادم ماينكرافت.
 */
public final class GatePlugin extends JavaPlugin implements Listener, TabExecutor, DiscordGateway.Handler {

    private enum Kind { ALLOW, NEED_LINK, NOT_MEMBER, ERROR }

    private record Decision(Kind kind, String code) {
        static Decision of(Kind k) {
            return new Decision(k, "");
        }
    }

    private LinkStore store;
    private DiscordApi api;
    private DiscordGateway gateway;
    private BukkitTask recheck;
    private boolean configured;
    private boolean warnedUnconfigured;
    private volatile boolean commandsRegistered;

    /** معرّف ديسكورد ← وقت انتهاء صلاحية نتيجة التحقق الناجحة. */
    private final Map<String, Long> memberCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ دورة الحياة

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new LinkStore(new File(getDataFolder(), "links.yml"));
        store.load();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("gate") != null) {
            getCommand("gate").setExecutor(this);
            getCommand("gate").setTabCompleter(this);
        }
        startDiscord();
    }

    @Override
    public void onDisable() {
        stopDiscord();
    }

    private void startDiscord() {
        stopDiscord();
        String token = getConfig().getString("discord.bot-token", "").trim();
        String guild = getConfig().getString("discord.guild-id", "").trim();
        configured = !token.isEmpty() && !guild.isEmpty();
        memberCache.clear();
        commandsRegistered = false;
        if (!configured) {
            getLogger().warning("لم يُضبط discord.bot-token أو discord.guild-id في config.yml، "
                    + "لذلك لن يُفرض شرط ديسكورد حاليًا (يُسمح للجميع بالدخول).");
            return;
        }
        api = new DiscordApi(token, guild, getLogger());
        int intents = 0;
        if (getConfig().getBoolean("link.dm", true)) {
            intents |= 1 << 12;                 // DIRECT_MESSAGES (لا يحتاج صلاحية خاصة)
        }
        if (!getConfig().getString("link.channel-id", "").isBlank()) {
            intents |= (1 << 9) | (1 << 15);    // GUILD_MESSAGES + MESSAGE_CONTENT (يلزم تفعيله من Developer Portal)
        }
        gateway = new DiscordGateway(token, intents, this, getLogger());
        gateway.start();
        if (getConfig().getBoolean("link.slash-command", false)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> registerCommands(false));
        }

        int minutes = getConfig().getInt("recheck-minutes", 10);
        if (minutes > 0) {
            long ticks = minutes * 60L * 20L;
            recheck = Bukkit.getScheduler().runTaskTimer(this, () -> recheckOnline(null), ticks, ticks);
        }
    }

    private void stopDiscord() {
        if (recheck != null) {
            recheck.cancel();
            recheck = null;
        }
        if (gateway != null) {
            gateway.stop();
            gateway = null;
        }
        api = null;
    }

    // ------------------------------------------------------------ منطق القرار

    private boolean bypassed(UUID uuid, String name) {
        if (getConfig().getStringList("bypass.names").stream().anyMatch(n -> n.equalsIgnoreCase(name))) {
            return true;
        }
        if (getConfig().getStringList("bypass.uuids").stream().anyMatch(u -> u.equalsIgnoreCase(uuid.toString()))) {
            return true;
        }
        return getConfig().getBoolean("bypass.ops", true) && Bukkit.getOfflinePlayer(uuid).isOp();
    }

    /** يُستدعى خارج الخيط الرئيسي فقط (قد يتصل بديسكورد). */
    private Decision decide(UUID uuid, String name) {
        DiscordApi a = api;
        if (!configured || a == null || bypassed(uuid, name)) {
            return Decision.of(Kind.ALLOW);
        }
        String discordId = store.discordOf(uuid);
        if (discordId == null) {
            long ttl = getConfig().getInt("code-minutes", 10) * 60_000L;
            return new Decision(Kind.NEED_LINK, store.codeFor(uuid, name, ttl));
        }
        Long until = memberCache.get(discordId);
        if (until != null && until > System.currentTimeMillis()) {
            return Decision.of(Kind.ALLOW);
        }
        switch (a.check(discordId)) {
            case MEMBER -> {
                memberCache.put(discordId, System.currentTimeMillis()
                        + getConfig().getInt("cache-seconds", 300) * 1000L);
                return Decision.of(Kind.ALLOW);
            }
            case NOT_MEMBER -> {
                memberCache.remove(discordId);
                return Decision.of(Kind.NOT_MEMBER);
            }
            default -> {
                boolean deny = "deny".equalsIgnoreCase(getConfig().getString("on-discord-error", "allow"));
                return Decision.of(deny ? Kind.ERROR : Kind.ALLOW);
            }
        }
    }

    /** نص طريقة الربط المناسبة حسب الإعدادات. */
    private String howText() {
        if (getConfig().getBoolean("link.slash-command", false)) {
            return getConfig().getString("messages.how-slash", "");
        }
        boolean hasChannel = !getConfig().getString("link.channel-id", "").isBlank();
        boolean hasDm = getConfig().getBoolean("link.dm", true);
        if (hasChannel && hasDm) {
            return getConfig().getString("messages.how-both", "");
        }
        if (hasChannel) {
            return getConfig().getString("messages.how-channel", "");
        }
        return getConfig().getString("messages.how-dm", "");
    }

    private Component message(String key, String code) {
        List<String> lines = getConfig().getStringList("messages." + key);
        String text = String.join("\n", lines)
                .replace("{how}", howText())
                .replace("{bot}", getConfig().getString("link.bot-name", "البوت"))
                .replace("{channel}", getConfig().getString("link.channel-name", "قناة الربط"))
                .replace("{server}", getConfig().getString("discord.guild-name", "ديسكورد"))
                .replace("{invite}", getConfig().getString("discord.invite-url", ""))
                .replace("{command}", getConfig().getString("link-command-name", "link"))
                .replace("{minutes}", String.valueOf(getConfig().getInt("code-minutes", 10)))
                .replace("{code}", code);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private Component kickMessage(Decision d) {
        return switch (d.kind()) {
            case NEED_LINK -> message("kick-not-linked", d.code());
            case NOT_MEMBER -> message("kick-not-member", "");
            default -> message("kick-error", "");
        };
    }

    // ------------------------------------------------------------ الدخول

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        Decision d = decide(e.getUniqueId(), e.getName());
        if (d.kind() != Kind.ALLOW) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage(d));
        }
    }

    /** فحص دوري للمتصلين: طرد من غادر ديسكورد. */
    private void recheckOnline(CommandSender requester) {
        List<UUID> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ids.add(p.getUniqueId());
            names.add(p.getName());
        }
        if (ids.isEmpty()) {
            if (requester != null) {
                requester.sendMessage(Component.text("لا يوجد لاعبون متصلون للفحص."));
            }
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            int kicked = 0;
            int errors = 0;
            for (int i = 0; i < ids.size(); i++) {
                UUID id = ids.get(i);
                Decision d = decide(id, names.get(i));
                // لا نطرد المتصلين بسبب أعطال ديسكورد، بل عند ثبوت المغادرة أو عدم الربط فقط
                if (d.kind() == Kind.NOT_MEMBER || d.kind() == Kind.NEED_LINK) {
                    kicked++;
                    Bukkit.getScheduler().runTask(this, () -> {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) {
                            p.kick(kickMessage(d));
                        }
                    });
                } else if (d.kind() == Kind.ERROR) {
                    errors++;
                }
            }
            if (requester != null) {
                final int k = kicked;
                final int er = errors;
                final int total = ids.size();
                Bukkit.getScheduler().runTask(this, () -> requester.sendMessage(Component.text(
                        "اكتمل الفحص: " + total + " لاعب، طُرد " + k + (er > 0 ? "، وتعذّر التحقق من " + er : "") + ".")));
            }
        });
    }

    // ------------------------------------------------------------ أوامر ديسكورد

    @Override
    public void onReady(String applicationId) {
        if (getConfig().getBoolean("link.slash-command", false)) {
            registerWith(applicationId, false);
        }
    }

    /** تسجيل أمر الربط (يُستدعى خارج الخيط الرئيسي). */
    private boolean registerCommands(boolean force) {
        DiscordApi a = api;
        if (a == null) {
            return false;
        }
        String appId = a.fetchApplicationId();
        return appId != null && registerWith(appId, force);
    }

    /** متزامن حتى لا يُرسل طلب التسجيل مرتين في وقت واحد (يتسبب بحدّ الطلبات). */
    private synchronized boolean registerWith(String applicationId, boolean force) {
        DiscordApi a = api;
        if (a == null) {
            return false;
        }
        if (commandsRegistered && !force) {
            return true;
        }
        boolean ok = a.registerLinkCommand(applicationId,
                getConfig().getString("link-command-name", "link"),
                getConfig().getString("messages.command-description", "ربط الحساب"),
                getConfig().getString("messages.option-description", "الرمز"));
        commandsRegistered = ok;
        return ok;
    }

    @Override
    public void onInteraction(JsonObject d) {
        DiscordApi a = api;
        if (a == null) {
            return;
        }
        try {
            if (!d.has("type") || d.get("type").getAsInt() != 2 || !d.has("data")) {
                return;
            }
            JsonObject data = d.getAsJsonObject("data");
            String cmd = data.has("name") ? data.get("name").getAsString() : "";
            if (!cmd.equals(getConfig().getString("link-command-name", "link"))) {
                return;
            }
            String interactionId = d.get("id").getAsString();
            String interactionToken = d.get("token").getAsString();

            String userId = null;
            if (d.has("member") && d.getAsJsonObject("member").has("user")) {
                userId = d.getAsJsonObject("member").getAsJsonObject("user").get("id").getAsString();
            } else if (d.has("user")) {
                userId = d.getAsJsonObject("user").get("id").getAsString();
            }
            String code = "";
            if (data.has("options")) {
                JsonArray opts = data.getAsJsonArray("options");
                for (JsonElement el : opts) {
                    JsonObject o = el.getAsJsonObject();
                    if (o.get("name").getAsString().equals("code")) {
                        code = o.get("value").getAsString();
                    }
                }
            }
            if (userId == null) {
                return;
            }
            // الأمر مسجّل داخل خادم البرج فقط، فتنفيذه يعني أن صاحبه عضو فيه
            a.respond(interactionId, interactionToken, handleLink(userId, code));
        } catch (RuntimeException ex) {
            getLogger().warning("تعذّر معالجة أمر ديسكورد: " + ex.getMessage());
        }
    }

    @Override
    public void onMessage(JsonObject d) {
        DiscordApi a = api;
        if (a == null || !d.has("author") || !d.has("channel_id") || !d.has("id")) {
            return;
        }
        try {
            JsonObject author = d.getAsJsonObject("author");
            if (author.has("bot") && author.get("bot").getAsBoolean()) {
                return;
            }
            String userId = author.get("id").getAsString();
            String channelId = d.get("channel_id").getAsString();
            String messageId = d.get("id").getAsString();
            String content = d.has("content") ? d.get("content").getAsString().trim() : "";
            boolean isDm = !d.has("guild_id");
            String linkChannel = getConfig().getString("link.channel-id", "").trim();

            if (isDm) {
                if (!getConfig().getBoolean("link.dm", true)) {
                    return;
                }
            } else if (linkChannel.isEmpty() || !channelId.equals(linkChannel)) {
                return;
            }
            String code = content.replaceAll("[^A-Za-z0-9]", "");
            boolean looksLikeCode = content.matches("(?i)^[A-Z0-9]{6}$");
            if (!isDm && !looksLikeCode) {
                return; // لا نرد على الأحاديث العادية في قناة الربط
            }
            String replyTo = isDm ? null : messageId;
            if (!looksLikeCode) {
                a.sendMessage(channelId, text("link-dm-help"), replyTo);
                return;
            }
            // يجب أن يكون صاحب الرسالة عضوًا في خادم ديسكورد (الرسالة الخاصة لا تثبت ذلك وحدها)
            if (a.check(userId) != DiscordApi.Membership.MEMBER) {
                a.sendMessage(channelId, plain("link-not-member"), replyTo);
                return;
            }
            a.sendMessage(channelId, handleLink(userId, code.toUpperCase()), replyTo);
        } catch (RuntimeException ex) {
            getLogger().warning("تعذّر معالجة رسالة ديسكورد: " + ex.getMessage());
        }
    }

    private String plain(String key) {
        return getConfig().getString("messages." + key, key)
                .replace("{server}", getConfig().getString("discord.guild-name", "ديسكورد"))
                .replace("{invite}", getConfig().getString("discord.invite-url", ""));
    }

    private String text(String key) {
        return getConfig().getString("messages." + key, key);
    }

    private String handleLink(String discordId, String code) {
        LinkStore.Pending p = code.isBlank() ? null : store.peek(code);
        if (p == null) {
            return text("link-invalid");
        }
        String existing = store.discordOf(p.uuid());
        if (existing != null && !existing.equals(discordId)) {
            return text("link-already").replace("{player}", p.name());
        }
        int max = Math.max(1, getConfig().getInt("max-accounts-per-discord", 2));
        if (existing == null && store.countFor(discordId) >= max) {
            return text("link-limit").replace("{max}", String.valueOf(max));
        }
        try {
            store.link(p.uuid(), discordId);
        } catch (IOException ex) {
            getLogger().warning("تعذّر حفظ links.yml: " + ex.getMessage());
            return "حدث خطأ أثناء الحفظ، أبلغ الإدارة.";
        }
        store.consume(code);
        memberCache.put(discordId, System.currentTimeMillis() + getConfig().getInt("cache-seconds", 300) * 1000L);
        getLogger().info("رُبط اللاعب " + p.name() + " بحساب ديسكورد " + discordId);
        return text("link-success").replace("{player}", p.name());
    }

    // ------------------------------------------------------------ أوامر اللعبة

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gawharagate.admin")) {
            sender.sendMessage(Component.text("ليست لديك صلاحية لتنفيذ هذا الأمر."));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                store.load();
                startDiscord();
                sender.sendMessage(Component.text("تمت إعادة تحميل الإعدادات والاتصال بديسكورد."));
            }
            case "scan" -> {
                if (!configured) {
                    sender.sendMessage(Component.text("الإضافة غير مضبوطة بعد (رمز البوت مفقود)."));
                } else {
                    sender.sendMessage(Component.text("جارٍ فحص اللاعبين المتصلين..."));
                    recheckOnline(sender);
                }
            }
            case "register" -> {
                if (!configured) {
                    sender.sendMessage(Component.text("الإضافة غير مضبوطة بعد (رمز البوت مفقود)."));
                } else {
                    sender.sendMessage(Component.text("جارٍ تسجيل الأوامر في ديسكورد..."));
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        boolean ok = registerCommands(true);
                        Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(Component.text(ok
                                ? "تم تسجيل الأمر /" + getConfig().getString("link-command-name", "link")
                                + " في ديسكورد. قد تحتاج إلى إعادة فتح ديسكورد ليظهر."
                                : "فشل التسجيل، راجع سجل السيرفر (console) لمعرفة السبب.")));
                    });
                }
            }
            case "status" -> sender.sendMessage(Component.text(
                    "الحالة: " + (configured ? "مفعّلة" : "غير مضبوطة (لا يُفرض الشرط)")
                            + " | تسجيل الأمر في ديسكورد: " + (commandsRegistered ? "تم" : "لم يتم")
                            + " | عدد الحسابات المربوطة: " + store.total()));
            case "check" -> {
                OfflinePlayer op = target(sender, args);
                if (op != null) {
                    String id = store.discordOf(op.getUniqueId());
                    sender.sendMessage(Component.text(id == null
                            ? op.getName() + ": غير مربوط بأي حساب ديسكورد."
                            : op.getName() + ": مربوط بحساب ديسكورد " + id));
                }
            }
            case "unlink" -> {
                OfflinePlayer op = target(sender, args);
                if (op != null) {
                    try {
                        String old = store.discordOf(op.getUniqueId());
                        boolean had = store.unlink(op.getUniqueId());
                        if (old != null) {
                            memberCache.remove(old);
                        }
                        sender.sendMessage(Component.text(had
                                ? "تم فك ربط " + op.getName() + "."
                                : op.getName() + " غير مربوط أصلًا."));
                    } catch (IOException ex) {
                        sender.sendMessage(Component.text("تعذّر حفظ الملف: " + ex.getMessage()));
                    }
                }
            }
            case "link" -> {
                OfflinePlayer op = target(sender, args);
                if (op != null) {
                    if (args.length < 3 || !args[2].matches("\\d{15,25}")) {
                        sender.sendMessage(Component.text("الاستخدام: /gate link <اللاعب> <معرّف ديسكورد الرقمي>"));
                        return true;
                    }
                    try {
                        store.link(op.getUniqueId(), args[2]);
                        sender.sendMessage(Component.text("تم ربط " + op.getName() + " بالحساب " + args[2] + "."));
                    } catch (IOException ex) {
                        sender.sendMessage(Component.text("تعذّر حفظ الملف: " + ex.getMessage()));
                    }
                }
            }
            default -> sender.sendMessage(Component.text("الاستخدام: /gate <reload|status|register|scan|check|link|unlink>"));
        }
        return true;
    }

    private OfflinePlayer target(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("حدّد اسم اللاعب."));
            return null;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (op == null) {
            sender.sendMessage(Component.text("لاعب غير معروف: " + args[1] + " (يجب أن يكون قد دخل الخادم سابقًا)."));
        }
        return op;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("reload", "status", "register", "scan", "check", "link", "unlink")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("status")
                && !args[0].equalsIgnoreCase("scan")
                && !args[0].equalsIgnoreCase("register")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
