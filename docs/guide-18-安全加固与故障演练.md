# DevMind 安全与稳定性加固（阶段 O）

## 背景

阶段 O 之前，系统已有登录（Bearer Token + BCrypt 密码），但缺少：
- 防暴力破解（登录无失败限制）；
- 接口防刷（无限流）；
- 密钥保护（API Key 明文写在配置/环境变量）；
- Token 长期有效（7 天固定过期，无刷新）；
- 敏感信息脱敏（日志/错误可能泄露密钥）。

本阶段完成上述加固，并增加故障演练脚本与文档。

## 功能与实现

### 1. 登录失败次数限制（防暴力破解）

- 组件：`security/LoginAttemptService`（内存计数，按用户名）。
- 同一用户名连续失败达到阈值后锁定一段时间；锁定期间即使密码正确也拒绝（429）。
- 配置（`devmind.security.*`，均可用环境变量覆盖）：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| `login-max-failures` | `DEVMIND_LOGIN_MAX_FAILURES` | 5 |
| `login-lock-minutes` | `DEVMIND_LOGIN_LOCK_MINUTES` | 10 |

- 实测：alice 连错 5 次后，正确密码也返回 `429 ACCOUNT_LOCKED`。

### 2. 接口限流（防高频请求）

- 组件：`security/RateLimitInterceptor`（固定窗口，按「路径 + 用户」维度，60 秒窗口）+ `config/WebConfig` 注册到 `/api/**`。
- 登录/注册接口使用独立更严格的配额；`limit <= 0` 表示关闭限流。
- 超限返回 `429 RATE_LIMITED`。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| `rate-limit-per-minute` | `DEVMIND_RATE_LIMIT_PER_MINUTE` | 120 |
| `rate-limit-login-per-minute` | `DEVMIND_RATE_LIMIT_LOGIN_PER_MINUTE` | 10 |

- 实测：同一来源登录接口连发超限后返回 `429 RATE_LIMITED`。

### 3. API Key 加密存储

- 组件：`security/SecretCipher`（AES/GCM-128）。
- 加密值形如 `enc:<base64(iv+密文)>`；配置中以 `enc:` 前缀标识，运行时解密。
- 主密钥来源优先级：`devmind.security.master-key`（环境变量 `DEVMIND_SECRET_MASTER_KEY`）> 本地自动生成并持久化的 `data/secret.key`。
- 应用点：`ZhipuRestModelGateway`（智谱 key）、`ChatRouter`（备用模型 key）取 key 时统一走 `SecretCipher.resolve()`，未加密的明文值原样透传（不破坏现有配置）。
- 管理员加密接口：`POST /api/admin/secrets/encrypt`（仅 ADMIN），把明文转成 `enc:` 值放入配置。

使用示例：

```bash
# 1) 管理员获取加密值
curl -X POST -H "Authorization: Bearer <token>" -H "X-User-Id: 1" \
  -H "Content-Type: application/json" \
  -d '{"value":"sk-your-real-key"}' \
  http://localhost:8080/api/admin/secrets/encrypt
# => {"encrypted":"enc:B4LMLOZRe+..."}

# 2) 把返回值写入配置/环境变量
# DEVMIND_ZHIPU_API_KEY="enc:B4LMLOZRe+..."
```

### 4. Token 刷新机制（滑动续期）

- `AuthService.resolveUser`：每次请求解析 token 时，若剩余有效期低于阈值（默认 2 天），自动延长到完整有效期（默认 7 天）。
- 用户长期活跃时无需重新登录；不活跃超过 7 天则过期失效（旧 token 删除）。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| `token-ttl-days` | `DEVMIND_TOKEN_TTL_DAYS` | 7 |
| `token-refresh-threshold-days` | `DEVMIND_TOKEN_REFRESH_THRESHOLD_DAYS` | 2 |

- 实测：把 token 过期时间改为 1 天后，调用 API 后自动延长到 7 天后。

### 5. 敏感字段脱敏

- 日志脱敏：`logback-spring.xml` 在 CONSOLE 与 FILE 两个 appender 的 pattern 中增加 `%replace(...)`，把 `Bearer <token>` 形式的密钥替换为 `Bearer ***`。
- 代码层：`security/SensitiveDataMasker`（掩码 `Bearer`、`api_key=`、`sk-` 前缀密钥），供错误消息脱敏使用。
- 密码存储：注册/登录使用 BCrypt（已有），密码不以明文落库。

### 6. 故障注入测试与恢复演练

- 脚本：`scripts/fault-drill.ps1`（`pwsh -File scripts/fault-drill.ps1`）。
- 演练项：
  1. 重启策略验证：确认 `restart: unless-stopped` 配置生效，容器停止后可拉起；
  2. 数据库中断自愈：停止 PostgreSQL → health DOWN → 恢复 PostgreSQL → health 自动 UP（Hikari 自动重连，应用无需重启）；
  3. 登录接口可用性。

**已知环境限制**：Docker Desktop (Windows) 下 `docker kill` 强制终止容器不触发 restart policy 的自动重启（已用独立容器复现确认，属 Docker Desktop 行为）。`restart: unless-stopped` 的价值体现在 Docker 守护进程/Desktop 重启时自动拉起、以及容器内进程崩溃时自动重启。

## 验证结果

| 项目 | 结果 |
| --- | --- |
| 后端单元测试 | ✅ 43/43（新增 SecretCipherTest 4 + LoginAttemptServiceTest 3 + RateLimitInterceptorTest 3） |
| 登录失败锁定 | ✅ 5 次错误后正确密码也返回 429 ACCOUNT_LOCKED |
| 接口限流 | ✅ 同源连发超限返回 429 RATE_LIMITED |
| API Key 加密 | ✅ `enc:` 前缀、不含明文；管理员接口正常 |
| Token 滑动续期 | ✅ 1 天剩余 → API 调用后延长到 7 天 |
| 故障演练 | ✅ 重启策略、容器恢复、数据库中断自愈、登录接口全部 PASS |

## 相关配置汇总（application.yml / docker-compose 环境变量）

```yaml
devmind:
  security:
    login-max-failures: 5            # DEVMIND_LOGIN_MAX_FAILURES
    login-lock-minutes: 10           # DEVMIND_LOGIN_LOCK_MINUTES
    rate-limit-per-minute: 120       # DEVMIND_RATE_LIMIT_PER_MINUTE
    rate-limit-login-per-minute: 10  # DEVMIND_RATE_LIMIT_LOGIN_PER_MINUTE
    token-ttl-days: 7                # DEVMIND_TOKEN_TTL_DAYS
    token-refresh-threshold-days: 2  # DEVMIND_TOKEN_REFRESH_THRESHOLD_DAYS
    master-key: ""                   # DEVMIND_SECRET_MASTER_KEY（留空则自动生成到 data/secret.key）
```
