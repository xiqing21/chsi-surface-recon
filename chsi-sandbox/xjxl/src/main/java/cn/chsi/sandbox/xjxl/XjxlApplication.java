package cn.chsi.sandbox.xjxl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 复刻 xjxl.chsi.com.cn —— 学籍学历管理平台（上报端）。
 *
 * 线上证据：
 *   Location: https://xjxl.chsi.com.cn/htgl/index.mvc
 *   Set-Cookie: XSRF-CCKTOKEN=...; JSESSIONID=...; CHSICC02=!...
 *   Set-Cookie: TS01a8fc6e=...  (Domain=.xjxl.chsi.com.cn)
 *
 * 三个特征：Spring MVC .mvc 处理器、Spring Security CSRF、
 * 自研加密 cookie CHSICC02。
 *
 * 本模块承载测试点 4（CHSICC 可伪造性）与上报端写权限约束验证。
 */
@SpringBootApplication
public class XjxlApplication {
    public static void main(String[] args) {
        SpringApplication.run(XjxlApplication.class, args);
    }
}
