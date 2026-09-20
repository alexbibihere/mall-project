# -*- coding: utf-8 -*-
"""商城全页面仿真数据灌入脚本"""
import pymysql, hashlib, random, json, time
from datetime import datetime, timedelta

random.seed(20260921)
conn = pymysql.connect(host='localhost', user='root', password='root', charset='utf8mb4', autocommit=False)
cur = conn.cursor()
NOW = datetime.now()

# ============ 1. 商品：补 96 个（总数 100）============
CATS = {
    '数码': ['无线蓝牙耳机', '智能手环', '机械键盘', '4K显示器', '手机支架', '充电宝', '蓝牙音箱', '电竞鼠标', 'USB-C扩展坞', '行车记录仪', '降噪耳机', '平板电脑'],
    '家居': ['经典马克杯', '香薰蜡烛', '记忆棉枕头', '收纳箱', '落地灯', '陶瓷餐具套装', '加湿器', '懒人沙发', '遮光窗帘', '电热毯', '保温杯', '置物架'],
    '服饰': ['纯棉T恤 基础款', '连帽卫衣', '牛仔裤', '轻薄羽绒服', '运动短裤', '针织开衫', '白衬衫', '休闲西裤', '棒球帽', '羊毛围巾', '帆布鞋', '雪地靴'],
    '美妆': ['氨基酸洁面乳', '保湿精华液', '防晒霜SPF50', '口红礼盒', '香水50ml', '面膜10片装', '眼霜', '散粉', '洗发水', '身体乳', '眉笔', '卸妆水'],
    '食品': ['坚果大礼包', '龙井茶叶250g', '巧克力礼盒', '螺蛳粉3连包', '每日坚果', '牛肉干', '进口红酒', '挂耳咖啡30杯', '海苔脆片', '芒果干', '燕麦片', '蜂蜜500g'],
    '运动': ['瑜伽垫', '跳绳', '哑铃5kg', '篮球', '羽毛球拍', '泳镜', '运动水壶', '护膝', '跑步袜', '筋膜枪', '乒乓球拍', '骑行手套'],
    '图书': ['Java并发编程', '深入理解JVM', 'Redis设计与实现', 'MySQL必知必会', '算法图解', 'Spring实战', '分布式系统原理', '计算机网络自顶向下', 'Clean Code', '设计模式', 'Linux命令行', 'Machine Learning入门'],
    '母婴': ['婴儿纸尿裤', '儿童绘本套装', '益智积木', '婴儿推车', '儿童保温杯', '辅食机', '孕妇奶粉', '儿童牙刷', '玩具汽车', '婴儿洗护套装', '儿童雨伞', '学步鞋'],
}
BRANDS = ['MallSelf', '优选严选', '极客工坊', '轻奢志']
cats, names, pids = [], [], []
cur.execute("SELECT COUNT(*) FROM mall.product"); base = cur.fetchone()[0]
n = 0
for cat, items in CATS.items():
    for nm in items:
        if n >= 96: break
        n += 1
        brand = random.choice(BRANDS)
        price = round(random.uniform(9.9, 2999), 2)
        stock = random.choice([random.randint(50, 500), random.randint(5000, 50000), random.randint(50000, 200000)])
        cur.execute("INSERT INTO mall.product(name,category,brand,price,stock,created_at,updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s)",
                    (nm, cat, brand, price, stock, NOW, NOW))
        pids.append(cur.lastrowid)
conn.commit()
cur.execute("SELECT COUNT(*) FROM mall.product"); print("商品总数:", cur.fetchone()[0])

# 商品快照(供订单引用)
cur.execute("SELECT id,name,price FROM mall.product")
PROD = {r[0]: (r[1], float(r[2])) for r in cur.fetchall()}
ALL_PIDS = list(PROD.keys())

# ============ 2. 用户：补 50 个（可登录，密码 Test1234）============
pwd = hashlib.sha256('Test1234'.encode()).hexdigest()
new_uids = []
for i in range(1, 51):
    phone = f"137{random.randint(10000000,99999999)}"
    cur.execute("SELECT id FROM mall.users WHERE phone=%s", (phone,))
    r = cur.fetchone()
    if r: new_uids.append(r[0]); continue
    cur.execute("INSERT INTO mall.users(phone,password_hash,nick_name,created_at) VALUES (%s,%s,%s,%s)",
                (phone, pwd, f"体验官{i:03d}", NOW - timedelta(days=random.randint(1, 60))))
    new_uids.append(cur.lastrowid)
conn.commit()
print("新增用户:", len(new_uids), "示例:", new_uids[:3])

# ============ 3. 地址：每个新用户 2~3 条 ============
PROVS = [('广东省','深圳市','南山区'), ('广东省','深圳市','福田区'), ('浙江省','杭州市','西湖区'), ('北京市','北京市','朝阳区'), ('上海市','上海市','浦东新区'), ('四川省','成都市','武侯区')]
addr_cnt = 0
for uid in new_uids:
    for _ in range(random.randint(2, 3)):
        p, c, d = random.choice(PROVS)
        cur.execute("INSERT INTO mall.address(user_id,province,city,detail,receiver,phone,is_default,deleted,created_at,updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,0,%s,%s)",
                    (uid, p, c, f'{d}{random.choice(["科技园","幸福小区","创业大厦","中心公园"])}{random.randint(1,99)}号', random.choice(['张先生','李女士','王同学','刘工','陈小姐']), f"13{random.randint(100000000,999999999)}", random.choice([0,1]), NOW, NOW))
        addr_cnt += 1
