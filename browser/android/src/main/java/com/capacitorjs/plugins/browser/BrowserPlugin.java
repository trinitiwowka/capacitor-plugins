package com.capacitorjs.plugins.browser;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.util.WebColor;

@CapacitorPlugin(name = "Browser")
public class BrowserPlugin extends Plugin {

    private Browser implementation;

    public static BrowserControllerListener browserControllerListener;
    private static BrowserControllerActivity browserControllerActivityInstance;

    public static void setBrowserControllerListener(BrowserControllerListener listener) {
        browserControllerListener = listener;
        if (listener == null) {
            browserControllerActivityInstance = null;
        }
    }

    public void load() {
        implementation = new Browser(getContext());
        implementation.setBrowserEventListener(this::onBrowserEvent);
    }

    @PluginMethod
    public void open(PluginCall call) {
        // get the URL
        String urlString = call.getString("url");
        if (urlString == null) {
            call.reject("Must provide a URL to open");
            return;
        }
        if (urlString.isEmpty()) {
            call.reject("URL must not be empty");
            return;
        }
        Uri url;
        try {
            url = Uri.parse(urlString);
        } catch (Exception ex) {
            call.reject(ex.getLocalizedMessage());
            return;
        }

        // get the toolbar color, if provided
        String colorString = call.getString("toolbarColor");
        Integer toolbarColor = null;
        if (colorString != null) try {
            toolbarColor = WebColor.parseColor(colorString);
        } catch (IllegalArgumentException ex) {
            Logger.error(getLogTag(), "Invalid color provided for toolbarColor. Using default", null);
        }

        // open the browser and finish
        Context launchContext = getActivity();
        if (launchContext == null) {
            launchContext = getContext();
        }

        Intent intent = new Intent(launchContext, BrowserControllerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!(launchContext instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        if (intent.resolveActivity(launchContext.getPackageManager()) == null) {
            Logger.error(getLogTag(), "No Activity found to handle internal browser controller", null);
            if (!launchExternalBrowser(launchContext, url, urlString)) {
                call.reject("No Activity found to handle the Intent for URL: " + urlString);
            } else {
                call.resolve();
            }
            return;
        }

        try {
            launchContext.startActivity(intent);

            Integer finalToolbarColor = toolbarColor;
            Context finalLaunchContext = launchContext;
            String finalUrlString = urlString;
            Uri finalUrl = url;
            setBrowserControllerListener(
                activity -> {
                    try {
                        activity.open(implementation, finalUrl, finalToolbarColor);
                        browserControllerActivityInstance = activity;
                        call.resolve();
                    } catch (ActivityNotFoundException ex) {
                        Logger.warn(getLogTag(), "Custom tabs unavailable, attempting external browser fallback", ex);
                        activity.finish();
                        if (launchExternalBrowser(finalLaunchContext, finalUrl, finalUrlString)) {
                            call.resolve();
                        } else {
                            call.reject("No activity found to handle the URL: " + finalUrlString, ex);
                        }
                    } catch (Exception ex) {
                        Logger.error(getLogTag(), "Error while opening the URL", ex);
                        call.reject("Failed to open URL: " + ex.getMessage(), ex);
                    }
                }
            );
        } catch (ActivityNotFoundException ex) {
            Logger.error(getLogTag(), "No activity found to handle the URL: " + urlString, ex);
            if (!launchExternalBrowser(launchContext, url, urlString)) {
                call.reject("No activity found to handle the URL: " + urlString, ex);
            } else {
                call.resolve();
            }
        } catch (Exception ex) {
            Logger.error(getLogTag(), "Unexpected error occurred", ex);
            call.reject("Unexpected error occurred: " + ex.getMessage(), ex);
        }
    }

    @PluginMethod
    public void close(PluginCall call) {
        if (browserControllerActivityInstance != null) {
            browserControllerActivityInstance = null;
            Intent intent = new Intent(getContext(), BrowserControllerActivity.class);
            intent.putExtra("close", true);
            getContext().startActivity(intent);
        }
        call.resolve();
    }

    @Override
    protected void handleOnResume() {
        if (!implementation.bindService()) {
            Logger.error(getLogTag(), "Error binding to custom tabs service", null);
        }
    }

    @Override
    protected void handleOnPause() {
        implementation.unbindService();
    }

    void onBrowserEvent(int event) {
        switch (event) {
            case Browser.BROWSER_LOADED:
                notifyListeners("browserPageLoaded", null);
                break;
            case Browser.BROWSER_FINISHED:
                notifyListeners("browserFinished", null);
                break;
        }
    }

    private boolean launchExternalBrowser(Context context, Uri url, String urlString) {
        Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, url);
        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!(context instanceof Activity)) {
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        try {
            if (fallbackIntent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(fallbackIntent);
                return true;
            }
            Logger.error(getLogTag(), "No application available to open URL externally: " + urlString, null);
        } catch (ActivityNotFoundException ex) {
            Logger.error(getLogTag(), "Fallback browser activity not found for URL: " + urlString, ex);
        } catch (Exception ex) {
            Logger.error(getLogTag(), "Unexpected error launching fallback browser for URL: " + urlString, ex);
        }

        return false;
    }
}
