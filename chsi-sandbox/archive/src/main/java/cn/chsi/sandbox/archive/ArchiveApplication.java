package cn.chsi.sandbox.archive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 复刻 my.chsi.com.cn/archive —— CAS 保护的学信档案。
 *
 * 线上证据：GET /archive/index.action 302 到
 *   account.chsi.com.cn/passport/login?service=
 *     .../archive/j_spring_cas_security_check;jsessionid=...
 *
 * 本模块用于测试点 3（会话固定）：验证 Spring Security 在
 * URL 重写会话跟踪下是否仍然轮换 session id。
 */
@SpringBootApplication
public class ArchiveApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArchiveApplication.class, args);
    }
}
