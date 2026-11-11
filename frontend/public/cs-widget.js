/* ForgeAgent 智能客服网页挂件 (C3) — 零依赖,随 public/ 原样打包
   用法: <script src="/cs-widget.js" data-channel="你的渠道编码" data-api-base="" data-title="在线客服"></script> */
(function () {
  'use strict';
  var script = document.currentScript || (function () {
    var all = document.getElementsByTagName('script');
    return all[all.length - 1];
  })();
  var channel = script.getAttribute('data-channel') || 'default';
  var base = script.getAttribute('data-api-base') || '';
  var title = script.getAttribute('data-title') || '在线客服';
  var page = base + '/customer-service/index.html?channel=' + encodeURIComponent(channel)
    + '&base=' + encodeURIComponent(base) + '&title=' + encodeURIComponent(title);

  var launcher = document.createElement('button');
  launcher.type = 'button';
  launcher.setAttribute('aria-label', '打开' + title);
  launcher.textContent = '客服';
  launcher.style.cssText = 'position:fixed;right:24px;bottom:24px;z-index:2147483000;width:56px;height:56px;'
    + 'border:0;border-radius:28px;background:#2563eb;color:#fff;font-size:14px;cursor:pointer;'
    + 'box-shadow:0 6px 18px rgba(37,99,235,.35)';

  var panel = document.createElement('div');
  panel.style.cssText = 'position:fixed;right:24px;bottom:92px;z-index:2147483000;width:360px;height:520px;'
    + 'border-radius:12px;overflow:hidden;box-shadow:0 12px 36px rgba(0,0,0,.22);display:none;background:#fff';
  var frame = document.createElement('iframe');
  frame.src = page;
  frame.title = title;
  frame.style.cssText = 'width:100%;height:100%;border:0';
  frame.setAttribute('allow', 'clipboard-write');
  panel.appendChild(frame);

  var opened = false;
  launcher.addEventListener('click', function () {
    opened = !opened;
    panel.style.display = opened ? 'block' : 'none';
    launcher.textContent = opened ? '收起' : '客服';
  });

  function mount() {
    document.body.appendChild(launcher);
    document.body.appendChild(panel);
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount);
  } else {
    mount();
  }
})();
