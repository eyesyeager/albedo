# syntax=docker/dockerfile:1
# ==========================================================================
# Albedo 前端镜像（Dockerfile）
# 产物为纯静态 html/css/js（架构红线），由 nginx:alpine 托管静态产物。
# 前后端分域名：前端站点 albedo-{tenant}，API 走 api-albedo-{tenant}
# （request.ts 运行时推导；VITE_API_DOMAIN 留空）。本容器可仍反代 /api/、/site/ 兜底同源链路。
# ==========================================================================

# ---------- 阶段 1：构建 ----------
FROM node:22-alpine AS build
WORKDIR /app

# 先装依赖（package-lock.json 已提交，用 npm ci 保证可复现）
COPY package.json package-lock.json ./
RUN npm ci

# 再拷源码（利用层缓存：依赖不变时不重建）
COPY . .
RUN npm run build

# ---------- 阶段 2：运行 ----------
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80 443
