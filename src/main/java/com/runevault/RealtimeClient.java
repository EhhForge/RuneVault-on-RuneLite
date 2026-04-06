package com.runevault;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a persistent Supabase Realtime WebSocket connection for the RuneVault plugin.
 *
 * Subscribes to UPDATE events on the profiles table filtered by user_id.
 * When plugin_disconnect_requested=true is received, fires onDisconnectRequested
 * on the provided executor (never on the OkHttp WebSocket thread).
 *
 * Handles:
 *  - Phoenix protocol framing (join, heartbeat, postgres_changes)
 *  - Exponential reconnect backoff (2s → 5s → 10s → 30s → 60s, capped)
 *  - One-time REST fallback on reconnect to catch events missed while down
 *  - In-band token refresh (sends updated access_token to server without reconnecting)
 *  - Duplicate connection guard (synchronized on currentSocket)
 *  - Clean shutdown with no dangling threads
 */
@Slf4j
public class RealtimeClient
{
    private static final String WS_URL_TEMPLATE =
        "wss://SUPABASE_PROJECT_URL_REDACTED.supabase.co/realtime/v1/websocket?apikey=%s&vsn=1.0.0";
    private static final String ANON_KEY =
        "SUPABASE_ANON_KEY_REDACTED";
    private static final String SUPABASE_URL =
        "https://SUPABASE_PROJECT_URL_REDACTED.supabase.co";

    // Phoenix heartbeat interval — server drops the connection after ~60s without one
    private static final long PHOENIX_HEARTBEAT_INTERVAL_S = 25;

    // Reconnect backoff steps in seconds
    private static final long[] BACKOFF_STEPS = { 2, 5, 10, 30, 60 };

    private final OkHttpClient httpClient;
    private final Gson         gson;
    private final ScheduledExecutorService executor; // shared with SupabaseClient — single-thread
    private final Runnable onDisconnectRequested;     // dispatched to executor, never WS thread

    // Guarded by `this` lock
    private WebSocket  currentSocket    = null;
    private boolean    closed           = false; // true after close() — suppresses reconnects
    private String     currentUserId    = null;
    private String     currentToken     = null;

    private final AtomicInteger  ref           = new AtomicInteger(1);
    private final AtomicInteger  backoffIndex  = new AtomicInteger(0);
    private       ScheduledFuture<?> phoenixHeartbeat = null;
    private       ScheduledFuture<?> reconnectFuture  = null;

