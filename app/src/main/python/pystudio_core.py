"""PyStudio core runtime.

Everything the Kotlin UI needs from Python lives here: script runner, debugger,
code analysis, completion, formatting, pip manager, shell/REPL and docs.
All calls return JSON strings (or plain values) so the Kotlin side stays simple.
"""
import ast
import bdb
import code
import contextlib
import ctypes
import importlib
import io
import json
import os
import queue
import re
import shlex
import shutil
import sys
import threading
import time
import traceback
import types

SITE = None            # directory where pip installs packages (on sys.path)
ABI = "arm64_v8a"      # Android ABI used to pick native wheels
CHAQUO_INDEX = "https://chaquo.com/pypi-13.1"
RUN = None             # current _Run
JOB = None             # current background pip job
_tl = threading.local()
_THIS = os.path.basename(__file__) if "__file__" in globals() else "pystudio_core.py"


# --------------------------------------------------------------------------
# stdout / stderr / stdin routing (thread aware)
# --------------------------------------------------------------------------
def _default_sink(kind, s):
    r = RUN
    if r is not None:
        r.q.put({"t": kind, "s": s})


class _Out:
    encoding = "utf-8"
    errors = "replace"

    def __init__(self, kind):
        self.kind = kind

    def write(self, s):
        if not isinstance(s, str):
            s = str(s)
        if s:
            (getattr(_tl, "sink", None) or _default_sink)(self.kind, s)
        return len(s)

    def writelines(self, lines):
        for l in lines:
            self.write(l)

    def flush(self):
        pass

    def isatty(self):
        return False

    def writable(self):
        return True

    def fileno(self):
        raise io.UnsupportedOperation("fileno")


class _In:
    encoding = "utf-8"

    def readline(self, size=-1):
        h = getattr(_tl, "stdin", None)
        if h is not None:
            return h()
        r = RUN
        return r.request_input() if r is not None else ""

    def read(self, size=-1):
        return self.readline()

    def readable(self):
        return True

    def isatty(self):
        return False

    def __iter__(self):
        return self

    def __next__(self):
        line = self.readline()
        if not line:
            raise StopIteration
        return line


def init(site_dir, abi="arm64_v8a"):
    global SITE, ABI
    SITE, ABI = site_dir, abi
    os.makedirs(site_dir, exist_ok=True)
    if site_dir not in sys.path:
        sys.path.append(site_dir)
    sys.stdout, sys.stderr, sys.stdin = _Out("out"), _Out("err"), _In()
    return sys.version.split()[0]


# --------------------------------------------------------------------------
# Script runner + debugger
# --------------------------------------------------------------------------
def _async_raise(tid, exc):
    getters = [lambda: ctypes.pythonapi,
               lambda: ctypes.PyDLL("libpython%d.%d.so" % sys.version_info[:2])]
    for g in getters:
        try:
            fn = g().PyThreadState_SetAsyncExc
            fn.argtypes = [ctypes.c_ulong, ctypes.py_object]
            return fn(tid, exc)
        except Exception:
            continue
    return 0


def _fmt(v):
    try:
        r = repr(v)
    except Exception as e:  # noqa
        r = "<repr failed: %s>" % e
    return r if len(r) <= 200 else r[:200] + "..."


def _vars(d, skip_callables):
    out = []
    for k, v in list(d.items())[:200]:
        if k.startswith("__") and k.endswith("__"):
            continue
        if skip_callables and isinstance(v, (types.ModuleType, types.FunctionType, type)):
            continue
        out.append([k, type(v).__name__, _fmt(v)])
    return out


def _internal(filename):
    b = os.path.basename(filename)
    return b in (_THIS, "bdb.py", "threading.py")


