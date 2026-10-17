# -*- coding: utf-8 -*-
"""TVBox 兼容的 Spider 基类（精简版）。

真实影视仓/TVBox 爬虫包里的 base.spider.Spider 功能更全；
这里提供最小可运行子集，让 `from base.spider import Spider` 与
常见方法调用不因缺库直接崩溃。脚本可覆盖任意方法。
"""


class Spider(object):
    def __init__(self):
        self.extend = ""
        self.siteKey = ""
        self.siteUrl = ""

    def init(self, extend=""):
        self.extend = extend or ""

    def homeContent(self, filter=False):
        return {"class": [], "list": []}

    def homeVideoContent(self):
        return {"list": []}

    def categoryContent(self, tid, pg, filter=False, extend=""):
        return {"list": [], "page": pg, "pagecount": 1, "limit": 20, "total": 0}

    def detailContent(self, ids):
        return {"list": []}

    def searchContent(self, key, quick=False, pg="1"):
        return {"list": []}

    def playerContent(self, flag, id, vipFlags=None):
        return {"parse": 0, "url": id, "header": ""}

    def localProxy(self, params):
        return [404, "text/plain", "", None, 0]

    def isVideoFormat(self, url):
        if not url:
            return False
        u = str(url).split("?")[0].lower()
        return u.endswith((".mp4", ".m3u8", ".flv", ".mkv", ".ts", ".m4a"))

    def manualVideoCheck(self):
        return False

    def getName(self):
        return "base"

    def getDependence(self):
        return []

    def setProxyUrl(self, url):
        pass

    def getProxyUrl(self):
        return ""
