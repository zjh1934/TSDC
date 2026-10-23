#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4.3 队列持久化回归测试。

为什么专门写这个：
v4.3 之前队列是纯内存的，进程一死排队的书就凭空消失（这是用户说"软件不稳定"的
真实原因之一）。v4.3 改成落盘 + 恢复。而落盘恢复最危险的失败模式是**静默丢字段**：

    序列化时少写一个字段 -> 反序列化用默认值 -> 用户上次设的语速/音色被悄悄重置
    -> 恢复出来的任务"能跑"，但参数全错，用户根本发现不了。

所以这里逐字段比对 toJson / fromJson 的键集合，并模拟"进程被杀 -> 重启恢复"
全流程的不变量。
"""
import re
import sys
import json

SRC = "/workspace/ListenBook/app/src/main/java/com/listenbook/export/ExportJob.java"
QRC = "/workspace/ListenBook/app/src/main/java/com/listenbook/export/ExportQueue.java"

fails = []
checks = 0


def ck(cond, msg):
    global checks
    checks += 1
    if not cond:
        fails.append(msg)


def body(src, start_pat, end_marker="\n    }"):
    i = src.find(start_pat)
    if i < 0:
        return ""
    j = src.find(end_marker, i)
    return src[i:j if j > 0 else len(src)]


src = open(SRC, encoding="utf-8").read()
qrc = open(QRC, encoding="utf-8").read()

# ---------- 1. 字段集合必须一一对应 ----------
ser = body(src, "private static final org.json.JSONObject toJson")
des = body(src, "private static ExportJob fromJson")

put_keys = re.findall(r'o\.put\("(\w+)"', ser)
opt_keys = re.findall(r'o\.opt(?:Long|String|Boolean|Double|Int|JSONArray)\("(\w+)"', des)

# toJson 里的 indices 是数组容器，同样用 indices 键
ser_keys = set(put_keys)
des_keys = set(opt_keys)

only_ser = sorted(ser_keys - des_keys)
only_des = sorted(des_keys - ser_keys)

ck(not only_ser, "序列化写了但反序列化没读（会丢字段）：%s" % only_ser)
ck(not only_des, "反序列化读了一个不存在的键（永远拿默认值，字段名写错了）：%s" % only_des)

# ---------- 2. ExportJob 的每个业务字段都必须进盘 ----------
# 这些是用户设的参数，少一个就是"恢复后设置被重置"
must_persist = ["bookId", "bookTitle", "syncPublic", "enginePkg", "customTree",
                "voiceName", "rate", "pitch", "languageMode", "localeTag",
                "chunkBudget", "timeoutSlack", "maxAttempts", "indices",
                "jobId", "status", "enqueuedAt"]
for k in must_persist:
    ck(k in ser_keys, "业务字段 %s 没有落盘" % k)
    ck(k in des_keys, "业务字段 %s 没有读回" % k)

# ---------- 3. float 用 Double 存，读回要能还原精度 ----------
ck('o.put("rate", Double.valueOf(j.rate))' in ser, "rate 存的是 float 不是 Double，JSON 会丢精度")
ck('j.rate = (float) o.optDouble("rate", 1.0d)' in des, "rate 读回方式不对")

# ---------- 4. 恢复的任务必须置回 PENDING ----------
# 留着 RUNNING 会让队列认为"还有人在跑"，永远轮不到它 —— 任务卡死不报错
ck("j.status = ST_PENDING;" in qrc, "恢复的任务没置回 ST_PENDING，会导致队列卡死")

# ---------- 5. persist 的挂载点：五个会改变队列内容的地方都得落盘 ----------
for fn in ["enqueue", "finish", "cancelAt", "clearPending", "clearFinished"]:
    # 在对应方法体里找 persist()
    m = re.search(r'(?:public|static)[^\n]*\b' + fn + r'\s*\([^)]*\)\s*\{', qrc)
    ck(m is not None, "找不到方法 %s" % fn)
    if m:
        seg = qrc[m.end():m.end() + 4000]
        #截到下一个方法定义
        nxt = re.search(r'\n    (?:public|private|static)[^\n]*\{', seg)
        if nxt:
            seg = seg[:nxt.start()]
        ck("persist()" in seg, "方法 %s 里没有 persist()，队列内容变了但没落盘" % fn)

# ---------- 6. restoreFromDisk 幂等 ----------
# 被系统 + App 启动各调一次，第二次不能重复灌入
ck("items.size() > 0" in qrc, "restoreFromDisk 没有 items.size()>0 守卫，会重复灌入任务")

# ---------- 7. 空盘数据不能让 App 起不来 ----------
ck("catch (Exception e)" in src.split("queueFromJson")[-1], "queueFromJson 没有兜异常")
ck("return null" in src.split("queueFromJson")[-1][:1200], "queueFromJson 失败没有返回安全的 null")

# ---------- 8. STICKY 只在有活干时挂 ----------
ck("START_STICKY" in open("/workspace/ListenBook/app/src/main/java/com/listenbook/export/ExportService.java", encoding="utf-8").read(),
   "ExportService 没有 START_STICKY")
svc = open("/workspace/ListenBook/app/src/main/java/com/listenbook/export/ExportService.java", encoding="utf-8").read()
i_sticky = svc.find("return START_STICKY;", svc.find("int restored = ExportQueue.restoreFromDisk()"))
i_empty = svc.find("if (!ExportQueue.hasPending())", svc.find("int restored = ExportQueue.restoreFromDisk()"))
ck(i_empty > 0 and i_sticky > i_empty,
   "队列为空时没有提前 return START_NOT_STICKY，系统会空跑拉起服务")

# ---------- 9. 用户主动停止要断掉重启 ----------
ck("ExportQueue.clearInterrupted();" in svc.split("ACTION_STOP.equals(action)")[1][:600],
   "点「停止」后没清中断标记，下次会被当成「被中断」误报")

print("检查项: %d" % checks)
if fails:
    print("失败 %d 项:" % len(fails))
    for f in fails:
        print("  ✗ " + f)
    sys.exit(1)
print("✓ v4.3 队列持久化回归测试全部通过")