package com.chatdwellers.twitch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivationLinkTest {

    @Test
    void prefersCompleteUrlWhenPresent() {
        assertEquals("https://twitch.tv/activate?device-code=ABC123",
            ActivationLink.best("https://twitch.tv/activate",
                "https://twitch.tv/activate?device-code=ABC123"));
    }

    @Test
    void fallsBackToBaseWhenCompleteMissing() {
        assertEquals("https://twitch.tv/activate",
            ActivationLink.best("https://twitch.tv/activate", null));
        assertEquals("https://twitch.tv/activate",
            ActivationLink.best("https://twitch.tv/activate", ""));
        assertEquals("https://twitch.tv/activate",
            ActivationLink.best("https://twitch.tv/activate", "   "));
    }
}
