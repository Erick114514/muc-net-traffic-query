# 校园网流量查询工具（中央民族大学）

输入账号密码，一键查询校园网流量，并**按设备（电脑 / 手机 / iPad…）分别统计**，生成可视化报告。

提供 **Windows 桌面版** 与 **Android 手机版** 两种形态，数据口径一致。

> ⚠️ 仅限**本人账号**在校内网使用。工具只读取你自己账号的流量记录，不修改任何账号设置。

---

## 下载

**安卓版直接下载 APK（手机浏览器打开即装）：**

https://github.com/Erick114514/muc-net-traffic-query/releases/download/v1.0.0/muc-traffic-query-v1.0.0.apk

其他方式：到 [Releases](../../releases) 页面下载

| 版本 | 文件 | 说明 |
|---|---|---|
| Android | `muc-traffic-query-v1.0.0.apk` | 直接安装，Android 7.0+ |
| Android | `android-v1.0.0.zip` | APK + 安装说明 |
| Windows | `desktop-v1.0.0.zip` | 解压后双击 exe，**免安装 Python** |

---|---|---|
| Windows | `desktop-v1.0.0.zip` | 解压后双击 exe，**免安装 Python** |
| Android | `android-v1.0.0.zip` | 内含 APK，Android 7.0+ |

---

## 功能

- **按设备统计流量**：校园网后台只记录 MAC，工具把 MAC 映射成「电脑 / 手机 / iPad」，一眼看出谁用了多少
- **可视化报告**：总流量、设备占比、会话次数、在线时长、活跃天数、**活跃时段分布图**、每日流量柱状图、明细会话清单
- **额度与超支提醒**：按 70G/月免费额度自动算超支金额（1 元/G）
- **免重复输验证码**：登录状态保存在本机，只需首次输入一次验证码
- **任意时间区间**：本月 / 上月 / 最近 7 天 / 最近 30 天 / 任意起止日期
- **设备命名一次生效**：在 App 内命名，或写入门户「无感知认证」备注，工具会自动读取

## 工作原理

1. 直接用 HTTP 请求登录校园网认证计费平台（深澜系统），处理 CSRF 与验证码
2. 翻页抓取「上网明细」全量记录（支持按时间区间筛选）
3. 按 MAC 聚合流量、会话、时段分布；读取门户设备备注作为设备名
4. 生成内联 SVG 图表的 HTML 报告（桌面版可用浏览器打开，手机版在 WebView 内展示）

实现细节：桌面版 Python + requests；安卓版纯 Java + HttpURLConnection（无第三方依赖，APK 仅 60KB 左右）。

## 目录结构

```
.
├── desktop/                  Windows 版源码
│   ├── muc_traffic.pyw       主程序（GUI + 抓取 + 报告）
│   ├── 启动.bat              启动脚本（自动探测 Python）
│   ├── 使用说明.md
│   └── 示例报告.html          报告样式示例（合成数据）
└── android/                  Android 版源码（Gradle 工程）
    └── app/src/main/java/cn/edu/muc/netquery/
        ├── PortalClient.java  HTTP 客户端（纯 Java，可独立测试）
        ├── Model.java         聚合与设备画像
        ├── ReportBuilder.java HTML 报告生成
        ├── MainActivity.java  界面
        └── ReportActivity.java 报告展示（WebView）
```

## 从源码构建

**Windows 版**

```bash
pip install requests
python desktop/muc_traffic.pyw
# 打包 exe
pip install pyinstaller
pyinstaller --noconfirm --onefile --noconsole --name "民大校园网流量查询" desktop/muc_traffic.pyw
```

**Android 版**

需要 JDK 17、Android SDK（platform-34 / build-tools 34.0.0）、Gradle 8.7：

```bash
cd android
echo "sdk.dir=<你的SDK路径>" > local.properties
gradle assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
```

## 隐私说明

- **不保存密码**：程序只保存登录后的会话凭据（用于免验证码），存于本机用户目录
- **数据不出本机**：报告与设备命名仅保存在本地，不上传任何服务器
- **随时清除**：桌面版「退出登录」/ 手机版「退出登录」可清除会话；删除 `data/` 目录即可清空全部本地数据

## 免责声明

本项目仅用于查询**使用者本人账号**的流量使用情况，不修改账号设置、不代他人查询、不涉及对校园网设施的任何探测或绕过行为。请勿将账号密码提供给他人在本工具中使用。
