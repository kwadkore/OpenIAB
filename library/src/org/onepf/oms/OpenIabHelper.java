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

package org.onepf.oms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.onepf.oms.appstore.AmazonAppstore;
import org.onepf.oms.appstore.GooglePlay;
import org.onepf.oms.appstore.NokiaStore;
import org.onepf.oms.appstore.OpenAppstore;
import org.onepf.oms.appstore.googleUtils.IabException;
import org.onepf.oms.appstore.googleUtils.IabHelper;
import org.onepf.oms.appstore.googleUtils.IabHelper.OnIabSetupFinishedListener;
import org.onepf.oms.appstore.googleUtils.IabHelper.QueryInventoryFinishedListener;
import org.onepf.oms.appstore.googleUtils.IabResult;
import org.onepf.oms.appstore.googleUtils.Inventory;
import org.onepf.oms.appstore.googleUtils.Purchase;
import org.onepf.oms.appstore.googleUtils.Security;
import org.onepf.oms.util.CollectionUtils;
import org.onepf.oms.util.Logger;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

/**
 * @author Boris Minaev, Oleg Orlov, Kirill Rozov
 * @since 16.04.13
 */
public class OpenIabHelper {
    /**
     * Default timeout (in milliseconds) for check inventory in all stores.
     * For generic stores it takes 1.5 - 3sec.
     * <p/>
     * SamsungApps initialization is very time consuming (from 4 to 12 seconds).
     * TODO: Optimize: ~1sec is consumed for check account certification via account activity + ~3sec for actual setup
     */
    private static final int CHECK_INVENTORY_TIMEOUT = 10 * 1000;
    /**
     * Default timeout (in milliseconds) for discover all OpenStores on device.
     */
    private static final int DEFAULT_DISCOVER_TIMEOUT = 5 * 1000;

    private static final String BIND_INTENT = "org.onepf.oms.openappstore.BIND";

    /**
     * Used for all communication with Android services
     */
    private final Context context;

    private static final Handler notifyHandler = new Handler(Looper.getMainLooper());

    /**
     * selected appstore
     */
    private Appstore mAppstore;

    /**
     * selected appstore billing service
     */
    private AppstoreInAppBillingService mAppstoreBillingService;

    private final Options options;

    public static final int SETUP_RESULT_NOT_STARTED = -1;
    public static final int SETUP_RESULT_SUCCESSFUL = 0;
    public static final int SETUP_RESULT_FAILED = 1;
    public static final int SETUP_DISPOSED = 2;
    public static final int SETUP_IN_PROGRESS = 3;

    @MagicConstant(intValues = {SETUP_DISPOSED, SETUP_IN_PROGRESS,
            SETUP_RESULT_FAILED, SETUP_RESULT_NOT_STARTED, SETUP_RESULT_SUCCESSFUL})
    private int setupState = SETUP_RESULT_NOT_STARTED;


    // (for logging/debugging)
    // if mAsyncInProgress == true, what asynchronous operation is in progress?
    private String mAsyncOperation = "";

    // Item types
    public static final String ITEM_TYPE_INAPP = "inapp";
    public static final String ITEM_TYPE_SUBS = "subs";

    // Billing response codes
    public static final int BILLING_RESPONSE_RESULT_OK = 0;
    public static final int BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3;
    public static final int BILLING_RESPONSE_RESULT_ERROR = 6;

    public static final String NAME_GOOGLE = "com.google.play";
    public static final String NAME_AMAZON = "com.amazon.apps";
    public static final String NAME_YANDEX = "com.yandex.store";
    public static final String NAME_NOKIA = "com.nokia.nstore";

    /**
     * @param sku - application inner SKU
     * @param storeSku - shouldn't duplicate already mapped values
     * @param storeName - @see {@link IOpenAppstore#getAppstoreName()} or
     *            {@link #NAME_AMAZON} {@link #NAME_GOOGLE}
     * @deprecated Use
     *             {@link org.onepf.oms.SkuManager#mapSku(String, String, String)}
     *             <p/>
     *             Map sku and storeSku for particular store.
     *             <p/>
     *             The best approach is to use SKU that unique in universe like
     *             <code>com.companyname.application.item</code>. Such SKU fit
     *             most of stores so it doesn't need to be mapped.
     *             <p/>
     *             If best approach is not applicable use application inner SKU
     *             in code (usually it is SKU for Google Play) and map SKU from
     *             other stores using this method. OpenIAB will map SKU in both
     *             directions, so you can use only your inner SKU
     */
    public static void mapSku(String sku, String storeName, String storeSku) {
        SkuManager.getInstance().mapSku(sku, storeName, storeSku);
    }

    /**
     * @param appstoreName
     * @param sku          - inner SKU
     * @return SKU used in store for specified inner SKU
     * @see org.onepf.oms.SkuManager#mapSku(String, String, String)
     * @deprecated Use {@link org.onepf.oms.SkuManager#getStoreSku(String, String)}
     * <p/>
     * Return previously mapped store SKU for specified inner SKU
     */
    public static String getStoreSku(final String appstoreName, String sku) {
        return SkuManager.getInstance().getStoreSku(appstoreName, sku);
    }

    /**
     * @see org.onepf.oms.SkuManager#mapSku(String, String, String)
     * @deprecated Use {@link org.onepf.oms.SkuManager#getSku(String, String)}
     * <p/>
     * Return mapped application inner SKU using store name and store SKU.
     */
    public static String getSku(final String appstoreName, String storeSku) {
        return SkuManager.getInstance().getSku(appstoreName, storeSku);
    }

    /**
     * @param appstoreName for example {@link OpenIabHelper#NAME_AMAZON}
     * @return list of skus those have mappings for specified appstore
     * @deprecated Use {@link org.onepf.oms.SkuManager#getAllStoreSkus(String)}
     */
    public static List<String> getAllStoreSkus(final String appstoreName) {
        final List<String> allStoreSkus = SkuManager.getInstance().getAllStoreSkus(appstoreName);
        return allStoreSkus == null ? Collections.<String>emptyList() : allStoreSkus;
    }

    /**
     * @param storeKeys - see {@link Options#storeKeys}
     * @param context - any context is acceptable
     * @deprecated Use
     *             {@link org.onepf.oms.OpenIabHelper#OpenIabHelper(android.content.Context, org.onepf.oms.OpenIabHelper.Options)}
     *             Will be removed in 1.0 release.
     *             <p/>
     *             <p/>
     *             Simple constructor for OpenIabHelper.
     *             <p>
     *             See {@link OpenIabHelper#OpenIabHelper(Context, Options)} for
     *             details
     */
    public OpenIabHelper(Context context, Map<String, String> storeKeys) {
        this(context,
                new Options.Builder()
                        .addStoreKeys(storeKeys)
                        .build()
        );
    }

