#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
亿级流量商城 测试套件（M1 + M1.5）
用法:
  python scripts/mall_bench.py smoke              # 全链路冒烟(注册→购物车→下单→支付→幂等→秒杀链路)
  python scripts/mall_bench.py bench              # 同步下单基线压测(QPS/P99)
  python scripts/mall_bench.py bench --mode seckill  # 秒杀压测(分桶预扣+MQ异步, 验证零超卖)
仅 Python 标准库。
"""
import argparse
import json
import random
import socket
import statistics
import threading
import time
from http.client import HTTPConnection

BASE_HOST = "127.0.0.1"
BASE_PORT = 9000  # M2: 经网关访问（单体时代为 8080）


class Client:
    def __init__(self, token=None):
        self.token = token
        self.conn = None

    def _connect(self):
        self.conn = HTTPConnection(BASE_HOST, BASE_PORT, timeout=15)

    def request(self, method, path, body=None, headers=None):
        for attempt in range(2):
            try:
                if self.conn is None:
                    self._connect()
                h = {"Content-Type": "application/json"}
                if self.token:
                    h["Authorization"] = "Bearer " + self.token
                if headers:
                    h.update(headers)
                payload = json.dumps(body) if body is not None else None
                self.conn.request(method, path, payload, h)
                resp = self.conn.getresponse()
                data = resp.read()
                return resp.status, json.loads(data.decode("utf-8"))
            except (ConnectionError, socket.timeout, OSError):
                self._connect()
                if attempt == 1:
                    raise
        raise RuntimeError("unreachable")

    def close(self):
        if self.conn:
            self.conn.close()


def register_and_login(c, prefix=138):
    phone = f"{prefix}%08d" % random.randint(0, 99999999)
    st, r = c.request("POST", "/api/auth/register",
                      {"phone": phone, "password": "Passw0rd", "smsCode": "123456"})
    assert st == 200 and r["code"] == 0, r
    st, r = c.request("POST", "/api/auth/login", {"phone": phone, "password": "Passw0rd"})
    assert r["code"] == 0, r
    c.token = r["data"]["token"]
    return phone


def smoke():
    print("=== 全链路冒烟 (M1 同步链路 + M1.5 秒杀链路) ===")
    c = Client()
    phone = register_and_login(c)
    print(f"[1] 注册+登录 OK ({phone})")

    st, r = c.request("POST", "/api/addresses", {
        "province": "广东省", "city": "深圳市", "detail": "南山区科技园1号",
        "receiver": "测试用户", "phone": phone, "isDefault": 1})
    assert r["code"] == 0, r
    address_id = r["data"]
    print(f"[2] 地址创建 OK id={address_id}")

    st, r = c.request("GET", "/api/products?pageNum=1&pageSize=5")
    assert r["code"] == 0 and r["data"]["records"], r
    pid = r["data"]["records"][0]["id"]
    stock0 = r["data"]["records"][0]["stock"]
    print(f"[3] 商品列表 OK 商品id={pid} 库存={stock0}")

    st, r = c.request("POST", "/api/cart", {"productId": pid, "quantity": 2})
    assert r["code"] == 0, r
    print(f"[4] 加购 OK")

    st, r = c.request("POST", "/api/orders",
                      {"addressId": address_id, "fromCart": True,
                       "items": [{"productId": pid, "quantity": 2}]})
    assert r["code"] == 0, r
    order_no = r["data"]["orderNo"]
    print(f"[5] 下单 OK orderNo={order_no}")

    st, r = c.request("POST", f"/api/payments/orders/{order_no}")
    assert r["code"] == 0, r
    payment_id = r["data"]["id"]
    st, r = c.request("POST", f"/api/payments/mock/notify/{payment_id}")
    assert r["code"] == 0 and r["data"]["duplicate"] is False, r
    st, r = c.request("POST", f"/api/payments/mock/notify/{payment_id}")
    assert r["code"] == 0 and r["data"]["duplicate"] is True, r
    print(f"[6] 支付回调 + 重复回调幂等 OK")

    # ---- M1.5 秒杀链路 ----
    st, r = c.request("POST", f"/api/seckill/products/{pid}/warmup/10")
    assert r["code"] == 0, r
    print(f"[7] 秒杀预热(10件分桶) OK")

    st, r = c.request("GET", "/api/seckill/token")
    assert r["code"] == 0, r
    idem = r["data"]
    st, r = c.request("POST", "/api/seckill/orders",
                      {"productId": pid, "addressId": address_id, "quantity": 1},
                      headers={"X-Idem-Token": idem})
    assert r["code"] == 0, r
    sk_order_no = r["data"]["orderNo"]
    print(f"[8] 秒杀受理 OK orderNo={sk_order_no}")

    # 重复提交(同一令牌)必须 409
    st, r = c.request("POST", "/api/seckill/orders",
                      {"productId": pid, "addressId": address_id, "quantity": 1},
                      headers={"X-Idem-Token": idem})
    assert r["code"] == 409, r
    print(f"[9] 幂等令牌防重放 OK (409)")

    # 再次购买须 DUP 拦截（限购1件）
    st, r = c.request("GET", "/api/seckill/token")
    idem2 = r["data"]
    st, r = c.request("POST", "/api/seckill/orders",
                      {"productId": pid, "addressId": address_id, "quantity": 1},
                      headers={"X-Idem-Token": idem2})
    assert r["code"] == 409, r
    print(f"[10] 限购拦截(一人一单) OK")

    # 轮询异步落库结果（MQ 有积压时可能要等几十秒）；M2.1 订单查询已归属 mall-order
    final = None
    for _ in range(150):
        st, r = c.request("GET", f"/api/orders/{sk_order_no}")
        assert r["code"] == 0, r
        status = r["data"]["order"]["status"]
        if status != "PROCESSING":
            final = dict(r["data"]["order"], status=status)
            break
        time.sleep(0.2)
    assert final and final["status"] == "UNPAID", f"秒杀单未落库: {final}"
    print(f"[11] MQ异步落库 OK status=UNPAID payAmount={final.get('payAmount')}")

    st, r = c.request("GET", f"/api/seckill/stock/{pid}")
    assert r["code"] == 0 and r["data"] == 9, r
    st, r = c.request("GET", f"/api/products/{pid}")
    db_stock = r["data"]["stock"]
    print(f"[12] 分桶余量=9 ✓  DB库存={db_stock}（已同步扣1）✓")
    c.close()
    print("=== 冒烟全部通过 ✓ ===")


def _pct(sorted_list, p):
    return sorted_list[min(int(len(sorted_list) * p), len(sorted_list) - 1)] if sorted_list else 0


def bench_sync(threads, total, product_id, qty):
    print(f"=== 同步下单基线压测: threads={threads} total={total} ===")
    setup = Client()
    phone = register_and_login(setup, 139)
    st, r = setup.request("POST", "/api/addresses", {
        "province": "广东省", "city": "深圳市", "detail": "压测专用",
        "receiver": "bench", "phone": phone, "isDefault": 1})
    address_id = r["data"]
    st, r = setup.request("GET", f"/api/products/{product_id}")
    stock_before = r["data"]["stock"]

    latencies, errors = [], {}
    success = 0
    lock = threading.Lock()
    per_thread = total // threads
    barrier = threading.Barrier(threads)

    def worker():
        nonlocal success
        c = Client(setup.token)
        barrier.wait()
        for _ in range(per_thread):
            t0 = time.perf_counter()
            try:
                st, r = c.request("POST", "/api/orders", {
                    "addressId": address_id, "fromCart": False,
                    "items": [{"productId": product_id, "quantity": qty}]})
                ms = (time.perf_counter() - t0) * 1000
                with lock:
                    latencies.append(ms)
                    if st == 200 and r["code"] == 0:
                        success += 1
                    else:
                        key = r.get("message", f"http{st}") if isinstance(r, dict) else f"http{st}"
                        errors[key] = errors.get(key, 0) + 1
            except Exception as e:
                with lock:
                    latencies.append((time.perf_counter() - t0) * 1000)
                    k = f"EXC:{type(e).__name__}"
                    errors[k] = errors.get(k, 0) + 1
        c.close()

    ts = [threading.Thread(target=worker) for _ in range(threads)]
    t0 = time.perf_counter()
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    elapsed = time.perf_counter() - t0
    latencies.sort()
    n = per_thread * threads
    print("\n========== 同步下单基线 ==========")
    print(f"总请求 {n}  成功 {success}  失败 {n - success}")
    print(f"耗时 {elapsed:.2f}s  QPS {n / elapsed:.1f}")
    if latencies:
        print(f"RT(ms) P50={_pct(latencies, .5):.1f} P95={_pct(latencies, .95):.1f} "
              f"P99={_pct(latencies, .99):.1f} avg={statistics.mean(latencies):.1f} max={latencies[-1]:.1f}")
    for k, v in sorted(errors.items(), key=lambda x: -x[1])[:5]:
        print(f"  失败[{k}]: {v}")
    st, r = setup.request("GET", f"/api/products/{product_id}")
    print(f"库存: {stock_before} -> {r['data']['stock']} (消耗 {stock_before - r['data']['stock']})")
    setup.close()


def bench_seckill(threads, total, product_id):
    """秒杀压测：多用户抢固定库存，验证 分桶预扣吞吐 + MQ 消化 + 零超卖。"""
    setup = Client()
    phone = register_and_login(setup, 137)
    st, r = setup.request("POST", "/api/addresses", {
        "province": "广东省", "city": "深圳市", "detail": "秒杀压测",
        "receiver": "sk", "phone": phone, "isDefault": 1})
    address_id = r["data"]

    SECKILL_STOCK = min(200, total // 2)
    st, r = setup.request("POST", f"/api/seckill/products/{product_id}/warmup/{SECKILL_STOCK}")
    assert r["code"] == 0, r
    st, r = setup.request("GET", f"/api/products/{product_id}")
    db_stock_before = r["data"]["stock"]
    print(f"=== 秒杀压测: threads={threads} 总抢购请求={total} 活动库存={SECKILL_STOCK} DB库存={db_stock_before} ===")

    # 预注册 threads 个用户（一人一单），每人建自己的地址（消息携带本人地址，归属校验才能通过）
    workers_cfg = []
    for _ in range(threads):
        c = Client()
        phone = register_and_login(c, 136)
        st, r = c.request("POST", "/api/addresses", {
            "province": "广东省", "city": "深圳市", "detail": "秒杀压测",
            "receiver": "sk", "phone": phone, "isDefault": 1})
        assert r["code"] == 0, r
        workers_cfg.append((c.token, r["data"]))
    print(f"预注册 {len(workers_cfg)} 个秒杀用户(各自带地址) OK")

    latencies, outcomes = [], {}
    accepted = []
    lock = threading.Lock()
    per_thread = total // threads
    barrier = threading.Barrier(threads)

    def worker(idx):
        token, my_address_id = workers_cfg[idx]
        c = Client(token)
        barrier.wait()
        for i in range(per_thread):
            t0 = time.perf_counter()
            try:
                st, r = c.request("GET", "/api/seckill/token")
                if r["code"] != 0:
                    with lock:
                        outcomes["token_fail"] = outcomes.get("token_fail", 0) + 1
                    continue
                idem = r["data"]
                st, r = c.request("POST", "/api/seckill/orders",
                                  {"productId": product_id, "addressId": my_address_id, "quantity": 1},
                                  headers={"X-Idem-Token": idem})
                ms = (time.perf_counter() - t0) * 1000
                with lock:
                    latencies.append(ms)
                    if st == 200 and r["code"] == 0:
                        outcomes["受理成功"] = outcomes.get("受理成功", 0) + 1
                        accepted.append((token, r["data"]["orderNo"]))
                    else:
                        key = r.get("message", f"http{st}") if isinstance(r, dict) else f"http{st}"
                        outcomes[key] = outcomes.get(key, 0) + 1
            except Exception as e:
                with lock:
                    latencies.append((time.perf_counter() - t0) * 1000)
                    k = f"EXC:{type(e).__name__}"
                    outcomes[k] = outcomes.get(k, 0) + 1
        c.close()

    ts = [threading.Thread(target=worker, args=(i,)) for i in range(threads)]
    t0 = time.perf_counter()
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    elapsed = time.perf_counter() - t0
    latencies.sort()
    n = per_thread * threads

    # 等待 MQ 消化完
    print(f"\n请求期结束 {elapsed:.2f}s，等待 MQ 消费落库...")
    target = outcomes.get("受理成功", 0)
    deadline = time.time() + 60
    while time.time() < deadline:
        st, r = setup.request("GET", f"/api/seckill/stock/{product_id}")
        remain = r["data"]
        st2, r2 = setup.request("GET", f"/api/products/{product_id}")
        db_now = r2["data"]["stock"]
        if remain == 0 and db_stock_before - db_now >= target:
            break
        time.sleep(1)
    st, r = setup.request("GET", f"/api/seckill/stock/{product_id}")
    remain = r["data"]
    st, r = setup.request("GET", f"/api/products/{product_id}")
    db_after = r["data"]["stock"]
    db_consumed = db_stock_before - db_after

    print("\n========== 秒杀压测报告 ==========")
    print(f"抢购请求 {n} 个 / {threads} 用户，受理 {target} 个")
    print(f"受理吞吐: {target / elapsed:.1f} TPS（含令牌获取两跳）")
    if latencies:
        print(f"RT(ms) P50={_pct(latencies, .5):.1f} P95={_pct(latencies, .95):.1f} P99={_pct(latencies, .99):.1f}")
    print("结果分布:")
    for k, v in sorted(outcomes.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v}")
    print(f"\n--- 一致性核对 ---")
    print(f"Redis 分桶余量: {remain} (应为 {SECKILL_STOCK - target})")
    print(f"DB 库存消耗: {db_consumed} (受理 {target})")
    ok = remain == SECKILL_STOCK - target and db_consumed == target
    print(f"零超卖判定: {'✓ 通过' if ok else '✗ 失败'}")
    setup.close()
    return ok


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["smoke", "bench"])
    parser.add_argument("--threads", type=int, default=50)
    parser.add_argument("--total", type=int, default=500)
    parser.add_argument("--product-id", type=int, default=1)
    parser.add_argument("--qty", type=int, default=1)
    parser.add_argument("--bench-mode", choices=["sync", "seckill"], default="sync")
    args = parser.parse_args()
    if args.mode == "smoke":
        smoke()
    else:
        if args.bench_mode == "seckill":
            ok = bench_seckill(args.threads, args.total, args.product_id)
            raise SystemExit(0 if ok else 1)
        else:
            bench_sync(args.threads, args.total, args.product_id, args.qty)