class _Dbg(bdb.Bdb):
    def __init__(self, run):
        super().__init__()
        self.r = run
        self.first = True

    def user_line(self, frame):
        if self.first:
            self.first = False
            if not self.break_here(frame):
                self.set_continue()
                return
        if self.r.stop_flag:
            self.set_quit()
            return
        self._pause(frame)

    def _snapshot(self, frame):
        stack, f = [], frame
        while f is not None and not _internal(f.f_code.co_filename):
            stack.append([f.f_code.co_filename, f.f_lineno, f.f_code.co_name])
            f = f.f_back
        same = frame.f_locals is frame.f_globals
        return {"t": "pause", "file": frame.f_code.co_filename, "line": frame.f_lineno,
                "stack": stack,
                "locals": _vars(frame.f_locals, same),
                "globals": [] if same else _vars(frame.f_globals, True)}

    def _eval(self, frame, expr):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            try:
                try:
                    c = compile(expr, "<debug>", "eval")
                except SyntaxError:
                    c = None
                if c is not None:
                    v = eval(c, frame.f_globals, frame.f_locals)
                    if v is not None:
                        print(repr(v))
                else:
                    exec(compile(expr, "<debug>", "exec"), frame.f_globals, frame.f_locals)
            except BaseException as e:  # noqa
                print("%s: %s" % (type(e).__name__, e))
        self.r.q.put({"t": "dbg", "s": buf.getvalue()})

    def _pause(self, frame):
        r = self.r
        r.q.put(self._snapshot(frame))
        while True:
            try:
                cmd = r.cmd_q.get(timeout=0.1)
            except queue.Empty:
                if r.stop_flag:
                    self.set_quit()
                    return
                continue
            if cmd == "into":
                self.set_step(); return
            if cmd == "over":
                self.set_next(frame); return
            if cmd == "out":
                self.set_return(frame); return
            if cmd == "continue":
                self.set_continue(); return
            if cmd == "quit":
                self.set_quit(); return
            if cmd.startswith("eval:"):
                self._eval(frame, cmd[5:])
                r.q.put(self._snapshot(frame))


class _Run:
    def __init__(self, path, debug, breaks):
        self.path, self.debug, self.breaks = path, debug, breaks
        self.q, self.input_q, self.cmd_q = queue.Queue(), queue.Queue(), queue.Queue()
        self.stop_flag = False
        self.running = True
        self.thread = None

    def request_input(self):
        self.q.put({"t": "input"})
        while True:
            try:
                v = self.input_q.get(timeout=0.1)
            except queue.Empty:
                if self.stop_flag:
                    raise KeyboardInterrupt()
                continue
            if v is None:
                raise KeyboardInterrupt()
            return v + "\n"

    def _print_exc(self):
        et, ev, tb = sys.exc_info()
        te = traceback.TracebackException(et, ev, tb)
        te.stack = traceback.StackSummary.from_list(
            [f for f in te.stack if not _internal(f.filename)])
        self.q.put({"t": "err", "s": "".join(te.format())})

    def main(self):
        started = time.time()
        rc = 0
        pdir = os.path.dirname(self.path)
        before = set(sys.modules)
        old_cwd = os.getcwd()
        old_argv = sys.argv
        try:
            os.chdir(pdir)
            sys.path.insert(0, pdir)
            sys.argv = [self.path]
            with open(self.path, encoding="utf-8") as f:
                src = f.read()
            compiled = compile(src, self.path, "exec")
            g = {"__name__": "__main__", "__file__": self.path, "__builtins__": __builtins__}
            if self.debug:
                d = _Dbg(self)
                for fn, lines in self.breaks.items():
                    for ln in lines:
                        d.set_break(fn, int(ln))
                d.run(compiled, g, g)
            else:
                exec(compiled, g, g)
        except SystemExit as e:
            if e.code is None:
                rc = 0
            elif isinstance(e.code, int):
                rc = e.code
            else:
                rc = 1
                self.q.put({"t": "err", "s": str(e.code) + "\n"})
        except bdb.BdbQuit:
            rc = -1
        except KeyboardInterrupt:
            rc = -1
            self.q.put({"t": "err", "s": "\nKeyboardInterrupt\n"})
        except BaseException:  # noqa
            rc = 1
            self._print_exc()
        finally:
            try:
                for name in set(sys.modules) - before:
                    mf = getattr(sys.modules.get(name), "__file__", None) or ""
                    if mf.startswith(pdir + os.sep):
                        sys.modules.pop(name, None)
                if pdir in sys.path:
                    sys.path.remove(pdir)
                sys.argv = old_argv
                os.chdir(old_cwd)
            except Exception:
                pass
            self.running = False
            self.q.put({"t": "done", "code": rc, "secs": round(time.time() - started, 2)})


def run_file(path, debug=False, breaks_json="{}"):
    global RUN
    if RUN is not None and RUN.running:
        return False
    RUN = _Run(path, bool(debug), json.loads(breaks_json or "{}"))
    RUN.thread = threading.Thread(target=RUN.main, name="pystudio-run", daemon=True)
    RUN.thread.start()
    return True


