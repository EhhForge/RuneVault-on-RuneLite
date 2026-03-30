package com.runevault;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ExecutorServiceExceptionLogger;
import okhttp3.OkHttpClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

@Slf4j
@PluginDescriptor(
    name = "Rune Vault",
    description = "Sync your OSRS portfolio — GE trades, item pickups, drops, and bank — to Rune Vault.",
    tags = {"portfolio", "ge", "grand exchange", "tracker", "runevault"}
)
public class RuneVaultPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar clientToolbar;
    @Inject private RuneVaultConfig config;
    @Inject private ItemManager itemManager;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;

    private SupabaseClient supabase;
    private GETracker geTracker;
    private InventoryTracker inventoryTracker;
    private BankTracker bankTracker;
    private ScheduledExecutorService linkCodePoller;

    private RuneVaultPanel panel;
    private NavigationButton navButton;

    private volatile boolean isConnecting = false;
    private volatile String  lastPlayerName        = null;
    private volatile boolean playerNamePending      = false; // resolved on first GameTick
    private volatile boolean shownDisabledWarning   = false; // one-time per session

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void startUp()
    {
        supabase = new SupabaseClient(okHttpClient, gson, config);
        geTracker = new GETracker(supabase, config, itemManager);
        inventoryTracker = new InventoryTracker(supabase, config, itemManager);
        bankTracker = new BankTracker(supabase, config, itemManager);

        // Build the side panel
        panel = new RuneVaultPanel(e -> handlePanelConnect(), e -> handlePanelDisconnect(), config);
        navButton = NavigationButton.builder()
            .tooltip("Rune Vault")
            .icon(RuneVaultPanel.buildNavIcon())
            .priority(5)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        // Single-thread executor — init runs first, then polling starts
        linkCodePoller = new ExecutorServiceExceptionLogger(Executors.newSingleThreadScheduledExecutor());

        // Run auth init off the EDT (blocking network calls not allowed on EDT)
        linkCodePoller.execute(() -> {
            if (!config.authToken().isEmpty())
            {
                log.info("[RuneVault] Session found — restoring.");
                supabase.initFromStoredToken();
                if (supabase.isAuthenticated())
                {
                    config.setConnectionStatus("Connected \u2713");
                    panel.setConnected(lastPlayerName);
                    supabase.setPluginActive(true);
                }
                else
                {
                    panel.setDisconnected();
                }
            }
            else if (!config.linkCode().isEmpty())
            {
                log.info("[RuneVault] Link code detected — connecting to Rune Vault...");
                boolean ok = supabase.exchangeLinkCode(config.linkCode());
                if (ok)
                {
                    log.info("[RuneVault] Linked successfully!");
                    config.setConnectionStatus("Connected \u2713");
                    panel.setConnected(lastPlayerName);
                }
                else
                {
                    log.warn("[RuneVault] Link failed — code may be expired. Generate a new one in the Rune Vault app.");
                    config.setConnectionStatus("Failed \u2014 generate a new code in the app");
                    panel.setFeedback("Failed \u2014 generate a new code in the app", true);
                }
            }
            else
            {
                log.info("[RuneVault] Not linked. Open the Rune Vault app \u2192 Settings \u2192 Connect RuneLite Plugin.");
                config.setConnectionStatus("Not connected");
                panel.setDisconnected();
            }

            // If the player is already in-game when the plugin loads, switch to their profile
            clientThread.invokeLater(() -> {
                if (client.getGameState() == GameState.LOGGED_IN && supabase.isAuthenticated())
                {
                    String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
                    if (playerName != null)
                    {
                        lastPlayerName = playerName;
                        panel.updatePlayerName(playerName);
                        final String name = playerName;
                        linkCodePoller.execute(() -> supabase.switchProfileForUsername(name));
                    }
                }
            });
        });

        // Poll every 3s for the Connect checkbox (single-thread executor ensures init completes first)
        linkCodePoller.scheduleWithFixedDelay(this::checkLinkCode, 3, 3, TimeUnit.SECONDS);

        // Heartbeat every 2 minutes — keeps plugin_last_seen fresh so the app can
        // distinguish active (online) from crashed (stale) vs disconnected (explicit).
        linkCodePoller.scheduleWithFixedDelay(
            () -> supabase.sendHeartbeat(), 120, 120, TimeUnit.SECONDS);
    }

    private void checkLinkCode()
    {
        if (!config.connectNow()) return;
        config.setConnectNow(false); // reset checkbox immediately — prevents re-trigger

        if (isConnecting)
        {
            showChatMessage("Already connecting, please wait\u2026");
            return;
        }

        String code = config.linkCode().trim();
        if (code.length() != 6)
        {
            config.setConnectionStatus("Error: enter a valid 6-char code");
            showChatMessage("Invalid code \u2014 enter the 6-character code from the Rune Vault app.");
            log.warn("[RuneVault] Connect checked but no valid 6-character code entered.");
            return;
        }
        if (!config.authToken().isEmpty())
        {
            config.setConnectionStatus("Connected \u2713");
            showChatMessage("Already linked to Rune Vault.");
            log.info("[RuneVault] Already linked.");
            return;
        }

        isConnecting = true;
        config.setConnectionStatus("Connecting\u2026");
        showChatMessage("Connecting to Rune Vault\u2026");
        log.info("[RuneVault] Connecting to Rune Vault...");

        boolean ok = supabase.exchangeLinkCode(code);
        isConnecting = false;

        if (ok)
        {
            config.setConnectionStatus("Connected \u2713");
            showChatMessage("Linked! Your portfolio will now sync automatically.");
            log.info("[RuneVault] Linked successfully!");
        }
        else
        {
            config.setConnectionStatus("Failed \u2014 generate a new code in the app");
            showChatMessage("Link failed \u2014 code may be expired. Generate a new one in the Rune Vault app.");
            log.warn("[RuneVault] Link failed.");
        }
    }

    private void showChatMessage(String message)
    {
        clientThread.invokeLater(() ->
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=e8a060>[Rune Vault]</col> " + message, null)
        );
    }

    private void handlePanelConnect()
    {
        if (isConnecting) return;

        String code = panel.getCode();
        if (code.length() != 6)
        {
            panel.setFeedback("Enter a valid 6-character code", true);
            return;
        }
        if (supabase.isAuthenticated())
        {
            // Already connected — treat as a re-link: disconnect first, then connect
            supabase.disconnect();
            panel.setDisconnected();
        }

        isConnecting = true;
        panel.setConnecting();
        showChatMessage("Connecting to Rune Vault\u2026");

        linkCodePoller.execute(() -> {
            boolean ok = supabase.exchangeLinkCode(code);
            isConnecting = false;
            if (ok)
            {
                config.setConnectionStatus("Connected \u2713");
                shownDisabledWarning = false;
                panel.setConnected(lastPlayerName);
                showChatMessage("Linked! Your portfolio will now sync automatically.");
                log.info("[RuneVault] Linked successfully via panel.");
                // If the player is already in-game, switch/confirm profile immediately
                // without waiting for the next GameState.LOGGED_IN event.
                if (lastPlayerName != null)
                {
                    final String name = lastPlayerName;
                    linkCodePoller.execute(() -> supabase.switchProfileForUsername(name));
                }
                supabase.setPluginActive(true);
            }
            else
            {
                panel.setFeedback("Failed \u2014 code may be expired", true);
                config.setConnectionStatus("Failed \u2014 generate a new code in the app");
                showChatMessage("Link failed \u2014 code may be expired. Generate a new one in the Rune Vault app.");
                log.warn("[RuneVault] Link failed via panel.");
            }
        });
    }

    private void handlePanelDisconnect()
    {
        supabase.disconnect();
        isConnecting = false;
        // Don't clear lastPlayerName — the player is still logged in, only the auth changed.
        // It will be reused immediately if the user reconnects without logging out.
        panel.setDisconnected();
        showChatMessage("Disconnected from Rune Vault.");
        log.info("[RuneVault] Disconnected via panel.");
    }

    @Override
    protected void shutDown()
    {
        // Mark plugin as inactive before tearing down so the app sees it immediately
        if (supabase != null) supabase.setPluginActive(false);

        if (linkCodePoller != null)
        {
            linkCodePoller.shutdownNow();
            linkCodePoller = null;
        }
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Fresh baseline on each login
            inventoryTracker.reset();
            // Defer player name lookup — getName() is null at this exact moment.
            // onGameTick will resolve it on the first tick.
            playerNamePending = true;
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            geTracker.reset();
            playerNamePending = false;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (!playerNamePending) return;
        String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (playerName == null) return; // still loading — try again next tick

        playerNamePending = false;
        lastPlayerName = playerName;
        panel.updatePlayerName(playerName);

        if (supabase.isAuthenticated())
        {
            final String name = playerName;
            linkCodePoller.execute(() -> supabase.switchProfileForUsername(name));
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        if (!supabase.isAuthenticated())
        {
            // Warn the player once per session when a completed trade can't be synced
            GrandExchangeOfferState state = event.getOffer().getState();
            if (!shownDisabledWarning
                && (state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD))
            {
                shownDisabledWarning = true;
                showChatMessage("<col=ff6060>Not connected</col> \u2014 this trade won\u2019t be synced. "
                    + "Open the Rune Vault panel to link your account.");
            }
            return;
        }
        geTracker.onOfferChanged(event);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!supabase.isAuthenticated()) return;
        inventoryTracker.onItemContainerChanged(event);
        bankTracker.onItemContainerChanged(event);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!supabase.isAuthenticated()) return;
        inventoryTracker.onMenuOptionClicked(event);
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (!supabase.isAuthenticated()) return;
        bankTracker.onWidgetLoaded(event);
    }

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    @Provides
    RuneVaultConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RuneVaultConfig.class);
    }
}
