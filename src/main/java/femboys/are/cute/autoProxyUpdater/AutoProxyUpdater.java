package femboys.are.cute.autoProxyUpdater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Plugin(id = "autoproxyupdater", name = "AutoProxyUpdater", version = "1.3", authors = {"FemBoysAreCut3"})
public class AutoProxyUpdater {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final File configFile;
    private final ObjectMapper yamlMapper;
    private JsonNode config;

    @Inject
    public AutoProxyUpdater(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configFile = dataDirectory.resolve("config.yml").toFile();
        this.yamlMapper = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        setupConfig();

        File oldVersionFile = dataDirectory.resolve("version.txt").toFile();
        if (oldVersionFile.exists()) oldVersionFile.delete();

        String userAgent = config.get("user-agent").asText("AutoProxyUpdater/1.3 (contact@femboys.are.cute)");
        int delay = config.get("shutdown-delay").asInt(3);

        String manualPath = config.get("proxy-jar-path").asText("");
        File proxyJar = manualPath.isEmpty()
                ? new File(System.getProperty("java.class.path").split(File.pathSeparator)[0])
                : new File(manualPath);

        cleanupOldFiles(proxyJar);

        String apiUrl = config.get("api-url").asText("");
        if (apiUrl.isEmpty()) {
            apiUrl = detectLatestApiUrl(userAgent);
        }

        final String finalApiUrl = apiUrl;

        server.getScheduler().buildTask(this, () -> {
            try {
                logger.info("=== Proxy Update Check ({}) ===", finalApiUrl);
                int currentBuild = config.get("internal-current-build").asInt(-1);

                JsonNode root = fetchJson(finalApiUrl + "/builds", userAgent);
                if (root != null && root.has("builds")) {
                    JsonNode builds = root.get("builds");
                    JsonNode latest = builds.get(builds.size() - 1);
                    int highestBuild = latest.get("build").asInt();

                    if (highestBuild > currentBuild) {
                        logger.info("New update found: Build " + highestBuild);
                        String fileName = latest.get("downloads").get("application").get("name").asText();
                        String downloadUrl = finalApiUrl + "/builds/" + highestBuild + "/downloads/" + fileName;

                        downloadAndReplaceJar(downloadUrl, proxyJar, userAgent);
                        updateConfigBuild(highestBuild);

                        logger.warn("Shutdown in " + delay + "s to apply update...");

                        new Thread(() -> {
                            try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) {}
                            server.shutdown();
                        }).start();
                    } else {
                        logger.info("Proxy is up to date.");
                    }
                }
            } catch (Exception e) {
                logger.error("Update failed: " + e.getMessage());
            }
        }).delay(5, TimeUnit.SECONDS).schedule();
    }

    private void cleanupOldFiles(File proxyJar) {
        File backup = new File(proxyJar.getParent(), proxyJar.getName() + ".old");
        if (backup.exists()) backup.delete();
    }

    private void downloadAndReplaceJar(String url, File target, String ua) throws IOException {
        logger.info("Downloading update...");
        File tempFile = new File(target.getParent(), target.getName() + ".tmp");

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", ua);

        try (InputStream in = c.getInputStream()) {
            FileUtils.copyInputStreamToFile(in, tempFile);
        }

        if (target.exists()) {
            File backup = new File(target.getParent(), target.getName() + ".old");
            if (backup.exists()) backup.delete();
            target.renameTo(backup);
        }

        if (!tempFile.renameTo(target)) {
            throw new IOException("Could not replace JAR file!");
        }
    }

    private void setupConfig() {
        try {
            if (!dataDirectory.toFile().exists()) dataDirectory.toFile().mkdirs();
            if (!configFile.exists()) {
                String defaultConfig =
                        "# AutoProxyUpdater Config\n" +
                                "api-url: \"\"\n" +
                                "proxy-jar-path: \"\"\n" +
                                "user-agent: \"AutoProxyUpdater/1.3 (contact@femboys.are.cute)\"\n" +
                                "shutdown-delay: 3\n" +
                                "\n" +
                                "# ------------------------------------------------------------\n" +
                                "# DONT TOUCH! Internal state, do not modify manually.\n" +
                                "# ------------------------------------------------------------\n" +
                                "internal-current-build: -1\n";
                Files.writeString(configFile.toPath(), defaultConfig, StandardCharsets.UTF_8);
            }
            config = yamlMapper.readTree(configFile);
        } catch (IOException e) {
            logger.error("Config error: " + e.getMessage());
        }
    }

    private void updateConfigBuild(int newBuild) {
        try {
            ((ObjectNode) config).put("internal-current-build", newBuild);
            yamlMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, config);

            String content = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            if (!content.contains("DONT TOUCH!")) {
                String header = "# ------------------------------------------------------------\n" +
                        "# DONT TOUCH! Internal state, do not modify manually.\n" +
                        "# ------------------------------------------------------------\n";
                Files.writeString(configFile.toPath(), header + content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            logger.error("Could not update config build: " + e.getMessage());
        }
    }

    private String detectLatestApiUrl(String ua) {
        try {
            JsonNode root = fetchJson("https://api.papermc.io/v2/projects/velocity", ua);
            if (root != null && root.has("versions")) {
                JsonNode versions = root.get("versions");
                String latestVer = versions.get(versions.size() - 1).asText();
                return "https://api.papermc.io/v2/projects/velocity/versions/" + latestVer;
            }
        } catch (Exception ignored) {}
        return "https://api.papermc.io/v2/projects/velocity/versions/3.3.0-SNAPSHOT";
    }

    private JsonNode fetchJson(String url, String ua) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", ua);
        if (c.getResponseCode() != 200) return null;
        try (InputStream in = c.getInputStream()) {
            return new ObjectMapper().readTree(in);
        }
    }
}