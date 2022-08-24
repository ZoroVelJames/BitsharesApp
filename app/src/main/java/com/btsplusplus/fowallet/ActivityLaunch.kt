package com.btsplusplus.fowallet

import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import bitshares.*
import bitshares.serializer.T_Base
import com.btsplusplus.fowallet.utils.VcUtils
import com.flurry.android.FlurryAgent
import com.fowallet.walletcore.bts.ChainObjectManager
import com.fowallet.walletcore.bts.WalletManager
import org.json.JSONObject
import java.util.*

class ActivityLaunch : BtsppActivity() {

    companion object {
        /**
         *  (public)
        Detect APP update data.
         */
        fun checkAppUpdate(): Promise {
            if (BuildConfig.kAppCheckUpdate) {
                val p = Promise()
                val version_url = "https://btspp.io/app/android/${BuildConfig.kAppChannelID}_${Utils.appVersionName()}/version.json?t=${Date().time}"
                OrgUtils.asyncJsonGet(version_url).then {
                    p.resolve(it as? JSONObject)
                    return@then null
                }.catch {
                    p.resolve(null)
                }
                return p
            } else {
                return Promise._resolve(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //  Initialize Flurry
        FlurryAgent.Builder().withLogEnabled(true).build(this, "H45RRHMWCPMKZNNKR5SR")

        //
        //Initialize the startup interface
        setFullScreen()

        //
        //Initialize the Graphene object serialization class
        T_Base.registerAllType()

        //  Initialization parameters
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        Utils.screen_width = dm.widthPixels.toFloat()
        Utils.screen_height = dm.heightPixels.toFloat()
        OrgUtils.initDir(this.applicationContext)
        AppCacheManager.sharedAppCacheManager().initload()

        //
        //Statistics device information
        val accountName = WalletManager.sharedWalletManager().getWalletAccountName()
        if (accountName != null && accountName != "") {
            FlurryAgent.setUserId(accountName)
        }

        //
        //Initial configuration
        initCustomConfig()

        //  startup log
        btsppLogCustom("event_app_start", jsonObjectfromKVS("ver", Utils.appVersionName()))

        //  Start after initialization
        startInit(true)
    }

    /**
     * start init graphene network & app
     */
    private fun startInit(first_init: Boolean) {
        val waitPromise = asyncWait()
        ActivityLaunch.checkAppUpdate().then {
            val pVersionConfig = it as? JSONObject
            SettingManager.sharedSettingManager().serverConfig = pVersionConfig
            return@then Promise.all(waitPromise, asyncInitBitshares(first_init)).then {
                _onLoadVersionJsonFinish(pVersionConfig)
                return@then null
            }
        }.catch {
            if (first_init) {
                showToast(resources.getString(R.string.tip_network_error))
            }
            //  auto restart
            OrgUtils.asyncWait(1000).then {
                startInit(false)
            }
        }
    }

    /**
     * Version loaded
     */
    private fun _onLoadVersionJsonFinish(pConfig: JSONObject?) {
        val bFoundNewVersion = VcUtils.processCheckAppVersionResponsed(this, pConfig) {
            //
            //There is a new version, but remind me later. start directly.
            _enterToMain()
        }
        if (!bFoundNewVersion) {
            //  No new version, start directly.
            _enterToMain()
        }
    }

    /**
     *
    Enter the main interface
     */
    private fun _enterToMain() {
        var homeClass: Class<*> = ActivityIndexMarkets::class.java
        if (!BuildConfig.kAppModuleEnableTabMarket) {
            homeClass = ActivityIndexCollateral::class.java
        }
        if (!BuildConfig.kAppModuleEnableTabDebt) {
            homeClass = ActivityIndexServices::class.java
        }
        val intent = Intent()
        intent.setClass(this, homeClass)
        startActivity(intent)
    }

    /**
     *
    forced to wait
     */
    private fun asyncWait(): Promise {
        return OrgUtils.asyncWait(2000)
    }

    /**
     * Initialize the BTS network and execute it once when the APP starts.
     */
    private fun asyncInitBitshares(first_init: Boolean): Promise {
        val p = Promise()

        val connMgr = GrapheneConnectionManager.sharedGrapheneConnectionManager()
        val chainMgr = ChainObjectManager.sharedChainObjectManager()
        val pAppCache = AppCacheManager.sharedAppCacheManager()

        //  initialize link
        connMgr.Start(resources.getString(R.string.serverWssLangKey), force_use_random_node = !first_init).then { success ->
            //
            //Initialize network related data
            chainMgr.grapheneNetworkInit().then { data ->
                //  Initialize dependent assets (built-in assets + custom trading peers, etc.)
                val dependence_syms = chainMgr.getConfigDependenceAssetSymbols()
                val custom_asset_ids = pAppCache.get_fav_markets_asset_ids()
                return@then chainMgr.queryAssetsBySymbols(symbols = dependence_syms, asset_ids = custom_asset_ids).then {
                    if (BuildConfig.DEBUG) {
                        //  Make sure the query is successful
                        for (sym in dependence_syms.forin<String>()) {
                            chainMgr.getAssetBySymbol(sym!!)
                        }
                        for (oid in custom_asset_ids.forin<String>()) {
                            chainMgr.getChainObjectByID(oid!!)
                        }
                    }
                    //
                    //Generate market data structures
                    chainMgr.buildAllMarketsInfos()
                    //  Initialize logically related data
                    val walletMgr = WalletManager.sharedWalletManager()
                    val promise_map = JSONObject().apply {
                        put("kInitTickerData", chainMgr.marketsInitAllTickerData())
                        put("kInitGlobalProperties", connMgr.last_connection().async_exec_db("get_global_properties"))
                        put("kInitFeeAssetInfo", chainMgr.queryFeeAssetListDynamicInfo())     //  查询手续费兑换比例、手续费池等信息
                        //  Refresh current account information every time you start
                        if (walletMgr.isWalletExist()) {
                            put("kInitFullUserData", chainMgr.queryFullAccountInfo(walletMgr.getWalletInfo().getString("kAccountName")))
                        }
                        //  Initialize OTC data
                        put("kQueryConfig", OtcManager.sharedOtcManager().queryConfig())
                    }
                    return@then Promise.map(promise_map).then {
                        //  Update global properties
                        val data_hash = it as JSONObject
                        chainMgr.updateObjectGlobalProperties(data_hash.getJSONObject("kInitGlobalProperties"))
                        //  Update account full data
                        val full_account_data = data_hash.optJSONObject("kInitFullUserData")
                        if (full_account_data != null) {
                            AppCacheManager.sharedAppCacheManager().updateWalletAccountInfo(full_account_data)
                        }
                        //  After initialization is complete: start the scheduled scheduling task
                        ScheduleManager.sharedScheduleManager().startTimer()
                        ScheduleManager.sharedScheduleManager().autoRefreshTickerScheduleByMergedMarketInfos()
                        //  loading finished
                        p.resolve(true)
                        return@then null
                    }
                }
            }.catch {
                p.reject(resources.getString(R.string.tip_network_error))
            }
            return@then null
        }.catch {
            p.reject(resources.getString(R.string.tip_network_error))
        }
        return p
    }

    fun setFullScreen() {
        val dector_view: View = window.decorView
        val option: Int = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        dector_view.systemUiVisibility = option
        window.navigationBarColor = TRANSPARENT
    }

    /**
     * Initialize custom startup settings: (startup is performed only once)
     */
    private fun initCustomConfig() {
        //Initialize the cache
        ChainObjectManager.sharedChainObjectManager().initConfig(this)
    }
}
