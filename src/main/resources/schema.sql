-- MySQL 8+ schema. Runs on every start; CREATE TABLE IF NOT EXISTS leaves existing tables alone.
-- All timestamps are UTC (the JDBC URL forces the session time zone to UTC).

CREATE TABLE IF NOT EXISTS users(
  id INT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  password_salt CHAR(32) NOT NULL,
  password_hash CHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'member',
  department VARCHAR(100) NOT NULL DEFAULT '',
  active TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT users_role CHECK (role IN ('admin', 'member'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sessions(
  token CHAR(64) PRIMARY KEY,
  user_id INT NOT NULL,
  expires_at BIGINT NOT NULL,
  KEY sessions_expires (expires_at),
  CONSTRAINT sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS spaces(
  id INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  description VARCHAR(500) NOT NULL DEFAULT '',
  icon VARCHAR(16) NOT NULL DEFAULT '📘',
  visibility VARCHAR(16) NOT NULL DEFAULT 'private',
  created_by INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT spaces_visibility CHECK (visibility IN ('private', 'public')),
  CONSTRAINT spaces_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS space_members(
  space_id INT NOT NULL,
  user_id INT NOT NULL,
  role VARCHAR(16) NOT NULL,
  PRIMARY KEY (space_id, user_id),
  CONSTRAINT members_role CHECK (role IN ('admin', 'editor', 'reader')),
  CONSTRAINT members_space FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE,
  CONSTRAINT members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- parent_id has no ON DELETE CASCADE: MySQL limits cascades to 15 levels, so PageService
-- deletes a subtree explicitly instead.
CREATE TABLE IF NOT EXISTS pages(
  id INT AUTO_INCREMENT PRIMARY KEY,
  space_id INT NOT NULL,
  parent_id INT NULL,
  title VARCHAR(240) NOT NULL DEFAULT '',
  icon VARCHAR(16) NOT NULL DEFAULT '',
  content MEDIUMTEXT NOT NULL,
  plain_text MEDIUMTEXT NOT NULL,
  position INT NOT NULL DEFAULT 0,
  revision INT NOT NULL DEFAULT 1,
  created_by INT NOT NULL,
  updated_by INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY pages_tree (space_id, parent_id, position),
  CONSTRAINT pages_space FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE,
  CONSTRAINT pages_parent FOREIGN KEY (parent_id) REFERENCES pages(id),
  CONSTRAINT pages_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT pages_updater FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS page_versions(
  id INT AUTO_INCREMENT PRIMARY KEY,
  page_id INT NOT NULL,
  title VARCHAR(240) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  editor_id INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY versions_page (page_id, id),
  CONSTRAINT versions_page FOREIGN KEY (page_id) REFERENCES pages(id) ON DELETE CASCADE,
  CONSTRAINT versions_editor FOREIGN KEY (editor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comments(
  id INT AUTO_INCREMENT PRIMARY KEY,
  page_id INT NOT NULL,
  user_id INT NOT NULL,
  parent_id INT NULL,
  body TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY comments_page (page_id, id),
  KEY comments_parent (parent_id),
  CONSTRAINT comments_page FOREIGN KEY (page_id) REFERENCES pages(id) ON DELETE CASCADE,
  CONSTRAINT comments_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT comments_parent FOREIGN KEY (parent_id) REFERENCES comments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comment_anchors(
  comment_id INT PRIMARY KEY,
  anchor_start INT NOT NULL,
  anchor_end INT NOT NULL,
  quote TEXT NOT NULL,
  CONSTRAINT comment_anchors_comment FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comment_mentions(
  comment_id INT NOT NULL,
  user_id INT NOT NULL,
  PRIMARY KEY (comment_id, user_id),
  CONSTRAINT comment_mentions_comment FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE,
  CONSTRAINT comment_mentions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS favorites(
  user_id INT NOT NULL,
  page_id INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, page_id),
  CONSTRAINT favorites_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT favorites_page FOREIGN KEY (page_id) REFERENCES pages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS page_visits(
  user_id INT NOT NULL,
  page_id INT NOT NULL,
  visited_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, page_id),
  KEY visits_user (user_id, visited_at),
  CONSTRAINT visits_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT visits_page FOREIGN KEY (page_id) REFERENCES pages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS files(
  name VARCHAR(64) PRIMARY KEY,
  original_name VARCHAR(200) NOT NULL,
  content_type VARCHAR(40) NOT NULL,
  size BIGINT NOT NULL,
  uploader_id INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT files_uploader FOREIGN KEY (uploader_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
