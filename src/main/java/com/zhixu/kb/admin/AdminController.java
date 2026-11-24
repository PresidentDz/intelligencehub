package com.zhixu.kb.admin;

import com.zhixu.kb.auth.AuthService;
import com.zhixu.kb.auth.User;
import com.zhixu.kb.common.ApiException;
import com.zhixu.kb.common.Input;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** System user management. AuthInterceptor already restricts /api/admin to system admins. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9_.-]{2,40}");
    private static final Set<String> ROLES = Set.of("admin", "member");

    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public AdminController(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @GetMapping("/users")
    Map<String, Object> users() {
        return Map.of("users", jdbc.query("SELECT * FROM users ORDER BY id", (rs, n) -> User.from(rs)));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String username = Input.text(body, "username", 40).trim();
        String name = Input.text(body, "name", 120).trim();
        String password = Input.text(body, "password", 1000);
        if (!USERNAME.matcher(username).matches()) throw ApiException.badRequest("账号只能包含字母、数字和 _ . -，长度 2-40");
        if (name.isEmpty()) throw ApiException.badRequest("姓名不能为空");
        if (password.length() < 8) throw ApiException.badRequest("密码至少 8 位");
        int id = auth.createUser(username, name, password, Input.oneOf(body, "role", "member", ROLES),
                Input.text(body, "department", 100).trim());
        return Map.of("id", id);
    }

    @PutMapping("/users/{id}")
    @Transactional
    Map<String, Object> update(@PathVariable int id, @RequestBody Map<String, Object> body, User current) {
        String role = Input.oneOf(body, "role", "member", ROLES);
        boolean active = !Boolean.FALSE.equals(body.get("active"));
        String name = Input.required(body, "name", 120, "姓名不能为空");
        if (id == current.id() && (!role.equals("admin") || !active)) throw ApiException.badRequest("不能停用或降级自己的管理员账号");
        int changed = jdbc.update("UPDATE users SET name=?,role=?,department=?,active=? WHERE id=?",
                name, role, Input.text(body, "department", 100).trim(), active ? 1 : 0, id);
        if (changed == 0) throw ApiException.notFound("用户不存在");
        if (!active) jdbc.update("DELETE FROM sessions WHERE user_id=?", id);
        String password = Input.text(body, "password", 1000);
        if (!password.isEmpty()) {
            if (password.length() < 8) throw ApiException.badRequest("密码至少 8 位");
            auth.setPassword(id, password);
        }
        return Map.of("ok", true);
    }
}
