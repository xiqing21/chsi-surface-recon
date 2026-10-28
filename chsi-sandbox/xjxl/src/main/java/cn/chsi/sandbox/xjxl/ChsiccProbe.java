package cn.chsi.sandbox.xjxl;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试点 4 的离线分析工具。
 *
 * 用法：java -cp target/classes cn.chsi.sandbox.xjxl.ChsiccProbe [mode]
 * 不带参数则四种模式全跑。
 *
 * 回答三个问题：
 *   1. 是否 ECB（相同明文块 → 相同密文块）
 *   2. 明文长度与密文长度的关系（能否反推结构）
 *   3. 篡改后能否解密（有无完整性校验）
 */
public final class ChsiccProbe {

    private static final String KEY = "c2FuZGJveC1rZXktMzJieXRlcy1sb25nISE=";

    public static void main(String[] args) {
        String[] modes = args.length > 0
            ? args
            : new String[]{"ecb_nomac", "cbc_nomac", "cbc_mac", "gcm"};
        for (String m : modes) {
            probe(m);
        }
    }

    private static void probe(String mode) {
        System.out.println("\n=== mode=" + mode + " ===");
        ChsiccCookie c = new ChsiccCookie(mode, KEY);

        // 1. ECB 判据：构造 16 字节前缀完全相同的两个明文，只有后续块不同
        //    明文 = user|role|expire，用同样长度的 user 保证第 1 块一致
        String a = c.encrypt("userAAAAAAAAAAAA", "ROLE_USER", 1900000000L);
        String b = c.encrypt("userAAAAAAAAAAAA", "ROLE_ADMIN", 1900000000L);
        System.out.println("  sampleA = " + head(a));
        System.out.println("  sampleB = " + head(b));

        byte[] ra = Base64.getDecoder().decode(a.substring(1));
        byte[] rb = Base64.getDecoder().decode(b.substring(1));
        boolean firstBlockSame = ra.length >= 16 && rb.length >= 16;
        for (int i = 0; i < 16 && firstBlockSame; i++) {
            if (ra[i] != rb[i]) firstBlockSame = false;
        }
        System.out.printf("  cipher_len A=%d B=%d%n", ra.length, rb.length);
        System.out.println("  前缀相同样本的首块一致? " + firstBlockSame
            + "  → " + (firstBlockSame ? "ECB 特征（可块重排/可拼接）" : "非 ECB 或 CBC 链式"));

        // 2. 长度差分
        Map<Integer, Integer> lens = new LinkedHashMap<>();
        for (int n : new int[]{4, 8, 12, 16, 20}) {
            String s = c.encrypt("u".repeat(n), "ROLE_USER", 1900000000L);
            lens.put(n, Base64.getDecoder().decode(s.substring(1)).length);
        }
        System.out.println("  明文用户名长度 → 密文长度: " + lens);

        // 3. 篡改后解密
        byte[] raw = ra.clone();
        raw[0] ^= 0xFF;
        String tampered = "!" + Base64.getEncoder().encodeToString(raw);
        System.out.println("  篡改样本 = " + head(tampered));
        try {
            String out = c.decrypt(tampered);
            System.out.println("  篡改解密结果: " + out + "   ← 被接受（无完整性校验）");
        } catch (Exception e) {
            System.out.println("  篡改解密: 拒绝 (" + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ")  ← 有完整性校验");
        }

        // 4. 正常解密作对照
        try {
            System.out.println("  正常解密: " + c.decrypt(a));
        } catch (Exception e) {
            System.out.println("  正常解密失败: " + e.getMessage());
        }
    }

    private static String head(String s) {
        return s.substring(0, Math.min(56, s.length())) + (s.length() > 56 ? "..." : "");
    }
}
