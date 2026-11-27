package cn.arch.dmr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Server-side NRL account registration, matching the official NRL /user/reg/create form. */
public class NrlRegisterActivity extends Activity {
    private Prefs p;
    private NrlServers.Server server;
    private EditText callsign, name, phone, password, address, email;
    private TextView licenseName, status;
    private Uri licenseUri;
    private int BG, CARD, TXT, SUB, ACCENT;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        server = NrlServers.find(this, p.nrlServerKey());
        theme();
        setContentView(build());
    }

    private void theme() {
        if (p.dark()) {
            BG = 0xFF0E1319; CARD = 0xFF1A222C; TXT = 0xFFEDF1F7; SUB = 0xFF98A4B3; ACCENT = 0xFF5B9BFF;
        } else {
            BG = 0xFFF2F4F7; CARD = 0xFFFFFFFF; TXT = 0xFF111827; SUB = 0xFF6B7280; ACCENT = 0xFF2563EB;
        }
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, int size, int color) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setIncludeFontPadding(false);
        return v;
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setTextColor(TXT); e.setHintTextColor(SUB); e.setInputType(type); e.setSingleLine(true);
        return e;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD); g.setCornerRadius(dp(16));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12)); c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout row(String label, View value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        TextView l = tv(label, 13, SUB); l.setMinWidth(dp(86)); r.addView(l);
        r.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextSize(13.5f); b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable(); g.setColor(ACCENT); g.setCornerRadius(dp(999));
        b.setBackground(g); b.setMinHeight(dp(46)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        return b;
    }

    private View build() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(28)); sv.addView(root);

        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("返回"); back.setTextColor(ACCENT); back.setBackgroundColor(Color.TRANSPARENT); back.setOnClickListener(v -> finish()); head.addView(back);
        TextView title = tv("NRL 服务器注册", 20, TXT); title.setTypeface(null, Typeface.BOLD); title.setPadding(dp(10), 0, 0, 0); head.addView(title);
        root.addView(head);

        TextView serverView = tv("注册到：" + server.name + " · " + server.host, 11, SUB);
        serverView.setPadding(dp(4), dp(8), dp(4), dp(10)); root.addView(serverView);

        LinearLayout form = card();
        callsign = input("5-6 位大写呼号", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        name = input("姓名", InputType.TYPE_CLASS_TEXT);
        phone = input("手机号", InputType.TYPE_CLASS_PHONE);
        password = input("密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        address = input("地址", InputType.TYPE_CLASS_TEXT);
        email = input("邮箱", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(row("呼号", callsign)); form.addView(row("姓名", name)); form.addView(row("手机号", phone));
        form.addView(row("密码", password)); form.addView(row("地址", address)); form.addView(row("邮箱", email));
        root.addView(form);

        LinearLayout lic = card();
        TextView licTitle = tv("操作证 / 电台执照", 15, TXT); licTitle.setTypeface(null, Typeface.BOLD); lic.addView(licTitle);
        licenseName = tv("未选择照片", 12, SUB); licenseName.setPadding(0, dp(8), 0, dp(8)); lic.addView(licenseName);
        Button choose = button("选择照片"); choose.setOnClickListener(v -> pickLicense()); lic.addView(choose);
        root.addView(lic);

        status = tv("", 12, SUB); status.setPadding(dp(4), dp(2), dp(4), dp(8)); root.addView(status);
        Button submit = button("提交注册"); submit.setOnClickListener(v -> submit()); root.addView(submit);
        return sv;
    }

    private void pickLicense() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "选择操作证照片"), 20);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 20 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            licenseUri = data.getData();
            String n = licenseUri.getLastPathSegment();
            try {
                android.database.Cursor c = getContentResolver().query(licenseUri, null, null, null, null);
                if (c != null) { int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0 && c.moveToFirst()) n = c.getString(idx); c.close(); }
            } catch (Throwable ignored) {}
            licenseName.setText(n == null ? "已选择照片" : n);
        }
    }

    private String text(EditText e) { return e == null ? "" : e.getText().toString().trim(); }

    private void submit() {
        String cs = text(callsign).toUpperCase(java.util.Locale.US).replaceAll("[^A-Z0-9]", "");
        String nm = text(name), ph = text(phone), pw = text(password), ad = text(address), em = text(email);
        if (!cs.matches("[A-Z0-9]{5,6}")) { toast("呼号必须是 5-6 位大写字母或数字"); return; }
        if (nm.isEmpty()) { toast("请输入姓名"); return; }
        if (!ph.matches("\\d{11,}")) { toast("请输入 11 位以上手机号"); return; }
        if (pw.isEmpty()) { toast("请输入密码"); return; }
        if (ad.isEmpty()) { toast("请输入地址"); return; }
        if (!em.matches("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}")) { toast("请输入有效邮箱"); return; }
        if (licenseUri == null) { toast("请上传操作证和电台执照"); return; }
        byte[] image;
        try { image = readBytes(licenseUri); } catch (Throwable t) { toast("读取照片失败"); return; }
        if (image == null || image.length == 0) { toast("照片为空"); return; }
        status.setText("正在提交注册…"); status.setTextColor(ACCENT);
        final String fcs = cs, fnm = nm, fph = ph, fpw = pw, fad = ad, fem = em;
        final byte[] fimg = image;
        new Thread(() -> doSubmit(fcs, fnm, fph, fpw, fad, fem, fimg), "nrl-register").start();
    }

    private byte[] readBytes(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return out.toByteArray();
    }

    private void doSubmit(String cs, String nm, String ph, String pw, String ad, String em, byte[] image) {
        MediaType media = MediaType.parse("image/jpeg");
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("callsign", cs)
                .addFormDataPart("name", nm)
                .addFormDataPart("phone", ph)
                .addFormDataPart("password", pw)
                .addFormDataPart("address", ad)
                .addFormDataPart("mail", em)
                .addFormDataPart("license", "license.jpg", RequestBody.create(image, media))
                .build();
        String lastError = "服务器无响应";
        for (String base : apiBases()) {
            try {
                OkHttpClient c = new OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build();
                Request req = new Request.Builder().url(base + "/user/reg/create").post(body).build();
                Response r = c.newCall(req).execute();
                String text = r.body() == null ? "" : r.body().string();
                int code = r.code();
                if (code >= 200 && code < 300) {
                    int business = 0; String err = "";
                    try { org.json.JSONObject j = new org.json.JSONObject(text); business = j.optInt("code", 0); err = j.optString("error", ""); } catch (Throwable ignored) {}
                    if (business == 20000 || code == 200 || code == 201) {
                        p.set("nrlUseGlobal", "0");
                        p.set("nrlCallsign", cs);
                        if (p.dmrId() > 0) p.set("nrlDmrId", String.valueOf(p.dmrId()));
                        p.set("nrlSsid", "1");
                        p.setNrlPassword(pw);
                        ui(text, true, "注册成功，请等待管理员审核");
                        return;
                    }
                    lastError = err.isEmpty() ? text : err;
                } else if (code != 404) {
                    lastError = "HTTP " + code + (text.isEmpty() ? "" : " · " + text);
                }
            } catch (Throwable t) { lastError = t.getMessage() == null ? t.toString() : t.getMessage(); }
        }
        ui("", false, "注册失败：" + lastError);
    }

    private List<String> apiBases() {
        ArrayList<String> out = new ArrayList<>();
        String h = server.host.trim();
        if (h.startsWith("http://") || h.startsWith("https://")) {
            out.add(h.replaceAll("/+$", ""));
        } else if (h.matches(".*:[0-9]+$")) {
            out.add("https://" + h);
            out.add("http://" + h);
        } else {
            out.add("https://" + h + ":443");
            out.add("http://" + h + ":9000");
        }
        return out;
    }

    private void ui(String ignored, boolean ok, String msg) {
        runOnUiThread(() -> { status.setText(msg); status.setTextColor(ok ? 0xFF22C55E : 0xFFDC2626); toast(msg); if (ok) finish(); });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}