package com.acme.api;

import dev.lotus.Extension;
import dev.lotus.This;

@Extension(Wallet.class)
public final class WalletExtensions {
    public static boolean canAfford(@This Wallet wallet, long price) {
        return wallet.getCoins() >= price;
    }

    public static String describe(@This Wallet wallet) {
        return "Wallet";
    }

    public static Wallet empty() {
        return new Wallet();
    }
}
