package com.xing.tv.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xing.tv.R
import com.xing.tv.data.Prefs
import com.xing.tv.databinding.ActivitySettingsBinding
import com.xing.tv.player.PlayerKernelType

/** 播放设置：仅播放偏好（自动换源 / 续播 / 倍速 / 跳片头片尾 / 内核）。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val skipOptions = intArrayOf(0, 5, 10, 15, 30, 60, 90)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        b.switchAuto.isChecked = Prefs.autoSwitchSource
        b.switchAuto.setOnCheckedChangeListener { _, checked -> Prefs.autoSwitchSource = checked }

        b.switchResume.isChecked = Prefs.resumeEnabled
        b.switchResume.setOnCheckedChangeListener { _, checked -> Prefs.resumeEnabled = checked }

        b.tvSpeed.text = "${Prefs.defaultSpeed}x"
        b.rowSpeed.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.Dialog_XingYing)
                .setTitle("默认倍速")
                .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, which ->
                    Prefs.defaultSpeed = speeds[which]
                    b.tvSpeed.text = "${speeds[which]}x"
                }
                .setNegativeButton("取消", null)
                .show()
        }
        b.rowSpeed.setOnLongClickListener {
            val labels = PlayerKernelType.entries.map { it.label }.toTypedArray()
            val cur = PlayerKernelType.fromName(Prefs.playerKernel)
            val checked = PlayerKernelType.entries.indexOf(cur).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this, R.style.Dialog_XingYing)
                .setTitle("播放内核")
                .setSingleChoiceItems(labels, checked) { d, which ->
                    Prefs.playerKernel = PlayerKernelType.entries[which].name
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        b.tvSkipIntro.text = secondsText(Prefs.skipIntroSec)
        b.rowSkipIntro.setOnClickListener { pickSkip("跳过片头") { Prefs.skipIntroSec = it; b.tvSkipIntro.text = secondsText(it) } }
        b.tvSkipOutro.text = secondsText(Prefs.skipOutroSec)
        b.rowSkipOutro.setOnClickListener { pickSkip("跳过片尾") { Prefs.skipOutroSec = it; b.tvSkipOutro.text = secondsText(it) } }
    }

    private fun pickSkip(title: String, onPick: (Int) -> Unit) {
        val labels = skipOptions.map { secondsText(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.Dialog_XingYing)
            .setTitle(title)
            .setItems(labels) { _, which -> onPick(skipOptions[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun secondsText(sec: Int): String = if (sec <= 0) "不跳过" else "${sec} 秒"
}
