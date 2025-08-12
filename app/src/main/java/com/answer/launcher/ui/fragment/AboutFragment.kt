package com.answer.launcher.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.answer.launcher.databinding.FragmentAboutBinding
import com.answer.launcher.utils.CrashHandler

class AboutFragment : Fragment() {
        lateinit var binding : FragmentAboutBinding;

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?): View? {
            binding = FragmentAboutBinding.inflate(inflater, container, false)

            binding.test.setOnClickListener {
                CrashHandler.crash()
            }
            
            return binding.root;
        }

        override fun onDestroy() {
            super.onDestroy()
        }
}