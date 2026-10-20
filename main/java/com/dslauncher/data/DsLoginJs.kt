package com.dslauncher.data

import org.json.JSONObject

/**
 * DeepSeek 登录页共用的注入脚本与凭据解析 —— 全部来自 base-2.apk（dev.multigw）的登录实现，
 * 只做两处必要修正（都在注释里写明原因）：
 *
 * 1. `plugins` 伪装从 base-2 的 `[1,2,3,4,5]` 换成真实结构：
 *    实测 DeepSeek 的 `fp.min.js` 会读 `plugins[i].name.indexOf(...)`，
 *    拿到数字 -> `undefined.indexOf` -> TypeError（控制台可见），指纹脚本一崩验证码框就空白。
 * 2. 附加输入法补丁 [INPUT_COMPAT_JS]（base-2 没有；不加会出现「输入倒序」）。
 */
object DsLoginJs {

    /** base-2 的登录入口：站点根路径。 */
    const val LOGIN_URL = "https://chat.deepseek.com"

    /** base-2 `Humanizer.UA_POOL` 的两条。 */
    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    const val UA_DESKTOP =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/145.0.0.0 Safari/537.36"

    /** base-2 `Humanizer.STEALTH_JS`（plugins 一项改为真实结构，见类注释）。 */
    const val STEALTH_JS = """
(function() {
    try {
        // 1. 隐藏 webdriver
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
        // 2. 伪装 Chrome 运行时
        if (!window.chrome) {
            window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {}, app: {} };
        }
        // 3. plugins / mimeTypes：给「像真的」结构。
        //    实测：DeepSeek 的 fp.min.js（指纹脚本）会读 plugins[i].name.indexOf(...)；
        //    base-2 原始写法 [1,2,3,4,5] 会让它拿到数字 -> undefined.indexOf -> TypeError
        //    （控制台那条「Cannot read properties of undefined (reading 'indexOf')」），
        //    指纹脚本一崩，验证码框就渲染不出来。这里换成真实结构。
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
        // 5. 阻止特征探测 toString
        const originalQuery = window.navigator.permissions && window.navigator.permissions.query;
        if (originalQuery) {
            window.navigator.permissions.query = (parameters) => (
                parameters.name === 'notifications' ?
                    Promise.resolve({ state: Notification.permission }) :
                    originalQuery(parameters)
            );
        }
    } catch(e) {}
})();
"""

    /** 输入法兼容补丁：防 Android WebView 上 `input[type=number]` 的「输入倒序」。 */
    const val INPUT_COMPAT_JS = """
(function () {
  try {
    if (window.__dslInputFix) { return; }
    window.__dslInputFix = true;
    function normalize(el) {
      try {
        if (el && el.tagName === 'INPUT' && el.type === 'number') {
          el.type = 'text';
          if (!el.getAttribute('inputmode')) { el.setAttribute('inputmode', 'numeric'); }
        }
      } catch (e) {}
    }
    function scan(root) {
      try {
        if (!root || !root.querySelectorAll) { return; }
        var list = root.querySelectorAll('input[type="number"]');
        for (var i = 0; i < list.length; i++) { normalize(list[i]); }
      } catch (e) {}
    }
    function fixCaret(el) {
      try {
        if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA')) { return; }
        if (document.activeElement !== el) { return; }
        var v = el.value || '';
        if (v.length > 0 && el.selectionStart === 0 && el.selectionEnd === 0) {
          el.setSelectionRange(v.length, v.length);
        }
      } catch (e) {}
    }
    scan(document);
    try {
      new MutationObserver(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          var added = muts[i].addedNodes || [];
          for (var j = 0; j < added.length; j++) { scan(added[j]); }
        }
      }).observe(document.documentElement || document, { childList: true, subtree: true });
    } catch (e) {}
    document.addEventListener('input', function (e) { fixCaret(e.target); }, true);
    document.addEventListener('compositionend', function (e) { fixCaret(e.target); }, true);
    document.addEventListener('focusin', function (e) { fixCaret(e.target); }, true);
  } catch (e) {}
})()
"""

    /** base-2「提取并保存当前站点凭据」的全量 dump JS。 */
    const val DUMP_JS = """
(function(){try{var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k)o['LS:'+k]=localStorage.getItem(k)||'';}for(var j=0;j<sessionStorage.length;j++){var sk=sessionStorage.key(j);if(sk)o['SS:'+sk]=sessionStorage.getItem(sk)||'';}o['DOC_COOKIE']=document.cookie||'';return JSON.stringify(o);}catch(e){return '{}';}})()
"""

    /** base-2 适配器从 LS/Cookie 嗅 token 用的 JWT 正则。 */
    val JWT_RE = Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_-]{10,})?")

    /** 键名优先级（base-2 出现过的 token 相关键）。 */
    val TOKEN_KEYS = listOf(
        "LS:userToken", "LS:user_token", "LS:usertoken", "LS:token",
        "LS:auth_token", "LS:access_token", "LS:accessToken",
        "SS:userToken", "SS:token"
    )

    /** 从 base-2 风格的全量 dump 里挑 token：键名优先，JWT 正则兜底。 */
    fun extractToken(dumpJson: String): String? {
        val o = runCatching { JSONObject(dumpJson) }.getOrNull() ?: return null
        for (k in TOKEN_KEYS) {
            val v = o.optString(k).trim().trim('"')
            if (v.length > 20) return v
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "DOC_COOKIE") continue
            val m = JWT_RE.find(o.optString(key))?.value
            if (!m.isNullOrBlank()) return m
        }
        JWT_RE.find(o.optString("DOC_COOKIE"))?.value?.let { hit -> return hit }
        return null
    }
}
