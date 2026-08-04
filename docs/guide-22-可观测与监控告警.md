# DevMind 可观测与监控告警（阶段 R）

## 背景

阶段 R 之前，系统运行状态只能靠 `health` 端点和日志人工观察，缺少指标化采集与告警。本阶段接入 Prometheus 指标，让请求量、延迟、模型调用失败率、限流/锁定事件、数据库连接池等可观测，并提供告警规则与面板示例。

## 功能与实现

### 1. Prometheus 指标暴露

- 依赖：`micrometer-registry-prometheus`；
- 端点：`/actuator/prometheus`（已加入 `management.endpoints.web.exposure.include`）。

### 2. 自定义指标

| 指标 | 类型 | 来源 | 说明 |
| --- | --- | --- | --- |
| `devmind.model.calls.duration` | Timer | ChatRouter | 模型聊天调用耗时（成功/回退均计时） |
| `devmind.model.calls.failed` | Counter | ChatRouter | 模型调用失败次数（主/备用均计） |
| `devmind.model.calls` / `devmind.model.tokens` | Counter | ModelUsageService | 模型调用次数与 Token（按 scene/model） |
| `devmind.rate.limited` | Counter | RateLimitInterceptor | 被限流拦截的请求次数 |
| `devmind.login.locked` | Counter | LoginAttemptService | 账号被锁定的次数 |

### 3. 自动指标（Spring Boot 内置）

- `http.server.requests`：请求量、延迟（直方图，可算 P95）；
- `hikaricp.connections.*`：数据库连接池活跃/空闲/上限；
- `jvm.*`：堆内存、GC 等；
- `system.*`：CPU、磁盘等。

### 4. 告警规则 `prometheus/rules.yml`

| 规则 | 表达式要点 | 严重级 |
| --- | --- | --- |
| DevMindServiceDown | `up{job="devmind-app"} == 0` | critical |
| DevMindModelFailureRateHigh | 5 分钟失败率 > 50% | warning |
| DevMindRateLimitedBurst | 每分钟限流拦截 > 10 | warning |
| DevMindLoginLockedBurst | 每分钟锁定 > 5（疑似暴力登录） | critical |
| DevMindDbConnectionsHigh | 活跃连接 > 90% 上限 | warning |
| DevMindErrorRateHigh | 5xx 错误率 > 5% | warning |

### 5. Grafana 面板示例 `prometheus/grafana-dashboard.json`

包含：HTTP QPS、P95 延迟、模型调用、模型失败率、限流与锁定、数据库连接池、JVM 堆内存。

## 验证结果

`/actuator/prometheus` 实测输出（触发一次 SQL 诊断 + 6 次错误登录后）：

```
devmind_model_calls_duration_seconds_count 1
devmind_model_calls_duration_seconds_sum 23.943    # 真实模型链路耗时
devmind_model_calls_total{model="glm-4.7-flash",scene="sql"} 1.0
devmind_model_calls_failed_total 0.0
devmind_login_locked_total 1.0                     # 第 5 次失败触发锁定
devmind_rate_limited_total 0.0
devmind_http_requests_seconds_count{...} ...        # HTTP 请求指标
```

- 后端 51/51 测试通过。

## 使用方式

```bash
# 抓取指标
curl http://localhost:8080/actuator/prometheus

# Prometheus 抓取配置（scrape_configs）
#  - job_name: devmind-app
#    metrics_path: /actuator/prometheus
#    static_configs:
#      - targets: ['localhost:8080']

# 告警规则
# rule_files: ['/etc/prometheus/rules.yml']  # 挂载 prometheus/rules.yml

# Grafana 导入面板
# 导入 prometheus/grafana-dashboard.json
```
