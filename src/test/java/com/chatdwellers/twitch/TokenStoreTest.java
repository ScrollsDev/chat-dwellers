package com.chatdwellers.twitch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TokenStoreTest {

    @Test
    void emptyStoreReadsAsEmptyValues(@TempDir Path dir) {
        TokenStore store = TokenStore.atPath(dir.resolve("chatdwellers-secret.toml"));
        assertEquals("", store.accessToken());
        assertEquals("", store.refreshToken());
        assertEquals(0L, store.tokenExpiresAtEpochSeconds());
        assertEquals("", store.broadcasterId());
        assertEquals("", store.rewardId());
    }

    @Test
    void setAndPersistRoundTrips(@TempDir Path dir) {
        Path file = dir.resolve("chatdwellers-secret.toml");
        TokenStore a = TokenStore.atPath(file);
        a.setAccessToken("AT");
        a.setRefreshToken("RT");
        a.setTokenExpiresAtEpochSeconds(1234567890L);
        a.setBroadcasterId("BID");
        a.setRewardId("RID");

        TokenStore b = TokenStore.atPath(file);
        assertEquals("AT", b.accessToken());
        assertEquals("RT", b.refreshToken());
        assertEquals(1234567890L, b.tokenExpiresAtEpochSeconds());
        assertEquals("BID", b.broadcasterId());
        assertEquals("RID", b.rewardId());
    }

    @Test
    void individualSetterWritesImmediately(@TempDir Path dir) {
        Path file = dir.resolve("chatdwellers-secret.toml");
        TokenStore a = TokenStore.atPath(file);
        a.setAccessToken("AT1");

        TokenStore b = TokenStore.atPath(file);
        assertEquals("AT1", b.accessToken());

        a.setAccessToken("AT2");
        TokenStore c = TokenStore.atPath(file);
        assertEquals("AT2", c.accessToken());
    }
}
