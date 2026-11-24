package com.zhixu.kb.auth;

import java.sql.ResultSet;
import java.sql.SQLException;

/** The signed-in user. {@code role} is the system role: admin or member. */
public record User(int id, String username, String name, String role, String department, boolean active) {
    public boolean isAdmin() {
        return "admin".equals(role);
    }

    public static User from(ResultSet rs) throws SQLException {
        return new User(rs.getInt("id"), rs.getString("username"), rs.getString("name"), rs.getString("role"),
                rs.getString("department"), rs.getInt("active") != 0);
    }
}
