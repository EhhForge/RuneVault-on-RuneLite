package com.runevault;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.game.ItemManager;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class InventoryTracker
{
    private static final int COINS_ID = 995;

    private final SupabaseClient supabase;
    private final RuneVaultConfig config;
    private final ItemManager itemManager;

    // Snapshot of the last known inventory: itemId → quantity
    private final Map<Integer, Integer> lastInventory = new HashMap<>();

    // True until the first container event after login — that event is baseline, not a change
    private boolean initialLoad = true;

    // Track if the next inventory change was caused by a ground pickup ("Take")
    private boolean pendingPickup = false;
    private int pendingPickupItemId = -1;

    // Track if the next inventory change was caused by a drop action
    private boolean pendingDrop = false;
    private int pendingDropItemId = -1;

    public InventoryTracker(SupabaseClient supabase, RuneVaultConfig config, ItemManager itemManager)
    {
        this.supabase = supabase;
        this.config = config;
        this.itemManager = itemManager;
    }

    /**
     * Call this on MenuOptionClicked to flag intentional drops before the
     * inventory change event fires.
     */
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!config.trackDropsAndSales()) return;

        if ("Drop".equalsIgnoreCase(event.getMenuOption()))
        {
            pendingDrop = true;
            pendingDropItemId = event.getItemId();
        }

        if ("Take".equalsIgnoreCase(event.getMenuOption()))
        {
            pendingPickup = true;
            pendingPickupItemId = event.getItemId();
        }
    }

    /**
     * Main inventory diff — fires on every inventory change.
     */
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INVENTORY.getId()) return;

        ItemContainer container = event.getItemContainer();
        if (container == null) return;

        Map<Integer, Integer> currentInventory = buildMap(container);

        // First event after login = initial load. Just capture as baseline, don't diff.
        if (initialLoad)
        {
            lastInventory.putAll(currentInventory);
            initialLoad = false;
            return;
        }

        // Diff: what was added?
        for (Map.Entry<Integer, Integer> entry : currentInventory.entrySet())
        {
            int itemId = entry.getKey();
            int currentQty = entry.getValue();
            int previousQty = lastInventory.getOrDefault(itemId, 0);
            int delta = currentQty - previousQty;

            if (delta > 0)
            {
                handleItemAdded(itemId, delta);
            }
        }

        // Diff: what was removed?
        for (Map.Entry<Integer, Integer> entry : lastInventory.entrySet())
        {
            int itemId = entry.getKey();
            int previousQty = entry.getValue();
            int currentQty = currentInventory.getOrDefault(itemId, 0);
            int delta = previousQty - currentQty;

            if (delta > 0)
            {
                handleItemRemoved(itemId, delta);
            }
        }

        // Update snapshot
        lastInventory.clear();
        lastInventory.putAll(currentInventory);

        // Reset action flags
        pendingPickup = false;
        pendingPickupItemId = -1;
        pendingDrop = false;
        pendingDropItemId = -1;
    }

    private void handleItemAdded(int itemId, int quantity)
    {
        if (!supabase.isProfileReady()) return;
        // Cash: only track explicit ground pickups (pendingPickup flag set by "Take" click).
        // All other coin increases (GE collection, bank withdrawal, etc.) are handled by BankTracker.
        if (itemId == COINS_ID)
        {
            // Ground pickups: pendingPickup is set by "Take" click.
            // getItemId() returns -1 for ground items so we don't check the ID here.
            if (config.syncCash() && pendingPickup)
                supabase.adjustCash(quantity);
            return;
        }

        if (!config.trackPickups()) return;

        // Only count genuine ground pickups (preceded by a "Take" click).
        // This prevents GE collections, bank withdrawals, etc. from being double-counted.
        // pendingPickupItemId is -1 for ground items (loot piles) — skip ID check in that case.
        if (!pendingPickup) return;
        if (pendingPickupItemId != -1 && pendingPickupItemId != itemId) return;

        int canonicalId = itemManager.canonicalize(itemId);
        if (canonicalId <= 0) return;
        String itemName = itemManager.getItemComposition(canonicalId).getName();
        String imageUrl = buildImageUrl(canonicalId);
        log.debug("[RuneVault] Picked up: " + quantity + "x " + itemName);

        // Buy price unknown for pickups — set to 0
        PortfolioItem item = new PortfolioItem(canonicalId, itemName, quantity, 0, imageUrl, 0);
        supabase.upsertItem(item);
    }

    private void handleItemRemoved(int itemId, int quantity)
    {
        if (!supabase.isProfileReady()) return;
        // Cash: only track explicit drops. Bank deposits / spending are handled by BankTracker.
        if (itemId == COINS_ID)
        {
            if (config.syncCash() && pendingDrop && pendingDropItemId == COINS_ID)
                supabase.adjustCash(-quantity);
            return;
        }

        if (!config.trackDropsAndSales()) return;

        // Only act on explicit drops (not inventory rearrangement, equipping, etc.)
        if (pendingDrop && pendingDropItemId == itemId)
        {
            String itemName = itemManager.getItemComposition(itemId).getName();
            log.debug("[RuneVault] Dropped: " + quantity + "x " + itemName);
            supabase.decrementItem(itemId, quantity);
        }
        // Note: GE sales are handled by GETracker, not here
    }

    /**
     * Call when the player logs in so we get a fresh baseline.
     */
    public void reset()
    {
        lastInventory.clear();
        initialLoad = true;
        pendingPickup = false;
        pendingPickupItemId = -1;
        pendingDrop = false;
        pendingDropItemId = -1;
    }

    private Map<Integer, Integer> buildMap(ItemContainer container)
    {
        Map<Integer, Integer> map = new HashMap<>();
        for (Item item : container.getItems())
        {
            if (item.getId() <= 0) continue;
            map.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        return map;
    }

    private String buildImageUrl(int itemId)
    {
        return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
    }
}