def stop_run():
    r = RUN
    if r is None or not r.running:
        return False
    r.stop_flag = True
    r.cmd_q.put("quit")
    r.input_q.put(None)
    if r.thread is not None and r.thread.ident:
        _async_raise(r.thread.ident, KeyboardInterrupt)
    return True


def is_running():
    return bool(RUN is not None and RUN.running)


def send_input(text):
    if RUN is not None:
        RUN.input_q.put(text)


def debug_cmd(cmd):
    if RUN is not None:
        RUN.cmd_q.put(cmd)


def poll():
    r = RUN
    if r is None:
        return "[]"
    ev = []
    for _ in range(400):
        try:
            e = r.q.get_nowait()
        except queue.Empty:
            break
        if ev and e["t"] in ("out", "err") and ev[-1]["t"] == e["t"]:
            ev[-1]["s"] += e["s"]
        else:
            ev.append(e)
    return json.dumps(ev)


# --------------------------------------------------------------------------
# Analysis: syntax errors, pyflakes, pycodestyle, quick fixes
# --------------------------------------------------------------------------
def _word_end(line, col0):
    j = col0
    while j < len(line) and (line[j].isalnum() or line[j] == "_"):
        j += 1
    return max(j, col0 + 1)


def _syntax_fix(msg, line_text):
    m = msg.lower()
    if "expected ':'" in m:
        return "Add ':' at the end of the line", "add_colon"
    if "was never closed" in m:
        return "Close the bracket with a matching ')' ']' or '}'", ""
    if "unterminated string" in m or "eol while scanning" in m:
        return "Close the string with a matching quote", ""
    if "missing parentheses" in m:
        return "Use print(...) with parentheses", ""
    if "indent" in m:
        return "Fix indentation: use 4 spaces per level and keep blocks aligned", ""
    if "unmatched" in m:
        return "Remove the extra closing bracket", ""
    if "invalid syntax" in m and "=" in line_text and ("if " in line_text or "while " in line_text):
        return "Use '==' to compare values; '=' assigns", ""
    if "perhaps you forgot a comma" in m:
        return "Add a comma between the items", ""
    return "Check the syntax around this position", ""


def _flake_fix(kind, args, line_text):
    if kind == "UnusedImport":
        s = line_text.strip()
        if (s.startswith("import ") or s.startswith("from ")) and "," not in s and "(" not in s:
            return "Remove the unused import", "delete_line"
        return "Remove the unused name from this import", ""
    if kind == "UndefinedName":
        name = args[0] if args else ""
        if name in getattr(sys, "stdlib_module_names", ()):
            return "Add 'import %s' at the top" % name, "import:" + name
        return "'%s' is not defined: check spelling, or define/import it" % name, ""
    if kind == "UnusedVariable":
        return "Remove the variable or rename it to '_'", ""
    if kind == "ImportStarUsed":
        return "Import the names you need explicitly", ""
    if kind == "RedefinedWhileUnused":
        return "Remove or rename the earlier unused definition", ""
    return "", ""


