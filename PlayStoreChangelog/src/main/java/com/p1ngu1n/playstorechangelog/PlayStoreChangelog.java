/*
 * Copyright (C) 2014  P1nGu1n
 *
 * This file is part of Play Store Changelog.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.p1ngu1n.playstorechangelog;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * The class to be loaded by Xposed.
 */
public class PlayStoreChangelog implements IXposedHookLoadPackage {
    private static final String LOG_TAG = "PSC: ";
    // Preferences and their default values
    private XSharedPreferences prefs;
    public boolean showFullChangelog = true;
    public boolean myAppsDefaultPane = false;
    public boolean autoRefreshInstalled = false;
    public boolean debugging = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!loadPackageParam.packageName.equals("com.android.vending"))
            return;

        prefs = new XSharedPreferences(BuildConfig.APPLICATION_ID);
        refreshPreferences();

        Object activityThread = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
        Context context = (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
        PackageInfo piPlayStore = context.getPackageManager().getPackageInfo(loadPackageParam.packageName, 0);
        final int playStoreVersion = piPlayStore.versionCode;

        if (debugging) {
            XposedBridge.log(LOG_TAG + "Play Store Version: " + piPlayStore.versionName + " (" + piPlayStore.versionCode + ")");
            XposedBridge.log(LOG_TAG + "Module version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        }

        final Obfuscator obfsc = new Obfuscator(playStoreVersion);

        /*
         * DetailsTextBlock is the block containing the "What's New" heading and the changelog.
         * The maximum number of lines of the changelog gets set to the number passed as a parameter to the 'bind' method.
         * This mod changes this parameter to the maximum integer value, so the changelog will always be fully shown.
         */
        Class<?> detailsTextBlockClass = XposedHelpers.findClass("com.google.android.finsky.layout.DetailsTextBlock", loadPackageParam.classLoader);
        XposedHelpers.findAndHookMethod(detailsTextBlockClass, obfsc.detailsTextBlockBind, CharSequence.class, CharSequence.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                refreshPreferences();
                if (showFullChangelog) {
                    // 0 = What's new text, 1 = changelog, 2 = maximum changelog lines
                    param.args[2] = Integer.MAX_VALUE;
                }
            }
        });

        /*
         * MainActivity.handleIntent() does exactly what the name says, it handles intents.
         * It checks what to do with the intent, for example open an app's details page.
         *
         * The last check is to load the default pane, it checks if there are any backstack
         * entries. If there are none, it'll show the default pane, but we intercept this
         * and show the My Apps page.
         *
         * We do this by creating temporary fields which we remove afterwards so the other
         * hooked methods can detect whether they were called by this method.
         */
        Class<?> mainActivityClass = XposedHelpers.findClass("com.google.android.finsky.activities.MainActivity", loadPackageParam.classLoader);
        XposedHelpers.findAndHookMethod(mainActivityClass, obfsc.mainActHandleIntent, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                refreshPreferences();
                if (myAppsDefaultPane) {
                    Object mNavigationManager = XposedHelpers.getObjectField(param.thisObject, obfsc.mainActNavManager);
                    XposedHelpers.setAdditionalInstanceField(mNavigationManager, "handlingIntent", true);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object mNavigationManager = XposedHelpers.getObjectField(param.thisObject, obfsc.mainActNavManager);
                XposedHelpers.removeAdditionalInstanceField(mNavigationManager, "handlingIntent");
                XposedHelpers.removeAdditionalInstanceField(mNavigationManager, "calledIsBackStackEmpty");
            }
        });

        /*
         * After we checked whether the method was called from handleIntent() and the result of this method
         * was positive, we create a temporary field to store this so goToAggregatedHome() can detect this.
         */
        Class<?> navigationManagerClass = XposedHelpers.findClass(obfsc.navManager, loadPackageParam.classLoader);
        XposedHelpers.findAndHookMethod(navigationManagerClass, obfsc.navManagerIsBackStackEmpty, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Boolean handlingIntent = (Boolean) XposedHelpers.getAdditionalInstanceField(param.thisObject, "handlingIntent");
                if (handlingIntent != null && handlingIntent && (Boolean) param.getResult()) {
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "calledIsBackStackEmpty", true);
                }
            }
        });

        /*
         * After we checked whether the method was called from handleIntent() and after isBackStackEmpty(),
         * we'll show the My Apps fragment.
         */
        Class<?> dfeTocClass = XposedHelpers.findClass("com.google.android.finsky.api.model.DfeToc", loadPackageParam.classLoader);
        XC_MethodHook navManagerGoToAggregatedHomeHook = new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                Boolean calledIsBackStackEmpty = (Boolean) XposedHelpers.getAdditionalInstanceField(param.thisObject, "calledIsBackStackEmpty");
                if (calledIsBackStackEmpty != null && calledIsBackStackEmpty) {
                    // v5.3.5 and up has an extra parameter, indicating whether all apps should be updated
                    if (playStoreVersion >= 80330500) {
                        XposedHelpers.callMethod(param.thisObject, obfsc.navManagerGoToMyDownloads, param.args[0], false);
                    } else {
                        XposedHelpers.callMethod(param.thisObject, obfsc.navManagerGoToMyDownloads, param.args[0]);
                    }
                } else {
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                }
                return null;
            }
        };

        Object[] navManagerGoToAggregatedHomeParams;
        if (playStoreVersion >= Obfuscator.V6_8_20_F) {
            Class<?> analyticsClass = XposedHelpers.findClass(obfsc.analytics, loadPackageParam.classLoader);
            navManagerGoToAggregatedHomeParams = new Object[] {dfeTocClass, analyticsClass, navManagerGoToAggregatedHomeHook};
        } else {
            navManagerGoToAggregatedHomeParams = new Object[] {dfeTocClass, navManagerGoToAggregatedHomeHook};
        }
        XposedHelpers.findAndHookMethod(navigationManagerClass, obfsc.navManagerGoToAggregatedHome, navManagerGoToAggregatedHomeParams);

        XposedHelpers.findAndHookMethod(obfsc.myAppsTabbedFragment, loadPackageParam.classLoader, obfsc.fragmentOnStart, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object previouslyCalled = XposedHelpers.getAdditionalInstanceField(param.thisObject, "previously_called");
                if (previouslyCalled == null) {
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "previously_called", true);
                    return;
                }

                if (autoRefreshInstalled) {
                    Object myAppsInstalledTab = XposedHelpers.getAdditionalInstanceField(param.thisObject, "my_apps_installed_tab");
                    if (myAppsInstalledTab != null) {
                        XposedHelpers.callMethod(myAppsInstalledTab, obfsc.myAppsTabLoadData);
                    }
                }
            }
        });

        final Class<?> myAppsInstalledTabClass = XposedHelpers.findClass(obfsc.myAppsInstalledTab, loadPackageParam.classLoader);
        XposedHelpers.findAndHookMethod(obfsc.myAppsTabbedAdapter, loadPackageParam.classLoader, obfsc.myAppsTabbedAdapterInstItem, ViewGroup.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object result = param.getResult();
                if (result.getClass() == myAppsInstalledTabClass) {
                    Object fragment = XposedHelpers.getObjectField(param.thisObject, obfsc.myAppsTabbedAdapterFragment);
                    XposedHelpers.setAdditionalInstanceField(fragment, "my_apps_installed_tab", result);
                }
            }
        });
    }

    /**
     * Refresh the preferences.
     */
    private void refreshPreferences() {
        prefs.reload();
        showFullChangelog = prefs.getBoolean("pref_full_changelog", showFullChangelog);
        myAppsDefaultPane = prefs.getBoolean("pref_my_apps_default_pane", myAppsDefaultPane);
        autoRefreshInstalled = prefs.getBoolean("pref_my_apps_auto_refresh", autoRefreshInstalled);
        debugging = prefs.getBoolean("pref_debug", debugging);
    }
}
