#!/bin/bash
# 唤醒词 / 功耗测试助手
# Override if you have more than one device attached (e.g. wireless adb uses a different serial).
export ANDROID_SERIAL=${ANDROID_SERIAL:-HA2FANPD}
PKG=com.desmond.gptwake
OUT=$(cd "$(dirname "$0")" && pwd)/measurements
B() { adb shell am broadcast -a com.desmond.gptwake.CTRL --es cmd "$@" -p $PKG >/dev/null 2>&1; }
S() { adb shell sleep "$1"; }

case "$1" in
  arm)
    adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1; S 3
    adb shell input keyevent 3
    B shim_fgs; S 8
    B eval_mode --ez on true
    B mic_only --ez on false
    B reset_counters
    adb logcat -c
    adb shell input keyevent 223
    echo "评估模式 + 熄屏锁屏就绪。命中不会启动 ChatGPT。"
    ;;

  recall)   # ./testkit.sh recall near 10   |   ./testkit.sh recall far3m 10
    DIST=${2:-near}; N=${3:-10}
    echo "=== 召回测试 距离=$DIST 共 $N 次 ==="
    echo "每次震动一下之后，用正常音量说一次「芝麻开门」，不要刻意喊。"
    echo "长震动=命中，短震动=未命中。"
    S 3
    for i in $(seq 1 $N); do
      printf "  第 %2d/%d 次 ... " "$i" "$N"
      B trial_begin --es distance "$DIST" --ei index "$i"
      S 5
      B trial_end
      S 1
      adb logcat -d -s GPTWAKE | grep 'TRIAL_END' | tail -1 | sed 's/.*TRIAL_END //'
      S 3
    done
    echo "=== 汇总 ==="
    adb logcat -d -s GPTWAKE | grep TRIAL_END | grep -c 'hit=true' | sed 's/^/  命中: /'
    adb logcat -d -s GPTWAKE | grep TRIAL_END | grep -c 'hit=false' | sed 's/^/  漏检: /'
    adb logcat -d -s GPTWAKE | grep -c KWS_HIT_SUPPRESSED | sed 's/^/  重复抑制: /'
    B counters; S 2
    adb logcat -d -s GPTWAKE | grep 'KWS_COUNTERS ' | tail -1
    ;;

  trial)    B trial_begin --es distance "${2:-near}" --ei index "${3:-0}"; S 5; B trial_end; S 1
            adb logcat -d -s GPTWAKE | grep TRIAL_END | tail -1 ;;
  hits)     adb logcat -d -s GPTWAKE | grep -E 'TRIAL_|KWS_HIT|KWS_EVAL_HIT|KWS_RESUMED'
            B counters; S 2; adb logcat -d -s GPTWAKE | grep 'KWS_COUNTERS ' | tail -1 ;;
  stats)    adb logcat -d -s GPTWAKE | grep -E 'KWS_STATS|POWER ' | tail -6 ;;
  state)    B wake_state; S 2; adb logcat -d -s GPTWAKE | grep WAKE_STATE | tail -1 ;;
  reset)    B reset_counters; adb logcat -c; echo "计数与 logcat 已清零" ;;
  live)     adb logcat -s GPTWAKE ;;
  eval-off) B eval_mode --ez on false; echo "评估模式关闭，命中将真正启动 ChatGPT" ;;
  threshold) B kws_params --ef threshold "$2"; B micfgs_stop; S 2; B shim_fgs; S 8
             adb logcat -d -s GPTWAKE | grep KWS_MODEL_LOAD_BEGIN | tail -1 ;;

  # ---- ABBA 功耗测试。每段 = 10 min 预热 + 30 min 记录 ----
  power)    # ./testkit.sh power A1 | B1 | B2 | A2
    ID=$2
    case "$ID" in
      A1|A2) B mic_only --ez on true  ;;
      B1|B2) B mic_only --ez on false ;;
      *) echo "用法: $0 power {A1|B1|B2|A2}"; exit 1 ;;
    esac
    B eval_mode --ez on true
    mkdir -p $OUT
    adb shell dumpsys meminfo -d $PKG > $OUT/meminfo-$ID-start.txt
    adb shell dumpsys batterystats --reset >/dev/null 2>&1
    adb shell input keyevent 223
    echo "[$ID] 预热 10 分钟（不记录）..."
    S 600
    B run_start --es id "$ID" --ei interval 10
    echo "[$ID] 正式记录 30 分钟开始。"
    echo ">>> 现在拔掉 USB 线。40 分钟后再插回来，然后执行: ./testkit.sh powerstop $ID"
    ;;
  powerstop)
    ID=${2:-run}
    mkdir -p $OUT
    B run_end --es reason completed; S 3
    adb shell dumpsys meminfo -d $PKG > $OUT/meminfo-$ID-end.txt
    adb shell dumpsys batterystats > $OUT/batterystats-$ID.txt
    B measure_dir; S 2
    adb logcat -d -s GPTWAKE | grep -E 'MEASURE_FILE|RUN_END' | tail -10
    echo "--- 拉取 JSONL ---"
    adb shell "run-as $PKG ls /data/user_de/0/$PKG/files/measurements" 2>/dev/null || \
      adb shell ls /data/user_de/0/$PKG/files/measurements 2>/dev/null
    ;;
  pull)
    mkdir -p $OUT
    for f in $(adb shell "run-as $PKG ls /data/user_de/0/$PKG/files/measurements" 2>/dev/null | tr -d '\r'); do
      adb shell "run-as $PKG cat /data/user_de/0/$PKG/files/measurements/$f" > "$OUT/$f" 2>/dev/null
      echo "  $f  $(wc -l < "$OUT/$f") 行"
    done
    echo "已拉到 $OUT"
    ;;
  meminfo)  adb shell dumpsys meminfo -d $PKG | grep -E 'TOTAL PSS|TOTAL RSS|Native Heap|EGL|GL mtrack|Private Dirty' ;;
  *) echo "用法: $0 {arm|recall <dist> <n>|trial|hits|stats|state|reset|live|eval-off|threshold <v>|power <A1|B1|B2|A2>|powerstop <id>|pull|meminfo}" ;;
esac
