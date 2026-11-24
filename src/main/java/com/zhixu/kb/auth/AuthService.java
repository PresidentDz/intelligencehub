package com.zhixu.kb.auth;

import com.zhixu.kb.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {
    public static final String COOKIE = "kb_session";
    public static final int SESSION_SECONDS = 1_209_600;

    private final JdbcTemplate jdbc;

    public AuthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public User resolve(String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) return null;
        List<User> rows = jdbc.query("SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token=? AND s.expires_at>? AND u.active=1",
                (rs, n) -> User.from(rs), token, Instant.now().getEpochSecond());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Returns the new session token. */
    public String login(String username, String password) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,password_salt,password_hash FROM users WHERE username=? AND active=1", username);
        if (rows.isEmpty() || !PasswordHasher.verify(password, (String) rows.get(0).get("password_salt"), (String) rows.get(0).get("password_hash")))
            throw ApiException.unauthorized("账号或密码不正确");
        long now = Instant.now().getEpochSecond();
        jdbc.update("DELETE FROM sessions WHERE expires_at<=?", now);
        String token = PasswordHasher.randomHex(32);
        jdbc.update("INSERT INTO sessions(token,user_id,expires_at) VALUES(?,?,?)", token, rows.get(0).get("id"), now + SESSION_SECONDS);
        return token;
    }

    public void logout(String token) {
        if (token != null) jdbc.update("DELETE FROM sessions WHERE token=?", token);
    }

    public int createUser(String username, String name, String password, String role, String department) {
        String salt = PasswordHasher.randomHex(16);
        jdbc.update("INSERT INTO users(username,name,password_salt,password_hash,role,department) VALUES(?,?,?,?,?,?)",
                username, name, salt, PasswordHasher.hash(password, salt), role, department);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
    }

    public void setPassword(int userId, String password) {
        String salt = PasswordHasher.randomHex(16);
        jdbc.update("UPDATE users SET password_salt=?,password_hash=? WHERE id=?", salt, PasswordHasher.hash(password, salt), userId);
        jdbc.update("DELETE FROM sessions WHERE user_id=?", userId);
    }
}
