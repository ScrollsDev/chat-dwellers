package com.chatdwellers.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NametagFormatterTest {

    @Test
    void substitutesTwitchToken() {
        assertEquals("Dog", NametagFormatter.format("{twitch}", "Dog", "xDoggeh"));
    }

    @Test
    void substitutesMcToken() {
        assertEquals("xDoggeh", NametagFormatter.format("{mc}", "Dog", "xDoggeh"));
    }

    @Test
    void substitutesBothTokensWithSurroundingText() {
        assertEquals("Dog (xDoggeh)", NametagFormatter.format("{twitch} ({mc})", "Dog", "xDoggeh"));
    }

    @Test
    void nullValuesBecomeEmptyString() {
        assertEquals(" ()", NametagFormatter.format("{twitch} ({mc})", null, null));
    }

    @Test
    void formatWithNoTokensReturnedVerbatim() {
        assertEquals("Vault Dweller", NametagFormatter.format("Vault Dweller", "Dog", "xDoggeh"));
    }
}
