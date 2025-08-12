package com.answer.launcher.ui.fragment


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.answer.launcher.databinding.FragmentSettingBinding

class SettingFragment : Fragment() {

    lateinit var binding : FragmentSettingBinding;

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentSettingBinding.inflate(inflater, container, false)
        return binding.root;
    }

    override fun onDestroy() {
        super.onDestroy()

    }
}