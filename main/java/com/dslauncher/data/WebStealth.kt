package com.dslauncher.data

/**
 * 反 WebView 特征探测脚本（与 base-2.apk 的 `Humanizer.STEALTH_JS` 同款，另补两处安全项）。
 *
 * 为什么需要：新版登录页挂了**人机验证 / 滑块**，验证码服务在渲染前会先探测环境，
 * 常见判据包括 `navigator.webdriver === true`、缺 `window.chrome`、
 * `navigator.plugins` 为空、`navigator.languages` 缺失、`permissions.query('notifications')`
 * 抛异常等 —— 命中任意一条就可能**直接不渲染验证码控件**（表现为「验证码加载不出来」）。
 *
 * 幂等：重复注入无副作用；在 onPageStarted / onPageCommitVisible / onPageFinished 各注入一次，
 * 因为部分页面会在 DOM 就绪后重新执行环境探测。
 */
object WebStealth {

    const val JS: String = """
(function() {
    try {
        // 1. 隐藏 webdriver
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
        // 2. 伪装 Chrome 运行时
        if (!window.chrome) {
            window.chrome = {
                runtime: {},
                loadTimes: function() {},
                csi: function() {},
                app: {
                    isInstalled: false,
                    InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
                    RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
                }
            };
        } else if (!window.chrome.app) {
            window.chrome.app = {
                isInstalled: false,
                InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
                RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
            };
        }
        // 3. plugins / mimeTypes：给出「像真的」结构。
        //    注意：不能塞 [1,2,3,4,5] —— 有些脚本会读 plugins[i].name.indexOf(...)，
        //    拿到数字就会抛 TypeError 并中断验证码挂载（真机上就撞到过这个）。
        try {
            function mkMime(type, suffixes, desc, plugin) {
                return { type: type, suffixes: suffixes, description: desc, enabledPlugin: plugin };
            }
            function mkPlugin(name, filename, desc) {
                var mimes = [];
                var p = { name: name, filename: filename, description: desc, length: 0 };
                p.item = function (n) { return mimes[n] || null; };
                p.namedItem = function (n) {
                    for (var j = 0; j < mimes.length; j++) { if (mimes[j].type === n) { return mimes[j]; } }
                    return null;
                };
                p.__add = function (m) { mimes.push(m); p.length = mimes.length; p[mimes.length - 1] = m; };
                return p;
            }
            var pdf = mkPlugin('Chrome PDF Plugin', 'internal-pdf-viewer', 'Portable Document Format');
            var viewer = mkPlugin('Chrome PDF Viewer', 'mhjfbmdgcfjbbpaeojofohoefgiehjai', '');
            var nacl = mkPlugin('Native Client', 'internal-nacl-plugin', '');
            var mPdf = mkMime('application/pdf', 'pdf', 'Portable Document Format', pdf);
            var mChromePdf = mkMime('application/x-google-chrome-pdf', 'pdf', 'Portable Document Format', viewer);
            pdf.__add(mPdf);
            viewer.__add(mChromePdf);
            var plist = [pdf, viewer, nacl];
            var parr = {
                length: plist.length,
                item: function (n) { return plist[n] || null; },
                namedItem: function (n) {
                    for (var k = 0; k < plist.length; k++) { if (plist[k].name === n) { return plist[k]; } }
                    return null;
                },
                refresh: function () {}
            };
            for (var q = 0; q < plist.length; q++) { parr[q] = plist[q]; }
            var mlist = [mPdf, mChromePdf];
            var marr = {
                length: mlist.length,
                item: function (n) { return mlist[n] || null; },
                namedItem: function (n) {
                    for (var k = 0; k < mlist.length; k++) { if (mlist[k].type === n) { return mlist[k]; } }
                    return null;
                }
            };
            for (var w = 0; w < mlist.length; w++) { marr[w] = mlist[w]; }
            Object.defineProperty(navigator, 'plugins', { get: function () { return parr; } });
            Object.defineProperty(navigator, 'mimeTypes', { get: function () { return marr; } });
        } catch (e) {}
        // 4. 补全 languages
        Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US', 'en'] });
        // 5. 阻止特征探测 toString（permissions.query 在 WebView 里对 notifications 会抛错）
        var oq = window.navigator.permissions && window.navigator.permissions.query;
        if (oq) {
            window.navigator.permissions.query = function (parameters) {
                try {
                    if (parameters && parameters.name === 'notifications' && typeof Notification !== 'undefined') {
                        return Promise.resolve({ state: Notification.permission });
                    }
                } catch (e) {}
                return oq(parameters);
            };
        }
        // 6. 少数验证码会读这两个（缺失时部分版本直接不渲染）
        try {
            if (!navigator.hardwareConcurrency) {
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
            }
        } catch (e) {}
        try {
            if (!navigator.deviceMemory) {
                Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });
            }
        } catch (e) {}
    } catch (e) {}
})();
"""

