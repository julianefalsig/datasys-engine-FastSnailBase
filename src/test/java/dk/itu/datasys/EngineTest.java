package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EngineTest {
    @Test
    void teamNameReturnsExpectedTeamName() {
        assertEquals("Team FastSnailBase", new Engine().teamName());
    }
}
