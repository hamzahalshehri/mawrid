package mawrid;

import com.google.gson.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import static mawrid.Db.*;
import static mawrid.Service.*;

public class Main {
    static final Gson G = new Gson();
    static final Map<String, Integer> sessions = new ConcurrentHashMap<>();

    interface H { Object run(Ctx x) throws Exception; }
    record Created(Object body) {}
    record Route(String method, Pattern p, Set<String> roles, H h) {}

    static class Ctx {
        Matcher m;
        JsonObject body = new JsonObject();
        Map<String, String> q = new HashMap<>();
        Map<String, Object> user;
        int uid() { return ((Number) user.get("user_id")).intValue(); }
        String role() { return (String) user.get("role_name"); }
        int id() { return Integer.parseInt(m.group(1)); }
    }

    static final List<Route> routes = new ArrayList<>();

    /** roles: null = public, "*" = any logged-in user, otherwise comma-separated role names. */
    static void r(String method, String path, String roles, H h) {
        routes.add(new Route(method, Pattern.compile(path), roles == null ? null : new HashSet<>(Arrays.asList(roles.split(","))), h));
    }

    static final String PSQL = "SELECT p.product_id, p.name, p.product_key, p.base_price, p.quality_grade, p.stock_qty, "
            + "(p.stock_qty>0) AS available, p.category_id, c.name AS category, u.user_id AS supplier_id, u.full_name AS supplier "
            + "FROM products p JOIN categories c ON c.category_id=p.category_id JOIN users u ON u.user_id=p.supplier_id "
            + "WHERE p.is_active=1 AND u.status='ACTIVE'";

