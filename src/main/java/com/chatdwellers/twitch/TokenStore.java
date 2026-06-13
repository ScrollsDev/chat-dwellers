package com.chatdwellers.twitch;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Path;

/** TOML-backed credential and reward-id store. One instance per file. Reads on
 *  construction; each setter immediately writes the full file back to disk. */
public final class TokenStore {

    private final CommentedFileConfig config;

    private TokenStore(Path path) {
        this.config = CommentedFileConfig.builder(path)
            .preserveInsertionOrder()
            .sync()
            .build();
        this.config.load();
    }

    /** Writes the current state to disk synchronously. Called after every setter. */
    private void persist() {
        config.save();
    }

    public static TokenStore atPath(Path path) {
        return new TokenStore(path);
    }

    private String getString(String key) {
        Object v = config.get(key);
        return v == null ? "" : v.toString();
    }

    private long getLong(String key) {
        Object v = config.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isEmpty()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    public String accessToken()                  { return getString("accessToken"); }
    public String refreshToken()                  { return getString("refreshToken"); }
    public long   tokenExpiresAtEpochSeconds()    { return getLong("tokenExpiresAtEpochSec"); }
    public String broadcasterId()                 { return getString("broadcasterId"); }
    public String rewardId()                      { return getString("rewardId"); }
    public String scopes()                        { return getString("scopes"); }

    public void setAccessToken(String v)               { config.set("accessToken", v); persist(); }
    public void setRefreshToken(String v)              { config.set("refreshToken", v); persist(); }
    public void setTokenExpiresAtEpochSeconds(long v)  { config.set("tokenExpiresAtEpochSec", v); persist(); }
    public void setBroadcasterId(String v)             { config.set("broadcasterId", v); persist(); }
    public void setRewardId(String v)                  { config.set("rewardId", v); persist(); }
    public void setScopes(String v)                    { config.set("scopes", v); persist(); }
}
