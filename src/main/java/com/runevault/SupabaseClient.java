package com.runevault;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;

@Slf4j
public class SupabaseClient
{
    private static final String SUPABASE_URL = RuneVaultBuildConfig.SUPABASE_URL;
    private static final String ANON_KEY     = RuneVaultBuildConfig.SUPABASE_ANON_KEY;
    private static final String EDGE_URL     = SUPABASE_URL + "/functions/v1/ge-prices";
    private static final MediaType JSON      = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final RuneVaultConfig config;

    private volatile String cachedUserId    = null;
    private volatile String cachedProfileId = null;
    private volatile long   cachedCashTotal = -1; // -1 = not yet known (bank not opened)

    // Realtime WebSocket — replaces the 10s REST disconnect poll
    private RealtimeClient realtimeClient = null;

    // Set to true only after switchProfileForUsername() completes for the current session.
    // Prevents bank/inventory syncs from firing against the wrong profile during startup.
    private volatile boolean profileReady = false;

    public boolean isProfileReady() { return profileReady; }

    // Fired when the session is permanently lost (refresh token expired / revoked).
    // The plugin uses this to update the panel and show a chat message.
    private Runnable onSessionLost = null;

    public void setOnSessionLost(Runnable callback) { this.onSessionLost = callback; }

    public void setRealtimeClient(RealtimeClient client) { this.realtimeClient = client; }

    public SupabaseClient(OkHttpClient httpClient, Gson gson, RuneVaultConfig config)
    {
        this.httpClient = httpClient;
        this.gson       = gson;
        this.config     = config;
    }

    // -------------------------------------------------------------------------
    // Auth — link-code exchange
    // -------------------------------------------------------------------------

