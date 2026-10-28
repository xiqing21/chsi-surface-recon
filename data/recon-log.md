# 只读侦察记录

时间 2026-10-28T04:52Z。全部为 GET/HEAD，无写操作，无载荷。

## 1. 边缘层

| 观测 | 证据 | 判定 |
|---|---|---|
| WAF | Set-Cookie: aliyungf_tc, acw_tc | 阿里云 WAF |
| 负载/ASM | Set-Cookie: TS01a8fc6e=..., TS017de4d1=... (Domain=.xjxl / .job) | F5 BIG-IP ASM，按子系统下发 |
| 静态 CDN | t1.chei.com.cn 承载 jquery/vue/iview | 静态资源独立域 |

## 2. 应用层

`my.chsi.com.cn/archive/index.action` 302 到：

```
https://account.chsi.com.cn/passport/login?service=
  https%3A%2F%2Fmy.chsi.com.cn%2Farchive%2Fj_spring_cas_security_check%3Bjsessionid%3D...
```

- `j_spring_cas_security_check` = Spring Security CAS 客户端过滤器，标准 CAS 1.0/2.0 协议。
- `;jsessionid=` 出现在 service 参数里 → URL 重写式会话跟踪被启用，容器层允许 ;jsessionid 解析。
- cookie 前缀 `CHSICC_CLIENTFLAG<子系统名>` 用于跨子系统标识来源。

## 3. 前端栈

www 与 my 的页面均注入 Vue 2.6.10 + iView 4.6.1 + jQuery 1.9.1 + lodash + moment。
模式：服务端把配置（含 rdmid、imgUrl）直接注入 Vue mixin 的 data()。

## 4. 零散查询业务链

```
GET  /xlcx/lscx/query.do
     -> 页面注入 rdmid=<24位随机> 与 imgUrl=imagechallenge.do?time=..&rdmid=..
GET  /xlcx/lscx/imagechallenge.do?time=<ts>&rdmid=<id>   # 图片验证码
POST /xlcx/lscx/queryinfo.do
     rdmid, state=CHSI, zsbh, xm, yzm, yhxy
```

关键：查询条件是 **证书编号 + 姓名**，不是姓名+身份证。图片验证码为必填。

## 5. 报告验证

`/xlcx/bgys.jsp`、`/xlcx/bg.do`、`/xlcx/bgcx.jsp` 均返回 403（3144 字节阿里云拦截页），
`rz.chsi.com.cn`、`dy.chsi.com.cn` CONNECT 阶段即被隧道 503。
→ 报告验证域不在开放可达面，走的是独立入口。

## 6. 未做的事

- 未对任何接口发送非表单声明的参数
- 未做验证码识别
- 未做并发/枚举
- 未尝试 CAS 票据构造
