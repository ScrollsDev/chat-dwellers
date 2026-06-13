package com.chatdwellers.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DwellerName mirrors the exact nickname Vault's {@code FighterEntity.tick()} derives from a
 * dweller's custom name before feeding it to {@code SkinProfile.updateSkin}. We need bit-for-bit
 * parity: ChatDwellers writes this same value into the entity's {@code lastName} field so Vault's
 * tick treats the custom name as already-processed and does not overwrite our injected skin.
 *
 * Cases are taken straight from the bytecode of {@code iskallia.vault.entity.entity.FighterEntity}
 * in the_vault-1.18.2-3.21.5-remastered: strip leading U+2712 (✒) chars, trim, and if the result
 * starts with '[' take the segment after the first ']'.
 */
class DwellerNameTest {

    private static final String PEN = String.valueOf((char) 10022); // ✒ U+2712

    @Test
    void plainNamePassesThrough() {
        assertEquals("xDoggeh", DwellerName.derive("xDoggeh"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", DwellerName.derive(null));
    }

    @Test
    void surroundingWhitespaceTrimmed() {
        assertEquals("xDoggeh", DwellerName.derive("  xDoggeh  "));
    }

    @Test
    void singleLeadingPenStripped() {
        assertEquals("xDoggeh", DwellerName.derive(PEN + "xDoggeh"));
    }

    @Test
    void multipleLeadingPensStripped() {
        assertEquals("xDoggeh", DwellerName.derive(PEN + PEN + PEN + "xDoggeh"));
    }

    @Test
    void penThenWhitespaceTrimmed() {
        assertEquals("xDoggeh", DwellerName.derive(PEN + "  xDoggeh "));
    }

    @Test
    void bracketTierPrefixDropped() {
        assertEquals("xDoggeh", DwellerName.derive("[Tier 3] xDoggeh"));
    }

    @Test
    void penAndBracketTogether() {
        assertEquals("xDoggeh", DwellerName.derive(PEN + "[Champion] xDoggeh"));
    }

    @Test
    void unclosedBracketLeftIntact() {
        // Vault itself would crash here; we degrade gracefully and leave the value as-is.
        assertEquals("[oops", DwellerName.derive("[oops"));
    }
}
