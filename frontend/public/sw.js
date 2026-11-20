/* ForgeAgent PWA Service Worker (D3) — 手写,零依赖,随 public/ 原样打包
 *
 * 设计原则(保守):
 * 1. 只缓存「应用外壳 + 静态资源」,任何 API 一律 network-only。
 *    客服/帮助中心的接口响应含访客消息、手机号等 PII,落盘缓存等于把隐私写进
 *    浏览器磁盘,且会造成"下线内容仍可见"的一致性事故 —— 因此白名单式缓存。
 * 2. 导航请求 network-first:在线始终拿最新 HTML(hash 资源随发布变化),
 *    离线回退缓存的外壳,页面可打开但数据区显示离线态。
 * 3. /chat-api 等 SSE 流式通道绝不进 SW(SW 介入会破坏逐字流式)。
 */
'use strict';

var VERSION = 'forge-pwa-v1';
var SHELL_CACHE = VERSION + '-shell';
var RUNTIME_CACHE = VERSION + '-runtime';

var PRECACHE = ['/', '/index.html', '/manifest.webmanifest', '/favicon.ico',
  '/customer-service/index.html'];

/* 永不缓存的路径前缀:API / 回调 / SSE / MCP */
var NEVER_CACHE = [
  /^\/prod-api\//, /^\/chat-api\//, /^\/chat-channel\//, /^\/chat-voice\//,
  /^\/help-api\//, /^\/workflow-api\//, /^\/mcp(\/|$)/, /^\/agent\//
];

/* 可缓存的静态资源扩展名 */
var STATIC_EXT = /\.(?:js|css|woff2?|ttf|otf|eot|png|jpe?g|gif|svg|webp|ico|webmanifest)$/;

function isNeverCache(url) {
  for (var i = 0; i < NEVER_CACHE.length; i++) {
    if (NEVER_CACHE[i].test(url.pathname)) return true;
  }
  return false;
}

self.addEventListener('install', function (event) {
  event.waitUntil(
    caches.open(SHELL_CACHE).then(function (cache) {
      /* 单个资源失败不能让整个安装失败(例如 IE 兜底页不存在) */
      return Promise.all(PRECACHE.map(function (p) {
        return cache.add(new Request(p, { cache: 'reload' })).catch(function () { /* 忽略 */ });
      }));
    }).then(function () { return self.skipWaiting(); })
  );
});

self.addEventListener('activate', function (event) {
  event.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.filter(function (k) {
        return k.indexOf(VERSION) !== 0;
      }).map(function (k) { return caches.delete(k); }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function (event) {
  var req = event.request;
  if (req.method !== 'GET') return;

  var url;
  try { url = new URL(req.url); } catch (e) { return; }
  if (url.origin !== self.location.origin) return;   /* 第三方资源不接管 */
  if (isNeverCache(url)) return;                      /* API 走浏览器原生 */

  /* 导航请求:网络优先,离线回退外壳 */
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).then(function (resp) {
        return resp;
      }).catch(function () {
        var fallback = url.pathname.indexOf('/customer-service/') === 0
          ? '/customer-service/index.html' : '/index.html';
        return caches.match(fallback).then(function (hit) {
          if (hit) return hit;
          return new Response(
            '<!doctype html><meta charset="utf-8"><title>离线</title>'
            + '<body style="font:14px/1.6 -apple-system,sans-serif;padding:24px;color:#374151">'
            + '<h3>当前离线</h3><p>网络恢复后刷新即可继续使用。会话内容不会离线缓存。</p>',
            { headers: { 'Content-Type': 'text/html; charset=utf-8' } }
          );
        });
      })
    );
    return;
  }

  /* 静态资源:stale-while-revalidate */
  if (STATIC_EXT.test(url.pathname)) {
    event.respondWith(
      caches.match(req).then(function (hit) {
        var refresh = fetch(req).then(function (resp) {
          if (resp && resp.status === 200) {
            var copy = resp.clone();
            caches.open(RUNTIME_CACHE).then(function (c) { c.put(req, copy); });
          }
          return resp;
        }).catch(function () { return hit; });
        return hit || refresh;
      })
    );
  }
});
