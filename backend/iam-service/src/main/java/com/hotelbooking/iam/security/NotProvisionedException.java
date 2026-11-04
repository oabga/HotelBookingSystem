package com.hotelbooking.iam.security;

/**
 * Raised whenever the authenticated Google identity must not receive an internal JWT: email not
 * verified, no matching pre-provisioned {@code app_user}, or a {@code google_sub} mismatch. Always
 * surfaces as 403, never auto-provisions a user or tenant (docs/api-contract.md §3.2).
 */
public class NotProvisionedException extends RuntimeException {
}
