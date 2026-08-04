package com.devmind.security;

import com.devmind.config.DevMindProperties;
import com.devmind.config.DevMindSecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SecretCipherTest {

    @Mock
    private DevMindProperties properties;

    private SecretCipher cipher() {
        DevMindSecurityProperties security = new DevMindSecurityProperties(5, 10, 120, 10, 7, 2, "test-master-key");
        lenient().when(properties.storagePath()).thenReturn("./target/test-secrets");
        return new SecretCipher(security, properties);
    }

    @Test
    void encryptThenDecryptReturnsOriginal() {
        SecretCipher cipher = cipher();
        String plain = "sk-abcdef1234567890";
        String encrypted = cipher.encrypt(plain);

        assertThat(encrypted).startsWith(SecretCipher.ENC_PREFIX);
        assertThat(encrypted).doesNotContain(plain);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void samePlainProducesDifferentCiphertext() {
        SecretCipher cipher = cipher();
        String a = cipher.encrypt("secret");
        String b = cipher.encrypt("secret");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void resolvePassesThroughPlainValues() {
        SecretCipher cipher = cipher();
        assertThat(cipher.resolve("sk-plain-key")).isEqualTo("sk-plain-key");
        assertThat(cipher.resolve(null)).isNull();
        assertThat(cipher.resolve("")).isEmpty();
    }

    @Test
    void resolveDecryptsEncryptedValues() {
        SecretCipher cipher = cipher();
        String encrypted = cipher.encrypt("sk-secret-value");
        assertThat(cipher.resolve(encrypted)).isEqualTo("sk-secret-value");
    }
}
