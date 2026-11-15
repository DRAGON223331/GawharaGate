package net.gawhara.gate;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** اتصال Gateway مبسّط بديسكورد لاستقبال أمر /link (لا يحتاج أي صلاحيات خاصة). */
final class DiscordGateway implements WebSocket.Listener {

    interface Handler {
        void onReady(String applicationId);

        void onInteraction(JsonObject data);

        void onMessage(JsonObject data);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final String token;
    private volatile int intents;
    private final Handler handler;
    private final Logger log;

    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GawharaGate-Gateway");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService work = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GawharaGate-Interactions");
        t.setDaemon(true);
        return t;
    });

    private final StringBuilder buffer = new StringBuilder();
    private volatile WebSocket socket;
    private volatile boolean stopped;
    private volatile Integer seq;
    private ScheduledFuture<?> heartbeat;
    private int failures;
    private volatile boolean acked = true;
    private volatile boolean connected;
    private volatile boolean degraded;
    private volatile String lastClose = "-";
    private final java.util.concurrent.atomic.AtomicBoolean reconnecting = new java.util.concurrent.atomic.AtomicBoolean();

    DiscordGateway(String token, int intents, Handler handler, Logger log) {
        this.token = token;
        this.intents = intents;
        this.handler = handler;
        this.log = log;
    }

    void start() {
        sched.execute(this::connect);
    }

    /** وصف مختصر لحالة الاتصال (يظهر في /gate status). */
    String status() {
        return (connected ? "متصلة" : "غير متصلة") + (degraded ? " (الرسائل الخاصة فقط)" : "")
                + " | آخر إغلاق: " + lastClose;
    }

    void stop() {
        stopped = true;
        WebSocket ws = socket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (RuntimeException ignored) {
                // لا شيء
            }
        }
        sched.shutdownNow();
        work.shutdownNow();
    }

    // ------------------------------------------------------------ اتصال

    private void connect() {
        if (stopped) {
            return;
        }
        reconnecting.set(false);
        buffer.setLength(0);
        HTTP.newWebSocketBuilder()
                .buildAsync(URI.create("wss://gateway.discord.gg/?v=10&encoding=json"), this)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.warning("تعذّر الاتصال ببوابة ديسكورد: " + err.getMessage());
                        reconnectLater();
                    }
                });
    }

    private void reconnectLater() {
        if (stopped || !reconnecting.compareAndSet(false, true)) {
            return;
        }
        cancelHeartbeat();
        failures++;
        long delay = Math.min(60, 3L * failures);
        try {
            sched.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
            // المنفّذ أُغلق
        }
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> h = heartbeat;
        if (h != null) {
            h.cancel(false);
            heartbeat = null;
        }
    }

    private void send(WebSocket ws, JsonObject o) {
        try {
            synchronized (this) {
                ws.sendText(o.toString(), true).orTimeout(8, TimeUnit.SECONDS).join();
            }
        } catch (RuntimeException ex) {
            log.warning("تعذّر الإرسال إلى بوابة ديسكورد: " + ex.getMessage());
        }
    }

    /** نبضة دورية: إن لم يردّ ديسكورد على النبضة السابقة فالاتصال ميّت ويُعاد بناؤه. */
    private void beat(WebSocket ws) {
        if (ws != socket || stopped) {
            return;
        }
        if (!acked) {
            log.warning("لم يردّ ديسكورد على نبضة الاتصال، سيُعاد الاتصال تلقائيًا...");
            connected = false;
            try {
                ws.abort();
            } catch (RuntimeException ignored) {
                // لا شيء
            }
            reconnectLater();
            return;
        }
        acked = false;
        sendHeartbeat(ws);
    }

    private void sendHeartbeat(WebSocket ws) {
        JsonObject o = new JsonObject();
        o.addProperty("op", 1);
        Integer s = seq;
        o.add("d", s == null ? JsonNull.INSTANCE : new JsonPrimitive(s));
        send(ws, o);
    }

    private void identify(WebSocket ws) {
        JsonObject props = new JsonObject();
        props.addProperty("os", "linux");
        props.addProperty("browser", "gawhara-gate");
        props.addProperty("device", "gawhara-gate");
        JsonObject d = new JsonObject();
        d.addProperty("token", token);
        d.addProperty("intents", intents);
        d.add("properties", props);
        JsonObject o = new JsonObject();
        o.addProperty("op", 2);
        o.add("d", d);
        send(ws, o);
    }

    // ------------------------------------------------------------ مستمع WebSocket

    @Override
    public void onOpen(WebSocket ws) {
        socket = ws;
        ws.request(1);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            String msg = buffer.toString();
            buffer.setLength(0);
            try {
                process(ws, JsonParser.parseString(msg).getAsJsonObject());
            } catch (RuntimeException ex) {
                log.warning("رسالة غير مفهومة من ديسكورد: " + ex.getMessage());
            }
        }
        ws.request(1);
        return null;
    }

    private void process(WebSocket ws, JsonObject o) {
        JsonElement s = o.get("s");
        if (s != null && !s.isJsonNull()) {
            seq = s.getAsInt();
        }
        int op = o.get("op").getAsInt();
        switch (op) {
            case 10 -> {
                long interval = o.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                cancelHeartbeat();
                acked = true;
                heartbeat = sched.scheduleAtFixedRate(() -> beat(ws),
                        (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
                sched.execute(() -> identify(ws));
            }
            case 1 -> sched.execute(() -> sendHeartbeat(ws));
            case 11 -> acked = true;
            case 7, 9 -> {
                // طلب إعادة اتصال أو جلسة غير صالحة
                try {
                    ws.sendClose(4000, "reconnect");
                } catch (RuntimeException ignored) {
                    // لا شيء
                }
            }
            case 0 -> {
                String t = o.has("t") && !o.get("t").isJsonNull() ? o.get("t").getAsString() : "";
                JsonObject d = o.has("d") && o.get("d").isJsonObject() ? o.getAsJsonObject("d") : new JsonObject();
                if (t.equals("READY")) {
                    failures = 0;
                    connected = true;
                    acked = true;
                    log.info("اتصل البوت ببوابة ديسكورد بنجاح.");
                    String appId = d.getAsJsonObject("application").get("id").getAsString();
                    work.execute(() -> handler.onReady(appId));
                } else if (t.equals("INTERACTION_CREATE")) {
                    work.execute(() -> handler.onInteraction(d));
                } else if (t.equals("MESSAGE_CREATE")) {
                    work.execute(() -> handler.onMessage(d));
                }
            }
            default -> { }
        }
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        if (ws != socket) {
            return null;
        }
        connected = false;
        lastClose = statusCode + (reason == null || reason.isBlank() ? "" : " " + reason);
        cancelHeartbeat();
        if (statusCode == 4004) {
            log.severe("رفضت ديسكورد رمز البوت (4004). تحقق من discord.bot-token ثم نفّذ /gate reload");
            stopped = true;
            return null;
        }
        if (statusCode == 4013 || statusCode == 4014) {
            // Message Content Intent غير مفعّل في Developer Portal: لا نوقف البوت كله،
            // بل نكمل بالرسائل الخاصة فقط (لا تحتاج صلاحية خاصة)
            int fallback = intents & ~((1 << 9) | (1 << 15));
            if (fallback != intents && fallback != 0) {
                intents = fallback;
                degraded = true;
                log.severe("رفضت ديسكورد الصلاحيات المطلوبة (" + statusCode + "): خيار Message Content Intent غير مفعّل. "
                        + "سيستمر الربط عبر الرسائل الخاصة فقط، وقناة الربط معطّلة. لتفعيلها: Developer Portal ← Bot ← "
                        + "Message Content Intent ثم /gate reload.");
                reconnectLater();
                return null;
            }
            log.severe("رفضت ديسكورد الصلاحيات المطلوبة (" + statusCode + "). فعّل الخيارات المطلوبة من Developer Portal ← Bot "
                    + "ثم نفّذ /gate reload");
            stopped = true;
            return null;
        }
        if (statusCode == 4010 || statusCode == 4011 || statusCode == 4012) {
            log.severe("أغلقت ديسكورد الاتصال برمز غير قابل للإصلاح (" + statusCode + ").");
            stopped = true;
            return null;
        }
        reconnectLater();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        if (ws != socket) {
            return;
        }
        connected = false;
        lastClose = "error: " + error.getMessage();
        log.warning("خطأ في اتصال ديسكورد: " + error.getMessage());
        reconnectLater();
    }
}
