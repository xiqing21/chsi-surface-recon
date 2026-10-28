package cn.chsi.sandbox.xjxl;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 上报端控制器。复刻 .mvc 后缀风格。
 *
 * 两条硬约束在这里落地（对应 docs/SANDBOX-PLAN.md 第五节）：
 *   1. 上报端只能写 audit_status=pending
 *   2. 已批准记录 UPDATE 会被数据库触发器拒绝
 */
@Controller
@RequestMapping("/htgl")
public class HtglController {

    private final JdbcTemplate jdbc;
    private final ChsiccCookie cookie;

    public HtglController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.cookie = new ChsiccCookie(ChsiccCookie.defaultMode(), ChsiccCookie.defaultKey());
    }

    /** GET /htgl/index.mvc —— 对齐线上 302 目标。 */
    @GetMapping(value = "/index.mvc", produces = "text/html;charset=utf-8")
    @ResponseBody
    public String index(HttpServletRequest req, HttpServletResponse resp) {
        String identity = readIdentity(req);
        if (identity == null) {
            // 无有效 CHSICC02 → 下发一个新的（复刻线上行为）
            String v = cookie.encrypt("guest", "ROLE_GUEST", System.currentTimeMillis() / 1000 + 1800);
            Cookie c = new Cookie("CHSICC02", v);
            c.setPath("/");
            c.setHttpOnly(true);
            resp.addCookie(c);
            return "<h1>学籍学历管理平台</h1><p>未登录</p>";
        }
        return "<h1>学籍学历管理平台</h1><p>身份: " + identity + "</p>";
    }

    /** GET /htgl/whoami.mvc —— 测试点 4 的观测点。 */
    @GetMapping(value = "/whoami.mvc", produces = "application/json;charset=utf-8")
    @ResponseBody
    public Map<String, Object> whoami(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        String raw = rawCookie(req, "CHSICC02");
        out.put("cookie_present", raw != null);
        out.put("cookie_shape_ok", ChsiccCookie.looksLike(raw));
        out.put("cipher_mode", ChsiccCookie.defaultMode());
        out.put("identity", readIdentity(req));
        return out;
    }

    /** POST /htgl/report.mvc —— 上报。只能写 pending。 */
    @PostMapping(value = "/report.mvc", produces = "application/json;charset=utf-8")
    @ResponseBody
    public Map<String, Object> report(@RequestParam String studentNo,
                                      @RequestParam String name,
                                      @RequestParam String school,
                                      @RequestParam String major,
                                      @RequestParam String level,
                                      @RequestParam String gradDate) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String certNo = "SBX" + System.currentTimeMillis();
            jdbc.update("INSERT INTO diploma" +
                    "(cert_no,student_no,name,school,major,level,grad_date,audit_status) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                certNo, studentNo, name, school, major, level, gradDate, "pending");
            out.put("ok", true);
            out.put("cert_no", certNo);
            out.put("audit_status", "pending");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** POST /htgl/approve.mvc —— 审批。已批准记录再改会被触发器拒绝。 */
    @PostMapping(value = "/approve.mvc", produces = "application/json;charset=utf-8")
    @ResponseBody
    public Map<String, Object> approve(@RequestParam String certNo) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            int n = jdbc.update(
                "UPDATE diploma SET audit_status='approved' WHERE cert_no=? AND audit_status='pending'",
                certNo);
            out.put("ok", n > 0);
            out.put("changed", n);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    private String readIdentity(HttpServletRequest req) {
        String raw = rawCookie(req, "CHSICC02");
        if (raw == null) return null;
        try {
            return cookie.decrypt(raw);
        } catch (Exception e) {
            return "DECRYPT_FAIL:" + e.getClass().getSimpleName();
        }
    }

    private static String rawCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
