"""DChat 用户自建工具运行器（Chaquopy 内置 Python）。

由 Kotlin 侧 PyRuntime 调用：
    dchat_runner.execute(entry_path, entry_func, args_json, sandbox, site_packages, state_path)

安全边界（1.0.3 起）：
- **标准库 / 内置 C 加速器 / 已安装第三方包全部放行**：不再维护 import 白名单，
  `os/sys/io/zlib/gzip/struct/functools/typing/enum/dataclasses/threading/asyncio/subprocess/...`
  都可用；网络模块（socket/ssl/http/urllib/requests…）也不做权限拦截，网络可用性由系统决定；
- **只保留一条限制**：open() 只允许工具沙箱目录内（realpath 防符号链接逃逸）；
- 不做 os.chdir（bg.run 会在 5 个线程里并发跑，进程级 cwd 会被抢）；相对路径由 sandbox_open 拼接；
- 本地收集输出上限 1MB，超出截断；回给模型的 64KB 截断由 Kotlin 侧负责；
- 不把真实 builtins.__import__ 暴露给用户代码已不再需要（白名单取消），但 open 仍用包装版。
注意：这不是强沙箱（Python 解释器同进程、线程无法强杀），只是受限执行约定。
"""

import builtins
import ast
import io
import itertools
import json
import os
import shutil
import sys
import threading
import types

# 本地收集上限：1MB（Kotlin 侧再把超 64KB 的结果落 python_out.txt，回模型前由调用方截断）
_OUTPUT_LIMIT = 1024 * 1024
_SANDBOX_ERR = "只能读写工具沙箱目录内的文件"

# 进程级共享状态的锁（sys.path 修改、首次导入等）
_PREP_LOCK = threading.Lock()

# ---------------------------------------------------------------- droid 能力模块
# 规范：docs/spec/droid-api.md
# - CHILD：写 stdout 协议 JSON、读 stdin 匹配 kind=droid_reply+id；
# - IN_PROCESS：from java import jclass 直接调 DroidHost.call（同一套语义）；
# - 两种模式都通过 sys.modules["droid"] 注入，用户代码 `import droid` 即可。
_DROID_LOCK = threading.RLock()
_DROID_CHILD_LOCK = threading.Lock()
_DROID_LOCAL = threading.local()
_DROID_GLOBAL_CTX = ("", "", "workspace")
_DROID_IDS = itertools.count(1)

# 当前执行的工具沙箱（droid 返回的文件要复制进沙箱，用户代码的 open() 才读得到）
_SANDBOX_LOCAL = threading.local()
_SANDBOX_GLOBAL = ""


def _set_active_sandbox(sandbox):
    global _SANDBOX_GLOBAL
    _SANDBOX_GLOBAL = sandbox or ""
    try:
        _SANDBOX_LOCAL.path = _SANDBOX_GLOBAL
    except Exception:
        pass


def _active_sandbox():
    try:
        return _SANDBOX_LOCAL.path
    except AttributeError:
        return _SANDBOX_GLOBAL


def _copy_droid_file_to_sandbox(result):
    """pick_photo/take_photo：DroidHost 返回的是会话工作区路径，而用户代码的
    open() 只允许工具沙箱 -> 这里把文件复制进沙箱，path 改成沙箱副本，
    原始路径放 workspace_path（保留给需要回写会话的调用方）。
    """
    if not isinstance(result, dict):
        return result
    src = result.get("path")
    sandbox = _active_sandbox()
    if not src or not sandbox:
        return result
    try:
        src_real = os.path.realpath(src)
        sandbox_real = os.path.realpath(sandbox)
        if src_real == sandbox_real or src_real.startswith(sandbox_real + os.sep):
            return result
        os.makedirs(sandbox_real, exist_ok=True)
        name = os.path.basename(src_real) or "droid_file"
        dst = os.path.join(sandbox_real, name)
        base, ext = os.path.splitext(dst)
        i = 1
        while os.path.exists(dst):
            dst = "%s_%d%s" % (base, i, ext)
            i += 1
        shutil.copyfile(src_real, dst)
        out = dict(result)
        out["path"] = dst
        out["workspace_path"] = src
        return out
    except Exception:
        # 复制失败时至少把原始信息返回，让工具自己决定（open 可能被沙箱拦住）
        return result


