package com.desmond.gptwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        L.i("BOOT_RECEIVER action=" + intent.getAction() + " mode=" + Prefs.mode(context));
        try {
            context.startActivity(new Intent(context, ShimActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ShimActivity.EXTRA_ACTION, "fgs"));
            L.i("BOOT_SHIM_START_OK");
        } catch (Throwable t) {
            L.e("BOOT_SHIM_START_FAIL", t);
        }
    }
}
