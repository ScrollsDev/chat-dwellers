package com.chatdwellers.twitch;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class MojangApiTest {

    @Test
    void resolveUuidExtractsId() throws IOException {
        MojangApi api = new MojangApi(url ->
            "{\"id\":\"853c80ef3c3749fdaa49938b674adae6\",\"name\":\"jeb_\"}");
        assertEquals(Optional.of("853c80ef3c3749fdaa49938b674adae6"), api.resolveUuid("jeb_"));
    }

    @Test
    void resolveUuidReturnsEmptyWhenNotFound() throws IOException {
        MojangApi api = new MojangApi(url -> null);
        assertEquals(Optional.empty(), api.resolveUuid("thisnamedoesnotexist"));
    }
}
