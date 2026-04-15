package dev.m1le.mjrmanager.service;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

public class SettingsService {

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(SettingsService.class);

    private static final String KEY_RECENT_PREFIX = "recent_jar_";
    private static final String KEY_RECENT_COUNT  = "recent_jar_count";
    private static final int    MAX_RECENT         = 10;

    public List<String> getRecentJars() {
        int count = PREFS.getInt(KEY_RECENT_COUNT, 0);
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String val = PREFS.get(KEY_RECENT_PREFIX + i, null);
            if (val != null) list.add(val);
        }
        return list;
    }

    public void addRecentJar(String path) {
        List<String> list = getRecentJars();
        list.remove(path);
        list.add(0, path);
        if (list.size() > MAX_RECENT) list = list.subList(0, MAX_RECENT);
        saveRecentList(list);
    }

    public void clearRecentJars() {
        saveRecentList(new ArrayList<>());
    }

    private void saveRecentList(List<String> list) {
        PREFS.putInt(KEY_RECENT_COUNT, list.size());
        for (int i = 0; i < list.size(); i++) {
            PREFS.put(KEY_RECENT_PREFIX + i, list.get(i));
        }
    }
}