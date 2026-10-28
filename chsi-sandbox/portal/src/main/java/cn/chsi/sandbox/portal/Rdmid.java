package cn.chsi.sandbox.portal;

import java.security.SecureRandom;
import java.util.HexFormat;

/** rdmid 生成器。复刻线上：24 位小写字母数字，会话内不变。 */
public final class Rdmid {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RND = new SecureRandom();

    private Rdmid() {}

    public static String generate() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(ALPHABET.charAt(RND.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 沙箱专用：验证码真值用固定种子的可复现算法，方便测试矩阵。 */
    public static String captchaAnswer(String rdmid, long epoch) {
        // 每 60 秒轮换一道题，答案可由 (rdmid, 时间窗) 复现
        long slot = epoch / 60;
        int h = (rdmid + ":" + slot).hashCode();
        int v = Math.abs(h) % 1000000;
        return String.format("%06d", v);
    }
}
