/*
 * 校园网流量查询工具（Android 版）
 * Copyright (C) 2026 Erick114514
 *
 * 本程序为自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证
 * （第 3 版，或你选择的任何更新版本）条款重新发布和/或修改它。
 * 本程序基于“有用”的目的分发，但不提供任何担保。
 * SPDX-License-Identifier: GPL-3.0-only
 */
package cn.edu.muc.netquery;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

/** 报告页：用 WebView 展示生成的 HTML（含内联 SVG 图表） */
public class ReportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra("path");
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        if (path != null) wv.loadUrl("file://" + path);
        setContentView(wv);
    }
}
