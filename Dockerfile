# ============================================================
#  Jhermes CLI 容器镜像（多阶段构建：可选，用于"全量容器化"）
#
#  默认部署只需 pgvector（见 docker-compose.yml），Jhermes 在
#  Windows/WSL 用 npm 装即可。本 Dockerfile 面向想在纯容器
#  环境（WSL/Linux/CI/服务器）里连 Jhermes 一起跑的用户。
#
#  交互式运行（挂载你的配置与数据）：
#    docker build -t jhermes .
#    docker run --rm -it \
#      -v ~/.jhermes:/root/.jhermes \
#      --network host \
#      jhermes
#
#  注意：
#  - API Key 通过挂载的 ~/.jhermes/config.yaml 提供，绞不写进镜像
#  - FetchPageTool 依赖的 Playwright 浏览器二进制未装（镜像瘦身），
#    抓取会自动降级到 Jsoup，绝大多数静态页面无影响
# ============================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# 先拷贝 pom 利用层缓存下载依赖（源码变动时无需重下依赖）
COPY Jhermes/pom.xml ./Jhermes/pom.xml
RUN mvn -f Jhermes/pom.xml -q dependency:go-offline -B
COPY Jhermes/src ./Jhermes/src
RUN mvn -f Jhermes/pom.xml -q clean package -DskipTests -B

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# shade 产出的 fat jar（含全部依赖）
COPY --from=builder /build/Jhermes/target/Jhermes-*.jar /app/hermes.jar

# JVM 强制 UTF-8，避免中文输出乱码（对齐 Jhermes.cmd 的启动参数）
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
# 交互式 CLI 需要 TTY；数据目录 ~/.jhermes 由运行时 -v 挂载进来
ENTRYPOINT ["java", "-jar", "/app/hermes.jar"]
