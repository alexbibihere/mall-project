#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""多级缓存效果验证：商品详情接口（Caffeine L1 → Redis L2 → DB）热读 QPS。"""
import json
import statistics
import sys
import threading
import time
from http.client import HTTPConnection

HOST, PORT = "127.0.0.1", 8080


def run(threads, total, product_id):
    lat = []
    lock = threading.Lock()
    per = total // threads
    barrier = threading.Barrier(threads)

    def worker():
        conn = HTTPConnection(HOST, PORT, timeout=10)
        barrier.wait()
        for _ in range(per):
            t0 = time.perf_counter()
            conn.request("GET", f"/api/products/{product_id}")
            conn.getresponse().read()
            with lock:
                lat.append((time.perf_counter() - t0) * 1000)
        conn.close()

    ts = [threading.Thread(target=worker) for _ in range(threads)]
    t0 = time.perf_counter()
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    elapsed = time.perf_counter() - t0
    lat.sort()
    n = per * threads
    p = lambda q: lat[min(int(n * q), n - 1)]
    print(f"详情接口热读: {n} 请求 / {threads} 线程")
    print(f"QPS={n / elapsed:.0f}  P50={p(.5):.2f}ms  P95={p(.95):.2f}ms  P99={p(.99):.2f}ms  max={lat[-1]:.2f}ms")
    return n / elapsed


if __name__ == "__main__":
    threads = int(sys.argv[1]) if len(sys.argv) > 1 else 50
    total = int(sys.argv[2]) if len(sys.argv) > 2 else 20000
    pid = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    run(threads, total, pid)
