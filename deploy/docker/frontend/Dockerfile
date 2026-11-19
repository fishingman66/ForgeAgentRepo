# syntax=docker/dockerfile:1
# ---------- 构建阶段 ----------
# 与 frontend/package.json 对齐：engines node >=22.22.0 / pnpm >=12.0.0，
# packageManager 固定 pnpm@12.16.1；Vite 8 / TypeScript 6 / vue-tsc 3 在 node:20 下会构建失败。
FROM node:22-alpine AS build
WORKDIR /build

# 固定 corepack 版本避免漂移；把 store 指到固定路径，供下面的 BuildKit 缓存挂载复用
RUN corepack enable && corepack prepare pnpm@12.16.1 --activate \
    && pnpm config set store-dir /pnpm/store \
    && node -v && pnpm -v

# 构建上下文没有 .dockerignore：本机 node_modules(可能含 Windows/macOS 原生二进制)
# 与 dist 会被一起 COPY 进来，覆盖容器里安装的依赖，esbuild 等在 alpine 下直接不可用。
# 因此先整体 COPY、清掉本地产物，再由 lockfile 在容器内重装；store 走缓存挂载，
# 源码变化不会导致依赖重新下载。
COPY . .
RUN --mount=type=cache,target=/pnpm/store \
    rm -rf node_modules dist && pnpm install --frozen-lockfile

# 演示模式构建标志：为 true 时登录页显示「演示账号一键登录」
# (由 compose 的 VITE_APP_DEMO_LOGIN 传入)。build:prod 已前置 vue-tsc --noEmit，
# 类型不过则构建终止。
ARG VITE_APP_DEMO_LOGIN=false
ENV VITE_APP_DEMO_LOGIN=$VITE_APP_DEMO_LOGIN
RUN pnpm build:prod

# ---------- 运行阶段 ----------
FROM nginx:alpine
LABEL org.opencontainers.image.title="forge-agent-frontend"

COPY --from=build /build/dist /usr/share/nginx/html
# nginx.conf 由 compose 的 additional_contexts(deploy: ./frontend) 提供
COPY --from=deploy nginx.conf /etc/nginx/conf.d/default.conf

# 构建期校验反代配置(含免登录入口正则与 SSE 长读代理)：语法错误直接让镜像构建失败，
# 而不是等容器起来才发现
RUN nginx -t

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
