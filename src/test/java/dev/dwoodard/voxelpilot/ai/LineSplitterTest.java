package dev.dwoodard.voxelpilot.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineSplitterTest {
    @Test
    void emitsLinesAsSoonAsTheyComplete() {
        List<String> lines = new ArrayList<>();
        LineSplitter splitter = new LineSplitter(lines::add);
        splitter.accept("box 0 0");
        assertEquals(List.of(), lines);
        splitter.accept(" 0 3 1 3 stone\ndoor 1");
        assertEquals(List.of("box 0 0 0 3 1 3 stone"), lines);
        splitter.accept(" 1 0 back\n\n  \nsay done");
        assertEquals(List.of("box 0 0 0 3 1 3 stone", "door 1 1 0 back"), lines);
        splitter.flush();
        assertEquals("say done", lines.get(2));
        assertEquals("box 0 0 0 3 1 3 stone\ndoor 1 1 0 back\n\n  \nsay done", splitter.text());
    }
}
