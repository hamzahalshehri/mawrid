package mawrid;

import com.google.gson.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;

import static mawrid.Db.*;

class ApiErr extends RuntimeException {
    final int code;
    ApiErr(int code, String msg) { super(msg); this.code = code; }
}

/** Business logic tier: PricingEngine, InventoryService and OrderStateMachine in one place. */
public class Service {
    // ---- JSON helpers ----
    static String s(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : null;
    }
    static int n(JsonObject o, String k) {
        try { return o.get(k).getAsInt(); } catch (Exception e) { throw new ApiErr(400, "'" + k + "' must be a whole number"); }
    }
    static BigDecimal d(JsonObject o, String k) {
        try { return o.get(k).getAsBigDecimal(); } catch (Exception e) { throw new ApiErr(400, "'" + k + "' must be a number"); }
    }

    // ---- Pricing (FR-3.1, FR-3.2) ----
    static BigDecimal unit(Connection c, Map<String, Object> p, int qty) throws SQLException {
        Map<String, Object> t = one(c, "SELECT unit_price FROM price_tiers WHERE product_id=? AND min_qty<=? "
                + "AND (max_qty IS NULL OR max_qty>=?) ORDER BY min_qty DESC LIMIT 1", p.get("product_id"), qty, qty);
        return (BigDecimal) (t != null ? t.get("unit_price") : p.get("base_price"));
    }

