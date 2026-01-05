package vn.unlimit.vpngate.activities.paid

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.MainActivity
import vn.unlimit.vpngate.databinding.ActivityLoginBinding
import vn.unlimit.vpngate.dialog.LoadingDialog
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.utils.PaidServerUtil
import vn.unlimit.vpngate.viewmodels.UserViewModel

class LoginActivity : EdgeToEdgeActivity(), View.OnClickListener {
    private var userViewModel: UserViewModel? = null
    private val paidServerUtil = App.instance!!.paidServerUtil!!
    private var loadingDialog: LoadingDialog? = null
    private var isFirstTimeHidePass = true
    private var isClickedLogin = false
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityLoginBinding.inflate(layoutInflater)
        viewBinding = binding
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.btnBackToFree.setOnClickListener(this)
        binding.ivHidePassword.setOnClickListener(this)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.btnLogin.setOnClickListener(this)
        binding.btnSignUp.setOnClickListener(this)
        binding.btnForgotPass.setOnClickListener(this)
        loadingDialog = LoadingDialog.newInstance(getString(R.string.login_loading_text))
        bindViewModel()
    }

    private fun bindViewModel() {
        userViewModel = ViewModelProvider(this)[UserViewModel::class.java]
        userViewModel!!.isLoading.observe(this) { isLoggingIn ->
            if (!isClickedLogin) {
                return@observe
            }
            if (isLoggingIn!!) {
                loadingDialog!!.show(supportFragmentManager, LoadingDialog::class.java.name)
            } else {
                isClickedLogin = false
                loadingDialog?.dismiss()
                if (!userViewModel!!.isLoggedIn.value!!) {
                    val errorMsg: String =
                        if (!userViewModel!!.errorList.value!!.has("code")) {
                            getString(R.string.login_failed)
                        } else if (userViewModel!!.errorList.value!!.get("code") == 101) {
                            getString(R.string.please_activate_account_first)
                        } else if (userViewModel!!.errorList.value!!.get("code") == 102) {
                            if (userViewModel!!.errorList.value!!.has("bannedReason")) {
                                getString(
                                    R.string.account_is_banned,
                                    userViewModel!!.errorList.value!!.get("bannedReason")
                                )
                            } else {
                                getString(R.string.account_is_banned_no_reason)
                            }
                        } else if (userViewModel!!.errorList.value!!.get("code") == 103) {
                            getString(R.string.account_did_not_exist)
                        } else {
                            getString(R.string.login_failed)
                        }
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                } else {
                    paidServerUtil.setStringSetting(
                        PaidServerUtil.SAVED_VPN_PW,
                        binding.txtPassword.text.toString()
                    )
