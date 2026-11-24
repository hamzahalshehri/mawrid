CREATE DATABASE IF NOT EXISTS mawrid CHARACTER SET utf8mb4;
USE mawrid;

CREATE TABLE roles (
  role_id INT AUTO_INCREMENT PRIMARY KEY,
  role_name VARCHAR(20) NOT NULL UNIQUE
) ENGINE=InnoDB;
INSERT INTO roles(role_name) VALUES ('ADMIN'),('SUPPLIER'),('MERCHANT'),('CONSUMER');

CREATE TABLE users (
  user_id INT AUTO_INCREMENT PRIMARY KEY,
  role_id INT NOT NULL,
  full_name VARCHAR(100) NOT NULL,
  email VARCHAR(120) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  status ENUM('ACTIVE','SUSPENDED','DELETED') NOT NULL DEFAULT 'ACTIVE',
  phone VARCHAR(20),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (role_id) REFERENCES roles(role_id)
) ENGINE=InnoDB;

CREATE TABLE categories (
  category_id INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(80) NOT NULL UNIQUE
) ENGINE=InnoDB;
INSERT INTO categories(name) VALUES ('Home appliances'),('Groceries'),('Building materials');

CREATE TABLE products (
  product_id INT AUTO_INCREMENT PRIMARY KEY,
  supplier_id INT NOT NULL,
  category_id INT NOT NULL,
  name VARCHAR(120) NOT NULL,
  product_key VARCHAR(120) NOT NULL,
  base_price DECIMAL(10,2) NOT NULL,
  quality_grade ENUM('A','B','C') NOT NULL DEFAULT 'A',
  stock_qty INT NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_stock CHECK (stock_qty >= 0),
  FOREIGN KEY (supplier_id) REFERENCES users(user_id),
  FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE RESTRICT,
  INDEX idx_compare (category_id, product_key)
) ENGINE=InnoDB;

CREATE TABLE price_tiers (
  tier_id INT AUTO_INCREMENT PRIMARY KEY,
  product_id INT NOT NULL,
  min_qty INT NOT NULL,
  max_qty INT NULL,
  unit_price DECIMAL(10,2) NOT NULL,
  UNIQUE (product_id, min_qty),
  FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE orders (
  order_id INT AUTO_INCREMENT PRIMARY KEY,
  buyer_id INT NOT NULL,
  supplier_id INT NOT NULL,
  status ENUM('PLACED','CONFIRMED','PROCESSING','SHIPPED','DELIVERED','CANCELLED') NOT NULL DEFAULT 'PLACED',
  delivery_address VARCHAR(255) NOT NULL,
  total_amount DECIMAL(12,2) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (buyer_id) REFERENCES users(user_id),
  FOREIGN KEY (supplier_id) REFERENCES users(user_id),
  INDEX idx_buyer (buyer_id, status),
  INDEX idx_supplier (supplier_id, status)
) ENGINE=InnoDB;

CREATE TABLE order_items (
  item_id INT AUTO_INCREMENT PRIMARY KEY,
  order_id INT NOT NULL,
  product_id INT NOT NULL,
  quantity INT NOT NULL CHECK (quantity > 0),
  unit_price_locked DECIMAL(10,2) NOT NULL,
  line_total DECIMAL(12,2) NOT NULL,
  FOREIGN KEY (order_id) REFERENCES orders(order_id),
  FOREIGN KEY (product_id) REFERENCES products(product_id)
) ENGINE=InnoDB;

CREATE TABLE order_status_log (
  log_id INT AUTO_INCREMENT PRIMARY KEY,
  order_id INT NOT NULL,
  from_status ENUM('PLACED','CONFIRMED','PROCESSING','SHIPPED','DELIVERED','CANCELLED') NULL,
  to_status ENUM('PLACED','CONFIRMED','PROCESSING','SHIPPED','DELIVERED','CANCELLED') NOT NULL,
  changed_by INT NOT NULL,
  changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (order_id) REFERENCES orders(order_id),
  FOREIGN KEY (changed_by) REFERENCES users(user_id),
  INDEX idx_timeline (order_id, changed_at)
) ENGINE=InnoDB;
