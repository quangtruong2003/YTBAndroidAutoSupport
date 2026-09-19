package me.aap.fermata.addon.web.yt;

import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_ERR;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_EVENT;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_ENDED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_FOUND;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_PAUSED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_PLAYING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_QUALITIES;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_RECOMMENDATIONS;

import android.content.Context;
import android.util.AttributeSet;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;

import org.json.JSONArray;

import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.web.FermataChromeClient;
import me.aap.fermata.addon.web.FermataJsInterface;
import me.aap.fermata.addon.web.FermataWebView;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * @author Andrey Pavlenko
 */
public class YoutubeWebView extends FermataWebView {
	private static final String CLEAR_HIGHEST_VIDEO_QUALITY_JS =
			"function clearFermataQ() {\n" +
					"  if (!window.__fermataQ) return;\n" +
					"  if (window.__fermataQ.timeout) clearTimeout(window.__fermataQ.timeout);\n" +
					"  if (window.__fermataQ.player && window.__fermataQ.handler) {\n" +
					"    try { window.__fermataQ.player.removeEventListener('onStateChange', window.__fermataQ.handler); } catch(e) {}\n" +
					"  }\n" +
					"  window.__fermataQ = null;\n" +
					"}\n";
	private YoutubeJsInterface js;

	public YoutubeWebView(Context context) {
		super(context);
	}

	public YoutubeWebView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public YoutubeWebView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	protected FermataJsInterface createJsInterface() {
		MainActivityDelegate a = MainActivityDelegate.get(getContext());
		return js = new YoutubeJsInterface(this, new YoutubeMediaEngine(this, a));
	}

	@Override
	public YoutubeAddon getAddon() {
		return (YoutubeAddon) super.getAddon();
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		super.onPreferenceChanged(store, prefs);

		if (getAddon().autoHighestQualityChanged(prefs)) {
			if (getAddon().autoHighestQuality()) setHighestVideoQuality();
			else clearHighestVideoQuality();
		}

		if (YoutubeSponsorBlock.isPreferenceChanged(prefs)) injectSponsorBlock();
	}

	@Override
	public void loadUrl(@NonNull String url) {
		// Avoid dumping large injected JavaScript strings to logcat on the debug
		// build. This was surprisingly expensive during YouTube startup.
		if (!url.startsWith("javascript:")) Log.d("Loading URL: " + url);
		super.loadUrl(url);
	}

	@Override
	public void goBack() {
		MediaSessionCallback cb = MainActivityDelegate.get(getContext()).getMediaSessionCallback();
		if (cb.getEngine() instanceof YoutubeMediaEngine) cb.onStop();
		super.goBack();
	}

	@Override
	protected void pageLoaded(String uri) {
		// Attach only the playback bridge immediately. Everything else is secondary
		// and is delayed so first paint/touch is not competing with extra JS/disk IO.
		attachListeners();
		postDelayed(this::injectSponsorBlock, 1200L);
		postDelayed(this::addFocusHighlight, 1600L);
		postDelayed(this::refreshRecommendations, 1800L);
		postDelayed(() -> CookieManager.getInstance().flush(), 2500L);
	}

	protected void submitForm() {
		if (!me.aap.fermata.BuildConfig.AUTO) return;
		loadUrl("javascript:\n" +
				"var e = new KeyboardEvent('keydown',\n" +
				"{ code: 'Enter', key: 'Enter', keyCode: 13, view: window, bubbles: true });\n" +
				"document.activeElement.dispatchEvent(e);\n" +
				"e = new KeyboardEvent('keyup',\n" +
				"{ code: 'Enter', key: 'Enter', keyCode: 13, view: window, bubbles: true });\n" +
				"document.activeElement.dispatchEvent(e);");
	}

	void refreshRecommendations() {
		// YouTube renders the recommendation rail lazily. A few fixed delays were
		// not enough on Android Auto, so install a debounced MutationObserver and
		// keep a couple of fallback scans for slow pages.
		postDelayed(this::installRecommendationPublisher, 1000L);
		postDelayed(this::publishRecommendations, 4500L);
		postDelayed(this::publishRecommendations, 10000L);
	}

