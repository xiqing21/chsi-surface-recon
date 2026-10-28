# chsi-sandbox

学信网链路复刻沙箱。用于在授权环境验证 [../docs/VULN-SURFACE.md](../docs/VULN-SURFACE.md) 里的每一条假设。

所有组件、数据、密钥均为合成件，不连接线上服务，不复制线上样本。

## 实测结果

见 [FINDINGS.md](FINDINGS.md)。三条已成立的结论：

1. **已批准记录不可改**。数据库触发器拦截，`ERROR 1644: approved diploma is immutable`。
2. **`session_latest` 模式下验证码可重放**；改成 `rdmid_bound` 一次性后重放被拒。两种模式的差异就是修复前后的对照。
3. **真实二要素是「证书编号 + 姓名」**，姓名不匹配即 MISS，与外部观测一致。

`CHSICC01/02` 的四模式加密对比也已完成：`ecb_nomac` 可块重排、无 MAC 时篡改被静默接受；`cbc_mac` 与 `gcm` 拒绝篡改。

## 结构

```
chsi-sandbox/
├── cas/services/      CAS 白名单（复刻 *.chsi.com.cn 形态）
├── portal/            www 零散查询 + 验证码 + rdmid
├── archive/           my 学信档案（CAS 客户端，测试点 3）
├── xjxl/              学籍管理平台（CHSICC 加密，测试点 4）
├── db/seed.sql        中央库 + 三条约束
├── tests/             测试脚本
└── FINDINGS.md        实测结果
```

## 四个测试点

| 点 | 目标 | 位置 | 状态 |
|---|---|---|---|
| 1 | CAS `service` 白名单绕过 | `tests/cas_service_bypass.sh` | 脚本就绪，待 CAS 启动 |
| 2 | `rdmid` 与验证码绑定 | `SANDBOX_CAPTCHA_MODE` 四种模式 | **已实测** |
| 3 | 会话固定 | `archive/.../CasSecurityConfig.java` | 配置就绪，待 CAS 启动 |
| 4 | CHSICC 可伪造性 | `xjxl/.../ChsiccProbe.java` | **已实测** |

## 运行

```bash
# 全栈
docker compose up -d

# 只跑数据约束验证
docker exec chsi-db mysql -uroot -psandbox chsi \
  -e "UPDATE diploma SET major='HACKED' WHERE cert_no='1048712021000001';"

# 验证码绑定两模式对照
SANDBOX_CAPTCHA_MODE=session_latest docker compose up -d portal   # 重放命中
SANDBOX_CAPTCHA_MODE=rdmid_bound    docker compose up -d portal   # 重放被拒

# CHSICC 加密形态
docker run --rm -v "$PWD/xjxl":/src -w /src eclipse-temurin:21-jre \
  java -cp /src/target/classes cn.chsi.sandbox.xjxl.ChsiccProbe
```

## 三条数据约束

`db/seed.sql` 里落实，是整个复刻的核心：

1. 上报端只能写 `audit_status=pending`（视图 `v_report_in`）
2. 查询端只读 `approved` 且 `grad_date <= CURDATE()`（视图 `v_query_out`）
3. 已批准的 `diploma` 记录禁止 UPDATE（触发器 `trg_diploma_guard`）

这三条拦上之后，"前端无写路径"这个结论才可验证。

## 边界

沙箱得出的结论只说明"这种实现下会怎样"，不能直接推断线上。要坐实，需在书面授权下对线上做最小验证。
