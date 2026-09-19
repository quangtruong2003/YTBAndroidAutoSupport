package me.aap.fermata.media.service;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat.QueueItem;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import me.aap.fermata.FermataApplication;
import me.aap.utils.app.App;
import me.aap.utils.log.Log;

/**
 * Small bridge between a dynamic media UI (YouTube WebView) and the base
 * MediaBrowserService used by Android Auto's dashboard recommendations.
 */
public final class SuggestedMediaRegistry {
	public static final String ROOT_ID = "fermata:suggested";
	public static final String EXTRA_SUGGESTED = "android.service.media.extra.SUGGESTED";
	public static final String EXTRA_RECENT = "android.service.media.extra.RECENT";
	private static final String MEDIA_ID_PREFIX = "youtube:suggested:";
	private static final String MEDIA_BRIDGE_PACKAGE = "me.aap.fermata.mediabridge";
	private static final String PREFS = "youtube_suggestions";
	private static final String PREF_JSON = "json";
	private static final String PREF_PENDING_URI = "pending_uri";
	private static final String OPENHU_PACKAGE = "com.andrerinas.headunitrevived";
	private static final String OPENHU_FERMATA_RECEIVER =
			"com.andrerinas.openheadunit.connection.self.FermataCarAppLaunchReceiver";
	private static final String ACTION_OPEN_FERMATA =
			"com.andrerinas.openheadunit.action.OPEN_FERMATA_WEB";
	private static final String ACTION_BRIDGE_UPDATE =
			"me.aap.fermata.mediabridge.action.UPDATE_SUGGESTIONS";
	private static final String ACTION_BRIDGE_PLAYBACK =
			"me.aap.fermata.mediabridge.action.UPDATE_PLAYBACK";
	private static final String ACTION_BRIDGE_HEARTBEAT =
			"me.aap.fermata.mediabridge.action.HEARTBEAT";
	private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{6,20}");
	private static volatile List<MediaItem> items = Collections.emptyList();
	private static volatile List<MediaItem> playbackQueue = Collections.emptyList();
	private static volatile Bitmap fallbackIcon;
	private static volatile PlaybackHandler playbackHandler;
	private static volatile ControlHandler controlHandler;
	private static volatile String currentMediaId;
	private static volatile Runnable changeListener;

	private SuggestedMediaRegistry() {
	}

	public static void updateYoutube(String json) {
		try {
			JSONArray a = new JSONArray(json);
			List<MediaItem> result = new ArrayList<>(Math.min(a.length(), 20));
			Bitmap fallback = getFallbackIcon();

			for (int i = 0; i < a.length() && result.size() < 20; i++) {
				JSONObject o = a.optJSONObject(i);
				if (o == null) continue;
				String id = o.optString("videoId", "");
				if (!YOUTUBE_ID.matcher(id).matches()) continue;

				String title = o.optString("title", "").trim();
				if (title.isEmpty()) title = "YouTube";
				if (title.length() > 180) title = title.substring(0, 180);

				String thumb = o.optString("thumb", "");
				MediaDescriptionCompat.Builder d = new MediaDescriptionCompat.Builder()
						.setMediaId(MEDIA_ID_PREFIX + id)
						.setTitle(title)
						.setSubtitle("YouTube")
						.setIconBitmap(fallback)
						.setMediaUri(Uri.parse("https://m.youtube.com/watch?v=" + id));
				if (thumb.startsWith("https://")) d.setIconUri(Uri.parse(thumb));
				result.add(new MediaItem(d.build(), MediaItem.FLAG_PLAYABLE));
			}

			items = Collections.unmodifiableList(result);
			FermataApplication.get().getSharedPreferences(PREFS, 0)
					.edit().putString(PREF_JSON, json).apply();
			Log.i("Updated YouTube suggestions: ", result.size());
			notifyChanged();
			broadcastToMediaBridge(json);

			FermataApplication app = FermataApplication.get();
			for (MediaItem item : result) {
				Uri icon = item.getDescription().getIconUri();
				if (icon == null) continue;
				String mediaId = item.getMediaId();
				app.getBitmapCache().getBitmap(app, icon.toString(), true, true).main()
						.onSuccess(bitmap -> updateIcon(mediaId, bitmap));
			}
		} catch (Exception ex) {
			Log.e(ex, "Failed to update YouTube suggestions");
		}
	}