    static Map<String, Object> quote(Connection c, int pid, int qty) throws SQLException {
        if (qty <= 0) throw new ApiErr(400, "Quantity must be at least 1");
        Map<String, Object> p = one(c, "SELECT * FROM products WHERE product_id=? AND is_active=1", pid);
        if (p == null) throw new ApiErr(404, "Product not found");
        BigDecimal u = unit(c, p, qty);
        return Map.of("productId", pid, "qty", qty, "unitPrice", u, "total", u.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP));
    }

    /** Tiers must not overlap; only the last tier may be open-ended. Returns parsed tiers sorted by min. */
    static List<Object[]> parseTiers(JsonArray arr) {
        List<Object[]> t = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            Integer max = o.has("maxQty") && !o.get("maxQty").isJsonNull() ? n(o, "maxQty") : null;
            t.add(new Object[]{n(o, "minQty"), max, d(o, "unitPrice")});
        }
        t.sort(Comparator.comparingInt(a -> (Integer) a[0]));
        for (int i = 0; i < t.size(); i++) {
            int min = (Integer) t.get(i)[0];
            Integer max = (Integer) t.get(i)[1];
            if (min < 1 || ((BigDecimal) t.get(i)[2]).signum() <= 0) throw new ApiErr(400, "Tier quantities and prices must be positive");
            if (max != null && max < min) throw new ApiErr(400, "A tier ends before it starts");
            if (i < t.size() - 1 && (max == null || max >= (Integer) t.get(i + 1)[0]))
                throw new ApiErr(400, "Tiers overlap, or an open-ended tier is not the last one");
        }
        return t;
    }

    static List<Map<String, Object>> withTiers(Connection c, List<Map<String, Object>> l) throws SQLException {
        for (Map<String, Object> p : l)
            p.put("tiers", rows(c, "SELECT min_qty, max_qty, unit_price FROM price_tiers WHERE product_id=? ORDER BY min_qty", p.get("product_id")));
        return l;
    }

    // ---- Supplier products (FR-2.1) ----
    static Object saveProduct(Connection c, int supplierId, Integer productId, JsonObject o) throws Exception {
        String name = s(o, "name"), key = s(o, "productKey"), grade = s(o, "qualityGrade");
        if (name == null || name.isEmpty()) throw new ApiErr(400, "Product name is required");
        if (key == null || key.isEmpty()) key = name;
        if (grade == null || !grade.matches("[ABC]")) throw new ApiErr(400, "Quality grade must be A, B or C");
        BigDecimal base = d(o, "basePrice");
        int stock = n(o, "stockQty"), cat = n(o, "categoryId");
        if (base.signum() <= 0 || stock < 0) throw new ApiErr(400, "Price must be above 0 and stock 0 or more");
        List<Object[]> tiers = parseTiers(o.has("tiers") ? o.getAsJsonArray("tiers") : new JsonArray());
        c.setAutoCommit(false);
        try {
            long id;
            if (productId == null) {
                id = insert(c, "INSERT INTO products(supplier_id,category_id,name,product_key,base_price,quality_grade,stock_qty) VALUES(?,?,?,?,?,?,?)",
                        supplierId, cat, name, key.toLowerCase(), base, grade, stock);
            } else {
                int changed = exec(c, "UPDATE products SET category_id=?,name=?,product_key=?,base_price=?,quality_grade=?,stock_qty=? WHERE product_id=? AND supplier_id=? AND is_active=1",
                        cat, name, key.toLowerCase(), base, grade, stock, productId, supplierId);
                if (changed == 0) throw new ApiErr(404, "Product not found");
                id = productId;
                exec(c, "DELETE FROM price_tiers WHERE product_id=?", id);
            }
            for (Object[] t : tiers) exec(c, "INSERT INTO price_tiers(product_id,min_qty,max_qty,unit_price) VALUES(?,?,?,?)", id, t[0], t[1], t[2]);
            c.commit();
            return Map.of("productId", id);
        } catch (Exception e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
    }

    // ---- Atomic order placement (FR-3.3, FR-4.1, UC-05) ----
    static List<Long> placeOrder(Connection c, int buyerId, String address, JsonArray items) throws Exception {
        if (address == null || address.length() < 5) throw new ApiErr(400, "Delivery address is required");
        TreeMap<Integer, Integer> need = new TreeMap<>(); // sorted by product id: consistent lock order, no deadlocks
        for (JsonElement e : items) {
            JsonObject o = e.getAsJsonObject();
            int q = n(o, "qty");
            if (q <= 0) throw new ApiErr(400, "Quantity must be at least 1");
            need.merge(n(o, "productId"), q, Integer::sum);
        }
        if (need.isEmpty()) throw new ApiErr(400, "The cart is empty");
        c.setAutoCommit(false);
        try {
            Map<Integer, List<Object[]>> bySupplier = new LinkedHashMap<>();
            for (Map.Entry<Integer, Integer> e : need.entrySet()) {
                Map<String, Object> p = one(c, "SELECT * FROM products WHERE product_id=? AND is_active=1 FOR UPDATE", e.getKey());
                if (p == null) throw new ApiErr(404, "Product " + e.getKey() + " not found");
                int stock = ((Number) p.get("stock_qty")).intValue();
                if (stock < e.getValue()) throw new ApiErr(409, "Out of stock: " + p.get("name") + " (only " + stock + " left)");
                BigDecimal u = unit(c, p, e.getValue());
                exec(c, "UPDATE products SET stock_qty = stock_qty - ? WHERE product_id=?", e.getValue(), e.getKey());
                bySupplier.computeIfAbsent(((Number) p.get("supplier_id")).intValue(), k -> new ArrayList<>())
                        .add(new Object[]{e.getKey(), e.getValue(), u});
            }
            List<Long> ids = new ArrayList<>();
            for (Map.Entry<Integer, List<Object[]>> s : bySupplier.entrySet()) {
                BigDecimal total = BigDecimal.ZERO;
                for (Object[] l : s.getValue())
                    total = total.add(((BigDecimal) l[2]).multiply(BigDecimal.valueOf((Integer) l[1])));
                total = total.setScale(2, RoundingMode.HALF_UP);
                long oid = insert(c, "INSERT INTO orders(buyer_id,supplier_id,status,delivery_address,total_amount) VALUES(?,?,'PLACED',?,?)",
                        buyerId, s.getKey(), address, total);
                for (Object[] l : s.getValue()) {
                    BigDecimal line = ((BigDecimal) l[2]).multiply(BigDecimal.valueOf((Integer) l[1])).setScale(2, RoundingMode.HALF_UP);
                    exec(c, "INSERT INTO order_items(order_id,product_id,quantity,unit_price_locked,line_total) VALUES(?,?,?,?,?)", oid, l[0], l[1], l[2], line);
                }
                exec(c, "INSERT INTO order_status_log(order_id,from_status,to_status,changed_by) VALUES(?,NULL,'PLACED',?)", oid, buyerId);
                ids.add(oid);
            }
            c.commit();
            return ids;
        } catch (Exception e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
    }

    // ---- Order state machine (FR-4.2, FR-4.3, UC-06, UC-08) ----
    // B = buyer may do it, S = supplier may do it
    static final Map<String, Map<String, String>> TR = Map.of(
            "PLACED", Map.of("CONFIRMED", "S", "CANCELLED", "BS"),
            "CONFIRMED", Map.of("PROCESSING", "S", "CANCELLED", "B"),
            "PROCESSING", Map.of("SHIPPED", "S"),
            "SHIPPED", Map.of("DELIVERED", "S"));

    static void changeStatus(Connection c, int uid, String role, int orderId, String to) throws Exception {
        if (to == null) throw new ApiErr(400, "'status' is required");
        to = to.toUpperCase();
        c.setAutoCommit(false);
        try {
            Map<String, Object> o = one(c, "SELECT * FROM orders WHERE order_id=? FOR UPDATE", orderId);
            if (o == null) throw new ApiErr(404, "Order not found");
            String who = "";
            if (role.equals("SUPPLIER") && ((Number) o.get("supplier_id")).intValue() == uid) who = "S";
            else if (!role.equals("SUPPLIER") && ((Number) o.get("buyer_id")).intValue() == uid) who = "B";
            if (who.isEmpty()) throw new ApiErr(404, "Order not found");
            String from = (String) o.get("status"), rule = TR.getOrDefault(from, Map.of()).get(to);
            if (rule == null || !rule.contains(who)) throw new ApiErr(409, "Not allowed: " + from + " to " + to);
            if (to.equals("CANCELLED"))
                for (Map<String, Object> i : rows(c, "SELECT product_id, quantity FROM order_items WHERE order_id=?", orderId))
                    exec(c, "UPDATE products SET stock_qty = stock_qty + ? WHERE product_id=?", i.get("quantity"), i.get("product_id"));
            exec(c, "UPDATE orders SET status=? WHERE order_id=?", to, orderId);
            exec(c, "INSERT INTO order_status_log(order_id,from_status,to_status,changed_by) VALUES(?,?,?,?)", orderId, from, to, uid);
            c.commit();
        } catch (Exception e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
    }

    // ---- Order reads (UC-07) ----
    static List<Map<String, Object>> orders(Connection c, int uid, String role, Integer onlyId) throws SQLException {
        String sql = "SELECT o.*, b.full_name AS buyer, s.full_name AS supplier FROM orders o "
                + "JOIN users b ON b.user_id=o.buyer_id JOIN users s ON s.user_id=o.supplier_id WHERE 1=1";
        List<Object> a = new ArrayList<>();
        if (role.equals("SUPPLIER")) { sql += " AND o.supplier_id=?"; a.add(uid); }
        else if (!role.equals("ADMIN")) { sql += " AND o.buyer_id=?"; a.add(uid); }
        if (onlyId != null) { sql += " AND o.order_id=?"; a.add(onlyId); }
        List<Map<String, Object>> l = rows(c, sql + " ORDER BY o.order_id DESC", a.toArray());
        for (Map<String, Object> o : l) {
            o.put("items", rows(c, "SELECT oi.*, p.name FROM order_items oi JOIN products p USING(product_id) WHERE oi.order_id=?", o.get("order_id")));
            o.put("history", rows(c, "SELECT l.from_status, l.to_status, u.full_name AS actor, l.changed_at FROM order_status_log l "
                    + "JOIN users u ON u.user_id=l.changed_by WHERE l.order_id=? ORDER BY l.changed_at, l.log_id", o.get("order_id")));
        }
        return l;
    }
}
