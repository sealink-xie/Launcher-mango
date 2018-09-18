/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.launcher.mango.model;

import android.os.Looper;
import android.util.Log;

import com.launcher.mango.AllAppsList;
import com.launcher.mango.AppInfo;
import com.launcher.mango.InvariantDeviceProfile;
import com.launcher.mango.ItemInfo;
import com.launcher.mango.LauncherAppState;
import com.launcher.mango.LauncherAppWidgetInfo;
import com.launcher.mango.LauncherModel.Callbacks;
import com.launcher.mango.LauncherModelDelegate;
import com.launcher.mango.LauncherSettings;
import com.launcher.mango.MainThreadExecutor;
import com.launcher.mango.PagedView;
import com.launcher.mango.Utilities;
import com.launcher.mango.config.FeatureFlags;
import com.launcher.mango.util.ComponentKey;
import com.launcher.mango.util.LooperIdleLock;
import com.launcher.mango.util.MultiHashMap;
import com.launcher.mango.util.ViewOnDrawExecutor;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executor;

import io.reactivex.annotations.NonNull;

/**
 * Helper class to handle results of {@link LoaderTask}.
 *
 * @author tic
 */
public class LoaderResults {

    private static final String TAG = "LoaderResults";
    private static final long INVALID_SCREEN_ID = -1L;
    private static final int ITEMS_CHUNK = 6; // batch size for the workspace icons

    private final Executor mUiExecutor;

    private final LauncherAppState mApp;
    private final BgDataModel mBgDataModel;
    private final AllAppsList mBgAllAppsList;
    private final int mPageToBindFirst;

    private final WeakReference<Callbacks> mCallbacks;

    public LoaderResults(LauncherAppState app, BgDataModel dataModel,
                         AllAppsList allAppsList, int pageToBindFirst, WeakReference<Callbacks> callbacks) {
        mUiExecutor = new MainThreadExecutor();
        mApp = app;
        mBgDataModel = dataModel;
        mBgAllAppsList = allAppsList;
        mPageToBindFirst = pageToBindFirst;
        mCallbacks = callbacks == null ? new WeakReference<>(null) : callbacks;
    }

    /**
     * Binds all loaded data to actual views on the main thread.
     */
    public void bindWorkspace() {
        // Save a copy of all the bg-thread collections
        ArrayList<ItemInfo> workspaceItems;
        ArrayList<LauncherAppWidgetInfo> appWidgets;
        final ArrayList<Long> orderedScreenIds;

        synchronized (mBgDataModel) {
            workspaceItems = new ArrayList<>(mBgDataModel.workspaceItems);
            appWidgets = new ArrayList<>(mBgDataModel.appWidgets);
            orderedScreenIds = new ArrayList<>(mBgDataModel.workspaceScreens);
        }

        bindToWorkspace(workspaceItems, appWidgets, orderedScreenIds);
    }

    public void bindAllAppsToScreen() {
        ArrayList<ItemInfo> added = new ArrayList<>();
        for (AppInfo app : mBgAllAppsList.data) {
            if (!mBgDataModel.existInWorkspace(app.componentName)
                    && !mBgAllAppsList.filter(app.componentName)) {
                added.add(app);
            }
        }

        Log.e(TAG, "add to workspace items:" + added.size());
        if (!added.isEmpty()) {
            // Save a copy of all the bg-thread collections
            ArrayList<LauncherAppWidgetInfo> appWidgets;

            synchronized (mBgDataModel) {
                appWidgets = new ArrayList<>(mBgDataModel.appWidgets);
            }
            ArrayList<Long> screenFinal = new LauncherModelDelegate(mApp.getContext())
                    .sortWorkspaceItemsSpatially(added, mBgDataModel);
            bindToWorkspace(added, appWidgets, screenFinal);
        }
        // all apps added in workspace
    }

    private int getCurrentScreen(Callbacks callbacks, ArrayList<Long> orderedScreenIds) {
        int currScreen = mPageToBindFirst != PagedView.INVALID_RESTORE_PAGE
                ? mPageToBindFirst : callbacks.getCurrentWorkspaceScreen();
        if (currScreen >= orderedScreenIds.size()) {
            // There may be no workspace screens (just hotseat items and an empty page).
            currScreen = PagedView.INVALID_RESTORE_PAGE;
        }
        return currScreen;
    }

