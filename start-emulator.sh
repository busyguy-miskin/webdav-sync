#!/usr/bin/env bash
# =============================================================================
#  启动 Android 模拟器(manga_test),并在 adb 中等待它完成引导。
#
#  为什么是这个配置(都是踩坑后验证过的):
#   - -gpu swiftshader_indirect : 本机是 AMD 集显 + NVIDIA 双 GPU,host GPU 模式下
#                                 模拟器窗口创建后进程会无声崩溃(日志无错误)。
#                                 软件渲染稳定,代价是 System UI 偶尔弹"无响应",
#                                 点 Wait 等几秒即可。
#   - -no-snapshot              : 曾遇到加载损坏快照卡死在 crashpad 阶段、永不注册
#                                 到 adb 的情况。强制冷启动规避。
#   - setsid + 重定向 + </dev/null : 完全脱离调用方终端。曾出现过"等待引导的长命令
#                                 被取消时,把模拟器子进程一起带走"的事故。
#   - 不用 `adb wait-for-device` 长挂 : 改成短轮询,每次立即返回,命令本身不会被
#                                 长时间占用,也不会拖垮模拟器。
#
#  用法:
#    ./start-emulator.sh              # 默认 AVD: manga_test
#    ./start-emulator.sh <avd名>      # 指定其他 AVD
#    AVD=manga_test ./start-emulator.sh
#
#  退出码:0=已开机并完成引导;非 0=启动失败或引导超时。
# =============================================================================
set -euo pipefail

# ---- 可配置项(环境变量优先)-------------------------------------------------
AVD="${1:-${AVD:-manga_test}}"
ANDROID_SDK="${ANDROID_SDK:-/home/miskin/Android/Sdk}"
EMULATOR_BIN="${ANDROID_SDK}/emulator/emulator"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
# 冷启动 + 软件渲染,引导总时长给宽点
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"   # 秒,轮询 boot_completed 的最长等待
REG_TIMEOUT="${REG_TIMEOUT:-60}"      # 秒,等待设备注册到 adb 的最长等待

# ---- 函数定义(必须在主流程调用之前)---------------------------------------
# 等待指定 serial 的设备变 device 并完成引导(boot_completed=1)。
# 用短轮询,每次 adb 查询立即返回,避免长命令被取消时连累模拟器进程。
wait_boot() {
    local serial="$1"
    local deadline=$(( $(date +%s) + BOOT_TIMEOUT ))
    # 先等设备变 device(可能短暂 offline)
    while [ "$(date +%s)" -le "$deadline" ]; do
        local state="$("$ADB_BIN" -s "$serial" get-state 2>/dev/null || true)"
        [ "$state" = "device" ] && break
        sleep 3
    done
    # 再轮询 boot_completed
    while [ "$(date +%s)" -le "$deadline" ]; do
        local bc
        bc="$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
        [ "$bc" = "1" ] && return 0
        sleep 5
    done
    return 1
}

# ---- 前置检查 --------------------------------------------------------------
[ -x "$EMULATOR_BIN" ] || { echo "✗ 找不到 emulator:$EMULATOR_BIN" >&2; exit 1; }
[ -x "$ADB_BIN" ]      || { echo "✗ 找不到 adb:$ADB_BIN" >&2; exit 1; }

# ---- 若已有同 AVD 的模拟器在跑,直接复用 -----------------------------------
EXISTING="$("$ADB_BIN" devices | awk '/emulator-[0-9]+\t(device|offline)/{print $1; exit}')"
if [ -n "$EXISTING" ]; then
    echo "→ 已有模拟器在线:$EXISTING,等待它完成引导(复用,不重复启动)..."
    if wait_boot "$EXISTING"; then
        echo "✓ $EXISTING 已就绪(boot_completed=1)"
        exit 0
    fi
    echo "→ 在线模拟器引导异常,关闭后重新冷启动。"
    "$ADB_BIN" -s "$EXISTING" emu kill 2>/dev/null || true
    sleep 3
fi

# ---- 启动 ------------------------------------------------------------------
LOG="/tmp/emulator_${AVD}.log"
: > "$LOG"
echo "→ 冷启动 AVD「$AVD」(swiftshader 软件渲染,GUI 窗口),日志:$LOG"
# setsid:新会话,脱离控制终端;stdio 全重定向;后台运行
setsid "$EMULATOR_BIN" \
    -avd "$AVD" \
    -no-snapshot \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    >>"$LOG" 2>&1 </dev/null &
EMU_LAUNCH_PID=$!
disown 2>/dev/null || true
echo "→ 启动器 PID=$EMU_LAUNCH_PID"

# ---- 等待设备注册到 adb ----------------------------------------------------
SERIAL=""
DEADLINE=$(( $(date +%s) + REG_TIMEOUT ))
while [ "$(date +%s)" -le "$DEADLINE" ]; do
    SERIAL="$("$ADB_BIN" devices | awk '/emulator-[0-9]+\t(device|offline)/{print $1; exit}')"
    [ -n "$SERIAL" ] && break
    sleep 2
done
if [ -z "$SERIAL" ]; then
    echo "✗ ${REG_TIMEOUT}s 内模拟器未注册到 adb。日志末尾:" >&2
    tail -n 20 "$LOG" >&2
    exit 2
fi
echo "→ 已注册:$SERIAL(offlin==device 状态皆可,继续等引导)"

# ---- 等待引导完成 ----------------------------------------------------------
if wait_boot "$SERIAL"; then
    echo "✓ 模拟器就绪:$SERIAL (boot_completed=1)"
    echo "  adb -s $SERIAL shell ..."
    exit 0
else
    echo "✗ 引导超时(${BOOT_TIMEOUT}s)。日志末尾:" >&2
    tail -n 30 "$LOG" >&2
    exit 3
fi
