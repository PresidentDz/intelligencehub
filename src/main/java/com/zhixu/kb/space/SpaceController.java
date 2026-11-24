package com.zhixu.kb.space;

import com.zhixu.kb.auth.User;
import com.zhixu.kb.common.ApiException;
import com.zhixu.kb.common.Db;
import com.zhixu.kb.common.Input;
import com.zhixu.kb.space.SpaceAccess.Role;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/spaces")
public class SpaceController {
    private static final Set<String> VISIBILITY = Set.of("private", "public");
    private static final Set<String> ROLES = Set.of("admin", "editor", "reader");

    private final JdbcTemplate jdbc;
    private final SpaceAccess access;

    public SpaceController(JdbcTemplate jdbc, SpaceAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping
    Map<String, Object> list(User user) {
        List<Map<String, Object>> spaces = jdbc.queryForList(
                "SELECT s.id,s.name,s.description,s.icon,s.visibility,s.created_at createdAt," +
                        " (SELECT role FROM space_members m WHERE m.space_id=s.id AND m.user_id=?) memberRole," +
                        " (SELECT COUNT(*) FROM pages p WHERE p.space_id=s.id) pageCount" +
                        " FROM spaces s WHERE " + SpaceAccess.VISIBLE + " ORDER BY s.name",
                concat(new Object[]{user.id()}, SpaceAccess.params(user)));
        for (Map<String, Object> s : spaces) s.put("myRole", effectiveRole(user, (String) s.remove("memberRole")));
        return Map.of("spaces", spaces);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Object> create(@RequestBody Map<String, Object> body, User user) {
        String name = Input.required(body, "name", 80, "空间名称不能为空");
        int id = Db.insert(jdbc, "INSERT INTO spaces(name,description,icon,visibility,created_by) VALUES(?,?,?,?,?)",
                name, Input.text(body, "description", 500).trim(), icon(body),
                Input.oneOf(body, "visibility", "private", VISIBILITY), user.id());
        jdbc.update("INSERT INTO space_members(space_id,user_id,role) VALUES(?,?,'admin')", id, user.id());
        return Map.of("space", get(id, user));
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable int id, User user) {
        access.require(id, user, Role.reader);
        return Map.of("space", get(id, user));
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@PathVariable int id, @RequestBody Map<String, Object> body, User user) {
        access.require(id, user, Role.admin);
        jdbc.update("UPDATE spaces SET name=?,description=?,icon=?,visibility=? WHERE id=?",
                Input.required(body, "name", 80, "空间名称不能为空"), Input.text(body, "description", 500).trim(), icon(body),
                Input.oneOf(body, "visibility", "private", VISIBILITY), id);
        return Map.of("space", get(id, user));
    }

    @DeleteMapping("/{id}")
    @Transactional
    Map<String, Object> delete(@PathVariable int id, User user) {
        access.require(id, user, Role.admin);
        // Flatten the tree first so the space→pages cascade stays one level deep.
        jdbc.update("UPDATE pages SET parent_id=NULL WHERE space_id=?", id);
        jdbc.update("DELETE FROM spaces WHERE id=?", id);
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/members")
    Map<String, Object> members(@PathVariable int id, User user) {
        access.require(id, user, Role.reader);
        return Map.of("members", jdbc.queryForList(
                "SELECT u.id,u.username,u.name,u.department,m.role FROM space_members m JOIN users u ON u.id=m.user_id" +
                        " WHERE m.space_id=? ORDER BY CASE m.role WHEN 'admin' THEN 0 WHEN 'editor' THEN 1 ELSE 2 END, u.name", id));
    }

    /** Adds a member or changes their role. */
    @PutMapping("/{id}/members/{userId}")
    @Transactional
    Map<String, Object> setMember(@PathVariable int id, @PathVariable int userId, @RequestBody Map<String, Object> body, User user) {
        access.require(id, user, Role.admin);
        String role = Input.oneOf(body, "role", "reader", ROLES);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND active=1", Integer.class, userId);
        if (exists == null || exists == 0) throw ApiException.notFound("用户不存在或已停用");
        jdbc.update("INSERT INTO space_members(space_id,user_id,role) VALUES(?,?,?) ON DUPLICATE KEY UPDATE role=?",
                id, userId, role, role);
        ensureAdminRemains(id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Transactional
    Map<String, Object> removeMember(@PathVariable int id, @PathVariable int userId, User user) {
        if (userId != user.id()) access.require(id, user, Role.admin);
        jdbc.update("DELETE FROM space_members WHERE space_id=? AND user_id=?", id, userId);
        ensureAdminRemains(id);
        return Map.of("ok", true);
    }

    /** Flat page list; the client builds the tree from parentId and position. */
    @GetMapping("/{id}/pages")
    Map<String, Object> pages(@PathVariable int id, User user) {
        access.require(id, user, Role.reader);
        return Map.of("pages", jdbc.queryForList(
                "SELECT id,parent_id parentId,title,icon,position FROM pages WHERE space_id=? ORDER BY position,id", id));
    }

    private void ensureAdminRemains(int spaceId) {
        Integer admins = jdbc.queryForObject("SELECT COUNT(*) FROM space_members WHERE space_id=? AND role='admin'", Integer.class, spaceId);
        if (admins == null || admins == 0) throw ApiException.badRequest("空间至少需要保留一位管理员");
    }

    private Map<String, Object> get(int id, User user) {
        Map<String, Object> s = jdbc.queryForMap(
                "SELECT s.id,s.name,s.description,s.icon,s.visibility,s.created_at createdAt FROM spaces s WHERE s.id=?", id);
        s.put("myRole", access.roleOf(id, user).name());
        return s;
    }

    private static String effectiveRole(User user, String memberRole) {
        if (user.isAdmin()) return "admin";
        return memberRole != null ? memberRole : "reader";
    }

    private static String icon(Map<String, Object> body) {
        String icon = Input.text(body, "icon", 16).trim();
        return icon.isEmpty() ? "📘" : icon;
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
