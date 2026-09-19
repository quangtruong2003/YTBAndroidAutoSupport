package me.aap.fermata.auto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import me.aap.fermata.media.service.SuggestedMediaRegistry;
import me.aap.utils.log.Log;

/** Receives media-browse selections from the dedicated Android Auto media bridge. */
public class FermataSuggestedPlaybackReceiver extends BroadcastReceiver {
    public static final String ACTION_PLAY = "me.aap.fermata.action.PLAY_SUGGESTED";
    public static final String ACTION_CONTROL = "me.aap.fermata.action.MEDIA_CONTROL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_PLAY.equals(intent.getAction())) {
            String mediaId = intent.getStringExtra("media_id");
            Log.i("FermataSuggestedPlaybackReceiver: play ", mediaId);
            SuggestedMediaRegistry.play(mediaId);
            return;
        }
        if (!ACTION_CONTROL.equals(intent.getAction())) return;
        String command = intent.getStringExtra("command");
        Log.i("FermataSuggestedPlaybackReceiver: control ", command);
        SuggestedMediaRegistry.control(command);
    }
}