	private void installRecommendationPublisher() {
		String scan =
				"var out=[],seen={},cur=(new URL(location.href)).searchParams.get('v');" +
				"var hs=document.querySelectorAll('yt-lockup-view-model,ytm-media-item,ytm-video-with-context-renderer,ytm-compact-video-renderer');" +
				"for(var n=0;n<hs.length&&out.length<20;n++){" +
				"var h=hs[n],a=h.querySelector('.ytLockupMetadataViewModelTitle a[href*=\\\"watch?v=\\\"],.media-item-metadata a[href*=\\\"watch?v=\\\"],a[href*=\\\"watch?v=\\\"]');if(!a)continue;" +
				"var u=new URL(a.href,location.href),id=u.searchParams.get('v');" +
				"if(!id||id===cur||seen[id])continue;seen[id]=true;" +
				"var hn=h.querySelector('.ytLockupMetadataViewModelTitle,.media-item-headline .ytAttributedStringHost,.media-item-headline,h3,h4,.compact-media-item-headline');" +
				"var title=((hn&&(hn.innerText||hn.getAttribute('aria-label')))||a.getAttribute('aria-label')||'YouTube').trim().replace(/\\s+/g,' ').slice(0,180);" +
				"var img=h.querySelector('.ytLockupViewModelContentImage img,.media-item-thumbnail-container img,img'),thumb=(img&&(img.currentSrc||img.src))||('https://i.ytimg.com/vi/'+id+'/hqdefault.jpg');" +
				"out.push({videoId:id,title:title,thumb:thumb});}";
		String script = "(function(){try{" +
				"window.__fermataPublishRecommendations=function(){try{" + scan +
				"if(!out.length)return 0;var json=JSON.stringify(out);" +
				"if(json!==window.__fermataLastRecommendations){window.__fermataLastRecommendations=json;" +
				JS_EVENT + "(" + JS_RECOMMENDATIONS + ",json);}return out.length;" +
				"}catch(e){" + JS_EVENT + "(" + JS_ERR + ",'Recommendations scan: '+e);return 0;}};" +
				"if(window.__fermataRecommendationsObserver)window.__fermataRecommendationsObserver.disconnect();" +
				"var timer=null;window.__fermataRecommendationsObserver=new MutationObserver(function(){" +
				"if(timer)clearTimeout(timer);timer=setTimeout(function(){window.__fermataPublishRecommendations();},1500);});" +
				"window.__fermataRecommendationsObserver.observe(document.documentElement,{childList:true,subtree:true});" +
				"window.__fermataPublishRecommendations();" +
				"}catch(e){" + JS_EVENT + "(" + JS_ERR + ",'Recommendations observer: '+e);}})();";
		evaluateJavascript(script, null);
	}

	private void publishRecommendations() {
		evaluateJavascript("window.__fermataPublishRecommendations ? window.__fermataPublishRecommendations() : 0;", null);
	}

	private void attachListeners() {
		String debug = BuildConfig.D ? JS_EVENT + "(" + JS_VIDEO_FOUND + ", null);\n" : "";
		String scale = getAddon().getScale().prefName();
		loadUrl("javascript:(function(){\n" +
				"function attachVideoListeners(v) {\n" +
				"  if (!v || v.getAttribute('FermataAttached') === 'true') return;\n" +
				"  v.setAttribute('FermataAttached', 'true');\n" +
				"  v.setAttribute('style', 'object-fit:" + scale + "');\n" + debug +
				"  if ((v.currentTime > 0) && !v.paused && !v.ended) " + JS_EVENT + "(" + JS_VIDEO_PLAYING +
				", v.currentSrc);\n" +
				"  v.addEventListener('playing', function() {" + JS_EVENT + "(" + JS_VIDEO_PLAYING +
				", v.currentSrc);});\n" +
				"  v.addEventListener('pause', function() {" + JS_EVENT + "(" + JS_VIDEO_PAUSED +
				", v.currentSrc);});\n" +
				"  v.addEventListener('ended', function() {" + JS_EVENT + "(" + JS_VIDEO_ENDED +
				", null);});\n" +
				"}\n" +
				"document.querySelectorAll('video').forEach(attachVideoListeners);\n" +
				"if(window.__fermataVideoObserver)window.__fermataVideoObserver.disconnect();\n" +
				"window.__fermataVideoObserver=new MutationObserver(function(ms){\n" +
				"  ms.forEach(function(m){m.addedNodes.forEach(function(n){\n" +
				"    if(n.nodeType!==1)return;\n" +
				"    if(n.tagName==='VIDEO')attachVideoListeners(n);\n" +
				"    if(n.querySelectorAll)n.querySelectorAll('video').forEach(attachVideoListeners);\n" +
				"  });});\n" +
				"});\n" +
				"window.__fermataVideoObserver.observe(document.documentElement,{childList:true,subtree:true});\n" +
				"})();");
	}

