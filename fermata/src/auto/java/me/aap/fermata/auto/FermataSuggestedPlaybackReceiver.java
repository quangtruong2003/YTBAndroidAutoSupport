package me.aap.fermata.auto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import me.aap.fermata.media.service.FermataMediaServiceConnection;
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
        if (SuggestedMediaRegistry.control(command)) return;

        // The media bridge can receive transport commands while the YouTube Fragment
        // is temporarily not the active UI owner. Fall back to the real Fermata media
        // session so Play/Pause/Previous/Next never become dead buttons in Android Auto.
        FermataMediaServiceConnection.connect(null)
                .onSuccess(c -> {
                    var cb = c.getMediaSessionCallback();
                    if (cb == null) return;
                    switch (command == null ? "" : command) {
                        case "play" -> cb.onPlay();
                        case "pause" -> cb.onPause();
                        case "next" -> cb.onSkipToNext();
                        case "previous" -> cb.onSkipToPrevious();
                        default -> Log.w("Unknown media bridge command: ", command);
                    }
                })
                .onFailure(Log::e);
    }
}
