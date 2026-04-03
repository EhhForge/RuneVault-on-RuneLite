package com.runevault;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.game.ItemManager;

@Slf4j
public class GETracker
{
    private static final int GE_SLOTS = 8;

    private final SupabaseClient supabase;
    private final RuneVaultConfig config;
    private final ItemManager itemManager;

    // Track previous state per slot. null = not yet seen this session (initial load).
    private final GrandExchangeOfferState[] previousStates = new GrandExchangeOfferState[GE_SLOTS];

    public GETracker(SupabaseClient supabase, RuneVaultConfig config, ItemManager itemManager)
    {
        this.supabase = supabase;
        this.config = config;
        this.itemManager = itemManager;
    }

    /** Call on logout so initial offer states are re-baselined on next login. */
    public void reset()
    {
        java.util.Arrays.fill(previousStates, null);
    }

    public void onOfferChanged(GrandExchangeOfferChanged event)
    {
        if (!config.syncGeTrades()) return;
        if (!supabase.isProfileReady()) return;

        int slot = event.getSlot();
        GrandExchangeOffer offer = event.getOffer();
        GrandExchangeOfferState state = offer.getState();

        // First time seeing this slot this session = initial load replay. Record and skip.
        if (previousStates[slot] == null)
        {
            previousStates[slot] = state;
            return;
        }

        GrandExchangeOfferState prev = previousStates[slot];
        previousStates[slot] = state;

        // Only process genuine transitions into a terminal state
        if (state == prev) return;
        if (state != GrandExchangeOfferState.BOUGHT && state != GrandExchangeOfferState.SOLD) return;

        int itemId   = offer.getItemId();
        int quantity = offer.getTotalQuantity();
        // Use the actual price paid (spent / traded), not the bid ceiling the player entered.
        // offer.getPrice() is the max-buy price; offer.getSpent() is what was actually charged.
        int price    = offer.getQuantitySold() > 0
            ? offer.getSpent() / offer.getQuantitySold()
            : offer.getPrice();

        String itemName = itemManager.getItemComposition(itemId).getName();
        String imageUrl = buildImageUrl(itemId);

        if (state == GrandExchangeOfferState.BOUGHT)
        {
            log.debug("[RuneVault] GE BUY: " + quantity + "x " + itemName + " @ " + price + "gp (actual, bid was " + offer.getPrice() + "gp)");
            supabase.upsertItem(new PortfolioItem(itemId, itemName, quantity, price, imageUrl, 0));
        }
        else
        {
            log.debug("[RuneVault] GE SELL: " + quantity + "x " + itemName);
            supabase.decrementItem(itemId, quantity);
        }
    }

    private String buildImageUrl(int itemId)
    {
        return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
    }
}
