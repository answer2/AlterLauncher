package com.mojang.minecraftpe.store;

/**
 * @Author AnswerDev
 * @Date 2024/07/12 21:52
 */
public interface StoreListener {
    void onPurchaseCanceled(String str);

    void onPurchaseFailed(String str);

    void onPurchaseSuccessful(String str, String str2);

    void onQueryProductsFail();

    void onQueryProductsSuccess(Product[] productArr);

    void onQueryPurchasesFail();

    void onQueryPurchasesSuccess(Purchase[] purchaseArr);

    void onStoreInitialized(boolean z);
}
