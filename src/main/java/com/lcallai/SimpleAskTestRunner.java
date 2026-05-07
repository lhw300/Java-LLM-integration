package com.lcallai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * End-to-end test for ChatSession.askSimple()
 *
 * Knowledge base (knowledge.txt):
 *   NYIT Vancouver:
 *   - 735 secure software engineering is held in Room 1812 on Thursdays.
 *   NYIT NewYork:
 *   - 735 secure software engineering is held in Room 1919 on Friday.
 *   Curriculum Update:
 *   - For students admitted in Fall 2024 or later (24fall+), course 775 is NOT a mandatory course.
 *
 * Run locally:
 *   mvn compile exec:java -Dexec.mainClass="com.lcallai.SimpleAskTestRunner" -Dexec.args="e:/ai"
 */
public class SimpleAskTestRunner {

    private static final Logger logger = LogManager.getLogger(SimpleAskTestRunner.class);

    private static int totalPass = 0;
    private static int totalFail = 0;

    // =========================================================================
    // Test case definition
    // =========================================================================
    static class SimpleCase {
        String question;
        String[] answerKeywords; // All keywords must appear in the answer to PASS

        SimpleCase(String question, String[] answerKeywords) {
            this.question = question;
            this.answerKeywords = answerKeywords;
        }
    }

    static final SimpleCase[] cases = {
            // Case 1: Room lookup - Vancouver campus


            new SimpleCase(
                    "i want to know the room number for seven three five in vancouver",
                    new String[]{"1812"}
            ),
            /*
              new SimpleCase(
                    "What room is 735 held in at NYIT Vancouver?",
                    new String[]{"1812"}
            ),
            // Case 2: Room and day lookup - New York campus
            new SimpleCase(
                    "When does 735 meet in New York?",
                    new String[]{"Friday"}
            ),

            // Case 3: Curriculum rule - 775 not mandatory for Fall 2024+
            new SimpleCase(
                    "I was admitted in Fall 2024. Do I need to take course 775?",
                    new String[]{"not", "mandatory"}
            ),
            // Case 4: Day lookup - Vancouver campus
            new SimpleCase(
                    "What day is 735 in Vancouver?",
                    new String[]{"Thursday"}
            ),
            // Case 5: Multi-turn - follow-up with no subject; expects history to carry Vancouver context
            new SimpleCase(
                    "What about the room number?",
                    new String[]{"1812"}
            ),
        */

    };

    // =========================================================================
    // Entry point
    // =========================================================================
    public static void main(String[] args) {

        // Resolve config path: CLI arg > env var > default
        String configPath = "e:\\ai";
        if (args.length > 0) {
            configPath = args[0];
        } else if (System.getenv("AI_CONFIG_PATH") != null) {
            configPath = System.getenv("AI_CONFIG_PATH");
        }
        logger.debug("Config path: " + configPath);

        SessionManager.init(configPath);
        SessionManager.warmUp();
        // Single session shared across all cases to validate multi-turn history
        ChatSession session = SessionManager.getSession("simple_test_001");

        logger.debug("\n========== askSimple Test Start ==========\n");
        long totalStart = System.currentTimeMillis();

        for (int i = 0; i < cases.length; i++) {
            SimpleCase sc = cases[i];
            logger.debug("--------------------------------------------------");
            logger.debug("Q[" + (i + 1) + "]: " + sc.question);

            long start = System.currentTimeMillis();
            ChatAnswer answer = session.ask(sc.question);
            long elapsed = System.currentTimeMillis() - start;

            String ans = (answer != null && answer.answer != null) ? answer.answer : "";
            logger.debug("A[" + (i + 1) + "]: " + ans);
            logger.debug("Elapsed: " + elapsed + " ms | code=" + (answer != null ? answer.code : -999));

            // Keyword validation
            boolean pass = checkKeywords(sc.question, ans, sc.answerKeywords);
            if (pass) totalPass++; else totalFail++;
            logger.debug(pass ? "✅ PASS" : "❌ FAIL");
        }

        // =========================================================================
        // Summary
        // =========================================================================
        int total = totalPass + totalFail;
        double passRate = total == 0 ? 0 : (double) totalPass / total * 100;
        long totalElapsed = System.currentTimeMillis() - totalStart;

        logger.debug("\n========== askSimple Test Summary ==========");
        logger.debug(String.format("Total: %d  ✅Pass: %d  ❌Fail: %d", total, totalPass, totalFail));
        logger.debug(String.format("Pass rate: %.1f%%  Total elapsed: %d ms", passRate, totalElapsed));
        logger.debug("============================================");

        System.exit(passRate >= 80 ? 0 : 1);
    }

    // =========================================================================
    // Utility: check all keywords exist in the answer (case-insensitive)
    // =========================================================================
    private static boolean checkKeywords(String question, String answer, String[] keywords) {
        if (keywords == null || keywords.length == 0) return true;
        String lowerAns = answer.toLowerCase();
        for (String kw : keywords) {
            if (!lowerAns.contains(kw.toLowerCase())) {
                logger.debug("  Missing keyword: [" + kw + "] | Q=" + question);
                return false;
            }
        }
        return true;
    }
}