package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EngineTest {
    @Test
    void teamNameReturnsExpectedTeamName() {
        assertEquals("The Query Crew", new Engine().teamName());
    }
}
