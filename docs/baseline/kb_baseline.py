"""R0 基线评测:语料入库 → 30 条带证据片段的问题 → 指标存档。

产出:
  ForgeAgent/docs/baseline/kb-baseline-r0.json  机器可读结果(后续批次 diff 用)
  ForgeAgent/docs/baseline/kb-baseline-r0.md    人读报告

判定口径(与 R3 将采用的口径一致):证据片段与命中分块内容「去除全部空白后」包含匹配。
指标:HitRate@5 / MRR / Recall@5 / Precision@5 / NDCG@5。
"""
import json
import math
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BASE = Path(r"D:\AgentWork\重构Agent项目")
FORGE = BASE / "ForgeAgent"
JAR = FORGE / "backend/forge-admin/target/forge-admin.jar"
CORPUS = FORGE / "docs/baseline/kb-baseline-corpus"
CASES = FORGE / "docs/baseline/kb-baseline-cases.json"
OUT_JSON = FORGE / "docs/baseline/kb-baseline-r0.json"
OUT_MD = FORGE / "docs/baseline/kb-baseline-r0.md"
APPLOG = BASE / "temp/r0_app.log"
MYSQL = r"D:\Programs\MySQL9.7\bin\mysql.exe"
SQL_ARGS = [MYSQL, "-uroot", "-pHong1234", "-D", "forge-vue",
            "--default-character-set=utf8mb4", "-N", "-B", "-e"]
JAVA = r"D:\Programs\OpenJDK25\bin\java.exe"
A1LOGIN = BASE / "temp" / "A1Login.java"
CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e"
PORT = 18093
BASE_URL = f"http://127.0.0.1:{PORT}"
KB_NAME = "kb-baseline-r0"
TOP_K = 5


def arg_value(flag, default=None):
    """取命令行参数:--unit token / --size 400 / --overlap 60"""
    argv = sys.argv
    for i, a in enumerate(argv):
        if a == flag and i + 1 < len(argv):
            return argv[i + 1]
    return default


# 分块口径(用于对照实验):默认按 char,与定版基线一致
CHUNK_UNIT = (arg_value("--unit", "char") or "char").lower()
CHUNK_SIZE = int(arg_value("--size", "0") or 0) or (400 if CHUNK_UNIT == "token" else 500)
CHUNK_OVERLAP = int(arg_value("--overlap", "0") or 0) or (60 if CHUNK_UNIT == "token" else 50)
SPLIT_STRATEGY = (arg_value("--strategy", "structure") or "structure").lower()

failures = []


def sql(query):
    r = subprocess.run(SQL_ARGS + [query], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = r.stdout.decode("utf-8", "replace").strip()
    if r.returncode != 0:
        raise RuntimeError(f"SQL 失败: {query[:80]} -> {out}")
    return "\n".join(line for line in out.splitlines()
                     if not line.startswith("mysql: [Warning]")).strip()


def sql_int(query, default=-1):
    try:
        return int(sql(query).split("\t")[0].splitlines()[0])
    except Exception:
        return default


def http(method, path, body=None, token=None, timeout=90, raw=None, ctype=None):
    hdrs = {"clientid": CLIENT_ID}
    data = None
    if raw is not None:
        data = raw
        hdrs["Content-Type"] = ctype
    else:
        hdrs["Content-Type"] = "application/json"
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        hdrs["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE_URL + path, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8", "replace") or "{}"
        try:
            return e.code, json.loads(txt)
        except json.JSONDecodeError:
            return e.code, {"code": e.code, "msg": txt[:200]}


def port_open(port=PORT):
    with socket.socket() as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def login():
    # 本地 dev 库的 admin 密码（Docker seed 与本地库一致时用同一个）
    for password in ("admin123",):
        plain = json.dumps({"username": "admin", "password": password,
                            "clientId": CLIENT_ID, "grantType": "password",
                            "tenantId": "000000"}, ensure_ascii=False)
        gen = subprocess.run([JAVA, str(A1LOGIN), plain], capture_output=True,
                             text=True, encoding="utf-8", timeout=120)
        parts = gen.stdout.strip().splitlines()
        if gen.returncode != 0 or len(parts) != 2:
            continue
        req = urllib.request.Request(
            BASE_URL + "/auth/login", data=parts[1].encode("utf-8"), method="POST",
            headers={"Content-Type": "application/json", "encrypt-key": parts[0],
                     "clientid": CLIENT_ID})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                payload = json.loads(resp.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError:
            continue
        data = payload.get("data") or {}
        if payload.get("code") == 200 and data.get("access_token"):
            return data["access_token"]
    raise RuntimeError("登录失败")


def start_app():
    APPLOG.write_text("", encoding="utf-8")
    f = APPLOG.open("ab")
    proc = subprocess.Popen([JAVA, "-Dserver.port=%d" % PORT, "-jar", str(JAR)],
                            stdout=f, stderr=subprocess.STDOUT, cwd=str(JAR.parent))
    deadline = time.time() + 200
    while time.time() < deadline:
        if port_open():
            time.sleep(8)
            return proc
        if proc.poll() is not None:
            raise RuntimeError("应用启动即退出,见 temp/r0_app.log")
        time.sleep(2)
    raise RuntimeError(f"应用 200 秒未监听 {PORT}")


def stop_app(proc):
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=45)
        except subprocess.TimeoutExpired:
            proc.kill()


def upload(token, kb_id, filename, content):
    boundary = "----r0" + uuid.uuid4().hex
    head = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            "Content-Type: text/markdown; charset=utf-8\r\n\r\n")
    body = head.encode("utf-8") + content.encode("utf-8") + \
        f"\r\n--{boundary}--\r\n".encode("utf-8")
    return http("POST", f"/agent/knowledge/doc/upload?knowledgeId={kb_id}",
                token=token, raw=body,
                ctype=f"multipart/form-data; boundary={boundary}")