	private void injectSponsorBlock() {
		String script = YoutubeSponsorBlock.getScript(getContext(), getAddon().getPreferenceStore());
		if (!script.isEmpty()) evaluateJavascript(script, result -> configureSponsorBlock());
		else configureSponsorBlock();
	}

	private void configureSponsorBlock() {
		evaluateJavascript("if (window.FermataSponsorBlock) window.FermataSponsorBlock.configure(" +
				YoutubeSponsorBlock.getConfigJson(getAddon().getPreferenceStore()) + ");", null);
	}

	protected boolean requestFullScreen() {
		loadUrl("javascript: var v = document.querySelector('video');\n" +
				"if ('webkitRequestFullscreen' in v) v.webkitRequestFullscreen();\n" +
				"else if ('requestFullscreen' in v) v.requestFullscreen();\n" +
				"else " + JS_EVENT + "(" + JS_ERR + ", 'Method requestFullscreen not found in ' + v);");
		return true;
	}

	void play() {
		resume(0L);
	}

	/**
	 * Starts a YouTube selection made from Android Auto and explicitly restores
	 * audible playback. YouTube/Chromium may autoplay a programmatically loaded
	 * watch page muted, so retry until the real player/video element is ready.
	 */
	void playAudible() {
		loadUrl("javascript:(function(){" +
				"window.__fermataDesiredPlaying=true;" +
				"if(window.__fermataAudibleTimer)clearInterval(window.__fermataAudibleTimer);" +
				"var until=Date.now()+12000,stable=0;" +
				"function stopRetry(){if(window.__fermataAudibleTimer)clearInterval(window.__fermataAudibleTimer);" +
				"window.__fermataAudibleTimer=null;}" +
				"function forceAudible(){" +
				"if(window.__fermataDesiredPlaying===false){stopRetry();return;}" +
				"var v=document.querySelector('video');" +
				"var p=document.querySelector('#movie_player')||document.querySelector('.html5-video-player');" +
				"try{if(p&&typeof p.unMute==='function')p.unMute();" +
				"if(p&&typeof p.getVolume==='function'&&p.getVolume()===0&&typeof p.setVolume==='function')p.setVolume(100);}catch(e){}" +
				"if(!v){stable=0;return;}try{v.defaultMuted=false;if(v.muted)v.muted=false;if(v.volume===0)v.volume=1.0;" +
				"if(v.paused){stable=0;var r=v.play();if(r&&r.catch)r.catch(function(){});return;}" +
				"if(!v.muted&&v.volume>0&&v.readyState>=2){if(++stable>=3)stopRetry();}else stable=0;}catch(e){stable=0;}" +
				"}" +
				"forceAudible();" +
				"window.__fermataAudibleTimer=setInterval(function(){forceAudible();" +
				"if(Date.now()>=until)stopRetry();},200);" +
				"})();");
	}

	void resume(long positionMs) {
		double pos = positionMs / 1000d;
		loadUrl("javascript:(function(){" +
				"window.__fermataDesiredPlaying=true;" +
				"var v=document.querySelector('video'); if(v==null)return;" +
				(positionMs > 0 ? "if(Math.abs(v.currentTime-" + pos + ")>1.5)v.currentTime=" + pos + ";" : "") +
				"var p=v.play(); if(p&&p.catch)p.catch(function(){setTimeout(function(){" +
				"if(window.__fermataDesiredPlaying!==false)v.play();},250);});" +
				"})();");
	}

