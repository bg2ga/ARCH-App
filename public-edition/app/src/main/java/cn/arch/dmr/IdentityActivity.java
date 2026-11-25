package cn.arch.dmr;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 统一身份设置与注册界面。 */
public class IdentityActivity extends Activity {
    private static final String ACCOUNT_API = "https://hljrsa.cn:50113/api/account/";
    private static final String ACCOUNT_API_LEGACY = "http://hljrsa.cn:50110/api/account/";
    private static final String ACCOUNT_API_OVERSEAS = "https://arch-overseas.pages.dev/api/account/";

    private Prefs p;
    private int BG, CARD, TXT, SUB, BLUE, GREEN, RED;

    private EditText callsignField, dmrIdField, essidField, assocPassField, bmPassField, nxdnIdField;
    private EditText dmr860IdField, dmr860EssidField, dmr860PassField;
    private EditText dmr46020IdField, dmr46020EssidField, dmr46020PassField;
    private TextView statusView;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        applyTheme();
        setContentView(build());
        refresh();
    }

    private void applyTheme() {
        if (p.dark()) {
            BG = 0xFF0E1319; CARD = 0xFF1A222C; TXT = 0xFFEDF1F7; SUB = 0xFF98A4B3;
            BLUE = 0xFF5B9BFF; GREEN = 0xFF34D399; RED = 0xFFFF6B6B;
        } else {
            BG = 0xFFF2F4F7; CARD = 0xFFFFFFFF; TXT = 0xFF111827; SUB = 0xFF6B7280;
            BLUE = 0xFF2563EB; GREEN = 0xFF22C55E; RED = 0xFFDC2626;
        }
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, int size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView title(String s) {
        TextView t = tv(s, 16, TXT);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private EditText input(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setTextColor(TXT);
        e.setHint(hint);
        e.setHintTextColor(SUB);
        e.setInputType(type);
        e.setText(value == null ? "" : value);
        e.setSingleLine(true);
        return e;
    }

    private LinearLayout row(String label, View value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        TextView l = tv(label, 13, SUB);
        l.setMinWidth(dp(108));
        r.addView(l);
        r.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13.5f);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(BLUE);
        g.setCornerRadius(dp(999));
        b.setBackground(g);
        b.setMinHeight(dp(46));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        return b;
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(28));
        sv.addView(root);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("返回设置");
        back.setTextColor(BLUE);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView h = tv("身份设置与注册", 20, TXT);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(dp(10), 0, 0, 0);
        head.addView(h);
        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = tv("首次使用请填写资料并注册；换手机或重装 APP 后，在最下方点击“恢复已注册身份”。", 11, SUB);
        intro.setPadding(0, dp(8), 0, dp(10));
        root.addView(intro);

        LinearLayout identity = card();
        identity.addView(title("统一身份资料"));
        TextView note = tv("D-Star、YSF、P25 使用统一呼号；NXDN 使用统一呼号和独立 NXDN ID；DMR/BM 使用统一呼号、DMR ID 和 ESSID，BM 密码单独填写。", 11, SUB);
        note.setPadding(0, 0, 0, dp(8));
        identity.addView(note);

        callsignField = input("业余无线电呼号", p.callsign(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        dmrIdField = input("选填，例如 4600111", p.dmrId() > 0 ? String.valueOf(p.dmrId()) : "", InputType.TYPE_CLASS_NUMBER);
        essidField = input("选填，00-99；未填按 00", p.dmrId() > 0 ? String.format(Locale.US, "%02d", p.essid()) : "", InputType.TYPE_CLASS_NUMBER);
        assocPassField = input("协会服务器密码（至少 6 位）", p.password(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        bmPassField = input("选填，BM 热点密码", p.bmPassword(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        nxdnIdField = input("选填，NXDN ID（1-65535）", p.nxdnId() > 0 ? String.valueOf(p.nxdnId()) : "", InputType.TYPE_CLASS_NUMBER);
        dmr860IdField = input("860 后台分配的 7 位 DMR ID", p.dmr860Id() > 0 ? String.valueOf(p.dmr860Id()) : "", InputType.TYPE_CLASS_NUMBER);
        dmr860EssidField = input("00-99；未填按 00", p.dmr860Id() > 0 ? String.format(Locale.US, "%02d", p.dmr860Essid()) : "", InputType.TYPE_CLASS_NUMBER);
        dmr860PassField = input("860 后台设置的“热点密码”", p.dmr860Password(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        dmr46020IdField = input("46020 分配的 7 位 DMR ID", p.dmr46020Id() > 0 ? String.valueOf(p.dmr46020Id()) : "", InputType.TYPE_CLASS_NUMBER);
        dmr46020EssidField = input("00-99；未填按 00", p.dmr46020Id() > 0 ? String.format(Locale.US, "%02d", p.dmr46020Essid()) : "", InputType.TYPE_CLASS_NUMBER);
        dmr46020PassField = input("46020 连接密码", p.dmr46020Password(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        identity.addView(row("呼号", callsignField));
        identity.addView(row("DMR ID", dmrIdField));
        identity.addView(row("ESSID", essidField));
        identity.addView(row("协会密码", assocPassField));
        identity.addView(row("BM 密码", bmPassField));
        identity.addView(row("NXDN ID", nxdnIdField));

        statusView = tv("状态: 未注册或未登录", 11, SUB);
        statusView.setPadding(0, dp(8), 0, dp(8));
        identity.addView(statusView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button register = button("注册 / 保存身份");
        Button restore = button("恢复已注册身份");
        register.setOnClickListener(v -> registerIdentity());
        restore.setOnClickListener(v -> restoreIdentity());
        actions.addView(register, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(restore, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        identity.addView(actions);
        root.addView(identity);

        LinearLayout dmr860Card = card();
        dmr860Card.addView(title("860国服登录（可选）"));
        TextView dmr860Note = tv("只给有 860/CDN 账号的用户填写。没有 860 账号可全部留空，不影响 46020 或 BM。服务器：mastra.radiowo.com（183.230.184.76）:62031。密码是 860.radiowo.com 后台设置的“热点密码”。", 11, SUB);
        dmr860Note.setPadding(0, 0, 0, dp(8));
        dmr860Card.addView(dmr860Note);
        dmr860Card.addView(row("860 DMR ID", dmr860IdField));
        dmr860Card.addView(row("860 ESSID", dmr860EssidField));
        dmr860Card.addView(row("860 热点密码", dmr860PassField));
        Button save860 = button("保存 860 国服登录");
        save860.setOnClickListener(v -> saveDmr860Only());
        LinearLayout.LayoutParams save860Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        save860Lp.topMargin = dp(10);
        dmr860Card.addView(save860, save860Lp);
        root.addView(dmr860Card);

        LinearLayout dmr46020Card = card();
        dmr46020Card.addView(title("46020广州登录（可选）"));
        TextView dmr46020Note = tv("只给有 46020 广州账号的用户填写。没有 46020 账号可全部留空，不影响 860 或 BM。服务器：46020.link（8.163.29.23）:62031。连接密码需向 BD7PET 单独申请。", 11, SUB);
        dmr46020Note.setPadding(0, 0, 0, dp(8));
        dmr46020Card.addView(dmr46020Note);
        dmr46020Card.addView(row("46020 DMR ID", dmr46020IdField));
        dmr46020Card.addView(row("46020 ESSID", dmr46020EssidField));
        dmr46020Card.addView(row("46020 连接密码", dmr46020PassField));
        Button save46020 = button("保存 46020 广州登录");
        save46020.setOnClickListener(v -> saveDmr46020Only());
        LinearLayout.LayoutParams save46020Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        save46020Lp.topMargin = dp(10);
        dmr46020Card.addView(save46020, save46020Lp);
        root.addView(dmr46020Card);

        return sv;
    }

    private void refresh() {
        callsignField.setText(p.callsign());
        dmrIdField.setText(p.dmrId() > 0 ? String.valueOf(p.dmrId()) : "");
        essidField.setText(p.dmrId() > 0 ? String.format(Locale.US, "%02d", p.essid()) : "");
        assocPassField.setText(p.password());
        bmPassField.setText(p.bmPassword());
        nxdnIdField.setText(p.nxdnId() > 0 ? String.valueOf(p.nxdnId()) : "");
        dmr860IdField.setText(p.dmr860Id() > 0 ? String.valueOf(p.dmr860Id()) : "");
        dmr860EssidField.setText(p.dmr860Id() > 0 ? String.format(Locale.US, "%02d", p.dmr860Essid()) : "");
        dmr860PassField.setText(p.dmr860Password());
        dmr46020IdField.setText(p.dmr46020Id() > 0 ? String.valueOf(p.dmr46020Id()) : "");
        dmr46020EssidField.setText(p.dmr46020Id() > 0 ? String.format(Locale.US, "%02d", p.dmr46020Essid()) : "");
        dmr46020PassField.setText(p.dmr46020Password());
    }

    private String text(EditText e) { return e == null ? "" : e.getText().toString().trim(); }
    private int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return 0; } }

    private boolean validate(boolean restore) {
        String cs = text(callsignField).toUpperCase(Locale.US);
        String pw = text(assocPassField);
        if (cs.length() < 3 || cs.length() > 12) {
            toast("请填写正确的业余无线电呼号");
            return false;
        }
        if (pw.length() < 6) {
            toast("协会服务器密码至少 6 位");
            return false;
        }
        String rawId = text(dmrIdField);
        if (!rawId.isEmpty() && !rawId.matches("\\d{7}")) {
            toast("DMR ID 如果填写，必须是 7 位数字");
            return false;
        }
        String rawEssid = text(essidField);
        if (!rawEssid.isEmpty()) {
            int essid = parseInt(rawEssid);
            if (essid < 0 || essid > 99) {
                toast("ESSID 必须在 00-99 之间");
                return false;
            }
        }
        String rawNxdn = text(nxdnIdField);
        if (!rawNxdn.isEmpty()) {
            int nxdnId = parseInt(rawNxdn);
            if (nxdnId < 1 || nxdnId > 65535) {
                toast("NXDN ID 必须在 1-65535 之间");
                return false;
            }
        }
        if (!restore && text(bmPassField).length() > 0 && text(bmPassField).length() < 4) {
            toast("BM 密码如果填写，至少 4 位");
            return false;
        }
        return validateDmrServerFields();
    }

    private boolean validateDmrServerFields() {
        if (!validateOneDmrServer("860", dmr860IdField, dmr860EssidField, dmr860PassField)) return false;
        return validateOneDmrServer("46020", dmr46020IdField, dmr46020EssidField, dmr46020PassField);
    }

    private boolean validateOneDmrServer(String label, EditText idField, EditText essidField, EditText passField) {
        String id = text(idField), es = text(essidField), pw = text(passField);
        boolean used = !id.isEmpty() || !es.isEmpty() || !pw.isEmpty();
        if (!used) return true;
        if (!id.matches("\\d{7}")) {
            toast(label + " DMR ID 必须是 7 位数字");
            return false;
        }
        if (!es.isEmpty()) {
            int v = parseInt(es);
            if (v < 0 || v > 99) {
                toast(label + " ESSID 必须在 00-99 之间");
                return false;
            }
        }
        if (pw.length() < 6) {
            toast(label + " 密码至少 6 位");
            return false;
        }
        return true;
    }

    private void saveDmrServersLocal() {
        p.saveDmrServerIdentities(parseInt(text(dmr860IdField)), parseInt(text(dmr860EssidField)), text(dmr860PassField),
                parseInt(text(dmr46020IdField)), parseInt(text(dmr46020EssidField)), text(dmr46020PassField));
    }

    private void saveDmr860Only() {
        if (!validateOneDmrServer("860", dmr860IdField, dmr860EssidField, dmr860PassField)) return;
        saveDmrServersLocal();
        toast("860 国服登录信息已保存");
    }

    private void saveDmr46020Only() {
        if (!validateOneDmrServer("46020", dmr46020IdField, dmr46020EssidField, dmr46020PassField)) return;
        saveDmrServersLocal();
        toast("46020 广州登录信息已保存");
    }

    private void registerIdentity() {
        if (!validate(false)) return;
        String cs = text(callsignField).toUpperCase(Locale.US);
        int dmrId = parseInt(text(dmrIdField));
        int essid = Math.max(0, Math.min(99, parseInt(text(essidField))));
        int nxdnId = parseInt(text(nxdnIdField));
        String assocPw = text(assocPassField);
        String bmPw = text(bmPassField);

        statusView.setText("状态: 正在注册 / 保存…");
        statusView.setTextColor(BLUE);
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("callsign", cs);
                body.put("dmrId", dmrId);
                body.put("essid", dmrId > 0 ? essid : 0);
                body.put("password", assocPw);
                body.put("bmPassword", bmPw);
                body.put("nxdnId", nxdnId);
                JSONObject response = postJson("register", body);
                JSONObject account = response.optJSONObject("account");
                runOnUiThread(() -> {
                    p.saveUnifiedIdentity(cs, dmrId, dmrId > 0 ? essid : 0, assocPw, bmPw, nxdnId);
                    if (account != null) applyAccount(account, cs, dmrId, dmrId > 0 ? essid : 0, assocPw, bmPw, nxdnId);
                    saveDmrServersLocal();
                    statusView.setText("状态: 已注册并保存");
                    statusView.setTextColor(GREEN);
                    toast("身份已注册并保存");
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText("状态: 注册失败 · " + safeMessage(e));
                    statusView.setTextColor(RED);
                    toast("注册失败: " + safeMessage(e));
                });
            }
        }, "identity-register").start();
    }

    private void restoreIdentity() {
        if (!validate(true)) return;
        String cs = text(callsignField).toUpperCase(Locale.US);
        int dmrId = parseInt(text(dmrIdField));
        int essid = Math.max(0, Math.min(99, parseInt(text(essidField))));
        String assocPw = text(assocPassField);
        String bmPw = text(bmPassField);
        int nxdnId = parseInt(text(nxdnIdField));

        statusView.setText("状态: 正在恢复已注册身份…");
        statusView.setTextColor(BLUE);
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("callsign", cs);
                body.put("dmrId", dmrId);
                body.put("essid", essid);
                body.put("password", assocPw);
                JSONObject response = postJson("login", body);
                JSONObject account = response.optJSONObject("account");
                if (account == null) throw new Exception("服务器没有返回账号资料");
                runOnUiThread(() -> {
                    applyAccount(account, cs, dmrId, essid, assocPw, bmPw, nxdnId);
                    saveDmrServersLocal();
                    statusView.setText("状态: 恢复成功，身份已保存");
                    statusView.setTextColor(GREEN);
                    toast("已恢复注册身份");
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText("状态: 恢复失败 · " + safeMessage(e));
                    statusView.setTextColor(RED);
                    toast("恢复失败: " + safeMessage(e));
                });
            }
        }, "identity-restore").start();
    }

    private void applyAccount(JSONObject account, String fallbackCall, int fallbackDmrId, int fallbackEssid,
                              String assocPw, String fallbackBmPw, int fallbackNxdnId) {
        String cs = account.optString("callsign", fallbackCall).trim().toUpperCase(Locale.US);
        if (cs.isEmpty()) cs = fallbackCall;
        int id = account.has("dmrId") ? account.optInt("dmrId", fallbackDmrId) : fallbackDmrId;
        int es = account.has("essid") ? account.optInt("essid", fallbackEssid) : fallbackEssid;
        String bmPw = account.has("bmPassword") ? account.optString("bmPassword", fallbackBmPw) : fallbackBmPw;
        int nxdnId = account.has("nxdnId") ? account.optInt("nxdnId", fallbackNxdnId) : fallbackNxdnId;
        p.saveUnifiedIdentity(cs, Math.max(0, id), Math.max(0, Math.min(99, es)), assocPw, bmPw,
                nxdnId > 0 && nxdnId <= 65535 ? nxdnId : 0);
    }

    private JSONObject postJson(String operation, JSONObject body) throws Exception {
        String[] bases = BuildConfig.OVERSEAS_EDITION
                ? new String[]{ACCOUNT_API_OVERSEAS}
                : new String[]{ACCOUNT_API, ACCOUNT_API_LEGACY};
        Exception last = null;
        for (int i = 0; i < bases.length; i++) {
            try {
                return postJsonAt(bases[i], operation, body);
            } catch (Exception e) {
                last = e;
                boolean retry = i < bases.length - 1 && isTransportFailure(e);
                android.util.Log.w("ARCH-Identity", "account API failed at " + bases[i]
                        + " op=" + operation + " retry=" + retry + ": " + e);
                if (!retry) throw e;
            }
        }
        throw last == null ? new Exception("账号接口不可用") : last;
    }

    private JSONObject postJsonAt(String base, String operation, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(base + operation).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Connection", "close");
            c.setRequestProperty("User-Agent", "ARCH-Android/" + BuildConfig.VERSION_NAME);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
            if (code >= 400 || !j.optBoolean("ok", false)) {
                throw new AccountApiException(j.optString("error", "HTTP " + code));
            }
            return j;
        } finally {
            c.disconnect();
        }
    }

    private boolean isTransportFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.io.IOException || current instanceof org.json.JSONException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static class AccountApiException extends Exception {
        AccountApiException(String message) { super(message); }
    }

    private String safeMessage(Exception e) {
        String s = e.getMessage();
        return s == null || s.trim().isEmpty() ? e.getClass().getSimpleName() : s;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}