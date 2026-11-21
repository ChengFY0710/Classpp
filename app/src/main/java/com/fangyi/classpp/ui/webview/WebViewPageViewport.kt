package com.fangyi.classpp.ui.webview

import android.webkit.WebView

/**
 * 可视网页页统一的视口与缩放配置。
 *
 * Android WebView 默认关闭宽视口与内置缩放：教务系统按桌面 UA 返回的宽布局会被压进
 * WebView 控件宽度，且无法捏合放大。所有会展示给用户的网页页在 `WebViewPolicy.harden`
 * 之后调用本函数；无头取数 WebView 不调用，取数路径不受展示取舍影响。
 *
 * @param fitWidth true = 按页面宽度整体缩放（[android.webkit.WebSettings.setLoadWithOverviewMode]），
 *   表单 / 课表入口页一屏看全；false = 保持页面原始尺寸（AI 识别页的宽表格取舍），
 *   超出屏幕的部分横向拖动查看。
 */
internal fun WebView.applyPageViewport(fitWidth: Boolean) {
    with(settings) {
        // 有 viewport meta 的页面按 meta 排版；没有 meta 的桌面页使用宽视口，
        // 不再被压到设备宽度
        useWideViewPort = true
        loadWithOverviewMode = fitWidth
        // 内置缩放是当前唯一支持的缩放机制（含双指捏合），只保留手势、不显示悬浮缩放钮
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
}
