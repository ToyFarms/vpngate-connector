package vn.unlimit.vpngate.activities.paid

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConfigParser.ConfigParseError
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNManagement
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.utils.TotalTraffic
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.service.SstpVpnService
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.activities.L2TPConnectActivity
import vn.unlimit.vpngate.databinding.ActivityServerBinding
import vn.unlimit.vpngate.dialog.ConnectionUseProtocol
import vn.unlimit.vpngate.models.PaidServer
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.NotificationUtil
import vn.unlimit.vpngate.utils.PaidServerUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.regex.Pattern

class ServerActivity : EdgeToEdgeActivity(), View.OnClickListener, VpnStatus.StateListener,
    VpnStatus.ByteCountListener {
    @Keep
    companion object {
        const val TAG = "ServerActivity"
        private var mVPNService: IOpenVPNServiceInternal? = null
        const val TYPE_FROM_NOTIFY = 1001
        const val TYPE_NORMAL = 1000
        const val TYPE_START = "vn.ulimit.vpngate.TYPE_START"
    }
    private var mPaidServer: PaidServer? = null
    private val paidServerUtil: PaidServerUtil = App.instance!!.paidServerUtil!!
    private val dataUtil: DataUtil = App.instance!!.dataUtil!!
    private var isConnecting = false
    private var isAuthFailed = false
    private var vpnProfile: VpnProfile? = null
    private var btnInstallOpenVpn: Button? = null
    private var btnSaveConfigFile: Button? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var listener: OnSharedPreferenceChangeListener
    private var isSSTPConnectOrDisconnecting = false
    private var isSSTPConnected = false
    private lateinit var binding: ActivityServerBinding
    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            className: ComponentName,
            service: IBinder
        ) {
            mVPNService = IOpenVPNServiceInternal.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mVPNService = null
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityServerBinding.inflate(layoutInflater)
        this.viewBinding = binding
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        supportActionBar!!.hide()
        binding.btnBack.setOnClickListener(this)
        binding.btnL2tpConnect.setOnClickListener(this)
        binding.btnSstpConnect.setOnClickListener(this)
        binding.btnConnect.setOnClickListener(this)
        binding.txtCheckIp.setOnClickListener(this)
        btnSaveConfigFile = findViewById(R.id.btn_save_config_file)
        btnSaveConfigFile?.setOnClickListener(this)
        btnInstallOpenVpn = findViewById(R.id.btn_install_openvpn)
        btnInstallOpenVpn?.setOnClickListener(this)
        bindData()
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
        binding.txtStatus.text = ""
        initSSTP()
    }

    override fun onDestroy() {
        super.onDestroy()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    override fun onResume() {
        super.onResume()
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(this, OpenVPNService::class.java)
                OpenVPNService.mDisplaySpeed =
                    dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
                intent.action = OpenVPNService.START_SERVICE
                bindService(intent, mConnection, BIND_AUTO_CREATE)
            }, 300)
            if (!App.isImportToOpenVPN) {
                btnInstallOpenVpn?.visibility = View.GONE
                btnSaveConfigFile?.visibility = View.GONE
                binding.btnConnect.visibility = View.VISIBLE
            } else {
                binding.btnConnect.visibility = View.GONE
                if (dataUtil.hasOpenVPNInstalled()) {
                    btnSaveConfigFile?.visibility = View.VISIBLE
                    btnInstallOpenVpn?.visibility = View.GONE
                } else {
                    btnSaveConfigFile?.visibility = View.GONE
                    btnInstallOpenVpn?.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        try {
            super.onPause()
            TotalTraffic.saveTotal(this)
            unbindService(mConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkStatus(): Boolean {
        try {
            return VpnStatus.isVPNActive()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun sendConnectVPN() {
        val intent = Intent(BaseProvider.ACTION.ACTION_CONNECT_VPN)
        sendBroadcast(intent)
    }

    private fun prepareVpn(useUdp: Boolean) {
        if (loadVpnProfile(useUdp)) {
            startVpn()
        } else {
            Toast.makeText(this, getString(R.string.error_load_profile), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleConnection(useUdp: Boolean) {
        if (isSSTPConnected) {
            startVpnSSTPService(DetailActivity.ACTION_VPN_DISCONNECT)
        }
        if (checkStatus()) {
            stopVpn()
            binding.txtCheckIp.visibility = View.GONE
            Handler(Looper.getMainLooper()).postDelayed({ prepareVpn(useUdp) }, 500)
        } else {
            prepareVpn(useUdp)
        }
        binding.btnConnect.background =
            ResourcesCompat.getDrawable(resources, R.drawable.selector_apply_button, null)
        binding.txtStatus.text = getString(R.string.connecting)
        isConnecting = true
        binding.btnConnect.setText(R.string.cancel)
        paidServerUtil.setLastConnectServer(mPaidServer!!)
        sendConnectVPN()
    }

    private fun bindData() {
        mPaidServer = if (intent.getIntExtra(TYPE_START, TYPE_NORMAL) == TYPE_FROM_NOTIFY) {
            paidServerUtil.getLastConnectServer()
        } else {
            IntentCompat.getParcelableExtra(
                intent, BaseProvider.PASS_DETAIL_VPN_CONNECTION,
                PaidServer::class.java
            )
        }
        try {
            Glide.with(this)
                .load(App.instance!!.dataUtil!!.baseUrl + "/images/flags/" + mPaidServer!!.serverCountryCode + ".png")
                .placeholder(R.color.colorOverlay)
                .error(R.color.colorOverlay)
                .into(binding.imgFlag)
            binding.txtCountry.text = mPaidServer!!.serverLocation
            binding.txtIp.text = mPaidServer!!.serverIp
            binding.txtHostname.text = mPaidServer!!.serverName
            binding.txtDomain.text = mPaidServer!!.serverDomain
            binding.txtSession.text = mPaidServer!!.sessionCount.toString()
            binding.txtMaxSession.text = mPaidServer!!.maxSession.toString()
            when {
                mPaidServer!!.serverStatus === "Full" -> {
                    binding.txtStatusColor.setTextColor(
                        ResourcesCompat.getColor(
                            resources,
                            R.color.colorRed,
                            null
                        )
                    )
                    binding.txtStatusText.text = getText(R.string.full)
                }

                mPaidServer!!.serverStatus === "Medium" -> {
                    binding.txtStatusColor.setTextColor(
                        ResourcesCompat.getColor(
                            resources,
                            R.color.colorAccent,
                            null
                        )
                    )
                    binding.txtStatusText.text = getText(R.string.medium)
                }

                else -> {
                    binding.txtStatusColor.setTextColor(
                        ResourcesCompat.getColor(
                            resources,
                            R.color.colorGoodStatus,
                            null
                        )
                    )
                    binding.txtStatusText.text = getText(R.string.good)
                }
            }
            if (mPaidServer!!.tcpPort > 0) {
                binding.lnTcp.visibility = View.VISIBLE
                binding.txtTcpPort.text = mPaidServer!!.tcpPort.toString()
            } else {
                binding.lnTcp.visibility = View.GONE
            }
            if (mPaidServer!!.udpPort > 0) {
                binding.lnUdp.visibility = View.VISIBLE
                binding.txtUdpPort.text = mPaidServer!!.udpPort.toString()
            } else {
                binding.lnUdp.visibility = View.GONE
            }
            if (mPaidServer!!.isL2TPSupport()) {
                binding.lnL2tp.visibility = View.VISIBLE
                binding.btnL2tpConnect.visibility = View.VISIBLE
            } else {
                binding.lnL2tp.visibility = View.GONE
                binding.btnL2tpConnect.visibility = View.GONE
            }
            if (mPaidServer!!.isSSTPSupport()) {
                binding.lnSstp.visibility = View.VISIBLE
                binding.lnSstpBtn.visibility = View.VISIBLE
            } else {
                binding.lnSstp.visibility = View.GONE
                binding.lnSstpBtn.visibility = View.GONE
            }
            if (isCurrent() && checkStatus()) {
                binding.btnConnect.text = resources.getString(R.string.disconnect)
                binding.btnConnect.background =
                    ResourcesCompat.getDrawable(resources, R.drawable.selector_apply_button, null)
                binding.txtStatus.text = VpnStatus.getLastCleanLogMessage(this)
                binding.txtNetStats.visibility = View.VISIBLE
            } else {
                binding.txtNetStats.visibility = View.GONE
            }
            if (checkStatus()) {
                binding.txtCheckIp.visibility = View.VISIBLE
            }
        } catch (th: Throwable) {
            Log.e(TAG, "Bind data error", th)
            th.printStackTrace()
        }
    }

    private fun initSSTP() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        listener =
            OnSharedPreferenceChangeListener { _: SharedPreferences, key: String? ->
                if (OscPrefKey.ROOT_STATE.toString() == key) {
                    val newState = prefs.getBoolean(OscPrefKey.ROOT_STATE.toString(), false)
                    if (!newState) {
                        binding.btnSstpConnect.background = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.selector_primary_button,
                            null
                        )
                        binding.btnSstpConnect.setText(R.string.connect_via_sstp)
                        if (isSSTPConnectOrDisconnecting) {
                            binding.txtStatus.setText(R.string.sstp_disconnected)
                        } else {
                            binding.txtStatus.setText(R.string.sstp_disconnected_by_error)
                        }
                        isSSTPConnected = false
                        paidServerUtil.clearCurrentSession()
                        binding.txtCheckIp.visibility = View.GONE
                    }
                    isSSTPConnectOrDisconnecting = false
                }
                if (OscPrefKey.HOME_CONNECTED_IP.toString() == key) {
                    val connectedIp =
                        prefs.getString(OscPrefKey.HOME_CONNECTED_IP.toString(), "")
                    if ("" != connectedIp) {
                        binding.btnSstpConnect.background = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.selector_red_button,
                            null
                        )
                        binding.btnSstpConnect.setText(R.string.disconnect_sstp)
                        binding.txtStatus.text = getString(R.string.sstp_connected, connectedIp)
                        paidServerUtil.setCurrentSession(mPaidServer!!._id, connectedIp!!)
                        isSSTPConnected = true
                        binding.txtCheckIp.visibility = View.VISIBLE
                    }
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        isSSTPConnected = prefs.getBoolean(OscPrefKey.ROOT_STATE.toString(), false)
        val sstpHostName = prefs.getString(OscPrefKey.HOME_HOSTNAME.toString(), "")
        if (isSSTPConnected) {
            binding.txtCheckIp.visibility = View.VISIBLE
            if (sstpHostName == mPaidServer!!.serverDomain) {
                binding.btnSstpConnect.background = ResourcesCompat.getDrawable(
                    resources, R.drawable.selector_red_button, null
                )
                binding.btnSstpConnect.setText(R.string.disconnect_sstp)
            }
        }
    }

    private fun connectSSTPVPN() {
        prefs.edit {
            putString(
                OscPrefKey.HOME_HOSTNAME.toString(),
                mPaidServer!!.serverDomain
            )
            putString(
                OscPrefKey.HOME_COUNTRY.toString(),
                mPaidServer!!.serverCountryCode.uppercase()
            )
            putString(
                OscPrefKey.HOME_USERNAME.toString(),
                paidServerUtil.getUserInfo()!!.username
            )
            putString(
                OscPrefKey.HOME_PASSWORD.toString(),
                paidServerUtil.getStringSetting(PaidServerUtil.SAVED_VPN_PW)
            )
            putString(OscPrefKey.SSL_PORT.toString(), mPaidServer!!.tcpPort.toString())
        }
        binding.btnSstpConnect.background = ResourcesCompat.getDrawable(
            resources,
            R.drawable.selector_apply_button,
            null
        )
        binding.btnSstpConnect.setText(R.string.cancel_sstp)
        binding.txtStatus.setText(R.string.sstp_connecting)
        startVpnSSTPService(DetailActivity.ACTION_VPN_CONNECT)
    }

    private fun startVpnSSTPService(action: String) {
        val intent = Intent(applicationContext, SstpVpnService::class.java).setAction(action)
        if (action == DetailActivity.ACTION_VPN_CONNECT && VERSION.SDK_INT >= VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
    }

    private val startActivityIntentSSTPVPN: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        handleActivityResult(DetailActivity.START_VPN_SSTP, it.resultCode)
    }

    private fun startSSTPVPN() {
        if (checkStatus()) {
            stopVpn()
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            try {
                startActivityIntentSSTPVPN.launch(intent)
            } catch (_: ActivityNotFoundException) {
                Log.e(TAG, "OS does not support VPN")
            }
        } else {
            handleActivityResult(DetailActivity.START_VPN_SSTP, RESULT_OK)
        }
    }

    private fun handleSSTPBtn() {
        isSSTPConnectOrDisconnecting = true
            stopVpn()
            binding.btnConnect.background =
                ResourcesCompat.getDrawable(resources, R.drawable.selector_primary_button, null)
            binding.btnConnect.setText(R.string.connect_to_this_server)
            binding.txtStatus.text = getString(R.string.canceled)
            isConnecting = false
        }
    }

    override fun onClick(v: View?) {
        when (v) {
            binding.btnBack -> onBackPressedDispatcher.onBackPressed()
            binding.btnL2tpConnect -> {
                val intentL2TP = Intent(this, L2TPConnectActivity::class.java)
                intentL2TP.putExtra(BaseProvider.L2TP_SERVER_TYPE, L2TPConnectActivity.TYPE_PAID)
                intentL2TP.putExtra(BaseProvider.PASS_DETAIL_VPN_CONNECTION, mPaidServer)
                startActivity(intentL2TP)
            }

            binding.btnSstpConnect -> handleSSTPBtn()
            binding.btnConnect -> connectVPNServer()
            binding.txtCheckIp -> {
