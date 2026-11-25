package com.zhixu.kb.page;

import com.zhixu.kb.auth.User;
import com.zhixu.kb.common.ApiException;
import com.zhixu.kb.common.Db;
import com.zhixu.kb.common.HtmlSanitizer;
import com.zhixu.kb.space.SpaceAccess;
import com.zhixu.kb.space.SpaceAccess.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PageService {
    /** Autosaves by the same editor within this window are folded into one history entry. */
    private static final int VERSION_WINDOW_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final SpaceAccess access;

    public PageService(JdbcTemplate jdbc, SpaceAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record PageRef(int id, int spaceId, Integer parentId) {}

    public PageRef ref(int pageId) {
        List<PageRef> rows = jdbc.query("SELECT id,space_id,parent_id FROM pages WHERE id=?",
                (rs, n) -> new PageRef(rs.getInt("id"), rs.getInt("space_id"), rs.getObject("parent_id", Integer.class)), pageId);
        if (rows.isEmpty()) throw ApiException.notFound("页面不存在");
        return rows.get(0);
    }

    /** Loads the page and checks the caller has at least {@code minimum} in its space. */
    public PageRef require(int pageId, User user, Role minimum) {
        PageRef page = ref(pageId);
        access.require(page.spaceId(), user, minimum);
        return page;
    }

    @Transactional
    public int create(int spaceId, Integer parentId, String title, String content, User user) {
        access.require(spaceId, user, Role.editor);
        if (parentId != null && ref(parentId).spaceId() != spaceId) throw ApiException.badRequest("父页面不在该空间中");
        String html = HtmlSanitizer.clean(content);
        Integer position = jdbc.queryForObject("SELECT COALESCE(MAX(position),-1)+1 FROM pages WHERE space_id=? AND parent_id <=> ?",
                Integer.class, spaceId, parentId);
        int id = Db.insert(jdbc, "INSERT INTO pages(space_id,parent_id,title,content,plain_text,position,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?)",
                spaceId, parentId, title, html, HtmlSanitizer.plainText(html), position, user.id(), user.id());
        jdbc.update("INSERT INTO page_versions(page_id,title,content,editor_id) VALUES(?,?,?,?)", id, title, html, user.id());
        return id;
    }

    /** A page to import together with its subpages. */
    public record ImportNode(String title, String content, List<ImportNode> children) {}

    /**
     * Creates the given page trees under {@code parentId} (null = space root) in one
     * transaction, so a failed import leaves nothing behind. Returns the ids of the top-level pages.
     */
    @Transactional
    public List<Integer> importTree(int spaceId, Integer parentId, List<ImportNode> nodes, User user) {
        List<Integer> ids = new ArrayList<>();
        for (ImportNode node : nodes) {
            int id = create(spaceId, parentId, node.title(), node.content(), user);
            importTree(spaceId, id, node.children(), user);
            ids.add(id);
        }
        return ids;
    }

    /**
     * Pages with content for export: the whole space when {@code rootId} is null, otherwise
     * that page and its descendants. Unlike the page detail endpoint, this records no visits.
     */
    public List<Map<String, Object>> exportTree(int spaceId, Integer rootId, User user) {
        access.require(spaceId, user, Role.reader);
        if (rootId == null)
            return jdbc.queryForList("SELECT id,parent_id parentId,title,icon,content,position FROM pages WHERE space_id=? ORDER BY position,id", spaceId);
        if (ref(rootId).spaceId() != spaceId) throw ApiException.notFound("页面不存在");
        return jdbc.queryForList(
                "WITH RECURSIVE t(id) AS (SELECT id FROM pages WHERE id=? UNION ALL SELECT p.id FROM pages p JOIN t ON p.parent_id=t.id)" +
                        " SELECT p.id,p.parent_id parentId,p.title,p.icon,p.content,p.position FROM pages p JOIN t ON t.id=p.id ORDER BY p.position,p.id",
                rootId);
    }

    /**
     * Saves the page if {@code baseRevision} matches the stored revision, otherwise rejects
     * with 409 so the client can tell the user someone else changed the page.
     */
    @Transactional
    public Map<String, Object> save(int pageId, String title, String icon, String content, int baseRevision, User user) {
        require(pageId, user, Role.editor);
        String html = HtmlSanitizer.clean(content);
        int changed = jdbc.update("UPDATE pages SET title=?,icon=?,content=?,plain_text=?,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=?",
                title, icon, html, HtmlSanitizer.plainText(html), user.id(), pageId, baseRevision);
        if (changed == 0) throw ApiException.conflict("页面已被其他人修改，请刷新后再编辑");
        recordVersion(pageId, title, html, user.id());
        return jdbc.queryForMap("SELECT revision,updated_at updatedAt FROM pages WHERE id=?", pageId);
    }

    @Transactional
    public void restore(int pageId, int versionId, User user) {
        require(pageId, user, Role.editor);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT title,content FROM page_versions WHERE id=? AND page_id=?", versionId, pageId);
        if (rows.isEmpty()) throw ApiException.notFound("版本不存在");
        String title = (String) rows.get(0).get("title");
        String content = (String) rows.get(0).get("content");
        jdbc.update("UPDATE pages SET title=?,content=?,plain_text=?,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                title, content, HtmlSanitizer.plainText(content), user.id(), pageId);
        jdbc.update("INSERT INTO page_versions(page_id,title,content,editor_id) VALUES(?,?,?,?)", pageId, title, content, user.id());
    }

    /** Moves a page under {@code parentId} (null = space root) at sibling {@code index}. */
    @Transactional
    public void move(int pageId, Integer parentId, int index, User user) {
        move(pageId, parentId, index, null, user);
    }

    @Transactional
    public void move(int pageId, Integer parentId, int index, Integer targetSpaceId, User user) {
        PageRef page = require(pageId, user, Role.editor);
        int destinationSpaceId = targetSpaceId == null ? page.spaceId() : targetSpaceId;
        if (destinationSpaceId != page.spaceId()) {
            access.require(destinationSpaceId, user, Role.editor);
            if (parentId != null) throw ApiException.badRequest("跨空间移动只能移动到目标空间根目录");
            moveAcrossSpaces(page, destinationSpaceId, user);
            return;
        }
        if (parentId != null) {
            if (ref(parentId).spaceId() != page.spaceId()) throw ApiException.badRequest("只能在同一空间内移动");
            for (Integer cursor = parentId; cursor != null; cursor = ref(cursor).parentId())
                if (cursor == pageId) throw ApiException.badRequest("不能把页面移动到它自己的子页面下");
        }
        List<Integer> siblings = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM pages WHERE space_id=? AND parent_id <=> ? AND id<>? ORDER BY position,id",
                Integer.class, page.spaceId(), parentId, pageId));
        siblings.add(Math.max(0, Math.min(index, siblings.size())), pageId);
        jdbc.update("UPDATE pages SET parent_id=? WHERE id=?", parentId, pageId);
        for (int i = 0; i < siblings.size(); i++)
            jdbc.update("UPDATE pages SET position=? WHERE id=?", i, siblings.get(i));
    }

    private void moveAcrossSpaces(PageRef page, int targetSpaceId, User user) {
        int position = jdbc.queryForObject("SELECT position FROM pages WHERE id=?", Integer.class, page.id());
        int targetPosition = jdbc.queryForObject("SELECT COALESCE(MAX(position),-1)+1 FROM pages WHERE space_id=? AND parent_id IS NULL",
                Integer.class, targetSpaceId);
        jdbc.update("UPDATE pages SET position=position-1 WHERE space_id=? AND parent_id <=> ? AND position>?",
                page.spaceId(), page.parentId(), position);
        jdbc.update("WITH RECURSIVE subtree(id) AS (" +
                        "SELECT id FROM pages WHERE id=? UNION ALL " +
                        "SELECT child.id FROM pages child JOIN subtree parent ON child.parent_id=parent.id) " +
                        "UPDATE pages page JOIN subtree ON subtree.id=page.id " +
                        "SET page.space_id=?,page.parent_id=IF(page.id=?,NULL,page.parent_id),page.position=IF(page.id=?, ?,page.position)," +
                        "page.revision=page.revision+1,page.updated_by=?,page.updated_at=CURRENT_TIMESTAMP",
                page.id(), targetSpaceId, page.id(), page.id(), targetPosition, user.id());
    }

    /**
     * Deletes the page and all of its descendants. pages.parent_id has no cascade (MySQL caps
     * cascades at 15 levels), so the subtree is detached and then deleted in one statement;
     * versions, comments, favorites and visits still cascade from each page.
     */
    @Transactional
    public void delete(int pageId, User user) {
        require(pageId, user, Role.editor);
        List<Integer> ids = jdbc.queryForList(
                "WITH RECURSIVE t(id) AS (SELECT id FROM pages WHERE id=? UNION ALL SELECT p.id FROM pages p JOIN t ON p.parent_id=t.id) SELECT id FROM t",
                Integer.class, pageId);
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.update("UPDATE pages SET parent_id=NULL WHERE id IN (" + in + ")", ids.toArray());
        jdbc.update("DELETE FROM pages WHERE id IN (" + in + ")", ids.toArray());
    }

    public List<Map<String, Object>> ancestors(int pageId) {
        return jdbc.queryForList(
                "WITH RECURSIVE a(id,parent_id,title,depth) AS (SELECT id,parent_id,title,0 FROM pages WHERE id=?" +
                        " UNION ALL SELECT p.id,p.parent_id,p.title,a.depth+1 FROM pages p JOIN a ON p.id=a.parent_id)" +
                        " SELECT id,title FROM a WHERE depth>0 ORDER BY depth DESC", pageId);
    }

    private void recordVersion(int pageId, String title, String content, int editorId) {
        List<Map<String, Object>> latest = jdbc.queryForList(
                "SELECT id,editor_id,created_at>CURRENT_TIMESTAMP - INTERVAL ? MINUTE recent FROM page_versions WHERE page_id=? ORDER BY id DESC LIMIT 1",
                VERSION_WINDOW_MINUTES, pageId);
        if (!latest.isEmpty() && Objects.equals(((Number) latest.get(0).get("editor_id")).intValue(), editorId)
                && ((Number) latest.get(0).get("recent")).intValue() == 1) {
            jdbc.update("UPDATE page_versions SET title=?,content=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", title, content, latest.get(0).get("id"));
        } else {
            jdbc.update("INSERT INTO page_versions(page_id,title,content,editor_id) VALUES(?,?,?,?)", pageId, title, content, editorId);
        }
    }
}
