package com.runevault;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.game.ItemManager;

import javax.swing.*;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
public class BankTracker
{
    // RuneLite widget group ID for the bank interface
    private static final int BANK_WIDGET_GROUP_ID = 12;
    private static final int COINS_ID = 995;

    private final SupabaseClient supabase;
    private final RuneVaultConfig config;
    private final ItemManager itemManager;
    private final Consumer<String> chatMessage;

    // Becomes true once the bank container fires after the bank widget loads
    private boolean bankOpenPending = false;

    // Most recent inventory coin balance — kept in sync so we can add it to bank coins on scan
    private int cachedInventoryCoins = 0;

    public BankTracker(SupabaseClient supabase, RuneVaultConfig config, ItemManager itemManager, Consumer<String> chatMessage)
    {
        this.supabase = supabase;
        this.config = config;
        this.itemManager = itemManager;
        this.chatMessage = chatMessage;
    }

    /**
     * Fires when any widget is loaded. We detect the bank opening here.
     */
    public void onWidgetLoaded(WidgetLoaded event)
    {
        log.debug("[RuneVault] onWidgetLoaded groupId={} bankScanEnabled={}", event.getGroupId(), config.bankScanEnabled());
        if (!config.bankScanEnabled()) return;
        if (event.getGroupId() == BANK_WIDGET_GROUP_ID)
        {
            log.debug("[RuneVault] Bank widget detected — setting bankOpenPending=true");
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
        log.debug("[RuneVault] onItemContainerChanged containerId={} bankOpenPending={} bankId={}",
            event.getContainerId(), bankOpenPending, InventoryID.BANK.getId());
        if (!bankOpenPending) return;
        if (event.getContainerId() != InventoryID.BANK.getId()) return;

        bankOpenPending = false;

        ItemContainer container = event.getItemContainer();
        if (container == null) return;

        ScanResult result = buildItemList(container);
        int bankCoins = getCoins(container);
        log.debug("[RuneVault] Bank scan: {} items, {} placeholders skipped, {} coins",
            result.items.size(), result.placeholderCount, bankCoins);
        if (result.items.isEmpty() && bankCoins == 0) return;

        if (config.bankScanMode() == BankScanMode.AUTO)
        {
            syncItems(result, bankCoins);
        }
        else
        {
            promptUser(result, bankCoins);
        }
    }

    private void promptUser(ScanResult result, int bankCoins)
    {
        SwingUtilities.invokeLater(() ->
        {
            int totalSlots = result.items.size() + (bankCoins > 0 ? 1 : 0);
            String placeholderLine = result.placeholderCount > 0
                ? "<br>(" + result.placeholderCount + " placeholder slot(s) skipped)"
                : "";

            JLabel messageLabel = new JLabel("<html><body style='width:360px;font-size:13pt;font-weight:bold'>"
                + "Sync " + totalSlots + " bank slot(s) to Rune Vault?"
                + placeholderLine
                + "</body></html>");

            JCheckBox autoCheckbox = new JCheckBox("<html><body style='width:340px;font-size:11pt'>"
                + "Switch to Auto mode <span style='color:gray'>(recommended — syncs automatically on every bank open)</span>"
                + "</body></html>");

            JButton yesButton = new JButton("Yes");
            JButton noButton  = new JButton("No");

            JPanel buttonPanel = new JPanel();
            buttonPanel.add(yesButton);
            buttonPanel.add(noButton);

            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            content.add(messageLabel);
            content.add(Box.createVerticalStrut(12));
            content.add(autoCheckbox);
            content.add(Box.createVerticalStrut(8));
            content.add(buttonPanel);

            JDialog dialog = new JDialog((Frame) null, "Rune Vault — Bank Scan", false);
            dialog.setContentPane(content);
            dialog.pack();
            dialog.setLocationRelativeTo(null);
            dialog.setAlwaysOnTop(true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

            yesButton.addActionListener(e -> {
                dialog.dispose();
                if (autoCheckbox.isSelected()) config.setBankScanMode(BankScanMode.AUTO);
                syncItems(result, bankCoins);
            });
            noButton.addActionListener(e -> dialog.dispose());

            dialog.setVisible(true);
        });
    }

    private void syncItems(ScanResult result, int bankCoins)
    {
        if (!supabase.isProfileReady())
        {
            log.warn("[RuneVault] Bank sync skipped — profile not yet confirmed for this character.");
            chatMessage.accept("<col=ff6060>Bank sync skipped</col> \u2014 not yet connected. Link via the Rune Vault panel.");
            return;
        }
        log.debug("[RuneVault] Syncing {} bank items ({} placeholders skipped)",
            result.items.size(), result.placeholderCount);
        supabase.bulkUpsertItems(result.items);

        if (config.bankRemoveMissing())
        {
            java.util.Set<Integer> bankItemIds = new java.util.HashSet<>();
            for (PortfolioItem item : result.items) bankItemIds.add(item.getItemId());
            supabase.removeItemsMissingFromBank(bankItemIds);
        }

        if (config.syncCash())
        {
            long totalCoins = (long) bankCoins + cachedInventoryCoins;
            if (totalCoins > 0) supabase.updateCash(totalCoins);
        }

        int synced = result.items.size() + (bankCoins > 0 ? 1 : 0);
        String skipped = result.placeholderCount > 0 ? " (" + result.placeholderCount + " placeholders skipped)" : "";
        chatMessage.accept("<col=00c060>Bank synced:</col> " + synced + " slot(s)." + skipped);
    }

    /**
     * Builds the list of real items from the bank container.
     * - Skips coins, empty slots, and placeholder slots.
     * - Aggregates quantities across multiple slots of the same item
     *   (e.g. two Fire cape slots → quantity 2, split bolt stacks → summed).
     */
    private ScanResult buildItemList(ItemContainer container)
    {
        // LinkedHashMap preserves insertion order and accumulates quantity per canonical ID
        Map<Integer, PortfolioItem> byId = new LinkedHashMap<>();
        int placeholderCount = 0;

        for (Item item : container.getItems())
        {
            if (item.getId() <= 0 || item.getId() == COINS_ID) continue;
            if (item.getQuantity() <= 0) continue; // empty slots

            // Placeholders report quantity=1 but the item is not actually owned.
            // getPlaceholderTemplateId() returns 0 for real items, >0 for placeholders.
            if (itemManager.getItemComposition(item.getId()).getPlaceholderTemplateId() > 0)
            {
                placeholderCount++;
                continue;
            }

            int canonicalId = itemManager.canonicalize(item.getId());
            if (canonicalId <= 0) continue;

            PortfolioItem existing = byId.get(canonicalId);
            if (existing != null)
            {
                // Same item in multiple bank slots (e.g. noted + unnoted, or duplicate
                // untradeable like Fire cape) — sum the quantities.
                byId.put(canonicalId, new PortfolioItem(
                    canonicalId,
                    existing.getItemName(),
                    existing.getQuantity() + item.getQuantity(),
                    0,
                    existing.getImageUrl(),
                    existing.getHaPrice()
                ));
            }
            else
            {
                net.runelite.api.ItemComposition comp = itemManager.getItemComposition(canonicalId);
                String rawName  = comp.getName();
                String nameLower = rawName.toLowerCase();
                String name     = nameLower.endsWith(" (members)") ? rawName.substring(0, rawName.length() - 10) : rawName;
                String imageUrl = "https://static.runelite.net/cache/item/icon/" + canonicalId + ".png";
                int haPrice     = (int) Math.floor(comp.getPrice() * 0.6);
                byId.put(canonicalId, new PortfolioItem(canonicalId, name, item.getQuantity(), 0, imageUrl, haPrice));
            }
        }

        return new ScanResult(new ArrayList<>(byId.values()), placeholderCount);
    }

    private int getCoins(ItemContainer container)
    {
        for (Item item : container.getItems())
        {
            if (item.getId() == COINS_ID) return item.getQuantity();
        }
        return 0;
    }

    /** Holds the result of a bank scan: real items + how many placeholders were skipped. */
    private static class ScanResult
    {
        final List<PortfolioItem> items;
        final int placeholderCount;

        ScanResult(List<PortfolioItem> items, int placeholderCount)
        {
            this.items = items;
            this.placeholderCount = placeholderCount;
        }
    }
}
