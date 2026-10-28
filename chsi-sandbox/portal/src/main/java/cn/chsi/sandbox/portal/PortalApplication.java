package cn.chsi.sandbox.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 复刻 www.chsi.com.cn 零散查询链路。
 *
 * 线上观测：
 *   GET  /xlcx/lscx/query.do          注入 rdmid 与 imgUrl
 *   GET  /xlcx/lscx/imagechallenge.do 图片验证码
 *   POST /xlcx/lscx/queryinfo.do      rdmid, state, zsbh, xm, yzm, yhxy
 *
 * 复刻要点：rdmid 在页面渲染时生成，会话内不变。
 */
@SpringBootApplication
public class PortalApplication {
    public static void main(String[] args) {
        SpringApplication.run(PortalApplication.class, args);
    }
}
