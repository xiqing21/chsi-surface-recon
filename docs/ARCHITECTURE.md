# 学信网（CHSI）对外可观测架构

> 全部结论来自公开面 HEAD/GET 取样，取样时间 2026-10-28T04:52Z。
> 未经授权的写操作、验证码识别、枚举均未进行，也不在本文范围。

## 一、分层总图

```
                    ┌─────────────────────────────────────────┐
                    │  阿里云 WAF (aliyungf_tc / acw_tc)       │
                    │  F5 BIG-IP ASM (TS01*=, 按子系统下发)     │
                    └───────────────┬─────────────────────────┘
                                    │
        ┌───────────────┬───────────┼───────────┬──────────────┐
        │               │           │           │              │
   www.chsi       my.chsi     account.chsi  xjxl.chsi     job/rz/dy
   (门户+零散查询)  (学信档案)   (CAS 认证中心) (学籍管理htgl)  (就业/报告/研招)
        │               │           │           │              │
        └───────────────┴───────────┴───────────┴──────────────┘
                                    │
                    ┌───────────────┴─────────────────┐
                    │  Java Servlet / Spring MVC       │
                    │  *.action  *.do  *.mvc           │
                    │  Spring Security CAS client      │
                    └───────────────┬─────────────────┘
                                    │
                    ┌───────────────┴─────────────────┐
                    │  中央库（教育部数据中心）          │
                    │  高校/省厅上报 → 审核 → 只读      │
                    └─────────────────────────────────┘
```

## 二、边缘层

| 组件 | 指纹 | 含义 |
|---|---|---|
| 阿里云 WAF | `Set-Cookie: aliyungf_tc=`, `acw_tc=` | 请求先过阿里云清洗 |
| F5 BIG-IP ASM | `TS01a8fc6e=`, `TS017de4d1=`，Domain 绑定到 `.xjxl.chsi.com.cn` / `.job.chsi.com.cn` | 每个子系统独立 ASM 实例，策略互不相同 |
| 静态 CDN | `t1.chei.com.cn` | jQuery/Vue/iView 等公共库走独立静态域 |

`TS01*` cookie 按子域下发是重要信号：**各子系统是独立的 ASM 策略域**，意味着 www 上拦住的东西，xjxl 上不一定拦，反之亦然。

## 三、应用层与认证

核心证据来自这条 302：

```
GET https://my.chsi.com.cn/archive/index.action
302 Location: https://account.chsi.com.cn/passport/login?service=
    https%3A%2F%2Fmy.chsi.com.cn%2Farchive%2F
    j_spring_cas_security_check%3Bjsessionid%3D0A46CDF...
```

拆解：

1. `j_spring_cas_security_check` — Spring Security 的 CAS 过滤器入口。这是 CAS **客户端**侧的动作。
2. `account.chsi.com.cn/passport/login?service=` — 标准 **CAS Server** 的登录端点，`service` 为回跳地址。
3. `;jsessionid=` 被塞进 `service` 参数 — 容器开启了 URL 重写会话跟踪。这个点值得记：`service` 参数是攻击面中最经典的开放重定向/票据窃取位。

会话 cookie 体系：

| Cookie | 作用域 | 用途 |
|---|---|---|
| `JSESSIONID` | 各子系统 | 容器会话 |
| `CHSICC_CLIENTFLAG<子系统>` | 各子系统 | 标识请求来自哪个子系统前端 |
| `CHSICC01` / `CHSICC02` | `.job` / `.xjxl` | 自建加密会话态（值为 `!` 开头的 base64 串） |
| `XSRF-CCKTOKEN` | `xjxl` | Spring Security CSRF token |

`CHSICC01/02` 是自研的加密 cookie，格式为 `!<base64>`，属于"服务端加密后下发、回传时解密"的模式。这类设计如果密钥管理不严，是伪造身份的经典入口；但从外部无法验证，只能标注为**需授权后重点审计**。

## 四、前端栈

| 库 | 版本 | 年份 |
|---|---|---|
| jQuery | 1.9.1 | 2013 |
| Vue | 2.6.10 | 2020（已 EOL） |
| iView | 4.6.1 | 2020 |
| lodash | min 版，未标版本 | — |

页面模式统一：服务端渲染 HTML 骨架 + 把配置对象注入 `Vue.mixin({data(){...}})`。零散查询页的 `rdmid`、`imgUrl` 都是这样硬编码进 script 块的。

Vue 2.6.10 / iView 4.6.1 本身在这个版本区间没有直接可打的 RCE，但要留意 `Vue` 的模板编译在某些 XSS 沉积点会被放大成 DOM XSS。**库老本身不是漏洞，库老 + 输出未转义才是。**

## 五、业务流程（对外可见部分）

```
[高校/省厅] --上报--> [审核] --入库--> [中央库]
                                          │
                          ┌───────────────┴──────────────┐
                          │                              │
                    [零散查询]                     [在线验证报告]
                 证书编号+姓名+验证码            16位验证码+报告编号
                          │                              │
                     [queryinfo.do]                   [rz 子系统]
```

六步链路与每步的校验点：

| 步 | 动作 | 入口 | 校验点 |
|---|---|---|---|
| 1 | 实名注册 | `account/passport` | 姓名+身份证+手机号三要素，调人口库 |
| 2 | 登录 | CAS SSO | service ticket 回跳 |
| 3 | 绑定学籍 | `my/archive` | 与中央库记录匹配 |
| 4 | 零散查询 | `www/xlcx/lscx/queryinfo.do` | 证书编号+姓名+图片验证码+rdmid |
| 5 | 报告验证 | `rz` 子系统 | 16位验证码+报告编号 |
| 6 | 数据变更 | 高校管理平台（xjxl/htgl） | 上报+审核，**非实时** |

## 六、关键结论

1. **学信网的主数据只读**。学历数据由高校/省厅上报后审核入库，前端任何操作都触不到主数据。要改学历，需要的是上报端权限，不是网站漏洞。
2. **零散查询要的是证书编号，不是身份证号**。流传的"姓名+身份证查学历"不成立；三要素中的第三要素是证书编号或院校名，不是证件号。
3. **认证是标准 CAS**。整条信任链的风险集中在 `service` 参数校验、ticket 一次性、子系统间信任边界这三处。
4. **各子系统 ASM 策略独立**。边缘防护强度不统一，弱环更可能出现在 `xjxl`（学籍管理平台）这类内部业务系统上，而非门户。
5. **前端依赖陈旧但不构成直接攻击面**。真实风险在输出编码与 DOM XSS 沉积点。