    /**
     * Binds all loaded data to actual views on the main thread.
     */
    private void bindToWorkspace(@NonNull ArrayList<ItemInfo> workspaceItems,
                                 @NonNull ArrayList<LauncherAppWidgetInfo> appWidgets,
                                 @NonNull ArrayList<Long> orderedScreenIds) {
        Runnable r;

        Callbacks callbacks = mCallbacks.get();
        // Don't use these two variables in any of the callback runnables.
        // Otherwise we hold a reference to them.
        if (callbacks == null) {
            // This launcher has exited and nobody bothered to tell us.  Just bail.
            Log.w(TAG, "LoaderTask running with no launcher");
            return;
        }

        final int currentScreen = getCurrentScreen(callbacks, orderedScreenIds);
        final boolean validFirstPage = currentScreen >= 0;
        final long currentScreenId =
                validFirstPage ? orderedScreenIds.get(currentScreen) : INVALID_SCREEN_ID;

        // Separate the items that are on the current screen, and all the other remaining items
        ArrayList<ItemInfo> currentWorkspaceItems = new ArrayList<>();
        ArrayList<ItemInfo> otherWorkspaceItems = new ArrayList<>();
        ArrayList<LauncherAppWidgetInfo> currentAppWidgets = new ArrayList<>();
        ArrayList<LauncherAppWidgetInfo> otherAppWidgets = new ArrayList<>();

        filterCurrentWorkspaceItems(currentScreenId, workspaceItems, currentWorkspaceItems,
                otherWorkspaceItems);
        filterCurrentWorkspaceItems(currentScreenId, appWidgets, currentAppWidgets,
                otherAppWidgets);
        sortWorkspaceItemsSpatially(currentWorkspaceItems);
        sortWorkspaceItemsSpatially(otherWorkspaceItems);

        // Tell the workspace that we're about to start binding items
        r = () -> {
            Callbacks callbacks1 = mCallbacks.get();
            if (callbacks1 != null) {
                callbacks1.clearPendingBinds();
                callbacks1.startBinding();
            }
        };
        mUiExecutor.execute(r);

        // Bind workspace screens
        mUiExecutor.execute(() -> {
            Callbacks callbacks15 = mCallbacks.get();
            if (callbacks15 != null) {
                callbacks15.bindScreens(orderedScreenIds);
            }
        });

        Executor mainExecutor = mUiExecutor;
        // Load items on the current page.
        bindWorkspaceItems(currentWorkspaceItems, currentAppWidgets, mainExecutor);

        // In case of validFirstPage, only bind the first screen, and defer binding the
        // remaining screens after first onDraw (and an optional the fade animation whichever
        // happens later).
        // This ensures that the first screen is immediately visible (eg. during rotation)
        // In case of !validFirstPage, bind all pages one after other.
        final Executor deferredExecutor =
                validFirstPage ? new ViewOnDrawExecutor(mUiExecutor) : mainExecutor;

        mainExecutor.execute(() -> {
            Callbacks callbacks14 = mCallbacks.get();
            if (callbacks14 != null) {
                callbacks14.finishFirstPageBind(
                        validFirstPage ? (ViewOnDrawExecutor) deferredExecutor : null);
            }
        });

        bindWorkspaceItems(otherWorkspaceItems, otherAppWidgets, deferredExecutor);

        // Tell the workspace that we're done binding items
        r = () -> {
            Callbacks callbacks12 = mCallbacks.get();
            if (callbacks12 != null) {
                callbacks12.finishBindingItems();
            }
        };
        deferredExecutor.execute(r);

        if (validFirstPage) {
            r = () -> {
                Callbacks callbacks13 = mCallbacks.get();
                if (callbacks13 != null) {
                    // We are loading synchronously, which means, some of the pages will be
                    // bound after first draw. Inform the callbacks that page binding is
                    // not complete, and schedule the remaining pages.
                    if (currentScreen != PagedView.INVALID_RESTORE_PAGE) {
                        callbacks13.onPageBoundSynchronously((int) currentScreen);
                    }
                    callbacks13.executeOnNextDraw((ViewOnDrawExecutor) deferredExecutor);
                }
            };
            mUiExecutor.execute(r);
        }
    }


    /**
     * Filters the set of items who are directly or indirectly (via another container) on the
     * specified screen.
     */
    private <T extends ItemInfo> void filterCurrentWorkspaceItems(long currentScreenId,
                                                                  ArrayList<T> allWorkspaceItems,
                                                                  ArrayList<T> currentScreenItems,
                                                                  ArrayList<T> otherScreenItems) {
        // Purge any null ItemInfos
        Iterator<T> iter = allWorkspaceItems.iterator();
        while (iter.hasNext()) {
            ItemInfo i = iter.next();
            if (i == null) {
                iter.remove();
            }
        }

        // Order the set of items by their containers first, this allows use to walk through the
        // list sequentially, build up a list of containers that are in the specified screen,
        // as well as all items in those containers.
        Set<Long> itemsOnScreen = new HashSet<>();
        Collections.sort(allWorkspaceItems, new Comparator<ItemInfo>() {
            @Override
            public int compare(ItemInfo lhs, ItemInfo rhs) {
                return Utilities.longCompare(lhs.container, rhs.container);
            }
        });
        for (T info : allWorkspaceItems) {
            if (info.container == LauncherSettings.Favorites.CONTAINER_DESKTOP) {
                if (info.screenId == currentScreenId) {
                    currentScreenItems.add(info);
                    itemsOnScreen.add(info.id);
                } else {
                    otherScreenItems.add(info);
                }
            } else if (info.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                currentScreenItems.add(info);
                itemsOnScreen.add(info.id);
            } else {
                if (itemsOnScreen.contains(info.container)) {
                    currentScreenItems.add(info);
                    itemsOnScreen.add(info.id);
                } else {
                    otherScreenItems.add(info);
                }
            }
        }
    }

