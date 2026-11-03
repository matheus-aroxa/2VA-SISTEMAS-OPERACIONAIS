package fsim;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Criptografia simétrica de dados em repouso (encryption at rest) para blocos do sistema de arquivos.
 *
 * Cifra o conteúdo dos arquivos antes da gravação nos blocos físicos usando AES-256 em modo contador
 * (`AES/CTR/NoPadding`), que preserva o tamanho exato em bytes sem padding e exige um vetor de inicialização (IV)
 * aleatório de 16 bytes a cada escrita. Um código de autenticação `HMAC-SHA256` sobre o IV com subchave independente
 * permite rejeitar chaves incorretas antes da leitura do texto plano.
 */
final class CryptoEngine {
    private static final int IV_BYTES = 16;
    private static final int PREVIEW_BYTES = 8;
    private static final String CIPHER_ALGO = "AES/CTR/NoPadding";
    private static final String MAC_ALGO = "HmacSHA256";
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    record Meta(byte[] iv, byte[] verifier) {
        Meta {
            if (iv == null || iv.length != IV_BYTES || verifier == null) {
                throw new FSException("metadados de criptografia inválidos");
            }
            iv = iv.clone();
            verifier = verifier.clone();
        }
    }

    record Encrypted(byte[] cipher, Meta meta) {
    }

    private CryptoEngine() {
    }

    /** Cifra `plain` com `AES/CTR/NoPadding` (256 bits) e retorna o texto cifrado e os metadados de verificação. */
    static Encrypted encrypt(byte[] plain, String key) {
        validateKey(key);
        byte[] input = plain == null ? new byte[0] : plain;
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);

        byte[] aesKey = deriveKey("AES:", key);
        byte[] macKey = deriveKey("MAC:", key);
        byte[] verifier = computeHmac(macKey, iv);
        byte[] cipher = transform(Cipher.ENCRYPT_MODE, aesKey, iv, input);
        return new Encrypted(cipher, new Meta(iv, verifier));
    }

    /** Valida a chave via HMAC sobre `meta.iv()` e decifra `cipher` com `AES/CTR/NoPadding`. */
    static byte[] decrypt(byte[] cipher, String key, Meta meta) {
        validateKey(key);
        if (meta == null) {
            throw new FSException("metadados de criptografia ausentes para descriptografar o arquivo");
        }
        byte[] macKey = deriveKey("MAC:", key);
        byte[] expectedVerifier = computeHmac(macKey, meta.iv());
        if (!MessageDigest.isEqual(meta.verifier(), expectedVerifier)) {
            throw new FSException("chave incorreta: não foi possível descriptografar o arquivo");
        }
        byte[] aesKey = deriveKey("AES:", key);
        byte[] input = cipher == null ? new byte[0] : cipher;
        return transform(Cipher.DECRYPT_MODE, aesKey, meta.iv(), input);
    }

    /** Formata uma amostra hexadecimal maiúscula dos primeiros bytes cifrados (ex.: `0x8F4A12B0...`). */
    static String hexPreview(byte[] cipher) {
        byte[] data = cipher == null ? new byte[0] : cipher;
        int limit = Math.min(data.length, PREVIEW_BYTES);
        StringBuilder sb = new StringBuilder(2 + limit * 2 + 28);
        sb.append("0x");
        for (int i = 0; i < limit; i++) {
            int v = data[i] & 0xFF;
            sb.append(HEX_UPPER[v >>> 4]);
            sb.append(HEX_UPPER[v & 0x0F]);
        }
        if (data.length > PREVIEW_BYTES) {
            sb.append("...");
        }
        sb.append(" (Encrypted Ciphertext)");
        return sb.toString();
    }

    // -------------------------------------------------------------- internos

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new FSException("a chave de criptografia não pode ser vazia");
        }
    }

    private static byte[] deriveKey(String domainPrefix, String key) {
        // deriva subchave de 256 bits (32 bytes): SHA-256(prefixo || chave_utf8)
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest((domainPrefix + key).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("algoritmo SHA-256 indisponível", e);
        }
    }

    private static byte[] computeHmac(byte[] macKey, byte[] data) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGO);
            mac.init(new SecretKeySpec(macKey, MAC_ALGO));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha ao calcular HMAC-SHA256", e);
        }
    }

    private static byte[] transform(int opmode, byte[] aesKey, byte[] iv, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(opmode, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha na operação AES/CTR/NoPadding", e);
        }
    }
}
