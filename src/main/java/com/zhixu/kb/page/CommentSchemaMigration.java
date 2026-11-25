package com.zhixu.kb.page;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CommentSchemaMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public CommentSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer columnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='comments' AND COLUMN_NAME='parent_id'",
                Integer.class);
        if (columnCount != null && columnCount == 0) {
            jdbc.execute("ALTER TABLE comments ADD COLUMN parent_id INT NULL, " +
                    "ADD KEY comments_parent (parent_id), " +
                    "ADD CONSTRAINT comments_parent FOREIGN KEY (parent_id) REFERENCES comments(id) ON DELETE CASCADE");
        }
    }
}
