package com.runevault;

import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Pricing logic for OSRS items.
 *
 * Rules (in order):
 *  1. Tradeable item          → GE market price
 *  2. Non-tradeable with parent (ItemVariationMapping) → parent's GE market price
 *  3. Non-tradeable, no parent → HA value (alch * 0.6)
 *  4. None of the above       → 0
 */
public final class PriceUtil
{
    private PriceUtil() {}

    /**
     * Returns {marketPrice, haPrice} as a two-element int array for the given canonical item ID.
     * Index 0 = buyPrice (what to store), index 1 = haPrice.
     */
    public static int[] resolvePrices(int canonicalId, ItemManager itemManager)
    {
        net.runelite.api.ItemComposition comp = itemManager.getItemComposition(canonicalId);

        // Rule 1: tradeable — use GE price. Store 0 if not cached (app uses current market
        // price as cost basis for bank items, so 0 = "unknown, treat as current market").
        if (comp.isTradeable())
        {
            int market = itemManager.getItemPrice(canonicalId);
            int ha     = (int) Math.floor(comp.getPrice() * 0.6);
            return new int[]{ market, ha };
        }

        // Rule 2: non-tradeable with a parent — use parent's GE price. Same 0-if-unknown rule.
        int parent = ItemVariationMapping.map(canonicalId);
        if (parent != canonicalId)
        {
            int market = itemManager.getItemPrice(parent);
            int ha     = (int) Math.floor(comp.getPrice() * 0.6);
            return new int[]{ market, ha };
        }

        // Rule 3: non-tradeable, no parent — try own GE price (some charged items like
        // Dragonfire shield have a cached price despite isTradeable()=false), then HA.
        int selfMarket = itemManager.getItemPrice(canonicalId);
        int ha         = (int) Math.floor(comp.getPrice() * 0.6);
        if (selfMarket > 0)
        {
            return new int[]{ selfMarket, ha };
        }
        if (ha > 0)
        {
            return new int[]{ ha, ha };
        }

        // Rule 4: nothing — zero
        return new int[]{ 0, 0 };
    }
}
