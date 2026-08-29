package com.xingyao.card.face;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * Draws the visual guidance above the CameraX preview.
 * Recognition and face matching remain owned by FaceAISDK; this view is visual-only.
 */
public final class FaceScanOverlayView extends View {
    private static final long SCAN_DURATION_MS = 1800L;

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF faceBounds = new RectF();
    private final Path faceClip = new Path();

    private float density;
    private float scanProgress;
    private ValueAnimator scanAnimator;

    public FaceScanOverlayView(Context context) {
        super(context);
        initialize();
    }

    public FaceScanOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public FaceScanOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    private void initialize() {
        density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        DashPathEffect dashEffect = new DashPathEffect(
                new float[]{dp(13f), dp(8f)},
                0f
        );

        borderGlowPaint.setStyle(Paint.Style.STROKE);
        borderGlowPaint.setStrokeWidth(dp(7f));
        borderGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        borderGlowPaint.setColor(Color.argb(105, 83, 167, 255));
        borderGlowPaint.setPathEffect(dashEffect);
        borderGlowPaint.setMaskFilter(new BlurMaskFilter(dp(7f), BlurMaskFilter.Blur.NORMAL));

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(2f));
        borderPaint.setStrokeCap(Paint.Cap.ROUND);
        borderPaint.setColor(Color.argb(235, 238, 247, 255));
        borderPaint.setPathEffect(dashEffect);

        scanLinePaint.setStyle(Paint.Style.STROKE);
        scanLinePaint.setStrokeWidth(dp(2f));
        scanLinePaint.setStrokeCap(Paint.Cap.ROUND);
        scanLinePaint.setColor(Color.rgb(54, 151, 255));

        scanGlowPaint.setStyle(Paint.Style.FILL);
        scanGlowPaint.setMaskFilter(new BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.NORMAL));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;

        float inset = dp(9f);
        faceBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        buildFacePath();

        drawScan(canvas);
        canvas.drawPath(faceClip, borderGlowPaint);
        canvas.drawPath(faceClip, borderPaint);
    }

    /**
     * A soft face-shaped guide: rounded forehead, gently tapered cheeks and a
     * broad curved chin. It intentionally avoids a geometric full ellipse.
     */
    private void buildFacePath() {
        buildFacePath(faceClip, faceBounds);
    }

    static void buildFacePath(Path target, RectF bounds) {
        float left = bounds.left;
        float top = bounds.top;
        float width = bounds.width();
        float height = bounds.height();
        float right = bounds.right;
        float bottom = bounds.bottom;
        float centerX = bounds.centerX();

        target.reset();
        target.moveTo(centerX, top);
        target.cubicTo(
                left + width * 0.20f, top,
                left + width * 0.04f, top + height * 0.14f,
                left + width * 0.03f, top + height * 0.35f
        );
        target.cubicTo(
                left + width * 0.01f, top + height * 0.58f,
                left + width * 0.12f, top + height * 0.80f,
                left + width * 0.32f, top + height * 0.94f
        );
        target.cubicTo(
                left + width * 0.39f, top + height * 0.985f,
                left + width * 0.44f, bottom,
                centerX, bottom
        );
        target.cubicTo(
                left + width * 0.56f, bottom,
                left + width * 0.61f, top + height * 0.985f,
                left + width * 0.68f, top + height * 0.94f
        );
        target.cubicTo(
                left + width * 0.88f, top + height * 0.80f,
                left + width * 0.99f, top + height * 0.58f,
                right - width * 0.03f, top + height * 0.35f
        );
        target.cubicTo(
                right - width * 0.04f, top + height * 0.14f,
                right - width * 0.20f, top,
                centerX, top
        );
        target.close();
    }

    private void drawScan(Canvas canvas) {
        float verticalProgress = 0.10f + scanProgress * 0.80f;
        float scanY = faceBounds.top + faceBounds.height() * verticalProgress;
        float horizontalInset = faceBounds.width() * 0.08f;
        float startX = faceBounds.left + horizontalInset;
        float endX = faceBounds.right - horizontalInset;

        int saveCount = canvas.save();
        canvas.clipPath(faceClip);

        float glowTop = scanY - dp(22f);
        float glowBottom = scanY + dp(6f);
        scanGlowPaint.setShader(new LinearGradient(
                0f,
                glowTop,
                0f,
                glowBottom,
                new int[]{Color.TRANSPARENT, Color.argb(75, 54, 151, 255), Color.argb(18, 54, 151, 255)},
                new float[]{0f, 0.72f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(startX, glowTop, endX, glowBottom, scanGlowPaint);
        scanGlowPaint.setShader(null);
        canvas.drawLine(startX, scanY, endX, scanY, scanLinePaint);
        canvas.restoreToCount(saveCount);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimationState();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopScanAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        post(this::updateAnimationState);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimationState();
    }

    private void updateAnimationState() {
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
            startScanAnimation();
            return;
        }
        stopScanAnimation();
    }

    private void startScanAnimation() {
        if (scanAnimator == null) {
            scanAnimator = ValueAnimator.ofFloat(0f, 1f);
            scanAnimator.setDuration(SCAN_DURATION_MS);
            scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
            scanAnimator.setRepeatMode(ValueAnimator.RESTART);
            scanAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            scanAnimator.addUpdateListener(animation -> {
                scanProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
        }
        if (!scanAnimator.isStarted()) scanAnimator.start();
    }

    private void stopScanAnimation() {
        if (scanAnimator != null) scanAnimator.cancel();
        scanProgress = 0f;
        invalidate();
    }

    private float dp(float value) {
        return value * density;
    }
}
