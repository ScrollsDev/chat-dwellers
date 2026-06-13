package com.chatdwellers.render;

import com.chatdwellers.render.VaultLifecycle.Transition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VaultLifecycleTest {

    @Test
    void enteringVaultFromOverworldReportsEntered() {
        VaultLifecycle lc = new VaultLifecycle();
        assertEquals(Transition.NONE, lc.update("minecraft:overworld"));
        assertEquals(Transition.ENTERED_VAULT, lc.update("the_vault:vault"));
    }

    @Test
    void leavingVaultReportsLeft() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault");
        assertEquals(Transition.LEFT_VAULT, lc.update("minecraft:overworld"));
    }

    @Test
    void stayingInVaultReportsNone() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault");
        assertEquals(Transition.NONE, lc.update("the_vault:vault"));
    }

    @Test
    void losingLevelWhileInVaultCountsAsLeft() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault");
        assertEquals(Transition.LEFT_VAULT, lc.update(null));
    }

    @Test
    void nullWhileNotInVaultIsNone() {
        VaultLifecycle lc = new VaultLifecycle();
        assertEquals(Transition.NONE, lc.update(null));
        assertEquals(Transition.NONE, lc.update("minecraft:overworld"));
    }

    @Test
    void enteringInstancedVaultDimReportsEntered() {
        VaultLifecycle lc = new VaultLifecycle();
        assertEquals(Transition.NONE, lc.update("minecraft:overworld"));
        assertEquals(Transition.ENTERED_VAULT,
            lc.update("the_vault:vault_cd144c84-266e-41fa-93c9-46e97bf88602"));
    }

    @Test
    void leavingInstancedVaultDimReportsLeft() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault_cd144c84-266e-41fa-93c9-46e97bf88602");
        assertEquals(Transition.LEFT_VAULT, lc.update("minecraft:overworld"));
    }

    @Test
    void arenaAndOtherSideAreNotVault() {
        VaultLifecycle lc = new VaultLifecycle();
        assertEquals(Transition.NONE, lc.update("the_vault:arena"));
        assertEquals(Transition.NONE, lc.update("the_vault:the_other_side"));
    }

    @Test
    void stayingInSameInstancedVaultReportsNone() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault_aaa");
        assertEquals(Transition.NONE, lc.update("the_vault:vault_aaa"));
    }
}
