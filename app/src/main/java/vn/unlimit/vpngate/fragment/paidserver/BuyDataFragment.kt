package vn.unlimit.vpngate.fragment.paidserver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import de.blinkt.openvpn.core.OpenVPNService
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.paid.LoginActivity
import vn.unlimit.vpngate.activities.paid.PaidServerActivity
import vn.unlimit.vpngate.adapter.OnItemClickListener
import vn.unlimit.vpngate.adapter.SkuDetailsAdapter
import vn.unlimit.vpngate.databinding.FragmentBuyDataBinding
import vn.unlimit.vpngate.dialog.LoadingDialog
import vn.unlimit.vpngate.viewmodels.PurchaseViewModel
import vn.unlimit.vpngate.viewmodels.UserViewModel
import java.util.Collections

class BuyDataFragment : Fragment(), View.OnClickListener, OnItemClickListener {
    private lateinit var binding: FragmentBuyDataBinding
    private var listSkus: Array<String>? = null
    private var dataUtil = App.instance!!.dataUtil!!
    private var paidServerUtil = App.instance!!.paidServerUtil!!
    private var billingClient: BillingClient? = null
    private var skuDetailsAdapter: SkuDetailsAdapter? = null
    private var isBillingDisconnected = false
    private var userViewModel: UserViewModel? = null
    private var buyingProductDetails: ProductDetails? = null
    private var isClickedBuyData = false
    private var isAttached = false
    private var loadingDialog: LoadingDialog? = null
    private var paidServerActivity: PaidServerActivity? = null
    private var purchaseViewModel: PurchaseViewModel? = null
    private val purchasesUpdatedListener =
        PurchasesUpdatedListener { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (purchase in purchases) {
                    handlePurchase(purchase)
                }
            } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                }
                Log.e(
                    TAG,
                    "Error when process purchase with error code %s. Msg: %s".format(
                        billingResult.responseCode,
                        billingResult.debugMessage
                    )
                )
            }
        }

    companion object {
        const val TAG = "BuyDataFragment"
    }

    override fun onResume() {
        super.onResume()
        if (isBillingDisconnected) {
            initBilling()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        isAttached = true
    }

    override fun onDetach() {
        super.onDetach()
        isAttached = false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentBuyDataBinding.inflate(layoutInflater)
        binding.txtDataSize.text = OpenVPNService.humanReadableByteCount(
            paidServerUtil.getUserInfo()!!.dataSize!!, false, resources
        )
        binding.btnBack.setOnClickListener(this)
        binding.rcvSkuDetails.layoutManager = LinearLayoutManager(context)
        skuDetailsAdapter = SkuDetailsAdapter(context)
        skuDetailsAdapter!!.setOnItemClickListener(this)
        binding.rcvSkuDetails.adapter = skuDetailsAdapter
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        billingClient = BillingClient.newBuilder(requireActivity())
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        initBilling()
        bindViewModel()
    }

    private fun bindViewModel() {
        paidServerActivity = activity as PaidServerActivity
        userViewModel = paidServerActivity!!.userViewModel
        userViewModel?.userInfo?.observe(viewLifecycleOwner) { userInfo ->
            run {
                if (isAttached) {
                    binding.txtDataSize.text = OpenVPNService.humanReadableByteCount(
                        userInfo!!.dataSize!!,
                        false,
                        resources
                    )
                }
            }
        }
        purchaseViewModel = ViewModelProvider(this)[PurchaseViewModel::class.java]
        purchaseViewModel?.isLoggedIn?.observe(viewLifecycleOwner) { isLoggedIn ->
            if (!isLoggedIn) {
                // Go to login screen if user login status is changed
                val intentLogin = Intent(paidServerActivity, LoginActivity::class.java)
                startActivity(intentLogin)
                paidServerActivity!!.finish()
            }
        }
        purchaseViewModel?.isLoading?.observe(viewLifecycleOwner) { isLoading ->
            if (!isClickedBuyData) {
                return@observe
            }
            if (!isLoading) {
                isClickedBuyData = false
                loadingDialog!!.dismiss()
                if (userViewModel?.errorCode == null) {
                    // Create purchase complete
                    Log.i(
                        TAG,
                        "Purchase product %s complete".format(buyingProductDetails?.productId)
                    )
                    // Force fetch user to update data size
                    userViewModel?.fetchUser(forceFetch = true)
                    Toast.makeText(
                        context,
                        getString(R.string.purchase_successful, buyingProductDetails?.title),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    var errorMsg = R.string.invalid_purchase_request
                    if (userViewModel?.errorCode == 112) {
                        errorMsg = R.string.invalid_product_id
                    } else if (userViewModel?.errorCode == 111) {
                        errorMsg = R.string.duplicate_purchase_create_request
                    }
