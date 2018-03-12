/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.tabs;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.mozilla.focus.persistence.TabModel;
import org.mozilla.focus.web.DownloadCallback;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Class to help on tabs management, such as adding or removing tabs.
 */
public class TabsSession {

    /**
     * Context for this session. In current intention, a session belongs to an Activity but not just a Context.
     */
    private Activity activity;

    private List<Tab> tabs = new LinkedList<>();

    private Notifier notifier;

    /**
     * Index to refer a tab which is 'focused' by this session. When this index be changed, session
     * should hoist focused tab as well.
     */
    private int focusIdx = -1;

    private List<TabsViewListener> tabsViewListeners = new ArrayList<>();
    private List<TabsChromeListener> tabsChromeListeners = new ArrayList<>();
    private DownloadCallback downloadCallback;

    public TabsSession(@NonNull Activity activity) {
        this.activity = activity;

        this.notifier = new Notifier(activity, this.tabsChromeListeners);
    }

    /**
     * To get count of tabs in this session.
     *
     * @return count in integer
     */
    public int getTabsCount() {
        return tabs.size();
    }

    /**
     * Copy reference of tabs which are held by this session.
     *
     * @return new List which is safe to change its order without effect this session
     */
    public List<Tab> getTabs() {
        // create a new list, in case of caller modify this list
        final List<Tab> refs = new ArrayList<>(tabs);
        return refs;
    }

    /**
     * To append tabs from a list of TabModel. If tabs is empty before this call, the first appended
     * tab will be hoisted, otherwise no tab will be hoisted.
     * <p>
     * This is asynchronous call.
     * TODO: make it asynchronous
     *
     * @param models
     */
    public void restoreTabs(@NonNull final List<TabModel> models, String focusTabId) {
        for (final TabModel model : models) {
            final Tab tab = new Tab(model);
            bindCallback(tab);
            tabs.add(tab);
        }

        if (tabs.size() > 0 && tabs.size() == models.size()) {
            int index = getTabIndex(focusTabId);
            focusIdx = (index != -1) ? index : 0;
        }

        for (final TabsChromeListener l : tabsChromeListeners) {
            l.onTabCountChanged(tabs.size());
        }
    }

    /**
     * To get data of tabs to store in persistent storage.
     *
     * @return created TabModel of tabs in this session.
     */
    public List<TabModel> getTabModelListForPersistence() {
        final List<TabModel> models = new ArrayList<>();
        for (final Tab tab : tabs) {
            models.add(tab.getSaveModel());
        }
        return models;
    }

    /**
     * Add a tab to a specific parent tab.
     *
     * @param parentId     id of parent tab. If it is null, the tab will be append to tail
     * @param url          initial url for this tab
     * @param fromExternal is this request from external app
     * @param hoist        true to hoist this tab after creation
     * @return id for created tab
     */
    @Nullable
    public String addTab(@Nullable final String parentId,
                         @NonNull final String url,
                         boolean fromExternal,
                         boolean hoist) {

        if (TextUtils.isEmpty(url)) {
            return null;
        }

        return addTabInternal(parentId, url, fromExternal, hoist);
    }

    /**
     * To remove a tab from list.
     *
     * @param id the id of tab to be removed.
     */
    public void removeTab(final String id) {
        final int idx = getTabIndex(id);
        final Tab tab = tabs.get(idx);
        if (tab == null) {
            return;
        }

        tabs.remove(idx);
        tab.destroy();

        // removed one tab, now idx should refer to next one
        focusIdx = idx >= tabs.size() ? tabs.size() - 1 : idx;
        if (hasTabs()) {
            hoistTab(tabs.get(focusIdx));
        }

        for (final TabsChromeListener l : tabsChromeListeners) {
            l.onTabCountChanged(tabs.size());
        }
    }

    /**
     * To hoist a tab from list.
     *
     * @param id the id of tab to be hoisted.
     */
    public void switchToTab(final String id) {
        final int idx = getTabIndex(id);
        if (idx < 0 || idx > tabs.size() - 1) {
            return;
        }

        focusIdx = idx;
        hoistTab(tabs.get(focusIdx));
    }

    /**
     * To check whether this session has any tabs
     *
     * @return true if this session has at least one tab
     */
    public boolean hasTabs() {
        return tabs.size() > 0;
    }

    /**
     * To get current focused tab.
     *
     * @return current focused tab. Return null if there is not any tab.
     */
    public Tab getFocusTab() {
        return (focusIdx >= 0 && focusIdx < tabs.size()) ? tabs.get(focusIdx) : null;
    }

