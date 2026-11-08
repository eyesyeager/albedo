# 仅用于「打包出 html/css/js」，不参与 docker-compose 部署。
# 产物在容器内 /dist，两种导出方式任选其一：
#
#   1) 直接导出到本机（需 BuildKit，推荐）：
#      DOCKER_BUILDKIT=1 docker build -f frontend/Dockerfile \
#        --output type=local,dest=./frontend/dist .
#
#   2) 先构建镜像再 docker cp：
#      docker build -f frontend/Dockerfile -t albedo-frontend-dist:local .
#      docker create --name albedo-dist albedo-frontend-dist:local
#      docker cp albedo-dist:/dist/. ./frontend/dist && docker rm albedo-dist
#
# 也可以不用 Docker，直接本机构建（产物同为 frontend/dist）：
#   cd frontend && npm ci && npm run build

FROM node:20-alpine AS build

WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# 前端全部使用同源相对路径 /api/v1，构建期无需注入任何后端地址。
RUN npm run build

FROM alpine:3.20 AS export

COPY --from=build /app/frontend/dist /dist
CMD ["/bin/sh", "-c", "echo '静态产物在 /dist，请用 --output 或 docker cp 导出'"]
