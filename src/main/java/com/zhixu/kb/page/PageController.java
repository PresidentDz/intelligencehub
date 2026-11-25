package com.zhixu.kb.page;

import com.zhixu.kb.auth.User;
import com.zhixu.kb.common.ApiException;
import com.zhixu.kb.common.Input;
import com.zhixu.kb.page.PageService.ImportNode;
import com.zhixu.kb.page.PageService.PageRef;
import com.zhixu.kb.space.SpaceAccess;
import com.zhixu.kb.space.SpaceAccess.Role;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class PageController {
    private static final int MAX_CONTENT = 2_000_000;
    private static final int MAX_IMPORT_PAGES = 2000;
    private static final int MAX_IMPORT_DEPTH = 30;

    private final JdbcTemplate jdbc;
    private final PageService pages;
    private final SpaceAccess access;

    public PageController(JdbcTemplate jdbc, PageService pages, SpaceAccess access) {
        this.jdbc = jdbc;
        this.pages = pages;
        this.access = access;
    }

    @PostMapping("/spaces/{spaceId}/pages")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@PathVariable int spaceId, @RequestBody Map<String, Object> body, User user) {
        int id = pages.create(spaceId, Input.integerOrNull(body, "parentId"), Input.text(body, "title", 240).trim(),
                Input.text(body, "content", MAX_CONTENT), user);
        return Map.of("id", id);
    }

    /** Body: {parentId, pages: [{title, content, children: [...]}]}, content already converted to HTML. */
    @PostMapping("/spaces/{spaceId}/import")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> importPages(@PathVariable int spaceId, @RequestBody Map<String, Object> body, User user) {
        int[] budget = {MAX_IMPORT_PAGES};
        List<ImportNode> nodes = importNodes(body.get("pages"), budget, 0);
        if (nodes.isEmpty()) throw ApiException.badRequest("没有可导入的页面");
        List<Integer> ids = pages.importTree(spaceId, Input.integerOrNull(body, "parentId"), nodes, user);
        return Map.of("ids", ids, "count", MAX_IMPORT_PAGES - budget[0]);
    }

    @GetMapping("/spaces/{spaceId}/export")
    Map<String, Object> exportPages(@PathVariable int spaceId, @RequestParam(required = false) Integer pageId, User user) {
        return Map.of("pages", pages.exportTree(spaceId, pageId, user));
    }

    @SuppressWarnings("unchecked")
    private static List<ImportNode> importNodes(Object value, int[] budget, int depth) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw ApiException.badRequest("参数 pages 无效");
        if (depth > MAX_IMPORT_DEPTH) throw ApiException.badRequest("目录层级不能超过 " + MAX_IMPORT_DEPTH + " 层");
        List<ImportNode> nodes = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) throw ApiException.badRequest("参数 pages 无效");
            if (--budget[0] < 0) throw ApiException.badRequest("一次最多导入 " + MAX_IMPORT_PAGES + " 个页面");
            Map<String, Object> map = (Map<String, Object>) raw;
            String title = Input.text(map, "title", 240).trim();
            String content = Input.text(map, "content", MAX_CONTENT + 1);
            if (content.length() > MAX_CONTENT) throw ApiException.badRequest("「" + title + "」内容过长，无法导入");
            nodes.add(new ImportNode(title, content, importNodes(map.get("children"), budget, depth + 1)));
        }
        return nodes;
    }

    @GetMapping("/pages/{id}")
    Map<String, Object> detail(@PathVariable int id, User user) {
        PageRef ref = pages.ref(id);
        Role role = access.require(ref.spaceId(), user, Role.reader);
        Map<String, Object> page = jdbc.queryForMap(
                "SELECT p.id,p.space_id spaceId,p.parent_id parentId,p.title,p.icon,p.content,p.revision,p.created_at createdAt,p.updated_at updatedAt," +
                        " c.name createdBy,u.name updatedBy,s.name spaceName,s.icon spaceIcon," +
                        " EXISTS(SELECT 1 FROM favorites f WHERE f.page_id=p.id AND f.user_id=?) favorite" +
                        " FROM pages p JOIN spaces s ON s.id=p.space_id JOIN users c ON c.id=p.created_by JOIN users u ON u.id=p.updated_by WHERE p.id=?",
                user.id(), id);
        page.put("favorite", ((Number) page.get("favorite")).intValue() == 1);
        page.put("ancestors", pages.ancestors(id));
        page.put("myRole", role.name());
        jdbc.update("INSERT INTO page_visits(user_id,page_id,visited_at) VALUES(?,?,CURRENT_TIMESTAMP)" +
                " ON DUPLICATE KEY UPDATE visited_at=CURRENT_TIMESTAMP", user.id(), id);
        return Map.of("page", page);
    }

    @PutMapping("/pages/{id}")
    Map<String, Object> save(@PathVariable int id, @RequestBody Map<String, Object> body, User user) {
        Integer revision = Input.integerOrNull(body, "revision");
        if (revision == null) throw ApiException.badRequest("缺少 revision");
        return pages.save(id, Input.text(body, "title", 240).trim(), Input.text(body, "icon", 16).trim(),
                Input.text(body, "content", MAX_CONTENT), revision, user);
    }

    @PostMapping("/pages/{id}/move")
    Map<String, Object> move(@PathVariable int id, @RequestBody Map<String, Object> body, User user) {
        Integer index = Input.integerOrNull(body, "index");
        pages.move(id, Input.integerOrNull(body, "parentId"), index == null ? Integer.MAX_VALUE : index, user);
        return Map.of("ok", true);
    }

    @DeleteMapping("/pages/{id}")
    Map<String, Object> delete(@PathVariable int id, User user) {
        pages.delete(id, user);
        return Map.of("ok", true);
    }

    @GetMapping("/pages/{id}/versions")
    Map<String, Object> versions(@PathVariable int id, User user) {
        pages.require(id, user, Role.reader);
        return Map.of("versions", jdbc.queryForList(
                "SELECT v.id,v.title,v.created_at createdAt,v.updated_at updatedAt,u.name editor FROM page_versions v JOIN users u ON u.id=v.editor_id" +
                        " WHERE v.page_id=? ORDER BY v.id DESC LIMIT 200", id));
    }

    @GetMapping("/pages/{id}/versions/{versionId}")
    Map<String, Object> version(@PathVariable int id, @PathVariable int versionId, User user) {
        pages.require(id, user, Role.reader);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT v.id,v.title,v.content,v.updated_at updatedAt,u.name editor FROM page_versions v JOIN users u ON u.id=v.editor_id WHERE v.id=? AND v.page_id=?",
                versionId, id);
        if (rows.isEmpty()) throw ApiException.notFound("版本不存在");
        return Map.of("version", rows.get(0));
    }

    @PostMapping("/pages/{id}/versions/{versionId}/restore")
    Map<String, Object> restore(@PathVariable int id, @PathVariable int versionId, User user) {
        pages.restore(id, versionId, user);
        return Map.of("ok", true);
    }

    @GetMapping("/pages/{id}/comments")
    Map<String, Object> comments(@PathVariable int id, User user) {
        pages.require(id, user, Role.reader);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.id,c.parent_id parentId,c.body,c.created_at createdAt,c.user_id userId,u.name author," +
                        "a.anchor_start anchorStart,a.anchor_end anchorEnd,a.quote FROM comments c JOIN users u ON u.id=c.user_id" +
                        " LEFT JOIN comment_anchors a ON a.comment_id=c.id" +
                        " WHERE c.page_id=? ORDER BY c.id", id);
        List<Map<String, Object>> result = rows.stream().map(LinkedHashMap::new).map(row -> (Map<String, Object>) row).toList();
        if (!result.isEmpty()) {
            List<Integer> commentIds = result.stream().map(row -> ((Number) row.get("id")).intValue()).toList();
            String placeholders = String.join(",", java.util.Collections.nCopies(commentIds.size(), "?"));
            List<Map<String, Object>> mentionRows = jdbc.queryForList(
                    "SELECT m.comment_id commentId,u.id,u.username,u.name,u.department FROM comment_mentions m" +
                            " JOIN users u ON u.id=m.user_id WHERE m.comment_id IN (" + placeholders + ") ORDER BY u.name",
                    commentIds.toArray());
            Map<Integer, List<Map<String, Object>>> mentionsByComment = new HashMap<>();
            for (Map<String, Object> mention : mentionRows) {
                int commentId = ((Number) mention.get("commentId")).intValue();
                Map<String, Object> userInfo = new LinkedHashMap<>(mention);
                userInfo.remove("commentId");
                mentionsByComment.computeIfAbsent(commentId, ignored -> new ArrayList<>()).add(userInfo);
            }
            for (Map<String, Object> comment : result)
                comment.put("mentions", mentionsByComment.getOrDefault(((Number) comment.get("id")).intValue(), List.of()));
        }
        Map<Integer, Map<String, Object>> commentsById = new HashMap<>();
        for (Map<String, Object> comment : result) {
            comment.put("replies", new ArrayList<Map<String, Object>>());
            commentsById.put(((Number) comment.get("id")).intValue(), comment);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> comment : result) {
            Number parentId = (Number) comment.get("parentId");
            if (parentId == null) roots.add(comment);
            else {
                Map<String, Object> parent = commentsById.get(parentId.intValue());
                if (parent != null) ((List<Map<String, Object>>) parent.get("replies")).add(comment);
            }
        }
        return Map.of("comments", roots);
    }

    /** Readers can comment, like in the previous version. */
    @PostMapping("/pages/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Object> addComment(@PathVariable int id, @RequestBody Map<String, Object> body, User user) {
        pages.require(id, user, Role.reader);
        String text = Input.text(body, "body", 3000).trim();
        if (text.isEmpty()) throw ApiException.badRequest("评论不能为空且不超过 3000 字");
        Integer parentId = Input.integerOrNull(body, "parentId");
        if (parentId != null) {
            Integer validParent = jdbc.query("SELECT id FROM comments WHERE id=? AND page_id=?",
                    rs -> rs.next() ? rs.getInt(1) : null, parentId, id);
            if (validParent == null) throw ApiException.badRequest("回复的评论不存在");
        }
        jdbc.update("INSERT INTO comments(page_id,user_id,parent_id,body) VALUES(?,?,?,?)", id, user.id(), parentId, text);
        Integer commentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
        Object anchorValue = body.get("anchor");
        if (anchorValue instanceof Map<?, ?> anchor) {
            Map<String, Object> anchorBody = new java.util.HashMap<>();
            anchor.forEach((key, value) -> {
                if (key instanceof String name) anchorBody.put(name, value);
            });
            Integer start = Input.integerOrNull(anchorBody, "start");
            Integer end = Input.integerOrNull(anchorBody, "end");
            Object quoteValue = anchorBody.get("quote");
            String quote = quoteValue == null ? "" : String.valueOf(quoteValue).trim();
            if (start == null || end == null || start < 1 || end <= start || quote.isEmpty() || quote.length() > 5000)
                throw ApiException.badRequest("选中内容无效或过长");
            jdbc.update("INSERT INTO comment_anchors(comment_id,anchor_start,anchor_end,quote) VALUES(?,?,?,?)",
                    commentId, start, end, quote);
        } else if (anchorValue != null) {
            throw ApiException.badRequest("评论锚点无效");
        }
        Object mentionsValue = body.get("mentions");
        if (mentionsValue != null) {
            if (!(mentionsValue instanceof List<?> mentions)) throw ApiException.badRequest("@用户列表无效");
            Set<Integer> mentionIds = new LinkedHashSet<>();
            for (Object value : mentions) {
                if (!(value instanceof Number number) || number.intValue() < 1)
                    throw ApiException.badRequest("@用户列表无效");
                mentionIds.add(number.intValue());
            }
            if (!mentionIds.isEmpty()) {
                String placeholders = String.join(",", java.util.Collections.nCopies(mentionIds.size(), "?"));
                List<Integer> activeUsers = jdbc.queryForList(
                        "SELECT id FROM users WHERE active=1 AND id IN (" + placeholders + ")",
                        Integer.class, mentionIds.toArray());
                if (activeUsers.size() != mentionIds.size()) throw ApiException.badRequest("@用户已不存在或已停用");
                for (Integer mentionId : mentionIds)
                    jdbc.update("INSERT INTO comment_mentions(comment_id,user_id) VALUES(?,?)", commentId, mentionId);
            }
        }
        return Map.of("ok", true);
    }

    @PutMapping("/comments/{commentId}")
    @Transactional
    Map<String, Object> updateComment(@PathVariable int commentId, @RequestBody Map<String, Object> body, User user) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT page_id,user_id FROM comments WHERE id=?", commentId);
        if (rows.isEmpty()) throw ApiException.notFound("璇勮涓嶅瓨鍦?");
        int pageId = ((Number) rows.get(0).get("page_id")).intValue();
        boolean own = ((Number) rows.get(0).get("user_id")).intValue() == user.id();
        pages.require(pageId, user, own ? Role.reader : Role.admin);
        String text = Input.text(body, "body", 3000).trim();
        if (text.isEmpty()) throw ApiException.badRequest("璇勮涓嶈兘涓虹┖涓斾笉瓒呰繃 3000 瀛?");
        jdbc.update("UPDATE comments SET body=? WHERE id=?", text, commentId);
        if (body.containsKey("mentions")) {
            Object mentionsValue = body.get("mentions");
            if (!(mentionsValue instanceof List<?> mentions)) throw ApiException.badRequest("@鐢ㄦ埛鍒楄〃鏃犳晥");
            Set<Integer> mentionIds = new LinkedHashSet<>();
            for (Object value : mentions) {
                if (!(value instanceof Number number) || number.intValue() < 1)
                    throw ApiException.badRequest("@鐢ㄦ埛鍒楄〃鏃犳晥");
                mentionIds.add(number.intValue());
            }
            if (!mentionIds.isEmpty()) {
                String placeholders = String.join(",", java.util.Collections.nCopies(mentionIds.size(), "?"));
                List<Integer> activeUsers = jdbc.queryForList(
                        "SELECT id FROM users WHERE active=1 AND id IN (" + placeholders + ")",
                        Integer.class, mentionIds.toArray());
                if (activeUsers.size() != mentionIds.size()) throw ApiException.badRequest("@鐢ㄦ埛宸蹭笉瀛樺湪鎴栧凡鍋滅敤");
            }
            jdbc.update("DELETE FROM comment_mentions WHERE comment_id=?", commentId);
            for (Integer mentionId : mentionIds)
                jdbc.update("INSERT INTO comment_mentions(comment_id,user_id) VALUES(?,?)", commentId, mentionId);
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/comments/{commentId}")
    Map<String, Object> deleteComment(@PathVariable int commentId, User user) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT page_id,user_id FROM comments WHERE id=?", commentId);
        if (rows.isEmpty()) throw ApiException.notFound("评论不存在");
        int pageId = ((Number) rows.get(0).get("page_id")).intValue();
        boolean own = ((Number) rows.get(0).get("user_id")).intValue() == user.id();
        pages.require(pageId, user, own ? Role.reader : Role.admin);
        jdbc.update("DELETE FROM comments WHERE id=?", commentId);
        return Map.of("ok", true);
    }

    @PutMapping("/pages/{id}/favorite")
    Map<String, Object> favorite(@PathVariable int id, User user) {
        pages.require(id, user, Role.reader);
        jdbc.update("INSERT IGNORE INTO favorites(user_id,page_id) VALUES(?,?)", user.id(), id);
        return Map.of("favorite", true);
    }

    @DeleteMapping("/pages/{id}/favorite")
    Map<String, Object> unfavorite(@PathVariable int id, User user) {
        jdbc.update("DELETE FROM favorites WHERE user_id=? AND page_id=?", user.id(), id);
        return Map.of("favorite", false);
    }
}