class DroidError(Exception):
    """droid 能力调用失败；code 见 docs/spec/droid-api.md 第 4 节。"""

    def __init__(self, code, message=""):
        self.code = code or "DROID_ERROR"
        self.message = message or self.code
        super().__init__("%s: %s" % (self.code, self.message))

    def __str__(self):
        return "%s: %s" % (self.code, self.message)


def _droid_context():
    try:
        return _DROID_LOCAL.ctx
    except AttributeError:
        return _DROID_GLOBAL_CTX


def _parse_droid_reply(raw):
    try:
        o = json.loads(raw) if isinstance(raw, str) else raw
    except Exception:
        raise DroidError("DROID_ERROR", "宿主返回无法解析: %r" % (raw,))
    if isinstance(o, dict) and o.get("ok") is True:
        return o.get("result")
    code = "DROID_ERROR"
    msg = "droid 调用失败"
    if isinstance(o, dict):
        code = o.get("code") or code
        msg = o.get("error") or o.get("message") or msg
    raise DroidError(code, msg)


def _child_droid_roundtrip(method, args):
    """CHILD：写协议 stdout 请求，读 stdin 等 droid_reply（宿主串行处理）。"""
    proto = getattr(sys.modules.get("__main__"), "_proto", None)
    if proto is None:
        raise DroidError("DROID_UNSUPPORTED", "子进程协议通道不可用")
    with _DROID_CHILD_LOCK:
        rid = next(_DROID_IDS)
        req = {"kind": "droid", "id": rid, "method": method, "args": args}
        try:
            payload = json.dumps(req, ensure_ascii=False)
        except TypeError as e:
            raise DroidError("DROID_ERROR", "droid 参数无法序列化: %s" % e)
        try:
            proto.write(payload + "\n")
            proto.flush()
        except Exception as e:
            raise DroidError("DROID_ERROR", "写入子进程协议通道失败: %s" % e)
        while True:
            line = sys.stdin.readline()
            if not line:
                raise DroidError("DROID_ERROR", "子进程协议通道已关闭")
            line = line.strip()
            if not line:
                continue
            try:
                reply = json.loads(line)
            except Exception:
                continue
            if not isinstance(reply, dict):
                continue
            if reply.get("kind") != "droid_reply":
                continue
            if reply.get("id") != rid:
                continue
            return _parse_droid_reply(reply)


def _inproc_droid_call(method, args):
    """IN_PROCESS：直接调 Kotlin DroidHost.call（Chaquopy jclass）。"""
    tool_id, conversation_id, mode = _droid_context()
    try:
        from java import jclass
    except Exception:
        raise DroidError("DROID_UNSUPPORTED", "当前运行时不支持 droid（JVM 调用不可用）")
    try:
        args_json = json.dumps(args, ensure_ascii=False)
    except TypeError as e:
        raise DroidError("DROID_ERROR", "droid 参数无法序列化: %s" % e)
    host = None
    last_err = None
    for class_name in (
        "com.dchat.app.data.droid.DroidHost",
        "com.dchat.app.data.tools.DroidHost",
    ):
        try:
            host = jclass(class_name)
            break
        except Exception as e:  # 类不存在 / JVM 调用不可用
            last_err = e
    if host is None:
        raise DroidError("DROID_UNSUPPORTED", "JVM droid 能力桥不可用: %s" % (last_err,))
    try:
        raw = host.call(method, args_json, tool_id, conversation_id, mode)
    except Exception as e:
        raise DroidError("DROID_ERROR", "%s: %s" % (type(e).__name__, e))
    return _parse_droid_reply(raw)


