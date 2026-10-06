package cn.edu.muc.netquery;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** 流量聚合与设备画像（纯 Java） */
public class Model {

    public static class Device {
        public String mac;
        public String name = "";
        public long bytes, dur;
        public int sessions;
        public TreeSet<String> ips = new TreeSet<>();
        public TreeSet<String> days = new TreeSet<>();
        public long[] hours = new long[24];
        public Date first, last;
        public List<PortalClient.Session> list = new ArrayList<>();

        public double share(long total) {
            return total <= 0 ? 0 : (double) bytes / total;
        }
    }

    /** 按 MAC 聚合 */
    public static LinkedHashMap<String, Device> aggregate(List<PortalClient.Session> sessions) {
        LinkedHashMap<String, Device> map = new LinkedHashMap<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat hour = new SimpleDateFormat("HH");
        for (PortalClient.Session s : sessions) {
            Device d = map.get(s.mac);
            if (d == null) {
                d = new Device();
                d.mac = s.mac;
                d.first = s.on;
                d.last = s.off;
                map.put(s.mac, d);
            }
            d.bytes += s.bytes;
            d.dur += s.dur;
            d.sessions++;
            d.ips.add(s.ip);
            d.days.add(day.format(s.on));
            try {
                d.hours[Integer.parseInt(hour.format(s.on))] += s.bytes;
            } catch (Exception ignored) {
            }
            if (s.on.before(d.first)) d.first = s.on;
            if (s.off.after(d.last)) d.last = s.off;
            d.list.add(s);
        }
        return map;
    }

    /** 每日总流量（key=yyyy-MM-dd） */
    public static TreeMap<String, long[]> daily(List<PortalClient.Session> sessions) {
        TreeMap<String, long[]> out = new TreeMap<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
        for (PortalClient.Session s : sessions) {
            String k = day.format(s.on);
            long[] v = out.get(k);
            if (v == null) {
                v = new long[]{0, 0};
                out.put(k, v);
            }
            v[0] += s.bytes;
            v[1]++;
        }
        return out;
    }

    /** 未命名设备的类型推测 */
    public static String guess(Device d, long total) {
        if (d.bytes > 8L * 1024 * 1024 * 1024 || d.share(total) > 0.4) return "电脑（推测）";
        if (d.sessions > 60 && d.bytes / Math.max(d.sessions, 1) < 30L * 1024 * 1024) return "手机（推测）";
        if (d.bytes / Math.max(d.sessions, 1) > 200L * 1024 * 1024) return "平板/电脑（推测）";
        return "未命名设备";
    }

    public static String human(long n) {
        if (n < 1024) return n + "B";
        double v = n;
        String[] u = {"B", "K", "M", "G", "T", "P"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.2f%s", v, u[i]);
    }

    public static String fmtDur(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return h + "小时" + m + "分";
        if (m > 0) return m + "分" + s + "秒";
        return s + "秒";
    }

    /** 区间起止（用于默认「本月」等） */
    public static String[] range(String mode) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        Calendar c = Calendar.getInstance();
        Calendar s = Calendar.getInstance();
        Calendar e = Calendar.getInstance();
        if ("本月".equals(mode)) {
            s.set(Calendar.DAY_OF_MONTH, 1);
        } else if ("上月".equals(mode)) {
            s.add(Calendar.MONTH, -1);
            s.set(Calendar.DAY_OF_MONTH, 1);
            e.set(Calendar.DAY_OF_MONTH, 1);
            e.add(Calendar.DAY_OF_MONTH, -1);
        } else if ("最近7天".equals(mode)) {
            s.add(Calendar.DAY_OF_MONTH, -6);
        } else {
            s.add(Calendar.DAY_OF_MONTH, -29);
        }
        return new String[]{f.format(s.getTime()), f.format(e.getTime())};
    }

    public static List<Map.Entry<String, Device>> sorted(LinkedHashMap<String, Device> map) {
        List<Map.Entry<String, Device>> list = new ArrayList<>(map.entrySet());
        list.sort(Comparator.comparingLong((Map.Entry<String, Device> x) -> x.getValue().bytes).reversed());
        return list;
    }
}
