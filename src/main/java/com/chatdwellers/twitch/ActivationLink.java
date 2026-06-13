package com.chatdwellers.twitch;

/** Chooses the activation URL to open/copy: the code-prefilled URL when Twitch returns one. */
public final class ActivationLink {

    private ActivationLink() {}

    public static String best(String verificationUri, String verificationUriComplete) {
        if (verificationUriComplete != null && !verificationUriComplete.isBlank()) {
            return verificationUriComplete;
        }
        return verificationUri;
    }
}
