package com.xing.tv.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.xing.tv.R
import com.xing.tv.databinding.ActivityMainBinding

/**
 * 主界面：底栏对齐星海（主页 / 直播 / 短剧 / 历史 / 我的）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val fragments = HashMap<Int, Fragment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val home = supportFragmentManager.findFragmentByTag(TAG_HOME) ?: HomeFragment().also {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragContainer, it, TAG_HOME)
                .commitNow()
        }
        fragments[R.id.nav_home] = home

        b.btnFilter.setOnClickListener {
            val homeFrag = fragments[R.id.nav_home] as? HomeFragment
                ?: supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment
            homeFrag?.openFilterSheet()
        }

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    show(TAG_HOME)
                }
                R.id.nav_live -> {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    show(TAG_LIVE)
                }
                R.id.nav_short -> {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    show(TAG_SHORT)
                }
                R.id.nav_history -> {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    show(TAG_HISTORY)
                }
                R.id.nav_mine -> {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    show(TAG_MINE)
                }
            }
            refreshFilterEntry()
            true
        }
        b.bottomNav.selectedItemId = R.id.nav_home
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (b.bottomNav.selectedItemId != R.id.nav_home) {
            b.bottomNav.selectedItemId = R.id.nav_home
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    fun syncFilterEntry(visible: Boolean, activeCount: Int) {
        if (!::b.isInitialized) return
        applyFilterEntry(visible && b.bottomNav.selectedItemId == R.id.nav_home, activeCount)
    }

    private fun refreshFilterEntry() {
        if (!::b.isInitialized) return
        val home = fragments[R.id.nav_home] as? HomeFragment
            ?: supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment
        val onHome = b.bottomNav.selectedItemId == R.id.nav_home
        applyFilterEntry(onHome && home?.filterEntryVisible() == true, home?.filterActiveCount() ?: 0)
    }

    private fun applyFilterEntry(visible: Boolean, activeCount: Int) {
        b.btnFilter.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val active = activeCount > 0
        b.btnFilter.setBackgroundResource(
            if (active) R.drawable.bg_filter_fab_active else R.drawable.bg_filter_fab
        )
        b.ivFilter.imageTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (active) R.color.on_primary else R.color.text_primary)
        )
        b.tvFilterBadge.visibility = if (active) View.VISIBLE else View.GONE
        b.tvFilterBadge.text = activeCount.toString()
    }

    override fun onResume() {
        super.onResume()
        refreshFilterEntry()
    }

    private fun show(tag: String) {
        val id = idOf(tag)
        val target = fragments[id] ?: newFragment(tag).also { fragments[id] = it }
        supportFragmentManager.beginTransaction().apply {
            fragments.values.forEach { if (it !== target) hide(it) }
            if (!target.isAdded) add(R.id.fragContainer, target, tag)
            show(target)
        }.commitNowAllowingStateLoss()
    }

    private fun idOf(tag: String): Int = when (tag) {
        TAG_HOME -> R.id.nav_home
        TAG_LIVE -> R.id.nav_live
        TAG_SHORT -> R.id.nav_short
        TAG_HISTORY -> R.id.nav_history
        else -> R.id.nav_mine
    }

    private fun newFragment(tag: String): Fragment = when (tag) {
        TAG_HOME -> HomeFragment()
        TAG_LIVE -> LiveFragment()
        TAG_SHORT -> ShortFragment()
        TAG_HISTORY -> HistoryFragment()
        else -> MineFragment()
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_LIVE = "live"
        private const val TAG_SHORT = "short"
        private const val TAG_HISTORY = "history"
        private const val TAG_MINE = "mine"
    }
}
