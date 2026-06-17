package com.mojang.minecraftpe.store;

/**
 * @Author AnswerDev
 * @Date 2024/07/12 21:49
 */
public class Product {
    public String mCurrencyCode;
    public String mId;
    public String mPrice;
    public String mUnformattedPrice;

    public Product(String id, String price, String currencyCode, String unformattedPrice) {
        mId = id;
        mPrice = price;
        mCurrencyCode = currencyCode;
        mUnformattedPrice = unformattedPrice;
    }
}
