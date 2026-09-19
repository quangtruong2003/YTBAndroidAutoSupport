package me.aap.fermata.addon.web;

import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewFeature;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.Completed;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.function.BooleanConsumer;
import me.aap.utils.log.Log;

/**
 * @author Andrey Pavlenko
 */
public class FermataWebClient extends WebViewClientCompat {
	BooleanConsumer loading;

	@Override
	public void onPageStarted(WebView view, String url, Bitmap favicon) {
		if (loading != null) {
			loading.accept(true);
		} else {
			MainActivityDelegate.getActivityDelegate(view.getContext())
					.onSuccess(a -> a.setContentLoading(new Promise<>()));
		}
		super.onPageStarted(view, url, favicon);
	}

	@Override
	public void onPageFinished(WebView view, String url) {
		FermataWebView v = (FermataWebView) view;
		FutureSupplier<MainActivityDelegate> f =
				MainActivityDelegate.getActivityDelegate(v.getContext());
		f.onSuccess(a -> a.setContentLoading(Completed.completedVoid()));

		if (loading != null) {
			loading.accept(false);
			loading = null;
		}

		super.onPageFinished(view, url);
		((FermataWebView) view).hideKeyboard();
		v.pageLoaded(url);
		f.onSuccess(a -> a.fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED));
	}

	@Override
	public boolean shouldOverrideUrlLoading(@NonNull WebView view,
																					@NonNull WebResourceRequest request) {
		Uri uri = request.getUrl();
		String scheme = uri.getScheme();

		if (scheme == null) return false;
		if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
			// Keep YouTube and normal web navigation inside Fermata Web+.
			// Do not hand YouTube links to the native phone app because this S21 FE
			// is also acting as the Android Auto head unit.
			return false;
		}

		if ("intent".equalsIgnoreCase(scheme)) {
			try {
				Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
				String fallback = intent.getStringExtra("browser_fallback_url");
				if ((fallback != null) && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
					view.loadUrl(fallback);
				}
			} catch (Exception ex) {
				Log.d(ex, "Blocked external intent URL: ", uri);
			}
			return true;
		}

		if ("vnd.youtube".equalsIgnoreCase(scheme) || "youtube".equalsIgnoreCase(scheme)) {
			view.loadUrl("https://m.youtube.com/");
			return true;
		}

		// External app schemes (market:, tel:, custom app links, etc.) are blocked
		// in the AA build so Web+ never steals foreground from Open Headunit.
		Log.d("Blocked external URL scheme in Android Auto: ", uri);
		return true;
	}

	public static boolean isYoutubeUri(Uri uri) {
		String host = uri.getHost();
		return ((host != null) && ((host.endsWith("youtube.com") && !host.endsWith("tv.youtube.com")) ||
				host.equals("youtu.be")));
	}

	@Override
	public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request,
															@NonNull WebResourceErrorCompat error) {
		if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) {
			Log.e("Web error received: " + error.getDescription());
		} else {
			Log.e("Web error received");
		}

		super.onReceivedError(view, request, error);
	}
}
