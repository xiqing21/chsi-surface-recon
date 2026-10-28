# 沙箱复刻方案

> 在授权环境里复刻学信网的「上报–审核–查询–验证」链路，用来验证上文的每个假设。
> 目标不是复刻功能，是复刻**校验点**。

## 一、为什么复刻而不是直接测

线上所有假设都无法安全验证：

| 假设 | 线上测法 | 风险 |
|---|---|---|
| CAS `service` 白名单绕过 | 构造畸形 service 看是否跳转 | 属未授权访问尝试 |
| `CHSICC01/02` 可伪造 | 篡改 cookie 看是否认 | 同上 |
| 会话固定 | 预设 jsessionid | 同上 |
| rdmid 竞态 | 并发提交 | 有 DoS 风险 |

在沙箱里把同样的组件装一遍，用同样的配置，就能在**零风险**下拿到答案。

## 二、组件选型（对齐观测到的指纹）

| 层 | 线上观测 | 沙箱对应 |
|---|---|---|
| WAF | 阿里云 WAF | 可选：ModSecurity + OWASP CRS |
| ASM | F5 BIG-IP | 可选：naxsi / 本地 oWASP CRS 规则集 |
| 应用 | Java Servlet + Spring MVC | Spring Boot 2.7 + Spring MVC |
| 认证 | Spring Security CAS | Apereo CAS Server 6.6 + `spring-security-cas` |
| 会话 | Tomcat + `;jsessionid=` | 内嵌 Tomcat，开 URL 重写 |
| 前端 | Vue 2.6.10 + iView 4.6.1 | 同版本（为了复刻 DOM 行为） |
| 数据库 | 关系库 | MySQL 8 |

## 三、目录结构

```
chsi-sandbox/
├── docker-compose.yml
├── cas/                      # 认证中心
│   └── services/             # service 白名单配置 ← 测试点 1
├── portal/                   # www 门户 + 零散查询
│   └── src/main/java/.../
│       ├── LscxController    # query.do / queryinfo.do
│       ├── CaptchaController # imagechallenge.do
│       └── RdmidFilter       # rdmid 生成与校验 ← 测试点 2
├── archive/                  # my 学籍档案
│   └── src/.../CasConfig     # CAS 客户端 ← 测试点 3
├── xjxl/                     # 学籍管理平台（上报端）
│   └── src/.../ChsiccCookie  # CHSICC01/02 加解密 ← 测试点 4
├── db/
│   └── seed.sql              # 模拟中央库
└── tests/
    ├── cas_service_bypass.sh
    ├── rdmid_race.py
    ├── session_fixation.sh
    └── chtscc_forge.py
```

## 四、四个测试点的实现要点

### 测试点 1：CAS service 白名单

`cas/services/HTTPS-443.json` 配成线上同款白名单（允许 `*.chsi.com.cn`）。测试集：

```
https://portal.chsi.com.cn@evil.example/
https://portal.chsi.com.cn.evil.example/
https://portal.chsi.com.cn/../..//evil.example/
https://portal.chsi.com.cn%2F@evil.example/
HTTPS://PORTAL.CHSI.COM.CN/   # 大小写
https://portal.chsi.com.cn/x;jsessionid=ATTACKER_SID
```

每种看两件事：是否放行、票据是否被带到目标域。

### 测试点 2：rdmid 绑定

复刻线上的行为：`rdmid` 在页面渲染时生成，会话内不变。

然后测接受矩阵：

| 输入 | 期望 |
|---|---|
| (rdmid_A, 验证码_A 答案) | 接受 |
| (rdmid_B, 验证码_A 答案) | 应拒绝 |
| (rdmid_A, 验证码_A 答案) 重复提交 | 应拒绝（一次性） |
| 同一 rdmid 并发 50 次提交 | 观察是否有多个成功（竞态） |

**不识别验证码**。在沙箱里让 CaptchaController 直接暴露真值（加调试头），这样才算得出矩阵。

### 测试点 3：会话固定

```
1. 攻击者 GET 登录页，拿到 sid=ATTACKER
2. 构造 https://portal/...;jsessionid=ATTACKER 发给受害者
3. 受害者在同一会话完成 CAS 登录
4. 检查：sid 是否已轮换？攻击者能否用 ATTACKER 访问？
```

Spring Security 默认 `migrateSession()` 会轮换。要看的是 **URL 重写路径下是否同样轮换**——这是漏洞所在。

### 测试点 4：CHSICC cookie

复刻 `!<base64>` 格式，取 20 份样本（不同用户、不同时间），做三件事：

1. **块重合度**：如果不同用户的前 16 字节完全相同 → ECB 模式 → 可做块重排
2. **单字节篡改**：改一位，看服务端是报错还是静默接受 → 判断有无 MAC
3. **长度差分**：改用户名长度看密文长度变化 → 反推明文结构

如果 1 成立且 2 是"静默接受"，那么这个 cookie 在沙箱里可伪造成任意用户 → 对应线上就是 `xjxl` 越权。

## 五、模拟中央库（`db/seed.sql`）

复刻上报制的数据模型：

```sql
CREATE TABLE enrollment (        -- 学籍
  student_no  VARCHAR(20),
  name        VARCHAR(50),
  id_card     VARCHAR(18),
  school      VARCHAR(100),
  major       VARCHAR(100),
  level       VARCHAR(20),       -- 本科/硕士/博士
  enroll_date DATE,
  status      VARCHAR(20)        -- 在校/毕业/退学
);

CREATE TABLE diploma (           -- 学历证书
  cert_no     VARCHAR(20) PRIMARY KEY,   -- zsbh
  student_no  VARCHAR(20),
  name        VARCHAR(50),
  school      VARCHAR(100),
  major       VARCHAR(100),
  level       VARCHAR(20),
  grad_date   DATE,
  audit_status VARCHAR(20)       -- pending/approved/rejected
);

CREATE TABLE verify_report (     -- 在线验证报告
  report_no   VARCHAR(32),
  verify_code CHAR(16),
  student_no  VARCHAR(20),
  expire_at   DATETIME
);
```

三条设计约束要在代码里落实，这是整个复刻的核心：

1. **上报端只能写 `audit_status=pending`**，不能直接改 `approved`
2. **查询端只读 `approved` 且 `grad_date <= now()`**
3. **`diploma` 表不允许 UPDATE 已批准的记录**——用触发器拦

这三条拦上之后，才能验证"前端无写路径"这个结论。

## 六、交付顺序

1. `docker-compose up` 起 CAS + portal + archive + MySQL
2. 跑 `tests/session_fixation.sh`（最简单，先验证链路通了）
3. 跑 `tests/cas_service_bypass.sh`（拿第一个真结论）
4. 跑 `tests/rdmid_race.py`
5. 跑 `tests/chsicc_forge.py`（最花时间，要样本）
6. 汇总成 `docs/FINDINGS.md`

## 七、边界

沙箱里的所有代码、配置、数据都是合成的。不连线上、不复制线上样本、不还原线上密钥。

沙箱得出的结论只能说明"这种配置下会怎样"，不能直接推断"线上就是这样"。要坐实，只能在获得书面授权的前提下对线上做最小验证。
