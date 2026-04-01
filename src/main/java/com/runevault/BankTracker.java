package com.runevault;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.game.ItemManager;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class BankTracker
{
    // RuneLite widget group ID for the bank interface
    private static final int BANK_WIDGET_GROUP_ID = 12;
    private static final int COINS_ID = 995;

    private final SupabaseClient supabase;
    private final RuneVaultConfig config;
    private final ItemManager itemManager;

    // Becomes true once the bank container fires after the bank widget loads
    private boolean bankOpenPending = false;

    // Most recent inventory coin balance — kept in sync so we can add it to bank coins on scan
    private int cachedInventoryCoins = 0;

    public BankTracker(SupabaseClient supabase, RuneVaultConfig config, ItemManager itemManager)
    {
        this.supabase = supabase;
        this.config = config;
        this.itemManager = itemManager;
    }

    /**
     * Fires when any widget is loaded. We detect the bank opening here.
     */
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (!config.bankScanEnabled()) return;
        if (event.getGroupId() == BANK_WIDGET_GROUP_ID)
        {
            bankOpenPending = true;
        }
    }

    /**
     * Fires when a container changes. We wait for the bank container after the
     * bank widget loads to ensure all items are populated.
     */
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        // Always keep inventory coin cache up to date
        if (event.getContainerId() == InventoryID.INVENTORY.getId())
        {
            ItemContainer inv = event.getItemContainer();
            if (inv != null) cachedInventoryCoins = getCoins(inv);
        }

        if (!config.bankScanEnabled()) return;
        if (!bankOpenPending) return;
        if (event.getContainerId() != InventoryID.BANK.getId()) return;

        bankOpenPending = false;

        ItemContainer container = event.getItemContainer();
        if (container == null) return;

        List<PortfolioItem> items = buildItemList(container);
        int bankCoins = getCoins(container);
        if (items.isEmpty() && bankCoins == 0) return;

        if (config.bankScanMode() == BankScanMode.AUTO)
        {
            syncItems(items, bankCoins);
        }
        else
        {
            promptUser(items, bankCoins);
        }
    }

    private void promptUser(List<PortfolioItem> items, int bankCoins)
    {
        SwingUtilities.invokeLater(() ->
        {
            int result = JOptionPane.showConfirmDialog(
                null,
                "Sync " + items.size() + " bank item(s) to Rune Vault?",
                "Rune Vault — Bank Scan",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            );

            if (result == JOptionPane.YES_OPTION)
            {
                syncItems(items, bankCoins);
            }
        });
    }

    private void syncItems(List<PortfolioItem> items, int bankCoins)
    {
        if (!supabase.isProfileReady())
        {
            log.warn("[RuneVault] Bank sync skipped — profile not yet confirmed for this character.");
            return;
        }
        log.debug("[RuneVault] Syncing " + items.size() + " bank items");
        supabase.bulkUpsertItems(items);

        if (config.bankRemoveMissing())
        {
            java.util.Set<Integer> bankItemIds = new java.util.HashSet<>();
            for (PortfolioItem item : items) bankItemIds.add(item.getItemId());
            supabase.removeItemsMissingFromBank(bankItemIds);
        }

        if (config.syncCash())
        {
            long totalCoins = (long) bankCoins + cachedInventoryCoins;
            if (totalCoins > 0) supabase.updateCash(totalCoins);
        }
    }

    private List<PortfolioItem> buildItemList(ItemContainer container)
    {
        List<PortfolioItem> items = new ArrayList<>();
        for (Item item : container.getItems())
        {
            if (item.getId() <= 0 || item.getId() == COINS_ID) continue;
            if (item.getQuantity() <= 0) continue; // skip placeholders
            int canonicalId = itemManager.canonicalize(item.getId());
            if (canonicalId <= 0) continue;
            String rawName = itemManager.getItemComposition(canonicalId).getName();
            String nameLower = rawName.toLowerCase();
            String name = nameLower.endsWith(" (members)") ? rawName.substring(0, rawName.length() - 10) : rawName;
            String imageUrl = "https://static.runelite.net/cache/item/icon/" + canonicalId + ".png";
            items.add(new PortfolioItem(canonicalId, name, item.getQuantity(), 0, imageUrl));
        }
        return items;
    }

    private int getCoins(ItemContainer container)
    {
        for (Item item : container.getItems())
        {
            if (item.getId() == COINS_ID) return item.getQuantity();
        }
        return 0;
    }
}
