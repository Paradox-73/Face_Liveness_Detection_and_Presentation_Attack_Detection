/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import io.mosip.liveness.core.ui.Severity;

/**
 * The face guide drawn over the camera preview: the area outside an upright oval is dimmed, the oval's outline
 * takes the colour of the state's severity (the oval colours of the status palette shared with the desktop client,
 * {@link StatusPalette}), and an arc along the outline shows the session's progress. Colour is
 * never the only signal: the prompt text and icon below always say the same thing.
 */
public final class OvalGuideView extends View {

    /** Largest oval width as a share of the view width. */
    static final float OVAL_WIDTH = 0.70f;
    /** Oval height as a multiple of its width (a face is about 1.3 times taller than wide). */
    static final float OVAL_ASPECT = 1.32f;
    /** Largest oval height as a share of the height left above the bottom panel. */
    static final float OVAL_MAX_HEIGHT = 0.75f;
    /** Oval centre as a share of the height left above the bottom panel (below the title). */
    static final float OVAL_CENTRE = 0.55f;

    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final int progressColor;
    private float progress;
    private int bottomInset;

    /**
     * Creates the view from a layout.
     *
     * @param context context
     * @param attrs   layout attributes
     */
    public OvalGuideView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        dim.setColor(0xB3000000);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(4 * density);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(8 * density);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressColor = context.getColor(io.mosip.registration.livenessmanager.R.color.liveness_accent);
        progressPaint.setColor(progressColor);
        setSeverity(Severity.INFO);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Sets the outline colour from a severity.
     *
     * @param severity the state's severity
     */
    public void setSeverity(Severity severity) {
        int color = getContext().getColor(StatusPalette.of(severity).oval);
        outline.setColor(color);
        // A finished progress arc covers the outline; on success it turns green with it.
        progressPaint.setColor(severity == Severity.SUCCESS ? color : progressColor);
        invalidate();
    }

    /**
     * Returns the outline colour of the current severity.
     *
     * @return ARGB colour
     */
    int outlineColor() {
        return outline.getColor();
    }

    /**
     * Keeps the oval above the prompt panel, so the face stays visible on a landscape tablet as on a phone.
     *
     * @param px height in pixels covered by the panel at the bottom of this view
     */
    public void setBottomInset(int px) {
        if (px != bottomInset) {
            bottomInset = Math.max(0, px);
            invalidate();
        }
    }

    /**
     * Sets the progress arc.
     *
     * @param value 0 to 1
     */
    public void setProgress(double value) {
        progress = (float) Math.max(0, Math.min(1, value));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float free = Math.max(h * 0.5f, h - bottomInset);
        float oh = Math.min(w * OVAL_WIDTH * OVAL_ASPECT, free * OVAL_MAX_HEIGHT);
        float ow = oh / OVAL_ASPECT;
        float cx = w / 2f;
        float cy = free * OVAL_CENTRE;
        oval.set(cx - ow / 2f, cy - oh / 2f, cx + ow / 2f, cy + oh / 2f);
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, w, h, Path.Direction.CW);
        path.addOval(oval, Path.Direction.CW);
        canvas.drawPath(path, dim);
        canvas.drawOval(oval, outline);
        if (progress > 0) {
            canvas.drawArc(oval, -90, 360 * progress, false, progressPaint);
        }
    }
}
