package com.runevault;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
    public static final int BANK_WIDGET_GROUP_ID = 12;
    private static final int COINS_ID = 995;

    private final SupabaseClient supabase;
    private final RuneVaultConfig config;
    private final ItemManager itemManager;
    private final Consumer<String> chatMessage;
    private final Client client;

    // Becomes true once the bank container fires after the bank widget loads
    private boolean bankOpenPending = false;

    // True after the first scan this bank-open session.
    // Prevents bank tab switches (which re-fire WidgetLoaded for group 12) from triggering
    // a second scan with only the filtered tab's items visible.
    // Reset by checkBankClosed() when the bank widget disappears.
    private boolean bankScannedThisOpen = false;

    // Number of consecutive game ticks that the bank widget has been absent.
    // Requires 2+ consecutive null ticks before treating it as a real close — tab
    // transitions briefly destroy the widget for 1 tick, which would otherwise
    // erroneously reset bankScannedThisOpen and allow a second scan mid-session.
    private int bankClosedTickCount = 0;

    // Monotonically-increasing counter incremented on every syncItems() call.
    // The removeItemsMissingFromBank callback captures the value at dispatch time
    // and only executes if no newer scan has started — prevents stale callbacks
    // from deleting rows that a later scan just upserted.
    private volatile int bankScanGeneration = 0;

    // Most recent inventory coin balance — kept in sync so we can add it to bank coins on scan
    private int cachedInventoryCoins = 0;

    public BankTracker(SupabaseClient supabase, RuneVaultConfig config, ItemManager itemManager, Consumer<String> chatMessage, Client client)
    {
        this.supabase = supabase;
        this.config = config;
        this.itemManager = itemManager;
        this.chatMessage = chatMessage;
        this.client = client;
    }

    /**
     * Called from RuneVaultPlugin.onGameTick so BankTracker can detect when the bank
     * widget closes (widget returns null) and reset the scan-guard for the next open.
     */
    public void checkBankClosed(net.runelite.api.Client client)
    {
        if (!bankScannedThisOpen)
        {
            bankClosedTickCount = 0;
            return;
        }
        if (client.getWidget(BANK_WIDGET_GROUP_ID, 0) == null)
        {
            bankClosedTickCount++;
            if (bankClosedTickCount >= 2)
            {
                log.debug("[RuneVault] Bank closed — resetting scan guard (closedTicks={})", bankClosedTickCount);
                bankScannedThisOpen = false;
                bankOpenPending     = false;
                bankClosedTickCount = 0;
            }
        }
        else
        {
            // Widget visible again — was just a tab transition, not a real close
            bankClosedTickCount = 0;
        }
    }

    public void onWidgetLoaded(WidgetLoaded event)
    {
        log.debug("[RuneVault] onWidgetLoaded groupId={} bankScanEnabled={} scannedThisOpen={}",
            event.getGroupId(), config.bankScanEnabled(), bankScannedThisOpen);
        if (!config.bankScanEnabled()) return;
        if (event.getGroupId() == BANK_WIDGET_GROUP_ID)
        {
            if (bankScannedThisOpen)
            {
                // Tab switch or widget refresh — ignore to prevent partial re-scan
                log.debug("[RuneVault] Bank WidgetLoaded ignored — already scanned this open session (tab switch?)");
                return;
            }
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

        // In modern OSRS the bank sends ALL items to the client on open regardless of which
        // tab is displayed — tabs are purely a visual filter on the client side.
        // No need to enforce tab 0; scan immediately on the first container event.
        bankOpenPending     = false;
        bankScannedThisOpen = true; // block re-scans until bank closes

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
            // Profile isn't confirmed yet — queue the scan and apply it automatically
            // once switchProfileForUsername() completes (usually within 1-2s of login).
            log.info("[RuneVault] Bank sync queued — profile not yet confirmed, will auto-apply when ready.");
            supabase.queueForProfileReady(() -> syncItems(result, bankCoins));
            return;
        }

        // Increment generation so any in-flight callback from a previous scan
        // knows it is stale and should not delete rows from the new scan.
        bankScanGeneration = (bankScanGeneration + 1) % 100_000;
        final int thisGeneration = bankScanGeneration;

        log.debug("[RuneVault] Syncing {} bank items ({} placeholders skipped) gen={}",
            result.items.size(), result.placeholderCount, thisGeneration);

        // Build the ID set before the async call so it's captured in the lambda below.
        final java.util.Set<Integer> bankItemIds = new java.util.HashSet<>();
        for (PortfolioItem item : result.items) bankItemIds.add(item.getItemId());

        // Chain removeItemsMissingFromBank as the upsert's onSuccess callback so it only
        // runs AFTER the upsert has landed in the DB — prevents the race where deletes
        // fire before inserts complete and cause items to temporarily vanish in the app.
        // Guard with the generation counter: if a newer scan has already started by the
        // time this callback fires, skip the delete to avoid wiping the new scan's rows.
        // The chat message is also sent from the callback so it only appears after the
        // upsert succeeds — not before.
        int synced = result.items.size() + (bankCoins > 0 ? 1 : 0);
        String skipped = result.placeholderCount > 0 ? " (" + result.placeholderCount + " placeholders skipped)" : "";
        Runnable notifyUser = () -> chatMessage.accept("<col=00c060>Bank synced:</col> " + synced + " slot(s)." + skipped);

        Runnable afterUpsert;
        if (config.bankRemoveMissing())
        {
            afterUpsert = () -> {
                if (bankScanGeneration != thisGeneration)
                {
                    log.debug("[RuneVault] Skipping stale removeItemsMissingFromBank (gen {} superseded by gen {})",
                        thisGeneration, bankScanGeneration);
                    return;
                }
                supabase.removeItemsMissingFromBank(bankItemIds);
                notifyUser.run();
            };
        }
        else
        {
            afterUpsert = notifyUser;
        }
        supabase.bulkUpsertItems(result.items, "runelite", afterUpsert);

        if (config.syncCash())
        {
            long totalCoins = (long) bankCoins + cachedInventoryCoins;
            if (totalCoins > 0) supabase.updateCash(totalCoins);
        }
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
        int emptyCount = 0;
        int coinsCount = 0;

        for (Item item : container.getItems())
        {
            if (item.getId() <= 0) { emptyCount++; continue; }
            if (item.getId() == COINS_ID) { coinsCount++; continue; }
            if (item.getQuantity() <= 0)
            {
                log.info("[RuneVault][BankSkip] reason=qty<=0  rawId={} qty={} name={}",
                    item.getId(), item.getQuantity(), itemManager.getItemComposition(item.getId()).getName());
                continue;
            }

            // Placeholders report quantity=1 but the item is not actually owned.
            // getPlaceholderTemplateId() returns 0 for real items, >0 for placeholders.
            int placeholderTemplateId = itemManager.getItemComposition(item.getId()).getPlaceholderTemplateId();
            if (placeholderTemplateId > 0)
            {
                log.info("[RuneVault][BankSkip] reason=placeholder  rawId={} qty={} name={} templateId={}",
                    item.getId(), item.getQuantity(), itemManager.getItemComposition(item.getId()).getName(), placeholderTemplateId);
                placeholderCount++;
                continue;
            }

            // Store each item under its canonical ID (noted → unnoted) so every distinct
            // bank slot gets its own row, matching how the OSRS bank itself shows items.
            int canonicalId = itemManager.canonicalize(item.getId());
            if (canonicalId <= 0) continue;

            String rawName = itemManager.getItemComposition(item.getId()).getName();

            PortfolioItem existing = byId.get(canonicalId);
            if (existing != null)
            {
                // Same canonical ID across multiple slots (noted + unnoted, split stack) — merge.
                byId.put(canonicalId, new PortfolioItem(
                    canonicalId,
                    existing.getItemName(),
                    existing.getQuantity() + item.getQuantity(),
                    existing.getBuyPrice(),
                    existing.getImageUrl(),
                    existing.getHaPrice()
                ));
            }
            else
            {
                int[] prices    = PriceUtil.resolvePrices(canonicalId, itemManager);
                int buyPrice    = prices[0];
                int haPrice     = prices[1];
                String nameLower = rawName.toLowerCase();
                String name      = nameLower.endsWith(" (members)") ? rawName.substring(0, rawName.length() - 10) : rawName;
                String imageUrl  = "https://static.runelite.net/cache/item/icon/" + canonicalId + ".png";
                byId.put(canonicalId, new PortfolioItem(canonicalId, name, item.getQuantity(), buyPrice, imageUrl, haPrice));
            }
        }

        log.info("[RuneVault][BankScan] done: {} items, {} placeholders, {} empty, {} coin slots",
            byId.size(), placeholderCount, emptyCount, coinsCount);

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