    /**
     * 诊断脚本：把「验证码为什么没出来」需要的现场信息一次性拿回来。
     * 返回 JSON 字符串（由 Kotlin 侧解析后展示）。
     */
    const val DIAG_JS: String = """
(function () {
  try {
    var info = {};
    info.ua = navigator.userAgent;
    info.ready = document.readyState;
    info.title = document.title;
    info.url = location.href;
    info.bodyLen = (document.body ? (document.body.innerText || '').length : 0);
    info.hasPwd = !!document.querySelector('input[type="password"]');
    info.chrome = !!window.chrome;
    info.webdriver = String(typeof navigator.webdriver);
    try {
      var pl = navigator.plugins;
      info.pluginsLen = pl ? pl.length : -1;
      info.plugin0 = (pl && pl[0] && pl[0].name) ? String(pl[0].name) : 'none';
      info.plugin0IndexOfOk = (pl && pl[0] && typeof pl[0].name === 'string') ? 'yes' : 'no';
    } catch (e) { info.pluginsLen = -2; }
    info.dpr = String(window.devicePixelRatio || 1);
    info.innerH = window.innerHeight || 0;
    info.scrollH = (document.documentElement && document.documentElement.scrollHeight) || 0;
    function describe(el, i) {
      var r = { width: 0, height: 0, left: 0, top: 0 };
      try { r = el.getBoundingClientRect(); } catch (e) {}
      var cs = {};
      try { cs = getComputedStyle(el); } catch (e) {}
      var tag = String(el.tagName || '');
      var src = String(el.src || el.id || el.className || '');
      return '#' + i + ' ' + tag + ' ' +
        Math.round(r.width) + 'x' + Math.round(r.height) +
        '@' + Math.round(r.left) + ',' + Math.round(r.top) +
        ' display=' + (cs.display || '?') + ' vis=' + (cs.visibility || '?') +
        ' z=' + (cs.zIndex || '?') + ' src=' + src.slice(0, 90);
    }
    var ifr = document.querySelectorAll('iframe');
    info.iframes = [];
    for (var i = 0; i < ifr.length && i < 12; i++) { info.iframes.push(describe(ifr[i], i)); }
    var cand = document.querySelectorAll(
      '#captcha,[class*=captcha i],[id*=captcha i],iframe[src*=captcha i],' +
      '[class*=verify i],[id*=verify i],[class*=slider i],[id*=slider i],' +
      '[class*=nc_ i],[id*=nc_ i],[class*=geetest i],[id*=geetest i],' +
      '[class*=aliyun i],[id*=aliyun i],[class*=sec i],[id*=sec i]'
    );
    info.captchaNodes = cand.length;
    info.captchaRects = [];
    for (var j = 0; j < cand.length && j < 10; j++) { info.captchaRects.push(describe(cand[j], j)); }
    var t = (document.body ? document.body.innerText : '') || '';
    var keys = ['失败', '错误', '重试', '刷新', '不支持', '请稍后', '网络异常'];
    var hits = [];
    for (var k = 0; k < keys.length; k++) {
      var idx = t.indexOf(keys[k]);
      if (idx >= 0) { hits.push(t.slice(idx, idx + 30)); }
      if (hits.length >= 4) { break; }
    }
    info.errText = hits.join(' | ');
    return JSON.stringify(info);
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})()
"""
}
