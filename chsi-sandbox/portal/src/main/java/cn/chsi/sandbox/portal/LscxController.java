package cn.chsi.sandbox.portal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 零散查询控制器。
 *
 * 测试点 2（rdmid 绑定）就在 verifyCaptcha 的四种策略里切换。
 */
@Controller
@RequestMapping("/xlcx/lscx")
public class LscxController {

    private final JdbcTemplate jdbc;

    public LscxController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** GET query.do —— 注入 rdmid，复刻线上 Vue data() 内联配置。 */
    @GetMapping(value = "/query.do", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String form(HttpSession session) {
        String rdmid = (String) session.getAttribute("rdmid");
        if (rdmid == null) {
            rdmid = Rdmid.generate();
            session.setAttribute("rdmid", rdmid);
        }
        long v = 1483609374520L;
        return """
            <!doctype html><html><head><meta charset="utf-8"><title>学历查询</title></head>
            <body>
            <form name="queryForm" action="queryinfo.do" method="post">
              <input name="rdmid" type="hidden" value="%s" />
              <input name="state" type="hidden" value="CHSI" />
              <input name="zsbh"  placeholder="证书编号" />
              <input name="xm"    placeholder="姓名" />
              <input name="yzm"   placeholder="图片验证码" />
              <input name="yhxy" type="checkbox" />
              <img src="imagechallenge.do?time=1793163152852&rdmid=%s&v=%d" />
              <button type="submit">查询</button>
            </form>
            <script>
              var rdmid = "%s";
              var imgUrl = 'imagechallenge.do?time=1793163152852&rdmid=%s&v=%d';
            </script>
            </body></html>
            """.formatted(rdmid, rdmid, v, rdmid, rdmid, v);
    }

    /** GET imagechallenge.do —— 返回验证码图。沙箱下同时回调试头给出真值。 */
    @GetMapping(value = "/imagechallenge.do", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public byte[] captcha(@RequestParam(required = false) String rdmid,
                          @RequestParam(required = false) String time,
                          HttpSession session,
                          jakarta.servlet.http.HttpServletResponse resp) {
        String id = rdmid != null ? rdmid : (String) session.getAttribute("rdmid");
        String answer = Rdmid.captchaAnswer(id == null ? "" : id, System.currentTimeMillis() / 1000);
        // 严格模式下把答案绑定到 rdmid，供校验时比对
        if ("rdmid_bound".equals(System.getenv().getOrDefault("SANDBOX_CAPTCHA_MODE", ""))) {
            session.setAttribute("cap:" + id, answer);
        }
        // 沙箱调试头：仅本地复刻件才有，用于构造接受矩阵
        resp.setHeader("X-Sandbox-Captcha-Answer", answer);
        resp.setHeader("X-Sandbox-Rdmid", id == null ? "" : id);
        return CaptchaPng.render(answer);
    }

    /** POST queryinfo.do —— 零散查询。 */
    @PostMapping(value = "/queryinfo.do", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String query(@RequestParam String rdmid,
                        @RequestParam(required = false, defaultValue = "CHSI") String state,
                        @RequestParam(required = false, defaultValue = "") String zsbh,
                        @RequestParam(required = false, defaultValue = "") String xm,
                        @RequestParam(required = false, defaultValue = "") String yzm,
                        HttpServletRequest req) {

        String result;
        String html;

        if (zsbh.isBlank() || xm.isBlank() || yzm.isBlank()) {
            result = "PARAM_MISSING";
            html = "<p>参数不完整</p>";
        } else if (!verifyCaptcha(rdmid, yzm, req.getSession())) {
            result = "CAPTCHA_FAIL";
            html = "<p>验证码错误</p>";
        } else {
            try {
                Map<String, Object> row = jdbc.queryForMap(
                    "SELECT cert_no,name,school,major,level,grad_date FROM v_query_out " +
                    "WHERE cert_no = ? AND name = ?", zsbh, xm);
                result = "HIT";
                html = "<table>" + row.entrySet().stream()
                    .map(e -> "<tr><th>" + e.getKey() + "</th><td>" + e.getValue() + "</td></tr>")
                    .reduce("", String::concat) + "</table>";
            } catch (EmptyResultDataAccessException ex) {
                result = "MISS";
                html = "<p>未查询到学历信息</p>";
            }
        }

        jdbc.update("INSERT INTO query_audit(rdmid,cert_no,name,ip,result) VALUES(?,?,?,?,?)",
            rdmid, zsbh, xm, req.getRemoteAddr(), result);
        return html;
    }

    /**
     * 验证码校验 —— 测试点 2 的四种策略。
     * 通过 SANDBOX_CAPTCHA_MODE 环境变量切换：
     *   session_latest  按会话存最近一次答案（线上疑似形态）
     *   rdmid_bound     按 rdmid 绑定，一次性（最严）
     *   rdmid_reuse     按 rdmid 绑定，可重复用（宽松）
     *   window_only     只按时间窗校验，不看 rdmid
     */
    private boolean verifyCaptcha(String rdmid, String yzm, HttpSession session) {
        String mode = System.getenv().getOrDefault("SANDBOX_CAPTCHA_MODE", "session_latest");
        long now = System.currentTimeMillis() / 1000;

        return switch (mode) {
            case "rdmid_bound" -> {
                String key = "cap:" + rdmid;
                String expect = (String) session.getAttribute(key);
                boolean ok = yzm.equals(expect);
                if (ok) session.removeAttribute(key);   // 一次性
                yield ok;
            }
            case "rdmid_reuse" -> {
                String key = "cap:" + rdmid;
                String expect = (String) session.getAttribute(key);
                boolean ok = yzm.equals(expect);
                if (ok && expect == null) session.setAttribute(key, yzm);
                yield ok || yzm.equals(Rdmid.captchaAnswer(rdmid, now));
            }
            case "window_only" -> yzm.equals(Rdmid.captchaAnswer(rdmid, now))
                                || yzm.equals(Rdmid.captchaAnswer(rdmid, now - 1));
            default -> {   // session_latest
                String expect = Rdmid.captchaAnswer(rdmid, now);
                yield yzm.equals(expect) || yzm.equals(Rdmid.captchaAnswer(rdmid, now - 1));
            }
        };
    }
}
