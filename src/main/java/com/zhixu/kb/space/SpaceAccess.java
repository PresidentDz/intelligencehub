package com.zhixu.kb.space;

import com.zhixu.kb.auth.User;
import com.zhixu.kb.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves a user's role in a space. System admins act as space admins everywhere;
 * signed-in non-members get reader access to public spaces.
 */
@Component
public class SpaceAccess {
    public enum Role { reader, editor, admin }

    /** SQL predicate over alias {@code s}; bind {@link #params(User)} after it. */
    public static final String VISIBLE = "(?=1 OR s.visibility='public' OR EXISTS(SELECT 1 FROM space_members m WHERE m.space_id=s.id AND m.user_id=?))";

    private final JdbcTemplate jdbc;

    public SpaceAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static Object[] params(User user) {
        return new Object[]{user.isAdmin() ? 1 : 0, user.id()};
    }

    /** Returns null when the user cannot see the space. Throws 404 if the space does not exist. */
    public Role roleOf(int spaceId, User user) {
        List<String> visibility = jdbc.queryForList("SELECT visibility FROM spaces WHERE id=?", String.class, spaceId);
        if (visibility.isEmpty()) throw ApiException.notFound("知识空间不存在");
        if (user.isAdmin()) return Role.admin;
        List<String> member = jdbc.queryForList("SELECT role FROM space_members WHERE space_id=? AND user_id=?", String.class, spaceId, user.id());
        if (!member.isEmpty()) return Role.valueOf(member.get(0));
        return "public".equals(visibility.get(0)) ? Role.reader : null;
    }

    public Role require(int spaceId, User user, Role minimum) {
        Role role = roleOf(spaceId, user);
        if (role == null) throw ApiException.forbidden("你不是该知识空间的成员");
        if (role.ordinal() < minimum.ordinal())
            throw ApiException.forbidden(minimum == Role.admin ? "需要空间管理员权限" : "你在该空间只有阅读权限");
        return role;
    }
}