	private static void updateIcon(String mediaId, Bitmap bitmap) {
		if ((mediaId == null) || (bitmap == null)) return;
		bitmap = scaleForCar(bitmap);
		boolean changed = false;
		synchronized (SuggestedMediaRegistry.class) {
			List<MediaItem> current = items;
			for (int i = 0; i < current.size(); i++) {
				MediaItem old = current.get(i);
				MediaDescriptionCompat d = old.getDescription();
				if (!mediaId.equals(d.getMediaId())) continue;
				MediaDescriptionCompat nd = new MediaDescriptionCompat.Builder()
						.setMediaId(d.getMediaId())
						.setTitle(d.getTitle())
						.setSubtitle(d.getSubtitle())
						.setDescription(d.getDescription())
						.setIconBitmap(bitmap)
						.setIconUri(d.getIconUri())
						.setMediaUri(d.getMediaUri())
						.setExtras(d.getExtras())
						.build();
				List<MediaItem> next = new ArrayList<>(current);
				next.set(i, new MediaItem(nd, old.getFlags()));
				items = Collections.unmodifiableList(next);
				changed = true;
				break;
			}
		}
		if (changed) notifyChanged();
	}

	private static Bitmap scaleForCar(Bitmap bitmap) {
		final int max = 192;
		int w = bitmap.getWidth();
		int h = bitmap.getHeight();
		if ((w <= max) && (h <= max)) return bitmap;
		float scale = Math.min((float) max / Math.max(1, w), (float) max / Math.max(1, h));
		return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(w * scale)),
				Math.max(1, Math.round(h * scale)), true);
	}

	private static Bitmap getFallbackIcon() {
		Bitmap b = fallbackIcon;
		if (b != null) return b;
		synchronized (SuggestedMediaRegistry.class) {
			b = fallbackIcon;
			if (b != null) return b;
			try {
				FermataApplication app = FermataApplication.get();
				Drawable d = app.getPackageManager().getApplicationIcon(app.getPackageName());
				b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
				Canvas c = new Canvas(b);
				d.setBounds(0, 0, b.getWidth(), b.getHeight());
				d.draw(c);
			} catch (Exception ex) {
				Log.e(ex, "Failed to create recommendation fallback icon");
				b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
			}
			fallbackIcon = b;
			return b;
		}
	}

	private static void broadcastToMediaBridge(String json) {
		try {
			Intent i = new Intent(ACTION_BRIDGE_UPDATE)
					.setComponent(new ComponentName(MEDIA_BRIDGE_PACKAGE,
							MEDIA_BRIDGE_PACKAGE + ".SuggestionReceiver"))
					.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
					.putExtra("json", json);
			FermataApplication.get().sendBroadcast(i);
			Log.i("Broadcast YouTube suggestions to Fermata Media bridge");
		} catch (Exception ex) {
			Log.e(ex, "Failed to broadcast YouTube suggestions to media bridge");
		}
	}

	public static void broadcastHeartbeat() {
		try {
			Intent i = new Intent(ACTION_BRIDGE_HEARTBEAT)
					.setComponent(new ComponentName(MEDIA_BRIDGE_PACKAGE,
							MEDIA_BRIDGE_PACKAGE + ".SuggestionReceiver"))
					.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
			FermataApplication.get().sendBroadcast(i);
		} catch (Exception ex) {
			Log.e(ex, "Failed to heartbeat media bridge");
		}
	}

	public static void broadcastPlayback(@Nullable MediaMetadataCompat metadata,
			@Nullable PlaybackStateCompat state) {
		try {
			Intent i = new Intent(ACTION_BRIDGE_PLAYBACK)
					.setComponent(new ComponentName(MEDIA_BRIDGE_PACKAGE,
							MEDIA_BRIDGE_PACKAGE + ".SuggestionReceiver"))
					.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);

			if (metadata != null) {
				MediaDescriptionCompat d = metadata.getDescription();
				CharSequence title = d.getTitle();
				CharSequence subtitle = d.getSubtitle();
				if (title != null) i.putExtra("title", title.toString());
				if (subtitle != null) i.putExtra("subtitle", subtitle.toString());
				String art = metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI);
				if ((art == null) || art.isEmpty())
					art = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI);
				if ((art != null) && !art.isEmpty()) i.putExtra("art", art);
			}

			if (state != null) {
				i.putExtra("state", state.getState());
				i.putExtra("position", state.getPosition());
			}
			FermataApplication.get().sendBroadcast(i);
		} catch (Exception ex) {
			Log.e(ex, "Failed to publish playback to media bridge");
		}
	}

	private static void notifyChanged() {
		Runnable l = changeListener;
		if (l != null) App.get().getHandler().post(l);
	}

	public static void restoreYoutube() {
		try {
			String json = FermataApplication.get().getSharedPreferences(PREFS, 0)
					.getString(PREF_JSON, "");
			if ((json != null) && !json.isEmpty()) updateYoutube(json);
		} catch (Exception ex) {
			Log.e(ex, "Failed to restore cached YouTube suggestions");
		}
	}

	public static List<MediaItem> getItems() {
		return items;
	}

	@Nullable
	public static MediaItem getItem(String mediaId) {
		if (mediaId == null) return null;
		for (MediaItem item : items) {
			if (mediaId.equals(item.getMediaId())) return item;
		}
		return null;
	}

	public static List<QueueItem> getQueueItems() {
		List<MediaItem> current = items;
		if (current.isEmpty()) return Collections.emptyList();
		List<QueueItem> queue = new ArrayList<>(current.size());
		for (int i = 0; i < current.size(); i++)
			queue.add(new QueueItem(current.get(i).getDescription(), i));
		return queue;
	}

	public static boolean playQueueItem(long queueId) {
		List<MediaItem> current = items;
		if ((queueId < 0) || (queueId >= current.size())) return false;
		return play(current.get((int) queueId).getMediaId());
	}

	/**
	 * Moves within the exact Android Auto recommendation queue. This gives
	 * deterministic previous/next behavior even on standalone YouTube watch pages
	 * where the site's nextVideo()/previousVideo() methods can legally be no-ops.
	 */
	@Nullable
	public static String moveAdjacent(boolean next) {
		List<MediaItem> current = playbackQueue;
		String selected = currentMediaId;
		if ((selected == null) || current.isEmpty()) return null;

		int index = -1;
		for (int i = 0; i < current.size(); i++) {
			if (selected.equals(current.get(i).getMediaId())) {
				index = i;
				break;
			}
		}
		if (index < 0) return null;
		int target = next ? index + 1 : index - 1;
		if ((target < 0) || (target >= current.size())) return null;

		MediaItem item = current.get(target);
		Uri uri = item.getDescription().getMediaUri();
		if (uri == null) return null;
		currentMediaId = item.getMediaId();
		return uri.toString();
	}

	public static boolean hasItems() {
		return !items.isEmpty();
	}

	public static boolean hasPendingPlayback() {
		String uri = FermataApplication.get().getSharedPreferences(PREFS, 0)
				.getString(PREF_PENDING_URI, "");
		return (uri != null) && !uri.isEmpty();
	}

	public static void setPlaybackHandler(@Nullable PlaybackHandler handler) {
		playbackHandler = handler;
		if (handler == null) return;

		String uri = FermataApplication.get().getSharedPreferences(PREFS, 0)
				.getString(PREF_PENDING_URI, "");
		if ((uri == null) || uri.isEmpty()) return;
		FermataApplication.get().getSharedPreferences(PREFS, 0)
				.edit().remove(PREF_PENDING_URI).apply();
		Log.i("Consuming pending Android Auto YouTube selection: ", uri);
		App.get().getHandler().post(() -> handler.play(uri));
	}

	public static void setControlHandler(@Nullable ControlHandler handler) {
		controlHandler = handler;
	}

	public static void control(String command) {
		ControlHandler h = controlHandler;
		if (h != null) App.get().getHandler().post(() -> h.control(command));
	}

	public static boolean play(String mediaId) {
		if ((mediaId == null) || !mediaId.startsWith(MEDIA_ID_PREFIX)) return false;
		String id = mediaId.substring(MEDIA_ID_PREFIX.length());
		if (!YOUTUBE_ID.matcher(id).matches()) return true;
		currentMediaId = mediaId;
		// Freeze the visible recommendation row for transport navigation. YouTube
		// replaces its live recommendations as soon as a video opens, but Previous/
		// Next should continue through the row the user actually selected from.
		List<MediaItem> visible = items;
		for (MediaItem item : visible) {
			if (mediaId.equals(item.getMediaId())) {
				playbackQueue = visible;
				break;
			}
		}

		String uri = "https://m.youtube.com/watch?v=" + id;
		PlaybackHandler h = playbackHandler;
		if (h != null) {
			App.get().getHandler().post(() -> h.play(uri));
			return true;
		}

		// Android Auto can invoke a recommendation while Fermata Web is not the
		// foreground car app. Do not drop that selection. Persist it, then ask the
		// same-device Open Headunit helper to bring the real Fermata Web car app to
		// the foreground. YoutubeFragment consumes this URI as soon as its WebView
		// is ready.
		FermataApplication.get().getSharedPreferences(PREFS, 0)
				.edit().putString(PREF_PENDING_URI, uri).apply();
		Log.i("Queued Android Auto YouTube selection until Fermata Web opens: ", uri);
		requestOpenFermataWeb();
		return true;
	}

	private static void requestOpenFermataWeb() {
		try {
			Intent i = new Intent(ACTION_OPEN_FERMATA)
					.setComponent(new ComponentName(OPENHU_PACKAGE, OPENHU_FERMATA_RECEIVER))
					.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
			FermataApplication.get().sendBroadcast(i);
			Log.i("Requested Fermata Web car app from Open Headunit");
		} catch (Exception ex) {
			Log.e(ex, "Failed to request Fermata Web car app");
		}
	}

	public static void setChangeListener(@Nullable Runnable listener) {
		changeListener = listener;
	}

	public interface PlaybackHandler {
		void play(String uri);
	}

	public interface ControlHandler {
		void control(String command);
	}
}
