package com.chatdwellers.twitch;

/** A redemption returned by Helix listPending or arriving as an EventSub event. */
public record PendingRedemption(
    String id,
    String userId,
    String userName,
    String userInput,
    String redeemedAt
) {}
