package com.runevault;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;

@Slf4j
public class SupabaseClient
{
    private static final String SUPABASE_URL = "https://SUPABASE_PROJECT_URL_REDACTED.supabase.co";
    private static final String ANON_KEY     = "SUPABASE_ANON_KEY_REDACTED";
    private static final String EDGE_URL     = SUPABASE_URL + "/functions/v1/ge-prices";
    private static final MediaType JSON      = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final RuneVaultConfig config;

    private String cachedUserId    = null;
    private String cachedProfileId = null;
    private long   cachedCashTotal = -1; // -1 = not yet known (bank not opened)

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

            String accessToken  = json.get("accessToken").getAsString();
            String refreshToken = json.get("refreshToken").getAsString();
            String userId       = json.get("userId").getAsString();
            String profileId    = json.has("profileId") && !json.get("profileId").isJsonNull()
                ? json.get("profileId").getAsString() : null;

            config.setAuthToken(accessToken);
            config.setRefreshToken(refreshToken);
            config.setLinkedUserId(userId);
            config.setLinkCode(""); // clear one-time code — it's been consumed

            cachedUserId   = userId;
            cachedProfileId = profileId;

            if (cachedProfileId == null)
            {
                fetchAndCacheProfile();
            }

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
            fetchAndCacheProfile();
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
            String newAccessToken  = json.get("access_token").getAsString();
            String newRefreshToken = json.get("refresh_token").getAsString();