	void pause() {
		loadUrl("javascript:(function(){window.__fermataDesiredPlaying=false;" +
				"if(window.__fermataAudibleTimer){clearInterval(window.__fermataAudibleTimer);" +
				"window.__fermataAudibleTimer=null;}var v=document.querySelector('video');" +
				"if(v!=null&&!v.paused)v.pause();})();");
	}

	void stop() {
		loadUrl("javascript:(function(){window.__fermataDesiredPlaying=false;" +
				"if(window.__fermataAudibleTimer){clearInterval(window.__fermataAudibleTimer);" +
				"window.__fermataAudibleTimer=null;}var v=document.querySelector('video');" +
				"if(v!=null){v.currentTime=0;v.pause();}})();");
	}

	void prev() {
		prevNext(false);
	}

	void next() {
		prevNext(true);
	}

	private void prevNext(boolean next) {
		Log.i("YouTube WebView transport: ", next ? "next" : "previous");
		// Keep the site's fullscreen view intact and issue exactly one transport action.
		// A larger settling script caused duplicate/no-op navigation on YouTube's SPA.
		String js = "javascript:(function(){" +
				"var n=" + (next ? "true" : "false") + ";" +
				"var p=document.querySelector('#movie_player')||document.querySelector('.html5-video-player');" +
				"try{if(p){" +
				"(n&&typeof p.nextVideo==='function'?(p.nextVideo(),1):" +
				"(!n&&typeof p.previousVideo==='function'?(p.previousVideo(),1):0));" +
				"return;}}catch(e){}" +
				"var s=n?['.ytp-next-button','button.player-controls-middle-next-button'," +
				"'button.player-middle-controls-prev-next-button:last-of-type']:" +
				"['.ytp-prev-button','button.player-controls-middle-prev-button'," +
				"'button.player-middle-controls-prev-next-button:first-of-type'];" +
				"for(var i=0;i<s.length;i++){var b=document.querySelector(s[i]);if(b){b.click();break;}}" +
				"})();";
		loadUrl(js);
	}

	FutureSupplier<Long> getDuration() {
		return getMilliseconds("duration");
	}

	FutureSupplier<Long> getPosition() {
		return getMilliseconds("currentTime");
	}

	FutureSupplier<String> getVideoQualities() {
		Promise<String> p = js.getResultPromise();
		loadUrl("javascript:\n" +
				"function retryGetVideoQualities(attempt, openMenu) {\n" +
				"  if (attempt < 10) setTimeout(getVideoQualities, 100, attempt + 1, openMenu);\n" +
				"  else " + JS_EVENT + '(' + JS_VIDEO_QUALITIES + ", null);\n" +
				"  return null;\n" +
				"}\n" +
				"function getVideoQualities(attempt, openMenu) {\n" +
				"  if (openMenu) {\n" +
				"    var b = document.querySelector('.player-settings-icon');\n" +
				"    if (b == null) return retryGetVideoQualities(attempt, true);\n" +
				"    b.click();\n" +
				"  }\n" +
				"  var settings = document.querySelector('.player-quality-settings');\n" +
				"  if (settings == null) return retryGetVideoQualities(attempt, false);\n" +
				"  var select = settings.querySelector('.select');\n" +
				"  if (select == null) return retryGetVideoQualities(attempt, false);\n" +
				"  var options = select.querySelectorAll('.option');\n" +
				"  var result = '';\n" +
				"  for (let i = 0; i < options.length; i++) {\n" +
				"    if (i != 0) result += ';';\n" +
				"    if (i == select.selectedIndex) result += '*';\n" +
				"    result += options[i].innerText;\n" +
				"  }\n" +
				"  " + JS_EVENT + '(' + JS_VIDEO_QUALITIES + ", result);\n" +
				"  setTimeout(()=> {settings.parentNode.parentNode.querySelector('" +
				".c3-material-button-button').click();}, 100);\n" +
				"  return result;\n" +
				"}\n" +
				"getVideoQualities(0, true);");
		return p;
	}

