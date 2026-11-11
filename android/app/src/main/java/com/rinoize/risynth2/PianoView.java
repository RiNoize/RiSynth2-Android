package com.rinoize.risynth2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

final class PianoView extends View {
    interface Notes { void note(int key, boolean down); }
    private final Notes notes;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseIntArray fingers = new SparseIntArray();
    private final int[] held = new int[128];
    private int base = 48, whiteDown = Color.rgb(92,184,255), blackDown = Color.rgb(32,119,185);
    private static final int[] WHITE = {0,2,4,5,7,9,11,12,14,16,17,19,21,23};
    private static final int[] BLACK = {1,3,6,8,10,13,15,18,20,22};
    private static final int[] BLACK_AFTER = {0,1,3,4,5,7,8,10,11,12};
    PianoView(Context context, Notes notes) {
        super(context); this.notes = notes;
        setContentDescription("Teclado musical de dos octavas, multitactil");
        setFocusable(false);
    }
    void setBase(int value) { releaseAll(); base = Math.max(12, Math.min(96, value)); invalidate(); }
    int base() { return base; }
    /** Colours of pressed keys; the default stays the original blue. */
    void setPressedColors(int white, int black) { whiteDown = white; blackDown = black; invalidate(); }
    void releaseAll() {
        fingers.clear();
        for (int i = 0; i < held.length; ++i) if (held[i] > 0) { held[i] = 0; notes.note(i, false); }
        invalidate();
    }
    private void release(int key) { if (key >= 0 && held[key] > 0 && --held[key] == 0) notes.note(key, false); }
    private void press(int key) { if (key >= 0 && held[key]++ == 0) notes.note(key, true); }
    private int hit(float x, float y) {
        if (x < 0 || x >= getWidth() || y < 0 || y >= getHeight()) return -1;
        float w = getWidth() / 14f;
        if (y < getHeight() * 0.62f) {
            for (int i = 0; i < BLACK.length; ++i) {
                float center = (BLACK_AFTER[i] + 1) * w;
                if (Math.abs(x - center) < w * 0.31f) return base + BLACK[i];
            }
        }
        return base + WHITE[Math.min(13, (int)(x / w))];
    }
    @Override protected void onDraw(Canvas c) {
        float w = getWidth() / 14f;
        for (int i = 0; i < WHITE.length; ++i) {
            paint.setColor(held[base + WHITE[i]] > 0 ? whiteDown : Color.rgb(230,234,239));
            c.drawRect(i * w + 1, 1, (i + 1) * w - 1, getHeight() - 1, paint);
        }
        for (int i = 0; i < BLACK.length; ++i) {
            float center = (BLACK_AFTER[i] + 1) * w;
            paint.setColor(held[base + BLACK[i]] > 0 ? blackDown : Color.rgb(24,30,38));
            c.drawRect(center - w * 0.31f, 0, center + w * 0.31f, getHeight() * 0.62f, paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked(), index = e.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            int key = hit(e.getX(index), e.getY(index));
            fingers.put(e.getPointerId(index), key); press(key);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); ++i) {
                int id = e.getPointerId(i), old = fingers.get(id, -1), key = hit(e.getX(i), e.getY(i));
                if (old != key) { release(old); press(key); fingers.put(id, key); }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int id = e.getPointerId(index); release(fingers.get(id, -1)); fingers.delete(id);
            if (action == MotionEvent.ACTION_UP) { getParent().requestDisallowInterceptTouchEvent(false); performClick(); }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll(); getParent().requestDisallowInterceptTouchEvent(false);
        }
        invalidate(); return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
