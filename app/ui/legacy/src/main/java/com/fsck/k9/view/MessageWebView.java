package com.fsck.k9.view;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.AttributeSet;
import timber.log.Timber;

import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebSettings.LayoutAlgorithm;
import android.webkit.WebSettings.RenderPriority;
import android.webkit.WebView;
import android.widget.Toast;

import com.fsck.k9.ui.R;
import com.fsck.k9.mailstore.AttachmentResolver;


public class MessageWebView extends WebView {

    public MessageWebView(Context context) {
        super(context);
    }

    public MessageWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MessageWebView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Configure a web view to load or not load network data. A <b>true</b> setting here means that
     * network data will be blocked.
     * @param shouldBlockNetworkData True if network data should be blocked, false to allow network data.
     */
    public void blockNetworkData(final boolean shouldBlockNetworkData) {
        /*
         * Block network loads.
         *
         * Images with content: URIs will not be blocked, nor
         * will network images that are already in the WebView cache.
         *
         */
        getSettings().setBlockNetworkLoads(shouldBlockNetworkData);
    }


    /**
     * Configure a {@link WebView} to display a Message. This method takes into account a user's
     * preferences when configuring the view. This message is used to view a message and to display a message being
     * replied to.
     */
    @SuppressLint("SetJavaScriptEnabled")
    public void configure(WebViewConfig config) {
        this.setVerticalScrollBarEnabled(true);
        this.setVerticalScrollbarOverlay(true);
        this.setScrollBarStyle(SCROLLBARS_INSIDE_OVERLAY);
        this.setLongClickable(true);

        if (config.getUseDarkMode()) {
            // Black theme should get a black webview background
            // we'll set the background of the messages on load
            this.setBackgroundColor(0xff000000);
        }

        final WebSettings webSettings = this.getSettings();

        /* TODO this might improve rendering smoothness when webview is animated into view
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            webSettings.setOffscreenPreRaster(true);
        }
        */
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            webSettings.setOffscreenPreRaster(true);
        }

        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setUseWideViewPort(true);
        if (config.getAutoFitWidth()) {
            webSettings.setLoadWithOverviewMode(true);
        }

        disableDisplayZoomControls();

        webSettings.setJavaScriptEnabled(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setRenderPriority(RenderPriority.HIGH);

        // TODO:  Review alternatives.  NARROW_COLUMNS is deprecated on KITKAT
        webSettings.setLayoutAlgorithm(LayoutAlgorithm.NARROW_COLUMNS);

        setOverScrollMode(OVER_SCROLL_NEVER);

        webSettings.setTextZoom(config.getTextZoom());

        // Disable network images by default.  This is overridden by preferences.
        // 直接显示图片
        blockNetworkData(false);
    }

    /**
     * Disable on-screen zoom controls on devices that support zooming via pinch-to-zoom.
     */
    private void disableDisplayZoomControls() {
        PackageManager pm = getContext().getPackageManager();
        boolean supportsMultiTouch =
                pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH) ||
                pm.hasSystemFeature(PackageManager.FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT);

        getSettings().setDisplayZoomControls(!supportsMultiTouch);
    }

    public void displayHtmlContentWithInlineAttachments(@NonNull String htmlText,
            @Nullable AttachmentResolver attachmentResolver, @Nullable OnPageFinishedListener onPageFinishedListener) {
        setWebViewClient(attachmentResolver, new WebViewOnPageFinishedListener(onPageFinishedListener));
        setHtmlContent(htmlText);
    }

    private class WebViewOnPageFinishedListener implements OnPageFinishedListener {

        private final OnPageFinishedListener mOnPageFinishedListener;

        public WebViewOnPageFinishedListener(OnPageFinishedListener mOnPageFinishedListener) {
            this.mOnPageFinishedListener = mOnPageFinishedListener;
        }

        @Override
        public void onPageFinished() {
            if (mOnPageFinishedListener != null) {
                mOnPageFinishedListener.onPageFinished();
            }

            loadJS();
        }
    }

    private void loadJS(){
        String js = "javascript:(function(){"
                // 将DIV元素中的外边距和内边距设置为零，防止网页左右有空隙
                + " var divs = document.getElementsByTagName(\"div\");"
                + " for(var j=0;j<divs.length;j++){"
                + "     if(divs[j].parentElement.nodeName != \"BODY\"){"
                + "       divs[j].style.margin=\"0px\";"
                + "       divs[j].style.padding=\"0px\";"
//                + "   divs[j].style.width=document.body.clientWidth-10;"
                + "       divs[j].style.width=\"fit-content\";"
                + "     }"
                + " }"

                + " var objs = document.getElementsByTagName(\"img\"); "
                + " for(var i=0;i<objs.length;i++){"
                // 过滤掉GIF图片，防止过度放大后，GIF失真
                + "     var vkeyWords=/.gif$/;"
                + "     if(!vkeyWords.test(objs[i].src)){"
                + "         var hRatio=" + getScreenWidthDP() + "/objs[i].width;"
                + "         objs[i].height= objs[i].height*hRatio;" // 通过缩放比例来设置图片的高度
                + "         objs[i].width=" + getScreenWidthDP() + ";" // 设置图片的宽度
                + "     }"
                + "  }"
                + "})()";
        loadUrl(js);

    }

    /**
     * 获取屏幕的宽度（单位：像素PX）
     */
    private int getScreenWidthPX(){
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null && wm.getDefaultDisplay() != null){
            wm.getDefaultDisplay().getMetrics(dm);
            return dm.widthPixels;
        }else {
            return 0;
        }
    }

    /**
     * 获取屏幕的宽度（单位：dp）
     */
    private int getScreenWidthDP(){
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null && wm.getDefaultDisplay() != null){
            wm.getDefaultDisplay().getMetrics(dm);
            return px2dip(dm.widthPixels);
        }else {
            return 0;
        }
    }

    /**
     * 像素转DP
     */
    public  int px2dip(float pxValue) {
        float scale = this.getResources().getDisplayMetrics().density;
        return (int) (pxValue / scale + 0.5f);
    }

    private void setWebViewClient(@Nullable AttachmentResolver attachmentResolver,
            @Nullable OnPageFinishedListener onPageFinishedListener) {
        K9WebViewClient webViewClient = K9WebViewClient.newInstance(attachmentResolver);
        if (onPageFinishedListener != null) {
            webViewClient.setOnPageFinishedListener(onPageFinishedListener);
        }
        setWebViewClient(webViewClient);
    }

    private void setHtmlContent(@NonNull String htmlText) {
        loadDataWithBaseURL("about:blank", htmlText, "text/html", "utf-8", null);
        resumeTimers();
    }

    /*
     * Emulate the shift key being pressed to trigger the text selection mode
     * of a WebView.
     */
    public void emulateShiftHeld() {
        try {

            KeyEvent shiftPressEvent = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                                                    KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0);
            shiftPressEvent.dispatch(this, null, null);
            Toast.makeText(getContext() , R.string.select_text_now, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Timber.e(e, "Exception in emulateShiftHeld()");
        }
    }

    public interface OnPageFinishedListener {
        void onPageFinished();
    }
}
