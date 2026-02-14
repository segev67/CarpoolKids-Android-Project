package dev.segev.carpoolkids

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import dev.segev.carpoolkids.databinding.FragmentDashboardPlaceholderBinding

class DriversRequestsFragment : Fragment() {

    private var _binding: FragmentDashboardPlaceholderBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardPlaceholderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.placeholderTitle.setText(R.string.dashboard_drivers_requests_title)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