    /**
     * @param storeKeys - see
     *            {@link org.onepf.oms.OpenIabHelper.Options#getStoreKeys()}
     * @param preferredStores - see
     *            {@link org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()}
     * @param context - any context is acceptable
     * @deprecated Use
     *             {@link org.onepf.oms.OpenIabHelper#OpenIabHelper(android.content.Context, org.onepf.oms.OpenIabHelper.Options)}
     *             Will be removed in 1.0 release.
     *             <p/>
     *             <p/>
     *             Simple constructor for OpenIabHelper.
     *             <p>
     *             See {@link OpenIabHelper#OpenIabHelper(Context, Options)} for
     *             details
     */
    public OpenIabHelper(Context context, Map<String, String> storeKeys, String[] preferredStores) {
        this(context,
                new Options.Builder()
                        .addStoreKeys(storeKeys)
                        .addPreferredStoreName(preferredStores)
                        .build()
        );
    }

    /**
     * @param storeKeys - see
     *            {@link org.onepf.oms.OpenIabHelper.Options#getStoreKeys()}
     * @param preferredStores - see
     *            {@link org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()}
     * @param availableStores - see
     *            {@link org.onepf.oms.OpenIabHelper.Options#getAvailableStores()}
     * @param context - any context is acceptable
     * @deprecated Use
     *             {@link org.onepf.oms.OpenIabHelper#OpenIabHelper(android.content.Context, org.onepf.oms.OpenIabHelper.Options)}
     *             Will be removed in 1.0 release.
     *             <p/>
     *             Simple constructor for OpenIabHelper.
     *             <p>
     *             See {@link OpenIabHelper#OpenIabHelper(Context, Options)} for
     *             details
     */
    public OpenIabHelper(Context context, Map<String, String> storeKeys, String[] preferredStores, Appstore[] availableStores) {
        this(context,
                new Options.Builder()
                        .addStoreKeys(storeKeys)
                        .addPreferredStoreName(preferredStores)
                        .addAvailableStores(availableStores)
                        .build()
        );
    }

    /**
     * Before start ensure you already have <li>- permission
     * <code>org.onepf.openiab.permission.BILLING</code> in your
     * AndroidManifest.xml<li>- publicKey for store you decided to work with
     * (you can find it in Developer Console of your store)<li>- map SKUs for
     * your store if they differs using {@link #mapSku(String, String, String)}</li>
     * <p/>
     * <p/>
     * You can specify publicKeys for stores (excluding Amazon those don't use
     * verification based on RSA keys). See {@link Options#storeKeys} for
     * details
     * <p/>
     * By default verification will be performed for receipt from every store.
     * To aviod verification exception OpenIAB doesn't connect to store that key
     * is not specified for
     * <p/>
     * If you don't want to put publicKey in code and verify receipt remotely,
     * you need to set {@link Options#verifyMode} to {@link Options#VERIFY_SKIP}
     * . To make OpenIAB connect even to stores key is not specified for, use
     * {@link Options#VERIFY_ONLY_KNOWN}
     * <p/>
     * {@link org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()} is
     * useful option when you test your app on device with multiple stores
     * installed. Specify store name you want to work with here and it would be
     * selected if you install application using adb.
     * 
     * @param options - specify all necessary options
     * @param context - any context is acceptable
     */
    public OpenIabHelper(Context context, Options options) {
        this.context = context.getApplicationContext();
        this.options = options;

        checkSettings(options, context);
        Logger.init();
    }

    private void startAppstoreBillingServiceSetupAsync(
            final IabHelper.OnIabSetupFinishedListener listener) {
        if (mAppstoreBillingService == null) {
            return;
        }
        new Thread() {
            public void run() {
                mAppstoreBillingService.startSetup(new OnIabSetupFinishedListener() {
                    public void onIabSetupFinished(IabResult result) {
                        fireSetupFinished(listener, result);
                    }
                });
            }
        }.start();
    }

    private static boolean useNew = true;

    /**
     * Discover all available stores and select the best billing service. If the
     * flag {@link Options#checkInventory} is set to true, stores with existing
     * inventory are checked first.
     * <p/>
     * Should be called from UI thread.
     * @param listener - called when setup is completed
     */
    public void startSetup(final IabHelper.OnIabSetupFinishedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Setup listener must be not null!");
        }

        if (setupState != SETUP_RESULT_NOT_STARTED && setupState != SETUP_RESULT_FAILED) {
            // Try to setup if it hasn't been set up already or the last setup
            // failed.
            throw new IllegalStateException("Couldn't be set up. Current state: "
                    + setupStateToString(setupState));
        }
        Logger.init();
        setupState = SETUP_IN_PROGRESS;

        if (useNew) {
            String installerName = context.getPackageManager().getInstallerPackageName(
                    context.getPackageName());
            if (installerName != null) {
                if (installerName.equals("com.android.vending")) {
                    final String publicKey = options.verifyMode == Options.VERIFY_SKIP ? null
                            : options.storeKeys.get(OpenIabHelper.NAME_GOOGLE);
                    mAppstore = new GooglePlay(context, publicKey);
                    mAppstoreBillingService = mAppstore.getInAppBillingService();
                    startAppstoreBillingServiceSetupAsync(listener);
                    return;
                } else if (installerName.equals("com.amazon.venezia")) {
                    mAppstore = new AmazonAppstore(context);
                    mAppstoreBillingService = mAppstore.getInAppBillingService();
                    startAppstoreBillingServiceSetupAsync(listener);
                    return;
                } else {
                    // Store isn't supported in this part of the code. Maybe old
                    // version will pick up something. startSetup would have to
                    // be called again.
                    useNew = false;
                    throw new UnsupportedOperationException("installer '" + installerName
                            + "' is not checked in setup");
                }
            }
        }

