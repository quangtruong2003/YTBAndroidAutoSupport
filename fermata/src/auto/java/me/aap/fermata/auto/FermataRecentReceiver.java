package me.aap.fermata.auto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import me.aap.fermata.media.service.FermataMediaServiceConnection;
import me.aap.utils.log.Log;

/**
 * Best-effort activation hook used by the dedicated same-device head-unit setup.
 *
 * It only primes Fermata's media service so Android Auto considers Fermata the
 * current/recent media app. It does not start playback or open a video.
 */
public class FermataRecentReceiver extends BroadcastReceiver {
    public static final String ACTION_ACTIVATE_MEDIA = "me.aap.fermata.action.ACTIVATE_MEDIA";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_ACTIVATE_MEDIA.equals(intent.getAction())) return;
        Log.i("FermataRecentReceiver: activating media service");
        FermataMediaServiceConnection.connect(null)
                .onSuccess(c -> {
                    var cb = c.getMediaSessionCallback();
                    // Runtime package name is authoritative for the custom Web+ build.
                    // BuildConfig.APPLICATION_ID can resolve to the base id in this
                    // dynamic-feature/flavor build and would incorrectly reactivate the
                    // hidden FermataMediaService instead of leaving AA on the bridge.
                    boolean bridged = context.getPackageName().contains(".webplus");
                    if ((cb != null) && !bridged) {
                        cb.getSession().setActive(true);
                        Log.i("FermataRecentReceiver: media session active");
                    } else if (bridged) {
                        Log.i("FermataRecentReceiver: media bridge owns Android Auto session");
                    }
                })
                .onFailure(Log::e);
    }
}
