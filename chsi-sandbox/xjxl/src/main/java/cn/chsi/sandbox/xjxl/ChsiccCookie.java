package cn.chsi.sandbox.xjxl;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 复刻 CHSICC01 / CHSICC02 的加解密形态。
 *
 * 线上观测：值为 "!" + base64，随子系统下发（CHSICC01 → job，CHSICC02 → xjxl），
 * 格式一致 → 同一套公共组件。
 *
 * 测试点 4 要在沙箱里回答三个问题：
 *   1. 是否 ECB（相同明文块产生相同密文块）
 *   2. 有无完整性校验（单字节篡改是否被拒）
 *   3. 明文结构能否从长度差分反推
 *
 * 通过 SANDBOX_CIPHER_MODE 切换四种实现：
 *   ecb_nomac    ECB + 无 MAC        ← 最弱，块重排可行
 *   cbc_nomac    CBC + 无 MAC        ← 可字节翻转
 *   cbc_mac      CBC + HMAC-SHA256   ← 篡改被拒
 *   gcm          AES-GCM + 认证标签  ← 最严
 */
public final class ChsiccCookie {

    public static final String MAGIC = "!";
    private static final Pattern SHAPE = Pattern.compile("^![A-Za-z0-9+/=]+$");

    private final String mode;
    private final SecretKeySpec key;

    public ChsiccCookie(String mode, String base64Key) {
        this.mode = mode;
        byte[] raw = Base64.getDecoder().decode(base64Key);
        byte[] k = new byte[16];
        System.arraycopy(raw, 0, k, 0, Math.min(raw.length, 16));
        this.key = new SecretKeySpec(k, "AES");
    }

    public static String defaultMode() {
        return System.getenv().getOrDefault("SANDBOX_CIPHER_MODE", "ecb_nomac");
    }

    public static String defaultKey() {
        return System.getenv().getOrDefault(
            "SANDBOX_COOKIE_KEY", "c2FuZGJveC1rZXktMzJieXRlcy1sb25nISE=");
    }

    public static boolean looksLike(String v) {
        return v != null && SHAPE.matcher(v).matches();
    }

    /** 明文 = userId|role|expireEpoch */
    public String encrypt(String user, String role, long expireEpoch) {
        String plain = user + "|" + role + "|" + expireEpoch;
        try {
            byte[] out = switch (mode) {
                case "cbc_nomac" -> cbcEncrypt(plain.getBytes(StandardCharsets.UTF_8));
                case "cbc_mac"   -> withMac(cbcEncrypt(plain.getBytes(StandardCharsets.UTF_8)));
                case "gcm"       -> gcmEncrypt(plain.getBytes(StandardCharsets.UTF_8));
                default          -> ecbEncrypt(plain.getBytes(StandardCharsets.UTF_8));
            };
            return MAGIC + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(String cookie) throws Exception {
        if (!looksLike(cookie)) throw new IllegalArgumentException("bad shape");
        byte[] in = Base64.getDecoder().decode(cookie.substring(1));
        byte[] plain = switch (mode) {
            case "cbc_nomac" -> cbcDecrypt(in);
            case "cbc_mac"   -> cbcDecrypt(stripMac(in));
            case "gcm"       -> gcmDecrypt(in);
            default          -> ecbDecrypt(in);
        };
        return new String(plain, StandardCharsets.UTF_8);
    }

    // ---- ECB：块重排可行 ----
    private byte[] ecbEncrypt(byte[] p) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return c.doFinal(p);
    }
    private byte[] ecbDecrypt(byte[] in) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, key);
        return c.doFinal(in);
    }

    // ---- CBC：字节翻转可行 ----
    private static final byte[] IV = new byte[16];
    private byte[] cbcEncrypt(byte[] p) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.IvParameterSpec(IV));
        return c.doFinal(p);
    }
    private byte[] cbcDecrypt(byte[] in) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, key, new javax.crypto.spec.IvParameterSpec(IV));
        return c.doFinal(in);
    }

    // ---- MAC 包装 ----
    private byte[] withMac(byte[] ct) throws Exception {
        byte[] mac = hmac(ct, "MAC");
        byte[] out = new byte[ct.length + mac.length];
        System.arraycopy(ct, 0, out, 0, ct.length);
        System.arraycopy(mac, 0, out, ct.length, mac.length);
        return out;
    }
    private byte[] stripMac(byte[] in) throws Exception {
        int n = in.length - 32;
        if (n <= 0) throw new IllegalArgumentException("too short");
        byte[] ct = new byte[n];
        System.arraycopy(in, 0, ct, 0, n);
        byte[] want = hmac(ct, "MAC");
        byte[] got = new byte[32];
        System.arraycopy(in, n, got, 0, 32);
        if (!java.security.MessageDigest.isEqual(want, got))
            throw new SecurityException("MAC mismatch");
        return ct;
    }
    private byte[] hmac(byte[] data, String label) throws Exception {
        javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(base64KeyBytes(), "HmacSHA256"));
        return m.doFinal(data);
    }
    private byte[] base64KeyBytes() {
        String k = defaultKey();
        return Base64.getDecoder().decode(k);
    }

    // ---- GCM ----
    private byte[] gcmEncrypt(byte[] p) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, IV));
        return c.doFinal(p);
    }
    private byte[] gcmDecrypt(byte[] in) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, IV));
        return c.doFinal(in);
    }
}
