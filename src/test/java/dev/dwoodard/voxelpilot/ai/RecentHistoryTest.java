package dev.dwoodard.voxelpilot.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecentHistoryTest {
    private final RecentHistory history = RecentHistory.get();

    @AfterEach
    void reset() { history.clear(); }

    @Test
    void inFlightRequestIsNotShown() {
        history.start("build a hut");
        assertEquals("", history.render());
    }

    @Test
    void showsOutcomeScriptAndLaterStatus() {
        history.start("build a hut").finish(List.of("box 0 0 0 3 1 3 stone"), "previewed 9 changes");
        history.mark("confirmed");
        String text = history.render();
        assertTrue(text.contains("\"build a hut\" -> previewed 9 changes -> confirmed"), text);
        assertTrue(text.contains("    box 0 0 0 3 1 3 stone"), text);
    }

    @Test
    void markSkipsRepliesWithoutScripts() {
        history.start("build a hut").finish(List.of("box 0 0 0 3 1 3 stone"), "previewed 9 changes");
        history.start("hello").finish(List.of(), "replied: hi");
        history.mark("undone");
        assertTrue(history.render().contains("previewed 9 changes -> undone"));
        assertFalse(history.render().contains("replied: hi -> undone"));
    }

    @Test
    void keepsOnlyTheLastFew() {
        for (int i = 1; i <= 6; i++) history.start("request " + i).finish(List.of(), "no changes");
        String text = history.render();
        assertFalse(text.contains("request 3"));
        assertTrue(text.contains("request 4") && text.contains("request 6"));
    }

    @Test
    void failuresAreReported() {
        history.start("build a castle").fail("error: model timed out");
        assertTrue(history.render().contains("error: model timed out"));
    }
}
