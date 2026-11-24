package com.zhixu.kb.file;

import com.zhixu.kb.auth.PasswordHasher;
import com.zhixu.kb.auth.User;
import com.zhixu.kb.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Image uploads for the editor. Files get random names and are served only to signed-in users. */
@RestController
@RequestMapping("/api/files")
public class FileController {
    private static final Map<String, String> TYPES = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/gif", "gif", "image/webp", "webp");

    private final JdbcTemplate jdbc;
    private final Path dir;

    public FileController(JdbcTemplate jdbc, @Value("${kb.upload-dir}") String dir) throws IOException {
        this.jdbc = jdbc;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(this.dir);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> upload(@RequestParam("file") MultipartFile file, User user) throws IOException {
        if (file.isEmpty()) throw ApiException.badRequest("文件为空");
        String type = sniff(file);
        if (type == null) throw ApiException.badRequest("只支持 PNG、JPEG、GIF、WebP 图片");
        String name = PasswordHasher.randomHex(16) + "." + TYPES.get(type);
        file.transferTo(dir.resolve(name));
        String original = file.getOriginalFilename() == null ? name : file.getOriginalFilename();
        jdbc.update("INSERT INTO files(name,original_name,content_type,size,uploader_id) VALUES(?,?,?,?,?)",
                name, original.length() > 200 ? original.substring(0, 200) : original, type, file.getSize(), user.id());
        return Map.of("url", "/api/files/" + name);
    }

    @GetMapping("/{name:[a-f0-9]{32}\\.[a-z]{3,4}}")
    ResponseEntity<Resource> download(@PathVariable String name, User user) {
        List<String> type = jdbc.queryForList("SELECT content_type FROM files WHERE name=?", String.class, name);
        Path path = dir.resolve(name).normalize();
        if (type.isEmpty() || !path.startsWith(dir) || !Files.exists(path)) throw ApiException.notFound("文件不存在");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type.get(0))).body(new FileSystemResource(path));
    }

    /** Detects the image type from magic bytes instead of trusting the client's Content-Type. */
    private static String sniff(MultipartFile file) throws IOException {
        byte[] h = new byte[12];
        int n;
        try (InputStream in = file.getInputStream()) {
            n = in.readNBytes(h, 0, h.length);
        }
        if (n >= 8 && (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "image/png";
        if (n >= 3 && (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff) return "image/jpeg";
        if (n >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return "image/gif";
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P')
            return "image/webp";
        return null;
    }
}
