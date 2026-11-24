package mawrid;

import java.sql.Connection;
import java.util.Map;

import static mawrid.Db.*;

/** Inserts demo suppliers, a merchant, a consumer and products on first start (set SEED=false to skip). */
public class Seed {
    static long user(Connection c, String role, String name, String email, String hash) throws Exception {
        exec(c, "INSERT IGNORE INTO users(role_id,full_name,email,password_hash) VALUES((SELECT role_id FROM roles WHERE role_name=?),?,?,?)", role, name, email, hash);
        return ((Number) one(c, "SELECT user_id FROM users WHERE email=?", email).get("user_id")).longValue();
    }

    static long cat(Connection c, String name) throws Exception {
        return ((Number) one(c, "SELECT category_id FROM categories WHERE name=?", name).get("category_id")).longValue();
    }

    /** tiers like "1-49:80;50-199:72;200+:65" */
    static void product(Connection c, long sup, long cat, String name, String key, double base, String grade, int stock, String tiers) throws Exception {
        long id = insert(c, "INSERT INTO products(supplier_id,category_id,name,product_key,base_price,quality_grade,stock_qty) VALUES(?,?,?,?,?,?,?)",
                sup, cat, name, key, base, grade, stock);
        for (String t : tiers.split(";")) {
            String[] a = t.split(":"), r = a[0].split("-");
            boolean open = a[0].endsWith("+");
            int min = Integer.parseInt(open ? a[0].replace("+", "") : r[0]);
            Integer max = open ? null : Integer.parseInt(r[1]);
            exec(c, "INSERT INTO price_tiers(product_id,min_qty,max_qty,unit_price) VALUES(?,?,?,?)", id, min, max, Double.parseDouble(a[1]));
        }
    }

    static void run(Connection c) throws Exception {
        if (one(c, "SELECT 1 FROM products LIMIT 1") != null || "false".equalsIgnoreCase(env("SEED", "true"))) return;
        String h = Auth.hash("demo1234");
        long najd = user(c, "SUPPLIER", "Najd Appliances", "najd@mawrid.sa", h);
        long gulf = user(c, "SUPPLIER", "Gulf Home Supply", "gulf@mawrid.sa", h);
        long foods = user(c, "SUPPLIER", "Eastern Foods", "foods@mawrid.sa", h);
        user(c, "MERCHANT", "Al-Noor Retail", "noor@mawrid.sa", h);
        user(c, "CONSUMER", "Sara Consumer", "sara@mawrid.sa", h);
        long app = cat(c, "Home appliances"), gro = cat(c, "Groceries"), bld = cat(c, "Building materials");
        product(c, najd, app, "Blender 1.5L", "blender 1.5l", 80, "A", 300, "1-49:80;50-199:72;200+:65");
        product(c, gulf, app, "Blender 1.5L", "blender 1.5l", 76, "B", 120, "1-29:76;30+:70");
        product(c, gulf, app, "Washing machine 8kg", "washer 8kg", 1450, "A", 12, "1-4:1450;5+:1330");
        product(c, foods, gro, "Basmati rice 10kg", "rice 10kg", 10, "A", 500, "1-49:10;50-199:9;200+:8");
        product(c, najd, gro, "Basmati rice 10kg", "rice 10kg", 9.5, "B", 60, "1-99:9.5;100+:8.6");
        product(c, foods, bld, "Cement bag 50kg", "cement 50kg", 14, "A", 0, "1-99:14;100+:12.5");
        System.out.println("Seeded demo data (demo accounts use password demo1234).");
    }
}
