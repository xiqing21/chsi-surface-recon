package cn.chsi.sandbox.archive;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 学信档案页。测试点 3 的观测点：登录前后 session id 是否轮换。 */
@RestController
@RequestMapping("/archive")
public class ArchiveController {

    @GetMapping(value = "/whoami", produces = "application/json;charset=utf-8")
    public Map<String, Object> whoami(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", s == null ? null : s.getId());
        out.put("authenticated", req.getUserPrincipal() != null);
        out.put("principal", req.getUserPrincipal() == null ? null : req.getUserPrincipal().getName());
        return out;
    }
}
