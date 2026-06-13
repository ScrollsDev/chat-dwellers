package com.chatdwellers.config;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BlacklistTest {
    private static final String GENERIC = "{name} can't be a Vault Dweller.";

    @Test
    void customMessageIsReturnedForBlacklistedName() {
        Blacklist bl = Blacklist.of(
            List.of("technoblade=Technoblade can't possibly be a dweller since he never dies."),
            GENERIC);
        assertEquals("Technoblade can't possibly be a dweller since he never dies.",
            bl.messageFor("technoblade").orElseThrow());
    }

    @Test
    void matchIsCaseInsensitive() {
        Blacklist bl = Blacklist.of(List.of("Technoblade=nope"), GENERIC);
        assertEquals("nope", bl.messageFor("TECHNOBLADE").orElseThrow());
        assertEquals("nope", bl.messageFor("technoBlade").orElseThrow());
    }

    @Test
    void bareEntryUsesGenericTemplateWithNameSubstituted() {
        Blacklist bl = Blacklist.of(List.of("griefer123"), GENERIC);
        assertEquals("griefer123 can't be a Vault Dweller.",
            bl.messageFor("griefer123").orElseThrow());
    }

    @Test
    void nonMemberAndNullReturnEmpty() {
        Blacklist bl = Blacklist.of(List.of("technoblade=x"), GENERIC);
        assertTrue(bl.messageFor("someoneelse").isEmpty());
        assertTrue(bl.messageFor(null).isEmpty());
    }

    @Test
    void blankAndNamelessEntriesAreIgnored() {
        Blacklist bl = Blacklist.of(Arrays.asList("", "  ", "=onlymsg", null, "ok=yes"), GENERIC);
        assertTrue(bl.messageFor("").isEmpty());
        assertEquals("yes", bl.messageFor("ok").orElseThrow());
    }
}
