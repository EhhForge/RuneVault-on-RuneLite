package com.runevault;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PortfolioItem
{
    private int itemId;
    private String itemName;
    private int quantity;
    private int buyPrice;    // 0 = unknown (e.g. bank scan)
    private String imageUrl; // nullable
}
