package com.leantv.home;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Videos copied from the computer by LeanTV Drop, in /sdcard/Android/data/com.leantv.home/files/videos/.
 * (Streamed videos are never stored; they play straight from the computer.)
 */
final class Videos {

    static List<File> list(Context c) {
        File dir = c.getExternalFilesDir("videos");
        File[] files = dir != null ? dir.listFiles() : null;
        List<File> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) if (f.isFile() && isVideo(f.getName()) && f.length() > 0) out.add(f);
        File[] sorted = out.toArray(new File[0]);
        Arrays.sort(sorted, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return new ArrayList<>(Arrays.asList(sorted));
    }

    static boolean isVideo(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mkv") || n.endsWith(".mov")
                || n.endsWith(".webm") || n.endsWith(".ts") || n.endsWith(".3gp");
    }

    /** "holiday_2024.mp4" -> "holiday 2024" */
    static String title(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String t = dot > 0 ? fileName.substring(0, dot) : fileName;
        return t.replace('_', ' ').trim();
    }

    private Videos() {}
}
