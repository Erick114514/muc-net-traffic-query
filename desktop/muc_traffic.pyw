#!/usr/bin/env python
# -*- coding: utf-8 -*-
# 校园网流量查询工具（Windows 桌面版）
# Copyright (C) 2026 Erick114514
#
# 本程序为自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证
# （第 3 版，或你选择的任何更新版本）条款重新发布和/或修改它。
#
# 本程序基于“有用”的目的分发，但不提供任何担保；甚至不包含适销性或
# 特定用途适用性的默示担保。详见 GNU 通用公共许可证。
#
# 你应已随本程序收到一份 GNU 通用公共许可证副本；如果没有，请见
# <https://www.gnu.org/licenses/>。
#
# SPDX-License-Identifier: GPL-3.0-only
#
"""民大校园网流量查询工具（图形版）

功能：输入账号密码 → 一键查询指定时间段的流量 → 按设备（电脑/手机/iPad 等）
      分别统计并生成可视化报告。

依赖：Python 3.8+、requests（其余为标准库）
作者：为中央民族大学校园网用户制作
"""
import json
import os
import re
import sys
import threading
import time
import webbrowser
from datetime import datetime, timedelta

try:
    import tkinter as tk
    from tkinter import ttk, messagebox
    HAS_TK = True
except ImportError:
    HAS_TK = False
    tk = ttk = messagebox = None

try:
    import requests
except ImportError:
    print("缺少 requests 库，请先执行：pip install requests")
    sys.exit(1)
# ============================== 常量 ==============================
BASE = "http://192.168.2.231:8900"


def app_base_dir():
    """打包成 exe 后，数据目录跟随 exe 所在位置（而不是临时解包目录）"""
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


APP_DIR = app_base_dir()
if getattr(sys, "frozen", False):          # exe 版：数据放到用户目录，避免污染桌面
    DATA_DIR = os.path.join(os.environ.get("LOCALAPPDATA", APP_DIR), "MUCNetQuery")
else:                                       # 源码版：数据就放在工具目录
    DATA_DIR = os.path.join(APP_DIR, "data")
REPORT_DIR = os.path.join(DATA_DIR, "reports")
SESSION_FILE = os.path.join(DATA_DIR, "session.json")
DEVICES_FILE = os.path.join(DATA_DIR, "devices.json")
CONFIG_FILE = os.path.join(DATA_DIR, "config.json")
PER_PAGE = 10
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

DEVICE_PRESETS = ["电脑", "笔记本", "台式机", "手机", "iPhone", "安卓手机",
                  "iPad", "平板", "路由器", "电视", "其他"]

os.makedirs(REPORT_DIR, exist_ok=True)


# ============================== 工具函数 ==============================
def human(n):
    n = float(n or 0)
    for u in ["B", "K", "M", "G", "T"]:
        if n < 1024:
            return f"{n:.2f}{u}" if u != "B" else f"{int(n)}B"
        n /= 1024
    return f"{n:.2f}P"


def fmt_dur(sec):
    sec = int(sec or 0)
    h, r = divmod(sec, 3600)
    m, s = divmod(r, 60)
    if h:
        return f"{h}小时{m}分"
    if m:
        return f"{m}分{s}秒"
    return f"{s}秒"


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def save_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


def local_ips():
    """本机当前 IPv4 列表（用于判断是否在校园网内）"""
    import socket
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return " / ".join(ips) if ips else "未知"