            config.setAuthToken(newAccessToken);
            config.setRefreshToken(newRefreshToken);
            log("Access token refreshed successfully.");
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
        return !config.authToken().isEmpty();
    }

    public boolean hasProfile()
    {
        return cachedProfileId != null;
    }

    /** Clear all stored credentials and cached state. */
    public void disconnect()
    {
        setPluginActive(false); // tell the app before wiping the token
        clearStoredCredentials();
        config.setLinkCode("");
        config.setConnectionStatus("Not connected");
        log("Disconnected from Rune Vault.");
    }

    /**
     * Set plugin_active on the profile row. Called on connect (true) and disconnect (false).
     * Synchronous so the flag is written before credentials are cleared on disconnect.
     */
    public void setPluginActive(boolean active)
    {
        if (cachedProfileId == null || !isAuthenticated()) return;

        JsonObject body = new JsonObject();
        body.addProperty("plugin_active", active);
        if (active) body.addProperty("plugin_last_seen",
            java.time.Instant.now().toString());

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
    public void sendHeartbeat()
    {
        if (cachedProfileId == null || !isAuthenticated()) return;

        JsonObject body = new JsonObject();
        body.addProperty("plugin_active",    true);
        body.addProperty("plugin_last_seen", java.time.Instant.now().toString());

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?id=eq." + cachedProfileId)
            .patch(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        "return=minimal")
            .build();

        executeAsync(request, "heartbeat");
    }

    // -------------------------------------------------------------------------
    // Portfolio items
    // -------------------------------------------------------------------------

    /**
     * Upsert a GE-purchased item with weighted average cost.
     */
    public void upsertItem(PortfolioItem item)
    {
        if (!isAuthenticated()) return;
        if (!hasProfile()) { log("upsertItem skipped — no profile loaded yet"); return; }

        Request fetchRequest = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items"
                + "?user_id=eq." + getUserId()
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
                log("upsertItem fetch error: " + e.getMessage());
                doUpsertItem(item, item.getQuantity(), item.getBuyPrice());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                if (!response.isSuccessful() || response.body() == null)
                {
                    doUpsertItem(item, item.getQuantity(), item.getBuyPrice());
                    return;
                }

                JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);
                response.close();
                if (rows.size() == 0)
                {
                    doUpsertItem(item, item.getQuantity(), item.getBuyPrice());
                }
                else
                {
                    JsonObject row      = rows.get(0).getAsJsonObject();
                    int existingQty     = row.get("quantity").getAsInt();
                    long existingPrice  = row.get("buy_price").getAsLong();
                    int newQty          = existingQty + item.getQuantity();
                    long avgPrice       = (existingQty * existingPrice + (long) item.getQuantity() * item.getBuyPrice()) / newQty;
                    doUpsertItem(item, newQty, avgPrice);
                }
            }
        });
    }

    private void doUpsertItem(PortfolioItem item, int quantity, long buyPrice)
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
            .url(SUPABASE_URL + "/rest/v1/portfolio_items?on_conflict=user_id,item_id,game")
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
        if (!isAuthenticated()) return;

        Request fetchRequest = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items"
                + "?user_id=eq." + getUserId()
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
                log("decrementItem fetch error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                if (!response.isSuccessful() || response.body() == null) return;

                JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);
                response.close();
                if (rows.size() == 0) return;

                JsonObject row   = rows.get(0).getAsJsonObject();
                String rowId     = row.get("id").getAsString();
                int currentQty   = row.get("quantity").getAsInt();
                int newQty       = currentQty - quantityToRemove;

                if (newQty <= 0) deleteItem(rowId);
                else             updateQuantity(rowId, newQty);
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
        PortfolioItem coins = new PortfolioItem(995, "Coins", (int) amount, 1, null);
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

    public void bulkUpsertItems(java.util.List<PortfolioItem> items)
    {
        if (!isAuthenticated() || items.isEmpty()) return;
        if (!hasProfile()) { log("bulkUpsert skipped — profile not loaded yet"); return; }

        String userId = getUserId();
        String nowIso = java.time.Instant.now().toString();
        long nowMs    = System.currentTimeMillis();

        JsonArray body = new JsonArray();
        for (PortfolioItem item : items)
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("id",            java.util.UUID.randomUUID().toString());
            obj.addProperty("user_id",        userId);
            obj.addProperty("profile_id",     cachedProfileId);
            obj.addProperty("item_id",        item.getItemId());
            obj.addProperty("item_name",      item.getItemName());
            obj.addProperty("game",           "osrs");
            obj.addProperty("quantity",       item.getQuantity());
            obj.addProperty("buy_price",      item.getBuyPrice());
            obj.addProperty("buy_date",       nowIso);
            obj.addProperty("last_added_at",  nowMs);
            obj.addProperty("watchlisted",    false);
            obj.addProperty("source",         "runelite");
            if (item.getImageUrl() != null) obj.addProperty("image_url", item.getImageUrl());
            body.add(obj);
        }

        String prefer = config.bankOverwriteDuplicates()
            ? "resolution=merge-duplicates,return=minimal"
            : "resolution=ignore-duplicates,return=minimal";

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items?on_conflict=user_id,item_id,game")
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .addHeader("Content-Type",  "application/json")
            .addHeader("Prefer",        prefer)
            .build();

        executeAsync(request, "bulkUpsert(" + items.size() + " items)");
    }

    public void removeItemsMissingFromBank(java.util.Set<Integer> bankItemIds)
    {
        if (!isAuthenticated() || !hasProfile()) return;

        Request fetchRequest = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/portfolio_items"
                + "?user_id=eq." + getUserId()
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
                log("removeItemsMissingFromBank error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                if (!response.isSuccessful() || response.body() == null) return;
                JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);
                response.close();
                for (int i = 0; i < rows.size(); i++)
                {
                    JsonObject row = rows.get(i).getAsJsonObject();
                    int itemId     = row.get("item_id").getAsInt();
                    if (!bankItemIds.contains(itemId))
                    {
                        deleteItem(row.get("id").getAsString());
                        log("Removed missing bank item: item_id=" + itemId);
                    }
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

        String encoded;
        try { encoded = java.net.URLEncoder.encode(rsUsername, "UTF-8"); }
        catch (Exception e) { encoded = rsUsername; }

        Request request = new Request.Builder()
            .url(SUPABASE_URL + "/rest/v1/profiles?user_id=eq." + cachedUserId
                + "&name=eq." + encoded
                + "&select=id,name&limit=1")
            .get()
            .addHeader("apikey",        ANON_KEY)
            .addHeader("Authorization", "Bearer " + config.authToken())
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                log("switchProfile fetch failed: HTTP " + response.code());
                return;
            }

            JsonArray rows = gson.fromJson(response.body().string(), JsonArray.class);
            if (rows.size() > 0)
            {
                String profileId = rows.get(0).getAsJsonObject().get("id").getAsString();
                if (!profileId.equals(cachedProfileId))
                {
                    cachedProfileId = profileId;
                    log("Switched to profile for " + rsUsername + " (" + cachedProfileId + ")");
                }
                // Mark verified — this RuneLite session proves ownership of the RS account
                markPluginVerified();
            }
            else
            {
                log("No profile found for " + rsUsername + " — creating one.");
                createProfileForUsername(rsUsername);
            }
        }
        catch (IOException e)
        {
            log("switchProfile error: " + e.getMessage());
        }
    }

    private void createProfileForUsername(String rsUsername)
    {
        JsonObject body = new JsonObject();
        body.addProperty("id",      java.util.UUID.randomUUID().toString());
        body.addProperty("user_id", cachedUserId);
        body.addProperty("name",    rsUsername);

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
                request = request.newBuilder()
                    .removeHeader("Authorization")
                    .addHeader("Authorization", "Bearer " + config.authToken())
                    .build();
                fetchAndCacheProfile(); // tail-call retry with fresh token
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
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log(label + " failed: " + e.getMessage());
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
                            @Override public void onResponse(Call c, Response r)   { log(label + (r.isSuccessful() ? " retry OK" : " retry HTTP " + r.code())); r.close(); }
                        });
                    }
                    return;
                }
                if (!response.isSuccessful()) log(label + " HTTP error: " + response.code());
                else                          log(label + " OK");
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
