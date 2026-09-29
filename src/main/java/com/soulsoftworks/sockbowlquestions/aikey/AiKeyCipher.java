package com.soulsoftworks.sockbowlquestions.aikey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for saved user API keys. The master key comes from
 * {@code SOCKBOWL_AI_KEY_ENCRYPTION_KEY} (base64 of 32 random bytes, e.g.
 * {@code openssl rand -base64 32}); without it saved keys are disabled
 * ({@link #isEnabled()} is false) rather than stored in the clear.
 *
 * <p>The owner's Keycloak id is bound in as associated data, so a ciphertext
 * copied onto another user's node fails to decrypt.
 */
@Component
public class AiKeyCipher {
    private static final String VERSION_PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AiKeyCipher(@Value("${sockbowl.ai.user-keys.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SOCKBOWL_AI_KEY_ENCRYPTION_KEY is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "SOCKBOWL_AI_KEY_ENCRYPTION_KEY must decode to 32 bytes (openssl rand -base64 32)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isEnabled() {
        return key != null;
    }

    public String encrypt(String plaintext, String owner) {
        requireEnabled();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the API key", e);
        }
    }

    public String decrypt(String stored, String owner) {
        requireEnabled();
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("Unrecognized saved key format");
        }
        try {
            byte[] blob = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
            byte[] pt = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt the saved API key", e);
        }
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new SavedKeysDisabledException();
        }
    }
}