# ============================== 网络客户端 ==============================
class MucClient:
    def __init__(self):
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": UA,
                               "Referer": BASE + "/",
                               "Accept-Language": "zh-CN,zh;q=0.9"})
        self.username = ""

    # ---- 会话持久化（复用登录状态，避免每次都要验证码）----
    def load_session(self):
        data = load_json(SESSION_FILE, None)
        if not data:
            return False
        try:
            self.s.cookies.update(requests.utils.cookiejar_from_dict(data.get("cookies", {})))
            self.username = data.get("username", "")
            return True
        except Exception:
            return False

    def save_session(self):
        save_json(SESSION_FILE, {"cookies": requests.utils.dict_from_cookiejar(self.s.cookies),
                                 "username": self.username})

    def clear_session(self):
        self.s.cookies.clear()
        try:
            os.remove(SESSION_FILE)
        except OSError:
            pass

    # ---- 登录 ----
    def fetch_login_page(self):
        r = self.s.get(BASE + "/login", timeout=20)
        r.raise_for_status()
        csrf = re.search(r'name="_csrf-8800"\s+value="([^"]+)"', r.text)
        cap = re.search(r'id="loginform-verifycode-image"[^>]*src="([^"]+)"', r.text)
        cap_url = cap.group(1) if cap else "/site/captcha"
        if cap_url.startswith("/"):
            cap_url = BASE + cap_url
        return (csrf.group(1) if csrf else ""), cap_url

    def fetch_captcha_image(self, url):
        r = self.s.get(url, timeout=20)
        r.raise_for_status()
        return r.content

    def do_login(self, username, password, verify_code, csrf):
        data = {"_csrf-8800": csrf,
                "LoginForm[username]": username,
                "LoginForm[password]": password,
                "LoginForm[verifyCode]": verify_code}
        r = self.s.post(BASE + "/login", data=data, timeout=25, allow_redirects=True)
        ok = "loginform-username" not in r.text
        if ok:
            self.username = username
            self.save_session()
        return ok

    def is_logged_in(self):
        try:
            r = self.s.get(BASE + "/log/detail?page=1&per-page=10", timeout=20)
            return ("loginform-username" not in r.text) and ("上网明细" in r.text or r.status_code == 200)
        except Exception:
            return False

    # ---- 无感知认证设备表（用户可在门户给 MAC 起名）----
    def fetch_bound_devices(self):
        """读取门户「无感知认证」里用户给 MAC 填的备注名"""
        try:
            r = self.s.get(BASE + "/user/mac-auth", timeout=20)
            out = {}
            for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", r.text, re.S):
                cells = [re.sub(r"<[^>]+>", " ", c) for c in
                         re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, re.S)]
                cells = [re.sub(r"&nbsp;|&times;", " ", c).strip() for c in cells]
                if len(cells) < 3:
                    continue
                mac = next((c for c in cells if re.match(r"^[0-9a-fA-F:]{17}$", c)), None)
                if not mac:
                    continue
                mac = mac.lower()
                if mac == "11:22:33:44:55:66":      # 门户示例行
                    continue
                # 备注单元格可能夹带“编辑 备注”按钮文本，取按钮之前的内容
                remark = ""
                for c in cells:
                    if c == mac or re.match(r"^\d+$", c):
                        continue
                    cleaned = re.split(r"编辑|备注$", c)[0].strip()
                    cleaned = re.sub(r"\s+", " ", cleaned).strip()
                    if cleaned and "未设置" not in cleaned:
                        remark = cleaned
                        break
                if remark:
                    out[mac] = remark
            return out
        except Exception:
            return {}

    # ---- 上网明细 ----
    @staticmethod
    def _parse_page(text):
        rows, total = [], None
        m = re.search(r"共\s*(\d+)\s*条", re.sub(r"<[^>]+>", "", text))
        if m:
            total = int(m.group(1))
        for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", text, re.S):
            cells = [re.sub(r"<[^>]+>", "", c).strip()
                     for c in re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, re.S)]
            cells = [c.replace("&nbsp;", " ").strip() for c in cells]
            if len(cells) >= 5 and re.match(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$", cells[0]):
                rows.append(cells)
        return rows, total

    @staticmethod
    def _row_key(row):
        """Stable identity for detecting rows repeated across portal pages."""
        return (
            row[0].strip(), row[1].strip(), row[2].strip().lower(),
            row[3].strip(), row[4].strip(),
            row[6].strip() if len(row) > 6 else "",
        )

    def fetch_logs(self, start, end, progress=None):
        """start/end: 'YYYY-MM-DD'；返回会话列表"""
        all_rows, seen_rows, page, total = [], set(), 1, None
        while True:
            url = (f"{BASE}/log/detail?add_time={start}&drop_time={end}"
                   f"&page={page}&per-page={PER_PAGE}")
            r = self.s.get(url, timeout=25)
            if "loginform-username" in r.text:
                raise RuntimeError("登录状态已失效，请重新登录")
            rows, total = self._parse_page(r.text)
            if not rows:
                break

            # The portal can ignore the page parameter and return old rows again.
            # Deduplicate across pages and stop when pagination makes no progress.
            seen_before_page = seen_rows.copy()
            page_keys = {self._row_key(row) for row in rows}
            new_rows = [row for row in rows if self._row_key(row) not in seen_before_page]
            if not new_rows:
                break
            all_rows.extend(new_rows)
            seen_rows.update(page_keys)
            if progress:
                progress(len(all_rows), total or len(all_rows))
            if total and len(all_rows) >= total:
                break
            if len(rows) < PER_PAGE:
                break
            page += 1
            if page > 500:
                break
            time.sleep(0.15)
        return all_rows


# ============================== 数据解析与聚合 ==============================
def build_sessions(raw_rows):
    """原始表格行 → 结构化会话。表格列：
       上线时间 | 下线时间 | mac | IP | 流量字节 | 流量可读 | 时长秒 | 时长可读"""
    sessions = []
    for c in raw_rows:
        try:
            on = datetime.strptime(c[0], "%Y-%m-%d %H:%M:%S")
            off = datetime.strptime(c[1], "%Y-%m-%d %H:%M:%S")
        except Exception:
            continue
        mac = c[2].strip().lower()
        ip = c[3].strip()
        try:
            nbytes = int(re.sub(r"[^\d]", "", c[4]) or 0)
        except Exception:
            nbytes = 0
        try:
            dur = int(re.sub(r"[^\d]", "", c[6]) or 0) if len(c) > 6 else int((off - on).total_seconds())
        except Exception:
            dur = int((off - on).total_seconds())
        sessions.append({"on": on, "off": off, "mac": mac, "ip": ip,
                         "bytes": nbytes, "dur": dur})
    sessions.sort(key=lambda x: x["on"])
    return sessions


def guess_device(mac, stats, total_bytes):
    """无标签时的设备类型猜测（仅提示，用户可改）"""
    if stats["bytes"] / max(total_bytes, 1) > 0.4 or stats["bytes"] > 8 * 1024 ** 3:
        return "电脑（推测）"
    if stats["n"] > 60 and stats["avg_bytes"] < 30 * 1024 ** 2:
        return "手机（推测）"
    if stats["avg_bytes"] > 200 * 1024 ** 2:
        return "平板/电脑（推测）"
    return "未命名设备"


def aggregate(sessions):
    dev = {}
    for s in sessions:
        d = dev.setdefault(s["mac"], {"mac": s["mac"], "bytes": 0, "dur": 0, "n": 0,
                                      "ips": set(), "days": set(), "hours": [0] * 24,
                                      "first": s["on"], "last": s["off"], "sessions": []})
        d["bytes"] += s["bytes"]
        d["dur"] += s["dur"]
        d["n"] += 1
        d["ips"].add(s["ip"])
        d["days"].add(s["on"].date())
        d["hours"][s["on"].hour] += s["bytes"]
        d["first"] = min(d["first"], s["on"])
        d["last"] = max(d["last"], s["off"])
        d["sessions"].append(s)
    total = sum(d["bytes"] for d in dev.values())
    for d in dev.values():
        d["avg_bytes"] = d["bytes"] / max(d["n"], 1)
        d["share"] = d["bytes"] / max(total, 1)
    return dev, total


def daily_totals(sessions):
    daily = {}
    for s in sessions:
        k = s["on"].strftime("%Y-%m-%d")
        cur = daily.setdefault(k, [0, 0])
        cur[0] += s["bytes"]
        cur[1] += 1
    return dict(sorted(daily.items()))


# ============================== 报告生成 ==============================
def svg_bars(values, labels, width=640, height=120, color="#378ADD", fmt=human):
    n = len(values)
    if n == 0:
        return "<p>无数据</p>"
    mx = max(values) or 1
    bw = width / n
    parts = [f'<svg viewBox="0 0 {width} {height + 26}" style="width:100%;height:auto">']
    for i, v in enumerate(values):
        h = max(2, v / mx * height)
        x = i * bw + bw * 0.15
        w = bw * 0.7
        parts.append(f'<rect x="{x:.1f}" y="{height - h:.1f}" width="{w:.1f}" height="{h:.1f}" '
                     f'rx="2" fill="{color}"><title>{labels[i]}：{fmt(v)}</title></rect>')
        if n <= 32:
            parts.append(f'<text x="{x + w / 2:.1f}" y="{height + 16}" font-size="9" '
                         f'text-anchor="middle" fill="#7b8794">{labels[i]}</text>')
    parts.append("</svg>")
    return "".join(parts)


def render_report(sessions, devices, total, start, end, free_quota_gb, out_path):
    dev, total_bytes = aggregate(sessions)
    daily = daily_totals(sessions)

    # 设备标签
    labeled = []
    for mac, d in dev.items():
        name = devices.get(mac) or guess_device(mac, d, total_bytes)
        labeled.append((name, d))
    labeled.sort(key=lambda x: -x[1]["bytes"])

    cards = ""
    palette = ["#378ADD", "#639922", "#BA7517", "#993556", "#534AB7", "#0F6E56"]
    for i, (name, d) in enumerate(labeled):
        color = palette[i % len(palette)]
        hours = [h / 1024 ** 3 for h in d["hours"]]
        top = sorted(d["sessions"], key=lambda x: -x["bytes"])[:3]
        top_html = "".join(
            f"<li>{s['on'].strftime('%m-%d %H:%M')} → {s['off'].strftime('%H:%M')}"
            f"　<b>{human(s['bytes'])}</b>　{fmt_dur(s['dur'])}</li>" for s in top if s["bytes"] > 0)
        cards += f"""
        <div class="card">
          <div class="card-head">
            <span class="dot" style="background:{color}"></span>
            <span class="dev-name">{name}</span>
            <span class="mac">{d['mac']}</span>
          </div>
          <div class="big">{human(d['bytes'])}</div>
          <div class="muted">占总流量 {d['share']*100:.1f}%</div>
          <div class="stats">
            <div><span class="k">会话</span><span class="v">{d['n']} 次</span></div>
            <div><span class="k">在线时长</span><span class="v">{fmt_dur(d['dur'])}</span></div>
            <div><span class="k">活跃天数</span><span class="v">{len(d['days'])} 天</span></div>
            <div><span class="k">用过 IP</span><span class="v">{len(d['ips'])} 个</span></div>
          </div>
          <div class="sub">活跃时段分布（按流量）</div>
          {svg_bars(hours, [f"{h}时" for h in range(24)], height=70, color=color, fmt=lambda v: f"{v:.2f}G")}
          <div class="sub">单次流量 Top3</div>
          <ul class="tops">{top_html or '<li class="muted">无有效流量会话</li>'}</ul>
          <div class="muted">首次 {d['first'].strftime('%m-%d %H:%M')} ・ 最近 {d['last'].strftime('%m-%d %H:%M')}</div>
        </div>"""

    daily_labels = [k[5:].replace("-", "/") for k in daily]
    daily_vals = [v[0] / 1024 ** 3 for v in daily.values()]

    # 原始明细表
    rows_html = ""
    name_by_mac = {mac: n for n, d in labeled for mac in [d["mac"]]}
    for s in sorted(sessions, key=lambda x: -x["bytes"]):
        if s["bytes"] <= 0:
            continue
        rows_html += (f"<tr><td>{s['on'].strftime('%m-%d %H:%M:%S')}</td>"
                      f"<td>{s['off'].strftime('%m-%d %H:%M:%S')}</td>"
                      f"<td>{name_by_mac.get(s['mac'], s['mac'])}</td>"
                      f"<td>{human(s['bytes'])}</td><td>{fmt_dur(s['dur'])}</td>"
                      f"<td class='mono'>{s['ip']}</td></tr>")

    quota = free_quota_gb * 1024 ** 3
    over = max(0, total_bytes - quota)
    html = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8">
<title>校园网流量报告 {start} ~ {end}</title>
<style>
 :root{{--line:#e3e8ef;--muted:#7b8794;--fg:#1b2430}}
 *{{box-sizing:border-box}}
 body{{margin:0;padding:28px;background:#f6f8fb;color:var(--fg);
      font-family:"Microsoft YaHei","Segoe UI",sans-serif}}
 .wrap{{max-width:1040px;margin:0 auto}}
 h1{{font-size:22px;margin:0 0 4px}} h2{{font-size:16px;margin:26px 0 12px}}
 .date{{color:var(--muted);font-size:13px;margin-bottom:18px}}
 .summary{{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:8px}}
 .metric{{flex:1;min-width:150px;background:#fff;border:1px solid var(--line);border-radius:10px;padding:14px 16px}}
 .metric .n{{font-size:22px;font-weight:600}} .metric .l{{color:var(--muted);font-size:12px}}
 .cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:14px}}
 .card{{background:#fff;border:1px solid var(--line);border-radius:12px;padding:16px 18px}}
 .card-head{{display:flex;align-items:center;gap:8px;margin-bottom:6px}}
 .dot{{width:10px;height:10px;border-radius:50%}}
 .dev-name{{font-size:15px;font-weight:600}} .mac{{color:var(--muted);font-size:11px;margin-left:auto;font-family:monospace}}
 .big{{font-size:26px;font-weight:600}}
 .muted{{color:var(--muted);font-size:12px}}
 .stats{{display:grid;grid-template-columns:1fr 1fr;gap:4px 10px;margin:10px 0 4px;font-size:12.5px}}
 .stats .k{{color:var(--muted);margin-right:8px}} .stats .v{{font-weight:500}}
 .sub{{font-size:12px;color:var(--muted);margin:12px 0 4px;border-top:1px solid var(--line);padding-top:8px}}
 ul.tops{{margin:4px 0 8px;padding-left:18px;font-size:12.5px}} ul.tops li{{margin:3px 0}}
 table{{width:100%;border-collapse:collapse;background:#fff;border:1px solid var(--line);border-radius:10px;overflow:hidden}}
 th,td{{padding:7px 10px;font-size:12.5px;border-bottom:1px solid var(--line);text-align:left}}
 th{{background:#f0f4f9;color:var(--muted);font-weight:500}}
 .mono{{font-family:monospace;color:var(--muted)}}
 .tip{{background:#fff;border-left:3px solid #378ADD;padding:10px 14px;border-radius:6px;font-size:13px;margin-top:10px}}
 h2 span{{font-size:12px;color:var(--muted);font-weight:400}}
</style></head><body><div class="wrap">
<h1>校园网流量使用报告</h1>
<div class="date">统计区间：{start} ～ {end}　·　生成于 {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</div>

<div class="summary">
  <div class="metric"><div class="n">{human(total_bytes)}</div><div class="l">总流量</div></div>
  <div class="metric"><div class="n">{len(sessions)}</div><div class="l">会话数</div></div>
  <div class="metric"><div class="n">{len(dev)}</div><div class="l">设备数</div></div>
  <div class="metric"><div class="n">{free_quota_gb} G</div><div class="l">免费额度</div></div>
  <div class="metric" style="border-color:{'#e05555' if over else '#3a9d6e'}">
    <div class="n" style="color:{'#c0392b' if over else '#2e8b57'}">
      {'超出 ' + human(over) + f'（约 {over/1024**3:.0f} 元）' if over else '额度内'}</div>
    <div class="l">计费估算（超出按 1 元/G）</div></div>
</div>

<h2>设备流量对比 <span>按 MAC 归属统计</span></h2>
<div class="cards">{cards}</div>

<h2>每日总流量 <span>单位 GB</span></h2>
<div class="card">{svg_bars(daily_vals, daily_labels, width=980, height=140, fmt=lambda v: f'{v:.2f}G')}</div>

<h2>明细会话 <span>按流量降序，仅列有流量会话</span></h2>
<table><thead><tr><th>上线时间</th><th>下线时间</th><th>设备</th><th>流量</th><th>时长</th><th>IP</th></tr></thead>
<tbody>{rows_html}</tbody></table>

<div class="tip">设备名称在工具里可以随时修改；若在门户“无感知认证”页给 MAC 填了备注，本报告会自动采用该备注名。
流量口径为校园网计费口径（IPv4 入流量），不含 IPv6 与上行。</div>
</div></body></html>"""

    with open(out_path, "w", encoding="utf-8") as f:
        f.write(html)
    return out_path, dev, total_bytes


if HAS_TK:
    _AppBase = tk.Tk
else:  # 无 tkinter 时给个占位基类，保证核心逻辑可被导入测试
    class _AppBase:  # type: ignore
        pass


# ============================== 图形界面 ==============================
class App(_AppBase):
    def __init__(self):
        super().__init__()
        self.title("民大校园网流量查询工具")
        self.geometry("620x560")
        self.resizable(False, False)
        self.client = MucClient()
        self.cfg = load_json(CONFIG_FILE, {})
        self.devices = load_json(DEVICES_FILE, {})
        self._build_ui()
        self.after(200, self.auto_check_session)

    # ---------- UI ----------
    def _build_ui(self):
        pad = {"padx": 14, "pady": 6}
        frm = ttk.Frame(self)
        frm.pack(fill="both", expand=True)

        ttk.Label(frm, text="民大校园网流量查询", font=("Microsoft YaHei", 16, "bold")).pack(**pad)
        ttk.Label(frm, text="输入账号密码即可查询，按设备分别统计流量",
                  foreground="#666").pack()

        box = ttk.LabelFrame(frm, text="账号信息", padding=10)
        box.pack(fill="x", **pad)
        ttk.Label(box, text="账号（学号）").grid(row=0, column=0, sticky="w", pady=4)
        self.ent_user = ttk.Entry(box, width=28)
        self.ent_user.grid(row=0, column=1, sticky="w")
        self.ent_user.insert(0, self.cfg.get("last_user", ""))
        ttk.Label(box, text="密码").grid(row=1, column=0, sticky="w", pady=4)
        self.ent_pwd = ttk.Entry(box, width=28, show="*")
        self.ent_pwd.grid(row=1, column=1, sticky="w")
        self.var_remember = tk.BooleanVar(value=self.cfg.get("remember", True))
        ttk.Checkbutton(box, text="记住账号（密码只保存在你本机，不落盘）",
                        variable=self.var_remember).grid(row=2, column=1, sticky="w")

        rng = ttk.LabelFrame(frm, text="统计区间", padding=10)
        rng.pack(fill="x", **pad)
        self.var_range = tk.StringVar(value="本月")
        for i, opt in enumerate(["本月", "上月", "最近 7 天", "最近 30 天", "自定义"]):
            ttk.Radiobutton(rng, text=opt, value=opt, variable=self.var_range,
                            command=self.on_range_change).grid(row=0, column=i, padx=4, sticky="w")
        ttk.Label(rng, text="起").grid(row=1, column=0, sticky="e", pady=6)
        self.ent_start = ttk.Entry(rng, width=12)
        self.ent_start.grid(row=1, column=1, sticky="w")
        ttk.Label(rng, text="止").grid(row=1, column=2, sticky="e")
        self.ent_end = ttk.Entry(rng, width=12)
        self.ent_end.grid(row=1, column=3, sticky="w")
        ttk.Label(rng, text="免费额度(G)").grid(row=1, column=4, sticky="e", padx=(12, 4))
        self.ent_quota = ttk.Entry(rng, width=6)
        self.ent_quota.grid(row=1, column=5, sticky="w")
        self.ent_quota.insert(0, str(self.cfg.get("quota", 70)))
        self.on_range_change()

        act = ttk.Frame(frm)
        act.pack(fill="x", **pad)
        self.btn_query = ttk.Button(act, text="开始查询", command=self.on_query)
        self.btn_query.pack(side="left")
        self.btn_open = ttk.Button(act, text="打开上次报告", command=self.open_last_report, state="disabled")
        self.btn_open.pack(side="left", padx=6)
        ttk.Button(act, text="给设备命名", command=self.open_naming).pack(side="left", padx=6)
        ttk.Button(act, text="退出登录", command=self.logout).pack(side="left", padx=6)

        self.pbar = ttk.Progressbar(frm, mode="determinate")
        self.pbar.pack(fill="x", padx=14)
        self.lbl_status = ttk.Label(frm, text="就绪", foreground="#444")
        self.lbl_status.pack(anchor="w", padx=14, pady=(4, 0))
        self.txt = tk.Text(frm, height=9, font=("Consolas", 9), bg="#fbfcfe")
        self.txt.pack(fill="both", expand=True, padx=14, pady=8)

    def log(self, *a):
        line = " ".join(str(x) for x in a)
        self.txt.insert("end", line + "\n")
        self.txt.see("end")
        self.update_idletasks()

    def status(self, s):
        self.lbl_status.config(text=s)
        self.update_idletasks()

    # ---------- 区间 ----------
    def on_range_change(self):
        today = datetime.now().date()
        r = self.var_range.get()
        if r == "本月":
            s = today.replace(day=1); e = today
        elif r == "上月":
            first = today.replace(day=1)
            e = first - timedelta(days=1); s = e.replace(day=1)
        elif r == "最近 7 天":
            s, e = today - timedelta(days=6), today
        elif r == "最近 30 天":
            s, e = today - timedelta(days=29), today
        else:
            return
        self.ent_start.delete(0, "end"); self.ent_start.insert(0, s.strftime("%Y-%m-%d"))
        self.ent_end.delete(0, "end"); self.ent_end.insert(0, e.strftime("%Y-%m-%d"))

    # ---------- 会话 ----------
    def auto_check_session(self):
        def work():
            if self.client.load_session() and self.client.username:
                self.log(f"检测到已保存的登录状态（账号 {self.mask(self.client.username)}）")
                if self.client.is_logged_in():
                    self.log("✔ 登录状态有效，可直接查询")
                    self.status("已登录，可直接查询")
                    return
                self.log("登录状态已过期，查询时会要求输入验证码")
            self.status("未登录：首次查询需输入验证码，之后免验证码")
        threading.Thread(target=work, daemon=True).start()

    @staticmethod
    def mask(u):
        return u[:3] + "*" * max(0, len(u) - 5) + u[-2:] if len(u) > 5 else u

    def ensure_login(self):
        """返回 True 表示已登录"""
        user = self.ent_user.get().strip()
        pwd = self.ent_pwd.get()
        # 先探网络，避免直接抛底层异常
        try:
            if self.client.load_session() and self.client.is_logged_in():
                return True
        except requests.exceptions.ConnectionError:
            messagebox.showerror("连不上校园网",
                                 "无法连接校园网认证平台。\n\n"
                                 f"本机当前 IP：{local_ips()}\n\n"
                                 "请确认：\n"
                                 "1) 已连接 Wi-Fi「MUC-student」（或插好网线）\n"
                                 "2) 已弹出认证页面并完成登录\n"
                                 "（IP 不是 10.70./10.71. 开头 = 没连上校园网）\n\n"
                                 "连接正常后重试即可。")
            return False
        except requests.exceptions.Timeout:
            messagebox.showerror("网络超时", "校园网认证平台响应超时，稍后重试。")
            return False
        if not user or not pwd:
            messagebox.showwarning("提示", "请先填写账号和密码")
            return False
        try:
            csrf, cap_url = self.client.fetch_login_page()
            img = self.client.fetch_captcha_image(cap_url)
        except requests.exceptions.ConnectionError:
            messagebox.showerror("连不上校园网",
                                 "无法连接校园网认证平台，请确认已连接「MUC-student」并完成认证。")
            return False
        except Exception as e:
            messagebox.showerror("获取验证码失败", str(e))
            return False
        code = self.ask_captcha(img)
        if not code:
            return False
        self.status("正在登录…")
        if self.client.do_login(user, pwd, code, csrf):
            self.log(f"✔ 登录成功（{self.mask(user)}）")
            self.cfg.update({"last_user": user, "remember": self.var_remember.get(),
                             "quota": int(self.ent_quota.get() or 70)})
            save_json(CONFIG_FILE, self.cfg)
            return True
        messagebox.showerror("登录失败", "账号、密码或验证码不正确，请重试")
        return False

    def ask_captcha(self, img_bytes):
        """弹出验证码输入框"""
        win = tk.Toplevel(self)
        win.title("请输入验证码")
        win.transient(self); win.grab_set()
        win.geometry("280x180")
        win.resizable(False, False)
        tk.Label(win, text="请输入图中验证码（4 位）", font=("Microsoft YaHei", 11)).pack(pady=8)
        raw_path = os.path.join(DATA_DIR, "_captcha.png")
        with open(raw_path, "wb") as f:
            f.write(img_bytes)
        try:
            photo = tk.PhotoImage(file=raw_path)
            lbl = tk.Label(win, image=photo)
            lbl.image = photo
            lbl.pack()
        except Exception:
            tk.Label(win, text="（图片无法显示，请重新查询）", foreground="red").pack()
        ent = ttk.Entry(win, width=12, justify="center", font=("Consolas", 14))
        ent.pack(pady=8)
        ent.focus_set()
        result = {"v": None}

        def ok(_=None):
            result["v"] = ent.get().strip()
            win.destroy()
        ttk.Button(win, text="确定", command=ok).pack()
        win.bind("<Return>", ok)
        self.wait_window(win)
        return result["v"]

    # ---------- 查询 ----------
    def on_query(self):
        self.btn_query.config(state="disabled")
        threading.Thread(target=self._query_worker, daemon=True).start()

    def _query_worker(self):
        try:
            if not self.ensure_login():
                return
            start = self.ent_start.get().strip()
            end = self.ent_end.get().strip()
            for d in (start, end):
                datetime.strptime(d, "%Y-%m-%d")
            self.log(f"开始抓取 {start} ~ {end} 的上网明细…")
            self.status("正在抓取…")

            def prog(cur, tot):
                self.pbar["maximum"] = tot
                self.pbar["value"] = cur
                if cur % 50 == 0:
                    self.log(f"  已获取 {cur}/{tot} 条")

            raw = self.client.fetch_logs(start, end, prog)
            self.log(f"共获取 {len(raw)} 条原始记录，正在解析统计…")
            sessions = build_sessions(raw)
            if not sessions:
                messagebox.showinfo("无数据", "该时间段没有上网记录")
                return

            # 合并门户备注名
            portal_names = self.client.fetch_bound_devices()
            if portal_names:
                self.log(f"读取到门户设备备注 {len(portal_names)} 条")
            merged = dict(portal_names)
            merged.update(self.devices)  # 本地命名优先

            stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            out = os.path.join(REPORT_DIR, f"流量报告_{start}_{end}_{stamp}.html")
            quota = int(self.ent_quota.get() or 70)
            _, dev, total_bytes = render_report(sessions, merged, len(sessions),
                                                start, end, quota, out)
            self.last_report = out
            self.last_devices = dev
            self.log(f"✔ 完成：总流量 {human(total_bytes)}，设备 {len(dev)} 台")
            for mac, d in sorted(dev.items(), key=lambda x: -x[1]["bytes"]):
                self.log(f"   {merged.get(mac, '(未命名)')}  {human(d['bytes'])}  {d['n']} 次会话")
            self.btn_open.config(state="normal")
            self.status("完成，已打开报告")
            webbrowser.open("file:///" + out.replace("\\", "/"))

            # 首次运行：自动弹出命名窗
            need = [m for m in dev if m not in merged]
            if need:
                self.after(400, lambda: self.open_naming(dev=dev, highlight=need))
        except Exception as e:
            self.log("✘ 出错：", e)
            messagebox.showerror("查询失败", str(e))
            self.status("失败")
        finally:
            self.btn_query.config(state="normal")

    def open_last_report(self):
        p = getattr(self, "last_report", None)
        if p and os.path.exists(p):
            webbrowser.open("file:///" + p.replace("\\", "/"))

    # ---------- 设备命名 ----------
    def open_naming(self, dev=None, highlight=None):
        dev = dev or getattr(self, "last_devices", None)
        if not dev:
            messagebox.showinfo("提示", "先查询一次，之后就能给设备命名了")
            return
        win = tk.Toplevel(self)
        win.title("给设备命名")
        win.transient(self)
        win.geometry("620x420")
        tk.Label(win, text="给每个 MAC 起个名字（电脑 / iPad / 手机…），报告里会按名字统计",
                 font=("Microsoft YaHei", 10)).pack(anchor="w", padx=12, pady=8)
        rows = sorted(dev.items(), key=lambda x: -x[1]["bytes"])
        frame = ttk.Frame(win)
        frame.pack(fill="both", expand=True, padx=12)
        entries = {}
        for i, (mac, d) in enumerate(rows):
            ttk.Label(frame, text=human(d["bytes"]), width=10).grid(row=i, column=0, sticky="w", pady=4)
            ttk.Label(frame, text=mac, font=("Consolas", 10), width=20).grid(row=i, column=1, sticky="w")
            cb = ttk.Combobox(frame, values=DEVICE_PRESETS, width=16)
            cb.set(self.devices.get(mac, ""))
            cb.grid(row=i, column=2, sticky="w", padx=6)
            entries[mac] = cb
            if highlight and mac in highlight:
                ttk.Label(frame, text="← 新增，建议命名", foreground="#c0392b").grid(row=i, column=3, sticky="w")

        def save():
            for mac, cb in entries.items():
                v = cb.get().strip()
                if v:
                    self.devices[mac] = v
            save_json(DEVICES_FILE, self.devices)
            self.log("已保存设备命名：", ", ".join(f"{m}={n}" for m, n in self.devices.items()))
            win.destroy()
        ttk.Button(win, text="保存", command=save).pack(pady=10)

    def logout(self):
        self.client.clear_session()
        self.log("已清除本机登录状态")
        self.status("已退出登录")


if __name__ == "__main__":
    if not HAS_TK:
        print("当前 Python 缺少 tkinter 图形库，无法启动界面。")
        print("请改用自带 tkinter 的 Python（Windows 官方安装包默认包含），")
        print("或执行：pip install tk  （部分发行版）")
        sys.exit(1)
    App().mainloop()
