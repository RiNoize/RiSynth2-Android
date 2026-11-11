package com.rinoize.risynth2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/** Vertical touch strip for pitch bend (spring back to centre) and mod wheel (stays put). */
final class StripView extends View {
    interface OnValue { void value(int value); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final String label, labelUpper;
    private final int max, rest;
    private final boolean spring;
    private final OnValue listener;
    private int value;
    private float trackTop, trackHeight, thumbHeight;

    StripView(Context context, String label, int max, int initial, boolean spring, OnValue listener) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.label = label;
        this.labelUpper = label.toUpperCase(Locale.ROOT);
        this.max = max;
        this.rest = initial;
        this.value = initial;
        this.spring = spring;
        this.listener = listener;
        setFocusable(true);
        setContentDescription(label);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(Math.round(52 * density), widthSpec), resolveSize(Math.round(120 * density), heightSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        thumbHeight = 40 * density;
        trackTop = 28 * density;
        trackHeight = Math.max(thumbHeight, h - trackTop - 8 * density);
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        rect.set(density, density, w - density, h - density);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Look.PANEL);
        canvas.drawRoundRect(rect, 18 * density, 18 * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(Look.LINE);
        canvas.drawRoundRect(rect, 18 * density, 18 * density, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Look.HEADING);
        paint.setTextSize(11 * density);
        paint.setColor(Look.MUTED);
        canvas.drawText(labelUpper, w / 2f, 18 * density, paint);
        if (spring) {
            paint.setColor(Look.LINE);
            float mid = trackTop + trackHeight / 2f;
            canvas.drawRect(12 * density, mid - density, w - 12 * density, mid + density, paint);
        }
        float thumbTop = ControlMath.stripThumbTop(value, max, trackTop, trackHeight, thumbHeight);
        rect.set(5 * density, thumbTop, w - 5 * density, thumbTop + thumbHeight);
        paint.setColor(spring ? Look.PANEL2 : Look.AMBER);
        canvas.drawRoundRect(rect, 12 * density, 12 * density, paint);
        if (spring) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density);
            paint.setColor(Look.BODY_EDGE);
            canvas.drawRoundRect(rect, 12 * density, 12 * density, paint);
        }
    }

    private void set(int next) {
        if (next == value) return;
        value = next;
        invalidate();
        if (listener != null) listener.value(next);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                set(ControlMath.stripValue(event.getY(), trackTop, trackHeight, thumbHeight, max));
                return true;
            case MotionEvent.ACTION_MOVE:
                set(ControlMath.stripValue(event.getY(), trackTop, trackHeight, thumbHeight, max));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (spring) set(rest);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() { return super.performClick(); }
}
