package com.github.crazyorr.ffmpegrecorder.util;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Created by wanglei on 05/01/2018.
 */

public class MiscUtils {
    public static boolean isOrientationLandscape(Context context) {
        boolean isOrientationLandscape;
        int orientation = context.getResources().getConfiguration().orientation;
        switch (orientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                isOrientationLandscape = true;
                break;
            case Configuration.ORIENTATION_PORTRAIT:
            default:
                isOrientationLandscape = false;
        }
        return isOrientationLandscape;
    }
}
