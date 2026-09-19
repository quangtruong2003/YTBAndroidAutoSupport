package me.aap.fermata.addon.web.yt;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.web.FermataChromeClient;
import me.aap.fermata.addon.web.FermataWebView;
import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.WebBrowserAddon;
import me.aap.fermata.addon.web.WebBrowserFragment;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.media.service.SuggestedMediaRegistry;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.VideoView;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.pref.SharedPreferenceStore;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
@Keep
@SuppressWarnings("unused")
public class YoutubeFragment extends WebBrowserFragment implements FermataServiceUiBinder.Listener {
	private static final String DEFAULT_URL = "https://m.youtube.com";
	private static final Set<String> DEFAULT_URLS = new HashSet<>(Arrays.asList(DEFAULT_URL, DEFAULT_URL + '/'));
	private static final Pref<LongSupplier> RESUME_POS = Pref.l("YT_RESUME_POS", 0L);
	private boolean playOnResume;
	private boolean recommendationPlaybackPending;
	private boolean recommendationDesiredPlaying = true;
	private long recommendationLoadGeneration;

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.youtube_fragment;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.youtube, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
		YoutubeAddon addon = AddonManager.get().getAddon(YoutubeAddon.class);
		if (addon == null) return;

		String url;
		boolean pause;

		if (state != null) {
			url = state.getString("url", DEFAULT_URL);
			pause = state.getBoolean("pause", false);
		} else {
			url = DEFAULT_URL;
			pause = false;
		}

