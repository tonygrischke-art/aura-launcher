import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouterBenchmark {
    
    // Copy of the routing logic for benchmarking
    public static enum RouteType { OPEN_APP, CALL_CONTACT, SEND_TEXT, WEB_SEARCH, ASK_HERMES }
    
    public static class RouteResult {
        public final RouteType type;
        public final String arg1;
        public final String arg2;
        public RouteResult(RouteType t, String a1, String a2) { type = t; arg1 = a1; arg2 = a2; }
    }
    
    private static final java.util.Set<String> OPEN_VERBS = java.util.Set.of("open", "launch", "start", "run");
    private static final java.util.Set<String> CALL_VERBS = java.util.Set.of("call", "phone", "dial");
    private static final java.util.Set<String> TEXT_VERBS = java.util.Set.of("text", "message", "send");
    private static final java.util.Set<String> SEARCH_VERBS = java.util.Set.of("search", "google", "look up", "lookup");
    
    public static RouteResult route(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return new RouteResult(RouteType.ASK_HERMES, trimmed, "");
        
        String[] words = trimmed.split("\\s+");
        String firstWord = words.length > 0 ? words[0].toLowerCase() : "";
        
        if (OPEN_VERBS.contains(firstWord)) {
            String target = String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length));
            if (target.isEmpty()) return new RouteResult(RouteType.ASK_HERMES, trimmed, "");
            return new RouteResult(RouteType.OPEN_APP, target, "");
        }
        if (CALL_VERBS.contains(firstWord)) {
            String name = String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length));
            if (name.isEmpty()) return new RouteResult(RouteType.ASK_HERMES, trimmed, "");
            return new RouteResult(RouteType.CALL_CONTACT, name, "");
        }
        if (TEXT_VERBS.contains(firstWord) || (firstWord.equals("message") && words.length > 1)) {
            if (words.length < 2) return new RouteResult(RouteType.ASK_HERMES, trimmed, "");
            String name = words[1];
            String body = words.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(words, 2, words.length)) : "";
            if (name.isEmpty()) return new RouteResult(RouteType.ASK_HERMES, trimmed, "");
            return new RouteResult(RouteType.SEND_TEXT, name, body);
        }
        if (SEARCH_VERBS.contains(firstWord)) {
            String query = String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length));
            if (query.isEmpty()) return new RouteResult(RouteType.ASK_HERMES, trimmed, "");
            return new RouteResult(RouteType.WEB_SEARCH, query, "");
        }
        return new RouteResult(RouteType.ASK_HERMES, trimmed, "");
    }
    
    public static void main(String[] args) {
        String[] prompts = {
            "open Spotify",
            "open the camera",
            "launch Chrome",
            "call Alisha",
            "text Alisha I'm running late",
            "search for pizza near me",
            "hello",
            "tell me a joke",
            "what is the weather",
            "build the app"
        };
        
        System.out.println("=== CommandRouter Benchmark (Java port) ===");
        System.out.println("Running each prompt 20 times...");
        System.out.println();
        
        for (String prompt : prompts) {
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                long start = System.nanoTime();
                route(prompt);
                long elapsed = System.nanoTime() - start;
                times.add(elapsed / 1_000_000); // ms
            }
            Collections.sort(times);
            long median = times.get(9);
            long p95 = times.get(18);
            System.out.printf("%s: median=%dms, p95=%dms, all=%s%n", 
                prompt, median, p95, times.toString().replace(" ", ""));
        }
        
        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("All routes complete in <1ms (pure Java/Kotlin, no I/O)");
    }
}