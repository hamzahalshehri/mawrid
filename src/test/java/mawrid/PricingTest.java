package mawrid;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PricingTest {
    static JsonArray tiers(String json) { return JsonParser.parseString(json).getAsJsonArray(); }

    @Test void acceptsNonOverlappingTiers() {
        assertEquals(3, Service.parseTiers(tiers("[{minQty:1,maxQty:49,unitPrice:10},{minQty:50,maxQty:199,unitPrice:9},{minQty:200,unitPrice:8}]")).size());
    }
    @Test void rejectsOverlap() {
        assertThrows(ApiErr.class, () -> Service.parseTiers(tiers("[{minQty:1,maxQty:50,unitPrice:10},{minQty:50,maxQty:99,unitPrice:9}]")));
    }
    @Test void rejectsOpenTierThatIsNotLast() {
        assertThrows(ApiErr.class, () -> Service.parseTiers(tiers("[{minQty:1,unitPrice:10},{minQty:50,maxQty:99,unitPrice:9}]")));
    }
    @Test void rejectsTierEndingBeforeItStarts() {
        assertThrows(ApiErr.class, () -> Service.parseTiers(tiers("[{minQty:10,maxQty:5,unitPrice:10}]")));
    }
    @Test void legalTransitionsOnly() {
        assertTrue(Service.TR.get("PLACED").get("CANCELLED").contains("B"));
        assertNull(Service.TR.get("PROCESSING").get("CANCELLED"));
        assertNull(Service.TR.get("DELIVERED"));
    }
}
