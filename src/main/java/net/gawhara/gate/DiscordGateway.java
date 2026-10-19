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
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final String token;
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
    private final java.util.concurrent.atomic.AtomicBoolean reconnecting = new java.util.concurrent.atomic.AtomicBoolean();

    DiscordGateway(String token, Handler handler, Logger log) {
        this.token = token;
        this.handler = handler;
        this.log = log;
    }

    void start() {
        sched.execute(this::connect);
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
        d.addProperty("intents", 0);
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
                heartbeat = sched.scheduleAtFixedRate(() -> sendHeartbeat(ws),
                        (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
                sched.execute(() -> identify(ws));
            }
            case 1 -> sched.execute(() -> sendHeartbeat(ws));
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
                    log.info("اتصل البوت ببوابة ديسكورد بنجاح.");
                    String appId = d.getAsJsonObject("application").get("id").getAsString();
                    work.execute(() -> handler.onReady(appId));
                } else if (t.equals("INTERACTION_CREATE")) {
                    work.execute(() -> handler.onInteraction(d));
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
        if (statusCode == 4004) {
            log.severe("رفضت ديسكورد رمز البوت (4004). تحقق من discord.bot-token ثم نفّذ /gate reload");
            stopped = true;
            return null;
        }
        if (statusCode == 4013 || statusCode == 4014) {
            log.severe("رفضت ديسكورد الصلاحيات المطلوبة (" + statusCode + ").");
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
        log.warning("خطأ في اتصال ديسكورد: " + error.getMessage());
        reconnectLater();
    }
}
