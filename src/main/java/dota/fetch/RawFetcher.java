package dota.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Качает Target'ы и сохраняет ответ байт в байт. Уже скачанное пропускает, если не refresh. */
class RawFetcher {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenDotaClient client;
    private final boolean refresh;
    private int downloaded;
    private int skipped;
    private int failed;

    RawFetcher(OpenDotaClient client, boolean refresh) {
        this.client = client;
        this.refresh = refresh;
    }

    void fetch(Endpoints.Target target) throws InterruptedException {
        if (!refresh && Files.exists(target.file())) {
            skipped++;
            Log.info("exists, skip: %s", target.file());
            return;
        }
        try {
            byte[] body = client.get(target.url());
            checkJson(body);
            writeAtomically(target.file(), body);
            downloaded++;
            Log.info("saved: %s (%d KB)", target.file(), body.length / 1024);
        } catch (IOException e) {
            failed++;
            Log.error("%s: %s", target.url(), e.getMessage());
        }
    }

    /** Ответ с кодом 200, но вида {"error": ...} / {"err": ...} не сохраняем, чтобы не считать его скачанным. */
    static void checkJson(byte[] body) throws IOException {
        JsonNode node = JSON.readTree(body);
        if (node == null || node.isMissingNode()) {
            throw new IOException("empty response");
        }
        for (String key : new String[]{"error", "err"}) {
            if (node.isObject() && node.size() == 1 && node.has(key)) {
                throw new IOException("API returned error: " + node.get(key).asText());
            }
        }
    }

    static void writeAtomically(Path file, byte[] body) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, body);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    void addCounts(int downloaded, int skipped) {
        this.downloaded += downloaded;
        this.skipped += skipped;
    }

    void recordFailure(String what, Exception e) {
        failed++;
        Log.error("%s: %s", what, e.getMessage());
    }

    int downloaded() {
        return downloaded;
    }

    int skipped() {
        return skipped;
    }

    int failed() {
        return failed;
    }
}
