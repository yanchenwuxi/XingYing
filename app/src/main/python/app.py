"""星影 Python 源桥接模块。

兼容两种 TVBox py 源写法：
1) 模块级函数：homeContent / categoryContent / detailContent / ...
2) 类式 Spider：class Spider(base.spider.Spider): def homeContent(self, filter): ...

Java 侧通过 com.chaquo.python.PyObject.callAttr 调用这里的函数。
"""
import json
import os
import sys

_SCRIPT_DIR = None


def _dumps(obj):
    if obj is None:
        return ""
    if isinstance(obj, str):
        return obj
    try:
        return json.dumps(obj, ensure_ascii=False)
    except Exception:
        return str(obj)


def _resolve_fn(obj, name):
    """从 dict 命名空间或 Spider 实例上取可调用函数。"""
    if obj is None:
        return None
    # 实例优先：类式 Spider
    if not isinstance(obj, dict):
        fn = getattr(obj, name, None)
        if callable(fn):
            return fn
        return None
    # 模块级函数
    fn = obj.get(name)
    if callable(fn):
        return fn
    # 命名空间里有 Spider 类但尚未实例化（兜底）
    cls = obj.get("Spider")
    if cls is not None and isinstance(cls, type):
        try:
            inst = cls()
            obj["_spider_inst"] = inst
            fn = getattr(inst, name, None)
            if callable(fn):
                return fn
        except Exception as e:
            print("py spider instantiate fallback failed: %s" % e)
    return None


def _safe(obj, name, *args):
    fn = _resolve_fn(obj, name)
    if fn is None:
        return ""
    try:
        return _dumps(fn(*args))
    except Exception as e:
        print("py spider error: %s(%s) -> %s" % (name, args, e))
        return ""


def spider(path, api):
    """加载 py 脚本。

    优先返回 Spider 实例（类式源）；否则返回模块命名空间 dict。
    path: 脚本根目录
    api:  脚本名（不含或含 .py）
    """
    global _SCRIPT_DIR
    _SCRIPT_DIR = path
    if path and path not in sys.path:
        sys.path.insert(0, path)
    # base/ 也要在 path 上，from base.spider import Spider 才能成功
    if path:
        base_dir = os.path.join(path, "base")
        if os.path.isdir(base_dir) and path not in sys.path:
            sys.path.insert(0, path)

    filename = api if api.endswith(".py") else (api + ".py")
    full = os.path.join(path, filename)
    if not os.path.exists(full):
        # 再试 py_ 前缀去掉
        alt = api[3:] if api.startswith("py_") else api
        alt_name = alt if alt.endswith(".py") else (alt + ".py")
        full2 = os.path.join(path, alt_name)
        if os.path.exists(full2):
            full = full2
            filename = alt_name
        else:
            print("py spider not found: " + full)
            return {}

    ns = {"__name__": "tvspider_" + api.replace(".", "_"), "__file__": full}
    try:
        with open(full, "r", encoding="utf-8", errors="ignore") as f:
            code = compile(f.read(), full, "exec")
        exec(code, ns)
    except Exception as e:
        print("py spider load failed: %s" % e)
        import traceback
        traceback.print_exc()
        return {}

    # 类式 Spider：实例化后返回实例，后续 _safe 走 getattr
    cls = ns.get("Spider")
    if cls is not None and isinstance(cls, type):
        try:
            inst = cls()
            # 把 siteKey 等模块属性挂到实例上方便脚本读
            if "siteKey" in ns:
                try:
                    inst.siteKey = ns["siteKey"]
                except Exception:
                    pass
            print("py spider loaded as class instance: %s" % filename)
            return inst
        except Exception as e:
            print("py spider class init failed: %s, fallback to ns" % e)

    # 模块级函数写法
    print("py spider loaded as module ns: %s keys=%s" % (
        filename, [k for k in ns.keys() if not k.startswith("__")][:12]))
    return ns


def setKey(obj, key):
    if isinstance(obj, dict):
        obj["siteKey"] = key
    else:
        try:
            setattr(obj, "siteKey", key)
        except Exception:
            pass
    return ""