def cleanup():
    sql(f"DELETE FROM agent_kb_chunk WHERE knowledge_id IN "
        f"(SELECT id FROM agent_knowledge WHERE name LIKE '{KB_NAME}%');")
    sql(f"DELETE FROM agent_knowledge_document WHERE knowledge_id IN "
        f"(SELECT id FROM agent_knowledge WHERE name LIKE '{KB_NAME}%');")
    sql(f"DELETE FROM agent_knowledge WHERE name LIKE '{KB_NAME}%';")


def norm(text):
    return "".join((text or "").split())


def judge(hits, span):
    """返回首个命中名次与逐条相关性"""
    target = norm(span)
    relevance = []
    first = None
    for i, hit in enumerate(hits, start=1):
        ok = target and target in norm(hit.get("content"))
        relevance.append(1 if ok else 0)
        if ok and first is None:
            first = i
    return first, relevance


def metrics(rows, k):
    n = len(rows)
    hit_rate = sum(1 for r in rows if r["hitRank"]) / n
    mrr = sum((1.0 / r["hitRank"]) if r["hitRank"] else 0.0 for r in rows) / n
    recall = sum(r["recall"] for r in rows) / n
    precision = sum(r["precision"] for r in rows) / n
    ndcg = sum(r["ndcg"] for r in rows) / n
    return {"hitRate": round(hit_rate, 4), "mrr": round(mrr, 4),
            "recallAtK": round(recall, 4), "precisionAtK": round(precision, 4),
            "ndcgAtK": round(ndcg, 4), "cases": n}


def ndcg_at_k(relevance, k):
    dcg = sum(rel / math.log2(i + 1) for i, rel in enumerate(relevance[:k], start=1))
    ideal = 1.0  # 每条用例只有 1 个证据片段
    return dcg / ideal


