# EFAK-AI 项目开发规范

## 项目概述

**EFAK-AI (Eagle For Apache Kafka - AI Enhanced)** 是一款融合人工智能技术的开源 Kafka 智能监控与管理平台，提供智能化、可视化、自动化的全方位运维解决方案。

- **版本**: 5.0.0
- **主要语言**: Java 17
- **框架**: Spring Boot 3.4.5
- **构建工具**: Maven 3.6+
- **许可证**: Apache License 2.0

## 项目结构

```
EFAK/
├── efak-ai/          # AI告警功能模块（钉钉、飞书、企业微信、Webhook）
├── efak-core/        # 核心功能模块（Kafka连接、集群监控、JMX管理）
├── efak-dto/         # 数据传输对象（DTO/VO）
├── efak-tool/        # 工具类模块（通用工具、版本管理）
├── efak-web/         # Web应用模块（主应用入口、控制器、服务、配置）
├── pom.xml           # Maven父POM配置
├── docker-compose.yml # Docker编排配置
├── Dockerfile        # Docker镜像构建
├── build-package.sh  # 打包构建脚本
└── quick-start.sh    # 快速启动脚本
```

## 技术栈

### 后端技术
- **框架**: Spring Boot 3.4.5
- **消息队列**: Apache Kafka 4.0.0
- **数据库**: MySQL 8.0+ (主数据库)
- **缓存/分布式锁**: Redis 6.0+ / Redisson 3.50.0
- **ORM**: MyBatis 3.0.4
- **安全**: Spring Security
- **任务调度**: Spring Quartz
- **模板引擎**: Thymeleaf

### 前端技术
- Thymeleaf模板
- 静态资源位于 `efak-web/src/main/resources/statics/`

### 开发工具
- **代码简化**: Lombok 1.18.26
- **API客户端**: RestTemplate
- **JSON处理**: Jackson (含JSR310时间模块)

## 核心功能模块

### 1. efak-web（主应用模块）

**主类**: `org.kafka.eagle.web.KafkaEagle`
- 应用启动入口
- 扫描包路径: `org.kafka.eagle`
- Mapper扫描: `org.kafka.eagle.web.mapper`
- 启用方法级安全和定时任务

**主要控制器**:
- `ChatController` / `ChatStreamController` - AI对话功能（SSE流式响应）
- `ClusterController` - Kafka集群管理
- `BrokerController` / `BrokerMetricsController` - Broker监控
- `TopicController` - Topic管理
- `ConsumerController` - 消费者组管理
- `AlertController` - 告警管理
- `DashboardController` - 仪表盘
- `DistributedTaskController` - 分布式任务管理
- `HealthController` - 健康检查

**核心服务**:
- `AIGatewayService` - AI网关服务
- `ChatService` / `ChatStreamService` - AI聊天服务
- `ClusterService` - 集群服务
- `BrokerService` / `BrokerMetricsService` - Broker监控服务
- `DistributedTaskSchedulerService` - 分布式任务调度
- `TaskExecutorManager` - 任务执行管理器
- `AlertService` - 告警服务
- `PerformanceMonitorService` - 性能监控服务
- `DataCleanupService` - 数据清理服务

**配置类**:
- `DataSourceConfig` - 数据源配置
- `MyBatisConfig` - MyBatis配置
- `RedisConfig` - Redis配置
- `RestTemplateConfig` - RestTemplate配置
- `DistributedTaskConfig` - 分布式任务配置
- `WebMvcConfig` - Spring MVC配置

### 2. efak-core（核心模块）

**主要功能**:
- Kafka集群连接与管理
- JMX连接管理（`JmxConnectionManager`）
- Kafka数据获取（`KafkaClusterFetcher`）
- Kafka存储插件（`KafkaStoragePlugin`）
- Schema工厂与初始化

**依赖**:
- `efak-tool` - 工具模块
- `efak-dto` - DTO模块
- Apache Kafka Client 4.0.0

### 3. efak-ai（AI告警模块）

