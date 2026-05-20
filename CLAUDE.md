# Rune Vault — RuneLite plugin

Java RuneLite plugin that syncs GE trades, inventory pickups, drops, and bank contents to the Rune Vault Supabase backend. **This repo is PUBLIC and reviewed by RuneLite Hub maintainers** — treat every commit accordingly.

Plugin Hub PR: `EhhForge/plugin-hub` → branch `EhhForge-patch-1` → `plugins/rune-vault`. The `commit=` line points at a specific SHA in THIS repo; every push here must be followed by a matching hash bump there.

## Stack

- Java + RuneLite API (`latest.release`)
- OkHttp3 + Gson
- Lombok (`@Slf4j` for logging — NEVER `System.out.println`)
- Gradle 8.10 (Plugin Hub CI uses 8.10 — don't use newer-only properties)
- `build=standard` in `runelite-plugin.properties`

## Dev launch

```bash
./gradlew run          # preferred — NOT manual JAR install
```

- Launcher: `src/test/java/com/runevault/RuneVaultPluginTest.java`
- Mac JVM args: `--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED` + two `java.base` opens
- Run with `--developer-mode` arg

## Source map

| File | Role |
|---|---|
| `RuneVaultPlugin.java` | Entry. Event subscriptions, `ScheduledExecutorService` for off-EDT auth. Wires `RealtimeClient` to `SupabaseClient`. |
| `SupabaseClient.java` | All network I/O. Auth, token refresh, cache, sync. Carries critical invariants (see below). |
| `RealtimeClient.java` | WebSocket to Supabase Realtime. Phoenix protocol. In-band token refresh via leave+rejoin. |
| `RuneVaultPanel.java` | Side panel UI: link code entry, status label, "Open Portfolio" button. |
| `RuneVaultConfig.java` | Config fields (linkCode, connectNow, hidden auth tokens, sync toggles, bank scan settings). |
| `GETracker.java` | `GrandExchangeOfferChanged` events. Per-slot `previousStates[]` array prevents login-replay double counting. |
| `InventoryTracker.java` | Inventory diffs. `pendingPickup`/`pendingDrop` flags from menu clicks gate cash/item attribution. |
| `BankTracker.java` | Bank widget open → bulk upsert of all bank items + total cash (bank + inventory coins). Keys by `canonicalId` (NOT `tradeableId`). |
| `PriceUtil.java` | 4-rule pricing hierarchy: tradeable own → parent's GE → own cached / HA → 0. Do NOT use `ItemVariationMapping.map()` unconditionally. |
| `RuneVaultBuildConfig.java` | Generated at build time. Holds Supabase URL + anon key (legacy JWT still active as of 2026-04-26). |
| `BankScanMode.java` | Enum for bank scan settings. |

## Critical invariants — do NOT refactor without reading

### `disconnect()` MUST be synchronous HTTP PATCH
`SupabaseClient.disconnect()`. Async caused a race where a stale PATCH lands after `resetPluginStateForUser`, re-disconnecting on the next link. Same for `clearDisconnectFlagForUser`.

### `disconnect()` patches by `user_id`, not by profile ID
Clears all profiles' `plugin_active`/`plugin_disconnect_requested`/`plugin_last_seen`. Profile-scoped patches left stale rows on other profiles that confused `refreshPluginStatus`.

### `RealtimeClient.connect()` MUST reset `closed=false`
Without this, the second disconnect from the app has no effect — the WebSocket never reopens after the first disconnect.

### `cachedProfileId` is intentionally null after exchange
Resolved by `switchProfileForUsername()` from RS username. The two-query approach: `rs_username=ilike.{name}` first, fall back to `name=ilike.{name}`. Prevents ghost profile name-match steals.

### On 401 + refresh fail → `clearStoredCredentials()`
Required to allow re-link. Don't add backoff/retry around this path.

### `Thread.currentThread().interrupt()` IS correct, not a "silent swallow"
Auditors flag it. It re-sets the interrupt flag — the canonical Java pattern.

### `synchronized` on `getAuthenticatedProfileId()` returns captured local
Not a TOCTOU vulnerability. Code comment documents this.

### Bank sync
- `bulkUpsertItems` → `on_conflict=user_id,profile_id,item_id,game`.
- `bankScanGeneration` counter prevents stale async removes from newer scans.
- `removeItemsMissingFromBank` — `id=in.(...)` can exceed PostgREST URL limits at ~800 items. **Chunk to ~150** (Apr-26 audit caught this still open in `SupabaseClient.java:942-944`).
- `buy_date` is nullable in `portfolio_items` — bank scans don't have a known purchase date.

### `Thread.sleep` in OkHttp callbacks
Partially migrated to `retryScheduler.schedule()` on a daemon `ScheduledExecutorService` (Apr-13). Apr-26 audit caught 4 remaining at `SupabaseClient.java:612, 717, 908, 1338`. Finish the migration if you touch those callsites.

## Plugin Hub compliance

- `runelite-plugin.properties` must have `build=standard` (or `build=gradle`).
- JNI is prohibited.
- No bundled native libs.
- Plugin description and metadata must be on-brand and accurate.
- Anything that ships to users is reviewed; assume the worst about what attackers will look for.

## Pre-commit checklist — RUN BEFORE EVERY COMMIT

1. No `.DS_Store`, `Thumbs.db`, OS metadata.
2. No absolute local paths (`/Users/<your-username>/...`, `/home/<your-username>/...`).
3. No `System.out.println` — use `log.debug/info/warn/error` (Lombok).
4. No leftover `TODO`/`FIXME`/`HACK` comments.
5. No commented-out code blocks.
6. No unused imports.
7. No hardcoded credentials, JWTs, or non-public secrets.
8. No real RS usernames / UUIDs / personal identifiers in example code.
9. Author = **EhhForge** with a noreply email. Never real name.
10. After any `git filter-repo` run, grep for replacement strings:
    ```bash
    grep -rn "SUPABASE_PROJECT_URL_REDACTED\|SUPABASE_ANON_KEY_REDACTED\|REPLACEME" src/
    ```

## After every push — REMEMBER THE PLUGIN-HUB BUMP

```
Repo:   EhhForge/plugin-hub
Branch: EhhForge-patch-1
File:   plugins/rune-vault
Edit:   commit=<new 40-char SHA from git rev-parse HEAD here>
```

Push the plugin-hub branch. Re-request review from RuneLite maintainers if applicable.

## Deep context

Run the `runevault-audit` skill (or say "audit") for full hotspot context, false-positive lists, and the auth-flow architecture brief.
