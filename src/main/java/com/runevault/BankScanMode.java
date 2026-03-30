package com.runevault;

public enum BankScanMode
{
    PROMPT("Prompt"),
    AUTO("Auto");

    private final String label;

    BankScanMode(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