def main():
    if not JAR.exists():
        raise RuntimeError(f"jar 不存在: {JAR}")
    if port_open():
        raise RuntimeError(f"{PORT} 已被占用")
    spec = json.loads(CASES.read_text(encoding="utf-8"))
    cases = spec["cases"]
    docs = sorted(CORPUS.glob("*.md"))
    if len(docs) < 6 or len(cases) < 30:
        raise RuntimeError("语料或用例不足")

    cleanup()
    # InnoDB 全文索引对已删除行是异步清理的:上一次跑剩的分块还在倒排表里,
    # 会改变本次的 IDF,导致同样的语料算出不同分数 → MRR 每轮漂 0.01。
    # 重建一次索引把"历史影响"清掉,测量才有可比性(仅测量夹具需要,生产不必)。
    sql("OPTIMIZE TABLE agent_kb_chunk;")
    proc = start_app()
    kb_id = None
    try:
        token = login()
        status, resp = http("POST", "/agent/knowledge", {
            "name": KB_NAME, "description": "R0 基线评测库",
            "embeddingModel": "local-hash",
            "chunkSize": CHUNK_SIZE, "chunkOverlap": CHUNK_OVERLAP,
            "chunkUnit": CHUNK_UNIT, "splitStrategy": SPLIT_STRATEGY,
        }, token=token)
        if resp.get("code") != 200:
            raise RuntimeError("建库失败: " + str(resp.get("msg")))
        kb_id = sql_int(f"SELECT id FROM agent_knowledge WHERE name='{KB_NAME}' AND del_flag='0' "
                        f"ORDER BY id DESC LIMIT 1;")
        print("KB 分块配置(库内实际值): " + sql(
            f"SELECT chunk_unit, chunk_size, chunk_overlap, split_strategy FROM agent_knowledge "
            f"WHERE id={kb_id};"))

        # 串行摄取:每篇等就绪再传下一篇。
        # 并发摄取会让不同文档的分块 id 交错,而"同分按 id 破平"的顺序随之漂移,
        # 每次跑的 MRR 会差 0.01~0.02——测不出真实变化。测量夹具必须自己保证顺序稳定。
        for doc in docs:
            status, resp = upload(token, kb_id, doc.name, doc.read_text(encoding="utf-8"))
            if resp.get("code") != 200:
                raise RuntimeError(f"上传失败 {doc.name}: {resp.get('msg')}")
            deadline = time.time() + 90
            while time.time() < deadline:
                pending = sql_int(f"SELECT COUNT(*) FROM agent_knowledge_document "
                                  f"WHERE knowledge_id={kb_id} AND file_name='{doc.name}' AND status <> '1';")
                if pending == 0:
                    break
                time.sleep(1)

        # 等所有文档就绪
        deadline = time.time() + 180
        while time.time() < deadline:
            pending = sql_int(f"SELECT COUNT(*) FROM agent_knowledge_document "
                              f"WHERE knowledge_id={kb_id} AND status <> '1';")
            if pending == 0:
                break
            time.sleep(2)
        docs_total = sql_int(f"SELECT COUNT(*) FROM agent_knowledge_document "
                             f"WHERE knowledge_id={kb_id};")
        docs_ok = sql_int(f"SELECT COUNT(*) FROM agent_knowledge_document "
                          f"WHERE knowledge_id={kb_id} AND status='1';")
        if docs_ok != docs_total:
            failures.append(f"仅 {docs_ok}/{docs_total} 篇文档就绪")

        chunk_total = sql_int(f"SELECT COUNT(*) FROM agent_kb_chunk WHERE knowledge_id={kb_id};")
        vec_rows = sql_int(f"SELECT COUNT(*) FROM agent_kb_chunk WHERE knowledge_id={kb_id} "
                           f"AND embedding IS NOT NULL;")
        pending_vec = sql_int(f"SELECT COUNT(*) FROM agent_kb_chunk WHERE knowledge_id={kb_id} "
                             f"AND vector_status <> 'SYNCED' AND chunk_role='c';")
        print("分块长度(条数/平均/最大/平均token): " + sql(
            f"SELECT COUNT(*), ROUND(AVG(CHAR_LENGTH(content))), MAX(CHAR_LENGTH(content)), "
            f"ROUND(AVG(token_count)) FROM agent_kb_chunk WHERE knowledge_id={kb_id} AND chunk_role='c';"))

        rows = []
        for case in cases:
            code, body = http("POST", "/agent/knowledge/search", {
                "knowledgeId": kb_id, "query": case["question"], "topK": TOP_K,
                "cacheable": False,
            }, token=token)
            if code != 200 or body.get("code") != 200:
                failures.append(f"{case['id']} 检索失败: {body.get('msg')}")
                hits = []
            else:
                hits = body.get("data") or []
            first, relevance = judge(hits, case["span"])
            rows.append({
                "id": case["id"], "topic": case["topic"], "doc": case["doc"],
                "question": case["question"], "span": case["span"],
                "hitRank": first,
                "recall": 1.0 if first else 0.0,
                "precision": sum(relevance) / len(hits) if hits else 0.0,
                "ndcg": ndcg_at_k(relevance, TOP_K),
                "hitCount": len(hits),
                "top1Score": (hits[0].get("score") if hits else None),
                "top1ScoreType": (hits[0].get("scoreType") if hits else None),
                "top1Doc": (hits[0].get("fileName") if hits else None),
                "channels": [h.get("matchChannels") for h in hits],
                "hitDocs": [h.get("fileName") for h in hits],
            })

        overall = metrics(rows, TOP_K)
        by_topic = {}
        for topic in sorted({r["topic"] for r in rows}):
            by_topic[topic] = metrics([r for r in rows if r["topic"] == topic], TOP_K)

        result = {
            "name": spec["name"],
            "generatedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
            "environment": {
                "profile": "dev",
                "vectorStore": "mysql(应用层余弦)",
                "embeddingModel": "local-hash(伪向量,仅供链路验证)",
                "chunkSize": CHUNK_SIZE, "chunkOverlap": CHUNK_OVERLAP,
                "chunkUnit": CHUNK_UNIT, "splitStrategy": SPLIT_STRATEGY,
                "parentChunk": False,
                "topK": TOP_K,
                "judge": "证据片段与命中内容去空白后包含匹配",
            },
            "corpus": {"docs": docs_total, "docsReady": docs_ok,
                       "chunks": chunk_total, "chunksWithVector": vec_rows,
                       "chunksVectorPending": pending_vec},
            "overall": overall,
            "byTopic": by_topic,
            "cases": rows,
        }
        # `--no-write` 用于回归校验:只打印指标,不覆盖已存档的基线文件
        if "--no-write" not in sys.argv:
            OUT_JSON.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            OUT_MD.write_text(render_markdown(result), encoding="utf-8")

        print(json.dumps({"overall": overall, "corpus": result["corpus"]}, ensure_ascii=False))
    finally:
        stop_app(proc)
        try:
            cleanup()
        except Exception:
            pass

    if failures:
        print("FAILURES:")
        for f in failures:
            print(" - " + f)
        sys.exit(1)


