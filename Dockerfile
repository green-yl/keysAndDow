# keysAndDwd 授权服务 Dockerfile
# 多阶段构建

# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# 复制 Maven 配置
COPY java-service/pom.xml .

# 下载依赖（利用缓存）
RUN mvn dependency:go-offline -B

# 复制源码
COPY java-service/src ./src

# 构建
RUN mvn clean package -DskipTests -B

# 运行阶段
FROM eclipse-temurin:17-jre

WORKDIR /app

# 创建必要目录
RUN mkdir -p /app/sources/uploaded /app/sources/extracted /app/sources/thumbnails \
    /app/temp-uploads /app/logs /app/configs/ssl /app/configs/domains

# 从构建阶段复制 JAR
COPY --from=build /app/target/*.jar app.jar

# 复制静态资源（管理页面）
COPY java-service/src/main/resources/static /app/static

# 复制数据库初始化脚本
COPY java-service/src/main/resources/schema.sql /app/schema.sql

# 设置时区
RUN ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo Asia/Shanghai > /etc/timezone

# 暴露端口
EXPOSE 3003

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:3003/api/health || exit 1

# 启动命令
CMD ["java", "-jar", "app.jar"]

