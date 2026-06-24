package com.mcplatform.domain.webauth;

/**
 * Why a web-auth token was issued. {@code LINK} creates the first web account for a player identity;
 * {@code RESET} replaces the password of an existing account. The canonical (English) name is what is
 * persisted/transported.
 */
public enum TokenPurpose {
    LINK,
    RESET
}
