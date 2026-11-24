package com.zhixu.kb.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;

public final class Db {
    private Db() {}

    /** Runs an INSERT and returns the generated AUTO_INCREMENT id. */
    public static int insert(JdbcTemplate jdbc, String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("No generated key for: " + sql);
        return key.intValue();
    }
}
