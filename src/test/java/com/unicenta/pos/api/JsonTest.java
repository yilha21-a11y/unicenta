package com.unicenta.pos.api;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class JsonTest {

    @Test
    public void ecritObjetsListesEtTypesSimples() {
        Map<String, Object> m = Json.obj("a", 1, "b", "x", "c", true, "d", null, "e", Arrays.asList(1.5, 2.0));
        assertEquals("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null,\"e\":[1.5,2]}", Json.write(m));
    }

    @Test
    public void echappeLesCaracteresSpeciaux() {
        String s = "guillemet\" antislash\\ ligne\n tab\t ctrl" + (char) 1 + " sep" + (char) 0x2028;
        assertEquals("\"guillemet\\\" antislash\\\\ ligne\\n tab\\t ctrl\\u0001 sep\\u2028\"", Json.write(s));
    }

    @Test
    public void conserveLesAccents() throws Exception {
        String s = "Entr" + (char) 0xE9 + "es";
        assertEquals(s, Json.parse(Json.write(s)));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void litUnObjetComplet() throws Exception {
        Object v = Json.parse(" { \"userId\" : \"42\", \"n\": -3, \"x\": 2.5e1, \"ok\": false, \"rien\": null,"
                + " \"l\": [1, \"deux\", {\"k\": \"\\u00e9\\n\"}] } ");
        Map<String, Object> m = (Map<String, Object>) v;
        assertEquals("42", m.get("userId"));
        assertEquals(-3L, m.get("n"));
        assertEquals(25.0, (Double) m.get("x"), 0.0);
        assertEquals(Boolean.FALSE, m.get("ok"));
        assertNull(m.get("rien"));
        assertTrue(m.containsKey("rien"));
        List<Object> l = (List<Object>) m.get("l");
        assertEquals(3, l.size());
        assertEquals("" + (char) 0xE9 + "\n", ((Map<String, Object>) l.get(2)).get("k"));
    }

    @Test
    public void refuseLeJsonInvalide() {
        String[] invalides = {"", "{", "{\"a\":}", "{\"a\":1,}", "[1 2]", "\"non termine", "{a:1}", "tru", "1 2",
            "{\"a\":\"" + (char) 1 + "\"}"};
        for (String s : invalides) {
            try {
                Json.parse(s);
                fail("Aurait du echouer : " + s);
            } catch (Json.ParseException expected) {
                // ok
            }
        }
    }

    @Test
    public void limiteLaProfondeur() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append('[');
        }
        for (int i = 0; i < 100; i++) {
            sb.append(']');
        }
        try {
            Json.parse(sb.toString());
            fail("Imbrication excessive acceptee");
        } catch (Json.ParseException expected) {
            assertTrue(expected.getMessage().contains("profonde"));
        }
    }
}
