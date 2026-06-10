package com.outfitai.app.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.outfitai.app.data.HistoryManager
import com.outfitai.app.data.OutfitRecord
import com.outfitai.app.databinding.DialogHistoryDetailBinding
import com.outfitai.app.databinding.FragmentHistoryBinding
import com.outfitai.app.ui.settings.SettingsActivity
import io.noties.markwon.Markwon

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var markwon: Markwon

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        markwon = Markwon.builder(requireContext())
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .build()

        binding.btnClearHistory.setOnClickListener {
            if (HistoryManager.getRecords(requireContext()).isEmpty()) {
                Toast.makeText(requireContext(), "暂无历史记录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("清空历史")
                .setMessage("确定要删除所有穿搭历史记录吗？此操作不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    HistoryManager.clearAll(requireContext())
                    refreshList()
                    Toast.makeText(requireContext(), "已清空历史记录", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        refreshList()
    }

    /**
     * MainActivity 使用 show/hide 切换 Fragment，onResume 不会触发。
     * 必须通过 onHiddenChanged 在切换回历史 tab 时刷新列表。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshList()
        }
    }

    private fun refreshList() {
        val records = HistoryManager.getRecords(requireContext())
        if (records.isEmpty()) {
            binding.rvHistory.visibility = View.GONE
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.btnClearHistory.visibility = View.GONE
        } else {
            binding.rvHistory.visibility = View.VISIBLE
            binding.layoutEmpty.visibility = View.GONE
            binding.btnClearHistory.visibility = View.VISIBLE
            binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
            binding.rvHistory.adapter = HistoryAdapter(records, markwon) { record ->
                showRecordDetail(record)
            }
        }
    }

    private fun showRecordDetail(record: OutfitRecord) {
        val dialogBinding = DialogHistoryDetailBinding.inflate(LayoutInflater.from(requireContext()))

        // 照片
        if (record.imagePath?.isNotBlank() == true) {
            dialogBinding.ivDialogPhoto.visibility = View.VISIBLE
            Glide.with(dialogBinding.ivDialogPhoto.context)
                .load(record.imagePath)
                .centerCrop()
                .into(dialogBinding.ivDialogPhoto)
        } else {
            dialogBinding.ivDialogPhoto.visibility = View.GONE
        }

        // AI 内容
        markwon.setMarkdown(dialogBinding.tvDialogContent, record.aiContent)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("${record.typeName} · ${record.formattedTime}")
            .setView(dialogBinding.root)
            .setPositiveButton("关闭", null)
            .setNeutralButton("🗑 删除") { _, _ ->
                HistoryManager.deleteRecord(requireContext(), record.id)
                refreshList()
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
