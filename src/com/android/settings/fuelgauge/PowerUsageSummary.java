/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.settings.fuelgauge;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.BatteryStats;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceGroup;
import android.support.v14.preference.SwitchPreference;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;

import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatterySipper.DrainType;
import com.android.internal.os.PowerProfile;
import com.android.settings.R;
import com.android.settings.Settings.HighPowerApplicationsActivity;
import com.android.settings.SettingsActivity;
import com.android.settings.applications.ManageApplications;
import com.android.settings.dashboard.SummaryLoader;
import com.android.settingslib.BatteryInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Displays a list of apps and subsystems that consume power, ordered by how much power was
 * consumed since the last time it was unplugged.
 */
public class PowerUsageSummary extends PowerUsageBase {

    public static final String SHOW_PERCENT_SETTING = "status_bar_show_battery_percent";
    private static final boolean DEBUG = false;

    private static final boolean USE_FAKE_DATA = false;

    static final String TAG = "PowerUsageSummary";

    private static final String KEY_APP_LIST = "app_list";
    private static final String KEY_BATTERY_HISTORY = "battery_history";
    private static final String KEY_BATTERY_PCT = "battery_pct";

    private static final int MENU_STATS_TYPE = Menu.FIRST;
    private static final int MENU_HIGH_POWER_APPS = Menu.FIRST + 3;
    private static final int MENU_HELP = Menu.FIRST + 4;

    private static final int MENU_BATTERY_STYLE             = Menu.FIRST + 5;
    private static final int SUBMENU_BATTERY_BAR            = Menu.FIRST + 6;
    private static final int SUBMENU_BATTERY_CIRCLE         = Menu.FIRST + 7;
    private static final int SUBMENU_BATTERY_TEXT           = Menu.FIRST + 8;
    private static final int SUBMENU_BATTERY_HIDDEN         = Menu.FIRST + 9;
    private static final int MENU_BATTERY_PERCENT           = Menu.FIRST + 10;
    private static final int SUBMENU_BATTERY_PERCENT_HIDDEN = Menu.FIRST + 11;
    private static final int SUBMENU_BATTERY_PERCENT_INSIDE = Menu.FIRST + 12;
    private static final int SUBMENU_BATTERY_PERCENT_NEXT   = Menu.FIRST + 13;

    private BatteryHistoryPreference mHistPref;
    private PreferenceGroup mAppListGroup;
    private SwitchPreference mBatteryPct;

    private int mStatsType = BatteryStats.STATS_SINCE_CHARGED;
    private boolean isShowBatteryPct;

    private static final int MIN_POWER_THRESHOLD_MILLI_AMP = 5;
    private static final int MAX_ITEMS_TO_LIST = USE_FAKE_DATA ? 30 : 10;
    private static final int MIN_AVERAGE_POWER_THRESHOLD_MILLI_AMP = 10;
    private static final int SECONDS_IN_HOUR = 60 * 60;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setAnimationAllowed(true);

