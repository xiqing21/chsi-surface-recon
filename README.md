# CHSI 对外可观测架构与漏洞面分析

学信网（chsi.com.cn）的公开面架构拆解、业务流程还原、漏洞面可行性判断、学历查询数据源溯源。

全部结论来自公开可达面的 HEAD/GET 取样，取样时间 2026-10-28T04:52Z。未经授权的写操作、验证码识别、批量枚举均未进行。

## 文档

| 文件 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层架构、CAS 认证链、子系统划分、业务流程六步 |
| [docs/VULN-SURFACE.md](docs/VULN-SURFACE.md) | 按面逐条评估：CAS service、rdmid、会话固定、自研 cookie、ASM 差异、前端依赖 |
| [docs/SUPPLIERS.md](docs/SUPPLIERS.md) | 「姓名+身份证查学历」的四条通道拆解与识别方法 |
| [docs/SANDBOX-PLAN.md](docs/SANDBOX-PLAN.md) | 授权环境复刻方案与四个测试点 |
| [data/architecture.json](data/architecture.json) | 结构化观测数据 |
| [data/recon-log.md](data/recon-log.md) | 原始侦察记录 |

## 五条主要结论

1. **主数据只读**。学历数据由高校/省厅上报后审核入库，前端任何操作触不到主数据。要改学历需要的是上报端权限，不是网站漏洞。

2. **零散查询要证书编号，不是身份证号**。实测表单字段为 `zsbh`（证书编号）+ `xm`（姓名）+ `yzm`（验证码）+ `rdmid`。流传的「姓名+身份证查学历」不成立。

3. **认证是标准 CAS**。`j_spring_cas_security_check` + `account.chsi.com.cn/passport/login?service=` 表明是 Spring Security CAS 客户端。整条信任链的风险集中在 `service` 校验、ticket 一次性、子系统信任边界。

4. **各子系统 ASM 策略独立**。`TS01*` cookie 按子域下发，证明是独立实例。弱环更可能在 `xjxl`（学籍管理平台）这类业务系统，而非门户。

5. **PHP 系漏洞不匹配**。可达面全是 Java（`.action`/`.do`/`.mvc` + `JSESSIONID`）。CVE-2024-4577 一类需要 php-cgi 入口，此处无。

## 观测到的技术栈

```
边缘   阿里云 WAF (aliyungf_tc, acw_tc)
       F5 BIG-IP ASM (TS01a8fc6e / TS017de4d1, 按子系统独立)
应用   Java Servlet / Spring MVC (.action .do .mvc, JSESSIONID)
认证   Spring Security CAS client + Apereo CAS (account.chsi.com.cn/passport)
会话   CHSICC01 / CHSICC02 (自研加密 cookie, !base64 格式)
       CHSICC_CLIENTFLAG<子系统> (来源标识)
       XSRF-CCKTOKEN (Spring Security CSRF)
前端   Vue 2.6.10 + iView 4.6.1 + jQuery 1.9.1
静态   t1.chei.com.cn
内域   chei.com.cn / chdi.com.cn / ncss.cn / qtedu.om.moe.edu.cn
```

## 认证链证据

```
GET https://my.chsi.com.cn/archive/index.action
302 Location: https://account.chsi.com.cn/passport/login?service=
    https%3A%2F%2Fmy.chsi.com.cn%2Farchive%2F
    j_spring_cas_security_check%3Bjsessionid%3D0A46CDF...
```

`service` 参数里直接嵌了 `;jsessionid=` —— 这是整条链上最值得验证的一处。

## 零散查询链路

```
GET  /xlcx/lscx/query.do              # 注入 rdmid + imgUrl
GET  /xlcx/lscx/imagechallenge.do     # 图片验证码
POST /xlcx/lscx/queryinfo.do          # rdmid, state, zsbh, xm, yzm, yhxy
```

## 用途

- 了解公开面架构与认证设计
- 在**授权环境**按 `docs/SANDBOX-PLAN.md` 复刻并验证假设
- 理解学历查询的真实数据源与市面上各通道的区别

## 边界

本文档不含可直接对线上执行的利用代码。所有测试方法均描述为"在授权环境的复刻件上如何验证"，不提供现成 payload、不提供验证码识别方案、不提供任何面向线上的自动化脚本。

沙箱方案中的代码与数据全部为合成件，不连线上、不复制线上样本。

## License

MIT
