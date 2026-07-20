/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.mediatek.voltebootfix;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.RegistrationManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Root cause (OP5231 / 广电 46015, MTK md_auto_setup_ims=1):
 * at boot the AOSP framework enables the MmTel VOICE capability, but the MTK
 * IMS stack is purely reactive - it only requests the IMS PDN when the modem
 * emits a bearer-activation indication, and the modem only does that when it
 * receives a VoLTE feature-value *edge* (1->0->1). The plain boot path pushes
 * value=1 once (no edge) and {@code enableIms} is a no-op stub, so the modem
 * never activates the bearer and IMS never registers until the user manually
 * toggles "Advanced Calling".
 *
 * This service replays that edge exactly once per boot, once telephony is
 * ready, by driving {@link ImsMmTelManager#setAdvancedCallingSettingEnabled}
 * false then true. Requires system uid (MODIFY_PHONE_STATE) - satisfied via
 * android:sharedUserId="android.uid.system".
 */
public class VolteBootFixService extends Service {
    private static final String LOG_TAG = "MtkVolteBootFix";

    // Time to let the IMS service bind / carrier config settle before the edge.
    private static final long SETTLE_MS = 8000;
    // Gap between the disable and the re-enable so the modem sees a real edge.
    private static final long EDGE_GAP_MS = 3000;
    // Spacing between edge attempts if registration is not observed.
    private static final long RETRY_MS = 25000;
    // Fallback poll interval while waiting for a valid subId / ready telephony.
    private static final long POLL_MS = 5000;
    // Total edge attempts before giving up.
    private static final int MAX_ATTEMPTS = 3;

    private Context mContext;
    private HandlerThread mThread;
    private Handler mHandler;

    private final AtomicBoolean mArmed = new AtomicBoolean(false);
    private volatile boolean mRegistered = false;
    private volatile boolean mFinished = false;
    private int mAttempts = 0;
    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private ImsMmTelManager mMmTel;
    private RegistrationManager.RegistrationCallback mRegCallback;

    private final BroadcastReceiver mCarrierConfigReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(intent.getAction())) {
                mHandler.post(VolteBootFixService.this::tryArm);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mThread != null) {
            // Already running.
            return START_STICKY;
        }
        mContext = this;
        mThread = new HandlerThread("VolteBootFix");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());

        Log.i(LOG_TAG, "service started, waiting for telephony to become ready");

        registerReceiver(mCarrierConfigReceiver,
                new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));

        // Also poll, in case the broadcast already fired before we registered.
        mHandler.post(this::tryArm);
        return START_STICKY;
    }

    /**
     * Arm exactly once, as soon as we have a valid default-voice subId and the
     * carrier config for it has loaded. Until then, keep polling.
     */
    private void tryArm() {
        if (mFinished || mArmed.get()) {
            return;
        }

        int subId = SubscriptionManager.getDefaultVoiceSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId) || !isCarrierConfigReady(subId)) {
            mHandler.postDelayed(this::tryArm, POLL_MS);
            return;
        }

        if (!mArmed.compareAndSet(false, true)) {
            return;
        }

        mSubId = subId;
        Log.i(LOG_TAG, "armed for subId=" + subId + "; scheduling VoLTE edge in "
                + SETTLE_MS + "ms");

        try {
            ImsManager imsManager = mContext.getSystemService(ImsManager.class);
            mMmTel = imsManager.getImsMmTelManager(subId);
            registerRegistrationCallback();
        } catch (Exception e) {
            Log.e(LOG_TAG, "failed to obtain ImsMmTelManager for subId=" + subId, e);
        }

        mHandler.postDelayed(this::attemptEdge, SETTLE_MS);
    }

    private boolean isCarrierConfigReady(int subId) {
        try {
            CarrierConfigManager ccm = mContext.getSystemService(CarrierConfigManager.class);
            if (ccm == null) {
                return false;
            }
            android.os.PersistableBundle b = ccm.getConfigForSubId(subId);
            // A non-empty bundle that advertises VoLTE availability is enough to
            // know the framework has applied config for this subscription.
            return b != null && !b.isEmpty()
                    && b.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, false);
        } catch (Exception e) {
            return false;
        }
    }

    private void registerRegistrationCallback() {
        try {
            mRegCallback = new RegistrationManager.RegistrationCallback() {
                @Override
                public void onRegistered(int imsTransportType) {
                    Log.i(LOG_TAG, "IMS registered (transport=" + imsTransportType
                            + "); VoLTE boot fix succeeded");
                    mRegistered = true;
                    mHandler.post(VolteBootFixService.this::finishIfRegistered);
                }

                @Override
                public void onUnregistered(ImsReasonInfo info) {
                    Log.i(LOG_TAG, "IMS unregistered: " + info);
                    mRegistered = false;
                }
            };
            mMmTel.registerImsRegistrationCallback(mContext.getMainExecutor(), mRegCallback);
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not register IMS registration callback", e);
        }
    }

    private void attemptEdge() {
        if (mFinished || mRegistered) {
            finishIfRegistered();
            return;
        }
        if (mAttempts >= MAX_ATTEMPTS) {
            Log.w(LOG_TAG, "giving up after " + mAttempts + " attempts");
            finish();
            return;
        }
        if (mMmTel == null || !SubscriptionManager.isValidSubscriptionId(mSubId)) {
            mHandler.postDelayed(this::attemptEdge, POLL_MS);
            return;
        }

        try {
            // Respect the user's choice: only nudge if VoLTE is meant to be on.
            if (!mMmTel.isAdvancedCallingSettingEnabled()) {
                Log.i(LOG_TAG, "advanced calling is disabled by user; nothing to do");
                finish();
                return;
            }
        } catch (Exception e) {
            // IMS not ready yet - reschedule without consuming an attempt.
            Log.w(LOG_TAG, "isAdvancedCallingSettingEnabled failed, IMS not ready yet; retrying", e);
            mHandler.postDelayed(this::attemptEdge, POLL_MS);
            return;
        }

        mAttempts++;
        Log.i(LOG_TAG, "applying VoLTE edge (attempt " + mAttempts + "/" + MAX_ATTEMPTS
                + ") on subId=" + mSubId);

        try {
            // Disable first to force a genuine 1->0->1 feature-value edge to the modem.
            mMmTel.setAdvancedCallingSettingEnabled(false);
        } catch (Exception e) {
            Log.e(LOG_TAG, "setAdvancedCallingSettingEnabled(false) failed", e);
            mHandler.postDelayed(this::attemptEdge, RETRY_MS);
            return;
        }

        mHandler.postDelayed(() -> {
            try {
                mMmTel.setAdvancedCallingSettingEnabled(true);
                Log.i(LOG_TAG, "VoLTE edge applied; awaiting IMS registration");
            } catch (Exception e) {
                Log.e(LOG_TAG, "setAdvancedCallingSettingEnabled(true) failed", e);
            }
            // Verify; if not registered by RETRY_MS, try again (bounded).
            mHandler.postDelayed(this::attemptEdge, RETRY_MS);
        }, EDGE_GAP_MS);
    }

    private void finishIfRegistered() {
        if (mRegistered) {
            finish();
        }
    }

    private void finish() {
        if (mFinished) {
            return;
        }
        mFinished = true;
        Log.i(LOG_TAG, "done (registered=" + mRegistered + ")");
        try {
            if (mMmTel != null && mRegCallback != null) {
                mMmTel.unregisterImsRegistrationCallback(mRegCallback);
            }
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(mCarrierConfigReceiver);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mThread != null) {
            mThread.quitSafely();
            mThread = null;
        }
    }
}
