# syntax=docker/dockerfile:1
# ForgeAgent 后端运行镜像（单阶段：先在宿主机 mvn package，再以 backend/forge-admin 为
# 构建上下文执行 docker build）。与 deploy/docker/backend/Dockerfile 的多阶段源码构建是
# 两条并存的用法：这里只打包已产出的 jar，适合 CI 已构建好产物的场景。
#
# 基础镜像沿用 BellSoft Liberica JDK 21(与父 pom java.version=21 一致)的 Rocky + CDS 变体；
# 可选的 JDK 25 / GraalVM native 镜像见历史注释，未启用。
# FROM bellsoft/liberica-openjdk-rocky:25.0.4-cds
# FROM findepi/graalvm:java21-native

FROM bellsoft/liberica-openjdk-rocky:21.0.12-cds

LABEL maintainer="智造 ForgeAgent" \
      org.opencontainers.image.title="forgeagent-admin"

RUN mkdir -p /forge/server/logs \
    /forge/server/temp \
    /forge/skywalking/agent

WORKDIR /forge/server

ENV SERVER_PORT=8080 \
    SNAIL_JOB_PORT=28080 \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_OPTS=""

EXPOSE ${SERVER_PORT}
# 暴露 snail 客户端端口，用于定时任务调度中心通信
EXPOSE ${SNAIL_JOB_PORT}

# 用 COPY 而非 ADD：这里只是复制本地 jar，不需要 ADD 的解包 / URL 语义
COPY ./target/forge-admin.jar ./app.jar

# 旧写法把 # 注释夹在 ENTRYPOINT 的续行里，shell form 下 `#` 会注释掉后面的
# -jar app.jar，容器可能启动后什么都不执行；注释已移出指令。
# 用 exec form + /bin/bash -c 既保留 ${SERVER_PORT}/${JAVA_OPTS} 可覆盖，
# 又通过 `exec java` 让 JVM 成为 PID 1 接收 SIGTERM(Spring Boot 优雅停机)。
# 可选 SkyWalking 通过 JAVA_OPTS 注入，例如
#   -javaagent:/forge/skywalking/agent/skywalking-agent.jar -Dskywalking.agent.service_name=forge-server
# JAVA_OPTS 里建议显式给 -Xmx，并为 milvus-sdk-java 3.0.12 自带的 shaded
# gRPC/Netty 直接内存、线程栈留出容器内存余量。
ENTRYPOINT ["/bin/bash", "-c", "exec java -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8 -Dserver.port=${SERVER_PORT} -Dsnail-job.port=${SNAIL_JOB_PORT} -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/forge/server/logs -XX:+UseZGC ${JAVA_OPTS} -jar /forge/server/app.jar"]