def analyze(source, path="<editor>"):
    out = []
    lines = source.splitlines()

    def line_of(n):
        return lines[n - 1] if 1 <= n <= len(lines) else ""

    try:
        tree = ast.parse(source, path)
    except SyntaxError as e:
        ln = e.lineno or 1
        col = max((e.offset or 1), 1)
        lt = line_of(ln)
        fix, op = _syntax_fix(e.msg or "", lt)
        out.append({"l": ln, "c": col, "ec": max(col + 1, _word_end(lt, col - 1) + 1),
                    "sev": "error", "msg": "%s: %s" % (type(e).__name__, e.msg),
                    "code": "E999", "fix": fix, "op": op})
        return json.dumps(out)
    except Exception as e:  # noqa
        out.append({"l": 1, "c": 1, "ec": 2, "sev": "error", "msg": str(e), "code": "E999",
                    "fix": "", "op": ""})
        return json.dumps(out)
    try:
        from pyflakes import checker
        w = checker.Checker(tree, filename=path)
        for m in sorted(w.messages, key=lambda x: (x.lineno, x.col)):
            lt = line_of(m.lineno)
            fix, op = _flake_fix(type(m).__name__, m.message_args, lt)
            out.append({"l": m.lineno, "c": m.col + 1, "ec": _word_end(lt, m.col) + 1,
                        "sev": "warning", "msg": m.message % m.message_args,
                        "code": type(m).__name__, "fix": fix, "op": op})
    except Exception:
        pass
    try:
        import pycodestyle

        class _Rep(pycodestyle.BaseReport):
            def __init__(self, options):
                super().__init__(options)
                self.items = []

            def error(self, line_number, offset, text, check):
                c = super().error(line_number, offset, text, check)
                if c and len(self.items) < 200:
                    self.items.append((line_number, offset + 1, text))
                return c

        style = pycodestyle.StyleGuide(max_line_length=100,
                                       ignore=["E121", "E123", "E126", "E226", "E24",
                                               "E704", "W503", "W504"])
        rep = style.init_report(_Rep)
        style.input_file(path, lines=source.splitlines(True))
        for ln, col, text in rep.items:
            code_ = text.split(" ", 1)[0]
            fix = "Format the file (Format code) to fix style issues"
            op = "strip_ws" if code_ in ("W291", "W293") else ""
            out.append({"l": ln, "c": col, "ec": col + 1, "sev": "info", "msg": text,
                        "code": code_, "fix": fix, "op": op})
    except Exception:
        pass
    return json.dumps(out)


def format_code(source):
    try:
        import autopep8
        return autopep8.fix_code(source, options={"max_line_length": 100, "aggressive": 1})
    except Exception:
        s = "\n".join(l.rstrip() for l in source.expandtabs(4).split("\n"))
        return s if s.endswith("\n") else s + "\n"


# --------------------------------------------------------------------------
# Completion (jedi, the engine behind python-lsp-server)
# --------------------------------------------------------------------------
_projects = {}


def _jedi_script(source, path, project_dir):
    import jedi
    proj = _projects.get(project_dir)
    if proj is None:
        proj = jedi.Project(path=project_dir or os.getcwd(),
                            added_sys_path=[SITE] if SITE else [])
        _projects[project_dir] = proj
    return jedi.Script(source, path=path, project=proj)


def complete(source, line, col, path, project_dir):
    try:
        s = _jedi_script(source, path, project_dir)
        comps = s.complete(line, col)
        items = []
        plen = 0
        for c in comps[:60]:
            if not items:
                plen = c.get_completion_prefix_length()
            items.append({"n": c.name, "t": c.type})
        sig = ""
        try:
            sigs = s.get_signatures(line, col)
            if sigs:
                sig = sigs[0].to_string()
        except Exception:
            pass
        return json.dumps({"p": plen, "items": items, "sig": sig})
    except Exception as e:  # noqa
        return json.dumps({"p": 0, "items": [], "sig": "", "err": str(e)})


# --------------------------------------------------------------------------
# Package manager (pip runs in-process, installs into app-private site dir)
# --------------------------------------------------------------------------
def _norm(n):
    return re.sub(r"[-_.]+", "-", n).lower()


def list_packages():
    res = []
    if SITE and os.path.isdir(SITE):
        for d in sorted(os.listdir(SITE)):
            if d.endswith(".dist-info"):
                base = d[:-len(".dist-info")]
                name, _, ver = base.rpartition("-")
                summary = ""
                try:
                    with open(os.path.join(SITE, d, "METADATA"), encoding="utf-8") as f:
                        for ln in f:
                            if ln.startswith("Summary:"):
                                summary = ln[8:].strip()
                                break
                            if not ln.strip():
                                break
                except Exception:
                    pass
                res.append({"name": name.replace("_", "-"), "version": ver, "summary": summary})
    return json.dumps(res)


def _pip_install(spec_str, upgrade=True):
    try:
        from pip._internal.cli.main import main as pip_main
    except Exception as e:  # noqa
        print("pip is not available in this build: %s" % e, file=sys.stderr)
        return 1
    specs = shlex.split(spec_str)
    pv = "%d.%d" % sys.version_info[:2]
    args = ["install", "--target", SITE, "--upgrade", "--no-input", "--disable-pip-version-check",
            "--no-warn-script-location", "--progress-bar", "off", "--only-binary", ":all:",
            "--implementation", "cp", "--python-version", pv, "--abi", "cp" + pv.replace(".", ""),
            "--platform", "android_21_" + ABI, "--extra-index-url", CHAQUO_INDEX] + specs
    print("$ pip install " + " ".join(specs))
    rc = pip_main(args)
    importlib.invalidate_caches()
    print("\nDone." if rc == 0 else "\nInstallation failed (exit code %s)." % rc)
    return rc


