package com.drskunk.sh3dikea;

import java.util.Collections;
import java.util.List;

/** Lightweight DTO for IKEA search results. */
public final class IkeaProduct {
    public final String itemNo;
    public final String name;
    public final String typeName;
    public final String measureRef;        // e.g. "30 1/8x57 5/8 \"" — IKEA's localised size text
    public final List<ProductColor> colors; // never null; may be empty
    public final String mainImageUrl;
    public final String mainImageAlt;
    public final String pipUrl;

    public IkeaProduct(String itemNo, String name, String typeName,
                       String measureRef, List<ProductColor> colors,
                       String mainImageUrl, String mainImageAlt, String pipUrl) {
        this.itemNo = itemNo;
        this.name = name;
        this.typeName = typeName;
        this.measureRef = measureRef;
        this.colors = colors == null ? Collections.emptyList() : colors;
        this.mainImageUrl = mainImageUrl;
        this.mainImageAlt = mainImageAlt;
        this.pipUrl = pipUrl;
    }

    /** A single colour entry from the {@code colors} field of an IKEA search hit. */
    public static final class ProductColor {
        public final String name; // e.g. "white"
        public final String hex;  // 6 hex chars, no leading '#'; null when missing
        public ProductColor(String name, String hex) { this.name = name; this.hex = hex; }
    }

    /** "12345678" → "123.456.78" */
    public static String formatItemNo(String itemNo) {
        String c = compactItemNo(itemNo);
        if (c.length() != 8) return itemNo;
        return c.substring(0, 3) + "." + c.substring(3, 6) + "." + c.substring(6, 8);
    }

    public static String compactItemNo(String itemNo) {
        return itemNo.replaceAll("[^0-9]", "");
    }

    public static boolean isItemNo(String s) {
        return s != null && s.matches("\\d{3}\\.?\\d{3}\\.?\\d{2}");
    }
}
