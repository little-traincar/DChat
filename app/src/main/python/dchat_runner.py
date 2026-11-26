"""DChat 用户自建工具运行器（Chaquopy 内置 Python）。

由 Kotlin 侧 PyRuntime 调用：
    dchat_runner.execute(entry_path, entry_func, args_json, sandbox, site_packages, state_path)

安全边界（4a）：
- import 白名单 + 已安装包；其它模块直接抛 ImportError("该模块未内置，请先安装")；
- open() 只允许 sandbox 目录内（realpath 防符号链接逃逸）；
- 工作目录切到 sandbox；
- 不把真实 builtins.__import__ 暴露给用户代码（只给每个用户模块一份安全的 builtins）。
注意：这不是强沙箱（Python 解释器同进程、线程无法强杀），只是 4a 的受限执行约定。
"""

import builtins
import ast
import io
import json
import os
import sys
import threading

# 用户代码允许 import 的标准库（brief 指定）
_ALLOWED_STD = {
    "json", "math", "random", "re", "datetime", "time", "statistics",
    "itertools", "collections", "uuid", "hashlib", "base64",
}

# C 加速器 / 标准库私有实现模块：用户代码不直接写它们，但上面那些模块内部会 import
# （例如 datetime -> _datetime、hashlib -> _sha256/_hashlib、json -> _json），
# 不放行会在 import 链中间报 ImportError，表现为「datetime 不能 import」的假象。
_ALLOWED_ACCEL = {
    "_collections_abc", "_bisect", "_heapq", "_struct", "_functools", "_operator",
    "_abc", "_string", "_contextvars", "_weakref", "_codecs", "_locale",
    "_sha1", "_sha256", "_sha512", "_md5", "_blake2", "_hashlib",
    "_json", "_datetime", "_random", "_decimal", "_csv", "_statistics",
    "_socket", "_ssl",
}

# 这些加速器等价于联网模块，仍按会话权限卡
_ACCEL_NET = {"_socket", "_ssl"}

# 常见第三方包（构建期未内置；安装到 site-packages 后可 import）
_ALLOWED_THIRD = {"requests", "yaml", "bs4", "dateutil"}

# 永远禁止（即使 site-packages 里出现同名目录）
# 注：socket/ssl/http/urllib 不在此列——网络口径 B：read 档禁止联网，workspace/full 放行。
_DENIED = {
    "os", "sys", "subprocess", "ctypes", "importlib", "shutil",
    "pathlib", "builtins", "multiprocessing", "threading", "signal", "gc",
    "inspect", "code", "codeop", "runpy", "pdb", "doctest", "site",
    "sysconfig", "platform", "resource", "pty", "fcntl", "posix", "tempfile",
    "pickle", "shelve", "sqlite3",
}

# 网络相关模块：只有会话权限 ≥ workspace 才允许 import（B 方案）
_NET_MODULES = {"socket", "ssl", "http", "urllib", "urllib3", "requests", "idna", "certifi", "charset_normalizer"}

_IMPORT_ERR = "该模块未内置或不允许"
_NET_DENIED_ERR = "当前会话权限不足，不能联网"
_SANDBOX_ERR = "只能读写工具沙箱目录内的文件"

# 进程级共享状态的锁（sys.path 修改、首次导入等）
_PREP_LOCK = threading.Lock()


def _top_level_installed(site_packages):
    """site-packages 里的顶层模块/包名（requests 的依赖也可被 import）"""
    names = set()
    try:
        for entry in os.listdir(site_packages):
            path = os.path.join(site_packages, entry)
            if entry.endswith((".py", ".pyc", ".so", ".pyd")):
                names.add(entry.split(".", 1)[0])
            elif os.path.isdir(path) and os.path.isfile(os.path.join(path, "__init__.py")):
                names.add(entry)
    except OSError:
        pass
    return names


def _make_import(allowed, perm):
    real_import = builtins.__import__
    can_net = perm in ("workspace", "full")

    def guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
        top = name.split(".", 1)[0]
        if top in _DENIED:
            raise ImportError(_IMPORT_ERR)
        if top.startswith("_"):
            # C 加速器：标准库内部的 import 一律放行；用户手写 import _xxx 也只放行白名单里的
            if top in _ACCEL_NET:
                if not can_net:
                    raise ImportError(_NET_DENIED_ERR)
                return real_import(name, globals, locals, fromlist, level)
            if top in _ALLOWED_ACCEL or top.lstrip("_") in _ALLOWED_STD:
                return real_import(name, globals, locals, fromlist, level)
            raise ImportError(_IMPORT_ERR)
        if top in _NET_MODULES and not can_net:
            raise ImportError(_NET_DENIED_ERR)
        if level == 0 and top not in allowed and top not in _NET_MODULES:
            raise ImportError(_IMPORT_ERR)
        return real_import(name, globals, locals, fromlist, level)

    return guarded_import


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


