import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** Checks the isolated API run's persisted state after every service has stopped. */
class VerifyApiDatabases {
    private static int checks;

    private static Connection database(Path root, String name) throws Exception {
        return DriverManager.getConnection("jdbc:h2:file:" + root.resolve(name).toString().replace('\\', '/'), "sa", "");
    }

    private static long number(Connection db, String sql) throws Exception {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new AssertionError("No result: " + sql);
            return rows.getLong(1);
        }
    }

    private static String value(Connection db, String sql) throws Exception {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new AssertionError("No result: " + sql);
            return rows.getString(1);
        }
    }

    private static void same(String label, String first, String second) {
        if (first == null || !first.equals(second)) throw new AssertionError(label + ": values differ");
        System.out.println("PASS DB " + label);
        checks++;
    }

    private static void expect(Connection db, String label, String sql, long expected) throws Exception {
        long actual = number(db, sql);
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        System.out.println("PASS DB " + label + " = " + actual);
        checks++;
    }

    private static void atLeast(Connection db, String label, String sql, long minimum) throws Exception {
        long actual = number(db, sql);
        if (actual < minimum) throw new AssertionError(label + ": expected at least " + minimum + ", got " + actual);
        System.out.println("PASS DB " + label + " = " + actual);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected isolated data directory");
        Path root = Path.of(args[0]);
        Map<String, Connection> databases = new HashMap<>();
        try {
            for (String name : new String[]{"auth", "catalog", "cart", "classroom", "feed", "studio", "quiz", "delivery"})
                databases.put(name, database(root, name));
            Connection auth = databases.get("auth"), catalog = databases.get("catalog"), cart = databases.get("cart");
            Connection classroom = databases.get("classroom"), feed = databases.get("feed"), studio = databases.get("studio");
            Connection quiz = databases.get("quiz"), delivery = databases.get("delivery");

            expect(auth, "registered accounts", "SELECT COUNT(*) FROM APP_USERS", 4);
            expect(auth, "account creation activity", "SELECT COUNT(*) FROM USER_ACTIVITIES WHERE ACTION='Created account'", 4);
            expect(auth, "sign-in activity", "SELECT COUNT(*) FROM USER_ACTIVITIES WHERE ACTION='Signed in'", 2);
            expect(auth, "seller activity", "SELECT COUNT(*) FROM USER_ACTIVITIES WHERE ACTION='Enabled seller workspace'", 1);
            expect(auth, "explicit activity", "SELECT COUNT(*) FROM USER_ACTIVITIES WHERE ACTION='Listed a product'", 1);
            expect(auth, "photo activity", "SELECT COUNT(*) FROM USER_ACTIVITIES WHERE ACTION='Updated profile photo'", 1);
            expect(auth, "activity rows linked to accounts", "SELECT COUNT(*) FROM USER_ACTIVITIES a LEFT JOIN APP_USERS u ON a.USER_ID=u.ID WHERE u.ID IS NULL", 0);
            expect(catalog, "catalog item after checkout and cancel", "SELECT COUNT(*) FROM PRODUCTS WHERE NAME LIKE 'Updated item %' AND STOCK=4", 1);
            expect(cart, "paid order", "SELECT COUNT(*) FROM SHOP_ORDERS WHERE TOTAL=10 AND DELIVERY_PUBLISHED=TRUE AND INVOICE_NUMBER IS NOT NULL", 1);
            expect(cart, "completed reservation", "SELECT COUNT(*) FROM PAYMENT_RESERVATIONS WHERE STATE='COMPLETED' AND TOTAL=10", 1);
            expect(cart, "cancelled reservation", "SELECT COUNT(*) FROM PAYMENT_RESERVATIONS WHERE STATE='CANCELLED' AND TOTAL=10", 1);
            expect(cart, "cancelled checkout retains cart item", "SELECT COUNT(*) FROM CART_ITEMS", 1);
            expect(classroom, "deleted modules", "SELECT COUNT(*) FROM LEARNING_MODULE", 0);
            expect(classroom, "deleted topics", "SELECT COUNT(*) FROM LEARNING_TOPIC", 0);
            expect(classroom, "no orphan comments", "SELECT COUNT(*) FROM TOPIC_COMMENT", 0);
            expect(classroom, "no orphan likes", "SELECT COUNT(*) FROM TOPIC_LIKE", 0);
            expect(classroom, "no orphan student notes", "SELECT COUNT(*) FROM TOPIC_STUDENT_NOTE", 0);
            expect(classroom, "no orphan topic resources", "SELECT COUNT(*) FROM MATERIAL WHERE TOPIC_ID IS NOT NULL", 0);
            expect(classroom, "standalone materials remain", "SELECT COUNT(*) FROM MATERIAL WHERE TOPIC_ID IS NULL", 2);
            try (var files = Files.list(root)) {
                long orphanQuizFiles = files.filter(path -> path.getFileName().toString().startsWith("teacher-topic-")
                        && path.getFileName().toString().endsWith(".json")).count();
                if (orphanQuizFiles != 0) throw new AssertionError("deleted topic left " + orphanQuizFiles + " quiz files");
                System.out.println("PASS DB no orphan topic quiz files = 0");
                checks++;
            }
            expect(feed, "published posts and imported lesson", "SELECT COUNT(*) FROM POST", 4);
            expect(feed, "single imported lesson", "SELECT COUNT(*) FROM POST WHERE LEGACY_TOPIC_ID IS NOT NULL", 1);
            expect(feed, "single studio publication", "SELECT COUNT(*) FROM POST WHERE SOURCE_DRAFT_ID IS NOT NULL", 1);
            expect(feed, "feed comment", "SELECT COUNT(*) FROM FEED_COMMENT", 1);
            expect(feed, "feed private note", "SELECT COUNT(*) FROM FEED_NOTE", 1);
            expect(studio, "published studio draft", "SELECT COUNT(*) FROM STUDIO_DRAFT WHERE PUBLISHED_AT IS NOT NULL", 1);
            expect(quiz, "quiz definition", "SELECT COUNT(*) FROM QUIZ", 1);
            expect(quiz, "quiz question", "SELECT COUNT(*) FROM QUIZ_QUESTION", 1);
            expect(quiz, "completed quiz attempt", "SELECT COUNT(*) FROM QUIZ_ATTEMPT WHERE FINISHED_AT IS NOT NULL", 1);
            expect(quiz, "warning on open attempt", "SELECT COUNT(*) FROM QUIZ_ATTEMPT WHERE FINISHED_AT IS NULL AND WARNINGS=1", 1);
            expect(delivery, "acknowledged delivery", "SELECT COUNT(*) FROM DELIVERY_ORDER WHERE ACKNOWLEDGED=TRUE AND PAYMENT_STATUS='Payment successful'", 1);
            atLeast(delivery, "delivery history events", "SELECT COUNT(*) FROM STATUS_EVENT", 1);
            atLeast(delivery, "delivery history links", "SELECT COUNT(*) FROM DELIVERY_ORDER_HISTORY", 1);
            expect(delivery, "timestamped delivery history", "SELECT COUNT(*) FROM STATUS_EVENT WHERE CHANGED_AT IS NULL", 0);
            long orderId = number(cart, "SELECT ID FROM SHOP_ORDERS");
            expect(delivery, "cart order linked to delivery", "SELECT COUNT(*) FROM DELIVERY_ORDER WHERE ORDER_ID=" + orderId, 1);
            same("order buyer matches delivery buyer", value(cart, "SELECT USER_ID FROM SHOP_ORDERS WHERE ID=" + orderId),
                    value(delivery, "SELECT USER_ID FROM DELIVERY_ORDER WHERE ORDER_ID=" + orderId));
            same("order item snapshot reaches delivery unchanged", value(cart, "SELECT ITEMS_JSON FROM SHOP_ORDERS WHERE ID=" + orderId),
                    value(delivery, "SELECT ITEMS_JSON FROM DELIVERY_ORDER WHERE ORDER_ID=" + orderId));
            long draftId = number(studio, "SELECT ID FROM STUDIO_DRAFT");
            expect(feed, "studio draft linked to feed", "SELECT COUNT(*) FROM POST WHERE SOURCE_DRAFT_ID=" + draftId, 1);
            System.out.println("DB SUMMARY: " + checks + "/" + checks + " passed");
        } finally {
            for (Connection db : databases.values()) db.close();
        }
    }
}