    /**
     * Sorts the set of items by hotseat, workspace (spatially from top to bottom, left to
     * right)
     */
    private void sortWorkspaceItemsSpatially(ArrayList<ItemInfo> workspaceItems) {
        final InvariantDeviceProfile profile = mApp.getInvariantDeviceProfile();
        final int screenCols = profile.numColumns;
        final int screenCellCount = profile.numColumns * profile.numRows;
        Collections.sort(workspaceItems, new Comparator<ItemInfo>() {
            @Override
            public int compare(ItemInfo lhs, ItemInfo rhs) {
                if (lhs.container == rhs.container) {
                    // Within containers, order by their spatial position in that container
                    switch ((int) lhs.container) {
                        case LauncherSettings.Favorites.CONTAINER_DESKTOP: {
                            long lr = (lhs.screenId * screenCellCount +
                                    lhs.cellY * screenCols + lhs.cellX);
                            long rr = (rhs.screenId * screenCellCount +
                                    rhs.cellY * screenCols + rhs.cellX);
                            return Utilities.longCompare(lr, rr);
                        }
                        case LauncherSettings.Favorites.CONTAINER_HOTSEAT: {
                            // We currently use the screen id as the rank
                            return Utilities.longCompare(lhs.screenId, rhs.screenId);
                        }
                        default:
                            if (FeatureFlags.IS_DOGFOOD_BUILD) {
                                throw new RuntimeException("Unexpected container type when " +
                                        "sorting workspace items.");
                            }
                            return 0;
                    }
                } else {
                    // Between containers, order by hotseat, desktop
                    return Utilities.longCompare(lhs.container, rhs.container);
                }
            }
        });
    }

    private void bindWorkspaceItems(final ArrayList<ItemInfo> workspaceItems,
                                    final ArrayList<LauncherAppWidgetInfo> appWidgets,
                                    final Executor executor) {

        // Bind the workspace items
        int N = workspaceItems.size();
        for (int i = 0; i < N; i += ITEMS_CHUNK) {
            final int start = i;
            final int chunkSize = (i + ITEMS_CHUNK <= N) ? ITEMS_CHUNK : (N - i);
            final Runnable r = () -> {
                Callbacks callbacks = mCallbacks.get();
                if (callbacks != null) {
                    callbacks.bindItems(workspaceItems.subList(start, start + chunkSize), false);
                }
            };
            executor.execute(r);
        }

        // Bind the widgets, one at a time
        N = appWidgets.size();
        for (int i = 0; i < N; i++) {
            final ItemInfo widget = appWidgets.get(i);
            final Runnable r = () -> {
                Callbacks callbacks = mCallbacks.get();
                if (callbacks != null) {
                    callbacks.bindItems(Collections.singletonList(widget), false);
                }
            };
            executor.execute(r);
        }
    }

    public void bindDeepShortcuts() {
        final MultiHashMap<ComponentKey, String> shortcutMapCopy;
        synchronized (mBgDataModel) {
            shortcutMapCopy = mBgDataModel.deepShortcutMap.clone();
        }
        Runnable r = () -> {
            Callbacks callbacks = mCallbacks.get();
            if (callbacks != null) {
                callbacks.bindDeepShortcutMap(shortcutMapCopy);
            }
        };
        mUiExecutor.execute(r);
    }

    public void bindAllApps() {
        // shallow copy
        @SuppressWarnings("unchecked") final ArrayList<AppInfo> list = (ArrayList<AppInfo>) mBgAllAppsList.data.clone();

        Runnable r = () -> {
            Callbacks callbacks = mCallbacks.get();
            if (callbacks != null) {
                callbacks.bindAllApplications(list);
            }
        };
        mUiExecutor.execute(r);
    }

    public void bindWidgets() {
        final MultiHashMap<PackageItemInfo, WidgetItem> widgets
                = mBgDataModel.widgetsModel.getWidgetsMap();
        Runnable r = () -> {
            Callbacks callbacks = mCallbacks.get();
            if (callbacks != null) {
                callbacks.bindAllWidgets(widgets);
            }
        };
        mUiExecutor.execute(r);
    }

    public LooperIdleLock newIdleLock(Object lock) {
        LooperIdleLock idleLock = new LooperIdleLock(lock, Looper.getMainLooper());
        // If we are not binding, there is no reason to wait for idle.
        if (mCallbacks.get() == null) {
            idleLock.queueIdle();
        }
        return idleLock;
    }
}
