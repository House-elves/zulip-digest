import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class ZulipChat {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MESSAGES = 100;

    /**
     * @param time   when it was sent, in epoch seconds
     * @param unread whether it is unread for you
     */
    record ZulipMessage(String server, String stream, String topic, String sender,
                        String content, String url, boolean isMention,
                        boolean involvesMe, String fullText, long time, boolean unread) {
        long id() {
            return extractMessageId(url);
        }
    }

    /**
     * Everything sent in your streams and DMs in the last {@code hours}, read or not, newest {@value #MAX_RECENT}
     * per server; plus older unread mentions. Marks nothing read.
     */
    static List<ZulipMessage> fetchRecent(Config config, int hours) {
        long since = System.currentTimeMillis() / 1000 - hours * 3600L;
        List<ZulipMessage> out = new ArrayList<>();
        for (Config.ZulipServer server : config.getZulipServers()) {
            Set<Long> seen = new HashSet<>();
            for (ZulipMessage m : fetchMessages(server,
                    "[{\"operator\":\"is\",\"operand\":\"mentioned\"},{\"operator\":\"is\",\"operand\":\"unread\"}]")) {
                if (seen.add(m.id())) out.add(asMention(m));
            }
            for (ZulipMessage m : fetchMessages(server, "[]", MAX_RECENT)) {
                if (m.time() >= since && seen.add(m.id())) out.add(m);
            }
        }
        out.sort(Comparator.comparingLong(ZulipMessage::time));
        return out;
    }

    static final int MAX_RECENT = 400;

    private static ZulipMessage asMention(ZulipMessage m) {
        return new ZulipMessage(m.server(), m.stream(), m.topic(), m.sender(), m.content(), m.url(), true, true,
                m.fullText(), m.time(), m.unread());
    }

    /**
     * Marks read the unread messages of a stream's topic (when {@code stream} is set) plus the given message ids.
     * The topic is matched as Zulip does (case-insensitively); its unread messages are looked up first, so messages
     * that arrived since the run are included.
     *
     * @return how many messages were asked to be marked read
     */
    static int markRead(Config.ZulipServer server, String stream, String topic, List<Long> messageIds) throws Exception {
        Set<Long> ids = new LinkedHashSet<>(messageIds);
        if (stream != null) {
            String narrow = MAPPER.writeValueAsString(List.of(
                    Map.of("operator", "stream", "operand", stream),
                    Map.of("operator", "topic", "operand", topic),
                    Map.of("operator", "is", "operand", "unread")));
            List<ZulipMessage> unread = fetchMessages(server, narrow, 1000);
            for (ZulipMessage m : unread) {
                long id = extractMessageId(m.url());
                if (id > 0) ids.add(id);
            }
        }
        if (ids.isEmpty()) return 0;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            String baseUrl = (server.url().startsWith("https://") ? server.url() : "https://" + server.url()).replaceAll("/+$", "");
            String auth = Base64.getEncoder().encodeToString(
                    (server.email() + ":" + server.apiKey()).getBytes(StandardCharsets.UTF_8));
            String body = "messages=" + URLEncoder.encode(MAPPER.writeValueAsString(ids), StandardCharsets.UTF_8)
                    + "&op=add&flag=read";
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/messages/flags"))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Zulip answered HTTP " + response.statusCode());
        }
        return ids.size();
    }

    /** The newest {@code max} messages of a narrow (JSON), oldest first; empty when Zulip doesn't answer. */
    static List<ZulipMessage> messages(Config.ZulipServer server, String narrow, int max) {
        List<ZulipMessage> out = new ArrayList<>(fetchMessages(server, narrow, max));
        out.sort(Comparator.comparingLong(ZulipMessage::time));
        return out;
    }

    static String baseUrl(Config.ZulipServer server) {
        return (server.url().startsWith("https://") ? server.url() : "https://" + server.url()).replaceAll("/+$", "");
    }

    private static String auth(Config.ZulipServer server) {
        return "Basic " + Base64.getEncoder().encodeToString((server.email() + ":" + server.apiKey()).getBytes(StandardCharsets.UTF_8));
    }

    /** A GET of Zulip's API ({@code path} from /api/v1), as you: its JSON, or an exception saying why not. */
    static JsonNode get(Config.ZulipServer server, String path) throws IOException {
        return call(server, HttpRequest.newBuilder().uri(URI.create(baseUrl(server) + path)).GET());
    }

    /** A POST of a form to Zulip's API, as you. */
    static JsonNode post(Config.ZulipServer server, String path, Map<String, String> form) throws IOException {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return call(server, HttpRequest.newBuilder().uri(URI.create(baseUrl(server) + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    private static JsonNode call(Config.ZulipServer server, HttpRequest.Builder request) throws IOException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<String> r = client.send(request.header("Authorization", auth(server)).timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode json = r.body().isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(r.body());
            if (r.statusCode() != 200 || !"success".equals(json.path("result").asText())) {
                throw new IOException("Zulip said: " + json.path("msg").asText("HTTP " + r.statusCode()));
            }
            return json;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    private static List<ZulipMessage> fetchMessages(Config.ZulipServer server, String narrow) {
        return fetchMessages(server, narrow, MAX_MESSAGES);
    }

    private static List<ZulipMessage> fetchMessages(Config.ZulipServer server, String narrow, int max) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {

            String baseUrl = server.url().startsWith("https://") ? server.url() : "https://" + server.url();
            baseUrl = baseUrl.replaceAll("/+$", "");

            String encodedNarrow = URLEncoder.encode(narrow, StandardCharsets.UTF_8);
            String url = baseUrl + "/api/v1/messages?narrow=" + encodedNarrow
                    + "&anchor=newest&num_before=" + max + "&num_after=0"
                    + "&apply_markdown=false";

            String auth = Base64.getEncoder().encodeToString(
                    (server.email() + ":" + server.apiKey()).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + auth)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("  Zulip API returned " + response.statusCode() + " from " + server.url());
                return List.of();
            }

            JsonNode root = MAPPER.readTree(response.body());
            if (!"success".equals(root.path("result").asText())) {
                System.err.println("  Zulip API error: " + root.path("msg").asText("unknown error"));
                return List.of();
            }

            JsonNode messages = root.path("messages");
            if (!messages.isArray()) return List.of();

            List<ZulipMessage> result = new ArrayList<>();
            for (JsonNode msg : messages) {
                boolean dm = "private".equals(msg.path("type").asText());
                Set<String> flags = new HashSet<>();
                msg.path("flags").forEach(f -> flags.add(f.asText()));
                boolean personal = dm || flags.contains("starred") || flags.contains("has_alert_word")
                        || flags.contains("mentioned") || flags.contains("wildcard_mentioned");
                String stream = dm ? "DM" : msg.path("display_recipient").asText("");
                String topic = msg.path("subject").asText("");
                String sender = msg.path("sender_full_name").asText("");
                String content = msg.path("content").asText("");
                String fullText = content;
                long messageId = msg.path("id").asLong();

                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }

                String messageUrl = buildMessageUrl(baseUrl, stream, topic, messageId);

                result.add(new ZulipMessage(server.url(), stream, topic, sender, content, messageUrl, false,
                        personal, fullText, msg.path("timestamp").asLong(), !flags.contains("read")));
            }
            return result;
        } catch (Exception e) {
            System.err.println("  Error calling Zulip API: " + e.getMessage());
            return List.of();
        }
    }

    private static String buildMessageUrl(String baseUrl, String stream, String topic, long messageId) {
        String encodedStream = URLEncoder.encode(stream, StandardCharsets.UTF_8).replace("+", ".");
        String encodedTopic = URLEncoder.encode(topic, StandardCharsets.UTF_8).replace("+", ".");
        return baseUrl + "/#narrow/stream/" + encodedStream + "/topic/" + encodedTopic + "/near/" + messageId;
    }

    private static long extractMessageId(String url) {
        int nearIdx = url.lastIndexOf("/near/");
        if (nearIdx < 0) return -1;
        try {
            return Long.parseLong(url.substring(nearIdx + 6));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
