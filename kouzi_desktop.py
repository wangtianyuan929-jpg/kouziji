#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扣字神器 V1 - 核心引擎与电脑端控制台
支持与安卓悬浮窗 App 共享相同的一套业务逻辑
"""

import os
import sys
import time
import json
import random
import threading
import urllib.request
import urllib.parse
from datetime import datetime

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

class DictManager:
    """词库系统"""
    def __init__(self, dict_dir="dicts"):
        self.dict_dir = dict_dir
        os.makedirs(self.dict_dir, exist_ok=True)

    def load_dict(self, file_path, deduplicate=False):
        """读取 TXT 词库（自动探测 UTF-8 / GBK 编码）"""
        if not os.path.exists(file_path):
            return []
        
        lines = []
        for encoding in ["utf-8", "gbk", "gb2312", "utf-16"]:
            try:
                with open(file_path, "r", encoding=encoding) as f:
                    raw_lines = [line.strip() for line in f if line.strip()]
                    if deduplicate:
                        seen = set()
                        lines = [x for x in raw_lines if not (x in seen or seen.add(x))]
                    else:
                        lines = raw_lines
                    break
            except Exception:
                continue
        return lines

    def ensure_default(self):
        default_file = os.path.join(self.dict_dir, "default.txt")
        if not os.path.exists(default_file):
            default_content = "\n".join([
                "扣字软件运行正常，请准备就绪",
                "打字速度决定输出效率，请开始你的表演",
                "顺风说骚话，逆风讲道理，这就是扣字艺术",
                "手速跟不上思维，请加大力度",
                "词库加载完毕，随时可以发起进攻",
                "精准定位目标，全自动节奏掌控",
                "节奏抖动已就绪，保持高频输出",
                "秒级自动撤回，不留一丝痕迹"
            ])
            with open(default_file, "w", encoding="utf-8") as f:
                f.write(default_content)
        return default_file


class OneBotClient:
    """OneBot 11 HTTP 通信客户端"""
    def __init__(self, host="127.0.0.1", port=3000, token=""):
        self.host = host
        self.port = port
        self.token = token

    def post(self, endpoint, payload):
        url = f"http://{self.host}:{self.port}/{endpoint}"
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                return res
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def get_login_info(self):
        return self.post("get_login_info", {})

    def send_group_msg(self, group_id, text, at_user_id=None):
        if at_user_id:
            msg = f"[CQ:at,qq={at_user_id}] {text}"
        else:
            msg = text
        res = self.post("send_group_msg", {"group_id": int(group_id), "message": msg})
        if res.get("status") in ["ok", "async"]:
            data = res.get("data")
            if isinstance(data, dict):
                return data.get("message_id")
            elif isinstance(data, int):
                return data
            return res.get("message_id", 0)
        return None

    def delete_msg(self, message_id):
        return self.post("delete_msg", {"message_id": int(message_id)})


class RecallManager:
    """高精度定时撤回管理器"""
    def __init__(self, client):
        self.client = client
        self.recalled_count = 0

    def schedule_recall(self, message_id, delay_seconds):
        if not message_id or delay_seconds <= 0:
            return
        
        def _task():
            time.sleep(delay_seconds)
            res = self.client.delete_msg(message_id)
            if res.get("status") == "ok":
                self.recalled_count += 1
                now = datetime.now().strftime("%H:%M:%S.%f")[:-3]
                print(f"[{now}] ✅ 消息已自动撤回 (msg_id: {message_id})")

        threading.Thread(target=_task, daemon=True).start()


class KouziEngine:
    """扣字核心调度引擎"""
    def __init__(self, client, recall_mgr):
        self.client = client
        self.recall_mgr = recall_mgr
        self.is_running = False
        self.is_paused = False
        self.thread = None
        self.sent_count = 0

    def start(self, lines, group_id, target_user_id=None, at_enabled=True,
              base_interval=3.0, jitter_enabled=True, jitter_range=0.5,
              auto_recall=False, recall_delay=2.0, count_limit=0, send_mode=0):
        if self.is_running:
            return
        self.is_running = True
        self.is_paused = False
        self.sent_count = 0

        def _loop():
            idx = 0
            order_indices = list(range(len(lines)))
            if send_mode == 1: # 随机不重复
                random.shuffle(order_indices)

            while self.is_running:
                if self.is_paused:
                    time.sleep(0.5)
                    continue

                if count_limit > 0 and self.sent_count >= count_limit:
                    print(f"\n🎉 已达到指定发送数量 ({count_limit} 条)，任务结束！")
                    self.stop()
                    break

                # 选句
                if send_mode == 0: # 顺序
                    line_idx = order_indices[idx % len(lines)]
                    idx += 1
                elif send_mode == 1: # 随机本轮不重
                    if idx >= len(order_indices):
                        random.shuffle(order_indices)
                        idx = 0
                    line_idx = order_indices[idx]
                    idx += 1
                else: # 完全随机
                    line_idx = random.randint(0, len(lines) - 1)

                text = lines[line_idx]

                # 计算间隔与抖动
                actual_interval = base_interval
                if jitter_enabled and jitter_range > 0:
                    actual_interval += random.uniform(-jitter_range, jitter_range)
                actual_interval = max(0.1, round(actual_interval, 2))

                # 发送
                at_id = target_user_id if at_enabled else None
                msg_id = self.client.send_group_msg(group_id, text, at_id)

                now = datetime.now().strftime("%H:%M:%S.%f")[:-3]
                if msg_id:
                    self.sent_count += 1
                    print(f"[{now}] 🚀 [已发:{self.sent_count}] (msg_id: {msg_id}) -> {text}")
                    if auto_recall and recall_delay > 0:
                        self.recall_mgr.schedule_recall(msg_id, recall_delay)
                else:
                    print(f"[{now}] ❌ 发送失败 -> {text}")

                time.sleep(actual_interval)

        self.thread = threading.Thread(target=_loop, daemon=True)
        self.thread.start()

    def pause(self):
        self.is_paused = True
        print("\n⏸️ 扣字任务已暂停")

    def resume(self):
        self.is_paused = False
        print("\n▶️ 扣字任务已继续")

    def stop(self):
        self.is_running = False
        self.is_paused = False
        print(f"\n⏹️ 扣字任务已停止 (本次总发送: {self.sent_count} 条)")


if __name__ == "__main__":
    print("=" * 60)
    print("⚡ 扣字神器 V1 电脑端与核心调试控制台 ⚡")
    print("=" * 60)
    
    dict_mgr = DictManager()
    default_dict_file = dict_mgr.ensure_default()
    lines = dict_mgr.load_dict(default_dict_file)

    client = OneBotClient(host="127.0.0.1", port=3000)
    recall_mgr = RecallManager(client)
    engine = KouziEngine(client, recall_mgr)

    login_res = client.get_login_info()
    if login_res.get("status") == "ok":
        data = login_res.get("data", {})
        print(f"🟢 NapCat 状态: 在线 | QQ: {data.get('nickname')} ({data.get('user_id')})")
    else:
        print("⚠️ NapCat 状态: 未连接 (请确认 NapCat 正在运行且 3000 端口已开启)")

    print(f"📚 词库: 默认词库 (共 {len(lines)} 句)")
    print("-" * 60)
    print("输入指令演示 / 快速上手：可在 Python 中直接调用 engine.start() 进行发送测试。")
