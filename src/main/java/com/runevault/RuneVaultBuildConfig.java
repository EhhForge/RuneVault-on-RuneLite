package com.runevault;

/**
 * Build-time constants for the Rune Vault plugin.
 *
 * SUPABASE_ANON_KEY is the Supabase anonymous (public) key. It is intentionally
 * committed here — it is designed to be client-facing and is compiled into every
 * user's installed JAR, the same as it would be in a mobile or web app.
 * Row-Level Security on the Supabase project enforces access control.
 */
public final class RuneVaultBuildConfig
{
    public static final String SUPABASE_URL      = "https://lxpbozteorpijrowhubl.supabase.co";
    public static final String SUPABASE_ANON_KEY = "sb_publishable__K0u7EoZTfXWZQyNwSF55g_XI0R4vZB";

    private RuneVaultBuildConfig() {}
}