**告警渠道实现**:
- `DingTalkAlertSender` - 钉钉告警
- `FeishuAlertSender` - 飞书告警
- `WeChatWorkAlertSender` - 企业微信告警
- `CustomWebhookAlertSender` - 自定义Webhook告警

### 4. efak-dto（数据传输对象）

定义系统中的数据传输对象、值对象和实体类。

### 5. efak-tool（工具模块）

通用工具类和版本管理工具（`KafkaEagleVersion`）。

## 配置说明

### 应用配置 (application.yml)

**服务配置**:
```yaml
server:
  port: 28888
  servlet:
    context-path: /
```

**数据库配置**:
```yaml
spring:
  datasource:
    url: jdbc:mysql://192.168.200.100:3306/efak_ai?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: root
    password: mysql_4w2NFb
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      connection-test-query: SELECT 1
      validation-timeout: 3000
      connection-timeout: 10000
```

**Redis配置**:
```yaml
spring:
  data:
    redis:
      host: 192.168.200.100
      port: 6379
      database: 0
      timeout: 3000ms
      password: redis_kit3sz
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5
          max-wait: 3000ms
```

**分布式任务配置**:
```yaml
efak:
  distributed:
    task:
      offline-timeout: 120           # 节点离线超时（秒）
      shard-result-wait-time: 30     # 分片结果等待时间（秒）
      shard-result-expire-minutes: 10 # 分片结果过期时间（分钟）
  data-retention-days: 7             # 数据保留天数
```

## 开发规范

### 代码规范

1. **Java版本**: 使用Java 17特性
2. **编码**: UTF-8
3. **注解使用**:
   - 使用 Lombok 简化代码（@Data, @Slf4j等）
   - 使用 Spring 注解（@Service, @Controller, @RestController等）
   - 使用 MyBatis 注解或XML映射

4. **命名规范**:
   - 类名: 大驼峰（PascalCase）
   - 方法/变量: 小驼峰（camelCase）
   - 常量: 全大写下划线分隔（UPPER_SNAKE_CASE）
   - 包名: 全小写

5. **包结构**:
   ```
   org.kafka.eagle.[module]
   ├── controller/    # 控制器
   ├── service/       # 服务层
   ├── mapper/        # 数据访问层
   ├── config/        # 配置类
   ├── scheduler/     # 调度器
   ├── security/      # 安全配置
   └── api/           # API接口（core模块）
   ```

### 日志规范

使用 SLF4J + Logback:
```java
@Slf4j
public class ExampleService {
    public void doSomething() {
        log.debug("调试信息");
        log.info("一般信息");
        log.warn("警告信息");
        log.error("错误信息", exception);
    }
}
```

**日志级别配置**:
- Kafka相关: WARN/ERROR
- 业务服务: DEBUG（开发环境）/ INFO（生产环境）
- 数据库事务: WARN
- 连接池: ERROR

### 异常处理

使用统一的异常处理器 `GlobalExceptionHandler`:
- 捕获并处理所有控制器异常
- 返回统一的错误响应格式
- 记录详细的错误日志

### 安全规范

1. 使用 Spring Security 进行认证和授权
2. 方法级安全: `@EnableMethodSecurity(securedEnabled = true)`
3. 密码加密存储
4. 密码重置工具: `/password-tool`

## 构建与部署

### 本地开发

```bash
# 1. 克隆项目
git clone https://github.com/smartloli/EFAK.git
cd EFAK

# 2. 初始化数据库
mysql -u root -p
CREATE DATABASE efak_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE efak_ai;
SOURCE efak-web/src/main/resources/sql/ke.sql;

# 3. 修改配置文件
# 编辑 efak-web/src/main/resources/application.yml

# 4. 编译项目
mvn clean compile -DskipTests

# 5. 运行应用
cd efak-web
mvn spring-boot:run
```

### 构建安装包

```bash
# 执行构建脚本
./build-package.sh

# 生成: efak-5.0.0.tar.gz
```