		MainActivityDelegate.getActivityDelegate(view.getContext()).onSuccess(a -> {
			YoutubeWebView webView = a.findViewById(R.id.ytWebView);
			VideoView videoView = a.findViewById(R.id.ytVideoView);
			YoutubeWebClient webClient = new YoutubeWebClient();
			YoutubeChromeClient chromeClient = new YoutubeChromeClient(webView, videoView);
			webView.init(addon, webClient, chromeClient);
			registerListeners(a);
			webView.loadUrl(DEFAULT_URL);
			if (!DEFAULT_URL.equals(url)) a.post(() -> webView.loadUrl(url));

			boolean pendingSelection = SuggestedMediaRegistry.hasPendingPlayback();
			recommendationPlaybackPending = pendingSelection;
			SuggestedMediaRegistry.setPlaybackHandler(uri ->
					loadRecommendation(addon, webView, uri, true));
			SuggestedMediaRegistry.setControlHandler(command -> {
				MediaSessionCallback cb = a.getMediaSessionCallback();
				MediaEngine eng = cb.getEngine();
				boolean youtubeReady = (eng instanceof YoutubeMediaEngine) && (eng.getSource() != null);

				if ("play".equals(command)) {
					recommendationDesiredPlaying = true;
					if (youtubeReady) cb.onPlay();
					else webView.playAudible();
				} else if ("pause".equals(command)) {
					recommendationDesiredPlaying = false;
					// Cancel the JS autoplay retry immediately, even if the media engine has
					// not been created yet. Retry briefly to cover a <video> created later.
					webView.pause();
					if (youtubeReady) cb.onPause();
					for (long delay : new long[]{300L, 900L, 1800L}) {
						webView.postDelayed(() -> {
							if (!recommendationDesiredPlaying) webView.pause();
						}, delay);
					}
				} else if ("next".equals(command)) {
					String uri = SuggestedMediaRegistry.moveAdjacent(true);
					if (uri != null) loadRecommendation(addon, webView, uri, false);
					else cb.onSkipToNext();
				} else if ("previous".equals(command)) {
					String uri = SuggestedMediaRegistry.moveAdjacent(false);
					if (uri != null) loadRecommendation(addon, webView, uri, false);
					else cb.onSkipToPrevious();
				}
			});
			a.postDelayed(() -> {
				PreferenceStore ps = addon.getPreferenceStore();
				long pos = ps.getLongPref(RESUME_POS);
				ps.removePref(RESUME_POS);
				if (pendingSelection) return;
				MediaSessionCallback cb = a.getMediaSessionCallback();
				if (cb.getEngine() instanceof YoutubeMediaEngine) {
					if (pos > 0L) cb.onSeekTo(pos);
					if (pause) cb.onPause();
				}
			}, 3000L);
		});
	}

	private void loadRecommendation(YoutubeAddon addon, YoutubeWebView webView, String uri,
			boolean startPlaying) {
		long generation = ++recommendationLoadGeneration;
		recommendationPlaybackPending = true;
		if (startPlaying) recommendationDesiredPlaying = true;
		addon.getPreferenceStore().removePref(RESUME_POS);
		webView.loadUrl(uri);

		// A recommendation/queue jump is a programmatic navigation. It can create or
		// replace the <video> at several points during YouTube's SPA transition, so
		// apply the user's desired state at multiple stages. Play uses playAudible(),
		// which has its own bounded retry loop; Pause never schedules a Play.
		for (long delay : new long[]{250L, 900L, 1800L, 3200L}) {
			webView.postDelayed(() -> {
				if (generation != recommendationLoadGeneration) return;
				if (recommendationDesiredPlaying) {
					if (delay == 900L) webView.playAudible();
				} else {
					webView.pause();
				}
			}, delay);
		}
		webView.postDelayed(() -> {
			if (generation == recommendationLoadGeneration) recommendationPlaybackPending = false;
		}, 5000L);
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle state) {
		super.onSaveInstanceState(state);
		String url = getUrl();
		if (url != null) state.putString("url", url);
		WebBrowserAddon addon = getAddon();
		if (addon == null) return;
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		if (a == null) return;

		SharedPreferenceStore ps = addon.getPreferenceStore();
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();

		if (eng instanceof YoutubeMediaEngine) {
			state.putBoolean("pause", !cb.isPlaying());
			eng.getPosition().onSuccess(pos -> ps.applyLongPref(RESUME_POS, pos));
		} else {
			ps.removePref(RESUME_POS);
		}
	}

	@Override
	public void onDestroyView() {
		SuggestedMediaRegistry.setPlaybackHandler(null);
		SuggestedMediaRegistry.setControlHandler(null);
		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		a.setWebUiOnly(false);
		unregisterListeners(a);
		super.onDestroyView();
	}

	@Override
	protected void registerListeners(MainActivityDelegate a) {
		super.registerListeners(a);
		a.getMediaServiceBinder().addBroadcastListener(this);
	}

	protected void unregisterListeners(MainActivityDelegate a) {
		super.unregisterListeners(a);
		a.getMediaServiceBinder().removeBroadcastListener(this);
	}

	@Override
	public void onPause() {
		if (BuildConfig.AUTO) {
			// Android Auto may destroy and recreate the car surface while switching apps.
			// Never carry a WebChromeClient custom fullscreen view across that boundary:
			// its Surface can become stale and leave a black screen on the next launch.
			FermataWebView v = getWebView();
			if (v != null) {
				FermataChromeClient chrome = v.getWebChromeClient();
				if ((chrome != null) && chrome.isFullScreen()) chrome.exitFullScreen();
				v.setVisibility(View.VISIBLE);
			}
			// A WebChromeClient fullscreen transition can temporarily pause the car
			// fragment. Do not expose Fermata chrome during that transition; onDestroyView
			// is the point where we restore normal chrome.
			MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> a.setWebUiOnly(true));
		} else {
			MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
				FermataServiceUiBinder b = a.getMediaServiceBinder();
				if (YoutubeMediaEngine.isYoutubeItem(b.getCurrentItem()) && b.isPlaying()) {
					b.getMediaSessionCallback().onPause();
					playOnResume = true;
				} else {
					playOnResume = false;
				}
			});
		}
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
			a.setWebUiOnly(true);
			FermataWebView v = getWebView();
			if (v != null) {
				FermataChromeClient chrome = v.getWebChromeClient();
				if ((chrome == null) || !chrome.isFullScreen()) v.setVisibility(View.VISIBLE);

				// A media session can survive/reappear with STATE_PLAYING even though the
				// restored YouTube page has no playing <video>. Correct that ghost state
				// after the page has had time to attach its player.
				v.postDelayed(() -> {
					if (recommendationPlaybackPending) return;
					v.evaluateJavascript(
						"(function(){var e=document.querySelector('video');" +
								"return !!e && !e.paused && !e.ended && e.readyState>=2;})()",
						playing -> {
							if (!"true".equals(playing)) {
								MediaSessionCallback cb = a.getMediaSessionCallback();
								if (cb.isPlaying()) cb.onPause();
							}
						});
				}, 1800L);
			}
		});
		if (BuildConfig.AUTO || !playOnResume) return;
		playOnResume = false;
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
			FermataServiceUiBinder b = a.getMediaServiceBinder();
			if (YoutubeMediaEngine.isYoutubeItem(b.getCurrentItem())) {
				b.getMediaSessionCallback().onPlay();
			}
		});
	}

	public void loadUrl(String url) {
		FermataWebView v = getWebView();
		if (v != null) v.loadUrl(url);
	}

	@Override
	public void onPlayableChanged(MediaLib.PlayableItem oldItem, MediaLib.PlayableItem newItem) {
		if (isHidden()) return;

		// Keep playback in YouTube's own Web UI instead of automatically switching
		// to Fermata's generic full-screen player. This preserves YouTube navigation,
		// queue/next controls and the familiar player chrome inside Android Auto.
		if (!YoutubeMediaEngine.isYoutubeItem(newItem) && YoutubeMediaEngine.isYoutubeItem(oldItem)) {
			FermataWebView v = getWebView();
			if (v == null) return;
			FermataChromeClient chrome = v.getWebChromeClient();
			if ((chrome != null) && chrome.isFullScreen()) chrome.exitFullScreen();
		}
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return ToolBarView.Mediator.Invisible.instance;
	}

	@Override
	public boolean canScrollUp() {
		FermataWebView v = getWebView();
		if (v == null) return false;
		FermataChromeClient chrome = v.getWebChromeClient();
		return (chrome != null) && (chrome.isFullScreen() || (v.getScrollY() > 0));
	}

	@Nullable
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(YoutubeAddon.class);
	}

	@Nullable
	protected YoutubeWebView getWebView() {
		View v = getView();
		return (v != null) ? v.findViewById(R.id.ytWebView) : null;
	}

	protected boolean isDesktopVersionSupported() {
		return false;
	}

	@Override
	protected String getSearchUrl() {
		return "https://www.youtube.com/results?search_query=";
	}
}
