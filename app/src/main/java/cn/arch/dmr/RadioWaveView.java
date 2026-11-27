package cn.arch.dmr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** [3.2.41] 网络收音机实时频谱：显示 RadioSpectrum 提供的真实 FFT 数据。 */
public class RadioWaveView extends View {
    private static final int BARS = RadioSpectrum.BARS;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] shown = new float[BARS];
    private final float[] peaks = new float[BARS];
    private boolean active;

    public RadioWaveView(Context c) { super(c); init(); }
    public RadioWaveView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        peakPaint.setStyle(Paint.Style.FILL);
        peakPaint.setColor(0x55FFFFFF);
        setWillNotDraw(false);
    }

    public void setActive(boolean on) {
        if (active != on) {
            active = on;
            if (!on) {
                java.util.Arrays.fill(peaks, 0f);
            }
            invalidate();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float[] target = active ? RadioSpectrum.snapshot() : null;
        float gap = Math.max(2f, w * 0.006f);
        float bar = (w - gap * (BARS + 1)) / BARS;
        if (bar < 1f) bar = 1f;
        float baseY = h - dp(7);
        float maxH = h - dp(14);
        boolean moving = false;
        for (int i = 0; i < BARS; i++) {
            float t = target == null ? 0f : target[i];
            if (t > shown[i]) shown[i] += (t - shown[i]) * 0.55f;   // 起音快
            else shown[i] *= 0.86f;                                  // 回落平滑
            if (shown[i] < 0.004f) shown[i] = 0f;
            float display = Math.min(1f, shown[i] * 1.5f);   // [3.2.47] 视觉幅度放大
            float bh = Math.max(display * maxH, active ? dp(2) : dp(1));
            float left = gap + i * (bar + gap);
            float top = baseY - bh;
            // [3.2.46] 多色频谱：按频段从蓝→青→绿→黄→红渐变
            float hue = 200f - 200f * i / (float) (BARS - 1);
            int topColor = Color.HSVToColor(new float[]{hue, 0.82f, 1f});
            int baseColor = Color.HSVToColor(new float[]{hue, 0.55f, 0.9f});
            paint.setShader(new LinearGradient(left, top, left, baseY,
                    new int[]{baseColor, topColor}, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(left, top, left + bar, baseY, bar / 2f, bar / 2f, paint);
            if (shown[i] > peaks[i]) peaks[i] = shown[i];
            else peaks[i] = Math.max(0f, peaks[i] - 0.02f);
            if (peaks[i] > 0.02f) {
                float peakV = Math.min(1f, peaks[i] * 1.5f);
                float py = baseY - peakV * maxH;
                canvas.drawRoundRect(left, py - dp(1.5f), left + bar, py,
                        dp(1f), dp(1f), peakPaint);
            }
            if (shown[i] > 0.001f) moving = true;
        }
        if (active) {
            if (moving) postInvalidateDelayed(66);
            else postInvalidateDelayed(90);
        }
    }
}
