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
    public static final String SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx4cGJvenRlb3JwaWpyb3dodWJsIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQwOTIxODUsImV4cCI6MjA4OTY2ODE4NX0.24lluvW8BjkhCl9ezLYyAVOev1ILOG9k9mqJTxf8MBU";

    private RuneVaultBuildConfig() {}
}
