import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ~/.config/zulip-digest/config: the Zulip servers it reads. Elf Desk writes it from its settings. */
public class Config {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "zulip-digest");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");

    int hours;
    private Map<String, String> raw;

    record ZulipServer(String url, String email, String apiKey) {}

    static Config load() {
        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Config file not found: " + CONFIG_PATH);
            System.err.println("Add zulip-digest on Elf Desk's Elves page and fill in its settings.");
            System.exit(1);
        }
        Map<String, String> raw = new HashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) raw.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            System.exit(1);
        }
        Config c = new Config();
        c.raw = raw;
        c.hours = Integer.parseInt(raw.getOrDefault("HOURS", "24"));
        return c;
    }

    /** ZULIP_SERVER_n with its ZULIP_EMAIL_n and ZULIP_API_KEY_n: the desk sets the first, more can be added by hand. */
    List<ZulipServer> getZulipServers() {
        List<ZulipServer> servers = new ArrayList<>();
        for (int i = 1; raw.containsKey("ZULIP_SERVER_" + i); i++) {
            String url = raw.get("ZULIP_SERVER_" + i);
            String email = raw.getOrDefault("ZULIP_EMAIL_" + i, "");
            String apiKey = raw.getOrDefault("ZULIP_API_KEY_" + i, "");
            if (!url.isEmpty() && !email.isEmpty() && !apiKey.isEmpty()) servers.add(new ZulipServer(url, email, apiKey));
        }
        return servers;
    }
}
