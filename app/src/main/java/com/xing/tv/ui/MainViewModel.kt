package com.xing.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xing.tv.api.ConfigProvider
import com.xing.tv.api.SourceFactory
import com.xing.tv.data.Prefs
import com.xing.tv.model.Category
import com.xing.tv.model.PageResult
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodFilter
import com.xing.tv.model.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页数据持有者。
 *
 * 把配置加载、站点/分类切换、翻页取数从 Activity 中剥离：
 * - 状态在 Activity 重建（进程回收后恢复、返回栈重建）时得以保留，避免整页重新拉取
 * - 所有网络请求跑在 [viewModelScope]，Activity 结束即取消，不再依赖 lifecycleScope
 * - 过期结果按「当前站点 + 当前分类」校验后才落地，避免切换线路后渲染成别的站点内容
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _sites = MutableLiveData<List<TvConfig.Site>>(emptyList())
    val sites: LiveData<List<TvConfig.Site>> = _sites

    private val _currentSite = MutableLiveData<TvConfig.Site?>(null)
    val currentSite: LiveData<TvConfig.Site?> = _currentSite

    private val _categories = MutableLiveData<List<Category>>(emptyList())
    val categories: LiveData<List<Category>> = _categories

    /** 当前分类 id；空串表示「推荐」 */
    private val _selectedCategory = MutableLiveData("")
    val selectedCategory: LiveData<String> = _selectedCategory

    /** 分类下筛选；切站点/切分类时清空（筛选条件属于具体上下文） */
    private val _filter = MutableLiveData(VodFilter())
    val filter: LiveData<VodFilter> = _filter

    /** 累积的影片列表（翻页追加，切换时清空） */
    private val _items = MutableLiveData<List<VodItem>>(emptyList())
    val items: LiveData<List<VodItem>> = _items

    /** 首次加载/整页刷新的全屏进度 */
    private val _initialLoading = MutableLiveData(false)
    val initialLoading: LiveData<Boolean> = _initialLoading

    /** 下拉刷新的转圈 */
    private val _refreshing = MutableLiveData(false)
    val refreshing: LiveData<Boolean> = _refreshing

    private val _toast = MutableLiveData<SingleEvent<String>>()
    val toast: LiveData<SingleEvent<String>> = _toast

    private var loadedUrl: String = ""
    private var page = 1
    private var pageCount = 1
    private var pageLoading = false
    private var pageJob: Job? = null

    fun activeConfigUrl(): String = ConfigProvider.activeUrl()

    /**
     * 按当前配置地址初始化。
     * 地址未变且已加载过时直接跳过，避免 onResume 反复触发网络请求。
     */
    fun start(url: String = activeConfigUrl(), force: Boolean = false) {
        if (url.isBlank()) {
            val wasEmpty = loadedUrl.isEmpty() && _sites.value.isNullOrEmpty()
            loadedUrl = ""
            _sites.value = emptyList()
            _items.value = emptyList()
            _categories.value = emptyList()
            _currentSite.value = null
            _initialLoading.value = false
            // 首次进入才提示，避免 onResume 反复弹 Toast
            if (!wasEmpty) toast("请先在「接口源」添加配置地址或导入本地文件")
            else if (_items.value.isNullOrEmpty()) {
                // 冷启动无配置：发一次提示
                toast("请先在「接口源」添加配置地址或导入本地文件")
            }
            return
        }
        if (!force && url == loadedUrl && !_sites.value.isNullOrEmpty()) return
        loadConfig(url, force)
    }

    /** 下拉刷新：强制绕过缓存 */
    fun refreshConfig() = start(activeConfigUrl(), force = true)

    private fun loadConfig(url: String, force: Boolean = false) {
        loadedUrl = url
        _initialLoading.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetchConfig(url, force) } }
            _initialLoading.value = false
            result.onSuccess { cfg ->
                _sites.value = cfg.usableSites
                if (cfg.usableSites.isEmpty()) {
                    val msg = when {
                        url.isBlank() -> "请先在「接口源」添加配置"
                        cfg.diag.totalSites > 0 && cfg.diag.supportedSites == 0 ->
                            "配置含 ${cfg.diag.totalSites} 个站点，但数据加载失败（可查看是否为需 jar 的爬虫源）"
                        cfg.diag.usableLives > 0 ->
                            "无点播站点，直播源 ${cfg.diag.usableLives} 个可用，请到「直播」页"
                        else -> "配置中没有可用站点"
                    }
                    toast(msg)
                    return@onSuccess
                }
                val last = cfg.usableSites.firstOrNull { it.key == Prefs.lastSiteKey }
                    ?: cfg.usableSites.first()
                selectSite(last)
            }.onFailure {
                android.util.Log.w("MainViewModel", "load config failed: url=$url", it)
                _sites.value = emptyList()
                toast(
                    if (url.isBlank()) "请先添加接口源"
                    else "配置加载失败: ${it.message}"
                )
            }
        }
    }

    private fun fetchConfig(url: String, force: Boolean): TvConfig =
        ConfigProvider.fetch(url, force = force)

    fun selectSite(site: TvConfig.Site) {
        _currentSite.value = site
        Prefs.lastSiteKey = site.key
        // 分类 id 与筛选都属于具体站点，跨站点沿用会命中不存在的分类而得到空列表
        _selectedCategory.value = ""
        _filter.value = VodFilter()
        loadCategories(site)
        reload()
    }

    /** 分类目录先渲染「推荐」，需要网络的源异步补齐 */
    private fun loadCategories(site: TvConfig.Site) {
        _categories.value = emptyList()
        viewModelScope.launch {
            val cats = withContext(Dispatchers.IO) {
                runCatching { SourceFactory.create(site).categories() }.getOrDefault(emptyList())
            }
            // 站点已切换，丢弃过期结果
            if (_currentSite.value?.key != site.key) return@launch
            _categories.value = cats
        }
    }

    fun selectCategory(id: String) {
        _selectedCategory.value = id
        _filter.value = VodFilter()
        reload()
    }

    /** 当前站点是否支持分类下筛选（macCMS 才有 area/year/class 参数） */
    fun supportsFilter(): Boolean =
        _currentSite.value?.let {
            runCatching { SourceFactory.create(it).supportsFilter }.getOrDefault(false)
        } ?: false

    fun setFilter(f: VodFilter) {
        _filter.value = f
        reload()
    }

    fun reload() {
        // 取消在途翻页请求：否则切换线路/分类时旧响应会覆盖新内容
        pageJob?.cancel()
        pageJob = null
        pageLoading = false
        _refreshing.value = false
        page = 1
        _items.value = emptyList()
        // 下拉刷新要拿最新数据，先让 py 源的 homeContent 缓存失效
        com.xing.tv.py.PySource.invalidate()
        fetchPage(reset = true)
    }

    fun loadMore() {
        if (page >= pageCount || pageLoading) return
        page++
        fetchPage(reset = false)
    }

    private fun fetchPage(reset: Boolean) {
        val site = _currentSite.value ?: return
        if (pageLoading) return
        pageLoading = true
        if (reset) _initialLoading.value = true else _refreshing.value = true

        val category = _selectedCategory.value.orEmpty()
        val requestedPage = page
        val activeFilter = _filter.value ?: VodFilter()

        pageJob = viewModelScope.launch {
            val result: Result<PageResult> = withContext(Dispatchers.IO) {
                runCatching {
                    val source = SourceFactory.create(site)
                    // 传真实页码：首页无限滚动依赖它，否则只会重复第一页
                    if (category.isEmpty() && activeFilter.isEmpty()) source.home(requestedPage)
                    else if (category.isEmpty()) source.categoryFiltered("", requestedPage, activeFilter)
                    else source.categoryFiltered(category, requestedPage, activeFilter)
                }
            }
            pageLoading = false
            _initialLoading.value = false
            _refreshing.value = false
            // 站点/分类/筛选已变化则丢弃，交由新请求渲染
            if (_currentSite.value?.key != site.key) return@launch
            if (_selectedCategory.value.orEmpty() != category) return@launch
            if (_filter.value != activeFilter) return@launch
            result.onSuccess { pr ->
                pageCount = pr.pageCount
                _items.value = if (reset) pr.items else _items.value.orEmpty() + pr.items
            }.onFailure {
                toast("加载失败: ${it.message}")
            }
        }
    }

    private fun toast(msg: String) {
        _toast.value = SingleEvent(msg)
    }
}

/**
 * 一次性事件：消费后不再重复投递，避免 Activity 重建时 Toast 弹第二次。
 */
class SingleEvent<out T>(private val content: T) {
    private var consumed = false

    @Synchronized
    fun consume(): T? =
        if (consumed) null else {
            consumed = true
            content
        }
}
