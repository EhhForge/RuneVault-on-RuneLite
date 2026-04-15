package com.runevault;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.game.ItemManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class InventoryTracker
{
    private static final int COINS_ID             = 995;
    private static final int BANK_WIDGET_GROUP_ID = 12;  // bank interface
    private static final int GE_WIDGET_GROUP_ID   = 465; // Grand Exchange interface

    private final SupabaseClient supabase;
    private final RuneVaultConfig config;
    private final ItemManager itemManager;
    private final Client client;

    // Snapshot of the last known inventory: itemId → quantity
    private final Map<Integer, Integer> lastInventory = new HashMap<>();

    // True until the first container event after login — that event is baseline, not a change
    private boolean initialLoad = true;

    // Track pending drops: itemId → count of pending drops.
    // A Map handles rapid shift+click of the same item — a Set would collapse
    // multiple drops of the same item ID into one and miss the rest.
    private boolean pendingDrop = false;
    private final java.util.Map<Integer, Integer> pendingDropCounts = new java.util.HashMap<>();

    // Last known equipment snapshot — used to avoid redundant re-uploads
    private final Map<Integer, Integer> lastEquipment = new HashMap<>();

    public InventoryTracker(SupabaseClient supabase, RuneVaultConfig config, ItemManager itemManager, Client client)
    {
        this.supabase = supabase;
        this.config = config;
        this.itemManager = itemManager;
        this.client = client;
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
            pendingDropCounts.merge(event.getItemId(), 1, Integer::sum);
        }
    }

    /**
     * Main inventory diff — fires on every inventory change.
     * Also handles equipment container changes to keep the equipped-items snapshot current.
     */
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() == InventoryID.EQUIPMENT.getId())
        {
            syncEquipment(event.getItemContainer());
            return;
        }

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

        // Diff: what was removed? (drops only — additions are handled by bank scan / GE)
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

        // Drop flags are cleared per-item in handleItemRemoved so rapid shift+click
        // drops across multiple IC events are all handled before the map is emptied.
    }

    private void handleItemRemoved(int itemId, int quantity)
    {
        // Bank/GE open → item leaving inventory is a deposit or sale, not a drop.
        // BankTracker handles bank changes; GETracker handles sales.
        if (client.getWidget(BANK_WIDGET_GROUP_ID, 0) != null) return;
        if (client.getWidget(GE_WIDGET_GROUP_ID,   0) != null) return;

        // Cash: only track explicit drops. Bank deposits / spending are handled by BankTracker.
        if (itemId == COINS_ID)
        {
            if (config.syncCash() && pendingDrop && pendingDropCounts.containsKey(COINS_ID))
            {
                int remaining = pendingDropCounts.get(COINS_ID) - 1;
                if (remaining <= 0) pendingDropCounts.remove(COINS_ID);
                else pendingDropCounts.put(COINS_ID, remaining);
                if (pendingDropCounts.isEmpty()) pendingDrop = false;
                supabase.adjustCash(-quantity);
            }
            return;
        }

        if (!config.trackDropsAndSales()) return;

        // Only act on explicit drops (not inventory rearrangement, equipping, etc.)
        // Remove per-item from the map — global clear would wipe remaining entries before
        // subsequent IC events fire (one IC per shift+click drop).
        if (pendingDrop && pendingDropCounts.containsKey(itemId))
        {
            int remaining = pendingDropCounts.get(itemId) - 1;
            if (remaining <= 0) pendingDropCounts.remove(itemId);
            else pendingDropCounts.put(itemId, remaining);
            if (pendingDropCounts.isEmpty()) pendingDrop = false;

            int canonicalId = itemManager.canonicalize(itemId);
            String itemName = itemManager.getItemComposition(canonicalId).getName();
            log.debug("[RuneVault] Dropped: " + quantity + "x " + itemName);
            supabase.decrementItem(canonicalId, quantity);
        }
        // Note: GE sales are handled by GETracker, not here
    }

    /**
     * Syncs the player's currently equipped items to Supabase as source="runelite_equip".
     * Only uploads when the equipment actually changes (compared to lastEquipment snapshot).
     */
    private void syncEquipment(ItemContainer container)
    {
        if (container == null) return;
        if (!supabase.isProfileReady()) return;

        Map<Integer, Integer> current = buildMap(container);

        // Skip upload if nothing changed since last sync
        if (current.equals(lastEquipment)) return;

        // In the single-row architecture, equipping overwrites the row's source to
        // "runelite_equip" via upsert conflict resolution — no separate bank-row deletion needed.
        //
        // DO NOT delete equipment rows immediately on unequip. Doing so creates a guaranteed
        // gap: the row is deleted before the next bank scan has a chance to restore it as
        // "runelite", causing a temporary portfolio value drop.
        //
        // Stale equipment rows (items unequipped and no longer in possession) are cleaned up
        // lazily by BankTracker.syncItems() via clearStaleEquipmentRows() — which runs after
        // the bank upsert has landed, so there is no gap.

        if (current.isEmpty())
        {
            // Nothing equipped — just clear the snapshot; bank scan handles cleanup.
            lastEquipment.clear();
            return;
        }

        List<PortfolioItem> items = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : current.entrySet())
        {
            int rawId = entry.getKey();
            if (rawId == COINS_ID) continue;

            int canonicalId  = itemManager.canonicalize(rawId);
            if (canonicalId <= 0) continue;

            int[] prices    = PriceUtil.resolvePrices(canonicalId, itemManager);
            int buyPrice    = prices[0];
            int haPrice     = prices[1];

            net.runelite.api.ItemComposition comp = itemManager.getItemComposition(canonicalId);
            String rawName   = comp.getName();
            String nameLower = rawName.toLowerCase();
            String name      = nameLower.endsWith(" (members)") ? rawName.substring(0, rawName.length() - 10) : rawName;
            String imageUrl  = "https://static.runelite.net/cache/item/icon/" + canonicalId + ".png";

            items.add(new PortfolioItem(canonicalId, name, entry.getValue(), buyPrice, imageUrl, haPrice));
        }

        if (!items.isEmpty())
        {
            log.debug("[RuneVault] Syncing {} equipped items", items.size());
            final Map<Integer, Integer> captured = current;
            supabase.bulkUpsertItems(items, "runelite_equip", () -> {
                lastEquipment.clear();
                lastEquipment.putAll(captured);
            });
        }
    }

    /**
     * Call when the player logs in so we get a fresh baseline.
     */
    public void reset()
    {
        lastInventory.clear();
        lastEquipment.clear();
        initialLoad = true;
        pendingDrop = false;
        pendingDropCounts.clear();
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
}
