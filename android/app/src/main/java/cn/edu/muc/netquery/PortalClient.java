package cn.edu.muc.netquery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校园网认证计费系统客户端（纯 Java，无安卓依赖，可独立测试）
 * 适配：深澜认证计费系统 v4.5.3
 */
public class PortalClient {

    public static final String BASE = "http://192.168.2.231:8900";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final int TIMEOUT = 20000;

    /** cookie 名值对（手工维护，避免依赖 CookieManager） */
    private final LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
    private String username = "";

    public interface Progress {
        void onProgress(int current, int total);
    }

    // ------------------------------------------------------------------ 会话
    public String getUsername() {
        return username;
    }

    public String cookiesToString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public void cookiesFromString(String s) {
        cookies.clear();
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) cookies.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
        }
    }

    public void setUsername(String u) {
        this.username = u;
    }

    // ------------------------------------------------------------------ HTTP
    /**
     * 手动处理重定向：HttpURLConnection 遇到 302 会把 POST 原样重发到新地址，
     * 会导致登录（Yii2 的登录后跳转）失败，所以这里自己跟跳转。
     */
    private byte[] request(String url, String method, String postBody) throws IOException {
        String curUrl = url;
        String curMethod = method;
        String curBody = postBody;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(curUrl).openConnection();
            c.setRequestMethod(curMethod);
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setRequestProperty("Referer", BASE + "/");
            if (!cookies.isEmpty()) c.setRequestProperty("Cookie", cookiesToString());
            if (curBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.getOutputStream().write(curBody.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            captureCookies(c);
            String location = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && location != null) {
                c.disconnect();
                curUrl = location.startsWith("http") ? location : BASE + location;
                curMethod = "GET";      // 表单提交后跟跳转一律用 GET
                curBody = null;
                continue;
            }
            InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
            }
            c.disconnect();
            return bos.toByteArray();
        }
        throw new IOException("重定向次数过多");
    }

    private void captureCookies(HttpURLConnection c) {
        Map<String, List<String>> headers = c.getHeaderFields();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                for (String raw : e.getValue()) {
                    String kv = raw.split(";", 2)[0];
                    int i = kv.indexOf('=');
                    if (i > 0) cookies.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
                }
            }
        }
    }

    private String getText(String url) throws IOException {
        return new String(request(url, "GET", null), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 登录
    /** @return [csrf, captchaUrl] */
    public String[] fetchLoginPage() throws IOException {
        String html = getText(BASE + "/login");
        Matcher m = Pattern.compile("name=\"_csrf-8800\"\\s+value=\"([^\"]+)\"").matcher(html);
        String csrf = m.find() ? m.group(1) : "";
        Matcher cm = Pattern.compile("id=\"loginform-verifycode-image\"[^>]*src=\"([^\"]+)\"")
                .matcher(html);
        String capUrl = cm.find() ? cm.group(1) : "/site/captcha";
        if (capUrl.startsWith("/")) capUrl = BASE + capUrl;
        return new String[]{csrf, capUrl};
    }

    public byte[] fetchCaptcha(String url) throws IOException {
        return request(url, "GET", null);
    }

    public boolean login(String user, String pass, String code, String csrf) throws IOException {
        String body = "_csrf-8800=" + enc(csrf)
                + "&LoginForm%5Busername%5D=" + enc(user)
                + "&LoginForm%5Bpassword%5D=" + enc(pass)
                + "&LoginForm%5BverifyCode%5D=" + enc(code);
        String html = new String(request(BASE + "/login", "POST", body), StandardCharsets.UTF_8);
        boolean ok = !html.contains("loginform-username");
        if (ok) username = user;
        return ok;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isLoggedIn() {
        try {
            String html = getText(BASE + "/log/detail?page=1&per-page=10");
            return !html.contains("loginform-username") && html.contains("上网明细");
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 设备备注
    /** 读取门户「无感知认证」页里用户给 MAC 填的备注名 */
    public LinkedHashMap<String, String> fetchBoundDevices() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try {
            String html = getText(BASE + "/user/mac-auth");
            Pattern tr = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
            Pattern cell = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL);
            Matcher mtr = tr.matcher(html);
            while (mtr.find()) {
                List<String> cells = new ArrayList<>();
                Matcher mc = cell.matcher(mtr.group(1));
                while (mc.find()) {
                    String t = mc.group(1).replaceAll("<[^>]+>", " ")
                            .replace("&nbsp;", " ").replace("&times;", " ").trim();
                    cells.add(t);
                }
                String mac = null;
                for (String c : cells) {
                    if (c.matches("(?i)^[0-9a-f:]{17}$")) { mac = c.toLowerCase(); break; }
                }
                if (mac == null || mac.equals("11:22:33:44:55:66")) continue;
                for (String c : cells) {
                    if (c.equals(mac) || c.matches("^\\d+$")) continue;
                    String cleaned = c.split("编辑")[0].replaceAll("\\s+", " ").trim();
                    if (!cleaned.isEmpty() && !cleaned.contains("未设置") && !cleaned.equals("备注")) {
                        out.put(mac, cleaned);
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ 明细
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL);

    public List<Session> fetchLogs(String start, String end, Progress progress) throws IOException {
        List<Session> all = new ArrayList<>();
        int page = 1, total = -1;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        while (page <= 400) {
            String url = BASE + "/log/detail?add_time=" + start + "&drop_time=" + end
                    + "&page=" + page + "&per-page=10";
            String html = getText(url);
            if (html.contains("loginform-username")) throw new IOException("登录状态已失效");
            if (total < 0) {
                Matcher tm = Pattern.compile("共\\s*(\\d+)\\s*条").matcher(html.replaceAll("<[^>]+>", " "));
                if (tm.find()) total = Integer.parseInt(tm.group(1));
            }
            int found = 0;
            Matcher mr = ROW.matcher(html);
            while (mr.find()) {
                List<String> cells = new ArrayList<>();
                Matcher mc = CELL.matcher(mr.group(1));
                while (mc.find()) {
                    cells.add(mc.group(1).replaceAll("<[^>]+>", "")
                            .replace("&nbsp;", " ").trim());
                }
                if (cells.size() < 5) continue;
                if (!cells.get(0).matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$")) continue;
                try {
                    Session s = new Session();
                    s.on = sdf.parse(cells.get(0));
                    s.off = sdf.parse(cells.get(1));
                    s.mac = cells.get(2).toLowerCase();
                    s.ip = cells.get(3);
                    s.bytes = parseLong(cells.get(4));
                    s.dur = cells.size() > 6 ? parseLong(cells.get(6))
                            : (s.off.getTime() - s.on.getTime()) / 1000;
                    all.add(s);
                    found++;
                } catch (Exception ignored) {
                }
            }
            if (progress != null) progress.onProgress(all.size(), total > 0 ? total : all.size());
            if (found < 10) break;
            page++;
        }
        return all;
    }

    private static long parseLong(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (Character.isDigit(c)) sb.append(c);
        try {
            return sb.length() == 0 ? 0 : Long.parseLong(sb.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 数据模型
    public static class Session {
        public Date on, off;
        public String mac, ip;
        public long bytes, dur;
    }
}
