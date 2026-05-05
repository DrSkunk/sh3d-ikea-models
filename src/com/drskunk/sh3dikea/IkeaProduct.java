package com.drskunk.sh3dikea;

/** Lightweight DTO for IKEA search results. */
public final class IkeaProduct {
    public final String itemNo;
    public final String name;
    public final String typeName;
    public final String mainImageUrl;
    public final String mainImageAlt;
    public final String pipUrl;

    public IkeaProduct(String itemNo, String name, String typeName,
                       String mainImageUrl, String mainImageAlt, String pipUrl) {
        this.itemNo = itemNo;
        this.name = name;
        this.typeName = typeName;
        this.mainImageUrl = mainImageUrl;
        this.mainImageAlt = mainImageAlt;
        this.pipUrl = pipUrl;
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
