package dev.mcpy.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.ImageButton;

/** Platform-only overlay: the isolated game classloader cannot use editor AndroidX classes. */
public final class FloatingPipButton extends ImageButton {
    private final SharedPreferences preferences;
    private final int slop;
    private final float margin;
    private boolean rightEdge, dragging;
    private float fractionY, downX, downY, startX, startY;
    private View container;
    private final RectF bounds = new RectF();
    private final OnLayoutChangeListener layoutListener = (v,l,t,r,b,ol,ot,or_,ob) -> restorePosition();

    public FloatingPipButton(Context context, Runnable minimize) {
        super(context, null, 0);
        float density = getResources().getDisplayMetrics().density;
        margin = 8 * density;
        preferences = context.getSharedPreferences("game-overlay", Context.MODE_PRIVATE);
        rightEdge = preferences.getBoolean("rightEdge", true);
        fractionY = preferences.getFloat("fractionY", 0);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setContentDescription("缩小为游戏浮窗");
        setTooltipText("缩小为游戏浮窗");
        setImageDrawable(new MinimizeDrawable());
        int padding = Math.round(12 * density);
        setPadding(padding, padding, padding, padding);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xE6333333);
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), circle, null));
        // Keep this overlay independent of the loaded game's widget state animator.
        setStateListAnimator(null);
        setElevation(6 * density);
        setOnClickListener(v -> minimize.run());
        setOnApplyWindowInsetsListener((v, insets) -> { post(this::restorePosition); return insets; });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        container = (View) getParent();
        container.addOnLayoutChangeListener(layoutListener);
        post(this::restorePosition);
    }

    @Override protected void onDetachedFromWindow() {
        if (container != null) container.removeOnLayoutChangeListener(layoutListener);
        container = null;
        super.onDetachedFromWindow();
    }

    private void updateBounds() {
        int left = 0, top = 0, right = 0, bottom = 0;
        WindowInsets insets = getRootWindowInsets();
        if (insets != null) {
            android.graphics.Insets safe = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
        }
        bounds.set(left + margin, top + margin,
                Math.max(left + margin, container.getWidth() - getWidth() - right - margin),
                Math.max(top + margin, container.getHeight() - getHeight() - bottom - margin));
    }

    public void restorePosition() {
        if (dragging || container == null || getVisibility() != VISIBLE || container.getWidth() == 0 || getWidth() == 0) return;
        updateBounds();
        place(rightEdge ? bounds.right : bounds.left,
                bounds.top + Math.max(0, Math.min(1, fractionY)) * bounds.height());
    }

    private void place(float x, float y) {
        android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) getLayoutParams();
        int left = Math.round(x), top = Math.round(y);
        if (params.leftMargin == left && params.topMargin == top) return;
        // SurfaceView's window transparent region must follow the real layout bounds.
        // Translation alone can leave the moved overlay punched out above the game surface.
        params.leftMargin = left; params.topMargin = top;
        setLayoutParams(params);
        getParent().requestTransparentRegion(this);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                updateBounds();
                downX = event.getRawX(); downY = event.getRawY();
                startX = getX(); startY = getY(); dragging = false;
                setPressed(true);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (!dragging && Math.hypot(dx, dy) > slop) { dragging = true; setPressed(false); }
                if (dragging) {
                    place(Math.max(bounds.left, Math.min(bounds.right, startX + dx)),
                            Math.max(bounds.top, Math.min(bounds.bottom, startY + dy)));
                }
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                getParent().requestDisallowInterceptTouchEvent(false);
                if (dragging) {
                    rightEdge = getX() >= bounds.centerX();
                    fractionY = bounds.height() > 0 ? (getY() - bounds.top) / bounds.height() : 0;
                    preferences.edit().putBoolean("rightEdge", rightEdge).putFloat("fractionY", fractionY).apply();
                    dragging = false;
                    restorePosition();
                } else performClick();
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false); dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                restorePosition();
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    /** Large window, inward arrow and small window: minimize to PiP, never close. */
    private static final class MinimizeDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setColor(0xFFFFFFFF); paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(2, 3, 22, 21, 2, 2, paint);
            Path arrow = new Path(); arrow.moveTo(6, 7); arrow.lineTo(11, 12);
            arrow.moveTo(7, 12); arrow.lineTo(11, 12); arrow.lineTo(11, 8);
            canvas.drawPath(arrow, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(13, 14, 20, 19, 1, 1, paint);
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
