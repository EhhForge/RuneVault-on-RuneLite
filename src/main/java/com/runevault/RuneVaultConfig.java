package com.runevault;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("runevault")
public interface RuneVaultConfig extends Config
{
    // -------------------------------------------------------------------------
    // Account  (link-code auth — no email/password needed)
    // -------------------------------------------------------------------------

    @ConfigSection(
        name = "Account",
        description = "Connect your Rune Vault account",
        position = 0
    )
    String accountSection = "account";

    @ConfigItem(
        keyName = "linkCode",
        name = "Link Code",
        description = "Enter the 6-character code shown in the Rune Vault app under Settings → Connect RuneLite Plugin",
        section = accountSection,
        position = 0
    )
    default String linkCode()
    {
        return "";
    }

    @ConfigItem(keyName = "linkCode", name = "", description = "")
    void setLinkCode(String code);

    @ConfigItem(
        keyName = "connectNow",
        name = "Connect",
        description = "Check this after entering your Link Code to connect to Rune Vault",
        section = accountSection,
        position = 1
    )
    default boolean connectNow()
    {
        return false;
    }

    @ConfigItem(keyName = "connectNow", name = "", description = "")
    void setConnectNow(boolean value);

    // -------------------------------------------------------------------------
    // Sync features
    // -------------------------------------------------------------------------

    @ConfigSection(
        name = "Sync Features",
        description = "Choose what to sync to Rune Vault",
        position = 1
    )
    String syncSection = "sync";

    @ConfigItem(
        keyName = "syncGeTrades",
        name = "Sync GE Trades",
        description = "Automatically add bought items and remove sold items from your portfolio",
        section = syncSection,
        position = 0
    )
    default boolean syncGeTrades()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackPickups",
        name = "Track Item Pickups",
        description = "Add items picked up from the ground to your portfolio",
        section = syncSection,
        position = 1
    )
    default boolean trackPickups()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackDropsAndSales",
        name = "Track Drops & Sales",
        description = "Remove items dropped or sold from your portfolio",
        section = syncSection,
        position = 2
    )
    default boolean trackDropsAndSales()
    {
        return true;
    }

    @ConfigItem(
        keyName = "syncCash",
        name = "Sync Cash Stack",
        description = "Keep your coin stack updated in Rune Vault",
        section = syncSection,
        position = 3
    )
    default boolean syncCash()
    {
        return true;
    }

    // -------------------------------------------------------------------------
    // Bank scanning
    // -------------------------------------------------------------------------

    @ConfigSection(
        name = "Bank Scanning",
        description = "Optional: sync your bank contents to Rune Vault",
        position = 2
    )
    String bankSection = "bank";

    @ConfigItem(
        keyName = "bankScanEnabled",
        name = "Enable Bank Scan",
        description = "Scan your bank when it opens and sync contents to portfolio",
        section = bankSection,
        position = 0
    )
    default boolean bankScanEnabled()
    {
        return false;
    }

    @ConfigItem(
        keyName = "bankScanMode",
        name = "Scan Mode",
        description = "Prompt: ask before syncing. Auto: sync silently.",
        section = bankSection,
        position = 1
    )
    default BankScanMode bankScanMode()
    {
        return BankScanMode.PROMPT;
    }

    @ConfigItem(
        keyName = "bankOverwriteDuplicates",
        name = "Overwrite Duplicates",
        description = "Update quantity and icon of items already in your portfolio when bank is scanned",
        section = bankSection,
        position = 2
    )
    default boolean bankOverwriteDuplicates()
    {
        return true;
    }

    @ConfigItem(
        keyName = "bankRemoveMissing",
        name = "Remove Missing Items",
        description = "Remove portfolio items that are no longer in your bank",
        section = bankSection,
        position = 3
    )
    default boolean bankRemoveMissing()
    {
        return false;
    }

    // -------------------------------------------------------------------------
    // Advanced
    // -------------------------------------------------------------------------

    @ConfigSection(
        name = "Advanced",
        description = "Advanced sync settings",
        position = 3,
        closedByDefault = true
    )
    String advancedSection = "advanced";

    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug Logging",
        description = "Print sync activity to the RuneLite console",
        section = advancedSection,
        position = 0
    )
    default boolean debugLogging()
    {
        return false;
    }

    // -------------------------------------------------------------------------
    // Internal (not shown in UI)
    // -------------------------------------------------------------------------

    @ConfigItem(keyName = "authToken",   name = "", description = "", hidden = true)
    default String authToken() { return ""; }

    @ConfigItem(keyName = "authToken",   name = "", description = "")
    void setAuthToken(String token);

    @ConfigItem(keyName = "refreshToken", name = "", description = "", hidden = true)
    default String refreshToken() { return ""; }

    @ConfigItem(keyName = "refreshToken", name = "", description = "")
    void setRefreshToken(String token);

    @ConfigItem(keyName = "linkedUserId", name = "", description = "", hidden = true)
    default String linkedUserId() { return ""; }

    @ConfigItem(keyName = "linkedUserId", name = "", description = "")
    void setLinkedUserId(String userId);
}
