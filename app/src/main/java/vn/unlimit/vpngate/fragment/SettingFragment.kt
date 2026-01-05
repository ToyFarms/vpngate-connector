package vn.unlimit.vpngate.fragment

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.InetAddresses.isNumericAddress
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.Spanned
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import de.blinkt.openvpn.core.OpenVPNService
import kittoku.osc.preference.OscPrefKey
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.App.Companion.instance
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.activities.MainActivity
import vn.unlimit.vpngate.databinding.FragmentSettingBinding
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.SpinnerInit
import vn.unlimit.vpngate.utils.SpinnerInit.OnItemSelectedIndexListener
import java.text.DateFormat

/**
 * Created by dongh on 31/01/2018.
 */
class SettingFragment : Fragment(), View.OnClickListener, AdapterView.OnItemSelectedListener,
    CompoundButton.OnCheckedChangeListener, OnFocusChangeListener {
    private lateinit var dataUtil: DataUtil
    private lateinit var mContext: Context
    private lateinit var prefs: SharedPreferences

    private lateinit var binding: FragmentSettingBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedState: Bundle?
    ): View {
        binding = FragmentSettingBinding.inflate(layoutInflater)
        dataUtil = instance!!.dataUtil!!
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        binding.spinCacheTime.onItemSelectedListener = this
        binding.btnClearCache.setOnClickListener(this)
        binding.lnUdp.setOnClickListener(this)
        binding.swUdp.setChecked(dataUtil.getBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, true))
        binding.swUdp.setOnCheckedChangeListener(this)
        val spinnerInit = SpinnerInit(context, binding.spinCacheTime)
        val listCacheTime = resources.getStringArray(R.array.setting_cache_time)
        spinnerInit.setStringArray(
            listCacheTime,
            listCacheTime[dataUtil.getIntSetting(DataUtil.SETTING_CACHE_TIME_KEY, 0)]
        )
        spinnerInit.onItemSelectedIndexListener = object : OnItemSelectedIndexListener {
            override fun onItemSelected(name: String?, index: Int) {
                dataUtil.setIntSetting(DataUtil.SETTING_CACHE_TIME_KEY, index)
            }
        }
        onHiddenChanged(false)
        binding.lnDns.setOnClickListener(this)
        if (dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false)) {
            binding.swDns.setChecked(true)
            binding.lnDnsIp.visibility = View.VISIBLE
        } else {
            binding.swDns.setChecked(false)
            binding.lnDnsIp.visibility = View.GONE
        }
        binding.swDns.setOnCheckedChangeListener(this)
        val inputFilters = this.ipInputFilters
        binding.txtDns1.setFilters(inputFilters)
        binding.txtDns1.setText(dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_1, "8.8.8.8"))
        binding.txtDns1.onFocusChangeListener = this
        binding.txtDns2.setFilters(inputFilters)
        binding.txtDns2.setText(dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_2, ""))
        binding.txtDns2.onFocusChangeListener = this
        binding.lnDomain.setOnClickListener(this)
        binding.swDomain.setChecked(dataUtil.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false))
        binding.swDomain.setOnCheckedChangeListener(this)
        binding.spinProto.onItemSelectedListener = this
        val listProtocol = resources.getStringArray(R.array.list_protocol)
        val spinnerInitProto = SpinnerInit(context, binding.spinProto)
        spinnerInitProto.setStringArray(
            listProtocol,
            listProtocol[dataUtil.getIntSetting(DataUtil.SETTING_DEFAULT_PROTOCOL, 0)]
        )
        spinnerInitProto.onItemSelectedIndexListener = object : OnItemSelectedIndexListener {
            override fun onItemSelected(name: String?, index: Int) {
            return
        }
        val editor = prefs.edit()
        if (switchCompat == binding.swDns) {
            dataUtil.setBooleanSetting(DataUtil.USE_CUSTOM_DNS, isChecked)
            if (isChecked) {
                binding.lnDnsIp.visibility = View.VISIBLE
                binding.txtDns1.requestFocus()
                val imm =
                    mContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.txtDns1, InputMethodManager.SHOW_IMPLICIT)
                editor.putBoolean(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), true)
            } else {
                hideKeyBroad()
                binding.lnDnsIp.visibility = View.GONE
                editor.remove(OscPrefKey.DNS_CUSTOM_ADDRESS.toString())
                editor.putBoolean(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), false)
            }
            editor.apply()
            return
        }
        if (switchCompat == binding.swDomain) {
            dataUtil.setBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, isChecked)
            return
        }
        if (switchCompat == binding.swNotifySpeed) {
            Toast.makeText(
                context,
                getText(R.string.setting_apply_on_next_connection_time),
                Toast.LENGTH_SHORT
            ).show()
            dataUtil.setBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, isChecked)
            return
        }
        if (dataUtil.hasAds() && isChecked) {
            switchCompat.isChecked = false
            Toast.makeText(context, getString(R.string.feature_available_in_pro), Toast.LENGTH_LONG)
                .show()
            return
        }
    }

    private fun sendClearCache() {
        try {
            val intent = Intent(BaseProvider.ACTION.ACTION_CLEAR_CACHE)
            mContext.sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
        dataUtil.setIntSetting(DataUtil.SETTING_CACHE_TIME_KEY, position)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
    }
}
