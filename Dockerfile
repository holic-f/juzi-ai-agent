# ===================== 构建阶段 =====================
# 直接用预装 Maven + JDK 21 的官方镜像（与本地 Maven/JDK 版本一致）
FROM maven:3.9-amazoncorretto-21 AS builder
WORKDIR /build

# 1) 容器内直连 Maven Central 不稳定，构建阶段统一走阿里云镜像（换到网络良好的环境可删掉这行）
COPY docker/maven-settings.xml /root/.m2/settings.xml

# 2) 先只复制 pom.xml 解析依赖，后续只改代码时可复用这一层缓存
#    --mount=type=cache 把 ~/.m2 缓存在构建器里：网络抖动导致失败后重跑可续传，不必从头下载
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests \
    -Dmaven.wagon.http.retryHandler.count=5 \
    dependency:go-offline

# 3) 复制源码打包。-DskipTests 是必须的：本项目测试会调用真实大模型/搜索 API（见 AGENTS.md）
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests \
    -Dmaven.wagon.http.retryHandler.count=5 \
    clean package

# ===================== 运行阶段 =====================
# 只保留 JDK 21，不带 Maven，镜像更小
FROM amazoncorretto:21
WORKDIR /app

# 生产配置：默认激活 prod；数据库/模型密钥等通过环境变量注入
# 也可把真实配置挂到 /app/config/application-prod.yml（Spring Boot 会自动读取该位置，且优先级更高）
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

COPY --from=builder /build/target/juzi-ai-agent-0.0.1-SNAPSHOT.jar /app/app.jar

# 应用会把对话记忆、生成文件写到 ${user.dir}/tmp，提前建好目录
RUN mkdir -p /app/tmp

EXPOSE 8123

# exec 形式：java 即 PID 1，docker stop 能收到 SIGTERM 优雅停机；额外参数直接追加在 docker run 末尾
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
