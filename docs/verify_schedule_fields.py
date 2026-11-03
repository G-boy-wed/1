#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第 1 条字段核对：App 保存的每个字段 vs 服务端 /api/tour/schedule 落盘文件。

做法：
1. 起一个真实的服务端实例（独立 data 目录，不碰生产数据）；
2. 逐个字段改一个特征值，POST 过去；
3. 读回落盘文件 tour_schedule.json，逐字段比对；
4. 打印真实的对账表。

用法：python3 docs/verify_schedule_fields.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(REPO, "server", "linjian_server.py")
TOKEN = "field-check-token-9f2a7c"
PORT = int(os.environ.get("VERIFY_PORT", "8799"))

# App 端 PolicySettingsActivity.savePolicy() 组装的 body 字段（逐字段）
APP_FIELDS = [
    ("enabled", "policyEnabled（开启巡回查岗）", True),
    ("check_in_interval_minutes", "policyCheckInInput（查岗周期）", 15),
    ("force_back_interval_minutes", "policyForceBackInput（强制拉回周期）", 30),
    ("cooldown_minutes", "policyCooldownInput（冷却分钟）", 5),
    ("single_app_lock_minutes", "policyLockMinutes（单 App 锁阈值）", 0),
    ("single_app_lock_duration_minutes", "policyLockDuration（单 App 锁时长）", 5),
    ("force_back_miss_threshold", "policyMissThreshold（拉回分级阈值）", 70),
    ("alert_tone", "policyToneGentle/policyToneAngry（弹窗语气）", "gentle"),
    ("quiet_hours", "policyQuietStart/policyQuietEnd（安静时段）", None),
    ("night_hours", "policyNightStart/policyNightEnd（深夜时段）", {"start": "23:00", "end": "06:00"}),
    ("exempt_packages", "policyExemptPackages（豁免应用）", ["com.tencent.mm", "com.xingin.xhs"]),
]


def http(path, body=None):
    url = "http://127.0.0.1:%d%s" % (PORT, path)
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="GET" if body is None else "POST")
    req.add_header("X-Auth-Token", TOKEN)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    tmp = tempfile.mkdtemp(prefix="linjian_fieldcheck_")
    env = dict(os.environ)
    env.update({
        "LINJIAN_TOKEN": TOKEN,
        "LINJIAN_DATA_DIR": tmp,
        "LINJIAN_PORT": str(PORT),
        "LINJIAN_HOST": "127.0.0.1",
    })
    proc = subprocess.Popen([sys.executable, SERVER], env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    landed = os.path.join(tmp, "tour_schedule.json")
    try:
        for _ in range(40):
            try:
                http("/api/tour/state")
                break
            except Exception:
                time.sleep(0.25)
        else:
            print("服务端启动失败")
            return 1

        # 用 App 端真实 body 提交
        body = {k: v for k, _, v in APP_FIELDS}
        posted = http("/api/tour/schedule", body)
        time.sleep(0.4)

        if not os.path.exists(landed):
            print("落盘文件不存在：%s" % landed)
            return 1
        with open(landed, encoding="utf-8") as f:
            disk = json.load(f)

        print("=" * 96)
        print("第 1 条字段核对：App 保存字段  vs  服务端 /api/tour/schedule 落盘文件 tour_schedule.json")
        print("=" * 96)
        print("%-34s %-26s %-22s %-22s %s" % ("落盘字段", "App 来源控件", "App 提交值", "落盘实际值", "结论"))
        print("-" * 96)
        ok = 0
        bad = []
        for key, source, want in APP_FIELDS:
            got = disk.get(key)
            same = got == want
            if same:
                ok += 1
            else:
                bad.append(key)
            print("%-34s %-26s %-22s %-22s %s" % (
                key, source, json.dumps(want, ensure_ascii=False),
                json.dumps(got, ensure_ascii=False), "一致" if same else "不一致"))
        print("-" * 96)
        print("字段总数 %d，一致 %d，不一致 %d" % (len(APP_FIELDS), ok, len(bad)))
        if bad:
            print("不一致字段：%s" % ", ".join(bad))

        # 反向核对：GET 返回也要和落盘一致（手机端加载策略走的就是这个 GET）
        got_back = http("/api/tour/state")["schedule"]
        print("")
        print("反向核对：GET /api/tour/state 返回 vs 落盘文件")
        for key, _, want in APP_FIELDS:
            print("  %-34s GET=%-22s 落盘=%-22s %s" % (
                key, json.dumps(got_back.get(key), ensure_ascii=False),
                json.dumps(disk.get(key), ensure_ascii=False),
                "一致" if got_back.get(key) == disk.get(key) else "不一致"))

        print("")
        print("落盘文件原文（%s）：" % landed)
        print(json.dumps(disk, ensure_ascii=False, indent=2))
        return 0 if not bad else 1
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
