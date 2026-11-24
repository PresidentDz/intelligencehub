package com.zhixu.kb.auth;

import com.zhixu.kb.common.Input;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final JdbcTemplate jdbc;

    public AuthController(AuthService auth, JdbcTemplate jdbc) {
        this.auth = auth;
        this.jdbc = jdbc;
    }

    /** Public endpoint: returns {"user": null} when not signed in. */
    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("user", auth.resolve(AuthInterceptor.token(request)));
        return result;
    }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
        String token = auth.login(Input.text(body, "username", 80), Input.text(body, "password", 1000));
        response.addHeader("Set-Cookie", cookie(token, AuthService.SESSION_SECONDS, request));
        return Map.of("user", auth.resolve(token));
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(AuthInterceptor.token(request));
        response.addHeader("Set-Cookie", cookie("", 0, request));
        return Map.of("ok", true);
    }

    /** Lightweight user directory used by the member picker. */
    @GetMapping("/users")
    Map<String, Object> directory(@RequestParam(defaultValue = "") String q) {
        String like = Input.like(q.trim());
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id,username,name,department FROM users WHERE active=1 AND (username LIKE ?" + Input.ESCAPE +
                        " OR name LIKE ?" + Input.ESCAPE + ") ORDER BY name LIMIT 20",
                like, like);
        return Map.of("users", users);
    }

    private static String cookie(String value, int maxAge, HttpServletRequest request) {
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        return AuthService.COOKIE + "=" + value + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + maxAge + (secure ? "; Secure" : "");
    }
}
