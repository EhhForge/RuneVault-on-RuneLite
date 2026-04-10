package com.runevault;

import lombok.extern.slf4j.Slf4j;
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

    // Last known equipment snapshot — used to avoid redundant re-uploads
    private final Map<Integer, Integer> lastEquipment = new HashMap<>();

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

        // Before updating lastEquipment, determine what changed so we can fix double-counting.
        //
        // Double-counting occurs when an item is both in a bank scan row (source="runelite")
        // AND an equipment row (source="runelite_equip"). These are stored separately because
        // on_conflict includes the source column. Both rows are summed in the app.
        //
        // Fix: when a non-stackable item is newly equipped, immediately delete its bank scan
        // row — it can't be in the bank and equipped simultaneously. When it's unequipped,
        // delete the equipment row so the next bank scan becomes the single source of truth.
        //
        // Stackable ammo (qty > 1 in the equipment slot) is intentionally skipped because
        // the player can legitimately have some in the quiver and more in the bank.

        java.util.Set<Integer> newlyEquipped    = new java.util.HashSet<>();
        java.util.Set<Integer> newlyUnequipped  = new java.util.HashSet<>();

        for (Map.Entry<Integer, Integer> entry : current.entrySet())
        {
            int rawId = entry.getKey();
            int qty   = entry.getValue();
            // qty == 1 means non-stackable (weapon, armour, etc.) — safe to remove bank row.
            if (qty == 1 && !lastEquipment.containsKey(rawId))
            {
                int canonicalId = itemManager.canonicalize(rawId);
                if (canonicalId > 0) newlyEquipped.add(canonicalId);
            }
        }
        for (int rawId : lastEquipment.keySet())
        {
            if (!current.containsKey(rawId))
            {
                int canonicalId = itemManager.canonicalize(rawId);
                if (canonicalId > 0) newlyUnequipped.add(canonicalId);
            }
        }

        if (current.isEmpty())
        {
            // Nothing equipped — clear any stale rows
            supabase.clearEquipmentItems();
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

        // Remove stale bank-scan rows for newly equipped non-stackables to prevent double-counting.
        if (!newlyEquipped.isEmpty())
        {
            log.debug("[RuneVault] Removing bank rows for {} newly equipped items", newlyEquipped.size());
            supabase.removeBankRowsForEquippedItems(newlyEquipped);
        }

        // Remove stale equipment rows for unequipped items — the next bank scan will
        // re-add them as source="runelite" when the player banks them.
        if (!newlyUnequipped.isEmpty())
        {
            log.debug("[RuneVault] Removing equipment rows for {} unequipped items", newlyUnequipped.size());
            supabase.removeEquipmentRowsForUnequippedItems(newlyUnequipped);
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
