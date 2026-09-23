package me.ganamaga.katharsis_proxy.punish;

/** Whether a punishment holds the account, its address, or both. */
public enum IpMode {
    /** Only the account. */
    ACCOUNT,
    /** The account, and the address it last joined from if one is known. */
    ACCOUNT_AND_IP,
    /** Only the address. */
    IP_ONLY
}