    public RealtimeClient(
        OkHttpClient httpClient,
        Gson gson,
        ScheduledExecutorService executor,
        Runnable onDisconnectRequested)
    {
        this.httpClient           = httpClient;
        this.gson                 = gson;
        this.executor             = executor;
        this.onDisconnectRequested = onDisconnectRequested;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Open a Realtime subscription for the given user.
     * Safe to call multiple times — closes any existing socket first.
     * Must be called from the executor thread (already the pattern in SupabaseClient).
     */
    public synchronized void connect(String userId, String accessToken)
    {
        if (closed) return;
        currentUserId = userId;
        currentToken  = accessToken;
        openSocket();
    }

    /**
     * Update the access token in-band after a token refresh.
     * Sends a Phoenix leave + rejoin with the new token — no TCP reconnect needed.
     * Falls back to a full reconnect if the socket is gone.
     */
    public synchronized void updateToken(String newToken)
    {
        currentToken = newToken;
        if (currentSocket == null || currentUserId == null) return;

        log.debug("[RuneVault/Realtime] Updating access token in-band.");
        // Leave the current channel
        currentSocket.send(buildLeave());
        // Rejoin with the new token — server will accept the updated credentials
        currentSocket.send(buildJoin(currentUserId, newToken));
    }

    /**
     * Permanently close the WebSocket. Suppresses all reconnect attempts.
     * Must be called on plugin shutdown.
     */
    public synchronized void close()
    {
        closed = true;
        cancelPhoenixHeartbeat();
        cancelReconnect();
        if (currentSocket != null)
        {
            currentSocket.cancel(); // hard close — don't wait for server ack
            currentSocket = null;
        }
        log.debug("[RuneVault/Realtime] Closed.");
    }

    // -------------------------------------------------------------------------
    // Internal — socket lifecycle
    // -------------------------------------------------------------------------

    private void openSocket()
    {
        // Cancel any pending reconnect — we're connecting now
        cancelReconnect();

        // Hard-cancel any existing socket before opening a new one
        if (currentSocket != null)
        {
            currentSocket.cancel();
            currentSocket = null;
        }

        String url = String.format(WS_URL_TEMPLATE, ANON_KEY);
        Request request = new Request.Builder().url(url).build();
        currentSocket = httpClient.newWebSocket(request, new Listener());
        log.debug("[RuneVault/Realtime] Opening WebSocket...");
    }

    private void scheduleReconnect()
    {
        if (closed) return;
        int idx  = Math.min(backoffIndex.getAndIncrement(), BACKOFF_STEPS.length - 1);
        long delay = BACKOFF_STEPS[idx];
        log.debug("[RuneVault/Realtime] Reconnecting in {}s (attempt {}).", delay, idx + 1);
        reconnectFuture = executor.schedule(() -> {
            synchronized (RealtimeClient.this)
            {
                if (!closed && currentUserId != null && currentToken != null)
                    openSocket();
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelPhoenixHeartbeat()
    {
        if (phoenixHeartbeat != null) { phoenixHeartbeat.cancel(false); phoenixHeartbeat = null; }
    }

    private void cancelReconnect()
    {
        if (reconnectFuture != null) { reconnectFuture.cancel(false); reconnectFuture = null; }
    }

    private void startPhoenixHeartbeat(WebSocket socket)
    {
        cancelPhoenixHeartbeat();
        phoenixHeartbeat = executor.scheduleWithFixedDelay(() -> {
            synchronized (RealtimeClient.this)
            {
                if (currentSocket == socket) // only heartbeat if this is still the live socket
                    socket.send(buildHeartbeat());
            }
        }, PHOENIX_HEARTBEAT_INTERVAL_S, PHOENIX_HEARTBEAT_INTERVAL_S, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Internal — one-time REST fallback on reconnect
    // -------------------------------------------------------------------------

    /**
     * Called once after a successful channel join.
     * Checks whether plugin_disconnect_requested was set while the WebSocket was down.
     * This covers the gap between a network drop and the next successful connection.
     */
    private void checkMissedDisconnect()
    {
        if (currentUserId == null || currentToken == null) return;

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles"
                + "?user_id=eq." + currentUserId
                + "&select=plugin_disconnect_requested")
            .get()
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + currentToken)
            .addHeader("Accept",        "application/json")
            .build();

        httpClient.newCall(request).enqueue(new okhttp3.Callback()
        {
            @Override
            public void onFailure(Call call, java.io.IOException e)
            {
                log.debug("[RuneVault/Realtime] Missed-disconnect check failed: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws java.io.IOException
            {
                try {
                    if (!response.isSuccessful() || response.body() == null) return;
                    String body = response.body().string();
                    com.google.gson.JsonArray arr =
                        new JsonParser().parse(body).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++)
                    {
                        JsonObject row = arr.get(i).getAsJsonObject();
                        if (row.has("plugin_disconnect_requested")
                                && row.get("plugin_disconnect_requested").getAsBoolean())
                        {
                            log.debug("[RuneVault/Realtime] Missed disconnect detected via REST fallback.");
                            executor.execute(onDisconnectRequested);
                            return;
                        }
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Internal — Phoenix message builders
    // -------------------------------------------------------------------------

    private String buildJoin(String userId, String accessToken)
    {
        JsonObject config = new JsonObject();
        com.google.gson.JsonArray changes = new com.google.gson.JsonArray();
        JsonObject filter = new JsonObject();
        filter.addProperty("event",  "UPDATE");
        filter.addProperty("schema", "public");
        filter.addProperty("table",  "profiles");
        filter.addProperty("filter", "user_id=eq." + userId);
        changes.add(filter);
        config.add("postgres_changes", changes);

        JsonObject payload = new JsonObject();
        payload.add("config", config);
        payload.addProperty("access_token", accessToken);

        JsonObject msg = new JsonObject();
        msg.addProperty("event",   "phx_join");
        msg.addProperty("topic",   "realtime:db-changes");
        msg.add("payload",         payload);
        msg.addProperty("ref",     String.valueOf(ref.getAndIncrement()));
        return gson.toJson(msg);
    }

    private String buildLeave()
    {
        JsonObject msg = new JsonObject();
        msg.addProperty("event",   "phx_leave");
        msg.addProperty("topic",   "realtime:db-changes");
        msg.add("payload",         new JsonObject());
        msg.addProperty("ref",     String.valueOf(ref.getAndIncrement()));
        return gson.toJson(msg);
    }

    private String buildHeartbeat()
    {
        JsonObject msg = new JsonObject();
        msg.addProperty("event",   "heartbeat");
        msg.addProperty("topic",   "phoenix");
        msg.add("payload",         new JsonObject());
        msg.addProperty("ref",     String.valueOf(ref.getAndIncrement()));
        return gson.toJson(msg);
    }

    // -------------------------------------------------------------------------
    // WebSocketListener
    // -------------------------------------------------------------------------

    private class Listener extends WebSocketListener
    {
        @Override
        public void onOpen(WebSocket webSocket, Response response)
        {
            log.debug("[RuneVault/Realtime] WebSocket opened. Joining channel...");
            synchronized (RealtimeClient.this)
            {
                if (currentUserId != null && currentToken != null)
                    webSocket.send(buildJoin(currentUserId, currentToken));
                startPhoenixHeartbeat(webSocket);
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text)
        {
            try
            {
                JsonObject msg     = new JsonParser().parse(text).getAsJsonObject();
                String     event   = msg.has("event")   ? msg.get("event").getAsString()   : "";
                String     topic   = msg.has("topic")   ? msg.get("topic").getAsString()   : "";
                JsonObject payload = msg.has("payload") ? msg.get("payload").getAsJsonObject() : new JsonObject();

                switch (event)
                {
                    case "phx_reply":
                        // Server ack on join — check status
                        if ("realtime:db-changes".equals(topic))
                        {
                            String status = payload.has("status")
                                ? payload.get("status").getAsString() : "";
                            if ("ok".equals(status))
                            {
                                log.debug("[RuneVault/Realtime] Channel joined successfully.");
                                backoffIndex.set(0); // reset backoff on successful join
                                // Check for disconnect events we missed while the socket was down
                                checkMissedDisconnect();
                            }
                            else
                            {
                                log.debug("[RuneVault/Realtime] Channel join failed: {}", payload);
                                // Token may be stale — SupabaseClient will refresh and call updateToken()
                            }
                        }
                        break;

                    case "postgres_changes":
                        // A profile row was updated — check if it's a disconnect request
                        handlePostgresChange(payload);
                        break;

                    case "phx_error":
                        log.debug("[RuneVault/Realtime] Phoenix error: {}", payload);
                        break;

                    case "phx_close":
                        log.debug("[RuneVault/Realtime] Phoenix closed channel.");
                        break;

                    default:
                        // heartbeat replies, system messages — ignore
                        break;
                }
            }
            catch (Exception e)
            {
                log.debug("[RuneVault/Realtime] Error parsing message: {}", e.getMessage());
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason)
        {
            log.debug("[RuneVault/Realtime] WebSocket closing: {} {}", code, reason);
            webSocket.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason)
        {
            log.debug("[RuneVault/Realtime] WebSocket closed: {} {}", code, reason);
            handleSocketGone(webSocket);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response)
        {
            log.debug("[RuneVault/Realtime] WebSocket failure: {}", t.getMessage());
            handleSocketGone(webSocket);
        }
    }

    // -------------------------------------------------------------------------
    // Internal — message handling & reconnect
    // -------------------------------------------------------------------------

    private void handlePostgresChange(JsonObject payload)
    {
        try
        {
            // Payload structure: { data: { type, table, record: { plugin_disconnect_requested, ... } } }
            if (!payload.has("data")) return;
            JsonObject data = payload.getAsJsonObject("data");
            if (!data.has("record")) return;
            JsonObject record = data.getAsJsonObject("record");
            if (!record.has("plugin_disconnect_requested")) return;

            boolean requested = record.get("plugin_disconnect_requested").getAsBoolean();
            if (requested)
            {
                log.debug("[RuneVault/Realtime] Disconnect requested via Realtime.");
                // Dispatch to executor — never mutate plugin state from the OkHttp WS thread
                executor.execute(onDisconnectRequested);
            }
        }
        catch (Exception e)
        {
            log.debug("[RuneVault/Realtime] Error handling postgres_changes: {}", e.getMessage());
        }
    }

    private synchronized void handleSocketGone(WebSocket socket)
    {
        // Only act on the socket we currently own — stale callbacks from old sockets are ignored
        if (socket != currentSocket) return;
        currentSocket = null;
        cancelPhoenixHeartbeat();
        if (!closed) scheduleReconnect();
    }
}