	void setVideoQuality(int idx) {
		loadUrl("javascript:\n" +
				"function retrySetVideoQuality(idx, attempt, openMenu) {\n" +
				"  if (attempt < 10) setTimeout(setVideoQuality, 100, idx, attempt + 1, openMenu);\n" +
				"  return false;\n" +
				"}\n" +
				"function setVideoQuality(idx, attempt, openMenu) {\n" +
				"  if (openMenu) {\n" +
				"    var b = document.querySelector('.player-settings-icon');\n" +
				"    if (b == null) return retrySetVideoQuality(idx, attempt, true);\n" +
				"    b.click();\n" +
				"  }\n" +
				"  var settings = document.querySelector('.player-quality-settings');\n" +
				"  if (settings == null) return retrySetVideoQuality(idx, attempt, false);\n" +
				"  var select = settings.querySelector('.select');\n" +
				"  if (select == null) return retrySetVideoQuality(idx, attempt, false);\n" +
				"  var options = select.querySelectorAll('.option');\n" +
				"  var evt = document.createEvent(\"HTMLEvents\");\n" +
				"  evt.initEvent(\"change\", true, true);\n" +
				"  select.selectedIndex = idx;\n" +
				"  options[idx].selected = true;\n" +
				"  select.dispatchEvent(evt);\n" +
				"  setTimeout(()=> {settings.parentNode.parentNode.querySelector('" +
				".c3-material-button-button').click();}, 100);\n" +
				"  return true;\n" +
				"}\n" +
				"setVideoQuality(" + idx + ", 0, true);");
	}

	void setHighestVideoQuality() {
		loadUrl("javascript:\n" +
				"(function() {\n" +
				CLEAR_HIGHEST_VIDEO_QUALITY_JS +
				"  clearFermataQ();\n" +
				"  var state = window.__fermataQ = { player: null, handler: null, timeout: null, attempts: 0 };\n" +
				"  function getPlayer() {\n" +
				"    return document.querySelector('#movie_player') || document.querySelector('.html5-video-player');\n" +
				"  }\n" +
				"  function applyHighest(p) {\n" +
				"    if (!p || typeof p.getAvailableQualityLevels !== 'function') return false;\n" +
				"    var levels = p.getAvailableQualityLevels();\n" +
				"    if (!levels || levels.length === 0) return false;\n" +
				"    var best = null;\n" +
				"    for (var i = 0; i < levels.length; i++) {\n" +
				"      if (levels[i] !== 'auto') { best = levels[i]; break; }\n" +
				"    }\n" +
				"    if (!best) return false;\n" +
				"    if (p.getPlaybackQuality && p.getPlaybackQuality() === best) return true;\n" +
				"    if (typeof p.setPlaybackQualityRange === 'function') p.setPlaybackQualityRange(best, best);\n" +
				"    else if (typeof p.setPlaybackQuality === 'function') p.setPlaybackQuality(best);\n" +
				"    else return false;\n" +
				"    return true;\n" +
				"  }\n" +
				"  function install() {\n" +
				"    var p = getPlayer();\n" +
				"    if (!p || typeof p.addEventListener !== 'function') {\n" +
				"      if (++state.attempts < 50) state.timeout = setTimeout(install, 200);\n" +
				"      return;\n" +
				"    }\n" +
				"    state.player = p;\n" +
				"    state.handler = function(s) {\n" +
				"      if ((s === 1) || (s === 3)) applyHighest(getPlayer() || p);\n" +
				"    };\n" +
				"    p.addEventListener('onStateChange', state.handler);\n" +
				"    applyHighest(p);\n" +
				"  }\n" +
				"  install();\n" +
				"})();");
	}

	void clearHighestVideoQuality() {
		loadUrl("javascript:\n" +
				"(function() {\n" +
				CLEAR_HIGHEST_VIDEO_QUALITY_JS +
				"  clearFermataQ();\n" +
				"})();");
	}