def _droid_call(method, **kwargs):
    args = dict(kwargs)
    if os.environ.get("DCHAT_CHILD") == "1":
        result = _child_droid_roundtrip(method, args)
    else:
        # IN_PROCESS：DroidHost 可能在主线程做 UI 操作，串行调用避免多个工具并发抢能力
        with _DROID_LOCK:
            result = _inproc_droid_call(method, args)
    if method in ("pick_photo", "take_photo"):
        result = _copy_droid_file_to_sandbox(result)
    return result


def toast(text):
    return _droid_call("toast", text=text)


def vibrate(ms=50):
    return _droid_call("vibrate", ms=ms)


def clipboard_get():
    return _droid_call("clipboard_get")


def clipboard_set(text):
    return _droid_call("clipboard_set", text=text)


def notify(title, text, id=0):
    return _droid_call("notify", title=title, text=text, id=id)


def share_text(text, title=""):
    return _droid_call("share_text", text=text, title=title)


def open_url(url):
    return _droid_call("open_url", url=url)


def battery():
    return _droid_call("battery")


def network():
    return _droid_call("network")


def sensors():
    return _droid_call("sensors")


def device():
    return _droid_call("device")


def locate(timeout=30):
    return _droid_call("locate", timeout=timeout)


def pick_photo():
    return _droid_call("pick_photo")


def take_photo():
    return _droid_call("take_photo")


_DROID_MODULE = types.ModuleType("droid")
_DROID_MODULE.__doc__ = "安卓能力 API（规范 docs/spec/droid-api.md）"
_DROID_MODULE.DroidError = DroidError
_DROID_MODULE.toast = toast
_DROID_MODULE.vibrate = vibrate
_DROID_MODULE.clipboard_get = clipboard_get
_DROID_MODULE.clipboard_set = clipboard_set
_DROID_MODULE.notify = notify
_DROID_MODULE.share_text = share_text
_DROID_MODULE.open_url = open_url
_DROID_MODULE.battery = battery
_DROID_MODULE.network = network
_DROID_MODULE.sensors = sensors
_DROID_MODULE.device = device
_DROID_MODULE.locate = locate
_DROID_MODULE.pick_photo = pick_photo
_DROID_MODULE.take_photo = take_photo


def _install_droid(tool_id="", conversation_id="", perm="workspace"):
    """把 droid 注入 sys.modules（执行前调用）；同时记录本次调用的会话上下文。"""
    global _DROID_GLOBAL_CTX
    ctx = (tool_id or "", conversation_id or "", perm or "workspace")
    _DROID_GLOBAL_CTX = ctx
    try:
        _DROID_LOCAL.ctx = ctx
    except Exception:
        pass
    sys.modules["droid"] = _DROID_MODULE


class _CappedBuffer(io.StringIO):
    """最多收集 limit 个字符的 stdout；超出部分丢弃并打 truncated 标记。"""

    def __init__(self, limit):
        super().__init__()
        self._limit = limit
        self.truncated = False

    def write(self, s):
        if not isinstance(s, str):
            s = str(s)
        remaining = self._limit - self.tell()
        if remaining <= 0:
            if s:
                self.truncated = True
            return len(s)
        if len(s) > remaining:
            self.truncated = True
            s = s[:remaining]
        return super().write(s)


def _cap_result(text):
    """结果字符串统一截到 1MB（保头，尾部标注）。"""
    if not isinstance(text, str):
        text = str(text)
    if len(text) <= _OUTPUT_LIMIT:
        return text
    return text[:_OUTPUT_LIMIT] + "\n...(输出超过 1MB，已截断)"


def _make_open(sandbox):
    real_open = builtins.open

    def sandbox_open(file, mode="r", *args, **kwargs):
        try:
            path = os.fspath(file)
        except TypeError:
            raise TypeError("file must be a path")
        if isinstance(path, bytes):
            path = path.decode("utf-8", "replace")
        if not os.path.isabs(path):
            path = os.path.join(sandbox, path)
        real = os.path.realpath(path)
        if not (real == sandbox or real.startswith(sandbox + os.sep)):
            raise PermissionError(_SANDBOX_ERR)
        return real_open(real, mode, *args, **kwargs)

    return sandbox_open


