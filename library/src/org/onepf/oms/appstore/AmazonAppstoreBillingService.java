/*
 * Copyright 2012-2014 One Platform Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onepf.oms.appstore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.json.JSONException;
import org.json.JSONObject;
import org.onepf.oms.AppstoreInAppBillingService;
import org.onepf.oms.OpenIabHelper;
import org.onepf.oms.SkuManager;
import org.onepf.oms.appstore.googleUtils.IabHelper;
import org.onepf.oms.appstore.googleUtils.IabResult;
import org.onepf.oms.appstore.googleUtils.Inventory;
import org.onepf.oms.appstore.googleUtils.Purchase;
import org.onepf.oms.appstore.googleUtils.SkuDetails;
import org.onepf.oms.util.Logger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.amazon.inapp.purchasing.BasePurchasingObserver;
import com.amazon.inapp.purchasing.GetUserIdResponse;
import com.amazon.inapp.purchasing.Item;
import com.amazon.inapp.purchasing.ItemDataResponse;
import com.amazon.inapp.purchasing.Offset;
import com.amazon.inapp.purchasing.PurchaseResponse;
import com.amazon.inapp.purchasing.PurchaseUpdatesResponse;
import com.amazon.inapp.purchasing.PurchasingManager;
import com.amazon.inapp.purchasing.Receipt;
import com.amazon.inapp.purchasing.SubscriptionPeriod;

/**
 * Amazon billing service impl
 *
 * @author Ruslan Sayfutdinov, Oleg Orlov
 * @since 16.04.13
 */
public class AmazonAppstoreBillingService extends BasePurchasingObserver implements AppstoreInAppBillingService {

    // ========================================================================
    // PURCHASE RESPONSE JSON KEYS
    // ========================================================================
    public static final String JSON_KEY_ORDER_ID = "orderId";
    public static final String JSON_KEY_PRODUCT_ID = "productId";
    public static final String JSON_KEY_RECEIPT_ITEM_TYPE = "itemType";
    public static final String JSON_KEY_PURCHASE_STATUS = "purchaseStatus";
    public static final String JSON_KEY_USER_ID = "userId";
    public static final String JSON_KEY_RECEIPT_PURCHASE_TOKEN = "purchaseToken";

    private Map<String, IabHelper.OnIabPurchaseFinishedListener> mRequestListeners = new HashMap<String, IabHelper.OnIabPurchaseFinishedListener>();

    /**
     * Only for verification all requests are for the same user
     * <p>Not expected to be undefined after setup is completed
     * <p>Initialized at {@link #onGetUserIdResponse(GetUserIdResponse)} if GetUserIdRequestStatus.SUCCESSFUL
     * durint startSetup().
     */
    private String currentUserId;

    /**
     * To process {@link #queryInventory(boolean, List, List)} request following steps are done:
     * <p>
     * {@link #queryInventory(boolean, List, List)} - initialize inventory object, request purchase data by
     * <code>initiatePurchaseUpdatesRequest()</code> and locks thread on inventoryLatch.
     * After whole purchase data is recieved request SKU details by <code>initiateItemDataRequest()</code>
     * <br>
     * {@link #onPurchaseUpdatesResponse(PurchaseUpdatesResponse)} - triggered by Amazon SDK.
     * Handles purchases data chunk by chunk. Releases inventoryLatch lock after last chunk is handled
     * <p>
     * {@link #onItemDataResponse(ItemDataResponse)} - triggered by Amazon SDK.
     * Handles items data chunk by chunk. Releases inventoryLatch lock after last chunk is handled
     * <p/>
     * <p>NOTES:</p>
     * Amazon SDK may trigger on*Response() before queryInventory() is called. It happens
     * when confirmation of processed purchase was not delivered to application (when applications
     * crashes or relaunched). So inventory object must be not null as well as inventoryLatch
     */
    private Inventory inventory = new Inventory();
    private CountDownLatch inventoryLatch = new CountDownLatch(0);

    /**
     * If not null will be notified from
     */
    private IabHelper.OnIabSetupFinishedListener setupListener;

    public AmazonAppstoreBillingService(Context context) {
        super(context);
    }

