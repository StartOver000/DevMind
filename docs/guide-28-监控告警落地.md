# DevMind 监控告警落地部署（阶段 W）

## 背景

阶段 R 已提供 Prometheus 指标与示例文件，但监控栈未实际部署。本阶段把 Prometheus + Grafana 真正接入 Docker Compose，开箱即用。

## 配置与部署

### 目录结构

```
prometheus/
  prometheus.yml          # 抓取配置（抓 devmind-app:8080/actuator/prometheus）
  rules.yml               # 告警规则（服务不可达/模型失败率/限流/暴力登录/连接池/5xx）
  grafana-dashboard.json  # 面板源文件
grafana/
  provisioning/
    datasources/datasources.yml   # 数据源（Prometheus -> http://prometheus:9090）
    dashboards/dashboards.yml     # 面板 provider（file -> /var/lib/grafana/dashboards）
  dashboards/devmind-dashboard.json  # 面板（自动导入）
```

### docker-compose 新增服务

| 服务 | 镜像 | 端口 | 说明 |
| --- | --- | --- | --- |
| prometheus | prom/prometheus | 9090 | 抓取 devmind-app 指标 + 加载告警规则 |
| grafana | grafana/grafana | 3000 | 预置数据源与面板，默认 admin/admin123 |

## 验证结果

| 项 | 实测 |
| --- | --- |
| Prometheus target | `devmind-app` health=up，scrape `http://devmind-app:8080/actuator/prometheus` |
| Prometheus 指标 | `up{job="devmind-app"}=1`；`devmind_model_calls_total{scene="chat"}=1` 可查 |
| Grafana 数据源 | Prometheus（url http://prometheus:9090）就绪 |
| Grafana 面板 | 「DevMind 监控面板」自动加载（provisioning） |
| 回归 | `docker compose config` 通过，应用 health=UP |

## 使用方式

```bash
docker compose up -d                        # 全栈启动（含监控）
docker compose up -d prometheus grafana     # 仅监控栈

# Prometheus 控制台
open http://localhost:9090
# 查询示例：up / devmind_model_calls_total / rate(devmind_model_calls_failed_total[5m])

# Grafana
open http://localhost:3000   # admin / admin123（可用环境变量 GF_ADMIN_USER/PASSWORD 覆盖）
# 面板：DevMind 文件夹 -> DevMind 监控面板
```

## 说明

- 告警规则通过 `rule_files` 挂载，Prometheus 每 15s 评估，命中即产生 alert（Alertmanager 不在本阶段范围，可后续接入）。