	private FutureSupplier<Long> getMilliseconds(String value) {
		Promise<Long> p = new Promise<>();
		evaluateJavascript(
				"(function(){var v = document.querySelector('video'); return (v != null) ? v." + value +
						" : 0})();",
				v -> {
					try {
						p.complete((long) (Double.parseDouble(v) * 1000));
					} catch (NumberFormatException ex) {
						Log.d(ex);
						p.complete(0L);
					}
				});
		return p;
	}

	void setPosition(long position) {
		double pos = position / 1000f;
		loadUrl("javascript:var v = document.querySelector('video'); if (v != null) v.currentTime = " +
				pos + ";");
	}

	FutureSupplier<Float> getSpeed() {
		Promise<Float> p = new Promise<>();
		evaluateJavascript(
				"(function(){var v = document.querySelector('video'); return (v != null) ? v" +
						".playbackRate" +
						" " +
						": 0})();",
				v -> {
					try {
						p.complete(Float.parseFloat(v));
					} catch (NumberFormatException ex) {
						Log.d(ex);
						p.complete(1f);
					}
				});
		return p;
	}

	void setSpeed(float speed) {
		loadUrl("javascript:var v = document.querySelector('video'); if (v != null) v.playbackRate =" +
				" " +
				speed + ";");
	}

	FutureSupplier<String> getVideoTitle() {
		return getJsString("(function(){" +
				"var p=document.querySelector('#movie_player')||document.querySelector('.html5-video-player');" +
				"try{if(p&&typeof p.getVideoData==='function'){var d=p.getVideoData();if(d&&d.title)return d.title;}}catch(e){}" +
				"var t=(document.title||'').trim();" +
				"return t.endsWith(' - YouTube')?t.slice(0,-10):t;" +
				"})()");
	}

	FutureSupplier<String> getVideoThumbnail() {
		return getJsString("(function(){" +
				"var p=document.querySelector('#movie_player')||document.querySelector('.html5-video-player');" +
				"try{if(p&&typeof p.getVideoData==='function'){var d=p.getVideoData();if(d&&d.video_id)return 'https://i.ytimg.com/vi/'+d.video_id+'/hqdefault.jpg';}}catch(e){}" +
				"var m=document.querySelector('meta[property=\\\"og:image\\\"]');" +
				"if(m&&m.content)return m.content;" +
				"var id=(new URL(location.href)).searchParams.get('v');" +
				"return id?('https://i.ytimg.com/vi/'+id+'/hqdefault.jpg'):'';" +
				"})()");
	}

	FutureSupplier<String> getVideoAuthor() {
		return getJsString("(function(){" +
				"var p=document.querySelector('#movie_player')||document.querySelector('.html5-video-player');" +
				"try{if(p&&typeof p.getVideoData==='function'){var d=p.getVideoData();if(d&&d.author)return d.author;}}catch(e){}" +
				"var c=document.querySelector('.slim-video-information-channel-name');" +
				"if(c){var t=(c.innerText||c.textContent||'').trim();if(t)return t;}" +
				"var owner=document.querySelector('a.slim-video-owner-icon');" +
				"if(owner){var label=owner.getAttribute('aria-label')||'';if(label)return label.split(',')[0].trim();}" +
				"var m=document.querySelector('meta[itemprop=\\\"author\\\"]');" +
				"if(m&&m.content)return m.content;" +
				"return '';" +
				"})()");
	}

	private FutureSupplier<String> getJsString(String expression) {
		Promise<String> p = new Promise<>();
		evaluateJavascript(expression, value -> {
			try {
				if ((value == null) || "null".equals(value)) {
					p.complete("");
					return;
				}
				JSONArray a = new JSONArray("[" + value + "]");
				p.complete(a.optString(0, ""));
			} catch (Exception ex) {
				Log.d(ex, "Failed to decode JavaScript string: ", value);
				p.complete(value);
			}
		});
		return p;
	}

	void setScale(YoutubeAddon.VideoScale scale) {
		getAddon().setScale(scale);
		String p = scale.prefName();
		loadUrl("javascript:" +
				"document.querySelectorAll('video')" +
				".forEach(v=> v.setAttribute('style', 'object-fit:" + p + "'));");
	}
}