def _prepare(sandbox, site_packages, perm="workspace"):
    """公共准备：返回 (sandbox, safe_builtins, base_globals)。

    并发注意（bg.run 会在 5 个线程里同时跑 Python）：
    - **不做 os.chdir**：进程级工作目录会被别的线程抢走；相对路径由 sandbox_open 负责拼接；
    - sys.path 的插入用锁保护并去重（只影响模块解析，不改 cwd）。
    """
    sandbox = os.path.realpath(sandbox)
    site_packages = os.path.realpath(site_packages)
    os.makedirs(sandbox, exist_ok=True)
    os.makedirs(site_packages, exist_ok=True)
    _set_active_sandbox(sandbox)
    with _PREP_LOCK:
        if site_packages not in sys.path:
            sys.path.insert(0, site_packages)
    safe_builtins = dict(builtins.__dict__)
    safe_builtins["open"] = _make_open(sandbox)
    base = {
        "__name__": "__dchat_user_tool__",
        "__builtins__": safe_builtins,
        "__perm__": perm,
    }
    return sandbox, safe_builtins, base


def _builtins_with_buffer(safe_builtins, buf):
    """给这一次调用单独一份 builtins：print 直接写进本次的 buf。

    为什么不用 contextlib.redirect_stdout：它替换的是**进程级** sys.stdout，
    5 个后台任务并发时会互相覆盖，后进场的把别人的输出抢走
    （真机现象：任务 6 的 print 出现在任务 4 的结果里）。
    """
    call_builtins = dict(safe_builtins)

    def _print(*args, sep=" ", end="\n", file=None, flush=False):
        if file is not None:
            # 用户显式指定文件对象：照常写（open() 已被沙箱限制）
            file.write(sep.join(str(a) for a in args) + end)
            return
        buf.write(sep.join(str(a) for a in args) + end)

    call_builtins["print"] = _print
    return call_builtins


def _purge_new_modules(before):
    """只清理本次调用新增的模块（仅子进程模式；in-process 并发线程不能动全局 sys.modules）。"""
    if before is None:
        return
    for name in set(sys.modules) - before:
        sys.modules.pop(name, None)


def execute_code(code, args_json="{}", sandbox="", site_packages="", state_path="", perm="workspace",
                 tool_id="", conversation_id=""):
    """local.python 裸代码模式：执行代码，返回 stdout + 最后一个表达式值。"""
    _mods_before = set(sys.modules) if os.environ.get("DCHAT_CHILD") == "1" else None
    try:
        sandbox, safe_builtins, base = _prepare(sandbox, site_packages, perm)
        # droid 能力模块：两种模式同一 API（执行前注入，用户代码 import droid 即可）
        _install_droid(tool_id, conversation_id, perm)
        try:
            args = json.loads(args_json) if args_json and args_json.strip() else {}
        except Exception:
            args = {}
        globals_dict = dict(base)
        globals_dict["__file__"] = "<python>"
        globals_dict["__state_path__"] = state_path
        globals_dict["args"] = args if isinstance(args, dict) else {}
        try:
            tree = ast.parse(code, "<python>", "exec")
        except SyntaxError as e:
            return json.dumps({"ok": False, "error": "SyntaxError: %s" % e, "code": "TOOL_ERROR"}, ensure_ascii=False)
        last_expr = None
        if tree.body and isinstance(tree.body[-1], ast.Expr):
            last_expr = ast.Expression(tree.body.pop().value)
        buf = _CappedBuffer(_OUTPUT_LIMIT)
        globals_dict["__builtins__"] = _builtins_with_buffer(safe_builtins, buf)
        if tree.body:
            exec(compile(tree, "<python>", "exec"), globals_dict)
        value = None
        if last_expr is not None:
            value = eval(compile(last_expr, "<python>", "eval"), globals_dict)
        parts = []
        printed = buf.getvalue().rstrip()
        if printed:
            parts.append(printed)
        if getattr(buf, "truncated", False):
            parts.append("...(stdout 超过 1MB，已截断)")
        if value is not None:
            parts.append(str(value))
        result = _cap_result("\n".join(parts).strip() or "(无输出)")
        return json.dumps({"ok": True, "result": result}, ensure_ascii=False)
    except RecursionError:
        return json.dumps(
            {"ok": False, "error": "递归过深（RecursionError）：请减少递归层级或改用迭代", "code": "TOOL_ERROR"},
            ensure_ascii=False,
        )
    except ImportError as e:
        return json.dumps(
            {"ok": False, "error": "ImportError: %s" % (str(e) or "模块不可用"), "code": "TOOL_ERROR"},
            ensure_ascii=False,
        )
    except PermissionError as e:
        return json.dumps({"ok": False, "error": str(e) or _SANDBOX_ERR, "code": "SANDBOX_DENIED"}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e), "code": "TOOL_ERROR"}, ensure_ascii=False)
    finally:
        _purge_new_modules(_mods_before)


