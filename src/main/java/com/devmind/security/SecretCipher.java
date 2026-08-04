package com.devmind.security;

import com.devmind.config.DevMindProperties;
import com.devmind.config.DevMindSecurityProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 等敏感配置的加解密工具（AES/GCM）。
 *
 * 加密值形如 {@code enc:<base64(iv+密文)>}，配置中以 {@code enc:} 前缀标识，
 * 运行时通过 {@link #resolve(String)} 解密；非 {@code enc:} 前缀的值原样透传，
 * 保证未启用加密时行为不变。
 *
 * 主密钥来源优先级：环境变量/配置 {@code devmind.security.master-key}
 * （或 {@code DEVMIND_SECRET_MASTER_KEY}）> 本地自动生成并持久化的
 * {@code <storageParent>/secret.key}。
 */
@Component
public class SecretCipher {

    public static final String ENC_PREFIX = "enc:";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(DevMindSecurityProperties security, DevMindProperties properties) {
        String master = security.masterKey();
        if (master == null || master.isBlank()) {
            master = loadOrCreateLocalKey(Path.of(properties.storagePath()).getParent());
        }
        this.key = new SecretKeySpec(toKeyBytes(master), "AES");
    }

    public String encrypt(String plain) {
        byte[] iv = new byte[IV_LEN];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("敏感信息加密失败", e);
        }
    }

    public String decrypt(String encValue) {
        String data = encValue.startsWith(ENC_PREFIX)
                ? encValue.substring(ENC_PREFIX.length())
                : encValue;
        byte[] raw = Base64.getDecoder().decode(data);
        try {
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN));
            byte[] pt = cipher.doFinal(raw, IV_LEN, raw.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("敏感信息解密失败，请检查主密钥是否一致", e);
        }
    }

    /** 解密 {@code enc:} 前缀值；其他值原样返回（兼容未加密配置）。 */
    public String resolve(String raw) {
        if (raw == null || !raw.startsWith(ENC_PREFIX)) {
            return raw;
        }
        return decrypt(raw);
    }

    private byte[] toKeyBytes(String master) {
        if (master.startsWith("base64:")) {
            return Base64.getDecoder().decode(master.substring("base64:".length()));
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(master.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法派生加密密钥", e);
        }
    }

    private String loadOrCreateLocalKey(Path dir) {
        Path file = dir.resolve("secret.key");
        try {
            if (Files.exists(file)) {
                return Files.readString(file).trim();
            }
            Files.createDirectories(dir);
            byte[] keyBytes = new byte[32];
            random.nextBytes(keyBytes);
            String generated = "base64:" + Base64.getEncoder().encodeToString(keyBytes);
            Files.writeString(file, generated);
            return generated;
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化本地密钥文件", e);
        }
    }
}
