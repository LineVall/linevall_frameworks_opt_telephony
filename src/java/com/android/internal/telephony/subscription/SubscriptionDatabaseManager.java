/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.subscription;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.provider.Telephony.SimInfo;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.DeviceToDeviceStatusSharingPreference;
import android.telephony.SubscriptionManager.ProfileClass;
import android.telephony.SubscriptionManager.SimDisplayNameSource;
import android.telephony.SubscriptionManager.SubscriptionType;
import android.telephony.SubscriptionManager.UsageSetting;
import android.telephony.TelephonyManager;
import android.telephony.UiccAccessRule;
import android.telephony.ims.ImsMmTelManager;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.telephony.uicc.UiccController;
import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The subscription database manager is the wrapper of {@link SimInfo}
 * table. It's a full memory cache of the entire subscription database, and it's intended to run
 * on a separate thread to perform asynchronous database update. The database's cache allows
 * multi threads to read simultaneously, if no write is ongoing.
 *
 * Note that from Android 14, directly writing into the subscription database through content
 * resolver with {@link SimInfo#CONTENT_URI} will cause cache/db out of sync. All the read/write
 * to the database should go through {@link SubscriptionManagerService}.
 */
public class SubscriptionDatabaseManager extends Handler {
    private static final String LOG_TAG = "SDM";

    /** Whether enabling verbose debugging message or not. */
    private static final boolean VDBG = false;

    /** Invalid database row index. */
    private static final int INVALID_ROW_INDEX = -1;

    /** The context */
    @NonNull
    private final Context mContext;

    /** Telephony manager */
    private final TelephonyManager mTelephonyManager;

    /** UICC controller */
    private final UiccController mUiccController;

    /**
     * The read/write lock to protect the entire database access. Using a Re-entrant read lock as
     * much more read requests are expected than the write requests. All the access to
     * {@link #mAllSubscriptionInfoInternalCache} needs to be protected by this lock.
     */
    @NonNull
    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();

    /** Local log for most important debug messages. */
    @NonNull
    private final LocalLog mLocalLog = new LocalLog(128);

    /**
     * The entire subscription database, including subscriptions from inserted, previously inserted
     * SIMs. This is the full memory cache of the subscription database. The key is the subscription
     * id. Note all the access to this map needs to be protected by the re-entrant lock
     * {@link #mReadWriteLock}.
     *
     * @see SimInfo
     */
    @GuardedBy("mReadWriteLock")
    @NonNull
    private final Map<Integer, SubscriptionInfoInternal> mAllSubscriptionInfoInternalCache =
            new HashMap<>(16);

    /** The mapping from {@link SimInfo} table to {@link SubscriptionInfoInternal} get methods. */
    private static final Map<String, Function<SubscriptionInfoInternal, ?>>
            SUBSCRIPTION_GET_METHOD_MAP = Map.ofEntries(
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID,
                            SubscriptionInfoInternal::getSubscriptionId),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_ICC_ID,
                            SubscriptionInfoInternal::getIccId),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_SIM_SLOT_INDEX,
                            SubscriptionInfoInternal::getSimSlotIndex),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_DISPLAY_NAME,
                            SubscriptionInfoInternal::getDisplayName),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_CARRIER_NAME,
                            SubscriptionInfoInternal::getCarrierName),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_NAME_SOURCE,
                            SubscriptionInfoInternal::getDisplayNameSource),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_COLOR,
                            SubscriptionInfoInternal::getIconTint),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_NUMBER,
                            SubscriptionInfoInternal::getNumber),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_DATA_ROAMING,
                            SubscriptionInfoInternal::getDataRoaming),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_MCC_STRING,
                            SubscriptionInfoInternal::getMccString),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_MNC_STRING,
                            SubscriptionInfoInternal::getMncString),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_EHPLMNS,
                            SubscriptionInfoInternal::getEhplmns),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_HPLMNS,
                            SubscriptionInfoInternal::getHplmnsRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_IS_EMBEDDED,
                            SubscriptionInfoInternal::isEmbeddedRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_CARD_ID,
                            SubscriptionInfoInternal::getCardString),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_ACCESS_RULES,
                            SubscriptionInfoInternal::getNativeAccessRulesRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_ACCESS_RULES_FROM_CARRIER_CONFIGS,
                            SubscriptionInfoInternal::getCarrierConfigAccessRulesRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_IS_REMOVABLE,
                            SubscriptionInfoInternal::isRemovableEmbeddedRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED,
                            SubscriptionInfoInternal::isEnhanced4GModeEnabledRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_VT_IMS_ENABLED,
                            SubscriptionInfoInternal::isVideoTelephonyEnabledRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_WFC_IMS_ENABLED,
                            SubscriptionInfoInternal::isWifiCallingEnabledRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_WFC_IMS_MODE,
                            SubscriptionInfoInternal::getWifiCallingMode),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_WFC_IMS_ROAMING_MODE,
                            SubscriptionInfoInternal::getWifiCallingModeForRoaming),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED,
                            SubscriptionInfoInternal::isWifiCallingEnabledForRoamingRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_IS_OPPORTUNISTIC,
                            SubscriptionInfoInternal::isOpportunisticRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_GROUP_UUID,
                            SubscriptionInfoInternal::getGroupUuidRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_ISO_COUNTRY_CODE,
                            SubscriptionInfoInternal::getCountryIso),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_CARRIER_ID,
                            SubscriptionInfoInternal::getCarrierId),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_PROFILE_CLASS,
                            SubscriptionInfoInternal::getProfileClass),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_SUBSCRIPTION_TYPE,
                            SubscriptionInfoInternal::getSubscriptionType),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_GROUP_OWNER,
                            SubscriptionInfoInternal::getGroupOwner),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_ENABLED_MOBILE_DATA_POLICIES,
                            SubscriptionInfoInternal::getEnabledMobileDataPoliciesRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_IMSI,
                            SubscriptionInfoInternal::getImsi),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_UICC_APPLICATIONS_ENABLED,
                            SubscriptionInfoInternal::areUiccApplicationsEnabledRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_IMS_RCS_UCE_ENABLED,
                            SubscriptionInfoInternal::isRcsUceEnabledRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_CROSS_SIM_CALLING_ENABLED,
                            SubscriptionInfoInternal::isCrossSimCallingEnabledRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_RCS_CONFIG,
                            SubscriptionInfoInternal::getRcsConfig),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_ALLOWED_NETWORK_TYPES_FOR_REASONS,
                            SubscriptionInfoInternal::getAllowedNetworkTypesForReasons),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_D2D_STATUS_SHARING,
                            SubscriptionInfoInternal::getDeviceToDeviceStatusSharingPreference),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_VOIMS_OPT_IN_STATUS,
                            SubscriptionInfoInternal::isVoImsOptInEnabledRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_D2D_STATUS_SHARING_SELECTED_CONTACTS,
                            SubscriptionInfoInternal::getDeviceToDeviceStatusSharingContacts),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_NR_ADVANCED_CALLING_ENABLED,
                            SubscriptionInfoInternal::isNrAdvancedCallingEnabledRaw),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_PHONE_NUMBER_SOURCE_CARRIER,
                            SubscriptionInfoInternal::getNumberFromCarrier),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_PHONE_NUMBER_SOURCE_IMS,
                            SubscriptionInfoInternal::getNumberFromIms),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_PORT_INDEX,
                            SubscriptionInfoInternal::getPortIndex),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_USAGE_SETTING,
                            SubscriptionInfoInternal::getUsageSetting),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_TP_MESSAGE_REF,
                            SubscriptionInfoInternal::getLastUsedTPMessageReference),
                    new AbstractMap.SimpleImmutableEntry<>(
                            SimInfo.COLUMN_USER_HANDLE,
                            SubscriptionInfoInternal::getUserId)
                    );

    /**
     * The constructor.
     *
     * @param context The context.
     * @param looper Looper for the handler.
     */
    public SubscriptionDatabaseManager(@NonNull Context context, @NonNull Looper looper) {
        super(looper);
        mContext = context;
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mUiccController = UiccController.getInstance();
        loadFromDatabase();
    }

    /**
     * Helper method to get specific field from {@link SubscriptionInfoInternal} by the database
     * column name. {@link SubscriptionInfoInternal} represent one single record in the
     * {@link SimInfo} table. So every column has a corresponding get method in
     * {@link SubscriptionInfoInternal} (except for unused or deprecated columns).
     *
     * @param subInfo The subscription info.
     * @param columnName The corresponding database column name.
     *
     * @return The corresponding value from {@link SubscriptionInfoInternal}.
     */
    private Object getSubscriptionInfoFieldByColumnName(@NonNull SubscriptionInfoInternal subInfo,
            @NonNull String columnName) {
        if (SUBSCRIPTION_GET_METHOD_MAP.containsKey(columnName)) {
            return SUBSCRIPTION_GET_METHOD_MAP.get(columnName).apply(subInfo);
        }
        return null;
    }

    /**
     * Comparing the old/new {@link SubscriptionInfoInternal} and create content values for database
     * update. If any field in the new subscription info is different from the old one, then each
     * delta will be added into the {@link ContentValues}.
     *
     * @param oldSubInfo The old {@link SubscriptionInfoInternal}.
     * @param newSubInfo The new {@link SubscriptionInfoInternal}.
     *
     * @return The delta content values for database update.
     */
    @NonNull
    private ContentValues createDeltaContentValues(@Nullable SubscriptionInfoInternal oldSubInfo,
            @NonNull SubscriptionInfoInternal newSubInfo) {
        ContentValues deltaContentValues = new ContentValues();

        for (String columnName : Telephony.SimInfo.getAllColumns()) {
            Object newValue = getSubscriptionInfoFieldByColumnName(newSubInfo, columnName);
            if (newValue != null) {
                Object oldValue = null;
                if (oldSubInfo != null) {
                    oldValue = getSubscriptionInfoFieldByColumnName(oldSubInfo, columnName);
                }
                // Some columns need special handling. We need to convert them into a format that
                // is accepted by the database.
                if (!Objects.equals(oldValue, newValue)) {
                    deltaContentValues.putObject(columnName, newValue);
                }
            }
        }
        return deltaContentValues;
    }

    /**
     * Synchronously insert a new record into the database. This operation is synchronous because
     * we need to convert the inserted row index into the subscription id.
     *
     * @param contentValues The fields of the subscription to be inserted into the database.
     *
     * @return The row index of the new record. {@link #INVALID_ROW_INDEX} if insertion failed.
     */
    private int insertNewRecordIntoDatabaseSync(@NonNull ContentValues contentValues) {
        Objects.requireNonNull(contentValues);
        Uri uri = mContext.getContentResolver().insert(SimInfo.CONTENT_URI, contentValues);
        if (uri != null && uri.getLastPathSegment() != null) {
            logl("insertNewRecordIntoDatabaseSync: Successfully added subscription. subId="
                    + uri.getLastPathSegment());
            return Integer.parseInt(uri.getLastPathSegment());
        } else {
            loge("insertNewRecordIntoDatabaseSync: Failed to insert subscription into database. "
                    + "contentValues=" + contentValues);
        }
        return INVALID_ROW_INDEX;
    }

    /**
     * Insert a new subscription info. The subscription info must have subscription id
     * {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}. Note this is a slow method, so be
     * cautious to call this method.
     *
     * @param subInfo The subscription info to update.
     *
     * @return The new subscription id. {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} (-1) if
     * insertion fails.
     */
    public int insertSubscriptionInfo(@NonNull SubscriptionInfoInternal subInfo) {
        Objects.requireNonNull(subInfo);
        // A new subscription to be inserted must have invalid subscription id.
        if (SubscriptionManager.isValidSubscriptionId(subInfo.getSubscriptionId())) {
            throw new RuntimeException("insertSubscriptionInfo: Not a new subscription to "
                    + "insert. subInfo=" + subInfo);
        }

        int subId;
        // Grab the write lock so no other threads can read or write the cache.
        mReadWriteLock.writeLock().lock();
        try {
            // Synchronously insert into the database. Note this should be the only synchronous
            // write operation performed by the subscription database manager. The reason is that
            // we need to get the sub id for cache update.
            subId = insertNewRecordIntoDatabaseSync(createDeltaContentValues(null, subInfo));
            if (subId > 0) {
                mAllSubscriptionInfoInternalCache.put(subId, new SubscriptionInfoInternal
                        .Builder(subInfo)
                        .setId(subId).build());
            } else {
                loge("insertSubscriptionInfo: Failed to insert a new subscription. subInfo="
                        + subInfo);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        return subId;
    }

    /**
     * Update a subscription in database asynchronously.
     *
     * @param subId The subscription id of the subscription to be updated.
     * @param contentValues The fields to be update.
     */
    private void updateDatabaseAsync(int subId, @NonNull ContentValues contentValues) {
        logv("updateDatabaseAsync: prepare to update sub " + subId);
        // Perform the update in the handler thread asynchronously.
        post(() -> {
            int rowsUpdated = mContext.getContentResolver().update(Uri.withAppendedPath(
                    SimInfo.CONTENT_URI, String.valueOf(subId)), contentValues, null, null);
            if (rowsUpdated == 1) {
                logv("updateDatabaseAsync: Successfully updated subscription in the database. "
                        + "subId=" + subId + ", contentValues= " + contentValues.getValues());
            } else {
                loge("updateDatabaseAsync: Unexpected update result. rowsUpdated=" + rowsUpdated
                        + ", contentValues=" + contentValues);
            }
        });
    }

    /**
     * Update a certain field of subscription in the database. Also update the subscription cache
     * {@link #mAllSubscriptionInfoInternalCache}.
     *
     * @param subId The subscription id.
     * @param columnName The database column name from the database table {@link SimInfo}.
     * @param newValue The new value to update the subscription info cache
     * {@link #mAllSubscriptionInfoInternalCache}.
     * @param builderSetMethod The {@link SubscriptionInfo.Builder} method to set a specific field
     * when constructing the new {@link SubscriptionInfo}. This should be one of the
     * SubscriptionInfoInternal.Builder.setXxxx method.
     * @param <T> The type of newValue for subscription cache update.
     */
    private <T> void writeDatabaseAndCacheHelper(int subId, @NonNull String columnName,
            @Nullable T newValue,
            BiFunction<SubscriptionInfoInternal.Builder, T, SubscriptionInfoInternal.Builder>
                    builderSetMethod) {
        ContentValues contentValues = new ContentValues();
        SubscriptionInfoInternal subInfoCache;

        // Grab the write lock so no other threads can read or write the cache.
        mReadWriteLock.writeLock().lock();
        try {
            subInfoCache = mAllSubscriptionInfoInternalCache.get(subId);
            if (subInfoCache != null) {
                // Check if the new value is different from the old value in the cache.
                if (!Objects.equals(getSubscriptionInfoFieldByColumnName(subInfoCache, columnName),
                        newValue)) {
                    // If the value is different, then we need to update the cache. Since all fields
                    // in SubscriptionInfo is final, so we need to create a new SubscriptionInfo.
                    SubscriptionInfoInternal.Builder builder = new SubscriptionInfoInternal
                            .Builder(subInfoCache);

                    // Apply the new value to the builder. This line is equivalent to
                    // builder.setXxxxxx(newValue);
                    builder = builderSetMethod.apply(builder, newValue);

                    // Update the subscription database cache.
                    mAllSubscriptionInfoInternalCache.put(subId, builder.build());

                    // Prepare the content value for update.
                    contentValues.putObject(columnName, newValue);
                    // Writing into the database is slow. So do this asynchronously.
                    updateDatabaseAsync(subId, contentValues);
                }
            } else {
                loge("Subscription doesn't exist. subId=" + subId);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Update the database with the {@link SubscriptionInfo}, and also update the cache.
     *
     * @param subInfo The new {@link SubscriptionInfo}.
     */
    public void updateSubscription(@NonNull SubscriptionInfoInternal subInfo) {
        Objects.requireNonNull(subInfo);

        // Grab the write lock so no other threads can read or write the cache.
        mReadWriteLock.writeLock().lock();
        try {
            int subId = subInfo.getSubscriptionId();
            SubscriptionInfoInternal subInfoCache = mAllSubscriptionInfoInternalCache.get(
                    subInfo.getSubscriptionId());
            if (subInfoCache == null) {
                throw new RuntimeException("updateSubscription: subscription does not exist. subId="
                        + subId);
            }
            mAllSubscriptionInfoInternalCache.put(subId, subInfo);
            // Writing into the database is slow. So do this asynchronously.
            updateDatabaseAsync(subId, createDeltaContentValues(subInfoCache, subInfo));
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Set the ICCID of the SIM that is associated with the subscription.
     *
     * @param subId Subscription id.
     * @param iccId The ICCID of the SIM that is associated with this subscription.
     */
    public void setIccId(int subId, @NonNull String iccId) {
        Objects.requireNonNull(iccId);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_ICC_ID, iccId,
                SubscriptionInfoInternal.Builder::setIccId);
    }

    /**
     * Set the SIM index of the slot that currently contains the subscription. Set to
     * {@link SubscriptionManager#INVALID_SIM_SLOT_INDEX} if the subscription is inactive.
     *
     * @param subId Subscription id.
     * @param simSlotIndex The SIM slot index.
     */
    public void setSimSlotIndex(int subId, int simSlotIndex) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_SIM_SLOT_INDEX, simSlotIndex,
                SubscriptionInfoInternal.Builder::setSimSlotIndex);
    }

    /**
     * Set the name displayed to the user that identifies this subscription. This name is used
     * in Settings page and can be renamed by the user.
     *
     * @param subId Subscription id.
     * @param displayName The display name.
     */
    public void setDisplayName(int subId, @NonNull String displayName) {
        Objects.requireNonNull(displayName);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_DISPLAY_NAME, displayName,
                SubscriptionInfoInternal.Builder::setDisplayName);
    }

    /**
     * Set the name displayed to the user that identifies subscription provider name. This name
     * is the SPN displayed in status bar and many other places. Can't be renamed by the user.
     *
     * @param subId Subscription id.
     * @param carrierName The carrier name.
     */
    public void setCarrierName(int subId, @NonNull String carrierName) {
        Objects.requireNonNull(carrierName);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_CARRIER_NAME, carrierName,
                SubscriptionInfoInternal.Builder::setCarrierName);
    }

    /**
     * Set the source of the display name.
     *
     * @param subId Subscription id.
     * @param displayNameSource The source of the display name.
     *
     * @see SubscriptionInfo#getDisplayName()
     */
    public void setDisplayNameSource(int subId, @SimDisplayNameSource int displayNameSource) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_NAME_SOURCE, displayNameSource,
                SubscriptionInfoInternal.Builder::setDisplayNameSource);
    }

    /**
     * Set the color to be used for tinting the icon when displaying to the user.
     *
     * @param subId Subscription id.
     * @param iconTint The color to be used for tinting the icon when displaying to the user.
     */
    public void setIconTint(int subId, int iconTint) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_COLOR, iconTint,
                SubscriptionInfoInternal.Builder::setIconTint);
    }

    /**
     * Set the number presented to the user identify this subscription.
     *
     * @param subId Subscription id.
     * @param number the number presented to the user identify this subscription.
     */
    public void setNumber(int subId, @NonNull String number) {
        Objects.requireNonNull(number);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_NUMBER, number,
                SubscriptionInfoInternal.Builder::setNumber);
    }

    /**
     * Set whether user enables data roaming for this subscription or not.
     *
     * @param subId Subscription id.
     * @param dataRoaming Data roaming mode. Either
     * {@link SubscriptionManager#DATA_ROAMING_ENABLE} or
     * {@link SubscriptionManager#DATA_ROAMING_DISABLE}
     */
    public void setDataRoaming(int subId, int dataRoaming) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_DATA_ROAMING, dataRoaming,
                SubscriptionInfoInternal.Builder::setDataRoaming);
    }

    /**
     * Set the mobile country code.
     *
     * @param subId Subscription id.
     * @param mcc The mobile country code.
     */
    public void setMcc(int subId, @NonNull String mcc) {
        Objects.requireNonNull(mcc);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_MCC_STRING, mcc,
                SubscriptionInfoInternal.Builder::setMcc);
    }

    /**
     * Set the mobile network code.
     *
     * @param subId Subscription id.
     * @param mnc Mobile network code.
     */
    public void setMnc(int subId, @NonNull String mnc) {
        Objects.requireNonNull(mnc);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_MNC_STRING, mnc,
                SubscriptionInfoInternal.Builder::setMnc);
    }

    /**
     * Set EHPLMNs associated with the subscription.
     *
     * @param subId Subscription id.
     * @param ehplmns EHPLMNs associated with the subscription.
     */
    public void setEhplmns(int subId, @NonNull String[] ehplmns) {
        Objects.requireNonNull(ehplmns);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_EHPLMNS, TextUtils.join(",", ehplmns),
                SubscriptionInfoInternal.Builder::setEhplmns);
    }

    /**
     * Set HPLMNs associated with the subscription.
     *
     * @param subId Subscription id.
     * @param hplmns HPLMNs associated with the subscription.
     */
    public void setHplmns(int subId, @NonNull String[] hplmns) {
        Objects.requireNonNull(hplmns);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_HPLMNS, TextUtils.join(",", hplmns),
                SubscriptionInfoInternal.Builder::setHplmns);
    }

    /**
     * Set whether the subscription is from eSIM or not.
     *
     * @param subId Subscription id.
     * @param isEmbedded {@code true} if the subscription is from eSIM.
     */
    public void setEmbedded(int subId, boolean isEmbedded) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_IS_EMBEDDED, isEmbedded ? 1 : 0,
                SubscriptionInfoInternal.Builder::setEmbedded);
    }

    /**
     * Set the card string of the SIM card. This is usually the ICCID or EID.
     *
     * @param subId Subscription id.
     * @param cardString The card string of the SIM card.
     *
     * @see SubscriptionInfo#getCardString()
     */
    public void setCardString(int subId, @NonNull String cardString) {
        Objects.requireNonNull(cardString);
        // Also update the public card id.
        setCardId(subId, mUiccController.convertToPublicCardId(cardString));
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_CARD_ID, cardString,
                SubscriptionInfoInternal.Builder::setCardString);
    }

    /**
     * Set the card id. This is the non-PII card id converted from
     * {@link SubscriptionInfoInternal#getCardString()}. This field only exists in
     * {@link SubscriptionInfo}, but not the database.
     *
     * @param subId Subscription id.
     * @param cardId The card id.
     */
    public void setCardId(int subId, int cardId) {
        // card id does not have a corresponding SimInfo column. So we only update the cache.

        // Grab the write lock so no other threads can read or write the cache.
        mReadWriteLock.writeLock().lock();
        try {
            SubscriptionInfoInternal subInfoCache = mAllSubscriptionInfoInternalCache.get(subId);
            mAllSubscriptionInfoInternalCache.put(subId,
                    new SubscriptionInfoInternal.Builder(subInfoCache)
                            .setCardId(cardId).build());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Set the native access rules for this subscription, if it is embedded and defines any.
     * This does not include access rules for non-embedded subscriptions.
     *
     * @param subId Subscription id.
     * @param nativeAccessRules The native access rules for this subscription.
     */
    public void setNativeAccessRules(int subId, @NonNull UiccAccessRule[] nativeAccessRules) {
        Objects.requireNonNull(nativeAccessRules);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_ACCESS_RULES,
                UiccAccessRule.encodeRules(nativeAccessRules),
                SubscriptionInfoInternal.Builder::setNativeAccessRules);
    }

    /**
     * Set the carrier certificates for this subscription that are saved in carrier configs.
     * This does not include access rules from the Uicc, whether embedded or non-embedded.
     *
     * @param subId Subscription id.
     * @param carrierConfigAccessRules The carrier certificates for this subscription.
     */
    public void setCarrierConfigAccessRules(int subId,
            @NonNull UiccAccessRule[] carrierConfigAccessRules) {
        Objects.requireNonNull(carrierConfigAccessRules);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_ACCESS_RULES_FROM_CARRIER_CONFIGS,
                UiccAccessRule.encodeRules(carrierConfigAccessRules),
                SubscriptionInfoInternal.Builder::setCarrierConfigAccessRules);
    }

    /**
     * Set whether an embedded subscription is on a removable card. Such subscriptions are
     * marked inaccessible as soon as the current card is removed. Otherwise, they will remain
     * accessible unless explicitly deleted. Only meaningful when for embedded subscription.
     *
     * @param subId Subscription id.
     * @param isRemovableEmbedded {@code true} if the subscription is from the removable
     * embedded SIM.
     */
    public void setRemovableEmbedded(int subId, boolean isRemovableEmbedded) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_IS_REMOVABLE, isRemovableEmbedded ? 1 : 0,
                SubscriptionInfoInternal.Builder::setRemovableEmbedded);
    }

    /**
     * Set whether enhanced 4G mode is enabled by the user or not.
     *
     * @param subId Subscription id.
     * @param isEnhanced4GModeEnabled whether enhanced 4G mode is enabled by the user or not.
     */
    public void setEnhanced4GModeEnabled(int subId, boolean isEnhanced4GModeEnabled) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED,
                isEnhanced4GModeEnabled ? 1 : 0,
                SubscriptionInfoInternal.Builder::setEnhanced4GModeEnabled);
    }

    /**
     * Set whether video telephony is enabled by the user or not.
     *
     * @param subId Subscription id.
     * @param isVideoTelephonyEnabled whether video telephony is enabled by the user or not.
     */
    public void setVideoTelephonyEnabled(int subId, boolean isVideoTelephonyEnabled) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_VT_IMS_ENABLED,
                isVideoTelephonyEnabled ? 1 : 0,
                SubscriptionInfoInternal.Builder::setVideoTelephonyEnabled);
    }

    /**
     * Set whether Wi-Fi calling is enabled by the user or not when the device is not roaming.
     *
     * @param subId Subscription id.
     * @param isWifiCallingEnabled whether Wi-Fi calling is enabled by the user or not when the
     * device is not roaming.
     */
    public void setWifiCallingEnabled(int subId, boolean isWifiCallingEnabled) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_WFC_IMS_ENABLED,
                isWifiCallingEnabled ? 1 : 0,
                SubscriptionInfoInternal.Builder::setWifiCallingEnabled);
    }

    /**
     * Set Wi-Fi calling mode when the device is not roaming.
     *
     * @param subId Subscription id.
     * @param wifiCallingMode Wi-Fi calling mode when the device is not roaming.
     */
    public void setWifiCallingMode(int subId,
            @ImsMmTelManager.WiFiCallingMode int wifiCallingMode) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_WFC_IMS_MODE, wifiCallingMode,
                SubscriptionInfoInternal.Builder::setWifiCallingMode);
    }

    /**
     * Set Wi-Fi calling mode when the device is roaming.
     *
     * @param subId Subscription id.
     * @param wifiCallingModeForRoaming Wi-Fi calling mode when the device is roaming.
     */
    public void setWifiCallingModeForRoaming(int subId,
            @ImsMmTelManager.WiFiCallingMode int wifiCallingModeForRoaming) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_WFC_IMS_ROAMING_MODE,
                wifiCallingModeForRoaming,
                SubscriptionInfoInternal.Builder::setWifiCallingModeForRoaming);
    }

    /**
     * Set whether Wi-Fi calling is enabled by the user or not when the device is roaming.
     *
     * @param subId Subscription id.
     * @param isWifiCallingEnabledForRoaming whether Wi-Fi calling is enabled by the user or not
     * when the device is roaming.
     */
    public void setWifiCallingEnabledForRoaming(int subId, boolean isWifiCallingEnabledForRoaming) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED,
                isWifiCallingEnabledForRoaming ? 1 : 0,
                SubscriptionInfoInternal.Builder::setWifiCallingEnabledForRoaming);
    }

    /**
     * Set whether the subscription is opportunistic or not.
     *
     * @param subId Subscription id.
     * @param isOpportunistic {@code true} if the subscription is opportunistic.
     */
    public void setOpportunistic(int subId, boolean isOpportunistic) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_IS_OPPORTUNISTIC, isOpportunistic ? 1 : 0,
                SubscriptionInfoInternal.Builder::setOpportunistic);
    }

    /**
     * Set the group UUID of the subscription group.
     *
     * @param subId Subscription id.
     * @param groupUuid The group UUID.
     *
     * @see SubscriptionInfo#getGroupUuid()
     */
    public void setGroupUuid(int subId, @NonNull String groupUuid) {
        Objects.requireNonNull(groupUuid);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_GROUP_UUID, groupUuid,
                SubscriptionInfoInternal.Builder::setGroupUuid);
    }

    /**
     * Set the ISO Country code for the subscription's provider.
     *
     * @param subId Subscription id.
     * @param countryIso The ISO country code for the subscription's provider.
     */
    public void setCountryIso(int subId, @NonNull String countryIso) {
        Objects.requireNonNull(countryIso);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_ISO_COUNTRY_CODE, countryIso,
                SubscriptionInfoInternal.Builder::setCountryIso);
    }

    /**
     * Set the subscription carrier id.
     *
     * @param subId Subscription id.
     * @param carrierId The carrier id.
     *
     * @see TelephonyManager#getSimCarrierId()
     */
    public void setCarrierId(int subId, int carrierId) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_CARRIER_ID, carrierId,
                SubscriptionInfoInternal.Builder::setCarrierId);
    }

    /**
     * Set the profile class populated from the profile metadata if present.
     *
     * @param subId Subscription id.
     * @param profileClass the profile class populated from the profile metadata if present.
     *
     * @see SubscriptionInfo#getProfileClass()
     */
    public void setProfileClass(int subId, @ProfileClass int profileClass) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_PROFILE_CLASS, profileClass,
                SubscriptionInfoInternal.Builder::setProfileClass);
    }

    /**
     * Set the subscription type.
     *
     * @param subId Subscription id.
     * @param type Subscription type.
     */
    public void setSubscriptionType(int subId, @SubscriptionType int type) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_SUBSCRIPTION_TYPE, type,
                SubscriptionInfoInternal.Builder::setType);
    }

    /**
     * Set the owner package of group the subscription belongs to.
     *
     * @param subId Subscription id.
     * @param groupOwner Owner package of group the subscription belongs to.
     */
    public void setGroupOwner(int subId, @NonNull String groupOwner) {
        Objects.requireNonNull(groupOwner);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_GROUP_OWNER, groupOwner,
                SubscriptionInfoInternal.Builder::setGroupOwner);
    }

    /**
     * Set the enabled mobile data policies.
     *
     * @param subId Subscription id.
     * @param enabledMobileDataPolicies The enabled mobile data policies.
     */
    public void setEnabledMobileDataPolicies(int subId, @NonNull String enabledMobileDataPolicies) {
        Objects.requireNonNull(enabledMobileDataPolicies);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_ENABLED_MOBILE_DATA_POLICIES,
                enabledMobileDataPolicies,
                SubscriptionInfoInternal.Builder::setEnabledMobileDataPolicies);
    }

    /**
     * Set the IMSI (International Mobile Subscriber Identity) of the subscription.
     *
     * @param subId Subscription id.
     * @param imsi The IMSI.
     */
    public void setImsi(int subId, @NonNull String imsi) {
        Objects.requireNonNull(imsi);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_IMSI, imsi,
                SubscriptionInfoInternal.Builder::setImsi);
    }

    /**
     * Set whether Uicc applications are configured to enable or not.
     *
     * @param subId Subscription id.
     * @param areUiccApplicationsEnabled {@code true} if Uicc applications are configured to
     * enable.
     */
    public void setUiccApplicationsEnabled(int subId, boolean areUiccApplicationsEnabled) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_UICC_APPLICATIONS_ENABLED,
                areUiccApplicationsEnabled ? 1 : 0,
                SubscriptionInfoInternal.Builder::setUiccApplicationsEnabled);
    }

    /**
     * Set whether the user has enabled IMS RCS User Capability Exchange (UCE) for this
     * subscription.
     *
     * @param subId Subscription id.
     * @param isRcsUceEnabled If the user enabled RCS UCE for this subscription.
     */
    public void setRcsUceEnabled(int subId, boolean isRcsUceEnabled) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_IMS_RCS_UCE_ENABLED,
                isRcsUceEnabled ? 1 : 0, SubscriptionInfoInternal.Builder::setRcsUceEnabled);
    }

    /**
     * Set whether the user has enabled cross SIM calling for this subscription.
     *
     * @param subId Subscription id.
     * @param isCrossSimCallingEnabled If the user enabled cross SIM calling for this
     * subscription.
     */
    public void setCrossSimCallingEnabled(int subId, boolean isCrossSimCallingEnabled) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_CROSS_SIM_CALLING_ENABLED,
                isCrossSimCallingEnabled ? 1 : 0,
                SubscriptionInfoInternal.Builder::setCrossSimCallingEnabled);
    }

    /**
     * Set the RCS config for this subscription.
     *
     * @param subId Subscription id.
     * @param rcsConfig The RCS config for this subscription.
     */
    public void setRcsConfig(int subId, @NonNull byte[] rcsConfig) {
        Objects.requireNonNull(rcsConfig);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_RCS_CONFIG, rcsConfig,
                SubscriptionInfoInternal.Builder::setRcsConfig);
    }

    /**
     * Set the allowed network types for reasons.
     *
     * @param subId Subscription id.
     * @param allowedNetworkTypesForReasons The allowed network types for reasons in string
     * format. The format is
     * "[reason]=[network types bitmask], [reason]=[network types bitmask], ..."
     *
     * For example, "user=1239287394, thermal=298791239, carrier=3456812312".
     *
     */
    public void setAllowedNetworkTypesForReasons(int subId,
            @NonNull String allowedNetworkTypesForReasons) {
        Objects.requireNonNull(allowedNetworkTypesForReasons);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_ALLOWED_NETWORK_TYPES_FOR_REASONS,
                allowedNetworkTypesForReasons,
                SubscriptionInfoInternal.Builder::setAllowedNetworkTypesForReasons);
    }

    /**
     * Set device to device sharing status.
     *
     * @param subId Subscription id.
     * @param deviceToDeviceStatusSharingPreference Device to device sharing status.
     */
    public void setDeviceToDeviceStatusSharingPreference(int subId,
            @DeviceToDeviceStatusSharingPreference int deviceToDeviceStatusSharingPreference) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_D2D_STATUS_SHARING,
                deviceToDeviceStatusSharingPreference,
                SubscriptionInfoInternal.Builder::setDeviceToDeviceStatusSharingPreference);
    }

    /**
     * Set whether the user has opted-in voice over IMS.
     *
     * @param subId Subscription id.
     * @param isVoImsOptInEnabled Whether the user has opted-in voice over IMS.
     */
    public void setVoImsOptInEnabled(int subId, boolean isVoImsOptInEnabled) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_VOIMS_OPT_IN_STATUS,
                isVoImsOptInEnabled ? 1 : 0,
                SubscriptionInfoInternal.Builder::setVoImsOptInEnabled);
    }

    /**
     * Set contacts information that allow device to device sharing.
     *
     * @param subId Subscription id.
     * @param deviceToDeviceStatusSharingContacts contacts information that allow device to
     * device sharing.
     */
    public void setDeviceToDeviceStatusSharingContacts(int subId,
            @NonNull String deviceToDeviceStatusSharingContacts) {
        Objects.requireNonNull(deviceToDeviceStatusSharingContacts);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_D2D_STATUS_SHARING_SELECTED_CONTACTS,
                deviceToDeviceStatusSharingContacts,
                SubscriptionInfoInternal.Builder::setDeviceToDeviceStatusSharingContacts);
    }

    /**
     * Set whether the user has enabled NR advanced calling.
     *
     * @param subId Subscription id.
     * @param isNrAdvancedCallingEnabled Whether the user has enabled NR advanced calling.
     */
    public void setNrAdvancedCallingEnabled(int subId, boolean isNrAdvancedCallingEnabled) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_NR_ADVANCED_CALLING_ENABLED,
                isNrAdvancedCallingEnabled ? 1 : 0,
                SubscriptionInfoInternal.Builder::setNrAdvancedCallingEnabled);
    }

    /**
     * Set the phone number retrieved from carrier.
     *
     * @param subId Subscription id.
     * @param numberFromCarrier The phone number retrieved from carrier.
     */
    public void setNumberFromCarrier(int subId, @NonNull String numberFromCarrier) {
        Objects.requireNonNull(numberFromCarrier);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_PHONE_NUMBER_SOURCE_CARRIER,
                numberFromCarrier, SubscriptionInfoInternal.Builder::setNumberFromCarrier);
    }

    /**
     * Set the phone number retrieved from IMS.
     *
     * @param subId Subscription id.
     * @param numberFromIms The phone number retrieved from IMS.
     */
    public void setNumberFromIms(int subId, @NonNull String numberFromIms) {
        Objects.requireNonNull(numberFromIms);
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_PHONE_NUMBER_SOURCE_IMS,
                numberFromIms, SubscriptionInfoInternal.Builder::setNumberFromIms);
    }

    /**
     * Set the port index of the Uicc card.
     *
     * @param subId Subscription id.
     * @param portIndex The port index of the Uicc card.
     */
    public void setPortIndex(int subId, int portIndex) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_PORT_INDEX, portIndex,
                SubscriptionInfoInternal.Builder::setPortIndex);
    }

    /**
     * Set subscription's preferred usage setting.
     *
     * @param subId Subscription id.
     * @param usageSetting Subscription's preferred usage setting.
     */
    public void setUsageSetting(int subId, @UsageSetting int usageSetting) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_USAGE_SETTING, usageSetting,
                SubscriptionInfoInternal.Builder::setUsageSetting);
    }

    /**
     * Set last used TP message reference.
     *
     * @param subId Subscription id.
     * @param lastUsedTPMessageReference Last used TP message reference.
     */
    public void setLastUsedTPMessageReference(int subId, int lastUsedTPMessageReference) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_TP_MESSAGE_REF,
                lastUsedTPMessageReference,
                SubscriptionInfoInternal.Builder::setLastUsedTPMessageReference);
    }

    /**
     * Set the user id associated with this subscription.
     *
     * @param subId Subscription id.
     * @param userId The user id associated with this subscription.
     */
    public void setUserId(int subId, int userId) {
        writeDatabaseAndCacheHelper(subId, SimInfo.COLUMN_USER_HANDLE, userId,
                SubscriptionInfoInternal.Builder::setUserId);
    }

    /**
     * Load the entire database into the cache.
     */
    private void loadFromDatabase() {
        // Perform the task in the handler thread.
        post(() -> {
            try (Cursor cursor = mContext.getContentResolver().query(
                    SimInfo.CONTENT_URI, null, null, null, null)) {
                mReadWriteLock.writeLock().lock();
                try {
                    mAllSubscriptionInfoInternalCache.clear();
                    while (cursor != null && cursor.moveToNext()) {
                        SubscriptionInfoInternal subInfo = createSubscriptionInfoFromCursor(cursor);
                        if (subInfo != null) {
                            mAllSubscriptionInfoInternalCache
                                    .put(subInfo.getSubscriptionId(), subInfo);
                        }
                    }
                    log("Loaded " + mAllSubscriptionInfoInternalCache.size()
                            + " records from the subscription database.");
                } finally {
                    mReadWriteLock.writeLock().unlock();
                }
            }
        });
    }

    /**
     * Build the {@link SubscriptionInfoInternal} from database.
     *
     * @param cursor  The cursor of the database.
     *
     * @return The subscription info from a single database record.
     */
    @Nullable
    private SubscriptionInfoInternal createSubscriptionInfoFromCursor(@NonNull Cursor cursor) {
        SubscriptionInfoInternal.Builder builder = new SubscriptionInfoInternal.Builder();
        int id = cursor.getInt(cursor.getColumnIndexOrThrow(
                SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID));
        builder.setId(id)
                .setIccId(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_ICC_ID))))
                .setSimSlotIndex(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_SIM_SLOT_INDEX)))
                .setDisplayName(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_DISPLAY_NAME))))
                .setCarrierName(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_CARRIER_NAME))))
                .setDisplayNameSource(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_NAME_SOURCE)))
                .setIconTint(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_COLOR)))
                .setNumber(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_NUMBER))))
                .setDataRoaming(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_DATA_ROAMING)))
                .setMcc(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_MCC_STRING))))
                .setMnc(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_MNC_STRING))))
                .setEhplmns(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_EHPLMNS))))
                .setHplmns(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_HPLMNS))))
                .setEmbedded(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_IS_EMBEDDED)));

        String cardString = TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                SimInfo.COLUMN_CARD_ID)));
        builder.setCardString(cardString);
        // publicCardId is the publicly exposed int card ID
        int publicCardId = mUiccController.convertToPublicCardId(cardString);

        builder.setCardId(publicCardId)
                .setNativeAccessRules(cursor.getBlob(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_ACCESS_RULES)))
                .setCarrierConfigAccessRules(cursor.getBlob(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_ACCESS_RULES_FROM_CARRIER_CONFIGS)))
                .setRemovableEmbedded(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_IS_REMOVABLE)))
                .setEnhanced4GModeEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED)))
                .setVideoTelephonyEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_VT_IMS_ENABLED)))
                .setWifiCallingEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_WFC_IMS_ENABLED)))
                .setWifiCallingMode(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_WFC_IMS_MODE)))
                .setWifiCallingModeForRoaming(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_WFC_IMS_ROAMING_MODE)))
                .setWifiCallingEnabledForRoaming(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED)))
                .setOpportunistic(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_IS_OPPORTUNISTIC)))
                .setGroupUuid(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_GROUP_UUID))))
                .setCountryIso(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_ISO_COUNTRY_CODE))))
                .setCarrierId(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_CARRIER_ID)))
                .setProfileClass(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_PROFILE_CLASS)))
                .setType(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_SUBSCRIPTION_TYPE)))
                .setGroupOwner(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_GROUP_OWNER))))
                .setEnabledMobileDataPolicies(TextUtils.emptyIfNull(
                        cursor.getString(cursor.getColumnIndexOrThrow(
                                SimInfo.COLUMN_ENABLED_MOBILE_DATA_POLICIES))))
                .setImsi(TextUtils.emptyIfNull(cursor.getString(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_IMSI))))
                .setUiccApplicationsEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_UICC_APPLICATIONS_ENABLED)))
                .setRcsUceEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_IMS_RCS_UCE_ENABLED)))
                .setCrossSimCallingEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_CROSS_SIM_CALLING_ENABLED)))
                .setRcsConfig(cursor.getBlob(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_RCS_CONFIG)))
                .setAllowedNetworkTypesForReasons(TextUtils.emptyIfNull(
                        cursor.getString(cursor.getColumnIndexOrThrow(
                                SimInfo.COLUMN_ALLOWED_NETWORK_TYPES_FOR_REASONS))))
                .setDeviceToDeviceStatusSharingPreference(cursor.getInt(
                        cursor.getColumnIndexOrThrow(
                                SimInfo.COLUMN_D2D_STATUS_SHARING)))
                .setVoImsOptInEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_VOIMS_OPT_IN_STATUS)))
                .setDeviceToDeviceStatusSharingContacts(TextUtils.emptyIfNull(cursor.getString(
                        cursor.getColumnIndexOrThrow(
                                SimInfo.COLUMN_D2D_STATUS_SHARING_SELECTED_CONTACTS))))
                .setNrAdvancedCallingEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_NR_ADVANCED_CALLING_ENABLED)))
                .setNumberFromCarrier(TextUtils.emptyIfNull(cursor.getString(
                        cursor.getColumnIndexOrThrow(
                                SimInfo.COLUMN_PHONE_NUMBER_SOURCE_CARRIER))))
                .setNumberFromIms(TextUtils.emptyIfNull(cursor.getString(
                        cursor.getColumnIndexOrThrow(
                                SimInfo.COLUMN_PHONE_NUMBER_SOURCE_IMS))))
                .setPortIndex(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_PORT_INDEX)))
                .setUsageSetting(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_USAGE_SETTING)))
                .setLastUsedTPMessageReference(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_TP_MESSAGE_REF)))
                .setUserId(cursor.getInt(cursor.getColumnIndexOrThrow(
                        SimInfo.COLUMN_USER_HANDLE)));

        return builder.build();
    }

    /**
     * Get the subscription info by subscription id.
     *
     * @param subId The subscription id
     *
     * @return The subscription info. {@code null} if not found.
     */
    @Nullable
    public SubscriptionInfoInternal getSubscriptionInfoInternal(int subId) {
        mReadWriteLock.readLock().lock();
        try {
            return mAllSubscriptionInfoInternalCache.get(subId);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Log debug messages.
     *
     * @param s debug messages
     */
    private void log(@NonNull String s) {
        Rlog.d(LOG_TAG, s);
    }

    /**
     * Log error messages.
     *
     * @param s error messages
     */
    private void loge(@NonNull String s) {
        Rlog.e(LOG_TAG, s);
    }

    /**
     * Log verbose messages.
     *
     * @param s debug messages.
     */
    private void logv(@NonNull String s) {
        if (VDBG) Rlog.v(LOG_TAG, s);
    }

    /**
     * Log debug messages and also log into the local log.
     *
     * @param s debug messages
     */
    private void logl(@NonNull String s) {
        log(s);
        mLocalLog.log(s);
    }

    /**
     * Dump the state of {@link SubscriptionManagerService}.
     *
     * @param fd File descriptor
     * @param printWriter Print writer
     * @param args Arguments
     */
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter printWriter,
            @NonNull String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        pw.println(SubscriptionManagerService.class.getSimpleName() + ":");
        pw.increaseIndent();
        pw.println("All subscriptions:");
        pw.increaseIndent();
        mAllSubscriptionInfoInternalCache.forEach((subId, subInfo) -> pw.println(subInfo));
        pw.decreaseIndent();
        pw.decreaseIndent();
    }
}