def download(target, url):
    try:
        import urllib.request
        os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"})
        with urllib.request.urlopen(req, timeout=30) as r, open(target, "wb") as f:
            f.write(r.read())
    except Exception as e:
        print("py download failed: %s" % e)


def getDependence(obj):
    fn = _resolve_fn(obj, "getDependence")
    if fn is None:
        return []
    try:
        dep = fn()
        return list(dep) if dep else []
    except Exception:
        return []


def init(obj, ext):
    return _safe(obj, "init", ext)


def destroy(obj):
    _safe(obj, "destroy")


def action(obj, key):
    return _safe(obj, "action", key)


def homeContent(obj, filter_flag):
    # 有的脚本签名是 homeContent(self) 无参，有的带 filter
    fn = _resolve_fn(obj, "homeContent")
    if fn is None:
        return ""
    try:
        try:
            return _dumps(fn(bool(filter_flag)))
        except TypeError:
            return _dumps(fn())
    except Exception as e:
        print("py spider error: homeContent -> %s" % e)
        return ""


def homeVideoContent(obj):
    return _safe(obj, "homeVideoContent")


def categoryContent(obj, tid, pg, filter_flag, ext):
    fn = _resolve_fn(obj, "categoryContent")
    if fn is None:
        return ""
    try:
        try:
            return _dumps(fn(tid, str(pg), bool(filter_flag), ext or ""))
        except TypeError:
            try:
                return _dumps(fn(tid, str(pg), bool(filter_flag)))
            except TypeError:
                return _dumps(fn(tid, str(pg)))
    except Exception as e:
        print("py spider error: categoryContent -> %s" % e)
        return ""


def _to_ids(ids):
    if ids is None:
        return []
    if isinstance(ids, str):
        try:
            parsed = json.loads(ids)
        except Exception:
            return [ids] if ids else []
        if isinstance(parsed, list):
            return [str(x) for x in parsed]
        return [str(parsed)] if parsed else []
    if isinstance(ids, dict):
        return [str(ids)]
    try:
        return [str(x) for x in ids]
    except Exception:
        return [str(ids)]


def detailContent(obj, ids):
    return _safe(obj, "detailContent", _to_ids(ids))


def searchContent(obj, wd, quick, ext=None):
    fn = _resolve_fn(obj, "searchContent")
    if fn is None:
        return ""
    try:
        if ext is None:
            try:
                return _dumps(fn(wd, bool(quick)))
            except TypeError:
                return _dumps(fn(wd))
        try:
            return _dumps(fn(wd, bool(quick), ext))
        except TypeError:
            return _dumps(fn(wd, bool(quick)))
    except Exception as e:
        print("py spider error: searchContent -> %s" % e)
        return ""


def playerContent(obj, flag, pid, ext):
    return _safe(obj, "playerContent", flag, pid, ext or "")


def liveContent(obj, flag):
    return _safe(obj, "liveContent", flag)


def isVideoFormat(obj, url):
    fn = _resolve_fn(obj, "isVideoFormat")
    if fn is None:
        return str(url).split("?")[0].endswith((".mp4", ".m3u8", ".flv", ".mkv", ".ts"))
    try:
        return bool(fn(url))
    except Exception:
        return False


def manualVideoCheck(obj):
    fn = _resolve_fn(obj, "manualVideoCheck")
    if fn is None:
        return False
    try:
        return bool(fn())
    except Exception:
        return False


def localProxy(obj, params_json):
    try:
        params = json.loads(params_json) if params_json else {}
    except Exception:
        params = {}
    fn = _resolve_fn(obj, "localProxy")
    if fn is None:
        return [404, "text/plain", "", None, 0]
    try:
        result = fn(params)
        if isinstance(result, (list, tuple)):
            return list(result)
        return [200, "text/plain", str(result), None, 0]
    except Exception as e:
        print("localProxy error: %s" % e)
        return [404, "text/plain", "", None, 0]


def has_method(obj, name):
    """供 Java 侧探测脚本能力。"""
    return _resolve_fn(obj, name) is not None
