package com.chatdwellers.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> TWITCH_CLIENT_ID;
    private static final ForgeConfigSpec.ConfigValue<String> REWARD_NAME;
    private static final ForgeConfigSpec.IntValue REWARD_COST;
    private static final ForgeConfigSpec.ConfigValue<String> REWARD_PROMPT;
    private static final ForgeConfigSpec.ConfigValue<String> NAMETAG_FORMAT;
    private static final ForgeConfigSpec.DoubleValue NAMETAG_Y_OFFSET;
    private static final ForgeConfigSpec.IntValue MAX_POOL_SIZE;
    private static final ForgeConfigSpec.BooleanValue CLEAR_QUEUE_ON_VAULT_EXIT;
    private static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> BLACKLIST;
    private static final ForgeConfigSpec.ConfigValue<String> BLACKLIST_GENERIC;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Master ChatDwellers settings").push("chatdwellers");
        ENABLED = b.comment(
            "Master enable. Set to false (or run /chatdwellers off) when joining a server",
            "with a similar plugin so the mod stops setting skins / processing Twitch events.")
            .define("enabled", true);
        b.pop();

        b.comment("Twitch settings").push("twitch");
        TWITCH_CLIENT_ID = b.comment(
            "Twitch application Client ID. Defaults to the embedded ChatDwellers app —",
            "you don't need to change this. Public OAuth client IDs are not secrets;",
            "Twitch is designed for them to be distributed in apps. Override only if",
            "you've registered your own Twitch app and prefer to use it (e.g. for",
            "your own rate-limit budget or to publish your own branded ChatDwellers fork).")
            .define("twitchClientId", "dt6kxscc7ugt59ze2lkfo5yfuacyv8");
        REWARD_NAME = b.define("rewardName", "Become a Vault Dweller");
        REWARD_COST = b.defineInRange("rewardCost", 500, 1, 1_000_000);
        REWARD_PROMPT = b.define("rewardPrompt", "Type your Minecraft username");
        b.pop();

        b.comment("Dweller skinning settings").push("dwellers");
        NAMETAG_FORMAT = b.comment("Tokens: {twitch}, {mc}").define("nametagFormat", "{twitch}");
        NAMETAG_Y_OFFSET = b.comment(
            "How far (in blocks) to lift the nametag WHILE Vault's health bar is showing over the",
            "dweller, to clear it. With no health bar the nametag sits at the standard MC height.")
            .defineInRange("nametagYOffset", 0.3, 0.0, 5.0);
        MAX_POOL_SIZE = b.comment("Pending viewers beyond this are refused")
            .defineInRange("maxPoolSize", 100, 1, 10_000);
        CLEAR_QUEUE_ON_VAULT_EXIT = b.comment(
            "When true, leaving a vault fulfills + drops the viewers who appeared this vault,",
            "keeping those who never appeared. When false the queue persists across vaults until",
            "you run /cd claim or /cd purge. Toggle live with /cd autoclear on|off.")
            .define("clearQueueOnVaultExit", true);
        b.pop();

        b.comment("Redemption blacklist").push("blacklist");
        BLACKLIST = b.comment(
            "Minecraft names that can't become dwellers. Format: 'mcname=message posted to Twitch chat'.",
            "An entry with no '=' uses genericMessage. Matching is case-insensitive.")
            .defineList("entries",
                java.util.List.of("technoblade=Technoblade can't possibly be a dweller since he never dies."),
                o -> o instanceof String);
        BLACKLIST_GENERIC = b.comment(
            "Message for blacklist entries that have no message of their own; {name} is replaced.")
            .define("genericMessage", "{name} can't be a Vault Dweller.");
        b.pop();

        SPEC = b.build();
    }

    private Config() {}

    public static boolean enabled() { return ENABLED.get(); }
    public static void setEnabled(boolean v) { ENABLED.set(v); ENABLED.save(); }

    public static String twitchClientId() { return TWITCH_CLIENT_ID.get(); }
    public static String rewardName() { return REWARD_NAME.get(); }
    public static int rewardCost() { return REWARD_COST.get(); }
    public static void setRewardCost(int v) { REWARD_COST.set(v); REWARD_COST.save(); }
    public static String rewardPrompt() { return REWARD_PROMPT.get(); }
    public static String nametagFormat() { return NAMETAG_FORMAT.get(); }
    public static double nametagYOffset() { return NAMETAG_Y_OFFSET.get(); }
    public static int maxPoolSize() { return MAX_POOL_SIZE.get(); }

    public static boolean clearQueueOnVaultExit() { return CLEAR_QUEUE_ON_VAULT_EXIT.get(); }
    public static void setClearQueueOnVaultExit(boolean v) {
        CLEAR_QUEUE_ON_VAULT_EXIT.set(v); CLEAR_QUEUE_ON_VAULT_EXIT.save();
    }

    public static String blacklistGenericMessage() { return BLACKLIST_GENERIC.get(); }

    @SuppressWarnings("unchecked")
    public static java.util.List<String> blacklistEntries() {
        return new java.util.ArrayList<>((java.util.List<String>) (java.util.List<?>) BLACKLIST.get());
    }

    public static Blacklist blacklist() {
        return Blacklist.of(blacklistEntries(), blacklistGenericMessage());
    }

    private static String entryName(String entry) {
        String n = entry.contains("=") ? entry.substring(0, entry.indexOf('=')) : entry;
        return n.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static void addBlacklist(String name, String message) {
        java.util.List<String> entries = blacklistEntries();
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        entries.removeIf(e -> entryName(e).equals(key));
        entries.add(message == null || message.isBlank() ? name : name + "=" + message);
        BLACKLIST.set(entries); BLACKLIST.save();
    }

    public static boolean removeBlacklist(String name) {
        java.util.List<String> entries = blacklistEntries();
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        boolean removed = entries.removeIf(e -> entryName(e).equals(key));
        if (removed) { BLACKLIST.set(entries); BLACKLIST.save(); }
        return removed;
    }

    public static String formatNametag(String twitch, String mc) {
        return NametagFormatter.format(nametagFormat(), twitch, mc);
    }
}
