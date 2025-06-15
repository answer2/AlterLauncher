package com.answer.launcher.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.answer.launcher.databinding.FragmentDownloadBinding

class DownloadFragment : Fragment() {
    lateinit var binding : FragmentDownloadBinding;

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentDownloadBinding.inflate(inflater, container, false)
        return binding.root;
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}