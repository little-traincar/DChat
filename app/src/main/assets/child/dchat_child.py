"""独立 Python 子进程协议入口（JSON-lines）。

stdout 只允许协议响应；用户代码/runner 的 stdout 全部重定向到 stderr，避免串台。
"""
import json
import os
import sys

_proto_fd = os.dup(1)
os.dup2(2, 1)
sys.stdout = sys.stderr
_proto = os.fdopen(_proto_fd, "w", buffering=1, encoding="utf-8", errors="replace")

_here = os.path.dirname(os.path.abspath(__file__))
if _here not in sys.path:
    sys.path.insert(0, _here)

try:
    import dchat_runner
except Exception as e:  # pragma: no cover - 启动期错误必须回给宿主
    _proto.write(json.dumps({"id": None, "ok": False, "code": "CHILD_BOOT_FAILED",
                             "error": "%s: %s" % (type(e).__name__, e)}) + "\n")
    _proto.flush()
    raise


def _send(obj):
    try:
        _proto.write(json.dumps(obj, ensure_ascii=False) + "\n")
        _proto.flush()
    except Exception:
        pass


def _one(req):
    rid = req.get("id")
    try:
        if req.get("code") is not None:
            raw = dchat_runner.execute_code(
                req.get("code", ""),
                req.get("argsJson", "{}"),
                req.get("sandbox", ""),
                req.get("sitePackages", ""),
                req.get("statePath", ""),
                req.get("perm", "workspace"),
            )
        else:
            raw = dchat_runner.execute(
                req.get("entry", ""),
                req.get("entryFunction", "run"),
                req.get("argsJson", "{}"),
                req.get("sandbox", ""),
                req.get("sitePackages", ""),
                req.get("statePath", ""),
                req.get("perm", "workspace"),
            )
        resp = json.loads(raw) if isinstance(raw, str) else raw
        if not isinstance(resp, dict):
            resp = {"ok": False, "code": "TOOL_ERROR", "error": "bad runner response"}
        resp["id"] = rid
        _send(resp)
    except Exception as e:
        _send({"id": rid, "ok": False, "code": "TOOL_ERROR",
               "error": "%s: %s" % (type(e).__name__, e)})


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception as e:
            _send({"id": None, "ok": False, "code": "BAD_REQUEST",
                   "error": "invalid json: %s" % e})
            continue
        _one(req)


if __name__ == "__main__":
    main()
