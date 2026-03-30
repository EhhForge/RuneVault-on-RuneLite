package com.runevault;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
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
    @Inject private RuneVaultConfig config;
    @Inject private ItemManager itemManager;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;

    private SupabaseClient supabase;
    private GETracker geTracker;
    private InventoryTracker inventoryTracker;
    private BankTracker bankTracker;
    private ScheduledExecutorService linkCodePoller;

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

        // Single-thread executor — init runs first, then polling starts
        linkCodePoller = new ExecutorServiceExceptionLogger(Executors.newSingleThreadScheduledExecutor());

        // Run auth init off the EDT (blocking network calls not allowed on EDT)
        linkCodePoller.execute(() -> {
            if (!config.authToken().isEmpty())
            {
                log.info("[RuneVault] Session found — restoring.");
                supabase.initFromStoredToken();
            }
            else if (!config.linkCode().isEmpty())
            {
                log.info("[RuneVault] Link code detected — connecting to Rune Vault...");
                boolean ok = supabase.exchangeLinkCode(config.linkCode());
                if (ok) log.info("[RuneVault] Linked successfully!");
                else    log.warn("[RuneVault] Link failed — code may be expired. Generate a new one in the Rune Vault app.");
            }
            else
            {
                log.info("[RuneVault] Not linked. Open the Rune Vault app → Settings → Connect RuneLite Plugin.");
            }
        });

        // Poll every 3s for the Connect checkbox (single-thread executor ensures init completes first)
        linkCodePoller.scheduleWithFixedDelay(this::checkLinkCode, 3, 3, TimeUnit.SECONDS);
    }

    private void checkLinkCode()
    {
        if (!config.connectNow()) return;
        config.setConnectNow(false); // reset checkbox immediately so it doesn't re-trigger

        String code = config.linkCode().trim();
        if (code.length() != 6)
        {
            log.warn("[RuneVault] Connect checked but no valid 6-character code entered.");
            return;
        }
        if (!config.authToken().isEmpty())
        {
            log.info("[RuneVault] Already linked — uncheck Connect and use the app to re-link if needed.");
            return;
        }

        log.info("[RuneVault] Connecting to Rune Vault...");
        boolean ok = supabase.exchangeLinkCode(code);
        if (ok) log.info("[RuneVault] Linked successfully!");
        else    log.warn("[RuneVault] Link failed — code may be expired. Generate a new one in the app.");
    }

    @Override
    protected void shutDown()
    {
        if (linkCodePoller != null)
        {
            linkCodePoller.shutdownNow();
            linkCodePoller = null;
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
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            // Reset GE slot states so the next login's offer replay is treated as baseline
            geTracker.reset();
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        if (!supabase.isAuthenticated()) return;
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
