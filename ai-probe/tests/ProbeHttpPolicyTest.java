package com.shatteredpixel.shatteredpixeldungeon.desktop;

public class ProbeHttpPolicyTest {
    public static void main(String[] args) {
        ProbeHttpPolicy.validate("127.0.0.1:18765", null);
        ProbeHttpPolicy.validate("127.0.0.1:18765", "http://127.0.0.1:18765");
        String[][] invalid = {{null, null}, {"attacker.invalid:18765", null},
                {"127.0.0.1:18765.attacker.invalid", null}, {"127.0.0.1", null},
                {"127.0.0.1:18765", "null"}, {"127.0.0.1:18765", "https://attacker.invalid"}};
        for (String[] pair : invalid) {
            try {
                ProbeHttpPolicy.validate(pair[0], pair[1]);
            } catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("Accepted foreign host/origin");
        }
        System.out.println("HTTP policy: 8 cases passed");
    }
}
