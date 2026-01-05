package vn.unlimit.vpngate.fragment.paidserver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import de.blinkt.openvpn.core.OpenVPNService
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.paid.PaidServerActivity
import vn.unlimit.vpngate.activities.paid.ServerActivity
import vn.unlimit.vpngate.adapter.OnItemClickListener
import vn.unlimit.vpngate.adapter.SessionAdapter
import vn.unlimit.vpngate.databinding.FragmentPaidServerHomeBinding
import vn.unlimit.vpngate.dialog.LoadingDialog
import vn.unlimit.vpngate.models.ConnectedSession
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.request.RequestListener
import vn.unlimit.vpngate.utils.SpinnerInit
import vn.unlimit.vpngate.viewmodels.ChartViewModel
import vn.unlimit.vpngate.viewmodels.SessionViewModel
import vn.unlimit.vpngate.viewmodels.UserViewModel


class HomeFragment : Fragment(), SwipeRefreshLayout.OnRefreshListener, View.OnClickListener {
    private lateinit var binding: FragmentPaidServerHomeBinding
    private val paidServerUtil = App.instance!!.paidServerUtil!!
    private var userViewModel: UserViewModel? = null
    private var paidServerActivity: PaidServerActivity? = null
    private var isObservedRefresh = false
    private var isAttached = false
    private var chartViewModel: ChartViewModel? = null
    private var sessionViewModel: SessionViewModel? = null
    private var sessionAdapter: SessionAdapter? = null

    companion object {
        const val TAG = "HomeFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPaidServerHomeBinding.inflate(layoutInflater)
        if (paidServerUtil.getUserInfo() != null) {
            binding.textHome.text = getString(
                R.string.home_paid_welcome,
                paidServerUtil.getUserInfo()!!.username
            )
            binding.txtDataSize.text = OpenVPNService.humanReadableByteCount(
                paidServerUtil.getUserInfo()!!.dataSize ?: 0,
                false,
                resources
            )
        }
        binding.lnSwipeRefresh.setOnRefreshListener(this)
        binding.lnBuyData.setOnClickListener(this)
        binding.lnPurchaseHistory.setOnClickListener(this)
        val chartTypes = resources.getStringArray(R.array.chart_type)
        val spinnerInit = SpinnerInit(requireContext(), binding.incDataChart.spinChartType)
        spinnerInit.setStringArray(chartTypes, chartTypes[0])
        spinnerInit.onItemSelectedIndexListener = object : SpinnerInit.OnItemSelectedIndexListener {
            override fun onItemSelected(name: String?, index: Int) {
                when (index) {
                    0 -> chartViewModel?.chartType?.value = ChartViewModel.ChartType.HOURLY
                    1 -> chartViewModel?.chartType?.value = ChartViewModel.ChartType.DAILY
                    2 -> chartViewModel?.chartType?.value = ChartViewModel.ChartType.MONTHLY
                }
