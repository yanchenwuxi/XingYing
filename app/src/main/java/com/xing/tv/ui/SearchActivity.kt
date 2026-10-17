package com.xing.tv.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.xing.tv.R
import com.xing.tv.databinding.ActivitySearchBinding

/**
 * 搜索页：顶栏返回 + 复用 [SearchFragment]。
 *
 * 底部导航不再保留搜索 tab（与首页搜索条重复），搜索统一从首页搜索条进入，
 * 这里只是一个承载搜索 Fragment 的薄壳，检索逻辑仍在 Fragment 里。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var b: ActivitySearchBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.searchContainer, SearchFragment())
                .commit()
        }
    }
}