    /**
     * To add @see{TabsViewListener} to this session.
     *
     * @param listener
     */
    public void addTabsViewListener(@NonNull TabsViewListener listener) {
        if (!this.tabsViewListeners.contains(listener)) {
            this.tabsViewListeners.add(listener);
        }
    }

    /**
     * To add @see{TabsChromeListener} to this session.
     *
     * @param listener
     */
    public void addTabsChromeListener(@Nullable TabsChromeListener listener) {
        if (!this.tabsChromeListeners.contains(listener)) {
            this.tabsChromeListeners.add(listener);
        }
    }

    /**
     * To remove @see{TabsViewListener} from this session.
     *
     * @param listener
     */
    public void removeTabsViewListener(@NonNull TabsViewListener listener) {
        this.tabsViewListeners.remove(listener);
    }

    /**
     * To remove @see{TabsChromeListener} from this session.
     *
     * @param listener
     */
    public void removeTabsChromeListener(@NonNull TabsChromeListener listener) {
        this.tabsChromeListeners.remove(listener);
    }

    /**
     * To specify @see{DownloadCallback} to this session, this method will replace existing one. It
     * also replace DownloadCallback from any existing Tab.
     *
     * @param downloadCallback
     */
    public void setDownloadCallback(@Nullable DownloadCallback downloadCallback) {
        this.downloadCallback = downloadCallback;
        if (hasTabs()) {
            for (final Tab tab : tabs) {
                tab.setDownloadCallback(downloadCallback);
            }
        }
    }

    /**
     * To destroy this session, and it also destroy any tabs in this session.
     * This method should be called after any View has been removed from view system.
     * No other methods may be called on this session after destroy.
     */
    public void destroy() {
        for (final Tab tab : tabs) {
            tab.destroy();
        }
    }

    /**
     * To pause this session, and it also pause any tabs in this session.
     */
    public void pause() {
        for (final Tab tab : tabs) {
            tab.pause();
        }
    }

    /**
     * To resume this session after a previous call to @see{#pause}
     */
    public void resume() {
        for (final Tab tab : tabs) {
            tab.resume();
        }
    }

    private void bindCallback(@NonNull Tab tab) {
        tab.setTabViewClient(new TabViewClientImpl(tab));
        tab.setTabChromeClient(new TabChromeClientImpl(tab));
        tab.setDownloadCallback(downloadCallback);
    }

    private String addTabInternal(@Nullable final String parentId,
                                  @Nullable final String url,
                                  boolean fromExternal,
                                  boolean hoist) {

        final Tab tab = new Tab();

        bindCallback(tab);

        final int parentIndex = (TextUtils.isEmpty(parentId)) ? -1 : getTabIndex(parentId);
        if (fromExternal) {
            tab.setParentId(Tab.ID_EXTERNAL);
            tabs.add(tab);
        } else {
            insertTab(parentIndex, tab);
        }

        focusIdx = (hoist || fromExternal) ? getTabIndex(tab.getId()) : focusIdx;

        if (!TextUtils.isEmpty(url)) {
            tab.createView(activity).loadUrl(url);
        }

        if (hoist || fromExternal) {
            hoistTab(tab);
        }

        for (final TabsChromeListener l : tabsChromeListeners) {
            l.onTabCountChanged(tabs.size());
        }
        return tab.getId();
    }

    private Tab getTab(final @NonNull String id) {
        final int index = getTabIndex(id);
        return index == -1 ? null : tabs.get(index);
    }

