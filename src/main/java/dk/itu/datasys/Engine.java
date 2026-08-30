package dk.itu.datasys;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber",
                "0");
        LOGGER.debug("engine started");
        System.out.println(new Engine().teamName());
        LOGGER.debug("engine stopped");
    }

    String teamName() {
        return "The Query Crew";
    }
}