def render_markdown(r):
    ov = r["overall"]
    lines = []
    lines.append("# 知识库检索 R0 基线（%s）" % r["generatedAt"])
    lines.append("")
    lines.append("> 用途：后续批次（R2 剩余 / R3 评测改造）改动前先与本文档对比，判断是变好还是变差。")
    lines.append("> 复现：`python temp/kb_baseline.py`（自动建库、灌语料、跑用例、写回本文件）。")
    lines.append("")
    lines.append("## 1. 环境与口径")
    lines.append("")
    lines.append("| 项 | 值 |")
    lines.append("| --- | --- |")
    for k, v in r["environment"].items():
        lines.append("| %s | %s |" % (k, v))
    lines.append("")
    lines.append("## 2. 语料")
    lines.append("")
    c = r["corpus"]
    lines.append("| 文档数 | 就绪 | 分块数 | 有向量分块 | 待补偿 |")
    lines.append("| --- | --- | --- | --- | --- |")
    lines.append("| %d | %d | %d | %d | %d |" %
                 (c["docs"], c["docsReady"], c["chunks"], c["chunksWithVector"], c["chunksVectorPending"]))
    lines.append("")
    lines.append("## 3. 总体指标（Top-5）")
    lines.append("")
    lines.append("| 指标 | 值 | 说明 |")
    lines.append("| --- | --- | --- |")
    lines.append("| HitRate@5 | %.2f%% | 前 5 条里能否找到证据片段 |" % (ov["hitRate"] * 100))
    lines.append("| MRR | %.4f | 首个命中的倒数名次均值 |" % ov["mrr"])
    lines.append("| Recall@5 | %.2f%% | 证据覆盖率（每例 1 条证据） |" % (ov["recallAtK"] * 100))
    lines.append("| Precision@5 | %.2f%% | 命中中真正相关的比例 |" % (ov["precisionAtK"] * 100))
    lines.append("| NDCG@5 | %.4f | 二值相关的排序质量 |" % ov["ndcgAtK"])
    lines.append("")
    lines.append("## 4. 分主题")
    lines.append("")
    lines.append("| 主题 | 用例数 | HitRate | MRR | Precision@5 | NDCG@5 |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for topic, m in r["byTopic"].items():
        lines.append("| %s | %d | %.0f%% | %.3f | %.0f%% | %.3f |" %
                     (topic, m["cases"], m["hitRate"] * 100, m["mrr"],
                      m["precisionAtK"] * 100, m["ndcgAtK"]))
    lines.append("")
    lines.append("## 5. 逐用例")
    lines.append("")
    lines.append("| # | 问题 | 名次 | 命中数 | 首条分/口径 | 首条文档 | 通道 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")
    for c in r["cases"]:
        rank = c["hitRank"] if c["hitRank"] else "—"
        score = c["top1Score"]
        score_txt = ("%.4f/%s" % (score, c["top1ScoreType"])) if score is not None else "—"
        lines.append("| %s | %s | %s | %d | %s | %s | %s |" %
                     (c["id"], c["question"], rank, c["hitCount"], score_txt,
                      c["top1Doc"] or "—", "/".join([x or "?" for x in c["channels"]])))
    lines.append("")
    lines.append("## 6. 已知局限（读数字前必看）")
    lines.append("")
    lines.append("1. **dev 环境用的是 local-hash 伪向量**：向量路基本等同于噪声，命中主要靠全文关键词路。"
                 "这份基线衡量的是「分块 + 关键词 + 融合 + 阈值」这条链，不代表生产语义召回质量。")
    lines.append("2. 判定口径是「证据片段去空白后包含于命中分块」，所以跨分块边界的证据会判未命中；"
                 "语料里每条证据都刻意选在单个段落内，减少这种噪声。")
    lines.append("3. 每例只有 1 条证据，Recall 与 HitRate 在当前口径下等价；R3 引入多证据用例后会分开。")
    lines.append("4. 数字只用于**同环境前后对比**（改分块、改阈值、改融合），不要拿去和别的系统比。")
    lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    main()
