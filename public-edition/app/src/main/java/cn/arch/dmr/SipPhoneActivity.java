package cn.arch.dmr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Two-state SIP phone UI: dialer when idle, focused call screen while in a call. */
public class SipPhoneActivity extends Activity implements SipRuntime.Listener {
    private static final int BTN_SECONDARY = 0, BTN_PRIMARY = 1, BTN_SUCCESS = 2, BTN_DANGER = 3, BTN_KEYPAD = 4, BTN_GHOST = 5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs p;
    private SipRuntime sip;
    private boolean registered;
    private boolean activeCall;
    private boolean incomingCall;
    private boolean speakerOn;
    private boolean callRecorded;
    private boolean hangupTransitionPending;
    private long callStartedAt;
    private String callRemote = "";
    private String callKind = "";
    private static volatile boolean visible;
    private static volatile SipPhoneActivity current;
    private long greenDownAt;
    private long lastHardwareKeyAt;

    private EditText target;
    private TextView remote, duration, directionView, callStateView;
    private Button callButton, answerButton, hangupButton, speakerButton, earpieceButton;
    private LinearLayout historyBox, callScreen, routeActions;
    private View dialerScreen;
    private Runnable timer;
    private MediaPlayer incomingPromptPlayer;
    private boolean incomingPromptOn;
    private final HyteraDeviceProfile hardwareProfile = HyteraDeviceProfile.detect();
    private final boolean pnc370 = HyteraDeviceProfile.FAMILY_PNC370.equals(hardwareProfile.family);
    private final boolean pncSmall = hardwareProfile.isPncSmall();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        p = new Prefs(this);
        if (pnc370) getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setContentView(build());
        renderHistory();
        sip = SipRuntime.get(this);
        sip.addListener(this);
        sip.ensureStarted();
        if (pnc370) {
            hideKeyboard();
            if (callButton != null) callButton.post(callButton::requestFocus);
        }
    }

    static boolean isVisible() { return visible; }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        current = this;
        if (sip != null) {
            sip.addListener(this);
            registered = sip.isRegistered();
            if (pnc370 && (activeCall || incomingCall || (sip.getCallState() != null
                    && !sip.getCallState().trim().isEmpty()))) forcePnc370Speaker();
            String call = sip.getCallState();
            if ("来电".equals(call)) onIncoming(sip.getCallRemote());
            else if (call != null && !call.trim().isEmpty()) onCallState(call, sip.getCallRemote());
            ui.post(this::syncSpeakerStateFromSystem);
        }
    }

    @Override protected void onPause() {
        stopPnc370IncomingPrompt();
        visible = false;
        if (current == this) current = null;
        if (sip != null) sip.removeListener(this);
        super.onPause();
    }

    private View build() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF061326, 0xFF0A2B58, 0xFF0E3B78}));

        dialerScreen = buildDialer();
        frame.addView(dialerScreen, new FrameLayout.LayoutParams(-1, -1));

        callScreen = buildCallScreen();
        callScreen.setVisibility(View.GONE);
        frame.addView(callScreen, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private View buildDialer() {
        if (pncSmall) return buildCompactDialer();
        return buildRegularDialer();
    }

    private View buildCompactDialer() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(4), 0, dp(4), dp(2));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        target = field("输入电话号码", InputType.TYPE_CLASS_PHONE);
        target.setTextSize(24);
        target.setGravity(Gravity.CENTER);
        target.setLetterSpacing(0.04f);
        inputRow.addView(target, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button historyButton = button("记录", BTN_SECONDARY);
        historyButton.setTextSize(12);
        historyButton.setOnClickListener(v -> showHistoryDialog());
        LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(dp(54), dp(48));
        historyLp.setMargins(dp(3), 0, 0, 0);
        inputRow.addView(historyButton, historyLp);
        page.addView(inputRow, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        String[][] keys = {{"1","2","3"},{"4","5","6"},{"7","8","9"},{"*","0","#"}};
        for (String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (String key : rowKeys) {
                Button b = button(key, BTN_KEYPAD);
                b.setTextSize(18);
                b.setContentDescription("数字 " + key);
                b.setOnClickListener(v -> target.append(key));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.setMargins(dp(2), dp(1), dp(2), dp(1));
                row.addView(b, lp);
            }
            keypad.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        page.addView(keypad, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        callButton = button("拨打", BTN_SUCCESS);
        callButton.setTextSize(16);
        callButton.setOnClickListener(v -> dial());
        Button clear = button("清除", BTN_GHOST);
        clear.setTextSize(15);
        clear.setOnClickListener(v -> target.setText(""));
        actions.addView(callButton, new LinearLayout.LayoutParams(0, dp(34), 1.35f));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        clearLp.setMargins(dp(7), 0, 0, 0);
        actions.addView(clear, clearLp);
        page.addView(actions, new LinearLayout.LayoutParams(-1, dp(34)));

        historyBox = null;
        target.post(target::requestFocus);
        return page;
    }

    private View buildRegularDialer() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        boolean wide = !pncSmall && widthDp >= 480;
        int side = pncSmall ? dp(5) : widthDp >= 720 ? dp(88) : wide ? dp(28) : dp(9);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(side, dp(pncSmall ? 0 : 10), side, dp(pncSmall ? 0 : 18));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        target = field("输入电话号码", InputType.TYPE_CLASS_PHONE);
        target.setTextSize(pncSmall ? 23 : 24);
        target.setGravity(Gravity.CENTER);
        target.setLetterSpacing(0.04f);
        root.addView(target, new LinearLayout.LayoutParams(-1, dp(pncSmall ? 40 : (wide ? 58 : 50))));

        String[][] keys = {{"1","2","3"},{"4","5","6"},{"7","8","9"},{"*","0","#"}};
        for (String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (String key : rowKeys) {
                Button b = button(key, BTN_KEYPAD);
                b.setTextSize(pncSmall ? 22 : (wide ? 25 : 22));
                b.setContentDescription("数字 " + key);
                b.setOnClickListener(v -> target.append(key));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(pncSmall ? 33 : (wide ? 52 : 41)), 1f);
                lp.setMargins(dp(pncSmall ? 2 : 3), dp(pncSmall ? 0 : 2),
                        dp(pncSmall ? 2 : 3), dp(pncSmall ? 1 : 2));
                row.addView(b, lp);
            }
            root.addView(row);
        }

        LinearLayout actions = new LinearLayout(this);
        callButton = button("拨打", BTN_SUCCESS);
        callButton.setTextSize(pncSmall ? 16 : 15);
        callButton.setOnClickListener(v -> dial());
        Button clear = button("清除", BTN_GHOST);
        clear.setTextSize(pncSmall ? 15 : 14);
        clear.setOnClickListener(v -> target.setText(""));
        actions.addView(callButton, new LinearLayout.LayoutParams(0, dp(pncSmall ? 40 : (wide ? 54 : 46)), 1.35f));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(pncSmall ? 40 : (wide ? 54 : 46)), 1f);
        clearLp.setMargins(dp(7), 0, 0, 0);
        actions.addView(clear, clearLp);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-1, -2);
        actionLp.setMargins(0, dp(pncSmall ? 0 : 5), 0, dp(pncSmall ? 0 : 12));
        root.addView(actions, actionLp);

        LinearLayout historyHead = new LinearLayout(this);
        historyHead.setGravity(Gravity.CENTER_VERTICAL);
        historyHead.addView(label("通话记录", 15, 0xFFFFFFFF, true), new LinearLayout.LayoutParams(0, -2, 1f));
        Button clearHistory = button("清空", BTN_SECONDARY);
        clearHistory.setTextSize(12);
        clearHistory.setOnClickListener(v -> { p.set("sipHistory", ""); renderHistory(); });
        historyHead.addView(clearHistory, new LinearLayout.LayoutParams(dp(62), dp(34)));
        root.addView(historyHead);
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(-1, -2);
        historyLp.setMargins(0, dp(5), 0, 0);
        root.addView(historyBox, historyLp);

        target.post(target::requestFocus);
        return scroll;
    }

    private LinearLayout buildCallScreen() {
        LinearLayout call = new LinearLayout(this);
        call.setOrientation(LinearLayout.VERTICAL);
        call.setGravity(Gravity.CENTER_HORIZONTAL);
        call.setPadding(dp(12), dp(pncSmall ? 10 : 18), dp(12), dp(pncSmall ? 6 : 12));
        call.setBackgroundColor(Color.TRANSPARENT);

        directionView = label("呼出", 12, 0xFFA9C9F0, true);
        directionView.setGravity(Gravity.CENTER);
        call.addView(directionView);

        remote = label("", pncSmall ? 24 : 26, 0xFFFFFFFF, true);
        remote.setGravity(Gravity.CENTER);
        remote.setSingleLine(true);
        remote.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams remoteLp = new LinearLayout.LayoutParams(-1, -2);
        remoteLp.setMargins(0, dp(pncSmall ? 4 : 8), 0, 0);
        call.addView(remote, remoteLp);

        callStateView = label("拨号中…", pncSmall ? 13 : 14, 0xFFA9C9F0, true);
        callStateView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-1, -2);
        stateLp.setMargins(0, dp(pncSmall ? 3 : 5), 0, 0);
        call.addView(callStateView, stateLp);

        duration = label("00:00", pncSmall ? 29 : 34, 0xFFFFFFFF, true);
        duration.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams durationLp = new LinearLayout.LayoutParams(-1, -2);
        durationLp.setMargins(0, dp(pncSmall ? 4 : 8), 0, 0);
        call.addView(duration, durationLp);

        View spacer = new View(this);
        call.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout callActions = new LinearLayout(this);
        callActions.setGravity(Gravity.CENTER);
        answerButton = button("接听", BTN_SUCCESS);
        answerButton.setTextSize(16);
        answerButton.setOnClickListener(v -> answer());
        hangupButton = button("挂断", BTN_DANGER);
        hangupButton.setTextSize(16);
        hangupButton.setOnClickListener(v -> hangup());
        setButtonEnabled(answerButton, false);
        setButtonEnabled(hangupButton, false);
        answerButton.setVisibility(View.GONE);
        callActions.addView(answerButton, new LinearLayout.LayoutParams(0, dp(pncSmall ? 44 : 50), 1f));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(0, dp(pncSmall ? 44 : 50), 1f);
        hLp.setMargins(dp(8), 0, 0, 0);
        callActions.addView(hangupButton, hLp);
        call.addView(callActions, new LinearLayout.LayoutParams(-1, -2));

        routeActions = new LinearLayout(this);
        routeActions.setGravity(Gravity.CENTER);
        speakerButton = button("免提", BTN_SECONDARY);
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        earpieceButton = button("听筒", BTN_SECONDARY);
        earpieceButton.setOnClickListener(v -> setEarpiece());
        routeActions.addView(speakerButton, new LinearLayout.LayoutParams(0, dp(pncSmall ? 36 : 40), 1f));
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(0, dp(pncSmall ? 36 : 40), 1f);
        eLp.setMargins(dp(8), 0, 0, 0);
        routeActions.addView(earpieceButton, eLp);
        LinearLayout.LayoutParams routeLp = new LinearLayout.LayoutParams(-1, -2);
        routeLp.setMargins(0, dp(pncSmall ? 4 : 8), 0, 0);
        routeActions.setVisibility(View.GONE);
        call.addView(routeActions, routeLp);
        paintRoute();
        return call;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTextSize(15);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0xFF8BA8CD);
        e.setPadding(dp(12), dp(7), dp(12), dp(7));
        e.setBackground(roundStroke(0xFF12345F, 0xFF4A7DBA, 12));
        e.setFocusable(true);
        e.setFocusableInTouchMode(true);
        e.setOnFocusChangeListener((view, focused) -> {
            e.setBackground(roundStroke(focused ? 0xFF081422 : 0xFF12345F,
                    focused ? 0xFFFF8A00 : 0xFF4A7DBA, 12, focused ? 3 : 1));
            e.setTextColor(0xFFFFFFFF);
            e.invalidate();
        });
        if (pnc370) {
            try { e.setShowSoftInputOnFocus(false); } catch (Throwable ignored) {}
            e.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() == KeyEvent.ACTION_DOWN && isConfirm(code)) {
                    showKeyboard(e);
                    return true;
                }
                return false;
            });
        }
        return e;
    }

    private Button button(String text, int variant) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(5), 0, dp(5), 0);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setTag(variant);
        paintButton(b, variant, false);
        b.setOnFocusChangeListener((view, focused) -> paintButton(b, variant, focused));
        return b;
    }

    private void paintButton(Button b, int variant, boolean focused) {
        int bgColor;
        int textColor;
        int strokeColor;
        switch (variant) {
            case BTN_PRIMARY:
                bgColor = 0xFF1677FF; textColor = Color.WHITE; strokeColor = 0xFF1677FF; break;
            case BTN_SUCCESS:
                bgColor = 0xFF16A34A; textColor = Color.WHITE; strokeColor = 0xFF16A34A; break;
            case BTN_DANGER:
                bgColor = 0xFFDC2626; textColor = Color.WHITE; strokeColor = 0xFFDC2626; break;
            case BTN_KEYPAD:
                bgColor = 0xFF1B4B87; textColor = Color.WHITE; strokeColor = 0xFF4A7DBA; break;
            case BTN_GHOST:
                bgColor = 0xFF143E73; textColor = Color.WHITE; strokeColor = 0xFF4A7DBA; break;
            default:
                bgColor = 0xFF143E73; textColor = Color.WHITE; strokeColor = 0xFF4A7DBA; break;
        }
        if (focused) {
            bgColor = 0xFF05080E;
            textColor = Color.WHITE;
            strokeColor = 0xFFFF8A00;
        }
        b.setBackground(roundStroke(bgColor, strokeColor, 12, focused ? 3 : 1));
        b.setTextColor(textColor);
        b.setAlpha(b.isEnabled() ? 1f : 0.42f);
        b.invalidate();
    }

    private GradientDrawable roundStroke(int fill, int stroke, int radiusDp) {
        return roundStroke(fill, stroke, radiusDp, 1);
    }

    private GradientDrawable roundStroke(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private void setButtonEnabled(Button b, boolean enabled) {
        if (b == null) return;
        b.setEnabled(enabled);
        Object tag = b.getTag();
        int variant = tag instanceof Integer ? (Integer) tag : BTN_SECONDARY;
        paintButton(b, variant, b.hasFocus());
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setIncludeFontPadding(false);
        return v;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private String value(EditText e) { return e == null ? "" : e.getText().toString().trim(); }

    private boolean isConfirm(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SOFT_LEFT
                || code == KeyEvent.KEYCODE_F1 || code == KeyEvent.KEYCODE_F3;
    }

    private void showKeyboard(EditText e) {
        try { e.setShowSoftInputOnFocus(true); } catch (Throwable ignored) {}
        e.requestFocus();
        e.postDelayed(() -> {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(e, InputMethodManager.SHOW_FORCED);
            } catch (Throwable ignored) {}
        }, 50L);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View focus = getCurrentFocus();
            if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            if (focus != null) focus.clearFocus();
            if (getWindow() != null && getWindow().getDecorView() != null)
                getWindow().getDecorView().requestFocus();
        } catch (Throwable ignored) {}
    }

    private void dial() {
        String dst = value(target);
        if (dst.isEmpty()) {
            Toast.makeText(this, "请输入电话号码", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sip == null || !sip.isRegistered()) {
            Toast.makeText(this, "SIP 未注册，正在自动重连", Toast.LENGTH_SHORT).show();
            if (sip != null) sip.ensureStarted();
            return;
        }
        callKind = "呼出";
        callRemote = dst;
        callRecorded = false;
        callStartedAt = 0L;
        forcePnc370Speaker();
        showCallScreen(false);
        setCallState("拨号中…", false);
        sip.dial(dst);
    }

    private void answer() {
        stopPnc370IncomingPrompt();
        forcePnc370Speaker();
        if (incomingCall) {
            incomingCall = false;
            callKind = "来电";
            setCallState("接听中…", false);
            answerButton.setVisibility(View.GONE);
            setButtonEnabled(answerButton, false);
            hangupButton.setText("挂断");
            setButtonEnabled(hangupButton, true);
        }
        if (sip != null) sip.answer();
    }

    private void hangup() {
        stopPnc370IncomingPrompt();
        setCallState("正在挂断…", false);
        if (sip != null) sip.hangup();
    }

    private void toggleSpeaker() {
        if (pnc370) { forcePnc370Speaker(); return; }
        speakerOn = !speakerOn;
        if (sip != null) sip.setSpeaker(speakerOn);
        paintRoute();
    }

    private void setEarpiece() {
        if (pnc370) { forcePnc370Speaker(); return; }
        speakerOn = false;
        if (sip != null) sip.setEarpiece();
        paintRoute();
    }

    private void paintRoute() {
        if (pnc370) {
            speakerOn = true;
            if (speakerButton != null) speakerButton.setText("免提已开（强制）");
            if (earpieceButton != null) earpieceButton.setVisibility(View.GONE);
            if (routeActions != null) routeActions.setVisibility(View.GONE);
            return;
        }
        if (speakerButton != null) speakerButton.setText(speakerOn ? "免提已开" : "免提");
        if (earpieceButton != null) earpieceButton.setText(speakerOn ? "听筒" : "听筒已用");
    }

    private void startPnc370IncomingPrompt() {
        if (!pnc370) return;
        stopPnc370IncomingPrompt();
        forcePnc370Speaker();
        incomingPromptOn = true;
        try {
            incomingPromptPlayer = MediaPlayer.create(this, R.raw.pnc370_ring);
            if (incomingPromptPlayer != null) {
                incomingPromptPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                incomingPromptPlayer.setLooping(true);
                incomingPromptPlayer.setVolume(1.0f, 1.0f);
                incomingPromptPlayer.start();
            }
        } catch (Throwable t) {
            android.util.Log.w("ARCH-SIP", "ringtone start failed", t);
            stopPnc370IncomingPrompt();
        }
    }

    private void stopPnc370IncomingPrompt() {
        incomingPromptOn = false;
        if (incomingPromptPlayer != null) {
            try { if (incomingPromptPlayer.isPlaying()) incomingPromptPlayer.stop(); } catch (Throwable ignored) {}
            try { incomingPromptPlayer.release(); } catch (Throwable ignored) {}
            incomingPromptPlayer = null;
        }
    }

    private void forcePnc370Speaker() {
        if (!pnc370) return;
        speakerOn = true;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(true);
            }
        } catch (Throwable ignored) {}
        try { if (sip != null) sip.setSpeaker(true); } catch (Throwable ignored) {}
        paintRoute();
    }

    private void syncSpeakerStateFromSystem() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) speakerOn = am.isSpeakerphoneOn();
        paintRoute();
    }

    private void showCallScreen(boolean incoming) {
        hideKeyboard();
        forcePnc370Speaker();
        if (dialerScreen != null) dialerScreen.setVisibility(View.GONE);
        if (callScreen != null) callScreen.setVisibility(View.VISIBLE);
        directionView.setText(incoming ? "来电" : "呼出");
        remote.setText(displayNumber(callRemote));
        duration.setText("00:00");
        answerButton.setVisibility(incoming ? View.VISIBLE : View.GONE);
        setButtonEnabled(answerButton, incoming);
        hangupButton.setText(incoming ? "拒接" : "挂断");
        hangupButton.setContentDescription(incoming ? "拒接来电" : "挂断通话");
        setButtonEnabled(hangupButton, true);
        routeActions.setVisibility(View.GONE);
        if (incoming) answerButton.requestFocus(); else hangupButton.requestFocus();
    }

    private void showDialer() {
        if (callScreen != null) callScreen.setVisibility(View.GONE);
        if (dialerScreen != null) dialerScreen.setVisibility(View.VISIBLE);
        answerButton.setVisibility(View.GONE);
        setButtonEnabled(answerButton, false);
        hangupButton.setText("挂断");
        hangupButton.setContentDescription("挂断通话");
        setButtonEnabled(hangupButton, false);
        routeActions.setVisibility(pnc370 ? View.GONE : View.GONE);
        target.setText("");
        if (pnc370 && callButton != null) callButton.post(callButton::requestFocus);
        else target.post(target::requestFocus);
    }

    private void setCallState(String text, boolean ok) {
        if (callStateView == null) return;
        callStateView.setText(text);
        boolean pending = text != null && (!"已挂断".equals(text)
                && (text.contains("拨号") || text.contains("振铃")
                || text.contains("来电") || text.contains("接听") || text.contains("挂断")));
        boolean failed = text != null && (text.contains("失败") || text.contains("断开") || text.contains("异常"));
        int color = failed ? 0xFFFFC2C2 : pending ? 0xFFFFD28A : ok ? 0xFF9CF0B1 : 0xFFA9C9F0;
        callStateView.setTextColor(color);
    }

    private void startTimer() {
        if (timer != null) return;
        timer = new Runnable() {
            @Override public void run() {
                long start = callStartedAt;
                long seconds = start == 0L ? 0 : Math.max(0, (System.currentTimeMillis() - start) / 1000);
                if (duration != null) duration.setText(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
                if (activeCall || incomingCall) ui.postDelayed(this, 1000L);
                else timer = null;
            }
        };
        ui.post(timer);
    }

    private void finishHangupCall() {
        if (hangupTransitionPending) return;
        hangupTransitionPending = true;
        activeCall = false;
        incomingCall = false;
        if (timer != null) { ui.removeCallbacks(timer); timer = null; }
        setCallState("已挂断", true);
        setButtonEnabled(answerButton, false);
        answerButton.setVisibility(View.GONE);
        setButtonEnabled(hangupButton, false);
        routeActions.setVisibility(View.GONE);
        ui.postDelayed(() -> {
            hangupTransitionPending = false;
            finishCall(true);
        }, 750L);
    }

    private void finishCall(boolean record) {
        stopPnc370IncomingPrompt();
        long seconds = callStartedAt == 0L ? 0 : Math.max(0, (System.currentTimeMillis() - callStartedAt) / 1000);
        activeCall = false;
        incomingCall = false;
        speakerOn = false;
        callStartedAt = 0L;
        if (timer != null) { ui.removeCallbacks(timer); timer = null; }
        if (record && !callRecorded && !callKind.isEmpty()) {
            callRecorded = true;
            addHistory(callKind, callRemote, seconds);
        }
        callKind = "";
        callRemote = "";
        paintRoute();
        showDialer();
    }

    private void showHistoryDialog() {
        StringBuilder textValue = new StringBuilder();
        String value = p.get("sipHistory", "");
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        for (String line : value.split("\n")) {
            String[] a = line.split("\\|", 4);
            if (a.length < 4) continue;
            String type = "来电".equals(a[1].trim()) ? "来电" : "呼出";
            String number = displayNumber(a[2]);
            long ts;
            try { ts = Long.parseLong(a[0]); } catch (Throwable t) { continue; }
            if (textValue.length() > 0) textValue.append('\n');
            textValue.append(type).append("  ").append(number.isEmpty() ? "未知号码" : number)
                    .append("    ").append(fmt.format(new Date(ts)));
        }
        if (textValue.length() == 0) textValue.append("暂无记录");
        TextView list = label(textValue.toString(), 15, 0xFF0E1726, false);
        list.setTextIsSelectable(true);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("通话记录")
                .setView(list)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", (d, w) -> p.set("sipHistory", ""))
                .create();
        dialog.show();
    }

    private void addHistory(String type, String who, long seconds) {
        try {
            String line = System.currentTimeMillis() + "|" + type + "|" + (who == null ? "" : who.replace("|", "")) + "|" + seconds;
            String old = p.get("sipHistory", "");
            String out = line + (old.isEmpty() ? "" : "\n" + old);
            String[] lines = out.split("\n");
            StringBuilder cap = new StringBuilder();
            for (int i = 0; i < lines.length && i < 20; i++) {
                if (i > 0) cap.append('\n');
                cap.append(lines[i]);
            }
            p.set("sipHistory", cap.toString());
            renderHistory();
        } catch (Throwable ignored) {}
    }

    private void renderHistory() {
        if (historyBox == null) return;
        historyBox.removeAllViews();
        String value = p.get("sipHistory", "");
        if (value.trim().isEmpty()) {
            historyBox.addView(label("暂无记录", 13, 0xFFA9C9F0, false));
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        for (String line : value.split("\n")) {
            String[] a = line.split("\\|", 4);
            if (a.length < 4) continue;
            String numberText = displayNumber(a[2]);
            String type = "来电".equals(a[1].trim()) ? "来电" : "呼出";
            long ts;
            try { ts = Long.parseLong(a[0]); } catch (Throwable t) { continue; }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(7), dp(9), dp(7));
            row.setBackground(roundStroke(0xFF17487F, 0xFF4A7DBA, 10));
            row.setClickable(true);
            row.setFocusable(true);
            TextView kind = label(type, 10, 0xFFA9C9F0, true);
            TextView numberView = label(numberText.isEmpty() ? "未知号码" : numberText, 14, 0xFFFFFFFF, true);
            numberView.setSingleLine(true);
            numberView.setEllipsize(TextUtils.TruncateAt.END);
            TextView time = label(fmt.format(new Date(ts)), 10, 0xFFA9C9F0, false);
            row.addView(kind, new LinearLayout.LayoutParams(dp(38), -2));
            row.addView(numberView, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(time);
            row.setOnFocusChangeListener((v, focused) -> {
                row.setBackground(roundStroke(focused ? 0xFF05080E : 0xFF17487F,
                        focused ? 0xFFFF8A00 : 0xFF4A7DBA, 10, focused ? 3 : 1));
                row.invalidate();
            });
            row.setOnClickListener(v -> {
                target.setText(numberText);
                target.requestFocus();
            });
            row.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() == KeyEvent.ACTION_DOWN && isConfirm(code)) {
                    row.performClick();
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, dp(4));
            historyBox.addView(row, lp);
        }
    }

    private String displayNumber(String raw) {
        String value = raw == null ? "" : raw.trim();
        int sip = value.toLowerCase(Locale.US).indexOf("sip:");
        if (sip >= 0) value = value.substring(sip + 4);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(0, at);
        int semi = value.indexOf(';');
        if (semi >= 0) value = value.substring(0, semi);
        return value.replace("<", "").replace(">", "").replace("\"", "").trim();
    }

    @Override public void onSipState(String state, boolean isRegistered) {
        ui.post(() -> registered = isRegistered);
    }

    @Override public void onIncoming(String remoteUri) {
        ui.post(() -> {
            incomingCall = true;
            activeCall = false;
            callKind = "来电";
            callRemote = remoteUri == null ? "" : remoteUri;
            callRecorded = false;
            callStartedAt = 0L;
            showCallScreen(true);
            setCallState("来电", false);
            startPnc370IncomingPrompt();
            startTimer();
        });
    }

    @Override public void onError(String message) {
        ui.post(() -> Toast.makeText(this, message == null ? "SIP 错误" : message, Toast.LENGTH_SHORT).show());
    }

    @Override public void onCallState(String state, String remoteUri) {
        ui.post(() -> {
            if (remoteUri != null && !remoteUri.isEmpty()) callRemote = remoteUri;
            if ("通话中".equals(state)) {
                incomingCall = false;
                activeCall = true;
                if (callStartedAt == 0L) callStartedAt = System.currentTimeMillis();
                if (callScreen.getVisibility() != View.VISIBLE) {
                    showCallScreen(false);
                }
                setCallState("通话中", true);
                setButtonEnabled(answerButton, false);
                answerButton.setVisibility(View.GONE);
                setButtonEnabled(hangupButton, true);
                routeActions.setVisibility(View.VISIBLE);
                syncSpeakerStateFromSystem();
                startTimer();
                hangupButton.requestFocus();
            } else if ("接听中".equals(state) || "正在挂断…".equals(state)) {
                if (callKind.isEmpty() && "接听中".equals(state)) callKind = "来电";
                if (callScreen.getVisibility() != View.VISIBLE) {
                    showCallScreen("接听中".equals(state));
                }
                setCallState(state, "正在挂断…".equals(state));
                setButtonEnabled(answerButton, false);
                answerButton.setVisibility(View.GONE);
                hangupButton.setText("挂断");
                setButtonEnabled(hangupButton, true);
                routeActions.setVisibility("正在挂断…".equals(state) ? View.VISIBLE : View.GONE);
                hangupButton.requestFocus();
            } else if ("拨号中".equals(state) || "振铃中".equals(state)) {
                if (callScreen.getVisibility() != View.VISIBLE) {
                    callKind = "呼出";
                    showCallScreen(false);
                }
                setCallState(state, false);
                setButtonEnabled(hangupButton, true);
            } else if ("通话结束".equals(state)) {
                finishCall(true);
            } else if ("已挂断".equals(state)) {
                finishHangupCall();
            } else if ("对方忙线".equals(state) || "无人接听".equals(state) || "号码不存在".equals(state)) {
                finishCall(true);
                Toast.makeText(this, state, Toast.LENGTH_LONG).show();
            } else if (state != null && state.startsWith("通话失败")) {
                finishCall(true);
                Toast.makeText(this, state, Toast.LENGTH_LONG).show();
            } else if ("音频连接失败".equals(state)) {
                setCallState("音频连接失败", false);
                Toast.makeText(this, "SIP 音频通道连接失败", Toast.LENGTH_LONG).show();
            }
        });
    }

    static boolean handleHardwareCallKey(int code, int action) {
        SipPhoneActivity activity = current;
        if (activity == null || !visible) return false;
        return activity.handleSipHardwareKey(code, action);
    }

    private boolean isIncomingForHardware() {
        return (incomingCall && !activeCall)
                || (sip != null && "来电".equals(sip.getCallState()))
                || (answerButton != null && answerButton.getVisibility() == View.VISIBLE);
    }

    private boolean isCallForHardware() {
        return incomingCall || activeCall || callScreen.getVisibility() == View.VISIBLE;
    }

    /** Green key dials only. Center/select answers incoming; Back hangs up. */
    private boolean handleSipHardwareKey(int code, int action) {
        boolean greenKey = code == KeyEvent.KEYCODE_CALL || code == KeyEvent.KEYCODE_F3
                || code == KeyEvent.KEYCODE_HEADSETHOOK;
        boolean selectKey = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SOFT_LEFT
                || code == KeyEvent.KEYCODE_F1 || code == KeyEvent.KEYCODE_F2;
        long now = SystemClock.uptimeMillis();
        if (selectKey) {
            if (action == KeyEvent.ACTION_DOWN && isIncomingForHardware()) answer();
            return isIncomingForHardware();
        }
        if (code == KeyEvent.KEYCODE_BACK && isCallForHardware()) {
            if (action == KeyEvent.ACTION_DOWN) hangup();
            return true;
        }
        if (!greenKey) return false;
        if (action == KeyEvent.ACTION_DOWN) {
            if (now - lastHardwareKeyAt < 250) return true;
            lastHardwareKeyAt = now;
            if (code == KeyEvent.KEYCODE_F3) {
                greenDownAt = now;
                return true;
            }
            if (!isCallForHardware()) dial();
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_F3 && greenDownAt > 0L && now - greenDownAt < 700L) {
                greenDownAt = 0L;
                if (!isCallForHardware()) dial();
            } else if (code == KeyEvent.KEYCODE_F3) {
                greenDownAt = 0L;
            }
            return true;
        }
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        if (handleSipHardwareKey(code, action)) {
            android.util.Log.i("ARCH-SIP-KEY", "code=" + code + " action=" + action);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (callScreen != null && callScreen.getVisibility() == View.VISIBLE) {
            hangup();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        stopPnc370IncomingPrompt();
        if (timer != null) { ui.removeCallbacks(timer); timer = null; }
        if (sip != null) sip.removeListener(this);
        super.onDestroy();
    }
}