def _uninstall(name):
    n = _norm(name)
    found = False
    for d in os.listdir(SITE):
        if not d.endswith(".dist-info"):
            continue
        if _norm(d[:-len(".dist-info")].rpartition("-")[0]) != n:
            continue
        found = True
        di = os.path.join(SITE, d)
        dirs = set()
        try:
            with open(os.path.join(di, "RECORD"), encoding="utf-8") as f:
                for ln in f:
                    rel = ln.split(",")[0].strip()
                    if not rel or rel.startswith(".."):
                        continue
                    full = os.path.join(SITE, rel)
                    if os.path.isfile(full):
                        os.remove(full)
                        print("removed " + rel)
                    dirs.add(os.path.dirname(full))
        except FileNotFoundError:
            pass
        shutil.rmtree(di, ignore_errors=True)
        for dd in sorted(dirs, key=len, reverse=True):
            while dd.startswith(SITE) and dd != SITE:
                shutil.rmtree(os.path.join(dd, "__pycache__"), ignore_errors=True)
                try:
                    os.rmdir(dd)
                except OSError:
                    break
                dd = os.path.dirname(dd)
    importlib.invalidate_caches()
    print("Uninstalled %s." % name if found else "Package '%s' is not installed." % name)
    return 0 if found else 1


class _Job:
    def __init__(self):
        self.q = queue.Queue()
        self.done = False
        self.code = 0


def _job_main(job, kind, arg, upgrade):
    _tl.sink = lambda k, s: job.q.put(s)
    _tl.stdin = lambda: ""
    try:
        job.code = _pip_install(arg, upgrade) if kind == "install" else _uninstall(arg)
    except BaseException:  # noqa
        traceback.print_exc()
        job.code = 1
    finally:
        job.done = True


def job_start(kind, arg, upgrade=True):
    global JOB
    if JOB is not None and not JOB.done:
        return False
    JOB = _Job()
    threading.Thread(target=_job_main, args=(JOB, kind, arg, upgrade), daemon=True).start()
    return True


def job_poll():
    j = JOB
    if j is None:
        return json.dumps({"out": "", "done": True, "code": 0})
    chunks = []
    while True:
        try:
            chunks.append(j.q.get_nowait())
        except queue.Empty:
            break
    return json.dumps({"out": "".join(chunks), "done": j.done and j.q.empty(), "code": j.code})


# --------------------------------------------------------------------------
# Terminal: mini shell + persistent Python REPL
# --------------------------------------------------------------------------
_ns = {"__name__": "__console__", "__doc__": None}
_interp = code.InteractiveInterpreter(_ns)
_buf = []
_cwd = [None]


def _repl(line):
    _buf.append(line)
    src = "\n".join(_buf)
    try:
        c = code.compile_command(src, "<console>", "single")
    except (OverflowError, SyntaxError, ValueError):
        _interp.showsyntaxerror("<console>")
        _buf.clear()
        return False
    if c is None:
        return True
    _buf.clear()
    _interp.runcode(c)
    return False


