package dota.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SharedDraftsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void savesCleanDraftAndBumpsVersion() throws Exception {
        SharedDrafts drafts = new SharedDrafts(dir.resolve("drafts"));
        String code = SharedDrafts.newCode();
        assertTrue(SharedDrafts.validCode(code));
        var first = drafts.save(code, JSON.readTree("""
                {"opponent":"SD_Team","map":2,"weFirst":false,"heroes":[23,22,0],"assign":{"42":"kren"},
                 "client":"tab1","evil":"<script>"}"""));
        assertEquals(1, first.path("version").asLong());
        assertFalse(first.has("evil"), "лишние поля не сохраняются");
        var second = drafts.save(code, JSON.readTree("{\"opponent\":\"SD_Team\",\"heroes\":[23]}"));
        assertEquals(2, second.path("version").asLong());
        assertEquals("SD_Team", drafts.read(code).orElseThrow().path("opponent").asText());
    }

    @Test
    void rejectsBadInput() throws Exception {
        SharedDrafts drafts = new SharedDrafts(dir.resolve("drafts"));
        assertFalse(SharedDrafts.validCode("../etc"), "код не путь");
        assertThrows(IllegalArgumentException.class, () -> drafts.save("abcd2345", JSON.readTree("{\"heroes\":[1]}")));
        String tooMany = "[" + "1,".repeat(24) + "1]";
        assertThrows(IllegalArgumentException.class,
                () -> drafts.save("abcd2345", JSON.readTree("{\"opponent\":\"X\",\"heroes\":" + tooMany + "}")));
        assertTrue(drafts.read("nothere1").isEmpty());
        assertFalse(new SharedDrafts(null).enabled());
    }
}
