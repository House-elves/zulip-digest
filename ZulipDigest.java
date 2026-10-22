///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//SOURCES Config.java
//SOURCES ZulipChat.java
//SOURCES TopicDigest.java
//SOURCES Reply.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Reads what was said in your Zulip streams and DMs lately, and sums it up per topic with Claude, for its Zulip tab on
 * Elf Desk. Marks nothing read unless you click Done on a topic there. Sends nothing.
 */
@Command(name = "zulip-digest", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Sums up your Zulip streams per topic with Claude, for Elf Desk's Zulip tab")
public class ZulipDigest implements Callable<Integer> {

    @Option(names = "--dry-run", description = "Read and sum up, but don't save the run")
    boolean dryRun;

    @Option(names = "--action", paramLabel = "NAME", description = "For the Zulip tab: done (stdin: {server, stream, "
            + "topic, messages}) marks the topic read and records it in ~/.config/zulip-digest/done; draft-reply "
            + "({server, stream, topic, message}) suggests a reply; send-reply (the same, and content) sends it. "
            + "Prints one JSON line.")
    String action;

    static final Path RUNS = Config.CONFIG_DIR.resolve("runs");
    static final Path DONE = Config.CONFIG_DIR.resolve("done");
    static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Integer call() {
        long startTime = System.currentTimeMillis();
        Config config = Config.load();
        if (action != null) return action(config);
        if (config.getZulipServers().isEmpty()) {
            System.err.println("No Zulip server set up: set Zulip server, account and API key in its settings.");
            return 1;
        }
        System.out.println("Fetching Zulip messages...");
        List<ZulipChat.ZulipMessage> messages = ZulipChat.fetchRecent(config, config.hours);
        System.out.println("  Found " + messages.size() + " messages from the last " + config.hours + " hours. Digesting...");
        Map<String, String> digests = TopicDigest.generate(messages);

        List<Map<String, Object>> list = new ArrayList<>();
        for (ZulipChat.ZulipMessage m : messages) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("server", m.server());
            d.put("stream", m.stream());
            d.put("topic", m.topic());
            d.put("sender", m.sender());
            d.put("content", m.content());
            d.put("url", m.url());
            d.put("time", m.time());
            d.put("mention", m.isMention());
            d.put("involvesMe", m.involvesMe());
            d.put("unread", m.unread());
            list.add(d);
        }
        Map<String, Object> runData = new LinkedHashMap<>();
        runData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        runData.put("hours", config.hours);
        runData.put("zulip", Map.of("messages", list, "digests", digests));
        runData.put("durationMs", System.currentTimeMillis() - startTime);
        runData.put("aiCost", TopicDigest.AI_COST.sum());
        runData.put("status", "success");
        if (dryRun) System.out.println("Dry run: the digest isn't saved.");
        else save(runData);
        System.out.println("Done!");
        return 0;
    }

    /** --action done: Done on a topic in the Zulip tab, its values on stdin (the runner checked them against elf.yaml). */
    private int action(Config config) {
        if (!List.of("done", "draft-reply", "send-reply").contains(action)) {
            System.err.println("Not an action: " + action);
            return 2;
        }
        JsonNode in;
        try {
            in = JSON.readTree(System.in);
        } catch (Exception e) {
            System.err.println("Can't read what to do: " + e.getMessage());
            return 2;
        }
        String serverName = in.path("server").asText("");
        Config.ZulipServer server = config.getZulipServers().stream()
                .filter(s -> host(s.url()).equalsIgnoreCase(host(serverName))).findFirst().orElse(null);
        if (server == null) {
            System.err.println("Not a configured Zulip server: " + serverName);
            return 2;
        }
        if (!action.equals("done")) return reply(server, in);
        String stream = in.path("stream").asText(""), topic = in.path("topic").asText("");
        List<Long> ids = new ArrayList<>();
        in.path("messages").forEach(m -> ids.add(m.asLong()));
        // Direct messages are one "topic" on the tab: they're marked read by id.
        boolean dm = stream.equals("DM");
        if (dm && ids.isEmpty()) {
            System.err.println("Direct messages are marked read by id: none given");
            return 2;
        }
        int marked;
        try {
            marked = ZulipChat.markRead(server, dm ? null : stream, dm ? null : topic, ids);
        } catch (Exception e) {
            System.err.println("Couldn't mark it read: " + e.getMessage());
            return 1;
        }
        if (recordDone(serverName, stream + " > " + topic) != 0) return 1;
        System.out.println("{\"marked\": " + marked + "}");
        return 0;
    }

    /** draft-reply and send-reply: the conversation is read from Zulip each time, so it goes where Zulip says. */
    private int reply(Config.ZulipServer server, JsonNode in) {
        try {
            Reply.Conversation c = Reply.conversation(server, in.path("stream").asText(""), in.path("topic").asText(""),
                    in.path("message").asLong(0));
            // The confirm bar named where it goes: it must be where Zulip says the conversation is.
            if (action.equals("send-reply") && !c.where().equals(in.path("where").asText(""))) {
                throw new IllegalArgumentException("That conversation is " + c.where() + " now, not " + in.path("where").asText("")
                        + ". Suggest the reply again.");
            }
            ObjectNode out = action.equals("draft-reply") ? Reply.draft(c) : Reply.send(c, in.path("content").asText(""));
            System.out.println(JSON.writeValueAsString(out));
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println((action.equals("draft-reply") ? "Couldn't suggest a reply: " : "Couldn't send it: ") + e.getMessage());
            return 1;
        }
    }

    static String host(String url) {
        return url.replaceFirst("^https://", "").replaceAll("/+$", "");
    }

    /** done/zulip.json: {server: {"stream > topic": when}}, a month of it. */
    private static int recordDone(String server, String key) {
        Path file = DONE.resolve("zulip.json");
        try {
            Files.createDirectories(DONE);
            ObjectNode all = Files.exists(file) ? (ObjectNode) JSON.readTree(file.toFile()) : JSON.createObjectNode();
            String monthAgo = Instant.now().minus(Duration.ofDays(31)).toString();
            all.fields().forEachRemaining(g -> {
                if (g.getValue() instanceof ObjectNode o) o.properties().removeIf(e -> e.getValue().asText().compareTo(monthAgo) < 0);
            });
            all.withObject(server).put(key, Instant.now().toString());
            Path tmp = file.resolveSibling("zulip.json.new");
            Files.writeString(tmp, JSON.writeValueAsString(all));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return 0;
        } catch (Exception e) {
            System.err.println("Marked read, but couldn't note it as done: " + e.getMessage());
            return 1;
        }
    }

    private static void save(Map<String, Object> runData) {
        try {
            Files.createDirectories(RUNS);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(RUNS.resolve(timestamp + ".json").toFile(), runData);
            Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
            try (Stream<Path> files = Files.list(RUNS)) {
                files.filter(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                    } catch (IOException e) {
                        return false;
                    }
                }).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException e) {
            System.err.println("Failed to save the run: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ZulipDigest()).execute(args));
    }
}
