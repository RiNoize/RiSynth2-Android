package com.rinoize.risynth2;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** "Hardware ámbar" palette and small view factories for the live-performance layout.
 * Colours are original to this app; no third-party artwork is used. */
final class Look {
    static final int GROUND = 0xFF15120E, PANEL = 0xFF1E1A15, PANEL2 = 0xFF29241D, LINE = 0xFF3B352C, TRACK = 0xFF38332B,
        TEXT = 0xFFF4F1EA, MUTED = 0xFFBDB4A4, AMBER = 0xFFF2AE45, AMBER_DIM = 0xFFA67A38,
        CYAN = 0xFF6FD2E3, DANGER = 0xFFF0634A, DARK = 0xFF15120E,
        LCD = 0xFF0E0A05, LCD_LINE = 0xFF46381F, BODY_HI = 0xFF4B463D, BODY_LO = 0xFF1B1813, BODY_EDGE = 0xFF625C52,
        KEY_DOWN_WHITE = 0xFFF6C46C, KEY_DOWN_BLACK = 0xFFF2AE45;
    static final Typeface HEADING = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Look() { }

    static GradientDrawable round(int fill, float radiusPx, int stroke, int strokePx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radiusPx);
        if (strokePx > 0) shape.setStroke(strokePx, stroke);
        return shape;
    }

    /** Rounded surface with a ripple that stays inside the rounded corners. */
    static Drawable ripple(int fill, float radiusPx, int stroke, int strokePx) {
        return new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), round(fill, radiusPx, stroke, strokePx),
            round(Color.WHITE, radiusPx, 0, 0));
    }

    static TextView label(Context context, String text, float sp, int color, Typeface face) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }

    static Button button(Context context, String text, int fill, int textColor, int stroke) {
        float density = context.getResources().getDisplayMetrics().density;
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(15);
        button.setTypeface(HEADING);
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        int pad = Math.round(14 * density);
        button.setPadding(pad, 0, pad, 0);
        button.setBackground(ripple(fill, 12 * density, stroke, Math.round(density)));
        return button;
    }

    /** One favourites/page chip: slot number plus patch name. Empty slots are disabled. */
    static TextView chip(Context context, String number, String name, boolean active, boolean enabled) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView chip = new TextView(context);
        chip.setText(number + "  " + name);
        chip.setTextSize(14);
        chip.setTypeface(HEADING);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(12 * density);
        chip.setPadding(pad, 0, pad, 0);
        chip.setEnabled(enabled);
        chip.setClickable(enabled);
        if (active) {
            chip.setTextColor(DARK);
            chip.setBackground(ripple(AMBER, 12 * density, 0, 0));
            chip.setContentDescription("Sonido " + number + ", " + name + ", seleccionado");
        } else {
            chip.setTextColor(enabled ? TEXT : MUTED);
            chip.setBackground(ripple(PANEL2, 12 * density, LINE, Math.round(density)));
            chip.setContentDescription(enabled ? "Sonido " + number + ", " + name : "Ranura " + number + " vacía");
        }
        return chip;
    }
}