        new Thread(new Runnable() {
            public void run() {
                List<Appstore> stores2check = new ArrayList<Appstore>();
                if (options.availableStores != null) {
                    stores2check.addAll(options.availableStores);
                } else { // if appstores are not specified by user - lookup for all available stores
                    final List<Appstore> openStores = discoverOpenStores(context, null, options);
                    Logger.dWithTimeFromUp("startSetup() discovered openstores: ", openStores.toString());
                    stores2check.addAll(openStores);
                    if (options.getVerifyMode() == Options.VERIFY_EVERYTHING && !options.hasStoreKey(NAME_GOOGLE)) {
                        // don't work with GooglePlay if verifyMode is strict and no publicKey provided 
                    } else {
                        final String publicKey = options.verifyMode == Options.VERIFY_SKIP ? null
                                : options.storeKeys.get(OpenIabHelper.NAME_GOOGLE);
                        stores2check.add(new GooglePlay(context, publicKey));
                    }

                    // try AmazonApps if in-app-purchasing.jar with Amazon SDK is compiled with app 
                    try {
                        OpenIabHelper.class.getClassLoader().loadClass("com.amazon.inapp.purchasing.PurchasingManager");
                        stores2check.add(new AmazonAppstore(context));
                    } catch (ClassNotFoundException e) {
                    }

                    //Nokia TODO change logic
                    stores2check.add(new NokiaStore(context));
                    if (!hasRequestedPermission(context, "com.nokia.payment.BILLING")) {
                        Logger.w("Required permission \"com.nokia.payment.BILLING\" NOT REQUESTED");
                    }
                }

                IabResult result = new IabResult(BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE, "Billing isn't supported");

                if (options.checkInventory) {

                    final List<Appstore> equippedStores = checkInventory(stores2check);

                    if (!equippedStores.isEmpty()) {
                        mAppstore = selectBillingService(equippedStores);
                    }
                    Logger.dWithTimeFromUp("select equipped");
                    if (mAppstore != null) {
                        final String message = "Successfully initialized with existing inventory: " + mAppstore.getAppstoreName();
                        result = new IabResult(BILLING_RESPONSE_RESULT_OK, message);
                        Logger.d(message);
                    } else {
                        // found no equipped stores. Select store based on store parameters
                        mAppstore = selectBillingService(stores2check);
                        Logger.dWithTimeFromUp("select non-equipped");
                        if (mAppstore != null) {
                            final String message = "Successfully initialized with non-equipped store: " + mAppstore.getAppstoreName();
                            result = new IabResult(BILLING_RESPONSE_RESULT_OK, message);
                            Logger.d(message);
                        }
                    }
                    if (mAppstore != null) {
                        mAppstoreBillingService = mAppstore.getInAppBillingService();
                    }
                    fireSetupFinished(listener, result);
                } else {   // no inventory check. Select store based on store parameters
                    mAppstore = selectBillingService(stores2check);
                    if (mAppstore != null) {
                        mAppstoreBillingService = mAppstore.getInAppBillingService();
                        mAppstoreBillingService.startSetup(new OnIabSetupFinishedListener() {
                            public void onIabSetupFinished(IabResult result) {
                                fireSetupFinished(listener, result);
                            }
                        });
                    } else {
                        fireSetupFinished(listener, result);
                    }
                }
                for (Appstore store : stores2check) {
                    if (store != mAppstore && store.getInAppBillingService() != null) {
                        store.getInAppBillingService().dispose();
                        Logger.dWithTimeFromUp("startSetup() disposing ", store.getAppstoreName());
                    }
                }
            }
        }, "openiab-setup").start();
    }


    @MagicConstant(intValues = {SETUP_DISPOSED, SETUP_IN_PROGRESS,
            SETUP_RESULT_FAILED, SETUP_RESULT_NOT_STARTED, SETUP_RESULT_SUCCESSFUL})
    public int getSetupState() {
        return setupState;
    }

    /**
     * Must be called after setup is finished. See {@link #startSetup(OnIabSetupFinishedListener)}
     *
     * @return <code>null</code> if no appstore connected, otherwise name of Appstore OpenIAB has connected to.
     */
    public synchronized String getConnectedAppstoreName() {
        if (mAppstore == null) return null;
        return mAppstore.getAppstoreName();
    }

    /**
     * Check options are valid
     */
    public static void checkOptions(Options options) {
        if (options.verifyMode != Options.VERIFY_SKIP && options.storeKeys != null) { // check publicKeys. Must be not null and valid
            for (Entry<String, String> entry : options.storeKeys.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("Null publicKey for store: " + entry.getKey() + ", key: " + entry.getValue());
                }
                try {
                    Security.generatePublicKey(entry.getValue());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid publicKey for store: " + entry.getKey() + ", key: " + entry.getValue(), e);
                }
            }
        }
    }

    private static void checkSettings(Options options, Context context) {
        checkOptions(options);
        checkNokia(options, context);
    }

    private static void checkNokia(Options options, Context context) {
        if (options.hasAvailableStoreWithName(NAME_NOKIA)
                && !hasRequestedPermission(context, "com.nokia.payment.BILLING")) {
            throw new IllegalStateException("Nokia permission \"com.nokia.payment.BILLING\" NOT REQUESTED");
        }
    }

    //todo move to Utils
    private static boolean hasRequestedPermission(Context context, String permission) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                for (String p : info.requestedPermissions) {
                    if (p.equals(permission)) {
                        return true;
                    }
                }
            }
        } catch (NameNotFoundException e) {
            Logger.e(e, "Error during checking permissions.");
        }
        return false;
    }

    //todo move to Utils
    private static void checkPermission(Context context, String paramString, StringBuilder builder) {
        if (context.checkCallingOrSelfPermission(paramString) != PackageManager.PERMISSION_GRANTED) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(String.format(" - Required permission \"%s\" is NOT granted.", paramString));
        }
    }

    //todo move to Utils
    private static void formatComponentStatus(String message, StringBuilder messageBuilder) {
        if (messageBuilder.length() > 0) {
            messageBuilder.append('\n');
        }
        messageBuilder.append(message);
    }




    protected void fireSetupFinished(final IabHelper.OnIabSetupFinishedListener listener, final IabResult result) {
        if (setupState == SETUP_DISPOSED) {
            return;
        }

        if (mAppstore != null) {
            Logger.dWithTimeFromUp("fireSetupFinished() === SETUP DONE === result: ", result, ", appstore: ",
                    mAppstore.getAppstoreName());
        } else {
            Logger.dWithTimeFromUp("fireSetupFinished() === SETUP DONE === result: ", result);
        }

        setupState = result.isSuccess() ? SETUP_RESULT_SUCCESSFUL : SETUP_RESULT_FAILED;
        notifyHandler.post(new Runnable() {
            public void run() {
                listener.onIabSetupFinished(result);
            }
        });
    }

    /**
     * Discover all OpenStore services, checks them and build {@link org.onepf.oms.OpenIabHelper.Options#getAvailableStores()} list<br>
     * <p/>
     * Lock current thread for {@link org.onepf.oms.OpenIabHelper.Options#getDiscoveryTimeout()} <br>
     * Must not be called from <code>main</code> thread to avoid service connection blocking
     *
     * @param dest    - discovered OpenStores will be added here. If <b>null</b> new List() will be created
     * @param options - settings for Appstore discovery like verifyMode and timeouts
     * @return dest or new List with discovered Appstores
     */
    public static List<Appstore> discoverOpenStores(final Context context, final List<Appstore> dest, final Options options) {
        if (Thread.currentThread().equals(Looper.getMainLooper().getThread())) {
            throw new IllegalStateException("Must not be called from main thread. "
                    + "Service interaction will be blocked");
        }
        PackageManager packageManager = context.getPackageManager();
        final Intent intentAppstoreServices = new Intent(BIND_INTENT);
        List<ResolveInfo> infoList = packageManager.queryIntentServices(intentAppstoreServices, 0);
        final List<Appstore> result = dest != null ? dest : new ArrayList<Appstore>(infoList != null ? infoList.size() : 0);
        if (infoList == null || infoList.isEmpty()) {
            return result;
        }
        final CountDownLatch storesToCheck = new CountDownLatch(infoList.size());
        for (ResolveInfo info : infoList) {
            String packageName = info.serviceInfo.packageName;
            String name = info.serviceInfo.name;
            Intent intentAppstore = new Intent(intentAppstoreServices);
            intentAppstore.setClassName(packageName, name);
            try {
                boolean isBound = context.bindService(intentAppstore, new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Logger.d("discoverOpenStores() appstoresService connected for component: ", name.flattenToShortString());
                        IOpenAppstore openAppstoreService = IOpenAppstore.Stub.asInterface(service);

                        try {
                            String appstoreName = openAppstoreService.getAppstoreName();
                            Intent billingIntent = openAppstoreService.getBillingServiceIntent();
                            if (appstoreName == null) { // no name - no service
                                Logger.e("discoverOpenStores() Appstore doesn't have name. Skipped. ComponentName: ", name);
                            } else if (billingIntent == null) { // don't handle stores without billing support
                                Logger.d("discoverOpenStores(): billing is not supported by store: ", name);
                            } else if ((options.verifyMode == Options.VERIFY_EVERYTHING) && !options.hasStoreKey(appstoreName)) {
                                // don't connect to OpenStore if no key provided and verification is strict
                                Logger.e("discoverOpenStores() verification is required but publicKey is not provided: ", name);
                            } else {
                                String publicKey = options.getStoreKey(appstoreName);
                                if (options.verifyMode == Options.VERIFY_SKIP) publicKey = null;
                                final OpenAppstore openAppstore = new OpenAppstore(context, appstoreName, openAppstoreService, billingIntent, publicKey, this);
                                openAppstore.componentName = name;
                                Logger.e("discoverOpenStores() add new OpenStore: ", openAppstore);
                                synchronized (result) {
                                    if (!result.contains(openAppstore)) {
                                        result.add(openAppstore);
                                    }
                                }
                            }
                        } catch (RemoteException e) {
                            Logger.e(e, "discoverOpenStores() ComponentName: ", name);
                        }
                        storesToCheck.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Logger.d("onServiceDisconnected() appstoresService disconnected for component: ", name.flattenToShortString());
                        //Nothing to do here
                    }
                }, Context.BIND_AUTO_CREATE);
                if (!isBound) {
                    storesToCheck.countDown();
                }
            } catch (SecurityException e) {
                Logger.e(e, "bindService() failed for ", packageName);
                storesToCheck.countDown();
            }
        }
        try {
            storesToCheck.await(options.discoveryTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Logger.e(e, "Interrupted: discovering OpenStores.");
        }
        return result;
    }

    /**
     * Connects to Billing Service of each store. Request list of user purchases (inventory)
     *
     * @param availableStores - list of stores to check
     * @return list of stores with non-empty inventory
     * @see {@link org.onepf.oms.OpenIabHelper#CHECK_INVENTORY_TIMEOUT} to set timout value
     */
    protected List<Appstore> checkInventory(final List<Appstore> availableStores) {
        String packageName = context.getPackageName();
        // candidates:
        Map<String, Appstore> candidates = new HashMap<String, Appstore>();
        for (Appstore appstore : availableStores) {
            if (appstore.isBillingAvailable(packageName)) {
                candidates.put(appstore.getAppstoreName(), appstore);
            }
        }
        Logger.dWithTimeFromUp(candidates.size(), " inventory candidates");
        final List<Appstore> equippedStores = Collections.synchronizedList(new ArrayList<Appstore>());
        final CountDownLatch storeRemains = new CountDownLatch(candidates.size());
        // for every appstore: connect to billing service and check inventory 
        for (Map.Entry<String, Appstore> entry : candidates.entrySet()) {
            final Appstore appstore = entry.getValue();
            final AppstoreInAppBillingService billingService = entry.getValue().getInAppBillingService();
            billingService.startSetup(new OnIabSetupFinishedListener() {
                public void onIabSetupFinished(IabResult result) {
                    Logger.dWithTimeFromUp("billing set ", appstore.getAppstoreName());
                    if (result.isFailure()) {
                        storeRemains.countDown();
                        return;
                    }
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                Inventory inventory = billingService.queryInventory(false, null, null);
                                if (inventory.getAllPurchases().size() > 0) {
                                    equippedStores.add(appstore);
                                }
                                Logger.dWithTimeFromUp("inventoryCheck() in ", appstore.getAppstoreName(), " found: ", inventory.getAllPurchases().size(), " purchases");
                            } catch (IabException e) {
                                Logger.e("inventoryCheck() failed for ", appstore.getAppstoreName());
                            }
                            storeRemains.countDown();
                        }
                    }, "inv-check[" + appstore.getAppstoreName() + "]").start();
                }
            });
        }
        try {
            storeRemains.await(options.checkInventoryTimeoutMs, TimeUnit.MILLISECONDS);
            Logger.dWithTimeFromUp("inventory check done");
        } catch (InterruptedException e) {
            Logger.e(e, "selectBillingService()  inventory check is failed. candidates: ", candidates.size()
                    , ", inventory remains: ", storeRemains.getCount());
        }
        return equippedStores;
    }

    /**
     * Lookup for requested service in store based on isPackageInstaller() & isBillingAvailable()
     * <p/>
     * Scenario:
     * <li>
     * - look for installer: if exists and supports billing service - we done <li>
     * - rest of stores who support billing considered as candidates<p><li>
     * <p/>
     * - find candidate according to [prefferedStoreNames]. if found - we done<p><li>
     * <p/>
     * - select candidate randomly from 3 groups based on published package version<li>
     * - published version == app.versionCode<li>
     * - published version  > app.versionCode<li>
     * - published version < app.versionCode
     */
    protected Appstore selectBillingService(final List<Appstore> availableStores) {
        String packageName = context.getPackageName();
        // candidates:
        Map<String, Appstore> candidates = new HashMap<String, Appstore>();
        //
        for (Appstore appstore : availableStores) {
            if (appstore.isBillingAvailable(packageName)) {
                candidates.put(appstore.getAppstoreName(), appstore);
            } else {
                continue; // for billing we cannot select store without billing
            }
            if (appstore.isPackageInstaller(packageName)) {
                return appstore;
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // lookup for developer preffered stores
        if (options.prefferedStoreNames != null) {
            for (int i = 0; i < options.prefferedStoreNames.length; i++) {
                Appstore candidate = candidates.get(options.prefferedStoreNames[i]);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        // nothing found. select something that matches package version
        int versionCode = Appstore.PACKAGE_VERSION_UNDEFINED;
        try {
            versionCode = context.getPackageManager().getPackageInfo(packageName, 0).versionCode;
        } catch (NameNotFoundException e) {
            Logger.e(e, "Are we installed?");
        }
        List<Appstore> sameVersion = new ArrayList<Appstore>();
        List<Appstore> higherVersion = new ArrayList<Appstore>();
        for (Appstore candidate : candidates.values()) {
            final int storeVersion = candidate.getPackageVersion(packageName);
            if (storeVersion == versionCode) {
                sameVersion.add(candidate);
            } else if (storeVersion > versionCode) {
                higherVersion.add(candidate);
            }
        }
        // use random if found stores with same version of package  
        if (sameVersion.size() > 0) {
            return sameVersion.get(new Random().nextInt(sameVersion.size()));
        } else if (higherVersion.size() > 0) {  // or one of higher version
            return higherVersion.get(new Random().nextInt(higherVersion.size()));
        } else {                                // ok, return no matter what
            return new ArrayList<Appstore>(candidates.values()).get(new Random().nextInt(candidates.size()));
        }
    }

    public void dispose() {
        Logger.d("Disposing.");
        if (mAppstoreBillingService != null) {
            mAppstoreBillingService.dispose();
        }
        setupState = SETUP_DISPOSED;
    }

    public boolean subscriptionsSupported() {
        checkSetupDone("subscriptionsSupported");
        return mAppstoreBillingService.subscriptionsSupported();
    }

    public void launchPurchaseFlow(Activity act, String sku, int requestCode, IabHelper.OnIabPurchaseFinishedListener listener) {
        launchPurchaseFlow(act, sku, requestCode, listener, "");
    }

    public void launchPurchaseFlow(Activity act, String sku, int requestCode,
                                   IabHelper.OnIabPurchaseFinishedListener listener, String extraData) {
        launchPurchaseFlow(act, sku, ITEM_TYPE_INAPP, requestCode, listener, extraData);
    }

    public void launchSubscriptionPurchaseFlow(Activity act, String sku, int requestCode,
                                               IabHelper.OnIabPurchaseFinishedListener listener) {
        launchSubscriptionPurchaseFlow(act, sku, requestCode, listener, "");
    }

    public void launchSubscriptionPurchaseFlow(Activity act, String sku, int requestCode,
                                               IabHelper.OnIabPurchaseFinishedListener listener, String extraData) {
        launchPurchaseFlow(act, sku, ITEM_TYPE_SUBS, requestCode, listener, extraData);
    }

    public void launchPurchaseFlow(Activity act, String sku, String itemType, int requestCode,
                                   IabHelper.OnIabPurchaseFinishedListener listener, String extraData) {
        checkSetupDone("launchPurchaseFlow");
        mAppstoreBillingService.launchPurchaseFlow(act,
                SkuManager.getInstance().getStoreSku(mAppstore.getAppstoreName(), sku),
                itemType,
                requestCode,
                listener,
                extraData);
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        Logger.dWithTimeFromUp("handleActivityResult() requestCode: ", requestCode, " resultCode: ", resultCode, " data: ", data);

        if (setupState != SETUP_RESULT_SUCCESSFUL) {
            Logger.d("handleActivityResult() setup is not done. requestCode: ", requestCode, " resultCode: ", resultCode, " data: ", data);
            return false;
        }
        return mAppstoreBillingService.handleActivityResult(requestCode, resultCode, data);
    }

    /**
     * See {@link #queryInventory(boolean, List, List)} for details
     */
    public Inventory queryInventory(boolean querySkuDetails, List<String> moreSkus) throws IabException {
        return queryInventory(querySkuDetails, moreSkus, null);
    }

    /**
     * Queries the inventory. This will query all owned items from the server, as well as
     * information on additional skus, if specified. This method may block or take long to execute.
     * Do not call from a UI thread. For that, use the non-blocking version {@link #queryInventoryAsync(boolean, java.util.List, java.util.List, org.onepf.oms.appstore.googleUtils.IabHelper.QueryInventoryFinishedListener)}.
     *
     * @param querySkuDetails if true, SKU details (price, description, etc) will be queried as well
     *                        as purchase information.
     * @param moreItemSkus    additional PRODUCT skus to query information on, regardless of ownership.
     *                        Ignored if null or if querySkuDetails is false.
     * @param moreSubsSkus    additional SUBSCRIPTIONS skus to query information on, regardless of ownership.
     *                        Ignored if null or if querySkuDetails is false.
     * @throws IabException if a problem occurs while refreshing the inventory.
     */
    public Inventory queryInventory(boolean querySkuDetails, List<String> moreItemSkus, List<String> moreSubsSkus) throws IabException {
        checkSetupDone("queryInventory");

        List<String> moreItemStoreSkus;
        final SkuManager skuManager = SkuManager.getInstance();
        if (moreItemSkus != null) {
            moreItemStoreSkus = new ArrayList<String>(moreItemSkus.size());
            for (String sku : moreItemSkus) {
                moreItemStoreSkus.add(skuManager.getStoreSku(mAppstore.getAppstoreName(), sku));
            }
        } else {
            moreItemStoreSkus = null;
        }

        List<String> moreSubsStoreSkus;
        if (moreSubsSkus != null) {
            moreSubsStoreSkus = new ArrayList<String>(moreSubsSkus.size());
            for (String sku : moreSubsSkus) {
                moreSubsStoreSkus.add(skuManager.getStoreSku(mAppstore.getAppstoreName(), sku));
            }
        } else {
            moreSubsStoreSkus = null;
        }
        return mAppstoreBillingService.queryInventory(querySkuDetails, moreItemStoreSkus, moreSubsStoreSkus);
    }

    /**
     * Queries the inventory. This will query all owned items from the server, as well as
     * information on additional skus, if specified. This method may block or take long to execute.
     *
     * @param querySkuDetails if true, SKU details (price, description, etc) will be queried as well
     *                        as purchase information.
     * @param moreItemSkus    additional PRODUCT skus to query information on, regardless of ownership.
     *                        Ignored if null or if querySkuDetails is false.
     * @param moreSubsSkus    additional SUBSCRIPTIONS skus to query information on, regardless of ownership.
     *                        Ignored if null or if querySkuDetails is false.
     * @throws IabException if a problem occurs while refreshing the inventory.
     */
    public void queryInventoryAsync(final boolean querySkuDetails, final List<String> moreItemSkus, final List<String> moreSubsSkus, final IabHelper.QueryInventoryFinishedListener listener) {
        checkSetupDone("queryInventory");
        if (listener == null) {
            throw new IllegalArgumentException("Inventory listener must be not null");
        }
        flagStartAsync("refresh inventory");
        (new Thread(new Runnable() {
            public void run() {
                IabResult result = new IabResult(BILLING_RESPONSE_RESULT_OK, "Inventory refresh successful.");
                Inventory inv = null;
                try {
                    inv = queryInventory(querySkuDetails, moreItemSkus, moreSubsSkus);
                } catch (IabException ex) {
                    result = ex.getResult();
                }

                flagEndAsync();

                final IabResult result_f = result;
                final Inventory inv_f = inv;
                if (setupState != SETUP_DISPOSED) {
                    notifyHandler.post(new Runnable() {
                        public void run() {
                            listener.onQueryInventoryFinished(result_f, inv_f);
                        }
                    });
                }
            }
        })).start();
    }

    /**
     * For details see {@link #queryInventoryAsync(boolean, List, List, QueryInventoryFinishedListener)}
     */
    public void queryInventoryAsync(final boolean querySkuDetails, final List<String> moreSkus, final IabHelper.QueryInventoryFinishedListener listener) {
        checkSetupDone("queryInventoryAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Inventory listener must be not null!");
        }
        queryInventoryAsync(querySkuDetails, moreSkus, null, listener);
    }

    /**
     * For details see {@link #queryInventoryAsync(boolean, List, List, QueryInventoryFinishedListener)}
     */
    public void queryInventoryAsync(IabHelper.QueryInventoryFinishedListener listener) {
        checkSetupDone("queryInventoryAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Inventory listener must be not null!");
        }
        queryInventoryAsync(true, null, listener);
    }

    /**
     * For details see {@link #queryInventoryAsync(boolean, List, List, QueryInventoryFinishedListener)}
     */
    public void queryInventoryAsync(boolean querySkuDetails, IabHelper.QueryInventoryFinishedListener listener) {
        checkSetupDone("queryInventoryAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Inventory listener must be not null!");
        }
        queryInventoryAsync(querySkuDetails, null, listener);
    }

    public void consume(Purchase itemInfo) throws IabException {
        checkSetupDone("consume");
        Purchase purchaseStoreSku = (Purchase) itemInfo.clone(); // TODO: use Purchase.getStoreSku()
        purchaseStoreSku.setSku(SkuManager.getInstance().getStoreSku(mAppstore.getAppstoreName(), itemInfo.getSku()));
        mAppstoreBillingService.consume(purchaseStoreSku);
    }

    public void consumeAsync(Purchase purchase, IabHelper.OnConsumeFinishedListener listener) {
        checkSetupDone("consumeAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Consume listener must be not null!");
        }
        List<Purchase> purchases = new ArrayList<Purchase>();
        purchases.add(purchase);
        consumeAsyncInternal(purchases, listener, null);
    }

    public void consumeAsync(List<Purchase> purchases, IabHelper.OnConsumeMultiFinishedListener listener) {
        checkSetupDone("consumeAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Consume listener must be not null!");
        }
        consumeAsyncInternal(purchases, null, listener);
    }

    void consumeAsyncInternal(final List<Purchase> purchases,
                              final IabHelper.OnConsumeFinishedListener singleListener,
                              final IabHelper.OnConsumeMultiFinishedListener multiListener) {
        checkSetupDone("consume");
        flagStartAsync("consume");
        (new Thread(new Runnable() {
            public void run() {
                final List<IabResult> results = new ArrayList<IabResult>();
                for (Purchase purchase : purchases) {
                    try {
                        consume(purchase);
                        results.add(new IabResult(BILLING_RESPONSE_RESULT_OK, "Successful consume of sku " + purchase.getSku()));
                    } catch (IabException ex) {
                        results.add(ex.getResult());
                    }
                }

                flagEndAsync();
                if (setupState != SETUP_DISPOSED && singleListener != null) {
                    notifyHandler.post(new Runnable() {
                        public void run() {
                            singleListener.onConsumeFinished(purchases.get(0), results.get(0));
                        }
                    });
                }
                if (setupState != SETUP_DISPOSED && multiListener != null) {
                    notifyHandler.post(new Runnable() {
                        public void run() {
                            multiListener.onConsumeMultiFinished(purchases, results);
                        }
                    });
                }
            }
        })).start();
    }

    /**
     * @return True if this OpenIabHelper instance is set up successfully.
     */
    public boolean setupSuccessful() {
        return setupState == SETUP_RESULT_SUCCESSFUL;
    }

    // Checks that setup was done; if not, throws an exception.
    void checkSetupDone(String operation) {
        String stateToString = setupStateToString(setupState);
        if (setupState != SETUP_RESULT_SUCCESSFUL) {
            Logger.e("Illegal state for operation (", operation, "): ", stateToString);
            throw new IllegalStateException(stateToString + " Can't perform operation: " + operation);
        }
    }

    void flagStartAsync(String operation) {
        // TODO: why can't be called consume and queryInventory at the same time?
//        if (mAsyncInProgress) {
//            throw new IllegalStateException("Can't start async operation (" +
//                    operation + ") because another async operation(" + mAsyncOperation + ") is in progress.");
//        }
        mAsyncOperation = operation;
        Logger.d("Starting async operation: ", operation);
    }

    void flagEndAsync() {
        Logger.d("Ending async operation: ", mAsyncOperation);
        mAsyncOperation = "";
    }

    private static String setupStateToString(int setupState) {
        String state;
        if (setupState == SETUP_RESULT_NOT_STARTED) {
            state = " IAB helper is not set up.";
        } else if (setupState == SETUP_DISPOSED) {
            state = "IAB helper was disposed of.";
        } else if (setupState == SETUP_RESULT_SUCCESSFUL) {
            state = "IAB helper is set up.";
        } else if (setupState == SETUP_RESULT_FAILED) {
            state = "IAB helper setup failed.";
        } else {
            throw new IllegalStateException("Wrong setup state: " + setupState);
        }
        return state;
    }

    /**
     * @deprecated Use {@link org.onepf.oms.util.Logger#isLoggable()}
     * <p/>
     * Will be removed in version 1.0.
     */
    public static boolean isDebugLog() {
        return Logger.isLoggable();
    }

    /**
     * @deprecated Use {@link org.onepf.oms.util.Logger#setLoggable(boolean)}
     * <p/>
     * Will be removed in version 1.0.
     */
    public static void enableDebugLogging(boolean enabled) {
        Logger.setLoggable(enabled);
    }

    /**
     * @deprecated Use {@link org.onepf.oms.util.Logger#setLoggable(boolean)}. Param 'tag' no effect.
     * <p/>
     * Will be removed in version 1.0.
     */
    public static void enableDebuglLogging(boolean enabled, String tag) {
        Logger.setLoggable(enabled);
    }

    public static boolean isPackageInstaller(Context appContext, String installer) {
        String installerPackageName = appContext.getPackageManager().getInstallerPackageName(appContext.getPackageName());
        return installerPackageName != null && installerPackageName.equals(installer);
    }

    public interface OnInitListener {
        void onInitFinished();
    }

    public interface OnOpenIabHelperInitFinished {
        void onOpenIabHelperInitFinished();
    }

    /**
     * All options of OpenIAB can be found here.
     * Create instance of this class via {@link org.onepf.oms.OpenIabHelper.Options.Builder}.
     * <p/>
     * TODO: consider to use cloned instance of Options in OpenIABHelper
     */
    public static class Options {

        /**
         * Verify signatures in any store.
         * <p/>
         * By default in Google's IabHelper. Throws exception if key is not available or invalid.
         * To prevent crashes OpenIAB wouldn't connect to OpenStore if no publicKey provided
         */
        public static final int VERIFY_EVERYTHING = 0;

        /**
         * Don't verify signatures. To perform verification on server-side
         */
        public static final int VERIFY_SKIP = 1;

        /**
         * Verify signatures only if publicKey is available. Otherwise skip verification.
         * <p/>
         * Developer is responsible for verify
         */
        public static final int VERIFY_ONLY_KNOWN = 2;

        /**
         * @deprecated Use
         *             {@link org.onepf.oms.OpenIabHelper.Options#getAvailableStores()}
         *             Will be private since 1.0.
         *             <p/>
         *             List of stores to be used for store elections. By default
         *             GooglePlay, Amazon and all installed OpenStores are used.
         *             <p/>
         *             To specify your own list, you need to instantiate
         *             Appstore object manually. GooglePlay and Amazon could be
         *             instantiated directly. OpenStore can be discovered using
         *             {@link OpenIabHelper#discoverOpenStores(Context, List, Options)}
         *             <p/>
         *             If you put only your instance of Appstore in this list
         *             OpenIAB will use it
         *             <p/>
         *             TODO: consider to use AppstoreFactory.get(storeName) ->
         *             Appstore instance
         */
        public List<Appstore> availableStores;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getDiscoveryTimeout()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * Wait specified amount of ms to find all OpenStores on device
         */
        public int discoveryTimeoutMs = DEFAULT_DISCOVER_TIMEOUT;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#isCheckInventory()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * Check user inventory in every store to select proper store
         * <p/>
         * Will try to connect to each billingService and extract user's purchases.
         * If purchases have been found in the only store that store will be used for further purchases.
         * If purchases have been found in multiple stores only such stores will be used for further elections
         */
        public boolean checkInventory = true;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getCheckInventoryTimeout()}
         * Will be private since 1.0.
         * <p/>
         * Wait specified amount of ms to check inventory in all stores
         */
        public int checkInventoryTimeoutMs = CHECK_INVENTORY_TIMEOUT;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getVerifyMode()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * OpenIAB could skip receipt verification by publicKey for GooglePlay and OpenStores
         * <p/>
         * Receipt could be verified in {@link org.onepf.oms.appstore.googleUtils.IabHelper.OnIabPurchaseFinishedListener#onIabPurchaseFinished(org.onepf.oms.appstore.googleUtils.IabResult, org.onepf.oms.appstore.googleUtils.Purchase)}
         * using {@link Purchase#getOriginalJson()} and {@link Purchase#getSignature()}
         */
        @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP})
        public int verifyMode = VERIFY_EVERYTHING;

        /**
         * @deprecated Use
         *             {@link org.onepf.oms.OpenIabHelper.Options#getStoreKeys()}
         *             Will be private since 1.0.
         *             <p/>
         *             <p/>
         *             storeKeys is map of [ appstore name -> publicKeyBase64 ]
         *             Put keys for all stores you support in this Map and pass
         *             it to instantiate {@link OpenIabHelper}
         *             <p/>
         *             <b>publicKey</b> key is used to verify receipt is created
         *             by genuine Appstore using provided signature. It can be
         *             found in Developer Console of particular store
         *             <p/>
         *             <b>name</b> of particular store can be provided by
         *             local_store tool if you run it on device. For Google Play
         *             OpenIAB uses {@link OpenIabHelper#NAME_GOOGLE}.
         *             <p/>
         *             <p>
         *             Note: AmazonApps doesn't use RSA keys for receipt
         *             verification, so you don't need to specify it
         */
        public Map<String, String> storeKeys = new HashMap<String, String>();

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * Used as priority list if store that installed app is not found and there are
         * multiple stores installed on device that supports billing.
         */
        public String[] prefferedStoreNames = new String[]{};


        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options.Builder} instead.
         */
        public Options() {
        }


        private Options(List<Appstore> availableStores,
                        Map<String, String> storeKeys,
                        boolean checkInventory,
                        int checkInventoryTimeout,
                        int discoveryTimeout,
                        @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP}) int verifyMode,
 String[] preferredStoreNames) {
            this.checkInventory = checkInventory;
            this.checkInventoryTimeoutMs = checkInventoryTimeout;
            this.availableStores = availableStores;
            this.discoveryTimeoutMs = discoveryTimeout;
            this.storeKeys = storeKeys;
            this.prefferedStoreNames = preferredStoreNames;
            this.verifyMode = verifyMode;
        }

        /**
         * Used as priority list if store that installed app is not found and there are
         * multiple stores installed on device that supports billing.
         */
        @Nullable
        public String[] getPreferredStoreNames() {
            return prefferedStoreNames;
        }

        /**
         * OpenIAB could skip receipt verification by publicKey for GooglePlay and OpenStores
         * <p/>
         * Receipt could be verified in {@link org.onepf.oms.appstore.googleUtils.IabHelper.OnIabPurchaseFinishedListener#onIabPurchaseFinished(org.onepf.oms.appstore.googleUtils.IabResult, org.onepf.oms.appstore.googleUtils.Purchase)}
         * using {@link org.onepf.oms.appstore.googleUtils.Purchase#getOriginalJson()} and {@link org.onepf.oms.appstore.googleUtils.Purchase#getSignature()}
         */
        @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP})
        public int getVerifyMode() {
            return verifyMode;
        }

        /**
         * Check user inventory in every store to select proper store
         * <p/>
         * Will try to connect to each billingService and extract user's purchases.
         * If purchases have been found in the only store that store will be used for further purchases.
         * If purchases have been found in multiple stores only such stores will be used for further elections
         */
        public boolean isCheckInventory() {
            return checkInventory;
        }

        /**
         * Wait specified amount of ms to check inventory in all stores
         */
        public long getCheckInventoryTimeout() {
            return checkInventoryTimeoutMs;
        }

        /**
         * Wait specified amount of ms to find all OpenStores on device
         */
        public long getDiscoveryTimeout() {
            return discoveryTimeoutMs;
        }

        /**
         * List of stores to be used for store elections. By default GooglePlay,
         * Amazon and all installed OpenStores are used.
         * <p/>
         * To specify your own list, you need to instantiate Appstore object
         * manually. GooglePlay and Amazon could be instantiated directly.
         * OpenStore can be discovered using
         * {@link OpenIabHelper#discoverOpenStores(android.content.Context, java.util.List, org.onepf.oms.OpenIabHelper.Options)}
         * <p/>
         * If you put only your instance of Appstore in this list OpenIAB will
         * use it
         * <p/>
         * TODO: consider to use AppstoreFactory.get(storeName) -> Appstore
         * instance
         */
        @Nullable
        public List<Appstore> getAvailableStores() {
            return availableStores;
        }

        /**
         * storeKeys is map of [ appstore name -> publicKeyBase64 ] Put keys for
         * all stores you support in this Map and pass it to instantiate
         * {@link OpenIabHelper}
         * <p/>
         * <b>publicKey</b> key is used to verify receipt is created by genuine
         * Appstore using provided signature. It can be found in Developer
         * Console of particular store
         * <p/>
         * <b>name</b> of particular store can be provided by local_store tool
         * if you run it on device. For Google Play OpenIAB uses
         * {@link OpenIabHelper#NAME_GOOGLE}.
         * <p/>
         * <p>
         * Note: AmazonApps doesn't use RSA keys for receipt verification, so
         * you don't need to specify it
         */
        @Nullable
        public Map<String, String> getStoreKeys() {
            return storeKeys;
        }

        public boolean hasAvailableStoreWithName(@NotNull String name) {
            if (availableStores != null) {
                for (Appstore s : availableStores) {
                    if (name.equals(s.getAppstoreName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        public boolean hasStoreKey(String storeName) {
            return storeKeys != null && storeKeys.containsKey(storeName);
        }

        public String getStoreKey(String storeName) {
            return storeKeys != null ? storeKeys.get(storeName) : null;
        }

        /**
         * Utility class for create instance of {@link org.onepf.oms.OpenIabHelper.Options}
         */
        public static final class Builder {

            private List<String> preferredStoreNames;
            private Map<String, String> storeKeys;
            private List<Appstore> availableStores;
            private int discoveryTimeout = DEFAULT_DISCOVER_TIMEOUT;
            private int checkInventoryTimeout = CHECK_INVENTORY_TIMEOUT;
            private boolean checkInventory = true;

            @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP})
            private int verifyMode = VERIFY_EVERYTHING;

            /**
             * Add available store to options.
             *
             * @param stores Stores to add.
             * @see org.onepf.oms.OpenIabHelper.Options#getAvailableStores()
             */
            public Builder addAvailableStores(Appstore... stores) {
                if (!CollectionUtils.isEmpty(stores)) {
                    if (this.availableStores == null) {
                        this.availableStores = new ArrayList<Appstore>(stores.length);
                    }
                    Collections.addAll(this.availableStores, stores);
                }
                return this;
            }

            /**
             * Add available store to options.
             *
             * @param stores Stores to add.
             * @see org.onepf.oms.OpenIabHelper.Options#getAvailableStores().
             */
            public Builder addAvailableStores(List<Appstore> stores) {
                if (!CollectionUtils.isEmpty(stores)) {
                    if (this.availableStores == null) {
                        this.availableStores = new ArrayList<Appstore>(stores.size());
                    }
                    this.availableStores.addAll(stores);
                }
                return this;
            }

            /**
             * Set check inventory. By default is true.
             *
             * @see org.onepf.oms.OpenIabHelper.Options#isCheckInventory()
             */
            public Builder setCheckInventory(boolean checkInventory) {
                this.checkInventory = checkInventory;
                return this;
            }

            /**
             * Set discovery timeout. By default 5 sec.
             *
             * @throws java.lang.IllegalArgumentException if timeout is negative value.
             * @see org.onepf.oms.OpenIabHelper.Options#getDiscoveryTimeout()
             */
            public Builder setDiscoveryTimeout(int discoveryTimeout) {
                if (discoveryTimeout < 0) {
                    throw new IllegalArgumentException("Discovery timeout can't be" +
                            " a negative value.");
                }
                this.discoveryTimeout = discoveryTimeout;
                return this;
            }

            /**
             * Set inventory check timeout. By default 10 sec.
             * This value has no effect if {@link org.onepf.oms.OpenIabHelper.Options.Builder#setCheckInventory(boolean)}
             * set to false.
             *
             * @throws java.lang.IllegalArgumentException if timeout is negative value.
             * @see org.onepf.oms.OpenIabHelper.Options#getCheckInventoryTimeout()
             * @see org.onepf.oms.OpenIabHelper.Options.Builder#setCheckInventory(boolean)
             */
            public Builder setCheckInventoryTimeout(int checkInventoryTimeout) {
                if (discoveryTimeout < 0) {
                    throw new IllegalArgumentException("Check inventory timeout can't be" +
                            " a negative value.");
                }
                this.checkInventoryTimeout = checkInventoryTimeout;
                return this;
            }

            /**
             * Get list of added available stores.
             *
             * @return List of available store of null if nothing was add.
             */
            @Nullable
            public List<Appstore> getAvailableStores() {
                return availableStores;
            }

            /**
             * Get map "store name -> public key" of added store keys.
             *
             * @return Map of added store keys or null if nothing was add.
             */
            @Nullable
            public Map<String, String> getStoreKeys() {
                return storeKeys;
            }

            /**
             * Add single store keys to options.
             *
             * @param storeName Name of store.
             * @param publicKey Key of store.
             * @throws java.lang.IllegalArgumentException if storeName is null.
             * @see org.onepf.oms.OpenIabHelper.Options#getStoreKeys()
             */
            public Builder addStoreKey(String storeName, String publicKey) {
                checkStoreKeyParam(storeName, publicKey);

                if (this.storeKeys == null) {
                    this.storeKeys = new HashMap<String, String>();
                }
                this.storeKeys.put(storeName, publicKey);
                return this;
            }

            private static void checkStoreKeyParam(String storeName, String publicKey) {
                if (storeName == null) {
                    throw new IllegalArgumentException("Store name can't be null value.");
                }
            }

            /**
             * Set verify mode for store. By default set to {@link org.onepf.oms.OpenIabHelper.Options#VERIFY_EVERYTHING}.
             *
             * @param verifyMode Verify doe for store. Must be on of {@link org.onepf.oms.OpenIabHelper.Options#VERIFY_EVERYTHING},
             *                   {@link org.onepf.oms.OpenIabHelper.Options#VERIFY_SKIP},
             *                   {@link org.onepf.oms.OpenIabHelper.Options#VERIFY_ONLY_KNOWN}.
             * @see org.onepf.oms.OpenIabHelper.Options#getVerifyMode()
             */
            public Builder setVerifyMode(
                    @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP}) int verifyMode) {
                this.verifyMode = verifyMode;
                return this;
            }

            /**
             * Add store keys to options.
             *
             * @param storeKeys Map storeName - store public key.
             * @throws java.lang.IllegalArgumentException if one of store names is null.
             * @see org.onepf.oms.OpenIabHelper.Options#getStoreKeys()
             */
            public Builder addStoreKeys(Map<String, String> storeKeys) {
                if (!CollectionUtils.isEmpty(storeKeys)) {
                    for (Entry<String, String> entry : storeKeys.entrySet()) {
                        checkStoreKeyParam(entry.getKey(), entry.getValue());
                    }

                    if (this.storeKeys == null) {
                        this.storeKeys = new HashMap<String, String>();
                    }

                    this.storeKeys.putAll(storeKeys);
                }
                return this;
            }

            /**
             * Add preferred stores to options. Priority of selection is order in what stores add.
             *
             * @see org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()
             */
            public Builder addPreferredStoreName(String... storeNames) {
                if (!CollectionUtils.isEmpty(storeNames)) {
                    if (this.preferredStoreNames == null) {
                        this.preferredStoreNames = new ArrayList<String>(storeNames.length);
                    }
                    Collections.addAll(this.preferredStoreNames, storeNames);
                }
                return this;
            }

            /**
             * Add preferred stores to options. Priority of selection is order in what stores add.
             *
             * @see org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()
             */
            public Builder addPreferredStoreName(List<String> storeNames) {
                if (!CollectionUtils.isEmpty(storeNames)) {
                    if (this.preferredStoreNames == null) {
                        this.preferredStoreNames = new ArrayList<String>(storeNames.size());
                    }
                    this.preferredStoreNames.addAll(storeNames);
                }
                return this;
            }



            /**
             * @return Create new instance of
             *         {@link org.onepf.oms.OpenIabHelper.Options}.
             */
            public Options build() {
                List<Appstore> availableStores = CollectionUtils.isEmpty(this.availableStores) ? null
                        : Collections.unmodifiableList(this.availableStores);
                Map<String, String> storeKeys = CollectionUtils.isEmpty(this.storeKeys) ? null
                        : Collections.unmodifiableMap(this.storeKeys);
                String[] preferredStoreNames = CollectionUtils.isEmpty(this.preferredStoreNames) ? null
                        : this.preferredStoreNames.toArray(new String[this.preferredStoreNames
                                .size()]);

                return new Options(availableStores, storeKeys, checkInventory,
                        checkInventoryTimeout, discoveryTimeout, verifyMode, preferredStoreNames);
            }
        }
    }
}