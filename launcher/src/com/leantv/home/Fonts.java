package com.leantv.home;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Inter (SIL OFL 1.1, assets/fonts/OFL-Inter.txt). Characters Inter lacks, such as Hebrew, fall back to
 * the system font automatically.
 */
final class Fonts {

    static final String EXTRA_LIGHT = "extralight", LIGHT = "light", REGULAR = "regular",
            MEDIUM = "medium", SEMIBOLD = "semibold";

    private static final Map<String, Typeface> cache = new HashMap<>();

    static synchronized Typeface get(Context c, String weight) {
        Typeface t = cache.get(weight);
        if (t == null) {
            try {
                t = Typeface.createFromAsset(c.getAssets(), "fonts/inter_" + weight + ".ttf");
            } catch (Exception e) {
                t = Typeface.DEFAULT;
            }
            cache.put(weight, t);
        }
        return t;
    }

    /** White text in the given weight and size, with a soft shadow so it reads on any photo. */
    static TextView style(TextView t, String weight, float sp, float alpha) {
        t.setTypeface(get(t.getContext(), weight));
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setShadowLayer(Tiles.dp(t.getContext(), 8), 0, Tiles.dp(t.getContext(), 1), 0x66000000);
        t.setAlpha(alpha);
        t.setIncludeFontPadding(false);
        return t;
    }

    private Fonts() {}
}
