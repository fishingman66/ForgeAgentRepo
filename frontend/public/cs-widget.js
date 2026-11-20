/* ForgeAgent 智能客服网页挂件 (A2) — 零依赖,随 public/ 原样打包
   用法: <script src="/cs-widget.js" data-channel="你的渠道编码" data-api-base="" data-title="在线客服"></script>
   A2 起主题/形态/欢迎语由后端 GET /chat-api/{channel}/widget-config 下发,data-* 仅作首屏兜底。 */
(function () {
  'use strict';
  var script = document.currentScript || (function () {
    var all = document.getElementsByTagName('script');
    return all[all.length - 1];
  })();
  var channel = script.getAttribute('data-channel') || 'default';
  var base = (script.getAttribute('data-api-base') || '').replace(/\/$/, '');
  var lang = script.getAttribute('data-lang') === 'en_US' ? 'en_US' : 'zh_CN';
  var WIDGET_I18N = {
    zh_CN: { title: '在线客服', open: '打开', close: '收起' },
    en_US: { title: 'Online Support', open: 'Open', close: 'Close' }
  };
  var WT = WIDGET_I18N[lang];
  var title = script.getAttribute('data-title') || WT.title;
  var cfgUrl = base + '/chat-api/' + encodeURIComponent(channel) + '/widget-config';

  // 默认配置:后端未返回/拉取失败时 fail-safe,行为与 C3 旧挂件一致
  var cfg = { theme: '#2563eb', mode: 'bubble', welcome: '', config: '', enabled: true };

  function hexColor(s) { return /^#[0-9a-fA-F]{6}$/.test(s || '') ? s : cfg.theme; }
  function parseExt() { try { return cfg.config ? JSON.parse(cfg.config) : {}; } catch (e) { return {}; } }

  function pageUrl() {
    return base + '/customer-service/index.html?channel=' + encodeURIComponent(channel)
      + '&base=' + encodeURIComponent(base) + '&title=' + encodeURIComponent(title)
      + '&lang=' + encodeURIComponent(lang);
  }

  var launcher, panel, frame, badge;
  var opened = false;
  var unread = 0;
  var unreadKey = 'forge_cs_unread_' + channel;
  try { unread = parseInt(localStorage.getItem(unreadKey) || '0', 10) || 0; } catch (e) { unread = 0; }

  function saveUnread() { try { localStorage.setItem(unreadKey, String(unread)); } catch (e) { /* 隐私模式忽略 */ } }
  function paintBadge() {
    if (!badge) return;
    badge.style.display = unread > 0 ? 'block' : 'none';
    badge.textContent = unread > 99 ? '99+' : String(unread);
  }
  function clearUnread() { unread = 0; saveUnread(); paintBadge(); }

  // 宿主上下文桥:只接受白名单 origin 的消息,白名单来自渠道 corsAllowlist(经 widget-config 下发 config.allowedOrigins)
  var lastCtx = null;
  function defaultCtx() {
    return {
      __forgeWidgetContext: true,
      externalUserId: '',
      pageUrl: location.href,
      orderId: ''
    };
  }
  function postCtx(ctx) {
    if (!frame || !frame.contentWindow) return;
    try { frame.contentWindow.postMessage(ctx, '*'); } catch (e) { /* iframe 未就绪时等下次 */ }
  }
  function postPanelState() {
    if (!frame || !frame.contentWindow) return;
    try { frame.contentWindow.postMessage({ __forgeWidget: true, type: 'panel', open: opened }, '*'); } catch (e) { /* 忽略 */ }
  }
  function onHostMessage(e) {
    if (!frame || !frame.contentWindow) return;
    if (e.source !== window && e.source !== frame.contentWindow) return;
    var d = e.data;
    if (!d || d.__forgeWidget !== true) return;
    var ext = parseExt();
    var allowed = ext.allowedOrigins || [];
    if (allowed.length && allowed.indexOf(e.origin) === -1) return;
    // 只透传白名单字段,不传任意对象(防宿主误塞敏感数据进模型上下文)
    var ctx = {
      __forgeWidgetContext: true,
      externalUserId: typeof d.externalUserId === 'string' ? d.externalUserId : '',
      pageUrl: typeof d.pageUrl === 'string' ? d.pageUrl : location.href,
      orderId: typeof d.orderId === 'string' ? d.orderId : ''
    };
    lastCtx = ctx;
    postCtx(ctx);
  }

  function mount() {
    var ext = parseExt();
    var theme = hexColor(cfg.theme);
    var isSidebar = cfg.mode === 'sidebar';
    var size = ext.size || {};
    var w = Math.max(280, Math.min(480, Number(size.width) || 360));
    var h = Math.max(360, Math.min(720, Number(size.height) || 520));
    var side = ext.position === 'left' ? 'left' : 'right';
    var bottom = Number(ext.bottom) || 24;

    launcher = document.createElement('button');
    launcher.type = 'button';
    launcher.setAttribute('aria-label', WT.open + ' ' + title);
    launcher.textContent = '';
    launcher.style.cssText = 'position:fixed;' + side + ':' + bottom + 'px;bottom:' + bottom + 'px;z-index:2147483000;'
      + 'width:52px;height:52px;border:0;border-radius:26px;background:' + theme + ';color:#fff;cursor:pointer;'
      + 'box-shadow:0 6px 18px rgba(0,0,0,.22);display:flex;align-items:center;justify-content:center;';
    // 内联 SVG 气泡图标(去掉"客服"文字按钮)
    launcher.innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
      + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
      + '<path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7'
      + 'a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>'
      + '</svg>';

    badge = document.createElement('span');
    badge.style.cssText = 'position:fixed;' + side + ':' + (bottom + 30) + 'px;bottom:' + (bottom + 30) + 'px;'
      + 'z-index:2147483001;min-width:18px;height:18px;padding:0 4px;border-radius:9px;background:#ef4444;'
      + 'color:#fff;font:11px/18px -apple-system,BlinkMacSystemFont,sans-serif;text-align:center;display:none;';

    panel = document.createElement('div');
    panel.style.cssText = 'position:fixed;' + side + ':' + bottom + 'px;bottom:' + (bottom + 66) + 'px;'
      + 'z-index:2147483000;width:' + w + 'px;height:' + h + 'px;border-radius:12px;overflow:hidden;'
      + 'box-shadow:0 12px 36px rgba(0,0,0,.22);display:none;background:#fff;';
    frame = document.createElement('iframe');
    frame.src = pageUrl();
    frame.title = title;
    frame.style.cssText = 'width:100%;height:100%;border:0';
    frame.setAttribute('allow', 'clipboard-write');
    panel.appendChild(frame);

    launcher.addEventListener('click', function () {
      opened = !opened;
      panel.style.display = opened ? (isSidebar ? 'flex' : 'block') : 'none';
      launcher.setAttribute('aria-label', opened ? WT.close + ' ' + title : WT.open + ' ' + title);
      if (opened) clearUnread();
      postPanelState();
    });
    // iframe 就绪后主动补发一次上下文与面板状态,避免握手消息早于监听注册
    frame.addEventListener('load', function () {
      postCtx(lastCtx || defaultCtx());
      postPanelState();
    });

    // 欢迎语:首次访问(本地标记不存在)时显示一次性气泡提示,不阻塞挂件
    if (cfg.welcome) {
      try {
        if (!localStorage.getItem('forge_cs_welcomed_' + channel)) {
          var welcome = document.createElement('div');
          welcome.textContent = cfg.welcome;
          welcome.style.cssText = 'position:fixed;' + side + ':' + (bottom + 66) + 'px;bottom:' + (bottom + 130)
            + 'px;z-index:2147482999;max-width:' + w + 'px;padding:10px 12px;border-radius:10px;background:#fff;'
            + 'box-shadow:0 6px 20px rgba(0,0,0,.16);font:13px/1.5 -apple-system,BlinkMacSystemFont,sans-serif;color:#1f2937;';
          document.body.appendChild(welcome);
          setTimeout(function () { if (welcome.parentNode) welcome.parentNode.removeChild(welcome); }, 8000);
          localStorage.setItem('forge_cs_welcomed_' + channel, '1');
        }
      } catch (e) { /* localStorage 不可用时跳过欢迎语 */ }
    }

    document.body.appendChild(launcher);
    document.body.appendChild(panel);
    document.body.appendChild(badge);
    paintBadge();
    window.addEventListener('message', onHostMessage);
  }

  // 挂件 ↔ iframe 的未读计数:iframe 内客服页通过 postMessage 通知新消息
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || d.__forgeWidget !== true) return;
    if (!frame || e.source !== frame.contentWindow) return;
    if (d.type === 'ready') {
      postCtx(lastCtx || defaultCtx());
      postPanelState();
      return;
    }
    if (d.type === 'unread' && !opened) {
      unread += 1; saveUnread(); paintBadge();
    }
  });

  function start() {
    // 拉配置失败也照常挂载(默认配置),保证挂件永不出不来
    var req = base ? new XMLHttpRequest() : null;
    if (req) {
      try {
        req.open('GET', cfgUrl, true);
        req.onload = function () {
          try {
            if (req.status === 200) {
              var r = JSON.parse(req.responseText);
              if (r && r.code === 200 && r.data) {
                cfg.theme = r.data.theme || cfg.theme;
                cfg.mode = r.data.mode === 'sidebar' ? 'sidebar' : 'bubble';
                cfg.welcome = r.data.welcome || '';
                cfg.config = r.data.config || '';
                cfg.enabled = r.data.enabled !== false;
                if (r.data.lang === 'en_US' && !script.getAttribute('data-lang')) {
                  lang = 'en_US'; WT = WIDGET_I18N.en_US;
                  title = script.getAttribute('data-title') || WT.title;
                }
              }
            }
          } catch (e) { /* 配置解析失败用默认 */ }
          if (cfg.enabled) mount();
        };
        req.onerror = function () { mount(); };
        req.send();
      } catch (e) { mount(); }
    } else {
      // 无 apiBase 时(旧用法同域相对路径)用 fetch 兜底
      if (window.fetch) {
        fetch(cfgUrl).then(function (r) { return r.json(); }).then(function (r) {
          if (r && r.code === 200 && r.data) {
            cfg.theme = r.data.theme || cfg.theme;
            cfg.mode = r.data.mode === 'sidebar' ? 'sidebar' : 'bubble';
            cfg.welcome = r.data.welcome || '';
            cfg.config = r.data.config || '';
            cfg.enabled = r.data.enabled !== false;
            if (r.data.lang === 'en_US' && !script.getAttribute('data-lang')) {
              lang = 'en_US'; WT = WIDGET_I18N.en_US;
              title = script.getAttribute('data-title') || WT.title;
            }
          }
          if (cfg.enabled) mount();
        }).catch(function () { mount(); });
      } else { mount(); }
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
