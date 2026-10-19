package net.gawhara.gate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/** استدعاءات REST الخاصة بديسكورد. */
final class DiscordApi {

    enum Membership { MEMBER, NOT_MEMBER, ERROR }

    private static final String BASE = "https://discord.com/api/v10";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String token;
    private final String guildId;
    private final Logger log;
    private volatile boolean warnedAuth;

    DiscordApi(String token, String guildId, Logger log) {
        this.token = token;
        this.guildId = guildId;
        this.log = log;
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(6))
                .header("Authorization", "Bot " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "DiscordBot (GawharaGate, 1.0.0)");
    }

    /** هل صاحب هذا المعرّف عضو في الخادم؟ (حظر: يُستدعى خارج الخيط الرئيسي فقط) */
    Membership check(String userId) {
        try {
            HttpResponse<String> r = HTTP.send(
                    req("/guilds/" + guildId + "/members/" + userId).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            int sc = r.statusCode();
            if (sc == 200) {
                return Membership.MEMBER;
            }
            if (sc == 404) {
                int code = -1;
                try {
                    code = JsonParser.parseString(r.body()).getAsJsonObject().get("code").getAsInt();
                } catch (RuntimeException ignored) {
                    // لا نعرف الرمز
                }
                // 10007 عضو غير معروف، 10013 مستخدم غير معروف
                if (code == 10007 || code == 10013) {
                    return Membership.NOT_MEMBER;
                }
                log.warning("ديسكورد أعاد 404 برمز " + code + " — تحقق من guild-id ومن وجود البوت داخل الخادم.");
                return Membership.ERROR;
            }
            if ((sc == 401 || sc == 403) && !warnedAuth) {
                warnedAuth = true;
                log.severe("رفض ديسكورد الطلب (HTTP " + sc + "): تحقق من رمز البوت ومن إضافته إلى الخادم.");
            } else if (sc != 401 && sc != 403) {
                log.warning("تعذّر التحقق من العضوية: HTTP " + sc);
            }
            return Membership.ERROR;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Membership.ERROR;
        } catch (Exception ex) {
            log.warning("تعذّر الاتصال بديسكورد: " + ex.getMessage());
            return Membership.ERROR;
        }
    }

    /** معرّف التطبيق (البوت) عبر REST دون الحاجة إلى اتصال البوابة. */
    String fetchApplicationId() {
        try {
            HttpResponse<String> r = HTTP.send(req("/oauth2/applications/@me").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                return JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
            }
            log.severe("تعذّر قراءة بيانات البوت من ديسكورد: HTTP " + r.statusCode()
                    + " — تحقق من رمز البوت (discord.bot-token).");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warning("تعذّر قراءة بيانات البوت: " + ex.getMessage());
        }
        return null;
    }

    /** تسجيل أمر الربط داخل خادم ديسكورد. */
    boolean registerLinkCommand(String appId, String name, String description, String optionDescription) {
        JsonObject opt = new JsonObject();
        opt.addProperty("type", 3);
        opt.addProperty("name", "code");
        opt.addProperty("description", optionDescription);
        opt.addProperty("required", true);
        JsonArray opts = new JsonArray();
        opts.add(opt);
        JsonObject cmd = new JsonObject();
        cmd.addProperty("name", name);
        cmd.addProperty("type", 1);
        cmd.addProperty("description", description);
        cmd.add("options", opts);
        // POST ينشئ أو يحدّث هذا الأمر وحده دون المساس ببقية أوامر البوت (على خلاف PUT الذي يستبدلها كلها)
        JsonObject body = cmd;
        try {
            HttpResponse<String> r = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                r = HTTP.send(
                        req("/applications/" + appId + "/guilds/" + guildId + "/commands")
                                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 429) {
                    break;
                }
                // حدّ الطلبات: ننتظر المدة التي حدّدتها ديسكورد ثم نعيد المحاولة
                double wait = 5;
                try {
                    wait = JsonParser.parseString(r.body()).getAsJsonObject().get("retry_after").getAsDouble();
                } catch (RuntimeException ignored) {
                    // نستخدم القيمة الافتراضية
                }
                wait = Math.min(60, wait) + 1;
                log.info("ديسكورد طلب الانتظار " + (int) wait + " ثانية قبل تسجيل الأمر، سنعيد المحاولة تلقائيًا...");
                Thread.sleep((long) (wait * 1000));
            }
            if (r.statusCode() / 100 != 2) {
                log.severe("تعذّر تسجيل أمر الربط في ديسكورد: HTTP " + r.statusCode() + " " + r.body()
                        + " — أعد دعوة البوت إلى الخادم بنطاقي bot و applications.commands، وتحقق من guild-id.");
                return false;
            }
            log.info("تم تسجيل أمر /" + name + " في ديسكورد.");
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warning("تعذّر تسجيل أمر الربط: " + ex.getMessage());
        }
        return false;
    }

    /** إرسال رسالة نصية إلى قناة (أو رسالة خاصة)، مع رد اختياري على رسالة. */
    void sendMessage(String channelId, String content, String replyToMessageId) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        if (replyToMessageId != null) {
            JsonObject ref = new JsonObject();
            ref.addProperty("message_id", replyToMessageId);
            ref.addProperty("fail_if_not_exists", false);
            body.add("message_reference", ref);
        }
        try {
            HttpResponse<String> r = HTTP.send(
                    req("/channels/" + channelId + "/messages")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                log.warning("تعذّر إرسال الرد في ديسكورد: HTTP " + r.statusCode() + " " + r.body());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warning("تعذّر إرسال الرد في ديسكورد: " + ex.getMessage());
        }
    }

    /** رد مخفي (لا يراه سوى صاحب الأمر) على تفاعل. */
    void respond(String interactionId, String interactionToken, String content) {
        JsonObject data = new JsonObject();
        data.addProperty("content", content);
        data.addProperty("flags", 64);
        JsonObject body = new JsonObject();
        body.addProperty("type", 4);
        body.add("data", data);
        try {
            HttpResponse<String> r = HTTP.send(
                    req("/interactions/" + interactionId + "/" + interactionToken + "/callback")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                log.warning("تعذّر الرد على أمر ديسكورد: HTTP " + r.statusCode() + " " + r.body());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warning("تعذّر الرد على أمر ديسكورد: " + ex.getMessage());
        }
    }
}