def execute(entry_path, entry_func="run", args_json="{}", sandbox="", site_packages="", state_path="", perm="workspace",
            tool_id="", conversation_id=""):
    """执行用户工具，返回 JSON 字符串（ok/result/error/code）。perm: read/workspace/full（仅记录，不再拦截）"""
    _mods_before = set(sys.modules) if os.environ.get("DCHAT_CHILD") == "1" else None
    sandbox = os.path.realpath(sandbox)
    site_packages = os.path.realpath(site_packages)
    os.makedirs(sandbox, exist_ok=True)
    os.makedirs(site_packages, exist_ok=True)
    _set_active_sandbox(sandbox)
    try:
        with _PREP_LOCK:
            if site_packages not in sys.path:
                sys.path.insert(0, site_packages)
        # droid 能力模块：两种模式同一 API（执行前注入，用户代码 import droid 即可）
        _install_droid(tool_id, conversation_id, perm)
        try:
            args = json.loads(args_json) if args_json.strip() else {}
        except Exception as e:
            return json.dumps({"ok": False, "error": "参数不是合法 JSON: %s" % e, "code": "INVALID_ARGS"}, ensure_ascii=False)
        if not isinstance(args, dict):
            return json.dumps({"ok": False, "error": "参数必须是 JSON 对象", "code": "INVALID_ARGS"}, ensure_ascii=False)

        with open(entry_path, "r", encoding="utf-8") as f:
            code = f.read()

        safe_builtins = dict(builtins.__dict__)
        safe_builtins["open"] = _make_open(sandbox)
        globals_dict = {
            "__name__": "__dchat_user_tool__",
            "__file__": entry_path,
            "__builtins__": safe_builtins,
            "__state_path__": state_path,
            "__perm__": perm,
        }
        compiled = compile(code, entry_path, "exec")
        exec(compiled, globals_dict)
        func = globals_dict.get(entry_func)
        if not callable(func):
            return json.dumps({"ok": False, "error": "入口函数 %s 不存在或不可调用" % entry_func, "code": "NO_ENTRY"}, ensure_ascii=False)
        result = func(args)
        if result is None:
            result = ""
        elif not isinstance(result, str):
            try:
                result = json.dumps(result, ensure_ascii=False, default=str)
            except Exception:
                result = str(result)
        return json.dumps({"ok": True, "result": _cap_result(result)}, ensure_ascii=False)
    except RecursionError:
        return json.dumps(
            {"ok": False, "error": "递归过深（RecursionError）：请减少递归层级或改用迭代", "code": "TOOL_ERROR"},
            ensure_ascii=False,
        )
    except ImportError as e:
        return json.dumps(
            {"ok": False, "error": "ImportError: %s" % (str(e) or "模块不可用"), "code": "TOOL_ERROR"},
            ensure_ascii=False,
        )
    except PermissionError as e:
        return json.dumps({"ok": False, "error": str(e) or _SANDBOX_ERR, "code": "SANDBOX_DENIED"}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e), "code": "TOOL_ERROR"}, ensure_ascii=False)
    finally:
        _purge_new_modules(_mods_before)
