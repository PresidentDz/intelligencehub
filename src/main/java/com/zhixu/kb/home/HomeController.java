package com.zhixu.kb.home;

import com.zhixu.kb.auth.User;
import com.zhixu.kb.common.Input;
import com.zhixu.kb.space.SpaceAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Recent pages, favorites and global search. All queries only return pages in spaces the user can see. */
@RestController
@RequestMapping("/api")
public class HomeController {
    private static final String PAGE_FIELDS =
            "p.id,p.title,p.icon,p.updated_at updatedAt,s.id spaceId,s.name spaceName,s.icon spaceIcon,u.name updatedBy";
    private static final String PAGE_JOIN = " FROM pages p JOIN spaces s ON s.id=p.space_id JOIN users u ON u.id=p.updated_by";

    private final JdbcTemplate jdbc;

    public HomeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/home/recent")
    Map<String, Object> recent(User user) {
        return Map.of("pages", jdbc.queryForList(
                "SELECT " + PAGE_FIELDS + ",v.visited_at visitedAt" + PAGE_JOIN + " JOIN page_visits v ON v.page_id=p.id AND v.user_id=?" +
                        " WHERE " + SpaceAccess.VISIBLE + " ORDER BY v.visited_at DESC LIMIT 20",
                user.id(), SpaceAccess.params(user)[0], user.id()));
    }

    @GetMapping("/home/favorites")
    Map<String, Object> favorites(User user) {
        return Map.of("pages", jdbc.queryForList(
                "SELECT " + PAGE_FIELDS + ",f.created_at favoritedAt" + PAGE_JOIN + " JOIN favorites f ON f.page_id=p.id AND f.user_id=?" +
                        " WHERE " + SpaceAccess.VISIBLE + " ORDER BY f.created_at DESC LIMIT 100",
                user.id(), SpaceAccess.params(user)[0], user.id()));
    }

    @GetMapping("/search")
    Map<String, Object> search(@RequestParam(defaultValue = "") String q, User user) {
        String query = q.trim();
        if (query.isEmpty()) return Map.of("results", List.of());
        if (query.length() > 100) query = query.substring(0, 100);
        String like = Input.like(query);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + PAGE_FIELDS + ",p.plain_text plainText" + PAGE_JOIN +
                        " WHERE (p.title LIKE ?" + Input.ESCAPE + " OR p.plain_text LIKE ?" + Input.ESCAPE + ") AND " + SpaceAccess.VISIBLE +
                        " ORDER BY (p.title LIKE ?" + Input.ESCAPE + ") DESC, p.updated_at DESC LIMIT 50",
                like, like, SpaceAccess.params(user)[0], user.id(), like);
        for (Map<String, Object> row : rows) row.put("excerpt", excerpt((String) row.remove("plainText"), query));
        return Map.of("results", rows);
    }

    /** About 120 characters around the first match, or the start of the page. */
    static String excerpt(String text, String query) {
        if (text == null || text.isEmpty()) return "";
        int at = text.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        int start = at < 0 ? 0 : Math.max(0, at - 40);
        int end = Math.min(text.length(), start + 120);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }
}