conn.commit()
print("新增地址:", addr_cnt)

# ============ 4. 订单：320 笔（PAID 160 / UNPAID 80 / CANCELLED 80），跨 30 天 ============
def gene_order_no(uid):
    # 订单号末位嵌入 user_id%10 基因（与线上基因法一致）
    body = ''.join([str(random.randint(0,9)) for _ in range(17)])
    return f"SO{body}{uid % 10}"

order_cnt = {'PAID':0,'UNPAID':0,'CANCELLED':0}
for i in range(320):
    uid = random.choice(new_uids)
    status = random.choices(['PAID','UNPAID','CANCELLED'], weights=[5,2.5,2.5])[0]
    created = NOW - timedelta(days=random.uniform(0, 30), hours=random.uniform(0,23))
    n_items = random.choices([1,2,3], weights=[5,3,2])[0]
    items = []
    for pid in random.sample(ALL_PIDS, n_items):
        qty = random.randint(1, 3)
        nm, price = PROD[pid]
        items.append((pid, nm, price, qty, round(price*qty, 2)))
    total = round(sum(x[4] for x in items), 2)
    order_no = gene_order_no(uid)
    table = f"mall.orders_{uid % 2}"
    paid_at = (created + timedelta(minutes=random.randint(1, 30))) if status == 'PAID' else None
    cur.execute(f"INSERT INTO {table}(user_id,order_no,address_snapshot,total_amount,pay_amount,status,created_at,paid_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
                (uid, order_no, '仿真收货地址', total, total, status, created, paid_at))
    oid = cur.lastrowid
    itable = f"mall.order_item_{uid % 2}"
    for pid, nm, price, qty, sub in items:
        cur.execute(f"INSERT INTO {itable}(order_id,user_id,product_id,name,price,quantity,subtotal) VALUES (%s,%s,%s,%s,%s,%s,%s)",
                    (oid, uid, pid, nm, price, qty, sub))
    ltable = f"mall.order_status_log_{uid % 2}"
    if status in ('PAID','CANCELLED'):
        cur.execute(f"INSERT INTO {ltable}(order_no,from_status,to_status,event,operator,created_at) VALUES (%s,'UNPAID','{status}',%s,'SIMULATE',%s)",
                    (order_no, 'PAY_NOTIFY' if status=='PAID' else 'USER_CANCEL', created + timedelta(minutes=5)))
    if status == 'PAID':  # 一致性：PAID 单配支付单 + DONE 任务
        cur.execute("INSERT INTO mall.payment(order_id,user_id,amount,channel,status,callback_payload,created_at,paid_at) VALUES (%s,%s,%s,'MOCK','PAID','simulate',%s,%s)",
                    (oid, uid, total, created, paid_at))
        cur.execute("INSERT INTO mall.order_stock_task(order_no,product_id,quantity,status,retries,created_at,updated_at) VALUES (%s,%s,%s,'DONE',1,%s,%s)",
                    (order_no, items[0][0], items[0][3], created, created))
    elif status == 'CANCELLED':  # CANCELLED 配 FAILED（对账规则①的合法终态）
        cur.execute("INSERT INTO mall.order_stock_task(order_no,product_id,quantity,status,retries,fail_reason,created_at,updated_at) VALUES (%s,%s,%s,'FAILED',1,'simulated-cancel',%s,%s)",
                    (order_no, items[0][0], items[0][3], created, created))
    # UNPAID 不造任务行（避免对账任务/补偿器重发 MQ 扣真实库存）
    order_cnt[status] += 1
    if (i+1) % 80 == 0: conn.commit()
conn.commit()
print("订单:", order_cnt)

# ============ 5. 购物车：30 个用户 + 演示账号 334 ============
import subprocess
cart_users = random.sample(new_uids, 30) + [334]
pipe = []
for uid in cart_users:
    for pid in random.sample(ALL_PIDS, random.randint(3, 6)):
        qty = random.randint(1, 3)
        pipe.append(f"HSET cart:{uid} {pid} {qty}|1")
p = subprocess.run(['docker','exec','-i','mall-redis','redis-cli','--pipe'], input='\n'.join(pipe).encode(), capture_output=True, timeout=30)
print("购物车 Redis 写入:", p.stdout.decode()[:40])

# 演示账号 334 补 2 个地址（下单用）
cur.execute("SELECT COUNT(*) FROM mall.address WHERE user_id=334")
if cur.fetchone()[0] < 2:
    for nm in ['总部大楼','研发中心']:
        cur.execute("INSERT INTO mall.address(user_id,province,city,detail,receiver,phone,is_default,deleted,created_at,updated_at) VALUES (334,'广东省','深圳市',%s,'演示用户','13900001234',0,0,%s,%s)", (nm, NOW, NOW))
        addr_cnt += 1
conn.commit()
conn.close()
print("=== 灌数完成 ===")
