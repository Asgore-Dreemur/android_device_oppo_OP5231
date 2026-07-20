/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.mediatek.voltebootfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Kicks off {@link VolteBootFixService} once the device has finished its
 * (locked) boot. The service then waits until telephony is ready and replays
 * the "Advanced Calling" off->on edge that the MTK modem needs to bring up the
 * IMS bearer / registration after every reboot.
 */
public class OnBootCompleteReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "MtkVolteBootFix";

    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.i(LOG_TAG, "onBoot: starting VolteBootFixService");
        context.startService(new Intent(context, VolteBootFixService.class));
    }
}
