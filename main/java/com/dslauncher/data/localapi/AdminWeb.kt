package com.dslauncher.data.localapi

/**
 * 内置**网页控制台**（浏览器直接访问 `http://<ip>:<port>/` 即可）。
 *
 * 设计目标：和 App 界面「一模一样」——同样的配色（Color.kt 的暗/亮两套令牌）、
 * 同样的三段式底部滑块（启动 / 功能 / 账号）、同样的卡片与文案（含作者署名 Gnuning）。
 *
 * 实现方式：单文件 HTML/CSS/JS（零依赖、无外部请求），由 [LocalApiServer] 直接吐给浏览器；
 * 数据与操作全部走同源的 `/admin/api/state` 与 `/admin/api/action`。
 *
 * 注意：本字符串是 Kotlin 原始字符串，内容里**不能出现 `$`**（会被当成模板），
 * 所以 JS 里一律用字符串拼接、不用模板字符串。
 */
object AdminWeb {

    val HTML: String = """
<!doctype html>
<html lang="zh-CN" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>DeepSeek API 启动器 · 网页控制台</title>
<style>
:root[data-theme="dark"]{--bg1:#0B1727;--bg2:#0A111C;--card:rgba(22,30,45,.78);--line:rgba(88,166,255,.18);--text:#E6EDF7;--text2:#9BB0C9;--blue:#2563EB;--blue2:#58A6FF;--green:#10B981;--red:#EF4444;--amber:#FFA726;--field:rgba(0,0,0,.35);--urlbg:rgba(0,0,0,.35)}
:root[data-theme="light"]{--bg1:#E4EDFA;--bg2:#F5F8FE;--card:rgba(255,255,255,.82);--line:rgba(37,99,235,.16);--text:#17233A;--text2:#5B6C85;--blue:#2563EB;--blue2:#1D6FE8;--green:#059669;--red:#DC2626;--amber:#B45309;--field:rgba(255,255,255,.8);--urlbg:rgba(255,255,255,.7)}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;padding:0;min-height:100%}
body{background:radial-gradient(1200px 620px at 72% -12%,var(--bg1),var(--bg2)) fixed;color:var(--text);
 font:14px/1.55 -apple-system,system-ui,"Segoe UI","Noto Sans SC",Roboto,sans-serif;padding-bottom:104px}
.wrap{max-width:760px;margin:0 auto;padding:18px 16px 0}
header{display:flex;align-items:center;gap:12px}
.logo{width:40px;height:40px;border-radius:14px;background:var(--blue);color:#fff;display:grid;place-items:center;font-size:19px;flex:none}
h1{font-size:20px;margin:0;font-weight:700;letter-spacing:.2px}
.sub{color:var(--text2);font-size:12.5px}
.pill{margin-left:auto;display:flex;gap:6px;align-items:center}
.chip{border:1px solid var(--line);background:var(--card);border-radius:999px;padding:4px 10px;font-size:12px;color:var(--text2);cursor:pointer;white-space:nowrap}
.chip.on{color:var(--green);border-color:var(--green)}
.chip.off{color:var(--red);border-color:var(--red)}
.chip.sel{background:var(--blue);border-color:var(--blue);color:#fff}
.card{background:var(--card);border:1px solid var(--line);border-radius:24px;padding:16px;margin-top:14px;backdrop-filter:blur(9px)}
.card h2{font-size:15px;margin:0 0 10px;display:flex;align-items:center;gap:8px;font-weight:650}
.row{display:flex;align-items:center;gap:10px}
.row+.row{margin-top:10px}
.wraprow{flex-wrap:wrap}
.grow{flex:1;min-width:0}
label.f{display:block;font-size:12px;color:var(--text2);margin-bottom:6px}
input,select{width:100%;background:var(--field);border:1px solid var(--line);border-radius:12px;padding:10px 12px;color:var(--text);outline:none;font-size:14px;font-family:inherit}
textarea{width:100%;min-height:110px;background:var(--field);border:1px solid var(--line);border-radius:12px;padding:10px 12px;color:var(--text);outline:none;font:12.5px/1.5 ui-monospace,Menlo,Consolas,monospace}
button{font:inherit;border:0;border-radius:14px;padding:10px 14px;background:var(--blue);color:#fff;cursor:pointer}
button.ghost{background:transparent;border:1px solid var(--line);color:var(--text)}
button.green{background:var(--green)}button.red{background:var(--red)}
button.sm{padding:6px 11px;border-radius:11px;font-size:12.5px}
button.block{width:100%;height:50px;font-size:15px;font-weight:600}
button:active{transform:scale(.985)}
.note{color:var(--amber);font-size:11.5px;margin-top:6px}
.tip{color:var(--text2);font-size:12px;margin-top:8px;word-break:break-word}
.url{background:var(--urlbg);border-radius:14px;padding:12px;font-family:ui-monospace,Menlo,Consolas,monospace;color:var(--blue2);word-break:break-all;font-size:12.5px}
.sw{width:46px;height:26px;border-radius:999px;background:var(--line);position:relative;flex:none;cursor:pointer;transition:.18s}
.sw i{position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:#fff;transition:.18s;box-shadow:0 1px 3px rgba(0,0,0,.3)}
.sw.on{background:var(--green)}.sw.on i{left:23px}
.acc{border:1px solid var(--line);border-radius:18px;padding:12px;margin-top:10px}
.acc .t{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.dot{width:8px;height:8px;border-radius:50%;background:var(--green);flex:none}
.mut{color:var(--text2);font-size:12px;margin-top:4px;word-break:break-word}
.mono{font-family:ui-monospace,Menlo,Consolas,monospace}
nav{position:fixed;left:0;right:0;bottom:16px;display:flex;justify-content:center;pointer-events:none;z-index:9}
.seg{pointer-events:auto;display:flex;gap:4px;background:var(--card);border:1px solid var(--line);border-radius:999px;padding:5px;backdrop-filter:blur(10px);box-shadow:0 12px 34px rgba(0,0,0,.28)}
.seg b{padding:8px 18px;border-radius:999px;font-weight:600;font-size:13.5px;color:var(--text2);cursor:pointer;min-width:74px;text-align:center}
.seg b.on{background:var(--blue);color:#fff}
.page{display:none}.page.on{display:block}
.toast{position:fixed;left:50%;transform:translateX(-50%);bottom:88px;background:#111827f2;color:#fff;padding:10px 15px;border-radius:12px;font-size:13px;opacity:0;transition:.18s;pointer-events:none;max-width:82%;z-index:20;text-align:center;white-space:pre-wrap}
.toast.on{opacity:1}
.hide{display:none}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo">▶</div>
    <div>
      <h1>DeepSeek API 启动器</h1>
      <div class="sub">本地 OpenAI 兼容接口 · 一键启动 · 作者 Gnuning</div>
    </div>
    <div class="pill">
      <span id="runChip" class="chip">连接中…</span>
      <span class="chip" id="themeBtn">🌓</span>
    </div>
  </header>

  <div class="card hide" id="kbar">
    <h2>需要 API Key</h2>
    <div class="row"><input id="keyGlobal" type="password" placeholder="请输入 API Key"></div>
    <div class="row"><button class="block" id="keySave">保存并使用</button></div>
    <div class="note">本服务设置了 API Key，网页控制台的状态查询与操作都需要带上它（只保存在本机浏览器里）。</div>
  </div>

  <div class="page on" id="page0"></div>
  <div class="page" id="page1"></div>
  <div class="page" id="page2"></div>
</div>

<nav><div class="seg">
  <b data-page="0" class="on">启动</b><b data-page="1">功能</b><b data-page="2">账号</b>
</div></nav>
<div class="toast" id="toast"></div>

<script>
var KEY = localStorage.getItem('dsl_key') || '';
var ST = null;
var POOL = 'ds';
var MODE_TAB = 0;

function el(id){ return document.getElementById(id); }
function each(list, fn){ for (var i = 0; i < (list ? list.length : 0); i++) { fn(list[i]); } }
function val(id){ var e = el(id); return e ? e.value : ''; }
function esc(s){ return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function toast(m){ var t = el('toast'); t.textContent = m; t.className = 'toast on'; clearTimeout(t._t); t._t = setTimeout(function(){ t.className = 'toast'; }, 2400); }
function head(opt){
  opt = opt || {};
  opt.headers = opt.headers || {};
  opt.headers['Content-Type'] = 'application/json';
  if (KEY) opt.headers['Authorization'] = 'Bearer ' + KEY;
  return opt;
}
function getState(){
  return fetch('/admin/api/state', head()).then(function(r){
    if (r.status === 401) { el('kbar').classList.remove('hide'); return null; }
    el('kbar').classList.add('hide');
    return r.json();
  });
}
function act(body, slow){
  if (slow) toast('处理中，请稍候…');
  return fetch('/admin/api/action', head({ method:'POST', body: JSON.stringify(body) })).then(function(r){
    if (r.status === 401) { el('kbar').classList.remove('hide'); throw new Error('需要 API Key'); }
    return r.json();
  }).then(function(j){
    toast(j.message || '完成');
    refresh();
    return j;
  }).catch(function(e){ toast('失败：' + e.message); });
}
function refresh(){
  getState().then(function(j){ if (!j) return; ST = j; render(); }).catch(function(){});
}
function copyText(t){
  if (navigator.clipboard) { navigator.clipboard.writeText(t).then(function(){ toast('已复制'); }, function(){ toast(t); }); }
  else { toast(t); }
}

/* ---------------- 渲染 ---------------- */

function render(){
  var s = ST;
  var chip = el('runChip');
  chip.textContent = s.server.running ? ('运行中 · ' + s.server.port) : '已停止';
  chip.className = 'chip ' + (s.server.running ? 'on' : 'off');
  renderLaunch(s); renderFeatures(s); renderAccounts(s);
}

function card(title, body){ return '<div class="card"><h2>' + title + '</h2>' + body + '</div>'; }
function toggleRow(title, desc, on, actKey, extra){
  return '<div class="row"><div class="grow"><div style="font-weight:600">' + title + '</div>' +
    '<div class="mut">' + desc + '</div>' + (extra || '') + '</div>' +
    '<div class="sw ' + (on ? 'on' : '') + '" data-act="toggle" data-key="' + actKey + '" data-val="' + (on ? '0' : '1') + '"><i></i></div></div>';
}

function renderLaunch(s){
  var h = '';
  h += card('服务',
    '<div class="row"><div class="grow"><label class="f">端口 (1024–65535)</label>' +
      '<input id="portInput" type="number" value="' + s.server.port + '"></div>' +
      '<button class="ghost sm" data-act="checkport" style="align-self:flex-end">检查占用</button></div>' +
    '<div class="row"><div class="grow"><label class="f">API Key（可留空）</label>' +
      '<input id="keyInput" type="text" value="' + esc(s.server.keyMasked) + '"></div>' +
      '<button class="ghost sm" data-act="setkey" style="align-self:flex-end">保存</button></div>' +
    '<div class="row"><button class="block ' + (s.server.running ? 'red' : 'green') + '" data-act="svc">' +
      (s.server.running ? '停止服务' : '启动服务') + '</button></div>' +
    toggleRow('开放局域网 API', s.server.allowLan ? '已开启 · 同一网络下的设备可访问本控制台与 /v1 接口' : '已关闭 · 仅本机 127.0.0.1 可访问', s.server.allowLan, 'allowLan') +
    '<div class="tip">' + (s.server.running ? ('服务运行中 · 端口 ' + s.server.port) : '服务已停止') +
      (s.server.lastError ? (' · 错误：' + esc(s.server.lastError)) : '') + '</div>');

  var urls = '<div class="url">' + esc(s.server.localUrl) + '</div>';
  if (s.server.lanUrl) urls += '<div class="url" style="margin-top:8px">' + esc(s.server.lanUrl) + '</div>';
  urls += '<div class="row" style="margin-top:10px"><button class="ghost grow" data-act="copy" data-text="' + esc(s.server.localUrl) + '">复制网址</button>' +
    '<button class="ghost grow" data-act="openui">管理本机 API Key</button></div>';
  if (s.server.tunnelUrl) {
    urls += '<div class="tip" style="color:var(--green)">内网穿透公网地址</div>' +
      '<div class="url" style="color:var(--green)">' + esc(s.server.tunnelUrl) + '/v1</div>' +
      '<div class="row" style="margin-top:10px"><button class="ghost block" data-act="copy" data-text="' + esc(s.server.tunnelUrl) + '/v1">复制公网地址</button></div>';
  }
  h += card('API 地址', urls);

  // 双协议：OpenAI 兼容 / Anthropic 兼容，客户端二选一
  var proto = '<div class="tip" style="font-weight:600">OpenAI 兼容</div>' +
    '<div class="url">' + esc(s.server.openaiUrl || s.server.localUrl) + '</div>' +
    '<div class="tip">/chat/completions · /models（流式、工具调用、reasoning_content）</div>' +
    '<div class="row" style="margin-top:8px"><button class="ghost block" data-act="copy" data-text="' + esc(s.server.openaiUrl || s.server.localUrl) + '">复制 OpenAI base_url</button></div>' +
    '<div class="tip" style="font-weight:600;margin-top:14px">Anthropic 兼容</div>' +
    '<div class="url" style="color:var(--green)">' + esc(s.server.anthropicUrl) + '</div>' +
    '<div class="tip">/v1/messages · /v1/messages/count_tokens（流式 SSE 事件、thinking、tool_use）<br>' +
      '密钥两种传法都行：<span class="mono">x-api-key</span> 或 <span class="mono">Authorization: Bearer</span></div>' +
    '<div class="row" style="margin-top:8px"><button class="ghost block" data-act="copy" data-text="' + esc(s.server.anthropicUrl) + '">复制 Anthropic base_url</button></div>' +
    (s.server.lanAnthropicUrl ? ('<div class="tip">局域网：<span class="mono">' + esc(s.server.lanAnthropicUrl) + '</span>（Anthropic）/ <span class="mono">' + esc(s.server.lanOpenaiUrl) + '</span>（OpenAI）</div>') : '');
  h += card('客户端接入（二选一）', proto);

  h += card('关于软件',
    '<div class="tip">DeepSeek API 启动器 v' + s.app.version + ' · 把官网对话能力封装成本地 OpenAI 兼容接口</div>' +
    '<div class="tip">· 双协议兼容：OpenAI（/v1/chat/completions）与 Anthropic（/v1/messages），客户端二选一</div>' +
    '<div class="tip">· 本地 OpenAI 兼容接口：/v1/chat/completions，支持流式输出、工具调用、思考流</div>' +
    '<div class="tip">· 多账号池：DeepSeek / Qwen 双池，轮询/随机取号、配额与冷却、批量导入</div>' +
    '<div class="tip">· 混合模型：model 选 mixed 时随机请求 DeepSeek + Qwen 双池账号</div>' +
    '<div class="tip">· 会话隔离：按「对话 + 账号」隔离官网会话，新对话不再串流</div>' +
    '<div class="tip">· 防撤回快照 · 深度思考开关 · 悬浮小窗 · 局域网开放 · 内网穿透（Pinggy）</div>' +
    '<div class="row" style="margin-top:12px;background:rgba(16,185,129,.12);border-radius:14px;padding:10px 12px">' +
      '<div class="grow"><div style="font-weight:600">作者：Gnuning</div><div class="mut">DeepSeek API 启动器 · Android</div></div></div>' +
    '<div class="row" style="margin-top:10px"><div class="grow"><div style="font-weight:600">QQ 群：1101774655</div>' +
      '<div class="mut">点击右侧复制群号</div></div>' +
      '<button class="ghost sm" data-act="copy" data-text="1101774655">复制</button></div>');
  el('page0').innerHTML = h;
  wire(el('page0'));
}

function renderFeatures(s){
  var f = s.settings;
  var h = '';
  h += card('能力开关',
    toggleRow('深度思考', f.deepThink ? '已开启 · 先推理再作答，首字稍慢，答案更稳（DeepSeek 与 Qwen 均生效）' : '已关闭 · 直接作答', f.deepThink, 'deepThink') +
    toggleRow('防撤回', f.antiRecall ? '已开启 · 官网撤回消息前把原文快照存本地' : '已关闭 · 不做本地快照', f.antiRecall, 'antiRecall') +
    toggleRow('开发者模式', f.devMode ? '已开启 · 显示诊断入口与调试信息' : '已关闭', f.devMode, 'devMode') +
    toggleRow('悬浮小窗', f.floatingWindow ? '已开启 · 半透明悬浮球常驻屏幕边缘' : '已关闭 · 在屏幕上显示可拖动悬浮球', f.floatingWindow, 'floatingWindow') +
    toggleRow('自动更换指纹',
      '账号被禁言 / Token 失效或每 ' + f.fingerprintRotateDays + ' 天自动更换设备指纹，账号间指纹互不重复',
      f.autoRotateFingerprint, 'autoRotateFingerprint',
      '<div class="note">注：设备指纹只是「这台设备是谁」的身份声明，更换它不保证降低被禁言概率 —— 是否触发风控还取决于 IP、UA、请求频率等。</div>'));

  h += card('模型',
    '<div class="row"><div class="grow"><label class="f">Qwen 模型名</label>' +
      '<input id="qmodel" type="text" value="' + esc(f.qwenModel) + '"></div>' +
      '<button class="ghost sm" data-act="setqmodel" style="align-self:flex-end">保存</button></div>' +
    '<div class="row wraprow" style="margin-top:10px">' +
      s.models.qwen.slice(0, 6).map(function(m){
        return '<span class="chip ' + (m === f.qwenModel ? 'sel' : '') + '" data-act="useqmodel" data-model="' + esc(m) + '">' + esc(m) + '</span>';
      }).join('') + '</div>' +
    '<div class="row wraprow" style="margin-top:10px">' +
      s.models.ds.map(function(m){ return '<span class="chip">' + esc(m) + '</span>'; }).join('') +
      '<span class="chip">' + esc(s.models.mixed) + '（混合双池）</span>' +
      '<span class="chip" data-act="models">刷新 Qwen 线上模型</span></div>');

  h += card('Qwen 通道',
    toggleRow('内置浏览器通道（防风控）',
      '状态：' + esc(s.qwen.browserStatus) + ' · chat.qwen.ai 的对话接口有阿里云 WAF 指纹风控，只有真 Chromium（WebView）栈过得去',
      f.qwenUseBrowser, 'qwenUseBrowser') +
    toggleRow('Token 自动续期（免重复登录）',
      f.qwenAutoRefresh
        ? ('每 60s 检查，到期前 ' + f.qwenRefreshLeadMin + ' 分钟自动换新 Token（已具备条件 ' + s.qwen.capable + '/' + s.qwen.accounts.length + ' 个号）')
        : '已关闭：Token 过期（约 15 分钟）后需重新登录',
      f.qwenAutoRefresh, 'qwenAutoRefresh') +
    '<div class="row wraprow" style="margin-top:10px">' +
      '<button class="ghost sm" data-act="qrefreshall">立即续期全部 Token</button>' +
      '<button class="ghost sm" data-act="qrefreshpage">续期页面会话</button></div>' +
    '<div class="row" style="margin-top:10px"><div class="grow"><label class="f">提前续期（分钟）</label>' +
      '<input id="qlead" type="number" value="' + f.qwenRefreshLeadMin + '"></div>' +
      '<button class="ghost sm" data-act="setqlead" style="align-self:flex-end">保存</button></div>' +
    '<div class="note">浏览器登录（抓 Cookie/Token）必须在 App 内完成；网页控制台只能续期与查看。</div>');

  h += card('工具箱 · DSML 解析测试',
    '<div class="tip">把模型原文（可能夹着 DSML 标记）贴进来，看看解析出的 tool_calls 与清洗后的正文 —— ' +
      '这条链路和本地 API 用的是同一个解析器。</div>' +
    '<div class="row" style="margin-top:8px"><textarea id="dsmlIn" placeholder="粘贴模型原文（含 DSML / tool_call 标记）"></textarea></div>' +
    '<div class="row" style="margin-top:8px"><button class="ghost block" data-act="dsmltest">解析</button></div>' +
    '<div class="hide" id="dsmlOut" style="margin-top:10px"><textarea id="dsmlOutText" readonly></textarea></div>');

  h += card('内网穿透',
    '<div class="tip">状态：' + (s.tunnel.running ? ('运行中 · ' + esc(s.tunnel.publicUrl || '')) : '未运行') +
      (s.tunnel.lastError ? (' · ' + esc(s.tunnel.lastError)) : '') + '</div>' +
    '<div class="row" style="margin-top:10px"><button class="' + (s.tunnel.running ? 'red' : 'green') + ' block" data-act="tunnel">' +
      (s.tunnel.running ? '停止内网穿透' : '开启内网穿透') + '</button></div>');

  el('page1').innerHTML = h;
  wire(el('page1'));
}

function dsAccHtml(a, idx){
  var st = a.statusText;
  var col = a.tokenInvalid || a.needRelogin || a.mutedNow ? 'var(--red)' : (!a.enabled ? 'var(--text2)' : (a.cooling || a.quotaOut ? 'var(--amber)' : 'var(--green)'));
  return '<div class="acc">' +
    '<div class="t"><span class="dot" style="background:' + col + '"></span>' +
      '<b>' + esc(a.display) + '</b><span class="grow"></span>' +
      '<span class="chip" style="color:' + col + '">' + esc(st) + '</span>' +
      '<div class="sw ' + (a.enabled ? 'on' : '') + '" data-act="acc" data-op="toggle" data-pool="ds" data-id="' + a.id + '"><i></i></div></div>' +
    '<div class="mut">邮箱：' + esc(a.email || '未知（点校验状态重取）') + ' · 手机：' + esc(a.phone || '未知') + '</div>' +
    '<div class="mut">设备指纹：…' + esc(a.fp) + '（更换指纹不保证降低禁言概率）</div>' +
    '<div class="mut">' + esc(st) + '</div>' +
    '<div class="row wraprow" style="margin-top:8px">' +
      '<input id="q_ds_' + a.id + '" type="number" value="' + a.quota + '" style="width:88px">' +
      '<button class="ghost sm" data-act="acc" data-op="setquota" data-pool="ds" data-id="' + a.id + '" data-target="q_ds_' + a.id + '">设配额</button>' +
      '<button class="ghost sm" data-act="acc" data-op="reset" data-pool="ds" data-id="' + a.id + '">刷新配额</button>' +
      '<button class="ghost sm" data-act="acc" data-op="rotate" data-pool="ds" data-id="' + a.id + '">更换指纹</button>' +
      '<button class="ghost sm" data-act="acc" data-op="verify" data-pool="ds" data-id="' + a.id + '">校验状态</button>' +
      '<button class="ghost sm" data-act="acc" data-op="del" data-pool="ds" data-id="' + a.id + '">删除</button>' +
    '</div></div>';
}

function qwAccHtml(a, idx){
  var col = a.tokenInvalid || a.needRelogin ? 'var(--red)' : (!a.enabled ? 'var(--text2)' : (a.cooling || a.quotaOut ? 'var(--amber)' : 'var(--green)'));
  return '<div class="acc">' +
    '<div class="t"><span class="dot" style="background:' + col + '"></span>' +
      '<b>' + esc(a.display) + '</b><span class="grow"></span>' +
      '<span class="chip" style="color:' + col + '">' + esc(a.statusText) + '</span>' +
      '<div class="sw ' + (a.enabled ? 'on' : '') + '" data-act="acc" data-op="toggle" data-pool="qwen" data-id="' + a.id + '"><i></i></div></div>' +
    '<div class="mut">邮箱：' + esc(a.email || '未知') + ' · Token 有效期：' + esc(a.ttlText) + '</div>' +
    '<div class="mut">' + (a.hasCookie ? 'Cookie 已存，自动续期生效' : '未存 Cookie（重新登录一次即可自动续期）') +
      (a.refreshFail > 0 ? (' · 续期失败 ' + a.refreshFail + ' 次') : '') + '</div>' +
    '<div class="mut">' + esc(a.statusText) + '</div>' +
    '<div class="row wraprow" style="margin-top:8px">' +
      '<input id="q_qw_' + a.id + '" type="number" value="' + a.quota + '" style="width:88px">' +
      '<button class="ghost sm" data-act="acc" data-op="setquota" data-pool="qwen" data-id="' + a.id + '" data-target="q_qw_' + a.id + '">设配额</button>' +
      '<button class="ghost sm" data-act="acc" data-op="reset" data-pool="qwen" data-id="' + a.id + '">刷新配额</button>' +
      '<button class="ghost sm" data-act="acc" data-op="verify" data-pool="qwen" data-id="' + a.id + '">续期/校验</button>' +
      '<button class="ghost sm" data-act="acc" data-op="del" data-pool="qwen" data-id="' + a.id + '">删除</button>' +
    '</div></div>';
}

function renderAccounts(s){
  var q = POOL === 'qwen';
  var src = q ? s.qwen : s.ds;
  var h = '';
  h += card('账号池', 
    '<div class="row wraprow">' +
      '<span class="chip ' + (q ? '' : 'sel') + '" data-act="pool" data-pool="ds">DeepSeek 池 · ' + s.ds.accounts.length + '</span>' +
      '<span class="chip ' + (q ? 'sel' : '') + '" data-act="pool" data-pool="qwen">Qwen 池 · ' + s.qwen.accounts.length + '</span></div>' +
    '<div class="row wraprow" style="margin-top:12px"><span class="mut" style="margin:0 2px 0 0">取号策略</span>' +
      '<span class="chip ' + (src.mode === 'ROUND_ROBIN' ? 'sel' : '') + '" data-act="mode" data-mode="ROUND_ROBIN">轮询</span>' +
      '<span class="chip ' + (src.mode === 'RANDOM' ? 'sel' : '') + '" data-act="mode" data-mode="RANDOM">随机</span></div>' +
    '<div class="row wraprow" style="margin-top:10px"><span class="mut" style="margin:0 2px 0 0">账号显示</span>' +
      '<span class="chip ' + (src.compact ? 'sel' : '') + '" data-act="compact" data-val="1">粗略 · 每行 2 个</span>' +
      '<span class="chip ' + (src.compact ? '' : 'sel') + '" data-act="compact" data-val="0">详细</span></div>' +
    '<div class="row" style="margin-top:12px"><div class="grow"><label class="f">默认配额 (0=不限)</label>' +
      '<input id="dq" type="number" value="' + src.defaultQuota + '"></div>' +
      '<button class="ghost sm" data-act="setdefaultquota" style="align-self:flex-end">保存</button></div>' +
    '<div class="row"><div class="grow"><label class="f">刷新时间 (分钟)</label>' +
      '<input id="qc" type="number" value="' + Math.round(src.quotaCooldownMs / 60000) + '"></div>' +
      '<button class="ghost sm" data-act="setcooldown" style="align-self:flex-end">保存</button></div>' +
    '<div class="row wraprow" style="margin-top:10px">' +
      '<button class="ghost sm" data-act="quotatoall">配额应用全部</button>' +
      '<button class="ghost sm" data-act="verifyall">' + (q ? '续期全部 / 校验状态' : '校验状态') + '</button>' +
      '<button class="ghost sm" data-act="resetall">立即刷新全部配额</button>' +
      '<button class="ghost sm" data-act="import">导入账号</button>' +
      '<button class="ghost sm" data-act="export">导出账号</button>' +
      '<button class="ghost sm" data-act="sweep">清理过期状态</button></div>' +
    '<div class="row hide" id="importBox" style="margin-top:10px"><textarea id="importText" placeholder="粘贴 token（每行一个）或本 App 导出的 JSON 数组"></textarea></div>' +
    '<div class="row hide" id="importBtns" style="margin-top:10px">' +
      '<button class="green grow" data-act="doimport">确认导入</button>' +
      '<button class="ghost grow" data-act="closeimport">取消</button></div>' +
    '<div class="row hide" id="exportBox" style="margin-top:10px"><textarea id="exportText" readonly></textarea></div>' +
    '<div class="note">账号登录（浏览器登录 / 账号密码）需要 App 里的 WebView，请在 App 内完成；' +
      '控制台负责取号策略、配额、续期、导入导出等其余全部管理。</div>' +
    '<div class="tip">' + esc(src.diagnosis) + '</div>');

  var list = '';
  if (src.accounts.length === 0) {
    list = '<div class="card"><div class="mut">还没有账号。登录请在 App 内完成（网页端登录/浏览器登录），成功后自动入池；也可以点上面的「导入账号」粘贴 token。</div></div>';
  } else {
    list = '<div class="card"><h2>账号列表（' + src.accounts.length + '）</h2>';
    for (var i = 0; i < src.accounts.length; i++) {
      list += q ? qwAccHtml(src.accounts[i], i) : dsAccHtml(src.accounts[i], i);
    }
    list += '</div>';
  }
  el('page2').innerHTML = h + list;
  wire(el('page2'));
}

/* ---------------- 事件 ---------------- */

function wire(root){
  each(root.querySelectorAll('[data-act]'), function(node){
    var a = node.getAttribute('data-act');
    if (a === 'toggle') { node.onclick = function(){ act({ action:'toggle', key: this.getAttribute('data-key'), value: this.getAttribute('data-val') === '1' }); }; return; }
    node.onclick = function(){
      var d = this;
      if (a === 'svc') {
        var want = num('portInput');
        var changing = want !== ST.server.port;
        act({ action: ST.server.running ? 'stop_server' : 'start_server', port: want, key: val('keyInput') }).then(function(j){
          if (changing && ST.server.running) {
            toast((j && j.message ? j.message : '端口已切换') + '\n请改用新地址打开控制台：http://' + location.hostname + ':' + want + '/');
          }
        });
      }
      else if (a === 'setkey') act({ action:'set_key', key: val('keyInput') });
      else if (a === 'checkport') fetch('/health', head()).then(function(r){
        if (r.status === 200) toast('端口可用 · 服务正在监听');
        else if (r.status === 401) toast('服务在跑，但需要 API Key（见顶部输入框）');
        else toast('响应异常：HTTP ' + r.status);
      }, function(){ toast('端口无响应（服务可能未启动）'); });
      else if (a === 'copy') copyText(d.getAttribute('data-text'));
      else if (a === 'openui') { el('kbar').classList.remove('hide'); toast('在顶部输入 API Key 后点保存'); }
      else if (a === 'setqmodel') act({ action:'set_qwen_model', value: val('qmodel') });
      else if (a === 'useqmodel') act({ action:'set_qwen_model', value: d.getAttribute('data-model') });
      else if (a === 'models') act({ action:'refresh_models' });
      else if (a === 'setqlead') act({ action:'set_qwen_lead', value: num('qlead') });
      else if (a === 'qrefreshall') act({ action:'qwen_refresh_all' }, true);
      else if (a === 'qrefreshpage') act({ action:'qwen_refresh_page' }, true);
      else if (a === 'tunnel') act({ action: ST.tunnel.running ? 'tunnel_stop' : 'tunnel_start' }, true);
      else if (a === 'dsmltest') {
        fetch('/debug/dsml', head({ method:'POST', body: JSON.stringify({ text: val('dsmlIn') }) }))
          .then(function(r){ if (r.status === 401) { throw new Error('需要 API Key'); } return r.json(); })
          .then(function(j){
            var box = el('dsmlOut');
            box.classList.remove('hide');
            el('dsmlOutText').value = '调用数量：' + j.count + '\n' +
              JSON.stringify(j.calls, null, 2) + '\n\n清洗后正文：\n' + j.text;
            toast(j.count > 0 ? ('解析出 ' + j.count + ' 个工具调用') : '没有检测到工具调用');
          })
          .catch(function(e){ toast('失败：' + e.message); });
      }
      else if (a === 'pool') { POOL = d.getAttribute('data-pool'); renderAccounts(ST); }
      else if (a === 'mode') act({ action:'set_mode', pool: POOL, value: d.getAttribute('data-mode') });
      else if (a === 'compact') act({ action:'set_compact', pool: POOL, value: d.getAttribute('data-val') === '1' });
      else if (a === 'setdefaultquota') act({ action:'set_default_quota', pool: POOL, value: num('dq') });
      else if (a === 'setcooldown') act({ action:'set_cooldown', pool: POOL, value: num('qc') });
      else if (a === 'quotatoall') act({ action:'quota_to_all', pool: POOL, value: num('dq') });
      else if (a === 'verifyall') act({ action:'verify_all', pool: POOL }, true);
      else if (a === 'resetall') act({ action:'reset_all_usage', pool: POOL });
      else if (a === 'import') { el('importBox').classList.remove('hide'); el('importBtns').classList.remove('hide'); }
      else if (a === 'closeimport') { el('importBox').classList.add('hide'); el('importBtns').classList.add('hide'); }
      else if (a === 'doimport') act({ action:'add_account', pool: POOL, text: val('importText') });
      else if (a === 'export') {
        fetch('/admin/api/state', head()).then(function(r){ return r.json(); }).then(function(j){
          var txt = j[POOL].exportJson || '';
          el('exportBox').classList.remove('hide');
          el('exportText').value = txt;
          toast('已导出到下方文本框（可直接复制）');
        });
      }
      else if (a === 'sweep') act({ action:'sweep' });
      else if (a === 'acc') {
        var op = d.getAttribute('data-op');
        var body = { action:'acc', op: op, pool: d.getAttribute('data-pool'), id: d.getAttribute('data-id') };
        if (op === 'setquota') body.value = num(d.getAttribute('data-target'));
        act(body, op === 'verify');
      }
    };
  });
}
function num(id){ var v = parseInt(val(id), 10); return isNaN(v) ? 0 : v; }

el('themeBtn').onclick = function(){
  var cur = document.documentElement.getAttribute('data-theme');
  document.documentElement.setAttribute('data-theme', cur === 'dark' ? 'light' : 'dark');
  localStorage.setItem('dsl_theme', cur === 'dark' ? 'light' : 'dark');
};
(function(){
  var t = localStorage.getItem('dsl_theme');
  if (!t) { t = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'; }
  document.documentElement.setAttribute('data-theme', t);
})();

document.querySelectorAll('.seg b');
each(document.querySelectorAll('.seg b'), function(b){
  b.onclick = function(){
    MODE_TAB = parseInt(this.getAttribute('data-page'), 10);
    each(document.querySelectorAll('.seg b'), function(x){ x.className = x === b ? 'on' : ''; });
    for (var i = 0; i < 3; i++) { el('page' + i).className = 'page' + (i === MODE_TAB ? ' on' : ''); }
  };
});
el('keySave').onclick = function(){
  KEY = val('keyGlobal').trim();
  localStorage.setItem('dsl_key', KEY);
  refresh();
};
refresh();
setInterval(refresh, 4000);
</script>
</body>
</html>
"""
}