**安装包结构**:
```
efak-5.0.0/
├── bin/       # 启动脚本（start.sh, stop.sh, restart.sh, status.sh）
├── config/    # 配置文件（application.yml）
├── libs/      # JAR包
├── logs/      # 日志目录
└── sql/       # SQL脚本
```

### Docker部署

```bash
# 一键启动（包含MySQL、Redis）
docker-compose up -d

# 查看日志
docker-compose logs -f efak-ai

# 访问应用
# http://localhost:8080
# 默认账号: admin / admin
```

**Docker环境变量**:
- `SPRING_DATASOURCE_URL` - 数据库连接URL
- `SPRING_DATASOURCE_USERNAME` - 数据库用户名
- `SPRING_DATASOURCE_PASSWORD` - 数据库密码
- `SPRING_DATA_REDIS_HOST` - Redis主机
- `SPRING_DATA_REDIS_PORT` - Redis端口
- `SERVER_PORT` - 应用端口
- `JAVA_OPTS` - JVM参数

## API说明

### 健康检查
```
GET /health/check
```

### AI助手
```
GET /api/chat/stream?modelId=1&message=分析集群性能&clusterId=xxx&enableCharts=true
返回: Server-Sent Events 流式响应
```

### 集群管理
```
GET /api/cluster/list       # 获取集群列表
GET /api/cluster/info       # 获取集群详情
GET /api/cluster/brokers    # 获取Broker信息
```

### Topic管理
```
GET /api/topic/list         # 获取Topic列表
GET /api/topic/metrics      # 获取Topic指标
```

### 消费者组
```
GET /api/consumer/groups    # 获取消费者组信息
```

## 常用命令

### Maven命令
```bash
# 编译
mvn clean compile

# 打包（跳过测试）
mvn clean package -DskipTests

# 运行测试
mvn test

# 安装到本地仓库
mvn clean install
```

### 应用管理（tar.gz部署）
```bash
./bin/start.sh      # 启动应用
./bin/stop.sh       # 停止应用
./bin/restart.sh    # 重启应用
./bin/status.sh     # 查看状态

# 查看日志
tail -f logs/efak-ai.log

# 查看进程（进程名显示为 KafkaEagle）
ps aux | grep KafkaEagle
```

### Docker命令
```bash
# 查看运行状态
docker-compose ps

# 停止服务
docker-compose down

# 重启服务
docker-compose restart efak-ai

# 查看日志
docker-compose logs -f efak-ai
```

## 性能调优

### JVM参数建议

**小内存（2GB）**:
```bash
JAVA_OPTS="-Xms256m -Xmx1g -XX:+UseG1GC"
```

**中等内存（4GB）**:
```bash
JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC"
```

**大内存（8GB+）**:
```bash
JAVA_OPTS="-Xms1g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

## 故障排查

### 常见问题

1. **应用启动失败**: 检查数据库连接配置和Redis连接
2. **Kafka连接失败**: 检查Kafka集群配置和网络连通性
3. **JMX连接失败**: 确保Kafka Broker开启JMX端口
4. **分布式任务不执行**: 检查Redis连接和节点注册状态

### 日志位置
- **开发环境**: 控制台输出
- **生产环境**: `logs/efak-ai.log`
- **Docker环境**: `docker-compose logs -f efak-ai`

## 贡献指南

1. Fork 项目
2. 创建特性分支: `git checkout -b feature/AmazingFeature`
3. 提交更改: `git commit -m 'Add some AmazingFeature'`
4. 推送到分支: `git push origin feature/AmazingFeature`
5. 提交 Pull Request

## 相关链接

- **官网**: https://www.kafka-eagle.org/
- **GitHub**: https://github.com/smartloli/EFAK
- **问题反馈**: https://github.com/smartloli/EFAK/issues
- **详细部署文档**: efak-web/src/main/resources/docs/DEPLOY.md
- **功能预览**: efak-web/src/main/resources/docs/FEATURE_PREVIEW.md

## 许可证

Apache License 2.0

---

**EFAK-AI** - 让 Kafka 监控更智能，让运维更高效！
