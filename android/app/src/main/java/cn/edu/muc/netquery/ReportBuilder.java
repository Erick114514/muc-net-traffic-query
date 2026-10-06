package cn.edu.muc.netquery;

import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生成手机端 HTML 报告（内联 SVG 图表，WebView 直接渲染；纯 Java） */
public class ReportBuilder {

    private static final String[] PALETTE = {
            "#378ADD", "#639922", "#BA7517", "#993556", "#534AB7", "#0F6E56"};

    public static String build(List<PortalClient.Session> sessions,
                               Map<String, String> names,
                               String start, String end,
                               int quotaGb) {
        LinkedHashMap<String, Model.Device> dev = Model.aggregate(sessions);
        long total = 0;
        for (Model.Device d : dev.values()) total += d.bytes;
        List<Map.Entry<String, Model.Device>> sorted = Model.sorted(dev);

        StringBuilder cards = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Model.Device> e : sorted) {
            Model.Device d = e.getValue();
            String name = names.get(d.mac);
            if (name == null || name.isEmpty()) name = Model.guess(d, total);
            String color = PALETTE[i++ % PALETTE.length];
            cards.append("<div class='card'>")
                    .append("<div class='chead'><span class='dot' style='background:").append(color).append("'></span>")
                    .append("<span class='dname'>").append(esc(name)).append("</span>")
                    .append("<span class='mac'>").append(d.mac).append("</span></div>")
                    .append("<div class='big'>").append(Model.human(d.bytes)).append("</div>")
                    .append("<div class='muted'>占总流量 ").append(String.format("%.1f%%", d.share(total) * 100)).append("</div>")
                    .append("<div class='stats'>")
                    .append("<span><b>").append(d.sessions).append("</b> 次会话</span>")
                    .append("<span>在线 <b>").append(Model.fmtDur(d.dur)).append("</b></span>")
                    .append("<span>活跃 <b>").append(d.days.size()).append("</b> 天</span>")
                    .append("<span>IP <b>").append(d.ips.size()).append("</b> 个</span>")
                    .append("</div>")
                    .append("<div class='sub'>活跃时段（按流量）</div>")
                    .append(bars(d.hours, 320, 60, color))
                    .append("<div class='sub'>单次流量 Top3</div><ul class='tops'>");
            d.list.sort((a, b) -> Long.compare(b.bytes, a.bytes));
            int shown = 0;
            SimpleDateFormat hm = new SimpleDateFormat("MM-dd HH:mm");
            SimpleDateFormat hms = new SimpleDateFormat("HH:mm");
            for (PortalClient.Session s : d.list) {
                if (s.bytes <= 0 || shown >= 3) continue;
                cards.append("<li>").append(hm.format(s.on)).append(" → ").append(hms.format(s.off))
                        .append("　<b>").append(Model.human(s.bytes)).append("</b>　")
                        .append(Model.fmtDur(s.dur)).append("</li>");
                shown++;
            }
            if (shown == 0) cards.append("<li class='muted'>无有效流量会话</li>");
            cards.append("</ul>")
                    .append("<div class='muted'>首次 ").append(hm.format(d.first))
                    .append(" ・ 最近 ").append(hm.format(d.last)).append("</div>")
                    .append("</div>");
        }

        // 每日图
        java.util.TreeMap<String, long[]> daily = Model.daily(sessions);
        long[] dv = new long[daily.size()];
        String[] dl = new String[daily.size()];
        int k = 0;
        for (Map.Entry<String, long[]> e : daily.entrySet()) {
            dv[k] = e.getValue()[0];
            dl[k] = e.getKey().substring(5).replace('-', '/');
            k++;
        }

        long quota = (long) quotaGb * 1024 * 1024 * 1024;
        long over = Math.max(0, total - quota);
        String overText = over > 0
                ? "超出 " + Model.human(over) + "（约 " + (over / 1024 / 1024 / 1024) + " 元）"
                : "额度内";

        // 明细
        StringBuilder rows = new StringBuilder();
        sessions.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        SimpleDateFormat full = new SimpleDateFormat("MM-dd HH:mm:ss");
        Map<String, String> nameByMac = new LinkedHashMap<>();
        for (Map.Entry<String, Model.Device> e : sorted) {
            String n = names.get(e.getKey());
            nameByMac.put(e.getKey(), (n == null || n.isEmpty()) ? Model.guess(e.getValue(), total) : n);
        }
        for (PortalClient.Session s : sessions) {
            if (s.bytes <= 0) continue;
            rows.append("<tr><td>").append(full.format(s.on)).append("</td><td>")
                    .append(full.format(s.off)).append("</td><td>")
                    .append(esc(nameByMac.getOrDefault(s.mac, s.mac))).append("</td><td>")
                    .append(Model.human(s.bytes)).append("</td><td>")
                    .append(Model.fmtDur(s.dur)).append("</td><td class='mono'>")
                    .append(s.ip).append("</td></tr>");
        }

