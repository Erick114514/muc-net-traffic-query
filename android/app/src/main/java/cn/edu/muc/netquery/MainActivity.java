package cn.edu.muc.netquery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

/** 主界面：输入账号密码 → 查询 → 生成报告 */
public class MainActivity extends Activity {

    private EditText edUser, edPwd, edQuota;
    private Spinner spRange;
    private Button btnQuery, btnNames, btnLast, btnLogout;
    private ProgressBar bar;
    private TextView status, logView;

    private final PortalClient client = new PortalClient();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<String> captchaInput = new ArrayBlockingQueue<>(1);

    private SharedPreferences prefs;
    private LinkedHashMap<String, String> deviceNames = new LinkedHashMap<>();
    private LinkedHashMap<String, Model.Device> lastDevices = null;
    private File lastReport = null;
    private int dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = (int) getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("muc", MODE_PRIVATE);
        buildUi();

        edUser.setText(prefs.getString("username", ""));
        edQuota.setText(prefs.getString("quota", "70"));
        client.cookiesFromString(prefs.getString("cookies", ""));
        client.setUsername(prefs.getString("username", ""));
        loadDeviceNames();

        if (!client.cookiesToString().isEmpty()) {
            say("检测到上次登录状态，可直接查询");
            new Thread(() -> {
                boolean ok = client.isLoggedIn();
                ui.post(() -> {
                    if (ok) setStatus("已登录，可直接查询");
                    else {
                        setStatus("登录状态已过期，查询时需输入验证码");
                    }
                });
            }).start();
        } else {
            setStatus("首次使用：输入学号密码后点「开始查询」");
        }
    }

    // ------------------------------------------------------------- 界面构建
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = 14 * dp;
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        TextView h1 = new TextView(this);
        h1.setText("民大校园网流量查询");
        h1.setTextSize(21);
        h1.setGravity(Gravity.CENTER);
        root.addView(h1);

        TextView h2 = new TextView(this);
        h2.setText("按设备统计流量，生成可视化报告");
        h2.setTextSize(12);
        h2.setTextColor(0xff7b8794);
        h2.setGravity(Gravity.CENTER);
        h2.setPadding(0, 4 * dp, 0, 12 * dp);
        root.addView(h2);

        root.addView(label("学号（账号）"));
        edUser = new EditText(this);
        edUser.setHint("请输入学号");
        edUser.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(edUser);

        root.addView(label("密码"));
        edPwd = new EditText(this);
        edPwd.setHint("校园网密码");
        edPwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(edPwd);

        root.addView(label("统计区间"));
        spRange = new Spinner(this);
        String[] ranges = {"本月", "上月", "最近7天", "最近30天"};
        spRange.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ranges));
        root.addView(spRange);

        root.addView(label("免费额度（G，民大为 70）"));
        edQuota = new EditText(this);
        edQuota.setInputType(InputType.TYPE_CLASS_NUMBER);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        edQuota.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(edQuota);
        root.addView(row1);

        btnQuery = new Button(this);
        btnQuery.setText("开始查询");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = 12 * dp;
        btnQuery.setLayoutParams(bp);
        btnQuery.setOnClickListener(v -> onQuery());
        root.addView(btnQuery);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(0, 8 * dp, 0, 8 * dp);
        btnNames = new Button(this);
        btnNames.setText("给设备命名");
        btnNames.setOnClickListener(v -> openNaming());
        tools.addView(btnNames);
        btnLast = new Button(this);
        btnLast.setText("打开报告");
        btnLast.setOnClickListener(v -> openLastReport());
        tools.addView(btnLast);
        btnLogout = new Button(this);
        btnLogout.setText("退出登录");
        btnLogout.setOnClickListener(v -> logout());
        tools.addView(btnLogout);
        root.addView(tools);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar);

        status = new TextView(this);
        status.setTextSize(12);
        status.setPadding(0, 6 * dp, 0, 6 * dp);
        root.addView(status);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(0xff55606e);
        logView.setBackgroundColor(0xffeeF3f8);
        logView.setPadding(10 * dp, 10 * dp, 10 * dp, 10 * dp);
        root.addView(logView);

        setContentView(scroll);
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setTextColor(0xff55606e);
        v.setPadding(0, 8 * dp, 0, 2 * dp);
        return v;
    }

    // ------------------------------------------------------------- 辅助
    private void say(String s) {
        ui.post(() -> {
            logView.append(s + "\n");
        });
    }

    private void setStatus(String s) {
        ui.post(() -> status.setText(s));
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    private void loadDeviceNames() {
        deviceNames.clear();
        String raw = prefs.getString("devices", "");
        for (String line : raw.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) deviceNames.put(line.substring(0, i), line.substring(i + 1));
        }
    }

    private void saveDeviceNames() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : deviceNames.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        prefs.edit().putString("devices", sb.toString()).apply();
    }

    // ------------------------------------------------------------- 查询
    private void onQuery() {
        final String user = edUser.getText().toString().trim();
        final String pwd = edPwd.getText().toString();
        if (user.isEmpty() || pwd.isEmpty()) {
            toast("请先填写学号和密码");
            return;
        }
        btnQuery.setEnabled(false);
        bar.setProgress(0);
        new Thread(() -> runQuery(user, pwd)).start();
    }

    private void runQuery(String user, String pwd) {
        try {
            if (!ensureLogin(user, pwd)) {
                setStatus("未登录");
                return;
            }
            String mode = spRange.getSelectedItem().toString();
            String[] r = Model.range(mode);
            say("开始抓取 " + r[0] + " ~ " + r[1] + " 的明细…");
            setStatus("正在抓取…");

            List<PortalClient.Session> list;
            try {
                list = client.fetchLogs(r[0], r[1], (cur, tot) -> {
                    int pct = tot > 0 ? (int) (cur * 100L / tot) : 0;
                    ui.post(() -> bar.setProgress(pct));
                    if (cur % 50 == 0) say("  已获取 " + cur + "/" + tot);
                });
            } catch (IOException e) {
                setStatus("抓取失败");
                toast("抓取失败：" + e.getMessage());
                say("✘ " + e.getMessage());
                return;
            }
            if (list.isEmpty()) {
                setStatus("该区间无记录");
                toast("该时间段没有上网记录");
                return;
            }
            say("共 " + list.size() + " 条记录，正在统计…");

            LinkedHashMap<String, String> portalNames = client.fetchBoundDevices();
            LinkedHashMap<String, String> names = new LinkedHashMap<>(portalNames);
            names.putAll(deviceNames);

            lastDevices = Model.aggregate(list);
            long total = 0;
            for (Model.Device d : lastDevices.values()) total += d.bytes;
            say("总流量 " + Model.human(total) + "，设备 " + lastDevices.size() + " 台");
            for (Map.Entry<String, Model.Device> e : Model.sorted(lastDevices)) {
                String nm = names.get(e.getKey());
                say("   " + (nm == null ? "(未命名)" : nm) + "  "
                        + Model.human(e.getValue().bytes) + "  " + e.getValue().sessions + " 次");
            }

            int quota = 70;
            try {
                quota = Integer.parseInt(edQuota.getText().toString().trim());
            } catch (Exception ignored) {
            }
            String html = ReportBuilder.build(list, names, r[0], r[1], quota);
            File out = new File(getFilesDir(),
                    "report_" + new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date()) + ".html");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(html.getBytes("UTF-8"));
            }
            lastReport = out;
            prefs.edit().putString("username", user).putString("quota", String.valueOf(quota))
                    .putString("cookies", client.cookiesToString()).apply();

            setStatus("完成，正在打开报告");
            ui.post(() -> {
                bar.setProgress(100);
                startActivity(new Intent(this, ReportActivity.class)
                        .putExtra("path", out.getAbsolutePath()));
            });

            final List<PortalClient.Session> okList = list;
            ui.post(() -> {
                if (lastDevices != null) {
                    List<String> unnamed = new ArrayList<>();
                    for (String mac : lastDevices.keySet()) {
                        if (!deviceNames.containsKey(mac)) unnamed.add(mac);
                    }
                    if (!unnamed.isEmpty()) openNaming();
                }
            });
        } catch (Exception e) {
            setStatus("出错");
            toast("出错：" + e.getMessage());
            say("✘ " + e);
        } finally {
            ui.post(() -> btnQuery.setEnabled(true));
        }
    }

    /** 确保已登录；需要时弹验证码 */
    private boolean ensureLogin(String user, String pwd) {
        try {
            if (!client.cookiesToString().isEmpty() && client.isLoggedIn()) {
                say("✔ 复用已有登录状态");
                return true;
            }
            String[] lc = client.fetchLoginPage();
            byte[] img = client.fetchCaptcha(lc[1]);
            final android.graphics.Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
            ui.post(() -> showCaptchaDialog(bmp));
            String code = captchaInput.take();
            if (code.isEmpty()) return false;
            setStatus("正在登录…");
            boolean ok = client.login(user, pwd, code, lc[0]);
            if (ok) {
                say("✔ 登录成功");
                prefs.edit().putString("username", user)
                        .putString("cookies", client.cookiesToString()).apply();
                return true;
            }
            toast("登录失败：账号、密码或验证码不正确");
            return false;
        } catch (IOException e) {
            setStatus("连不上校园网");
            String ips = localIps();
            toast("连不上校园网认证平台\n\n手机当前 IP：" + ips
                    + "\n\n请确认：\n1) 已连接 Wi-Fi「MUC-student」\n2) 已通过认证页面登录\n"
                    + "（IP 不是 10.70./10.71. 开头 = 没连上校园网）");
            say("✘ 网络错误，手机当前 IP：" + ips);
            say("   校园网 IP 应为 10.70.x / 10.71.x 开头");
            return false;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** 读取本机当前 IP，用于判断是否在校园网内 */
    private String localIps() {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append(a.getHostAddress()).append("（").append(ni.getName()).append("）");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() == 0 ? "未知" : sb.toString();
    }

    private void showCaptchaDialog(android.graphics.Bitmap bmp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20 * dp, 16 * dp, 20 * dp, 8 * dp);
        TextView t = new TextView(this);
        t.setText("请输入图中的验证码（4 位字母）");
        box.addView(t);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        box.addView(iv);
        EditText ed = new EditText(this);
        ed.setHint("验证码");
        box.addView(ed);
        new AlertDialog.Builder(this)
                .setTitle("验证码")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("确定", (d, w) -> captchaInput.offer(ed.getText().toString().trim()))
                .show();
    }

    // ------------------------------------------------------------- 设备命名
    private void openNaming() {
        if (lastDevices == null || lastDevices.isEmpty()) {
            toast("请先查询一次，再来给设备命名");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16 * dp, 12 * dp, 16 * dp, 12 * dp);
        Map<String, EditText> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, Model.Device> e : Model.sorted(lastDevices)) {
            TextView tv = new TextView(this);
            tv.setText(e.getKey() + "　" + Model.human(e.getValue().bytes));
            tv.setTextSize(12);
            box.addView(tv);
            EditText ed = new EditText(this);
            ed.setHint("如：电脑 / 手机 / iPad");
            String cur = deviceNames.get(e.getKey());
            if (cur != null) ed.setText(cur);
            box.addView(ed);
            inputs.put(e.getKey(), ed);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("给设备命名（只填你要用的）")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    int n = 0;
                    for (Map.Entry<String, EditText> e : inputs.entrySet()) {
                        String v = e.getValue().getText().toString().trim();
                        if (!v.isEmpty()) {
                            deviceNames.put(e.getKey(), v);
                            n++;
                        }
                    }
                    saveDeviceNames();
                    toast("已保存 " + n + " 个设备名，下次查询生效");
                    say("已保存设备命名 " + n + " 个");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openLastReport() {
        if (lastReport == null || !lastReport.exists()) {
            toast("还没有报告，请先查询");
            return;
        }
        startActivity(new Intent(this, ReportActivity.class)
                .putExtra("path", lastReport.getAbsolutePath()));
    }

    private void logout() {
        client.cookiesFromString("");
        prefs.edit().remove("cookies").apply();
        say("已清除登录状态");
        setStatus("已退出登录");
    }
}
