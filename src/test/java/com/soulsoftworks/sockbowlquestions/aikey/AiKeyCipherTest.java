package com.soulsoftworks.sockbowlquestions.aikey;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiKeyCipherTest {
    private static final String MASTER = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsAndNeverStoresThePlaintext() {
        AiKeyCipher cipher = new AiKeyCipher(MASTER);
        String stored = cipher.encrypt("sk-ant-secret-1234", "user-a");

        assertThat(stored).startsWith("v1:").doesNotContain("sk-ant-secret-1234");
        assertThat(cipher.decrypt(stored, "user-a")).isEqualTo("sk-ant-secret-1234");
        // A fresh IV each time.
        assertThat(cipher.encrypt("sk-ant-secret-1234", "user-a")).isNotEqualTo(stored);
    }

    @Test
    void ciphertextIsBoundToItsOwner() {
        AiKeyCipher cipher = new AiKeyCipher(MASTER);
        String stored = cipher.encrypt("sk-ant-secret-1234", "user-a");

        assertThatThrownBy(() -> cipher.decrypt(stored, "user-b")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongMasterKeyCannotDecrypt() {
        String stored = new AiKeyCipher(MASTER).encrypt("sk-ant-secret-1234", "user-a");
        byte[] other = new byte[32];
        other[0] = 1;

        assertThatThrownBy(() -> new AiKeyCipher(Base64.getEncoder().encodeToString(other)).decrypt(stored, "user-a"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankMasterKeyDisablesTheFeature() {
        AiKeyCipher cipher = new AiKeyCipher("");

        assertThat(cipher.isEnabled()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x", "u")).isInstanceOf(SavedKeysDisabledException.class);
    }

    @Test
    void rejectsAMasterKeyOfTheWrongSize() {
        assertThatThrownBy(() -> new AiKeyCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new AiKeyCipher("not base64!"))
                .isInstanceOf(IllegalStateException.class);
    }
}