    private int getTabIndex(final @NonNull String id) {
        for (int i = 0; i < tabs.size(); i++) {
            final Tab tab = tabs.get(i);
            if (tab.getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void insertTab(final int parentIdx, @NonNull final Tab tab) {
        final Tab parentTab = (parentIdx >= 0 && parentIdx < tabs.size() - 1)
                ? tabs.get(parentIdx)
                : null;
        if (parentTab == null) {
            tabs.add(tab);
            return;
        } else {
            tabs.add(parentIdx + 1, tab);
        }

        // TODO: in our current design, the parent of a tab are always locate at left(index -1).
        //       hence no need to loop whole list.
        // if the parent-tab has a child, give it a new parent
        for (final Tab t : tabs) {
            if (parentTab.getId().equals(t.getParentId())) {
                t.setParentId(tab.getId());
            }
        }

        // update family relationship
        tab.setParentId(parentTab.getId());
    }

    private void hoistTab(final Tab tab) {
        final Message msg = notifier.obtainMessage(Notifier.MSG_HOIST_TAB);
        msg.obj = tab;
        notifier.sendMessage(msg);
    }

    class TabViewClientImpl extends TabViewClient {
        @NonNull
        Tab source;

        TabViewClientImpl(@NonNull Tab source) {
            this.source = source;
        }

        @Override
        public void onPageStarted(String url) {
            source.setUrl(url);
            source.setTitle(source.getTabView().getTitle());

            // FIXME: workaround for 'dialog new window'
            if (source.getUrl() != null) {
                for (final TabsViewListener l : tabsViewListeners) {
                    l.onTabStarted(source);
                }
            }
        }

        @Override
        public void onPageFinished(boolean isSecure) {
            source.setTitle(source.getTabView().getTitle());

            for (final TabsViewListener l : tabsViewListeners) {
                l.onTabFinished(source, isSecure);
            }
        }

        @Override
        public void onURLChanged(String url) {
            source.setUrl(url);
            source.setTitle(source.getTabView().getTitle());

            for (final TabsViewListener l : tabsViewListeners) {
                l.onURLChanged(source, url);
            }
        }

        @Override
        public boolean handleExternalUrl(String url) {
            // only return false if none of listeners handled external url.
            for (final TabsViewListener l : tabsViewListeners) {
                if (l.handleExternalUrl(url)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void updateFailingUrl(String url, boolean updateFromError) {
            for (final TabsViewListener l : tabsViewListeners) {
                l.updateFailingUrl(source, url, updateFromError);
            }
        }
    }


    private class TabChromeClientImpl extends TabChromeClient {
        @NonNull
        Tab source;

        TabChromeClientImpl(@NonNull Tab source) {
            this.source = source;
        }

        @Override
        public boolean onCreateWindow(boolean isDialog, boolean isUserGesture, Message msg) {
            if (msg == null) {
                return false;
            }

            final String id = addTabInternal(source.getId(), null, false, false);
            final Tab tab = getTab(id);
            if (tab == null) {
                // FIXME: why null?
                return false;
            }

            final WebView webView = (WebView) tab.getTabView();
            final WebView.WebViewTransport transport = (WebView.WebViewTransport) msg.obj;
            transport.setWebView(webView);
            msg.sendToTarget();

            for (final TabsChromeListener l : tabsChromeListeners) {
                l.onTabHoist(tab);
            }
            return true;
        }

        @Override
        public void onCloseWindow(WebView webView) {
            if (source.getTabView() == webView) {
                for (int i = 0; i < tabs.size(); i++) {
                    final Tab tab = tabs.get(i);
                    if (tab.getTabView() == webView) {
                        removeTab(tab.getId());
                    }
                }
            }
        }

        @Override
        public void onProgressChanged(int progress) {
            for (final TabsChromeListener l : tabsChromeListeners) {
                l.onProgressChanged(source, progress);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
            for (final TabsChromeListener l : tabsChromeListeners) {
                if (l.onShowFileChooser(source, webView, filePathCallback, fileChooserParams)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            for (final TabsChromeListener l : tabsChromeListeners) {
                l.onReceivedTitle(source, title);
            }
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            source.setFavicon(icon);
            for (final TabsChromeListener l : tabsChromeListeners) {
                l.onReceivedIcon(source, icon);
            }
        }

        @Override
        public void onLongPress(TabView.HitTarget hitTarget) {
            for (final TabsChromeListener l : tabsChromeListeners) {
                l.onLongPress(source, hitTarget);
            }
        }

        @Override
        public void onEnterFullScreen(@NonNull TabView.FullscreenCallback callback, @Nullable View view) {
            for (final TabsChromeListener l : tabsChromeListeners) {
                l.onEnterFullScreen(source, callback, view);
            }
        }

        @Override
        public void onExitFullScreen() {
            for (final TabsChromeListener l : tabsChromeListeners) {
                l.onExitFullScreen(source);
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            for (final TabsChromeListener l : tabsChromeListeners) {
                l.onGeolocationPermissionsShowPrompt(source, origin, callback);
            }
        }
    }

    /**
     * A class to attach to UI thread for sending message.
     */
    private static class Notifier extends Handler {
        static final int MSG_HOIST_TAB = 0x1001;

        private Activity activity;
        private List<TabsChromeListener> chromeListeners = null;

        Notifier(@NonNull final Activity activity,
                 @NonNull final List<TabsChromeListener> listeners) {

            super(Looper.getMainLooper());
            this.activity = activity;
            this.chromeListeners = listeners;
        }

        @Override
        public void handleMessage(final Message msg) {
            switch (msg.what) {
                case MSG_HOIST_TAB:
                    hoistTab((Tab) msg.obj);
                    break;
                default:
                    break;
            }
        }

        private void hoistTab(final Tab tab) {
            if (tab != null && tab.getTabView() == null) {
                String url = tab.getUrl();
                tab.createView(this.activity).loadUrl(url);
            }

            for (final TabsChromeListener l : this.chromeListeners) {
                l.onTabHoist(tab);
            }
        }
    }
}