    static void defineRoutes() {
        // --- FR-1: users and access ---
        r("POST", "/api/register", null, x -> {
            String name = s(x.body, "name"), email = s(x.body, "email"), pw = s(x.body, "password"), role = s(x.body, "role");
            if (name == null || email == null || pw == null || pw.length() < 6 || !email.matches("\\S+@\\S+\\.\\S+"))
                throw new ApiErr(400, "Name, a valid email and a password of 6+ characters are required");
            role = role == null ? "" : role.toUpperCase();
            if (!List.of("SUPPLIER", "MERCHANT", "CONSUMER").contains(role)) throw new ApiErr(400, "Role must be Supplier, Merchant or Consumer");
            try (Connection c = conn()) {
                if (one(c, "SELECT 1 FROM users WHERE email=?", email.toLowerCase()) != null) throw new ApiErr(409, "This email is already registered");
                long id = insert(c, "INSERT INTO users(role_id,full_name,email,password_hash) VALUES((SELECT role_id FROM roles WHERE role_name=?),?,?,?)",
                        role, name, email.toLowerCase(), Auth.hash(pw));
                return new Created(Map.of("userId", id));
            }
        });
        r("POST", "/api/login", null, x -> {
            String email = s(x.body, "email"), pw = s(x.body, "password");
            if (email == null || pw == null) throw new ApiErr(400, "Email and password are required");
            try (Connection c = conn()) {
                Map<String, Object> u = one(c, "SELECT u.*, r.role_name FROM users u JOIN roles r USING(role_id) WHERE u.email=?", email.toLowerCase());
                if (u == null || !Auth.check(pw, (String) u.get("password_hash"))) throw new ApiErr(401, "Email or password is wrong");
                if (!"ACTIVE".equals(u.get("status"))) throw new ApiErr(403, "This account is " + String.valueOf(u.get("status")).toLowerCase());
                String token = UUID.randomUUID().toString();
                sessions.put(token, ((Number) u.get("user_id")).intValue());
                return Map.of("token", token, "userId", u.get("user_id"), "name", u.get("full_name"), "role", u.get("role_name"));
            }
        });
        r("GET", "/api/health", null, x -> Map.of("ok", true));
        r("POST", "/api/logout", "*", x -> Map.of("ok", true));

        // --- FR-2: catalog and comparison ---
        r("GET", "/api/categories", "*", x -> { try (Connection c = conn()) { return rows(c, "SELECT * FROM categories ORDER BY name"); } });
        r("GET", "/api/products", "*", x -> {
            StringBuilder q = new StringBuilder(PSQL);
            List<Object> a = new ArrayList<>();
            if (x.q.containsKey("category")) { q.append(" AND p.category_id=?"); a.add(Integer.valueOf(x.q.get("category"))); }
            if (x.q.containsKey("q")) { q.append(" AND p.name LIKE ?"); a.add("%" + x.q.get("q") + "%"); }
            if (x.q.containsKey("supplier")) { q.append(" AND p.supplier_id=?"); a.add(Integer.valueOf(x.q.get("supplier"))); }
            if (x.q.containsKey("quality")) { q.append(" AND p.quality_grade=?"); a.add(x.q.get("quality")); }
            if (x.q.containsKey("max")) { q.append(" AND p.base_price<=?"); a.add(new java.math.BigDecimal(x.q.get("max"))); }
            if (x.role().equals("SUPPLIER")) { q.append(" AND p.supplier_id=?"); a.add(x.uid()); }
            try (Connection c = conn()) { return withTiers(c, rows(c, q + " ORDER BY p.name", a.toArray())); }
        });
        r("GET", "/api/products/compare", "MERCHANT", x -> {
            if (!x.q.containsKey("key")) throw new ApiErr(400, "'key' is required");
            try (Connection c = conn()) {
                return withTiers(c, rows(c, PSQL + " AND p.product_key=? AND p.stock_qty>0 ORDER BY p.base_price", x.q.get("key").toLowerCase()));
            }
        });
        r("POST", "/api/pricing/quote", "*", x -> { try (Connection c = conn()) { return quote(c, n(x.body, "productId"), n(x.body, "qty")); } });

        // --- Supplier product management ---
        r("GET", "/api/supplier/products", "SUPPLIER", x -> {
            try (Connection c = conn()) { return withTiers(c, rows(c, PSQL + " AND p.supplier_id=? ORDER BY p.name", x.uid())); }
        });
        r("POST", "/api/supplier/products", "SUPPLIER", x -> { try (Connection c = conn()) { return new Created(saveProduct(c, x.uid(), null, x.body)); } });
        r("PUT", "/api/supplier/products/(\\d+)", "SUPPLIER", x -> { try (Connection c = conn()) { return saveProduct(c, x.uid(), x.id(), x.body); } });
        r("DELETE", "/api/supplier/products/(\\d+)", "SUPPLIER", x -> {
            try (Connection c = conn()) { // soft delete keeps order history valid
                if (exec(c, "UPDATE products SET is_active=0 WHERE product_id=? AND supplier_id=?", x.id(), x.uid()) == 0) throw new ApiErr(404, "Product not found");
                return Map.of("deleted", true);
            }
        });

        // --- FR-4: orders ---
        r("POST", "/api/orders", "MERCHANT,CONSUMER", x -> {
            if (!x.body.has("items") || !x.body.get("items").isJsonArray()) throw new ApiErr(400, "'items' is required");
            try (Connection c = conn()) { return new Created(Map.of("orderIds", placeOrder(c, x.uid(), s(x.body, "address"), x.body.getAsJsonArray("items")))); }
        });
        r("GET", "/api/orders", "*", x -> { try (Connection c = conn()) { return orders(c, x.uid(), x.role(), null); } });
        r("GET", "/api/orders/(\\d+)", "*", x -> {
            try (Connection c = conn()) {
                List<Map<String, Object>> l = orders(c, x.uid(), x.role(), x.id());
                if (l.isEmpty()) throw new ApiErr(404, "Order not found");
                return l.get(0);
            }
        });
        r("PUT", "/api/orders/(\\d+)/status", "SUPPLIER,MERCHANT,CONSUMER", x -> {
            try (Connection c = conn()) { changeStatus(c, x.uid(), x.role(), x.id(), s(x.body, "status")); return Map.of("ok", true); }
        });

        // --- Admin: users and categories ---
        r("GET", "/api/admin/users", "ADMIN", x -> {
            try (Connection c = conn()) { return rows(c, "SELECT u.user_id, u.full_name, u.email, r.role_name AS role, u.status, u.created_at FROM users u JOIN roles r USING(role_id) ORDER BY u.user_id"); }
        });
        r("PUT", "/api/admin/users/(\\d+)/status", "ADMIN", x -> {
            String st = s(x.body, "status");
            if (st == null || !List.of("ACTIVE", "SUSPENDED", "DELETED").contains(st.toUpperCase())) throw new ApiErr(400, "Status must be ACTIVE, SUSPENDED or DELETED");
            try (Connection c = conn()) {
                if (exec(c, "UPDATE users SET status=? WHERE user_id=? AND role_id<>(SELECT role_id FROM roles WHERE role_name='ADMIN')", st.toUpperCase(), x.id()) == 0)
                    throw new ApiErr(404, "User not found, or is an Admin");
                return Map.of("ok", true);
            }
        });
        r("POST", "/api/admin/categories", "ADMIN", x -> {
            String name = s(x.body, "name");
            if (name == null || name.isEmpty()) throw new ApiErr(400, "Category name is required");
            try (Connection c = conn()) {
                if (one(c, "SELECT 1 FROM categories WHERE name=?", name) != null) throw new ApiErr(409, "That category already exists");
                return new Created(Map.of("categoryId", insert(c, "INSERT INTO categories(name) VALUES(?)", name)));
            }
        });
        r("DELETE", "/api/admin/categories/(\\d+)", "ADMIN", x -> {
            try (Connection c = conn()) {
                if (one(c, "SELECT 1 FROM products WHERE category_id=? AND is_active=1 LIMIT 1", x.id()) != null)
                    throw new ApiErr(409, "This category has products, so it cannot be deleted");
                try { exec(c, "DELETE FROM categories WHERE category_id=?", x.id()); }
                catch (SQLIntegrityConstraintViolationException e) { throw new ApiErr(409, "This category is still referenced by older products"); }
                return Map.of("deleted", true);
            }
        });
    }