        return "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>校园网流量报告</title><style>"
                + "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
                + "body{margin:0;padding:12px;background:#f6f8fb;color:#1b2430;"
                + "font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;font-size:14px}"
                + "h1{font-size:19px;margin:2px 0 4px}h2{font-size:15px;margin:18px 0 8px}"
                + ".date{color:#7b8794;font-size:12px;margin-bottom:12px}"
                + ".metrics{display:grid;grid-template-columns:1fr 1fr;gap:8px}"
                + ".metric{background:#fff;border-radius:10px;padding:10px 12px;border:1px solid #e3e8ef}"
                + ".metric .n{font-size:17px;font-weight:600}.metric .l{color:#7b8794;font-size:11px}"
                + ".card{background:#fff;border:1px solid #e3e8ef;border-radius:12px;padding:12px 14px;margin-bottom:10px}"
                + ".chead{display:flex;align-items:center;gap:6px}"
                + ".dot{width:9px;height:9px;border-radius:50%}"
                + ".dname{font-size:15px;font-weight:600}.mac{color:#9aa5b1;font-size:10px;margin-left:auto}"
                + ".big{font-size:23px;font-weight:600;margin-top:4px}"
                + ".muted{color:#7b8794;font-size:12px}"
                + ".stats{display:flex;flex-wrap:wrap;gap:4px 14px;margin:8px 0;font-size:12px;color:#4a5568}"
                + ".sub{font-size:12px;color:#7b8794;margin:10px 0 4px;border-top:1px solid #eef2f7;padding-top:7px}"
                + "ul.tops{margin:4px 0 8px;padding-left:17px;font-size:12px}ul.tops li{margin:3px 0}"
                + "table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden;font-size:11.5px}"
                + "th,td{padding:6px 6px;border-bottom:1px solid #eef2f7;text-align:left}"
                + "th{background:#f0f4f9;color:#7b8794;font-weight:500}"
                + ".mono{color:#9aa5b1;font-family:monospace}"
                + ".tip{background:#fff;border-left:3px solid #378ADD;padding:9px 12px;border-radius:6px;font-size:12px;margin-top:10px}"
                + "</style></head><body>"
                + "<h1>校园网流量报告</h1>"
                + "<div class='date'>统计区间 " + start + " ～ " + end + "　·　"
                + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</div>"
                + "<div class='metrics'>"
                + metric(Model.human(total), "总流量")
                + metric(String.valueOf(sessions.size()), "会话数")
                + metric(String.valueOf(dev.size()), "设备数")
                + metric(overText, "计费估算（" + quotaGb + "G 内免费）")
                + "</div>"
                + "<h2>设备流量对比</h2>" + cards
                + "<h2>每日总流量</h2><div class='card'>" + bars(dv, 320, 90, "#378ADD", dl) + "</div>"
                + "<h2>明细会话</h2><table><thead><tr><th>上线</th><th>下线</th><th>设备</th>"
                + "<th>流量</th><th>时长</th><th>IP</th></tr></thead><tbody>" + rows + "</tbody></table>"
                + "<div class='tip'>流量口径为校园网计费口径（IPv4 下行），不含上行与 IPv6。"
                + "设备名称可在 App 内修改，或写入门户「无感知认证」备注。</div>"
                + "</body></html>";
    }

    private static String metric(String n, String l) {
        return "<div class='metric'><div class='n'>" + esc(n) + "</div><div class='l'>" + esc(l) + "</div></div>";
    }

    /** 内联 SVG 柱状图 */
    private static String bars(long[] values, int w, int h, String color) {
        return bars(values, w, h, color, null);
    }

    private static String bars(long[] values, int w, int h, String color, String[] labels) {
        if (values.length == 0) return "<p class='muted'>无数据</p>";
        long max = 1;
        for (long v : values) max = Math.max(max, v);
        StringBuilder sb = new StringBuilder();
        sb.append("<svg viewBox='0 0 ").append(w).append(' ').append(h + (labels != null ? 14 : 0))
                .append("' style='width:100%;height:auto'>");
        double bw = (double) w / values.length;
        for (int i = 0; i < values.length; i++) {
            double bh = Math.max(2, (double) values[i] / max * h);
            double x = i * bw + bw * 0.15;
            sb.append("<rect x='").append(String.format("%.1f", x)).append("' y='")
                    .append(String.format("%.1f", h - bh)).append("' width='")
                    .append(String.format("%.1f", bw * 0.7)).append("' height='")
                    .append(String.format("%.1f", bh)).append("' rx='1.5' fill='").append(color).append("'/>");
            if (labels != null && values.length <= 32 && i < labels.length && (i % 3 == 0)) {
                sb.append("<text x='").append(String.format("%.1f", x + bw * 0.35))
                        .append("' y='").append(h + 11)
                        .append("' font-size='8' text-anchor='middle' fill='#9aa5b1'>")
                        .append(labels[i]).append("</text>");
            }
        }
        return sb.append("</svg>").toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
