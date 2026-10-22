import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * How you write, in a few lines, so a suggested reply sounds like you: style/style.json, {text, from, at}. You can
 * write it yourself in the elf's settings, or have Claude learn it from your own recent messages, then edit it.
 * Every suggested reply follows it.
 */
public class Style {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path FILE = Config.CONFIG_DIR.resolve("style").resolve("style.json");
    static final int MAX = 4_000;

    /** The note, or "" when there's none yet. */
    static String text() {
        try {
            return Files.exists(FILE) ? JSON.readTree(FILE.toFile()).path("text").asText("").strip() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Keeps the note: {text, from (what it was learnt from, or "you"), at}. */
    static ObjectNode save(String text, String from) throws IOException {
        String t = text == null ? "" : text.strip();
        if (t.length() > MAX) throw new IllegalArgumentException("That's longer than a writing style needs to be");
        ObjectNode n = JSON.createObjectNode().put("text", t).put("from", from).put("at", Instant.now().toString());
        Files.createDirectories(FILE.getParent());
        Path tmp = FILE.resolveSibling("style.json.new");
        Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n));
        Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return n;
    }

    /**
     * Has Claude describe how you write from {@code samples} (your own messages, others' words taken out), keeps it
     * and answers {text, from, at, cost}. {@code what}: "sent emails" or "Zulip messages".
     */
    static ObjectNode learn(List<String> samples, String what) throws Exception {
        if (samples.size() < 3) throw new IllegalStateException("Too few of your " + what + " to learn from (" + samples.size() + ")");
        StringBuilder data = new StringBuilder();
        for (String s : samples) data.append("<message>\n").append(s.strip()).append("\n</message>\n");
        String prompt = "Below are recent " + what + " written by one person. Describe how they write, as a short "
                + "instruction for someone drafting replies in their voice: tone and formality, typical length, how they "
                + "open and sign off (the exact words they use), spelling and language, phrases they like, formatting "
                + "(lists, emoji, markdown), and anything they avoid. Write 5 to 10 short lines, addressed to the drafter "
                + "(\"Keep it short…\"). Describe the style only: no facts about their work, no names of other people, "
                + "nothing from what the messages are about.\n\n" + data;
        Process p = Reply.Claude.start("--json-schema", SCHEMA);
        try (var os = p.getOutputStream()) {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
        var out = new java.util.concurrent.CompletableFuture<String>();
        Thread reader = new Thread(() -> {
            try {
                out.complete(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                out.completeExceptionally(e);
            }
        });
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(3, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("Claude took too long");
        }
        JsonNode answer = JSON.readTree(out.get(10, TimeUnit.SECONDS));
        String style = answer.path("structured_output").path("style").asText("").strip();
        if (p.exitValue() != 0 || style.isBlank()) {
            throw new IllegalStateException("Claude didn't describe it: " + answer.path("result").asText("no answer"));
        }
        ObjectNode n = save(style, samples.size() + " of your " + what);
        if (answer.path("total_cost_usd").isNumber()) n.put("cost", answer.path("total_cost_usd").asDouble());
        return n;
    }

    /** {note, style}: note first, for anything said to the user, so the style is only the description. */
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"note\":{\"type\":\"string\",\"description\":\"Anything you say to the user. Not kept.\"},"
            + "\"style\":{\"type\":\"string\",\"description\":\"The description of how they write.\"}},"
            + "\"required\":[\"note\",\"style\"]}";

    /** What a suggested reply's prompt says about how you write: "" when there's no note. */
    static String forPrompt() {
        String t = text();
        return t.isEmpty() ? "" : "\n\nHow they write, in their own words (follow it):\n<style>\n" + t + "\n</style>";
    }
}
