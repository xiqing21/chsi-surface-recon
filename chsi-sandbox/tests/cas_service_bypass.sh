#!/usr/bin/env bash
# 测试点 1：CAS service 参数白名单绕过
# 用法：./cas_service_bypass.sh https://localhost:8443/cas
# 前提：CAS Server 已启动，且 cas/services/ 下已加载白名单
set -u
CAS="${1:-https://localhost:8443/cas}"
OUT="${2:-../data/cas-bypass-result.txt}"

# 白名单是 *.chsi.com.cn，下面每条都是"逃出白名单"的经典变形
PAYLOADS=(
  "https://portal.chsi.com.cn/archive/j_spring_cas_security_check"
  "https://portal.chsi.com.cn@evil.example/"
  "https://portal.chsi.com.cn.evil.example/"
  "https://portal.chsi.com.cn/../..//evil.example/"
  "https://portal.chsi.com.cn%2F@evil.example/"
  "https://portal.chsi.com.cn%00.evil.example/"
  "HTTPS://PORTAL.CHSI.COM.CN/"
  "https://evil.example/?x=.chsi.com.cn"
  "https://evil.example#.chsi.com.cn"
  "https://portal.chsi.com.cn\\@evil.example/"
  "https://portal.chsi.com.cn;jsessionid=ATTACKER_SID"
  "//evil.example/"
)

: > "$OUT"
printf "%-62s %-8s %s\n" "SERVICE" "STATUS" "LOCATION" | tee -a "$OUT"

for p in "${PAYLOADS[@]}"; do
  # URL 编码 service 参数
  enc=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$p")
  resp=$(curl -sS -k -o /dev/null -D - --max-time 10 "$CAS/login?service=$enc" 2>/dev/null)
  code=$(printf '%s' "$resp" | head -1 | awk '{print $2}')
  loc=$(printf '%s' "$resp" | grep -i '^location:' | tr -d '\r' | sed 's/^[Ll]ocation: //')
  printf "%-62s %-8s %s\n" "$(echo "$p" | cut -c1-62)" "${code:-ERR}" "${loc:0:90}" | tee -a "$OUT"
done

echo
echo "'服务未认证/需登录' 且 Location 指向 evil.example 的 → 白名单被绕过"
echo "结果已写入 $OUT"