    /**
     * @param listener - is triggered when {@link #onGetUserIdResponse(GetUserIdResponse)} happens
     */
    @Override
    public void startSetup(IabHelper.OnIabSetupFinishedListener listener) {
        PurchasingManager.registerObserver(this);
        this.setupListener = listener;
    }

    @Override
    public void onSdkAvailable(final boolean isSandboxMode) {
        Logger.v("onSdkAvailable() isSandBox: ", isSandboxMode);
        PurchasingManager.initiateGetUserIdRequest();
    }

    @Override
    public void onGetUserIdResponse(final GetUserIdResponse userIdResponse) {
        Logger.d("onGetUserIdResponse() reqId: ", userIdResponse.getRequestId(),
                ", status: ", userIdResponse.getUserIdRequestStatus());

        if (userIdResponse.getUserIdRequestStatus() == GetUserIdResponse.GetUserIdRequestStatus.SUCCESSFUL) {
            final String userId = userIdResponse.getUserId();
            Logger.d("Set current userId: ", userId);
            this.currentUserId = userId;
            if (setupListener != null) {
                setupListener.onIabSetupFinished(new IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK, "Setup successful."));
                setupListener = null;
            }
        } else {
            Logger.d("onGetUserIdResponse() Unable to get user ID");
            if (setupListener != null) {
                setupListener.onIabSetupFinished(new IabResult(IabHelper.BILLING_RESPONSE_RESULT_ERROR, "Unable to get userId"));
                setupListener = null;
            }
        }
    }

    @Override
    public Inventory queryInventory(boolean querySkuDetails, List<String> moreItemSkus, List<String> moreSubsSkus) {
        Logger.d("queryInventory() querySkuDetails: ", querySkuDetails, " moreItemSkus: ",
                moreItemSkus, " moreSubsSkus: ", moreSubsSkus);
        inventory = new Inventory();
        inventoryLatch = new CountDownLatch(1);
        PurchasingManager.initiatePurchaseUpdatesRequest(Offset.BEGINNING);
        try {
            inventoryLatch.await();
        } catch (InterruptedException e) {
            return null;
        }
        if (querySkuDetails) {
            Set<String> querySkus = new HashSet<String>(inventory.getAllOwnedSkus());
            if (moreItemSkus != null) {
                querySkus.addAll(moreItemSkus);
            }
            if (moreSubsSkus != null) {
                querySkus.addAll(moreSubsSkus);
            }
            if (!querySkus.isEmpty()) {
                inventoryLatch = new CountDownLatch(1);
                HashSet<String> queryStoreSkus = new HashSet<String>(querySkus.size());
                for (String sku : querySkus) {
                    queryStoreSkus.add(SkuManager.getInstance().getStoreSku(OpenIabHelper.NAME_AMAZON, sku));
                }
                PurchasingManager.initiateItemDataRequest(queryStoreSkus);
                try {
                    inventoryLatch.await();
                } catch (InterruptedException e) {
                    Logger.w("queryInventory() SkuDetails fetching interrupted");
                }
            }
        }
        Logger.d("queryInventory() finished. Inventory size: ", inventory.getAllOwnedSkus().size());
        return inventory;
    }

    @Override
    public void onPurchaseUpdatesResponse(final PurchaseUpdatesResponse purchaseUpdatesResponse) {
        Logger.v("onPurchaseUpdatesResponse() reqStatus: ", purchaseUpdatesResponse.getPurchaseUpdatesRequestStatus(),
                "reqId: ", purchaseUpdatesResponse.getRequestId());

        if ((currentUserId != null) && !currentUserId.equals(purchaseUpdatesResponse.getUserId())) {

            Logger.w("onPurchaseUpdatesResponse() Current UserId: ", currentUserId,
                    ", purchase UserId: ", purchaseUpdatesResponse.getUserId());
            if (inventoryLatch != null) {
                inventoryLatch.countDown();
            }
            return;
        }

        if (Logger.isLoggable()) {
            // TODO: do something with this
            for (final String sku : purchaseUpdatesResponse.getRevokedSkus()) {
                Logger.v("Revoked Sku:", sku);
            }
        }

        switch (purchaseUpdatesResponse.getPurchaseUpdatesRequestStatus()) {
            case SUCCESSFUL:
                for (final Receipt receipt : purchaseUpdatesResponse.getReceipts()) {
                    final String storeSku = receipt.getSku();
                    Purchase purchase;
                    switch (receipt.getItemType()) {
                        case ENTITLED:
                            purchase = new Purchase(OpenIabHelper.NAME_AMAZON);
                            purchase.setItemType(IabHelper.ITEM_TYPE_INAPP);
                            purchase.setSku(SkuManager.getInstance().getSku(OpenIabHelper.NAME_AMAZON, storeSku));
                            inventory.addPurchase(purchase);
                            Logger.d("Add to inventory SKU: ", storeSku);
                            break;

                        case SUBSCRIPTION:
                            final SubscriptionPeriod subscriptionPeriod = receipt.getSubscriptionPeriod();
                            if (subscriptionPeriod.getEndDate() == null) {
                                purchase = new Purchase(OpenIabHelper.NAME_AMAZON);
                                purchase.setItemType(IabHelper.ITEM_TYPE_SUBS);
                                purchase.setSku(SkuManager.getInstance().getSku(OpenIabHelper.NAME_AMAZON, storeSku));
                                inventory.addPurchase(purchase);
                                Logger.d("Add subscription to inventory SKU: ", storeSku);
                            }

                            break;

                    }
                }

                final Offset newOffset = purchaseUpdatesResponse.getOffset();
                if (purchaseUpdatesResponse.isMore()) {
                    Logger.v("Initiating Another Purchase Updates with offset: ", newOffset);
                    PurchasingManager.initiatePurchaseUpdatesRequest(newOffset);
                } else {
                    inventoryLatch.countDown();
                }
                return;
            case FAILED:
                inventoryLatch.countDown();
                return;
        }
        inventoryLatch.countDown();
    }

    @Override
    public void onItemDataResponse(final ItemDataResponse itemDataResponse) {
        Logger.v("onItemDataResponse() reqStatus: ", itemDataResponse.getItemDataRequestStatus(),
                ", reqId: ", itemDataResponse.getRequestId());
        switch (itemDataResponse.getItemDataRequestStatus()) {
            case SUCCESSFUL_WITH_UNAVAILABLE_SKUS:
                // Skus that you can not purchase will be here.
                if (Logger.isLoggable()) {
                    for (final String s : itemDataResponse.getUnavailableSkus()) {
                        Logger.v("Unavailable SKU:", s);
                    }
                }
            case SUCCESSFUL:
                // Information you'll want to display about your IAP items is here
                // In this example we'll simply log them.
                final Map<String, Item> items = itemDataResponse.getItemData();
                for (final String key : items.keySet()) {
                    Item i = items.get(key);
                    final String storeSku = i.getSku();
                    if (Logger.isLoggable()) {
                        Logger.v(String.format("Item: %s\n Type: %s\n SKU: %s\n Price: %s\n Description: %s\n",
                                i.getTitle(), i.getItemType(), storeSku, i.getPrice(), i.getDescription()));
                    }
                    String itemType = i.getItemType() == Item.ItemType.SUBSCRIPTION ? IabHelper.ITEM_TYPE_SUBS : IabHelper.ITEM_TYPE_INAPP;
                    SkuDetails skuDetails = new SkuDetails(itemType,
                            SkuManager.getInstance().getSku(OpenIabHelper.NAME_AMAZON, i.getSku()),
                            i.getTitle(), i.getPrice(), i.getDescription());
                    inventory.addSkuDetails(skuDetails);
                }
                break;
        }
        inventoryLatch.countDown();
    }

    @Override
    public void launchPurchaseFlow(Activity act, String sku, String itemType, int requestCode, IabHelper.OnIabPurchaseFinishedListener listener, String extraData) {
        String requestId = PurchasingManager.initiatePurchaseRequest(sku);
        storeRequestListener(requestId, listener);
    }

    @Override
    public void onPurchaseResponse(final PurchaseResponse purchaseResponse) {
         Logger.v("onPurchaseResponse() PurchaseRequestStatus:", purchaseResponse.getPurchaseRequestStatus());

        IabResult result = null;
        Purchase purchase = new Purchase(OpenIabHelper.NAME_AMAZON);

        if ((currentUserId != null) && !currentUserId.equals(purchaseResponse.getUserId())) {
            Logger.w("onPurchaseResponse() userId: ", currentUserId, ", purchase.userId: ", purchaseResponse.getUserId());
            result = new IabResult(IabHelper.BILLING_RESPONSE_RESULT_ERROR, "userId doesn't match purchase.userId");
        } else {
            switch (purchaseResponse.getPurchaseRequestStatus()) {
                case SUCCESSFUL :
                    final Receipt receipt = purchaseResponse.getReceipt();
                    final String storeSku = receipt.getSku();

                    purchase.setOriginalJson(generateOriginalJson(purchaseResponse));
                    purchase.setSku(SkuManager.getInstance().getSku(OpenIabHelper.NAME_AMAZON, storeSku));
                    switch (receipt.getItemType()) {
                        case CONSUMABLE:
                        case ENTITLED:
                            purchase.setItemType(IabHelper.ITEM_TYPE_INAPP);
                            break;
                        case SUBSCRIPTION:
                            purchase.setItemType(IabHelper.ITEM_TYPE_SUBS);
                            break;
                    }
                    //printReceipt(purchaseResponse.getReceipt());
                    result = new IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK, "Success");
                    break;
                case ALREADY_ENTITLED :
                    result = new IabResult(IabHelper.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED, "Already owned");
                    break;
                case FAILED :
                    result = new IabResult(IabHelper.BILLING_RESPONSE_RESULT_USER_CANCELED, "Purchase failed");
                    break;
                case INVALID_SKU :
                    result = new IabResult(IabHelper.BILLING_RESPONSE_RESULT_ERROR, "Invalid sku");
                    break;
            }
        }
        IabHelper.OnIabPurchaseFinishedListener listener = getRequestListener(purchaseResponse.getRequestId());
        if (listener != null) {
            listener.onIabPurchaseFinished(result, purchase);
        } else {
            Logger.e("Something went wrong: PurchaseFinishedListener is null");
        }
    }

    /**
     * Converts purchase response to json for transfer with purchase object
     *   
     * <pre>
     {
        "orderId"           : "purchaseResponse.getRequestId"
        "productId"         : "receipt.getSku"
        "purchaseStatus"    : "purchaseRequestStatus.name"
        "userId"            : "purchaseResponse.getUserId()" // can be null
        "itemType"          : "receipt.getItemType().name()" // if non-null
        "purchaseToken"     : "receipt.purchaseToken"
     } </pre>
     * 
     * @param purchaseResponse
     * @return
     */
    private String generateOriginalJson(PurchaseResponse purchaseResponse) {
        JSONObject json = new JSONObject();
        try {
            Receipt receipt = purchaseResponse.getReceipt();
            json.put(JSON_KEY_ORDER_ID, purchaseResponse.getRequestId());
            json.put(JSON_KEY_PRODUCT_ID, receipt.getSku());
            if (purchaseResponse.getPurchaseRequestStatus() != null)
                json.put(JSON_KEY_PURCHASE_STATUS, purchaseResponse.getPurchaseRequestStatus().name());
            json.put(JSON_KEY_USER_ID, purchaseResponse.getUserId());
            if (receipt.getItemType() != null) json.put(JSON_KEY_RECEIPT_ITEM_TYPE, receipt.getItemType().name());
            json.put(JSON_KEY_RECEIPT_PURCHASE_TOKEN, receipt.getPurchaseToken());
            Logger.d("generateOriginalJson(): JSON\n", json);
        } catch (JSONException e) {
            Logger.e("generateOriginalJson() failed to generate JSON", e);
        }
        return json.toString();
    }

    @Override
    public void consume(Purchase itemInfo) {
        // Nothing to do here
    }

    @Override
    public boolean subscriptionsSupported() {
        return true;
    }

    @Override
    public void dispose() {
        
    }

    @Override
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        return false;
    }
    
    private void storeRequestListener(String requestId, IabHelper.OnIabPurchaseFinishedListener listener) {
        mRequestListeners.put(requestId, listener);
    }

    public IabHelper.OnIabPurchaseFinishedListener getRequestListener(String requestId) {
        return mRequestListeners.get(requestId);
    }
}