def _shell(line):
    try:
        a = shlex.split(line)
    except ValueError as e:
        print("parse error: %s" % e, file=sys.stderr)
        return
    if not a:
        return
    cmd, args = a[0], a[1:]
    flags = [x for x in args if x.startswith("-")]
    paths = [x for x in args if not x.startswith("-")]
    try:
        if cmd == "help":
            print("ls cd pwd cat mkdir rm cp mv touch echo head wc tree clear\n"
                  "python [file.py]  - run a file / start the Python REPL\n"
                  "pip install|uninstall|list <pkg>\n"
                  "env, date, whoami, exit")
        elif cmd == "pwd":
            print(os.getcwd())
        elif cmd == "cd":
            os.chdir(os.path.expanduser(paths[0]) if paths else (_cwd[0] or "/"))
            _cwd[0] = os.getcwd()
        elif cmd == "ls":
            for p in (paths or ["."]):
                if os.path.isdir(p):
                    for n in sorted(os.listdir(p)):
                        if n.startswith(".") and "-a" not in flags:
                            continue
                        print(n + ("/" if os.path.isdir(os.path.join(p, n)) else ""))
                else:
                    print(p)
        elif cmd == "cat":
            for p in paths:
                with open(p, encoding="utf-8", errors="replace") as f:
                    print(f.read(), end="")
        elif cmd == "head":
            with open(paths[0], encoding="utf-8", errors="replace") as f:
                for _, l in zip(range(10), f):
                    print(l, end="")
        elif cmd == "wc":
            with open(paths[0], encoding="utf-8", errors="replace") as f:
                t = f.read()
            print(len(t.splitlines()), len(t.split()), len(t), paths[0])
        elif cmd == "mkdir":
            for p in paths:
                os.makedirs(p, exist_ok=True)
        elif cmd == "touch":
            for p in paths:
                open(p, "a").close()
        elif cmd == "rm":
            for p in paths:
                if os.path.isdir(p):
                    if "-r" in flags or "-rf" in flags:
                        shutil.rmtree(p)
                    else:
                        print("rm: %s is a directory (use -r)" % p, file=sys.stderr)
                else:
                    os.remove(p)
        elif cmd == "cp":
            src, dst = paths[0], paths[1]
            shutil.copytree(src, dst) if os.path.isdir(src) else shutil.copy(src, dst)
        elif cmd == "mv":
            shutil.move(paths[0], paths[1])
        elif cmd == "echo":
            print(" ".join(args))
        elif cmd == "tree":
            root = paths[0] if paths else "."
            for dp, dn, fn in os.walk(root):
                dn[:] = sorted(d for d in dn if not d.startswith("."))
                depth = dp[len(root):].count(os.sep)
                print("  " * depth + (os.path.basename(dp) or dp) + "/")
                for f in sorted(fn):
                    print("  " * (depth + 1) + f)
        elif cmd == "env":
            for k, v in sorted(os.environ.items()):
                print("%s=%s" % (k, v))
        elif cmd == "date":
            print(time.strftime("%a %b %d %H:%M:%S %Y"))
        elif cmd == "whoami":
            print("pystudio")
        elif cmd == "python" or cmd == "python3":
            if paths:
                print("\x00run:" + os.path.abspath(paths[0]), end="")
            else:
                print("\x00mode:py", end="")
        elif cmd == "pip":
            if paths and paths[0] == "list":
                for p in json.loads(list_packages()):
                    print("%-30s %s" % (p["name"], p["version"]))
            elif paths and paths[0] == "install":
                _pip_install(" ".join(shlex.quote(x) for x in paths[1:]))
            elif paths and paths[0] == "uninstall":
                for p in paths[1:]:
                    _uninstall(p)
            else:
                print("usage: pip install|uninstall|list [packages]")
        elif cmd == "git":
            print("Use the Git screen from the menu (built-in Git support).")
        else:
            print("%s: command not found (type 'help')" % cmd, file=sys.stderr)
    except SystemExit:
        pass
    except Exception as e:  # noqa
        print("%s: %s" % (cmd, e), file=sys.stderr)


def term_set_cwd(path):
    _cwd[0] = path
    try:
        os.chdir(path)
    except Exception:
        pass


def term_exec(line, mode):
    out = []
    _tl.sink = lambda k, s: out.append([k, s])
    _tl.stdin = lambda: ""
    more = False
    try:
        if _cwd[0]:
            try:
                os.chdir(_cwd[0])
            except Exception:
                pass
        if mode == "py":
            more = _repl(line)
        else:
            _shell(line)
    except BaseException:  # noqa
        traceback.print_exc()
    finally:
        _tl.sink = None
        _tl.stdin = None
    return json.dumps({"out": out, "more": more, "cwd": _cwd[0] or os.getcwd()})


# --------------------------------------------------------------------------
# Documentation viewer (pydoc, fully offline)
# --------------------------------------------------------------------------
def doc(name):
    import pydoc
    try:
        obj = pydoc.locate(name.strip())
        if obj is None:
            return "No documentation found for '%s'." % name
        text = pydoc.render_doc(obj, renderer=pydoc.plaintext)
        return text[:80000]
    except Exception as e:  # noqa
        return "Could not load documentation: %s" % e


def stdlib_modules():
    names = sorted(n for n in getattr(sys, "stdlib_module_names", ()) if not n.startswith("_"))
    return json.dumps(names)


def versions():
    return sys.version