def _prepare(sandbox, site_packages, perm):
    """公共准备：返回 (sandbox, allowed, safe_builtins, base_globals)。

    并发注意（bg.run 会在 5 个线程里同时跑 Python）：
    - **不做 os.chdir**：进程级工作目录会被别的线程抢走；相对路径由 sandbox_open 负责拼接；
    - sys.path 的插入用锁保护并去重（只影响模块解析，不改 cwd）。
    """
    if perm not in ("read", "workspace", "full"):
        perm = "workspace"
    sandbox = os.path.realpath(sandbox)
    site_packages = os.path.realpath(site_packages)
    os.makedirs(sandbox, exist_ok=True)
    os.makedirs(site_packages, exist_ok=True)
    with _PREP_LOCK:
        if site_packages not in sys.path:
            sys.path.insert(0, site_packages)
    installed = _top_level_installed(site_packages)
    allowed = _ALLOWED_STD | _ALLOWED_THIRD | installed
    safe_builtins = dict(builtins.__dict__)
    safe_builtins["__import__"] = _make_import(allowed, perm)
    safe_builtins["open"] = _make_open(sandbox)
    base = {
        "__name__": "__dchat_user_tool__",
        "__builtins__": safe_builtins,
        "__perm__": perm,
    }
    return sandbox, allowed, safe_builtins, base


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


def execute_code(code, args_json="{}", sandbox="", site_packages="", state_path="", perm="workspace"):
    """local.python 裸代码模式：执行代码，返回 stdout + 最后一个表达式值。"""
    _mods_before = set(sys.modules) if os.environ.get("DCHAT_CHILD") == "1" else None
    try:
        sandbox, allowed, safe_builtins, base = _prepare(sandbox, site_packages, perm)
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
        buf = io.StringIO()
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
        if value is not None:
            parts.append(str(value))
        result = "\n".join(parts).strip() or "(无输出)"
        return json.dumps({"ok": True, "result": result}, ensure_ascii=False)
    except ImportError as e:
        msg = str(e) or _IMPORT_ERR
        code = "PERMISSION_DENIED" if _NET_DENIED_ERR in msg else "IMPORT_DENIED"
        return json.dumps({"ok": False, "error": msg, "code": code}, ensure_ascii=False)
    except PermissionError as e:
        return json.dumps({"ok": False, "error": str(e) or _SANDBOX_ERR, "code": "SANDBOX_DENIED"}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e), "code": "TOOL_ERROR"}, ensure_ascii=False)
    finally:
        _purge_new_modules(_mods_before)


def execute(entry_path, entry_func="run", args_json="{}", sandbox="", site_packages="", state_path="", perm="workspace"):
    """执行用户工具，返回 JSON 字符串（ok/result/error/code）。perm: read/workspace/full"""
    _mods_before = set(sys.modules) if os.environ.get("DCHAT_CHILD") == "1" else None
    if perm not in ("read", "workspace", "full"):
        perm = "workspace"
    sandbox = os.path.realpath(sandbox)
    site_packages = os.path.realpath(site_packages)
    os.makedirs(sandbox, exist_ok=True)
    os.makedirs(site_packages, exist_ok=True)
    try:
        with _PREP_LOCK:
            if site_packages not in sys.path:
                sys.path.insert(0, site_packages)
        installed = _top_level_installed(site_packages)
        allowed = _ALLOWED_STD | _ALLOWED_THIRD | installed
        try:
            args = json.loads(args_json) if args_json.strip() else {}
        except Exception as e:
            return json.dumps({"ok": False, "error": "参数不是合法 JSON: %s" % e, "code": "INVALID_ARGS"}, ensure_ascii=False)
        if not isinstance(args, dict):
            return json.dumps({"ok": False, "error": "参数必须是 JSON 对象", "code": "INVALID_ARGS"}, ensure_ascii=False)

        with open(entry_path, "r", encoding="utf-8") as f:
            code = f.read()

        safe_builtins = dict(builtins.__dict__)
        safe_builtins["__import__"] = _make_import(allowed, perm)
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
        return json.dumps({"ok": True, "result": result}, ensure_ascii=False)
    except ImportError as e:
        msg = str(e) or _IMPORT_ERR
        code = "PERMISSION_DENIED" if _NET_DENIED_ERR in msg else "IMPORT_DENIED"
        return json.dumps({"ok": False, "error": msg, "code": code}, ensure_ascii=False)
    except PermissionError as e:
        return json.dumps({"ok": False, "error": str(e) or _SANDBOX_ERR, "code": "SANDBOX_DENIED"}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e), "code": "TOOL_ERROR"}, ensure_ascii=False)
    finally:
        _purge_new_modules(_mods_before)
