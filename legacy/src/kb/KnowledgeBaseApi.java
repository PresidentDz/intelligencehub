package com.zhixu.kb;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class KnowledgeBaseApi implements CommandLineRunner {
    private static final int ITERATIONS = 240_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9_.-]{2,40}");
    private static final Safelist HTML = Safelist.none()
            .addTags("p", "br", "h1", "h2", "h3", "strong", "b", "em", "i", "ul", "ol", "li", "blockquote", "code", "pre", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto");

    private final JdbcTemplate jdbc;

    public KnowledgeBaseApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        String[] schema = {
                "CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, name TEXT NOT NULL, password_salt TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'reader', department TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS categories(id INTEGER PRIMARY KEY, name TEXT UNIQUE NOT NULL)",
                "CREATE TABLE IF NOT EXISTS documents(id INTEGER PRIMARY KEY, title TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL, visibility TEXT NOT NULL DEFAULT 'company', department TEXT NOT NULL DEFAULT '', owner_id INTEGER NOT NULL REFERENCES users(id), updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS versions(id INTEGER PRIMARY KEY, document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE, title TEXT NOT NULL, content TEXT NOT NULL, editor_id INTEGER NOT NULL REFERENCES users(id), created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS comments(id INTEGER PRIMARY KEY, document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE, user_id INTEGER NOT NULL REFERENCES users(id), body TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, expires_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS docs_updated ON documents(updated_at DESC)",
                "CREATE INDEX IF NOT EXISTS versions_doc ON versions(document_id, id DESC)"
        };
        for (String sql : schema) jdbc.execute(sql);
        Integer users = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (users == null || users == 0) seed();
    }

    private void seed() {
        addUser("admin", "系统管理员", "admin123", "admin", "产品部");
        addUser("editor", "林编辑", "editor123", "editor", "产品部");
        addUser("reader", "周同学", "reader123", "reader", "研发部");
        for (String category : List.of("团队手册", "产品与设计", "工程实践", "会议纪要"))
            jdbc.update("INSERT INTO categories(name) VALUES(?)", category);
        int admin = id("SELECT id FROM users WHERE username='admin'");
        seedDoc("新同事入职指南", "<h2>欢迎加入团队</h2><p>这里整理了入职第一周需要了解的信息，帮助你快速熟悉团队与工作方式。</p><h3>第一天</h3><ul><li>联系直属负责人完成账号与设备配置</li><li>阅读团队工作约定，了解每周例会安排</li><li>在团队目录中找到自己的项目空间</li></ul><blockquote>遇到问题时，先问身边的伙伴。文档会持续更新。</blockquote>", "团队手册", "company", "", admin);
        seedDoc("产品发布检查清单", "<h2>发布前</h2><ul><li>需求与验收标准已确认</li><li>关键路径的自动化测试通过</li><li>准备回滚方案与监控面板</li><li>发布说明已同步给支持团队</li></ul><h2>发布后</h2><p>观察核心指标与错误率，在发布记录中注明结论。</p>", "产品与设计", "department", "产品部", admin);
        seedDoc("服务值班手册", "<h2>响应原则</h2><p>先确认影响范围，再恢复服务，最后复盘根因。每次操作都记录时间线和关键决策。</p><h3>升级路径</h3><ol><li>在值班群标记当班负责人</li><li>评估用户影响并更新状态</li><li>需要跨团队协助时联系服务 owner</li></ol>", "工程实践", "company", "", admin);
        seedDoc("Q3 规划讨论", "<p><strong>讨论重点</strong></p><ul><li>缩短核心流程的首次使用时间</li><li>梳理高频反馈并明确负责人</li><li>优先补齐内部文档的维护机制</li></ul><p>下一步：各方向负责人周五前更新计划。</p>", "会议纪要", "private", "", admin);
    }

    private void seedDoc(String title, String content, String category, String visibility, String department, int owner) {
        int cat = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Integer.class, category);
        jdbc.update("INSERT INTO documents(title,content,category_id,visibility,department,owner_id) VALUES(?,?,?,?,?,?)", title, content, cat, visibility, department, owner);
        int did = jdbc.queryForObject("SELECT id FROM documents WHERE owner_id=? AND title=? ORDER BY id DESC LIMIT 1", Integer.class, owner, title);
        jdbc.update("INSERT INTO versions(document_id,title,content,editor_id) VALUES(?,?,?,?)", did, title, content, owner);
    }

    private record User(int id, String username, String name, String role, String department, boolean active) {
        Map<String, Object> json() {
            return Map.of("id", id, "username", username, "name", name, "role", role, "department", department, "active", active);
        }
    }

    private record Doc(int id, String title, String content, Integer categoryId, String category,
                       String visibility, String department, int ownerId, String ownerName, String updatedAt) {}

    private final RowMapper<User> userMapper = (rs, n) -> user(rs);
    private static User user(ResultSet rs) throws SQLException {
        return new User(rs.getInt("id"), rs.getString("username"), rs.getString("name"), rs.getString("role"), rs.getString("department"), rs.getInt("active") != 0);
    }

    private User current(HttpServletRequest request) {
        String token = cookie(request, "kb_session");
        if (token == null || !token.matches("[a-f0-9]{64}")) return null;
        List<User> rows = jdbc.query("SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token=? AND s.expires_at>? AND u.active=1", userMapper, token, Instant.now().getEpochSecond());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private static boolean secureRequest(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }

    private User require(HttpServletRequest request, String... roles) {
        User user = current(request);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        if (roles.length > 0 && Arrays.stream(roles).noneMatch(user.role()::equals))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有执行此操作的权限");
        return user;
    }

    private Doc getDoc(int id, User user) {
        List<Doc> rows = jdbc.query("SELECT d.*,c.name category,u.name owner_name FROM documents d LEFT JOIN categories c ON c.id=d.category_id JOIN users u ON u.id=d.owner_id WHERE d.id=?",
                (rs, n) -> new Doc(rs.getInt("id"), rs.getString("title"), rs.getString("content"), nullableInt(rs, "category_id"), rs.getString("category"), rs.getString("visibility"), rs.getString("department"), rs.getInt("owner_id"), rs.getString("owner_name"), rs.getString("updated_at")), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        Doc doc = rows.get(0);
        if (!visible(doc, user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看此文档");
        return doc;
    }

    private boolean visible(Doc d, User u) {
        return u.role().equals("admin") || d.visibility().equals("company") ||
                (d.visibility().equals("department") && d.department().equals(u.department())) ||
                (d.visibility().equals("private") && d.ownerId() == u.id());
    }

    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        User u = current(request);
        Map<String, Object> result = new HashMap<>();
        result.put("user", u == null ? null : u.json());
        return result;
    }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
        String username = text(body.get("username"), 80);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM users WHERE username=? AND active=1", username);
        if (rows.isEmpty() || !verify(text(body.get("password"), 1000), (String) rows.get(0).get("password_salt"), (String) rows.get(0).get("password_hash")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码不正确");
        Map<String, Object> row = rows.get(0);
        String token = randomHex(32);
        jdbc.update("INSERT INTO sessions(token,user_id,expires_at) VALUES(?,?,?)", token, row.get("id"), Instant.now().getEpochSecond() + 1_209_600);
        response.addHeader("Set-Cookie", "kb_session=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=1209600" + (secureRequest(request) ? "; Secure" : ""));
        return Map.of("user", userFrom(row).json());
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = cookie(request, "kb_session");
        if (token != null) jdbc.update("DELETE FROM sessions WHERE token=?", token);
        response.addHeader("Set-Cookie", "kb_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0" + (secureRequest(request) ? "; Secure" : ""));
        return Map.of("ok", true);
    }

    @GetMapping("/docs")
    Map<String, Object> documents(@RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        User user = require(request);
        String like = "%" + q.trim() + "%";
        List<Doc> all = jdbc.query("SELECT d.*,c.name category,u.name owner_name FROM documents d LEFT JOIN categories c ON c.id=d.category_id JOIN users u ON u.id=d.owner_id WHERE d.title LIKE ? OR d.content LIKE ? ORDER BY d.updated_at DESC",
                (rs, n) -> new Doc(rs.getInt("id"), rs.getString("title"), rs.getString("content"), nullableInt(rs, "category_id"), rs.getString("category"), rs.getString("visibility"), rs.getString("department"), rs.getInt("owner_id"), rs.getString("owner_name"), rs.getString("updated_at")), like, like);
        List<Map<String, Object>> docs = all.stream().filter(d -> visible(d, user)).map(d -> {
            Map<String, Object> m = docMap(d);
            m.put("excerpt", d.content().replaceAll("<[^>]+>", " ").substring(0, Math.min(180, d.content().replaceAll("<[^>]+>", " ").length())));
            m.remove("content");
            return m;
        }).toList();
        return Map.of("documents", docs, "categories", jdbc.queryForList("SELECT id,name FROM categories ORDER BY name"));
    }

    @GetMapping("/docs/{id}")
    Map<String, Object> document(@PathVariable int id, HttpServletRequest request) {
        User user = require(request);
        Doc doc = getDoc(id, user);
        Map<String, Object> m = docMap(doc);
        m.put("can_edit", user.role().equals("admin") || user.role().equals("editor"));
        return Map.of("document", m);
    }

    @GetMapping("/docs/{id}/versions")
    Map<String, Object> versions(@PathVariable int id, HttpServletRequest request) {
        getDoc(id, require(request));
        return Map.of("versions", jdbc.queryForList("SELECT v.id,v.title,v.created_at,u.name editor FROM versions v JOIN users u ON u.id=v.editor_id WHERE document_id=? ORDER BY v.id DESC", id));
    }

    @GetMapping("/docs/{id}/comments")
    Map<String, Object> comments(@PathVariable int id, HttpServletRequest request) {
        getDoc(id, require(request));
        return Map.of("comments", jdbc.queryForList("SELECT c.id,c.body,c.created_at,u.name FROM comments c JOIN users u ON u.id=c.user_id WHERE document_id=? ORDER BY c.id", id));
    }

    @PostMapping("/docs/{id}/comments")
    Map<String, Object> addComment(@PathVariable int id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        User user = require(request);
        getDoc(id, user);
        String text = text(body.get("body"), 3000).trim();
        if (text.isEmpty()) throw bad("评论不能为空且不超过 3000 字");
        jdbc.update("INSERT INTO comments(document_id,user_id,body) VALUES(?,?,?)", id, user.id(), text);
        return Map.of("ok", true);
    }

    @PostMapping("/docs")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        User user = require(request, "admin", "editor");
        String title = title(body);
        String content = sanitize(body.get("content"));
        String visibility = visibility(body);
        String department = visibility.equals("department") && !user.role().equals("admin") ? user.department() : text(body.getOrDefault("department", user.department()), 100);
        Integer category = integerOrNull(body.get("category_id"));
        jdbc.update("INSERT INTO documents(title,content,category_id,visibility,department,owner_id) VALUES(?,?,?,?,?,?)", title, content, category, visibility, department, user.id());
        int id = id("SELECT last_insert_rowid()");
        saveVersion(id, title, content, user.id());
        return Map.of("id", id);
    }

    @PutMapping("/docs/{id}")
    Map<String, Object> update(@PathVariable int id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        User user = require(request, "admin", "editor");
        Doc old = getDoc(id, user);
        String title = title(body);
        String content = sanitize(body.get("content"));
        String visibility = visibility(body);
        String department = visibility.equals("department") && !user.role().equals("admin") ? user.department() : text(body.getOrDefault("department", old.department()), 100);
        Integer category = integerOrNull(body.get("category_id"));
        jdbc.update("UPDATE documents SET title=?,content=?,category_id=?,visibility=?,department=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", title, content, category, visibility, department, id);
        saveVersion(id, title, content, user.id());
        return Map.of("id", old.id());
    }

    @PostMapping("/docs/{id}/restore")
    Map<String, Object> restore(@PathVariable int id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        User user = require(request, "admin", "editor");
        getDoc(id, user);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM versions WHERE id=? AND document_id=?", integerOrNull(body.get("version_id")), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        Map<String, Object> version = rows.get(0);
        String title = (String) version.get("title"), content = (String) version.get("content");
        jdbc.update("UPDATE documents SET title=?,content=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", title, content, id);
        saveVersion(id, title, content, user.id());
        return Map.of("ok", true);
    }

    @GetMapping("/admin/users")
    Map<String, Object> users(HttpServletRequest request) {
        require(request, "admin");
        return Map.of("users", jdbc.query("SELECT * FROM users ORDER BY id", (rs, n) -> user(rs).json()));
    }

    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createUser(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        require(request, "admin");
        String username = text(body.get("username"), 40).trim();
        String name = text(body.get("name"), 120).trim();
        String password = text(body.get("password"), 1000);
        String role = text(body.getOrDefault("role", "reader"), 20);
        if (!USERNAME.matcher(username).matches() || name.isEmpty() || password.length() < 8 || !validRole(role))
            throw bad("账号格式无效，密码至少 8 位");
        addUser(username, name, password, role, text(body.get("department"), 100));
        return Map.of("ok", true);
    }

    @PutMapping("/admin/users/{id}")
    Map<String, Object> updateUser(@PathVariable int id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        User current = require(request, "admin");
        String role = text(body.getOrDefault("role", "reader"), 20);
        boolean active = !Boolean.FALSE.equals(body.get("active"));
        if (!validRole(role)) throw bad("角色无效");
        if (id == current.id() && (!role.equals("admin") || !active)) throw bad("不能停用或降级自己的管理员账号");
        int changed = jdbc.update("UPDATE users SET role=?,department=?,active=? WHERE id=?", role, text(body.get("department"), 100), active ? 1 : 0, id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        return Map.of("ok", true);
    }

    @GetMapping("/admin/categories")
    Map<String, Object> categories(HttpServletRequest request) {
        require(request, "admin");
        return Map.of("categories", jdbc.queryForList("SELECT * FROM categories ORDER BY name"));
    }

    @PostMapping("/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> addCategory(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        require(request, "admin");
        String name = text(body.get("name"), 80).trim();
        if (name.isEmpty()) throw bad("分类名称不能为空");
        jdbc.update("INSERT INTO categories(name) VALUES(?)", name);
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/categories/{id}")
    Map<String, Object> deleteCategory(@PathVariable int id, HttpServletRequest request) {
        require(request, "admin");
        jdbc.update("DELETE FROM categories WHERE id=?", id);
        return Map.of("ok", true);
    }

    private Map<String, Object> docMap(Doc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id()); m.put("title", d.title()); m.put("content", d.content()); m.put("category_id", d.categoryId());
        m.put("category", d.category()); m.put("visibility", d.visibility()); m.put("department", d.department());
        m.put("owner_id", d.ownerId()); m.put("owner_name", d.ownerName()); m.put("updated_at", d.updatedAt());
        return m;
    }

    private static User userFrom(Map<String, Object> m) {
        return new User(((Number)m.get("id")).intValue(), (String)m.get("username"), (String)m.get("name"), (String)m.get("role"), (String)m.get("department"), ((Number)m.get("active")).intValue() != 0);
    }

    private static boolean validRole(String role) { return Set.of("admin", "editor", "reader").contains(role); }
    private static String title(Map<String, Object> body) {
        String title = text(body.get("title"), 240).trim();
        if (title.isEmpty()) throw bad("标题不能为空");
        return title;
    }
    private static String visibility(Map<String, Object> body) {
        String value = text(body.getOrDefault("visibility", "company"), 20);
        if (!Set.of("company", "department", "private").contains(value)) throw bad("可见范围无效");
        return value;
    }
    private static String sanitize(Object value) { return Jsoup.clean(text(value, 1_500_000), HTML); }
    private static String text(Object value, int max) {
        String s = value == null ? "" : String.valueOf(value);
        return s.length() > max ? s.substring(0, max) : s;
    }
    private static Integer integerOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw bad("数字格式无效"); }
    }
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private int id(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private void saveVersion(int id, String title, String content, int editor) {
        jdbc.update("INSERT INTO versions(document_id,title,content,editor_id) VALUES(?,?,?,?)", id, title, content, editor);
    }

    private void addUser(String username, String name, String password, String role, String department) {
        String salt = randomHex(16);
        jdbc.update("INSERT INTO users(username,name,password_salt,password_hash,role,department) VALUES(?,?,?,?,?,?)",
                username, name, salt, hash(password, salt), role, department);
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes]; RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static String hash(String password, String saltHex) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(saltHex), ITERATIONS, 256);
        try {
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ex) { throw new IllegalStateException("Password hashing is unavailable", ex); }
        finally { spec.clearPassword(); }
    }

    private static boolean verify(String password, String salt, String expected) {
        return MessageDigest.isEqual(hash(password, salt).getBytes(java.nio.charset.StandardCharsets.US_ASCII), expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> duplicateName() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "名称已存在或数据关联无效"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> apiError(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(Map.of("error", Objects.requireNonNullElse(ex.getReason(), status.getReasonPhrase())));
    }
}
