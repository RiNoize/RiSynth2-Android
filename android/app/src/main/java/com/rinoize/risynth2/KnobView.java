package com.rinoize.risynth2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.Locale;

/** Rotary knob drawn with Canvas: value arc, body, pointer, name and number.
 * Drag vertically to change it. A knob made togglable (setToggle) also switches on/off
 * with a short tap, which is how Delay and Chorus keep their on/off state on screen. */
final class KnobView extends View {
    interface OnChange { void changed(int value); }
    interface OnToggle { void toggled(boolean on); }

    private static final float FULL_RANGE_DP = 170f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float density, slop;
    private final String label, labelUpper;
    private int min, max = 127, value;
    private boolean togglable, on = true, dragged;
    private OnChange onChange;
    private OnToggle onToggle;
    private float downY;
    private int downValue;
    // Geometry, recalculated only when the size changes.
    private float cx, cy, ringWidth, bodyRadius, labelBaseline, valueBaseline, labelSize, valueSize;
    private RadialGradient bodyShader;

    KnobView(Context context, String label) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.label = label;
        this.labelUpper = label.toUpperCase(Locale.ROOT);
        setFocusable(true);
        describe();
    }

    void setRange(int min, int max) { this.min = min; this.max = Math.max(min, max); setValue(value); }
    void setOnChange(OnChange listener) { onChange = listener; }
    void setToggle(OnToggle listener) { togglable = true; onToggle = listener; describe(); }

    /** Programmatic update (patch change, editor): never calls the listeners. */
    void setValue(int newValue) {
        int clamped = ControlMath.clamp(newValue, min, max);
        if (clamped == value) return;
        value = clamped;
        describe();
        invalidate();
    }
    void setOn(boolean newOn) {
        if (on == newOn) return;
        on = newOn;
        describe();
        invalidate();
    }

    private void describe() {
        setContentDescription(label + " " + value + (togglable ? (on ? ", activado" : ", desactivado") : ""));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(Math.round(90 * density), widthSpec), resolveSize(Math.round(140 * density), heightSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        labelSize = 12 * density;
        valueSize = 17 * density;
        float gap = 5 * density;
        float textBlock = labelSize + valueSize + gap * 3;
        float diameter = Math.max(30 * density, Math.min(w - 6 * density, h - textBlock));
        float top = Math.max(0f, (h - diameter - textBlock) / 2f);
        cx = w / 2f;
        cy = top + diameter / 2f;
        ringWidth = Math.max(4 * density, diameter * 0.085f);
        float ringRadius = diameter / 2f - ringWidth / 2f;
        oval.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
        bodyRadius = Math.max(4 * density, ringRadius - ringWidth / 2f - 4 * density);
        bodyShader = new RadialGradient(cx - bodyRadius * 0.3f, cy - bodyRadius * 0.4f, bodyRadius * 1.7f,
            Look.BODY_HI, Look.BODY_LO, Shader.TileMode.CLAMP);
        labelBaseline = top + diameter + gap + labelSize * 0.85f;
        valueBaseline = labelBaseline + gap + valueSize * 0.95f;
    }

    @Override protected void onDraw(Canvas canvas) {
        float fraction = ControlMath.fraction(value, min, max);
        int active = on ? Look.AMBER : Look.BODY_EDGE;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ringWidth);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(Look.TRACK);
        canvas.drawArc(oval, ControlMath.START_DEGREES, ControlMath.SWEEP_DEGREES, false, paint);
        if (fraction > 0f) {
            paint.setColor(active);
            canvas.drawArc(oval, ControlMath.START_DEGREES, ControlMath.SWEEP_DEGREES * fraction, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(bodyShader);
        canvas.drawCircle(cx, cy, bodyRadius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(Look.BODY_EDGE);
        canvas.drawCircle(cx, cy, bodyRadius, paint);
        double angle = Math.toRadians(ControlMath.pointerDegrees(fraction));
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        paint.setStrokeWidth(4 * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(active);
        canvas.drawLine(cx + cos * bodyRadius * 0.40f, cy + sin * bodyRadius * 0.40f,
            cx + cos * bodyRadius * 0.86f, cy + sin * bodyRadius * 0.86f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Look.HEADING);
        paint.setTextSize(labelSize);
        paint.setColor(Look.MUTED);
        canvas.drawText(labelUpper, cx, labelBaseline, paint);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(valueSize);
        paint.setColor(on ? Look.TEXT : Look.MUTED);
        canvas.drawText(Integer.toString(value), cx, valueBaseline, paint);
        // Effects are controlled by a short tap. Show their switch state on the face
        // rather than hiding the only on/off control in a gesture.
        if (togglable) {
            paint.setTextSize(9 * density);
            paint.setTypeface(Look.HEADING);
            paint.setColor(on ? Look.AMBER : Look.MUTED);
            canvas.drawText(on ? "ON" : "OFF", cx, cy + bodyRadius * 0.58f, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downY = event.getY(); downValue = value; dragged = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float upward = downY - event.getY();
                if (!dragged && Math.abs(upward) > slop) {
                    // Start measuring from here so the knob does not jump by the slop distance.
                    dragged = true; downY = event.getY(); downValue = value;
                    return true;
                }
                if (dragged) {
                    int next = ControlMath.dragValue(downValue, upward, FULL_RANGE_DP * density, min, max);
                    if (next != value) {
                        value = next;
                        describe();
                        invalidate();
                        if (onChange != null) onChange.changed(next);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (!dragged && togglable) {
                    on = !on;
                    describe();
                    invalidate();
                    if (onToggle != null) onToggle.toggled(on);
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() { return super.performClick(); }
}