    // ---- Request pipeline: AuthFilter -> route -> JSON response ----
    static void auth(HttpExchange ex, Ctx x) throws Exception {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        Integer id = h != null && h.startsWith("Bearer ") ? sessions.get(h.substring(7)) : null;
        if (id == null) throw new ApiErr(401, "Log in first");
        try (Connection c = conn()) {
            x.user = one(c, "SELECT u.*, r.role_name FROM users u JOIN roles r USING(role_id) WHERE u.user_id=?", id);
        }
        if (x.user == null || !"ACTIVE".equals(x.user.get("status"))) throw new ApiErr(403, "This account is not active");
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (!path.startsWith("/api/")) { file(ex, path); return; }
            Ctx x = new Ctx();
            String qs = ex.getRequestURI().getRawQuery();
            if (qs != null) for (String kv : qs.split("&")) {
                String[] p = kv.split("=", 2);
                x.q.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
            }
            byte[] raw = ex.getRequestBody().readAllBytes();
            if (raw.length > 0) {
                try { x.body = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject(); }
                catch (Exception e) { throw new ApiErr(400, "The request body must be a JSON object"); }
            }
            Route hit = null;
            boolean pathMatched = false;
            for (Route r : routes) {
                Matcher m = r.p().matcher(path);
                if (m.matches()) {
                    pathMatched = true;
                    if (r.method().equals(ex.getRequestMethod())) { hit = r; x.m = m; break; }
                }
            }
            if (hit == null) throw new ApiErr(pathMatched ? 405 : 404, pathMatched ? "Method not allowed" : "Not found");
            if (hit.roles() != null) {
                auth(ex, x);
                if (!hit.roles().contains("*") && !hit.roles().contains(x.role())) throw new ApiErr(403, "Your role cannot do this");
            }
            Object out = hit.h().run(x);
            int code = 200;
            if (out instanceof Created cr) { code = 201; out = cr.body(); }
            send(ex, code, out);
        } catch (ApiErr e) {
            send(ex, e.code, Map.of("error", e.getMessage()));
        } catch (NumberFormatException e) {
            send(ex, 400, Map.of("error", "A number in the request is not valid"));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Map.of("error", "Server error"));
        }
    }

    static void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = G.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    static void file(HttpExchange ex, String path) throws IOException {
        Path root = Paths.get("public").toAbsolutePath().normalize();
        Path f = root.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
        if (!f.startsWith(root) || !Files.isRegularFile(f)) { send(ex, 404, Map.of("error", "Not found")); return; }
        String n = f.toString();
        String ct = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".css") ? "text/css" : n.endsWith(".js") ? "text/javascript" : n.endsWith(".png") ? "image/png" : n.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
        byte[] b = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    /** Admins are pre-provisioned (SRS 2.6): create one on first start. */
    static void bootstrapAdmin(Connection c) throws Exception {
        if (one(c, "SELECT 1 FROM users u JOIN roles r USING(role_id) WHERE r.role_name='ADMIN' LIMIT 1") == null) {
            String pw = env("ADMIN_PASSWORD", "admin1234");
            insert(c, "INSERT INTO users(role_id,full_name,email,password_hash) VALUES((SELECT role_id FROM roles WHERE role_name='ADMIN'),'Admin','admin@mawrid.sa',?)", Auth.hash(pw));
            System.out.println("Created admin@mawrid.sa (password from ADMIN_PASSWORD, default admin1234). Change it.");
        }
    }

    /** Creates the tables on a fresh hosted database (skips if they already exist). */
    static void initSchema(Connection c) throws Exception {
        if (!rows(c, "SHOW TABLES LIKE 'roles'").isEmpty()) return;
        for (String st : Files.readString(Paths.get("schema.sql")).split(";\\s*(\\r?\\n|$)")) {
            String t = st.trim(), u = t.toUpperCase();
            if (t.isEmpty() || u.startsWith("CREATE DATABASE") || u.startsWith("USE ")) continue;
            try (Statement s = c.createStatement()) { s.execute(t); }
        }
        System.out.println("Database tables created.");
    }

    /** On hosts the database can start after the app, so retry for a while. */
    static Connection waitDb() throws Exception {
        for (int i = 0; ; i++) {
            try { return conn(); }
            catch (SQLException e) {
                if (i >= 60) throw e;
                System.out.println("Waiting for the database (" + e.getMessage() + ")");
                Thread.sleep(3000);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try (Connection c = waitDb()) { initSchema(c); bootstrapAdmin(c); Seed.run(c); }
        defineRoutes();
        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/", Main::handle);
        s.setExecutor(Executors.newFixedThreadPool(16));
        s.start();
        System.out.println("Mawrid server running on http://localhost:" + port);
    }
}