        addPreferencesFromResource(R.xml.power_usage_summary);
        mHistPref = (BatteryHistoryPreference) findPreference(KEY_BATTERY_HISTORY);
        mAppListGroup = (PreferenceGroup) findPreference(KEY_APP_LIST);
        mBatteryPct = (SwitchPreference) findPreference(KEY_BATTERY_PCT);
        isShowBatteryPct = getResources().getBoolean(R.bool.config_show_battery_percentage);
        if (!isShowBatteryPct) {
            getPreferenceScreen().removePreference(mBatteryPct);
        }
    }

    @Override
    protected int getMetricsCategory() {
        return MetricsEvent.FUELGAUGE_POWER_USAGE_SUMMARY;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isShowBatteryPct) {
            updateBatteryPct();
        }
        refreshStats();
    }

    @Override
    public void onPause() {
        BatteryEntry.stopRequestQueue();
        mHandler.removeMessages(BatteryEntry.MSG_UPDATE_NAME_ICON);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (getActivity().isChangingConfigurations()) {
            BatteryEntry.clearUidCache();
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (!(preference instanceof PowerGaugePreference)) {
            return super.onPreferenceTreeClick(preference);
        }
        PowerGaugePreference pgp = (PowerGaugePreference) preference;
        BatteryEntry entry = pgp.getInfo();
        PowerUsageDetail.startBatteryDetailPage((SettingsActivity) getActivity(), mStatsHelper,
                mStatsType, entry, true, true);
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        int selectedIcon = Settings.System.getInt(getActivity().getContentResolver(),
                                    Settings.System.STATUS_BAR_BATTERY_STYLE, 0);
        int selectedPercentage = Settings.System.getInt(getActivity().getContentResolver(),
                                    Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 0);
        if (DEBUG) {
            menu.add(0, MENU_STATS_TYPE, 0, R.string.menu_stats_total)
                    .setIcon(com.android.internal.R.drawable.ic_menu_info_details)
                    .setAlphabeticShortcut('t');
        }

        SubMenu batteryStyle = menu.addSubMenu(1, MENU_BATTERY_STYLE, 1, R.string.battery_style_title);

        batteryStyle.add(1, SUBMENU_BATTERY_BAR, 1, R.string.battery_style_icon)
                    .setChecked(selectedIcon == 0);
        batteryStyle.add(1, SUBMENU_BATTERY_CIRCLE, 3, R.string.battery_style_circle)
                    .setChecked(selectedIcon == 2);
        batteryStyle.add(1, SUBMENU_BATTERY_TEXT, 4, R.string.battery_style_text)
                    .setChecked(selectedIcon == 5);
        batteryStyle.add(1, SUBMENU_BATTERY_HIDDEN, 5, R.string.battery_style_hidden)
                    .setChecked(selectedIcon == 4);
        batteryStyle.setGroupCheckable(1, true, true);

        MenuItem batteryIcon = batteryStyle.getItem();
        batteryIcon.setIcon(R.drawable.ic_settings_battery_style)
                   .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

        SubMenu batteryPercentMenu = menu.addSubMenu(1, MENU_BATTERY_PERCENT, 1, R.string.battery_percentage_title);

        batteryPercentMenu.add(1, SUBMENU_BATTERY_PERCENT_HIDDEN, 1, R.string.battery_percentage_default)
                    .setChecked(selectedPercentage == 0);
        batteryPercentMenu.add(1, SUBMENU_BATTERY_PERCENT_INSIDE, 2, R.string.battery_percentage_text_inside)
                    .setChecked(selectedPercentage == 1);
        batteryPercentMenu.add(1, SUBMENU_BATTERY_PERCENT_NEXT, 3, R.string.battery_percentage_text_next)
                    .setChecked(selectedPercentage == 2);
        batteryPercentMenu.setGroupCheckable(1, true, true);

        MenuItem batteryPercent = batteryPercentMenu.getItem();
        batteryPercent.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

        menu.add(0, MENU_HIGH_POWER_APPS, 0, R.string.high_power_apps);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    protected int getHelpResource() {
        return R.string.help_url_battery;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final SettingsActivity sa = (SettingsActivity) getActivity();
        switch (item.getItemId()) {
            case MENU_STATS_TYPE:
                if (mStatsType == BatteryStats.STATS_SINCE_CHARGED) {
                    mStatsType = BatteryStats.STATS_SINCE_UNPLUGGED;
                } else {
                    mStatsType = BatteryStats.STATS_SINCE_CHARGED;
                }
                refreshStats();
                return true;
            case MENU_HIGH_POWER_APPS:
                Bundle args = new Bundle();
                args.putString(ManageApplications.EXTRA_CLASSNAME,
                        HighPowerApplicationsActivity.class.getName());
                sa.startPreferencePanel(ManageApplications.class.getName(), args,
                        R.string.high_power_apps, null, null, 0);
                return true;
            case SUBMENU_BATTERY_BAR:
                item.setChecked(true);
                Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.STATUS_BAR_BATTERY_STYLE, 0);
                return true;
            case SUBMENU_BATTERY_CIRCLE:
                item.setChecked(true);
                Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.STATUS_BAR_BATTERY_STYLE, 2);
                return true;
            case SUBMENU_BATTERY_TEXT:
                item.setChecked(true);
                Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.STATUS_BAR_BATTERY_STYLE, 5);
                return true;
            case SUBMENU_BATTERY_HIDDEN:
                item.setChecked(true);
                Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.STATUS_BAR_BATTERY_STYLE, 4);
                return true;
            case SUBMENU_BATTERY_PERCENT_HIDDEN:
                item.setChecked(true);
                Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 0);
                return true;
            case SUBMENU_BATTERY_PERCENT_INSIDE:
                item.setChecked(true);
                Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 1);
                return true;
            case SUBMENU_BATTERY_PERCENT_NEXT:
                item.setChecked(true);
                Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 2);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void addNotAvailableMessage() {
        final String NOT_AVAILABLE = "not_available";
        Preference notAvailable = getCachedPreference(NOT_AVAILABLE);
        if (notAvailable == null) {
            notAvailable = new Preference(getPrefContext());
            notAvailable.setKey(NOT_AVAILABLE);
            notAvailable.setTitle(R.string.power_usage_not_available);
            mAppListGroup.addPreference(notAvailable);
        }
    }

    private static boolean isSharedGid(int uid) {
        return UserHandle.getAppIdFromSharedAppGid(uid) > 0;
    }

    private static boolean isSystemUid(int uid) {
        return uid >= Process.SYSTEM_UID && uid < Process.FIRST_APPLICATION_UID;
    }

    /**
     * We want to coalesce some UIDs. For example, dex2oat runs under a shared gid that
     * exists for all users of the same app. We detect this case and merge the power use
     * for dex2oat to the device OWNER's use of the app.
     * @return A sorted list of apps using power.
     */
    private static List<BatterySipper> getCoalescedUsageList(final List<BatterySipper> sippers) {
        final SparseArray<BatterySipper> uidList = new SparseArray<>();

        final ArrayList<BatterySipper> results = new ArrayList<>();
        final int numSippers = sippers.size();
        for (int i = 0; i < numSippers; i++) {
            BatterySipper sipper = sippers.get(i);
            if (sipper.getUid() > 0) {
                int realUid = sipper.getUid();

                // Check if this UID is a shared GID. If so, we combine it with the OWNER's
                // actual app UID.
                if (isSharedGid(sipper.getUid())) {
                    realUid = UserHandle.getUid(UserHandle.USER_SYSTEM,
                            UserHandle.getAppIdFromSharedAppGid(sipper.getUid()));
                }

                // Check if this UID is a system UID (mediaserver, logd, nfc, drm, etc).
                if (isSystemUid(realUid)
                        && !"mediaserver".equals(sipper.packageWithHighestDrain)) {
                    // Use the system UID for all UIDs running in their own sandbox that
                    // are not apps. We exclude mediaserver because we already are expected to
                    // report that as a separate item.
                    realUid = Process.SYSTEM_UID;
                }

                if (realUid != sipper.getUid()) {
                    // Replace the BatterySipper with a new one with the real UID set.
                    BatterySipper newSipper = new BatterySipper(sipper.drainType,
                            new FakeUid(realUid), 0.0);
                    newSipper.add(sipper);
                    newSipper.packageWithHighestDrain = sipper.packageWithHighestDrain;
                    newSipper.mPackages = sipper.mPackages;
                    sipper = newSipper;
                }

                int index = uidList.indexOfKey(realUid);
                if (index < 0) {
                    // New entry.
                    uidList.put(realUid, sipper);
                } else {
                    // Combine BatterySippers if we already have one with this UID.
                    final BatterySipper existingSipper = uidList.valueAt(index);
                    existingSipper.add(sipper);
                    if (existingSipper.packageWithHighestDrain == null
                            && sipper.packageWithHighestDrain != null) {
                        existingSipper.packageWithHighestDrain = sipper.packageWithHighestDrain;
                    }

                    final int existingPackageLen = existingSipper.mPackages != null ?
                            existingSipper.mPackages.length : 0;
                    final int newPackageLen = sipper.mPackages != null ?
                            sipper.mPackages.length : 0;
                    if (newPackageLen > 0) {
                        String[] newPackages = new String[existingPackageLen + newPackageLen];
                        if (existingPackageLen > 0) {
                            System.arraycopy(existingSipper.mPackages, 0, newPackages, 0,
                                    existingPackageLen);
                        }
                        System.arraycopy(sipper.mPackages, 0, newPackages, existingPackageLen,
                                newPackageLen);
                        existingSipper.mPackages = newPackages;
                    }
                }
            } else {
                results.add(sipper);
            }
        }

        final int numUidSippers = uidList.size();
        for (int i = 0; i < numUidSippers; i++) {
            results.add(uidList.valueAt(i));
        }

        // The sort order must have changed, so re-sort based on total power use.
        Collections.sort(results, new Comparator<BatterySipper>() {
            @Override
            public int compare(BatterySipper a, BatterySipper b) {
                return Double.compare(b.totalPowerMah, a.totalPowerMah);
            }
        });
        return results;
    }

    protected void refreshStats() {
        super.refreshStats();
        updatePreference(mHistPref);
        cacheRemoveAllPrefs(mAppListGroup);
        mAppListGroup.setOrderingAsAdded(false);
        boolean addedSome = false;

        final PowerProfile powerProfile = mStatsHelper.getPowerProfile();
        final BatteryStats stats = mStatsHelper.getStats();
        final double averagePower = powerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);

        TypedValue value = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.colorControlNormal, value, true);
        int colorControl = getContext().getColor(value.resourceId);

        if (averagePower >= MIN_AVERAGE_POWER_THRESHOLD_MILLI_AMP || USE_FAKE_DATA) {
            final List<BatterySipper> usageList = getCoalescedUsageList(
                    USE_FAKE_DATA ? getFakeStats() : mStatsHelper.getUsageList());

            final int dischargeAmount = USE_FAKE_DATA ? 5000
                    : stats != null ? stats.getDischargeAmount(mStatsType) : 0;
            final int numSippers = usageList.size();
            for (int i = 0; i < numSippers; i++) {
                final BatterySipper sipper = usageList.get(i);
                if ((sipper.totalPowerMah * SECONDS_IN_HOUR) < MIN_POWER_THRESHOLD_MILLI_AMP) {
                    continue;
                }
                double totalPower = USE_FAKE_DATA ? 4000 : mStatsHelper.getTotalPower();
                final double percentOfTotal =
                        ((sipper.totalPowerMah / totalPower) * dischargeAmount);
                if (((int) (percentOfTotal + .5)) < 1) {
                    continue;
                }
                if (sipper.drainType == BatterySipper.DrainType.OVERCOUNTED) {
                    // Don't show over-counted unless it is at least 2/3 the size of
                    // the largest real entry, and its percent of total is more significant
                    if (sipper.totalPowerMah < ((mStatsHelper.getMaxRealPower()*2)/3)) {
                        continue;
                    }
                    if (percentOfTotal < 10) {
                        continue;
                    }
                    if ("user".equals(Build.TYPE) || "userdebug".equals(Build.TYPE)) {
                        continue;
                    }
                }
                if (sipper.drainType == BatterySipper.DrainType.UNACCOUNTED) {
                    // Don't show over-counted unless it is at least 1/2 the size of
                    // the largest real entry, and its percent of total is more significant
                    if (sipper.totalPowerMah < (mStatsHelper.getMaxRealPower()/2)) {
                        continue;
                    }
                    if (percentOfTotal < 5) {
                        continue;
                    }
                    if ("user".equals(Build.TYPE) || "userdebug".equals(Build.TYPE)) {
                        continue;
                    }
                }
                final UserHandle userHandle = new UserHandle(UserHandle.getUserId(sipper.getUid()));
                final BatteryEntry entry = new BatteryEntry(getActivity(), mHandler, mUm, sipper);
                final Drawable badgedIcon = mUm.getBadgedIconForUser(entry.getIcon(),
                        userHandle);
                final CharSequence contentDescription = mUm.getBadgedLabelForUser(entry.getLabel(),
                        userHandle);
                final String key = sipper.drainType == DrainType.APP ? sipper.getPackages() != null
                        ? TextUtils.concat(sipper.getPackages()).toString()
                        : String.valueOf(sipper.getUid())
                        : sipper.drainType.toString();
                PowerGaugePreference pref = (PowerGaugePreference) getCachedPreference(key);
                if (pref == null) {
                    pref = new PowerGaugePreference(getPrefContext(), badgedIcon,
                            contentDescription, entry);
                    pref.setKey(key);
                }

                final double percentOfMax = (sipper.totalPowerMah * 100)
                        / mStatsHelper.getMaxPower();
                sipper.percent = percentOfTotal;
                pref.setTitle(entry.getLabel());
                pref.setOrder(i + 1);
                pref.setPercent(percentOfMax, percentOfTotal);
                if (sipper.uidObj != null) {
                    pref.setKey(Integer.toString(sipper.uidObj.getUid()));
                }
                if ((sipper.drainType != DrainType.APP || sipper.uidObj.getUid() == 0)
                         && sipper.drainType != DrainType.USER) {
                    pref.setTint(colorControl);
                }
                addedSome = true;
                mAppListGroup.addPreference(pref);
                if (mAppListGroup.getPreferenceCount() - getCachedCount()
                        > (MAX_ITEMS_TO_LIST + 1)) {
                    break;
                }
            }
        }
        if (!addedSome) {
            addNotAvailableMessage();
        }
        removeCachedPrefs(mAppListGroup);

        BatteryEntry.startRequestQueue();
    }

    private static List<BatterySipper> getFakeStats() {
        ArrayList<BatterySipper> stats = new ArrayList<>();
        float use = 5;
        for (DrainType type : DrainType.values()) {
            if (type == DrainType.APP) {
                continue;
            }
            stats.add(new BatterySipper(type, null, use));
            use += 5;
        }
        for (int i = 0; i < 100; i++) {
            stats.add(new BatterySipper(DrainType.APP,
                    new FakeUid(Process.FIRST_APPLICATION_UID + i), use));
        }
        stats.add(new BatterySipper(DrainType.APP,
                new FakeUid(0), use));

        // Simulate dex2oat process.
        BatterySipper sipper = new BatterySipper(DrainType.APP,
                new FakeUid(UserHandle.getSharedAppGid(Process.FIRST_APPLICATION_UID)), 10.0f);
        sipper.packageWithHighestDrain = "dex2oat";
        stats.add(sipper);

        sipper = new BatterySipper(DrainType.APP,
                new FakeUid(UserHandle.getSharedAppGid(Process.FIRST_APPLICATION_UID + 1)), 10.0f);
        sipper.packageWithHighestDrain = "dex2oat";
        stats.add(sipper);

        sipper = new BatterySipper(DrainType.APP,
                new FakeUid(UserHandle.getSharedAppGid(Process.LOG_UID)), 9.0f);
        stats.add(sipper);

        return stats;
    }

    Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BatteryEntry.MSG_UPDATE_NAME_ICON:
                    BatteryEntry entry = (BatteryEntry) msg.obj;
                    PowerGaugePreference pgp =
                            (PowerGaugePreference) findPreference(
                                    Integer.toString(entry.sipper.uidObj.getUid()));
                    if (pgp != null) {
                        final int userId = UserHandle.getUserId(entry.sipper.getUid());
                        final UserHandle userHandle = new UserHandle(userId);
                        pgp.setIcon(mUm.getBadgedIconForUser(entry.getIcon(), userHandle));
                        pgp.setTitle(entry.name);
                        if (entry.sipper.drainType == DrainType.APP) {
                            pgp.setContentDescription(entry.name);
                        }
                    }
                    break;
                case BatteryEntry.MSG_REPORT_FULLY_DRAWN:
                    Activity activity = getActivity();
                    if (activity != null) {
                        activity.reportFullyDrawn();
                    }
                    break;
            }
            super.handleMessage(msg);
        }
    };

    private static class SummaryProvider implements SummaryLoader.SummaryProvider {
        private final Context mContext;
        private final SummaryLoader mLoader;

        private SummaryProvider(Context context, SummaryLoader loader) {
            mContext = context;
            mLoader = loader;
        }

        @Override
        public void setListening(boolean listening) {
            if (listening) {
                // TODO: Listen.
                BatteryInfo.getBatteryInfo(mContext, new BatteryInfo.Callback() {
                    @Override
                    public void onBatteryInfoLoaded(BatteryInfo info) {
                        mLoader.setSummary(SummaryProvider.this, info.mChargeLabelString);
                    }
                });
            }
        }
    }

    public static final SummaryLoader.SummaryProviderFactory SUMMARY_PROVIDER_FACTORY
            = new SummaryLoader.SummaryProviderFactory() {
        @Override
        public SummaryLoader.SummaryProvider createSummaryProvider(Activity activity,
                                                                   SummaryLoader summaryLoader) {
            return new SummaryProvider(activity, summaryLoader);
        }
    };

    private void updateBatteryPct() {
        if (mBatteryPct != null) {
            mBatteryPct.setChecked(
                Settings.System.getInt(getContext().getContentResolver(),
                SHOW_PERCENT_SETTING, 0) != 0);
            mBatteryPct.setOnPreferenceChangeListener(mBatteryPctChange);
        }
    }

    private final Preference.OnPreferenceChangeListener mBatteryPctChange =
            new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            final boolean v = (Boolean) newValue;
            Settings.System.putInt(getContext().getContentResolver(),
                    SHOW_PERCENT_SETTING, v ? 1 : 0);
            return true;
        }
    };
}
