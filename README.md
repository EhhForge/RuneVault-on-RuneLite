# Rune Vault — RuneLite Plugin

Rune Vault is a portfolio tracker for Old School RuneScape. This plugin connects your RuneLite client to the Rune Vault app, automatically syncing your bank contents, Grand Exchange trades, and item pickups/drops so your portfolio stays up to date in real time.

> **Note:** The Rune Vault mobile app is not yet available on the App Store or Google Play. We are currently in pre-launch and are happy to share early access with the RuneLite team so you can try it firsthand. If you'd like to test the full experience, reach out and we'll get you set up.

---

## Features

- **Bank sync** — Scans your bank when you open it and syncs all items and your total cash stack
- **GE trade tracking** — Records completed Grand Exchange buys and sells with the actual price paid
- **Item tracking** — Logs ground pickups and drops to keep your portfolio accurate
- **Real-time updates** — Changes made in the app (adding/removing items, linking profiles) are reflected in the plugin instantly via WebSocket
- **Public profiles** — Optionally share a read-only view of your portfolio with a public link
- **Multi-profile support** — Manage separate portfolios for different accounts

---

## How It Works

1. Install the Rune Vault plugin from the Plugin Hub
2. Open the Rune Vault side panel in RuneLite (look for the gold "RV" icon in the sidebar)
3. In the Rune Vault app, go to **Settings → Connect RuneLite Plugin** to generate a 6-character link code
4. Enter the code in the plugin panel and click **Connect**
5. The plugin is now linked — open your bank to trigger the first sync

Once linked, the plugin runs silently in the background. Your portfolio updates automatically as you play.

---

## Configuration

All settings live under **RuneLite → Plugin config → Rune Vault**, and the most-used toggles are mirrored in the side panel.

**Sync Features**

| Option | Default | Description |
|---|---|---|
| Sync GE Trades | On | Adds bought items / removes sold items on GE completion |
| Track Item Pickups | On | Adds items picked up off the ground |
| Track Drops & Sales | On | Removes items dropped or sold |
| Sync Cash Stack | On | Keeps your coin total updated (bank + inventory) |

**Bank Scanning**

| Option | Default | Description |
|---|---|---|
| Enable Bank Scan | On | Scans your bank when you open it |
| Scan Mode | Prompt | `Prompt` asks before syncing each session, `Auto` syncs silently |
| Overwrite Duplicates | On | Updates quantity/icon of existing portfolio items |
| Remove Missing Items | Off | Removes portfolio items no longer in your bank (destructive — opt-in) |

**Advanced**

| Option | Default | Description |
|---|---|---|
| Debug Logging | Off | Verbose console logs for troubleshooting |

---

## Data Synced

The plugin only syncs data that you have explicitly opted into:

- Item IDs, names, quantities, and prices (from your bank and GE trades)
- Your RS username (used to identify your profile)
- Coin totals (bank + inventory)
- GE trade history (completed trades only — buy/sell price and quantity)

All data is sent over HTTPS to a private backend. No data is shared with third parties. Token credentials are stored in RuneLite's encrypted config storage.

---

## Privacy

- All network calls go to Rune Vault's own Supabase backend over HTTPS
- No analytics, ad networks, or third-party tracking
- Tokens are never logged
- You can unlink the plugin at any time, which clears all stored credentials locally

---

## Building from Source

Prerequisites: JDK 11+ (Gradle wrapper is included).

```bash
# Launch RuneLite with the plugin loaded (development)
./gradlew run

# Build the plugin JAR
./gradlew build
```

The Supabase backend URL and public anonymous key are compiled into the plugin (same pattern as a web or mobile app — these are designed-public values, and access is enforced server-side by Row-Level Security).