    /**
     * Exchange a 6-char link code (generated in the Rune Vault app) for a session.
     * Stores the access token, refresh token, and user ID in config.
     * Returns true on success.
     */
    public boolean exchangeLinkCode(String code)
    {
        JsonObject body = new JsonObject();
        body.addProperty("linkCode", code.toUpperCase().trim());

        Request request = new Request.Builder()
            .url(EDGE_URL)
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + ANON_KEY)
            .addHeader("Content-Type",  "application/json")
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                log("Link code exchange failed: HTTP " + response.code());
                return false;
            }

            JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
            if (json.has("error"))
            {
                log("Link code exchange error: " + json.get("error").getAsString());
                return false;
            }

            JsonElement accessTokenEl = json.get("accessToken");
            if (accessTokenEl == null || accessTokenEl.isJsonNull())
            {
                log("Link code exchange: missing accessToken in response");
                return false;
            }
            JsonElement refreshTokenEl = json.get("refreshToken");
            if (refreshTokenEl == null || refreshTokenEl.isJsonNull())
            {
                log("Link code exchange: missing refreshToken in response");
                return false;
            }
            JsonElement userIdEl = json.get("userId");
            if (userIdEl == null || userIdEl.isJsonNull())
            {
                log("Link code exchange: missing userId in response");
                return false;
            }
            String accessToken  = accessTokenEl.getAsString();
            String refreshToken = refreshTokenEl.getAsString();
            String userId       = userIdEl.getAsString();
            // profileId from the link code is intentionally ignored — the correct profile
            // is always resolved from the logged-in RS username via switchProfileForUsername().
            // This ensures bank syncs always target the right character, regardless of which
            // profile was active in the mobile app when the link code was generated.

            config.setAuthToken(accessToken);
            config.setRefreshToken(refreshToken);
            config.setLinkedUserId(userId);
            config.setLinkCode(""); // clear one-time code — it's been consumed

            cachedUserId    = userId;
            cachedProfileId = null; // remains null until switchProfileForUsername() resolves it
            profileReady    = false;

            // Reset plugin state so the app shows "Waiting for first heartbeat" instead
            // of "Plugin Inactive/Disconnected" from a stale previous session.
            // plugin_active = true fires later once the RS username is resolved.
            resetPluginStateForUser(userId);

            // Open Realtime subscription so disconnect requests arrive instantly
            if (realtimeClient != null)
                realtimeClient.connect(userId, accessToken);

            log("RuneLite plugin linked successfully. User: " + userId);
            return true;
        }
        catch (IOException e)
        {
            log("Link code exchange error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Restore session from stored tokens on plugin startup.
     * Extracts user ID from the JWT and loads the profile.
     */
    public void initFromStoredToken()
    {
        String token = config.authToken();
        if (token.isEmpty()) return;

        // If stored token is expired, try to get a new one before proceeding
        if (isTokenExpired(token))
        {
            log("Stored token expired — attempting silent refresh on startup...");
            if (!refreshAccessToken())
            {
                clearStoredCredentials();
                log("Session expired and refresh failed. Enter a new Link Code to re-link.");
                return;
            }
            token = config.authToken();
        }

        // Prefer persisted userId (avoids JWT decode)
        String userId = config.linkedUserId();
        if (userId.isEmpty())
        {
            userId = extractUserIdFromToken(token);
        }

        if (userId != null && !userId.isEmpty())
        {
            cachedUserId = userId;
            log("Session restored. User: " + cachedUserId);
            // Clear any stale disconnect flag so an immediate Realtime event or
            // missed-disconnect check doesn't self-disconnect right after startup.
            clearDisconnectFlagForUser(userId);

            // Open Realtime subscription — replaces the 10s REST disconnect poll
            final String tokenForRealtime = config.authToken();
            if (realtimeClient != null)
                realtimeClient.connect(userId, tokenForRealtime);

            // If we know the last-used profile from a previous session, use it immediately.
            // This allows heartbeating to the correct character before login completes,
            // rather than defaulting to whatever profile is first in the DB (wrong character).
            String lastProfileId = config.lastProfileId();
            if (!lastProfileId.isEmpty())
            {
                cachedProfileId = lastProfileId;
                profileReady = true; // safe — this is the profile we last verified
                log("Restored last profile: " + cachedProfileId);
                fetchAndCacheCash(); // pre-warm coin tracking
                setPluginActive(true); // show connected immediately in the app
            }
            else
            {
                fetchAndCacheProfile(); // first-run fallback
            }
        }
        else
        {
            log("Stored token could not be decoded — please re-link via the Rune Vault app.");
        }
    }

    /**
     * Use the stored refresh token to get a new access token.
     * Called automatically on 401. Returns true on success.
     */
    public boolean refreshAccessToken()
    {
        String refreshToken = config.refreshToken();
        if (refreshToken.isEmpty())
        {
            log("No refresh token stored — please re-link via the Rune Vault app.");
            return false;
        }

        JsonObject body = new JsonObject();
        body.addProperty("refresh_token", refreshToken);

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token")
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey", ANON_KEY)
            .addHeader("Content-Type", "application/json")
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                log("Token refresh failed: HTTP " + response.code());
                return false;
            }

            JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
            JsonElement newAccessTokenEl = json.get("access_token");
            if (newAccessTokenEl == null || newAccessTokenEl.isJsonNull())
            {
                log("Token refresh: missing access_token in response");
                return false;
            }
            JsonElement newRefreshTokenEl = json.get("refresh_token");
            if (newRefreshTokenEl == null || newRefreshTokenEl.isJsonNull())
            {
                log("Token refresh: missing refresh_token in response");
                return false;
            }
            String newAccessToken  = newAccessTokenEl.getAsString();
            String newRefreshToken = newRefreshTokenEl.getAsString();

            config.setAuthToken(newAccessToken);
            config.setRefreshToken(newRefreshToken);
            log("Access token refreshed successfully.");
            // Keep the Realtime channel authenticated with the new token
            if (realtimeClient != null)
                realtimeClient.updateToken(newAccessToken);
            return true;
        }
        catch (IOException e)
        {
            log("Token refresh error: " + e.getMessage());
            return false;
        }
    }

    public boolean isAuthenticated()
    {
        String token = config.authToken();
        return !token.isEmpty() && !isTokenExpired(token);
    }

    /**
     * Returns cachedProfileId if authenticated (refreshing the token if needed), null otherwise.
     * Synchronized so the null-check, token refresh, and ID capture are all atomic — callers
     * capture the return value into a local variable to avoid TOCTOU races with disconnect().
     */
    private synchronized String getAuthenticatedProfileId()
    {
        if (cachedProfileId == null || !ensureAuthenticated()) return null;
        return cachedProfileId;
    }

    /**
     * Returns true if we have a valid access token, silently refreshing it if expired.
     * Synchronized so concurrent sync calls don't all fire a refresh at once.
     */
    private synchronized boolean ensureAuthenticated()
    {
        if (isAuthenticated()) return true;
        log("Access token expired — attempting silent refresh...");
        boolean ok = refreshAccessToken();
        if (!ok)
        {
            log("Token refresh failed — session lost. Please re-link via the Rune Vault panel.");
            clearStoredCredentials();
            cachedCashTotal = -1;
            profileReady    = false;
            config.setLastProfileId("");
            config.setConnectionStatus("Session expired — re-link in Settings");
            if (onSessionLost != null) onSessionLost.run();
        }
        return ok;
    }

    private boolean isTokenExpired(String token)
    {
        try
        {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return true;
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            JsonObject json = new JsonParser().parse(payload).getAsJsonObject();
            long exp = json.get("exp").getAsLong();
            return System.currentTimeMillis() / 1000L >= exp - 30; // 30s buffer
        }
        catch (Exception e)
        {
            return true; // treat unparseable token as expired
        }
    }

    public boolean hasProfile()
    {
        return cachedProfileId != null;
    }

    /** Clear all stored credentials and cached state. */
    public void disconnect()
    {
        profileReady = false;
        // Build a full explicit-disconnect patch: clear active, stamp last_seen=null so the app
        // knows this is a real disconnect (not just a character logout), and set the disconnect
        // flag so the app collapses the plugin panel immediately.
        if (cachedUserId != null && isAuthenticated())
        {
            JsonObject body = new JsonObject();
            body.addProperty("plugin_active", false);
            body.addProperty("plugin_disconnect_requested", true);
            body.add("plugin_last_seen", com.google.gson.JsonNull.INSTANCE); // nulls last_seen → not 'idle'
            String url = cachedProfileId != null
                ? SUPABASE_URL + "/rest/v1/profiles?id=eq." + cachedProfileId
                : SUPABASE_URL + "/rest/v1/profiles?user_id=eq." + cachedUserId;
            Request request = new Request.Builder()
                .url(url)
                .patch(RequestBody.create(JSON, gson.toJson(body)))
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer " + config.authToken())
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build();
            // Synchronous — must complete before credentials are cleared and before any
            // subsequent exchangeLinkCode() call runs. Both are queued on the same
            // single-thread executor, so making this sync guarantees ordering:
            // old disconnect lands in DB → then new link code exchange starts.
            // If async, the stale PATCH can arrive after resetPluginStateForUser() and
            // re-set plugin_disconnect_requested=true, causing an immediate auto-disconnect.
            try (Response r = httpClient.newCall(request).execute()) {
                if (!r.isSuccessful()) log("disconnectByUser failed: HTTP " + r.code());
                else                   log("disconnectByUser OK");
            } catch (IOException e) {
                log("disconnectByUser error: " + e.getMessage());
            }
        }
        // Close the Realtime WebSocket — no more disconnect events needed
        if (realtimeClient != null) realtimeClient.close();
        clearStoredCredentials();
        cachedUserId    = null;
        cachedProfileId = null;
        cachedCashTotal = -1;
        profileReady    = false;
        config.setLinkCode("");
        config.setLastProfileId(""); // clear so next link starts fresh
        config.setConnectionStatus("Not connected");
        log("Disconnected from Rune Vault.");
    }

    /**
     * Set plugin_active on the profile row. Called on connect (true) and disconnect (false).
     * Synchronous so the flag is written before credentials are cleared on disconnect.
     */
    public void setPluginActive(boolean active)
    {
        if (cachedProfileId == null || !ensureAuthenticated()) return;

        // When activating, clear plugin_active on all other profiles first so the green dot
        // never appears on the wrong character in the app.
        if (active && cachedUserId != null)
        {
            JsonObject clearBody = new JsonObject();
            clearBody.addProperty("plugin_active", false);
            Request clearRequest = new Request.Builder()
                .url(SUPABASE_URL + "/rest/v1/profiles?user_id=eq." + cachedUserId + "&id=neq." + cachedProfileId)
                .patch(RequestBody.create(JSON, gson.toJson(clearBody)))
                .addHeader("apikey",        ANON_KEY)
                .addHeader("Authorization", "Bearer " + config.authToken())
                .addHeader("Content-Type",  "application/json")
                .addHeader("Prefer",        "return=minimal")
                .build();
            executeAsync(clearRequest, "clearOtherActiveProfiles");
        }

        JsonObject body = new JsonObject();
        body.addProperty("plugin_active", active);
        if (active) {
            body.addProperty("plugin_last_seen", java.time.Instant.now().toString());
            // Clear any stale disconnect flag so a restart doesn't immediately re-trigger
            body.addProperty("plugin_disconnect_requested", false);
        }

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?id=eq." + cachedProfileId)
            .patch(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=minimal")
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
                log("setPluginActive(" + active + ") failed: HTTP " + response.code());
        }
        catch (IOException e)
        {
            log("setPluginActive error: " + e.getMessage());
        }
    }

    /**
     * Lightweight heartbeat — updates plugin_last_seen so the app can detect
     * crashes vs explicit disconnects. Called every 2 minutes by the plugin.
     */
    /**
     * Called when the player logs out to the login screen.
     * Clears plugin_active so the green dot disappears in the app immediately,
     * and stops heartbeats until the player logs back in.
     */
    public void onPlayerLogout()
    {
        profileReady = false; // stop heartbeats — no character is logged in
        final String profileId = cachedProfileId;
        if (profileId == null || !isAuthenticated()) return;

        // Set plugin_active=false AND stamp plugin_last_seen=now so the app can
        // distinguish "character logged out" (recent last_seen) from
        // "plugin crashed/disconnected" (stale last_seen).
        JsonObject body = new JsonObject();
        body.addProperty("plugin_active",    false);
        body.addProperty("plugin_last_seen", java.time.Instant.now().toString());

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?id=eq." + profileId)
            .patch(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=minimal")
            .build();

        executeAsync(request, "playerLogout");
    }

    public void sendHeartbeat()
    {
        // If logged out but still authenticated with a known profile, send a keep-alive
        // so plugin_last_seen stays fresh and the app shows "Character Logged Out"
        // instead of "Plugin Disconnected" after the 10-minute staleness threshold.
        if (!profileReady)
        {
            final String profileId = cachedProfileId;
            if (profileId == null || !ensureAuthenticated()) return;
            JsonObject body = new JsonObject();
            body.addProperty("plugin_last_seen", java.time.Instant.now().toString());
            Request request = new Request.Builder()
                .url(SUPABASE_URL + "/rest/v1/profiles?id=eq." + profileId)
                .patch(RequestBody.create(JSON, gson.toJson(body)))
                .addHeader("apikey",        ANON_KEY)
                .addHeader("Authorization", "Bearer " + config.authToken())
                .addHeader("Content-Type",  "application/json")
                .addHeader("Prefer",        "return=minimal")
                .build();
            executeAsync(request, "heartbeat-idle");
            return;
        }

        // Only heartbeat plugin_active=true after switchProfileForUsername has confirmed
        // the correct character — prevents heartbeating the wrong profile on startup.
        final String profileId = getAuthenticatedProfileId();
        if (profileId == null) return;

        JsonObject body = new JsonObject();
        body.addProperty("plugin_active",    true);
        body.addProperty("plugin_last_seen", java.time.Instant.now().toString());

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?id=eq." + profileId)
            .patch(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=minimal")
            .build();

        executeAsync(request, "heartbeat");
    }

    /**
     * Polls plugin_disconnect_requested on the profile row.
     * Returns true if the app has requested a remote disconnect, false otherwise.
     * If true, also clears the flag on the server so it doesn't re-trigger.
     */
    public boolean checkRemoteDisconnect()
    {
        // Always check by user_id — the app patches all profiles for the user on disconnect,
        // so checking by cachedProfileId would miss the flag if the active profile differs.
        if (cachedUserId == null || !isAuthenticated()) return false;
        final String filterClause = "user_id=eq." + cachedUserId;

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?" + filterClause
                + "&select=plugin_disconnect_requested")
            .get()
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Accept",        "application/json")
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null) return false;
            String body = response.body().string();
            com.google.gson.JsonArray arr = new JsonParser().parse(body).getAsJsonArray();
            if (arr.size() == 0) return false;
            // Check if ANY profile has the flag set (user may have multiple profiles)
            boolean requested = false;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject row = arr.get(i).getAsJsonObject();
                if (row.has("plugin_disconnect_requested")
                        && row.get("plugin_disconnect_requested").getAsBoolean()) {
                    requested = true;
                    break;
                }
            }
            if (!requested) return false;

            // Clear the flag immediately so it doesn't fire again on next poll
            JsonObject clear = new JsonObject();
            clear.addProperty("plugin_disconnect_requested", false);
            Request clearReq = new Request.Builder()
                .url(SUPABASE_URL + "/rest/v1/profiles?" + filterClause)
                .patch(RequestBody.create(JSON, gson.toJson(clear)))
                .addHeader("apikey",        ANON_KEY)
                .addHeader("Authorization", "Bearer " + config.authToken())
                .addHeader("Content-Type",  "application/json")
                .addHeader("Prefer",        "return=minimal")
                .build();
            try (Response clearResp = httpClient.newCall(clearReq).execute()) { /* fire and forget */ }

            return true;
        }
        catch (IOException e)
        {
            log("checkRemoteDisconnect error: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Portfolio items
    // -------------------------------------------------------------------------

    /**
     * Upsert a GE-purchased item with weighted average cost.
     */
    public void upsertItem(PortfolioItem item)
    {
        upsertItem(item, 2);
    }

    private void upsertItem(PortfolioItem item, int retriesLeft)
    {
        if (!ensureAuthenticated()) return;
        if (!hasProfile()) { log("upsertItem skipped — no profile loaded yet"); return; }

        Request fetchRequest = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items"
                + "?user_id=eq." + getUserId()
                + "&profile_id=eq." + cachedProfileId
                + "&item_id=eq." + item.getItemId()
                + "&game=eq.osrs"
                + "&select=id,quantity,buy_price")
            .get()
            .addHeader("apikey", ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .build();

        httpClient.newCall(fetchRequest).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                if (retriesLeft > 0)
                {
                    int attempt = 3 - retriesLeft;
                    log("upsertItem fetch error (attempt " + attempt + "): " + e.getMessage() + " — retrying...");
                    try { Thread.sleep(attempt * 1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    upsertItem(item, retriesLeft - 1);
                }
                else
                {
                    log("upsertItem fetch failed after all retries: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try {
                    if (!response.isSuccessful() || response.body() == null)
                    {
                        doUpsertItem(item, item.getQuantity(), item.getBuyPrice());
                        return;
                    }

                    JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);
                    if (rows.size() == 0)
                    {
                        doUpsertItem(item, item.getQuantity(), item.getBuyPrice());
                    }
                    else
                    {
                        JsonObject row      = rows.get(0).getAsJsonObject();
                        long existingQty    = row.get("quantity").getAsLong();
                        long existingPrice  = row.get("buy_price").getAsLong();
                        long newQty         = existingQty + item.getQuantity();
                        long avgPrice       = ((long) existingQty * existingPrice + (long) item.getQuantity() * item.getBuyPrice()) / newQty;
                        doUpsertItem(item, newQty, avgPrice);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private void doUpsertItem(PortfolioItem item, long quantity, long buyPrice)
    {
        String nowIso = java.time.Instant.now().toString();
        long nowMs    = System.currentTimeMillis();

        JsonObject body = new JsonObject();
        body.addProperty("id",            java.util.UUID.randomUUID().toString());
        body.addProperty("user_id",       getUserId());
        body.addProperty("profile_id",    cachedProfileId);
        body.addProperty("item_id",       item.getItemId());
        body.addProperty("item_name",     item.getItemName());
        body.addProperty("game",          "osrs");
        body.addProperty("quantity",      quantity);
        body.addProperty("buy_price",     buyPrice);
        body.addProperty("buy_date",      nowIso);
        body.addProperty("last_added_at", nowMs);
        body.addProperty("watchlisted",   false);
        body.addProperty("source",        "purchase");
        if (item.getImageUrl() != null) body.addProperty("image_url", item.getImageUrl());

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items?on_conflict=user_id,profile_id,item_id,game,source")
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",         ANON_KEY)
            .addHeader("Authorization",  "Bearer " + config.authToken())
            .addHeader("Content-Type",   "application/json")
            .addHeader("Prefer",         "resolution=merge-duplicates,return=minimal")
            .build();

        executeAsync(request, "upsertItem(" + item.getItemName() + ")");
    }

    /**
     * Decrement quantity of an item. Deletes row if quantity reaches 0.
     */
    public void decrementItem(int itemId, int quantityToRemove)
    {
        decrementItem(itemId, quantityToRemove, 2);
    }

    private void decrementItem(int itemId, int quantityToRemove, int retriesLeft)
    {
        if (!ensureAuthenticated()) return;

        Request fetchRequest = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items"
                + "?user_id=eq." + getUserId()
                + "&profile_id=eq." + cachedProfileId
                + "&item_id=eq." + itemId
                + "&game=eq.osrs"
                + "&select=id,quantity")
            .get()
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .build();

        httpClient.newCall(fetchRequest).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                if (retriesLeft > 0)
                {
                    int attempt = 3 - retriesLeft;
                    log("decrementItem fetch error (attempt " + attempt + "): " + e.getMessage() + " — retrying...");
                    try { Thread.sleep(attempt * 1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    decrementItem(itemId, quantityToRemove, retriesLeft - 1);
                }
                else
                {
                    log("decrementItem fetch failed after all retries: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try {
                    if (!response.isSuccessful() || response.body() == null) return;

                    JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);
                    if (rows.size() == 0) return;

                    JsonObject row   = rows.get(0).getAsJsonObject();
                    String rowId     = row.get("id").getAsString();
                    int currentQty   = row.get("quantity").getAsInt();
                    int newQty       = currentQty - quantityToRemove;

                    if (newQty <= 0) deleteItem(rowId);
                    else             updateQuantity(rowId, newQty);
                } finally {
                    response.close();
                }
            }
        });
    }

    private void updateQuantity(String rowId, int newQuantity)
    {
        JsonObject body = new JsonObject();
        body.addProperty("quantity", newQuantity);

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items?id=eq." + rowId)
            .patch(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=minimal")
            .build();

        executeAsync(request, "updateQuantity");
    }

    private void deleteItem(String rowId)
    {
        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items?id=eq." + rowId)
            .delete()
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .build();

        executeAsync(request, "deleteItem");
    }

    public void updateCash(long amount)
    {
        cachedCashTotal = amount;
        PortfolioItem coins = new PortfolioItem(995, "Coins", (int) amount, 1, null, 0);
        doUpsertItem(coins, (int) amount, 1);
    }

    /**
     * Apply a delta to the cached cash total (e.g. -100k for a drop).
     * Only acts if we have a cached total from a bank scan — avoids corrupting
     * the value before the bank has been opened at least once.
     */
    public void adjustCash(long delta)
    {
        if (cachedCashTotal < 0) return; // not yet known — skip
        long newTotal = Math.max(0, cachedCashTotal + delta);
        updateCash(newTotal);
    }

    // -------------------------------------------------------------------------
    // Bank scan — bulk upsert
    // -------------------------------------------------------------------------

    /**
     * Bulk upsert items with an explicit source and an optional callback.
     * The callback runs on the OkHttp thread after the request succeeds — use it to
     * chain dependent operations (e.g. removeItemsMissingFromBank) so they only run
     * after the upsert has landed in the DB, avoiding race conditions.
     */
    public void bulkUpsertItems(java.util.List<PortfolioItem> items, String source, Runnable onSuccess)
    {
        if (!ensureAuthenticated() || items.isEmpty()) return;
        if (!hasProfile()) { log("bulkUpsert skipped — profile not loaded yet"); return; }

        String userId = getUserId();

        JsonArray body = new JsonArray();
        for (PortfolioItem item : items)
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("id",         java.util.UUID.randomUUID().toString());
            obj.addProperty("user_id",    userId);
            obj.addProperty("profile_id", cachedProfileId);
            obj.addProperty("item_id",    item.getItemId());
            obj.addProperty("item_name",  item.getItemName());
            obj.addProperty("game",       "osrs");
            obj.addProperty("quantity",   item.getQuantity());
            obj.addProperty("buy_price",  item.getBuyPrice());
            obj.addProperty("ha_price",   item.getHaPrice());
            obj.addProperty("watchlisted", false);
            obj.addProperty("source",     source);
            // Intentionally omitting last_added_at and buy_date — bank scans should not
            // update these fields on existing rows, keeping GE/pickup timestamps intact.
            if (item.getImageUrl() != null) obj.addProperty("image_url", item.getImageUrl());
            body.add(obj);
        }

        // Equipment snapshots always overwrite (quantity and price may change on re-equip).
        // Bank scans respect the bankOverwriteDuplicates config.
        String prefer;
        if ("runelite_equip".equals(source)) {
            prefer = "resolution=merge-duplicates,return=minimal";
        } else {
            prefer = config.bankOverwriteDuplicates()
                ? "resolution=merge-duplicates,return=minimal"
                : "resolution=ignore-duplicates,return=minimal";
        }

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items?on_conflict=user_id,profile_id,item_id,game,source")
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        prefer)
            .build();

        executeAsync(request, "bulkUpsert(" + items.size() + " items, source=" + source + ")", onSuccess);
    }

    /**
     * Removes all equipment-snapshot items for the current profile.
     * Called on player logout so stale equipped items don't persist between sessions.
     */
    public void clearEquipmentItems()
    {
        if (!ensureAuthenticated() || !hasProfile()) return;
        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items"
                + "?user_id=eq." + getUserId()
                + "&profile_id=eq." + cachedProfileId
                + "&source=eq.runelite_equip")
            .delete()
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Prefer",        "return=minimal")
            .build();
        executeAsync(request, "clearEquipmentItems");
    }

    public void removeItemsMissingFromBank(java.util.Set<Integer> bankItemIds)
    {
        removeItemsMissingFromBank(bankItemIds, 2);
    }

    private void removeItemsMissingFromBank(java.util.Set<Integer> bankItemIds, int retriesLeft)
    {
        if (!ensureAuthenticated() || !hasProfile()) return;

        Request fetchRequest = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items"
                + "?user_id=eq." + getUserId()
                + "&profile_id=eq." + cachedProfileId
                + "&source=eq.runelite"
                + "&game=eq.osrs"
                + "&select=id,item_id")
            .get()
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .build();

        httpClient.newCall(fetchRequest).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                if (retriesLeft > 0)
                {
                    int attempt = 3 - retriesLeft;
                    log("removeItemsMissingFromBank error (attempt " + attempt + "): " + e.getMessage() + " — retrying...");
                    try { Thread.sleep(attempt * 1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    removeItemsMissingFromBank(bankItemIds, retriesLeft - 1);
                }
                else
                {
                    log("removeItemsMissingFromBank failed after all retries: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try {
                    if (!response.isSuccessful() || response.body() == null) return;
                    JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);

                    log.info("[RuneVault][RemoveMissing] DB rows fetched={}, bankItemIds size={}",
                        rows.size(), bankItemIds.size());

                    java.util.List<String> missingIds = new java.util.ArrayList<>();
                    for (int i = 0; i < rows.size(); i++)
                    {
                        JsonObject row = rows.get(i).getAsJsonObject();
                        int itemId     = row.get("item_id").getAsInt();
                        if (!bankItemIds.contains(itemId))
                        {
                            missingIds.add(row.get("id").getAsString());
                            log.info("[RuneVault][RemoveMissing] DELETE item_id={} (not in current bank scan)", itemId);
                        }
                    }

                    if (!missingIds.isEmpty())
                    {
                        // Single batch DELETE using Supabase `in` filter
                        String idList = String.join(",", missingIds);
                        Request deleteRequest = new Request.Builder()
                            .url(SUPABASE_URL + "/rest/v1/portfolio_items?id=in.(" + idList + ")")
                            .delete()
                            .addHeader("apikey",        ANON_KEY)
                            .addHeader("Authorization", "Bearer " + config.authToken())
                            .addHeader("Prefer",        "return=minimal")
                            .build();
                        executeAsync(deleteRequest, "removeItemsMissingFromBank(" + missingIds.size() + " items)");
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Called on GameState.LOGGED_IN with the RS character's username.
     * Finds an existing profile whose name matches, or creates a new one.
     * Updates cachedProfileId so all subsequent syncs target the right character.
     */
    public void switchProfileForUsername(String rsUsername)
    {
        if (cachedUserId == null || rsUsername == null || rsUsername.isEmpty()) return;
        if (!ensureAuthenticated()) return; // refresh expired token before any HTTP calls
        profileReady = false; // block bank/inventory syncs until correct profile is resolved

        String encoded;
        try { encoded = java.net.URLEncoder.encode(rsUsername, "UTF-8"); }
        catch (Exception e) { encoded = rsUsername; }

        // Two-query approach: prefer rs_username match (exact character) over name match.
        // This prevents selecting a ghost profile whose name happens to equal the RS username
        // when a correctly-tagged profile (rs_username=rsUsername) also exists.
        String resolvedProfileId = null;
        try
        {
            // Query 1: rs_username match (e.g. profile xxxxxxxx with rs_username=myusername)
            Request rsRequest = new Request.Builder()
                .url(SUPABASE_URL + "/rest/v1/profiles?user_id=eq." + cachedUserId
                    + "&rs_username=ilike." + encoded
                    + "&select=id,name&limit=1")
                .get()
                .addHeader("apikey",        ANON_KEY)
                .addHeader("Authorization", "Bearer " + config.authToken())
                .build();

            try (Response rsResponse = httpClient.newCall(rsRequest).execute())
            {
                if (rsResponse.isSuccessful() && rsResponse.body() != null)
                {
                    JsonArray rows = gson.fromJson(rsResponse.body().string(), JsonArray.class);
                    if (rows.size() > 0)
                        resolvedProfileId = rows.get(0).getAsJsonObject().get("id").getAsString();
                }
            }

            // Query 2: name match fallback (only if rs_username query found nothing)
            if (resolvedProfileId == null)
            {
                Request nameRequest = new Request.Builder()
                    .url(SUPABASE_URL + "/rest/v1/profiles?user_id=eq." + cachedUserId
                        + "&name=ilike." + encoded
                        + "&select=id,name&limit=1")
                    .get()
                    .addHeader("apikey",        ANON_KEY)
                    .addHeader("Authorization", "Bearer " + config.authToken())
                    .build();

                try (Response nameResponse = httpClient.newCall(nameRequest).execute())
                {
                    if (nameResponse.isSuccessful() && nameResponse.body() != null)
                    {
                        JsonArray rows = gson.fromJson(nameResponse.body().string(), JsonArray.class);
                        if (rows.size() > 0)
                            resolvedProfileId = rows.get(0).getAsJsonObject().get("id").getAsString();
                    }
                }
            }
        }
        catch (IOException e)
        {
            log("switchProfile error: " + e.getMessage());
            return;
        }

        if (resolvedProfileId != null)
        {
            if (!resolvedProfileId.equals(cachedProfileId))
                log("Switched to profile for " + rsUsername + " (" + resolvedProfileId + ")");
            cachedProfileId = resolvedProfileId;
            config.setLastProfileId(resolvedProfileId); // persist for startup heartbeating
            markPluginVerified();
        }
        else
        {
            log("No profile found for " + rsUsername + " — creating one.");
            createProfileForUsername(rsUsername);
        }

        if (cachedProfileId != null)
        {
            profileReady = true;
            setPluginActive(true); // now that we have the correct profile, mark as active in the app
        }
    }

    private void createProfileForUsername(String rsUsername)
    {
        JsonObject body = new JsonObject();
        body.addProperty("id",          java.util.UUID.randomUUID().toString());
        body.addProperty("user_id",     cachedUserId);
        body.addProperty("name",        rsUsername);
        body.addProperty("rs_username", rsUsername);
        body.addProperty("game",        "osrs");
        body.addProperty("color",       "#f0c040");

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles")
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=representation")
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                log("createProfile failed: HTTP " + response.code());
                return;
            }

            JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);
            if (rows.size() > 0)
            {
                cachedProfileId = rows.get(0).getAsJsonObject().get("id").getAsString();
                log("Created new profile for " + rsUsername + " (" + cachedProfileId + ")");
            }
        }
        catch (IOException e)
        {
            log("createProfile error: " + e.getMessage());
        }
        // New profile created by the plugin — verified by definition
        markPluginVerified();
    }

    /**
     * Mark this profile as plugin_verified = true.
     * Called whenever the plugin successfully connects to an RS character's profile.
     * This is the proof-of-ownership signal used by the public portfolio web page.
     */
    private void markPluginVerified()
    {
        if (cachedProfileId == null || !isAuthenticated()) return;

        JsonObject body = new JsonObject();
        body.addProperty("plugin_verified", true);

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?id=eq." + cachedProfileId)
            .patch(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=minimal")
            .build();

        executeAsync(request, "markPluginVerified");
    }

    /**
     * Set is_public on the current profile. Called when the user toggles
     * the Public Profile switch in the RuneLite panel.
     */
    public void setPublicProfile(boolean isPublic)
    {
        if (cachedProfileId == null || !isAuthenticated()) return;

        JsonObject body = new JsonObject();
        body.addProperty("is_public", isPublic);

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?id=eq." + cachedProfileId)
            .patch(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=minimal")
            .build();

        executeAsync(request, "setPublicProfile(" + isPublic + ")");
    }

    public void fetchAndCacheProfile()
    {
        fetchAndCacheProfile(0);
    }

    private void fetchAndCacheProfile(int retryCount)
    {
        if (retryCount >= 2)
        {
            log.warn("fetchAndCacheProfile: max retries reached");
            return;
        }
        if (cachedUserId == null) return;

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?user_id=eq." + cachedUserId + "&select=id,name&limit=1")
            .get()
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .build();

        // Synchronous — called on startup so profile is ready before any events fire
        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.code() == 401)
            {
                response.close();
                log("fetchProfile: token expired, attempting refresh...");
                if (!refreshAccessToken())
                {
                    // No refresh token or refresh failed — clear dead credentials so
                    // the user can re-link immediately by entering a new code.
                    clearStoredCredentials();
                    log("fetchProfile: session expired. Enter a new Link Code and check Connect to re-link.");
                    return;
                }
                // Retry with the new token
                fetchAndCacheProfile(retryCount + 1);
                return;
            }
            if (!response.isSuccessful() || response.body() == null)
            {
                log("fetchProfile failed: HTTP " + response.code());
                return;
            }
            JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);
            if (rows.size() > 0)
            {
                JsonObject row = rows.get(0).getAsJsonObject();
                cachedProfileId = row.get("id").getAsString();
                String name     = row.has("name") ? row.get("name").getAsString() : cachedProfileId;
                log("Using existing profile: " + name + " (" + cachedProfileId + ")");
            }
            else
            {
                log("No profile found — create one in the Rune Vault app first.");
            }

            // Pre-warm cash cache so coin drops/pickups work before bank is opened
            if (cachedProfileId != null) fetchAndCacheCash();
        }
        catch (IOException e)
        {
            log("fetchProfile error: " + e.getMessage());
        }
    }

    private void fetchAndCacheCash()
    {
        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items"
                + "?user_id=eq." + getUserId()
                + "&profile_id=eq." + cachedProfileId
                + "&item_id=eq.995"
                + "&game=eq.osrs"
                + "&select=quantity"
                + "&limit=1")
            .get()
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null) return;
            JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);
            if (rows.size() > 0)
            {
                cachedCashTotal = rows.get(0).getAsJsonObject().get("quantity").getAsLong();
                log("Cash cache initialized: " + cachedCashTotal + " gp");
            }
        }
        catch (IOException e) { /* non-critical, will initialize on next bank open */ }
    }

    /**
     * Clears only the disconnect flag on startup (token restore).
     * Does NOT touch plugin_active — switchProfileForUsername will set that correctly.
     */
    private void clearDisconnectFlagForUser(String userId)
    {
        JsonObject body = new JsonObject();
        body.addProperty("plugin_disconnect_requested", false);

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?user_id=eq." + userId)
            .patch(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=minimal")
            .build();

        executeAsync(request, "clearDisconnectFlag");
    }

    /**
     * Called on new link code exchange — resets plugin_active and last_seen so the app
     * shows "Waiting for first heartbeat" instead of "Plugin Inactive" for a stale session.
     */
    private void resetPluginStateForUser(String userId)
    {
        JsonObject body = new JsonObject();
        body.addProperty("plugin_disconnect_requested", false);
        body.addProperty("plugin_active", false);
        body.add("plugin_last_seen", com.google.gson.JsonNull.INSTANCE);

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?user_id=eq." + userId)
            .patch(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=minimal")
            .build();

        // Synchronous — must complete before checkRemoteDisconnect() polls to avoid an
        // immediate auto-disconnect (race: async PATCH hasn't landed when poll fires).
        try (Response r = httpClient.newCall(request).execute()) {
            if (!r.isSuccessful()) log("resetPluginState failed: HTTP " + r.code());
            else                   log("resetPluginState OK");
        } catch (IOException e) {
            log("resetPluginState exception: " + e.getMessage());
        }
    }

    private void clearStoredCredentials()
    {
        config.setAuthToken("");
        config.setRefreshToken("");
        config.setLinkedUserId("");
        cachedUserId    = null;
        cachedProfileId = null;
    }

    private String extractUserIdFromToken(String token)
    {
        try
        {
            String[] parts  = token.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded  = java.util.Base64.getUrlDecoder().decode(parts[1]);
            JsonObject payload = gson.fromJson(
                new String(decoded, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
            return payload.get("sub").getAsString();
        }
        catch (Exception e) { return null; }
    }

    private String getUserId()
    {
        return cachedUserId != null ? cachedUserId : "";
    }

    private void executeAsync(Request request, String label)
    {
        executeAsync(request, label, null);
    }

    private void executeAsync(Request request, String label, Runnable onSuccess)
    {
        executeAsync(request, label, onSuccess, 2);
    }

    private void executeAsync(Request request, String label, Runnable onSuccess, int retriesLeft)
    {
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                if (retriesLeft > 0)
                {
                    int attempt = 3 - retriesLeft;
                    log(label + " failed (attempt " + attempt + "): " + e.getMessage() + " — retrying...");
                    try { Thread.sleep(attempt * 1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    executeAsync(request, label, onSuccess, retriesLeft - 1);
                }
                else
                {
                    log(label + " failed after all retries: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                if (response.code() == 401)
                {
                    log(label + " — token expired, refreshing...");
                    response.close();
                    if (refreshAccessToken())
                    {
                        // Retry with the new token
                        Request retried = request.newBuilder()
                            .removeHeader("Authorization")
                            .addHeader("Authorization", "Bearer " + config.authToken())
                            .build();
                        httpClient.newCall(retried).enqueue(new Callback()
                        {
                            @Override public void onFailure(Call c, IOException e) { log(label + " retry failed: " + e.getMessage()); }
                            @Override public void onResponse(Call c, Response r)   {
                                log(label + (r.isSuccessful() ? " retry OK" : " retry HTTP " + r.code()));
                                if (r.isSuccessful() && onSuccess != null) onSuccess.run();
                                r.close();
                            }
                        });
                    }
                    return;
                }
                if (!response.isSuccessful()) {
                    String errorBody = "";
                    try { if (response.body() != null) errorBody = " — " + response.body().string(); } catch (IOException ignored) {}
                    log(label + " HTTP error: " + response.code() + errorBody);
                } else {
                    log(label + " OK");
                    if (onSuccess != null) onSuccess.run();
                }
                response.close();
            }
        });
    }

    private void log(String message)
    {
        if (config.debugLogging())
        {
            log.info("[RuneVault] " + message);
        }
    }
}
