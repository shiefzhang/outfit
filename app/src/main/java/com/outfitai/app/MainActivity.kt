package com.outfitai.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.outfitai.app.api.ApiKeyManager
import com.outfitai.app.databinding.ActivityMainBinding
import com.outfitai.app.ui.detail.DetailFragment
import com.outfitai.app.ui.evaluate.EvaluateFragment
import com.outfitai.app.ui.scene.SceneFragment
import com.outfitai.app.ui.settings.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val evaluateFragment by lazy { EvaluateFragment() }
    private val sceneFragment by lazy { SceneFragment() }
    private val detailFragment by lazy { DetailFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "穿搭AI助手"

        // 初始加载第一个 Fragment
        if (savedInstanceState == null) {
            loadFragment(evaluateFragment, "evaluate")
        }

        binding.bottomNavView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_evaluate -> {
                    loadFragment(evaluateFragment, "evaluate")
                    supportActionBar?.title = "穿搭评分"
                    true
                }
                R.id.navigation_scene -> {
                    loadFragment(sceneFragment, "scene")
                    supportActionBar?.title = "场景建议"
                    true
                }
                R.id.navigation_detail -> {
                    loadFragment(detailFragment, "detail")
                    supportActionBar?.title = "配饰建议"
                    true
                }
                else -> false
            }
        }

        // 首次启动时若没有 API Key，引导用户设置
        if (!ApiKeyManager.hasApiKey(this)) {
            showFirstRunHint()
        }
    }

    private fun loadFragment(fragment: Fragment, tag: String) {
        val transaction = supportFragmentManager.beginTransaction()
        // 隐藏所有 Fragment
        supportFragmentManager.fragments.forEach { transaction.hide(it) }
        // 如果 Fragment 未添加，则添加；否则显示
        if (!fragment.isAdded) {
            transaction.add(R.id.nav_host_fragment, fragment, tag)
        } else {
            transaction.show(fragment)
        }
        transaction.commit()
    }

    private fun showFirstRunHint() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("👋 欢迎使用穿搭AI助手")
            .setMessage("本应用使用智谱 GLM-4V-Flash 免费大模型，请先前往设置配置您的 API Key。\n\n还没有账号？可在智谱开放平台免费注册。")
            .setPositiveButton("前往设置") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("稍后设置", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, "设置")
            .setIcon(android.R.drawable.ic_menu_manage)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val MENU_SETTINGS = 1001
    }
}
