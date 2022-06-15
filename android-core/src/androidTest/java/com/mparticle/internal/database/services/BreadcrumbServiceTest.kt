package com.mparticle.internal.database.services

import androidx.test.rule.GrantPermissionRule
import com.mparticle.internal.ConfigManager
import com.mparticle.networking.DomainMapping
import com.mparticle.AttributionListener
import com.mparticle.AttributionResult
import com.mparticle.AttributionError
import com.mparticle.identity.BaseIdentityTask
import com.mparticle.identity.TaskFailureListener
import com.mparticle.identity.TaskSuccessListener
import com.mparticle.identity.IdentityApiResult
import com.mparticle.testing.BaseStartedTest
import com.mparticle.identity.IdentityStateListener
import com.mparticle.MParticleTask
import android.os.HandlerThread
import android.os.Looper
import com.mparticle.identity.MParticleIdentityClientImpl
import com.mparticle.internal.MPUtility
import IdentityRequest.IdentityRequestBody
import com.mparticle.identity.MParticleUserDelegate
import com.mparticle.consent.GDPRConsent
import com.mparticle.consent.CCPAConsent
import com.mparticle.identity.MParticleIdentityClientImplTest.MockIdentityApiClient
import com.mparticle.networking.MPConnection
import com.mparticle.identity.MParticleUserImpl
import com.mparticle.networking.MParticleBaseClientImpl
import com.mparticle.internal.database.services.SQLiteOpenHelperWrapper
import com.mparticle.internal.database.tables.BaseTableTest
import com.mparticle.internal.database.TestSQLiteOpenHelper
import com.mparticle.internal.database.tables.MParticleDatabaseHelper
import android.database.sqlite.SQLiteDatabase
import com.mparticle.internal.database.tables.UploadTable
import com.mparticle.internal.database.tables.MessageTable
import com.mparticle.internal.database.tables.MessageTableTest
import android.provider.BaseColumns
import com.mparticle.internal.database.tables.SessionTable
import com.mparticle.internal.database.tables.ReportingTable
import com.mparticle.internal.database.tables.BreadcrumbTable
import com.mparticle.internal.database.tables.UserAttributesTable
import com.mparticle.internal.database.services.MParticleDBManager
import android.database.sqlite.SQLiteOpenHelper
import com.mparticle.internal.database.services.BaseMPServiceTest
import com.mparticle.internal.database.MPDatabaseImpl
import com.mparticle.internal.messages.BaseMPMessage
import com.mparticle.internal.InternalSession
import com.mparticle.internal.database.services.MessageService
import com.mparticle.internal.database.services.MessageService.ReadyMessage
import com.mparticle.internal.database.services.SessionService
import com.mparticle.internal.BatchId
import com.mparticle.internal.MessageBatch
import com.mparticle.internal.database.services.SessionServiceTest.MockMessageBatch
import com.mparticle.internal.JsonReportingMessage
import com.mparticle.internal.database.services.ReportingService
import com.mparticle.internal.database.services.BreadcrumbServiceTest
import com.mparticle.internal.database.services.BreadcrumbService
import com.mparticle.internal.database.services.MParticleDBManager.UserAttributeRemoval
import com.mparticle.internal.database.services.MParticleDBManager.UserAttributeResponse
import com.mparticle.internal.database.services.UserAttributesService
import com.mparticle.internal.database.UpgradeVersionTest
import com.mparticle.internal.database.MPDatabase
import com.mparticle.internal.database.services.UploadService
import com.mparticle.internal.database.tables.SessionTableTest
import com.mparticle.internal.database.tables.BreadcrumbTableTest
import com.mparticle.internal.database.tables.ReportingTableTest
import com.mparticle.internal.database.tables.UserAttributeTableTest
import com.mparticle.internal.database.tables.MpIdDependentTable
import com.mparticle.internal.database.UpgradeVersionTest.FcmMessageTableColumns
import com.mparticle.internal.database.UpgradeMessageTableTest
import android.telephony.TelephonyManager
import com.mparticle.internal.UserStorage
import com.mparticle.internal.MessageManager
import android.content.SharedPreferences
import com.mparticle.internal.DeviceAttributes
import com.mparticle.internal.KitFrameworkWrapper
import com.mparticle.internal.KitFrameworkWrapperTest.StubKitManager
import androidx.test.rule.ActivityTestRule
import com.mparticle.WebViewActivity
import com.mparticle.internal.MParticleJSInterfaceITest
import android.webkit.WebView
import com.mparticle.internal.MParticleJSInterface
import android.webkit.JavascriptInterface
import com.mparticle.test.R
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.mparticle.internal.MParticleJSInterfaceITest.OptionsAllowResponse
import android.webkit.WebChromeClient
import android.annotation.TargetApi
import android.location.Location
import com.mparticle.internal.ConfigManagerInstrumentedTest.BothConfigsLoadedListener
import com.mparticle.internal.ConfigManagerInstrumentedTest.AddConfigListener
import com.mparticle.internal.ConfigManager.ConfigLoadedListener
import com.mparticle.internal.ConfigManager.ConfigType
import com.mparticle.internal.AppStateManager
import com.mparticle.internal.AppStateManagerInstrumentedTest.KitManagerTester
import com.mparticle.internal.ReportingManager
import com.mparticle.networking.NetworkOptionsManager
import com.mparticle.networking.PinningTestHelper
import com.mparticle.identity.MParticleIdentityClient
import com.mparticle.internal.MParticleApiClientImpl
import com.mparticle.internal.MParticleApiClientImpl.MPNoConfigException
import com.mparticle.internal.MParticleApiClient
import com.mparticle.networking.MParticleBaseClient
import com.mparticle.networking.BaseNetworkConnection
import com.mparticle.networking.MPUrl
import com.mparticle.networking.PinningTest
import com.mparticle.InstallReferrerHelper
import com.mparticle.MParticle.ResetListener
import com.mparticle.PushRegistrationTest.SetPush
import com.mparticle.internal.PushRegistrationHelper.PushRegistration
import com.mparticle.internal.MParticleApiClientImpl.MPThrottleException
import com.mparticle.internal.MParticleApiClientImpl.MPRampException
import com.mparticle.internal.Logger.DefaultLogHandler
import com.mparticle.PushRegistrationTest.GetPush
import com.mparticle.PushRegistrationTest.ClearPush
import com.mparticle.PushRegistrationTest.PushEnabled
import com.mparticle.internal.PushRegistrationHelper
import com.mparticle.PushRegistrationTest.SynonymousMethod
import org.json.JSONException
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.lang.Exception
import java.util.ArrayList

class BreadcrumbServiceTest : BaseMPServiceTest() {
    @Before
    @Throws(Exception::class)
    fun before() {
        message =
            BaseMPMessage.Builder("test").build(InternalSession(), Location("New York City"), 1)
        breadCrumbLimit = ConfigManager.getBreadcrumbLimit(context)
    }

    /**
     * test throwing a bunch of trash, edge cases into the table and make sure that null values are
     * not stored in essential fields, and that it does not break the database
     * @throws Exception
     */
    @Test
    @Throws(Exception::class)
    fun testNullValues() {
        for (i in 0 until breadCrumbLimit + 10) {
            BreadcrumbService.insertBreadcrumb(
                BaseMPServiceTest.Companion.database,
                context,
                message,
                "k",
                null
            )
        }
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbCount(
                BaseMPServiceTest.Companion.database,
                null
            ).toLong(), 0
        )
        for (i in 0 until breadCrumbLimit + 10) {
            BreadcrumbService.insertBreadcrumb(
                BaseMPServiceTest.Companion.database,
                context,
                message,
                null,
                10L
            )
        }
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbCount(
                BaseMPServiceTest.Companion.database,
                10L
            ).toLong(), 0
        )
        for (i in 0 until breadCrumbLimit + 10) {
            BreadcrumbService.insertBreadcrumb(
                BaseMPServiceTest.Companion.database,
                context,
                null,
                "k",
                10L
            )
        }
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbCount(
                BaseMPServiceTest.Companion.database,
                10L
            ).toLong(), 0
        )
        for (i in 0..9) {
            BreadcrumbService.insertBreadcrumb(
                BaseMPServiceTest.Companion.database,
                context,
                message,
                "k",
                10L
            )
        }
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbCount(
                BaseMPServiceTest.Companion.database,
                10L
            ).toLong(), 10
        )
        for (i in 0 until breadCrumbLimit + 10) {
            BreadcrumbService.insertBreadcrumb(
                BaseMPServiceTest.Companion.database,
                context,
                message,
                null,
                10L
            )
        }
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbCount(
                BaseMPServiceTest.Companion.database,
                10L
            ).toLong(), 10
        )
        for (i in 0 until breadCrumbLimit + 10) {
            BreadcrumbService.insertBreadcrumb(
                BaseMPServiceTest.Companion.database,
                context,
                null,
                "k",
                10L
            )
        }
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbCount(
                BaseMPServiceTest.Companion.database,
                10L
            ).toLong(), 10
        )
    }

    /**
     * test that the new DB schema of storing stuff dependent on MPID is working
     * @throws Exception
     */
    @Test
    @Throws(Exception::class)
    fun testMpIdSpecific() {
        /**
         * this test won't work if you can't store breadcrumbs
         */
        Assert.assertTrue(breadCrumbLimit >= 2)
        val expectedCount = breadCrumbLimit
        for (i in 0 until expectedCount) {
            BreadcrumbService.insertBreadcrumb(
                BaseMPServiceTest.Companion.database,
                context,
                message,
                "apiKey",
                1L
            )
        }
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbs(
                BaseMPServiceTest.Companion.database,
                context,
                1L
            ).length().toLong(), expectedCount.toLong()
        )
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbs(
                BaseMPServiceTest.Companion.database,
                context,
                2L
            ).length().toLong(), 0
        )
        for (i in 0 until expectedCount - 1) {
            BreadcrumbService.insertBreadcrumb(
                BaseMPServiceTest.Companion.database,
                context,
                message,
                "apiKey",
                2L
            )
        }
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbs(
                BaseMPServiceTest.Companion.database,
                context,
                1L
            ).length().toLong(), expectedCount.toLong()
        )
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbs(
                BaseMPServiceTest.Companion.database,
                context,
                2L
            ).length().toLong(), (expectedCount - 1).toLong()
        )
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbs(
                BaseMPServiceTest.Companion.database,
                context,
                3L
            ).length().toLong(), 0
        )
    }

    @Test
    @Throws(JSONException::class)
    fun testBreadcrumbLimit() {
        val deleted: MutableList<Int> = ArrayList()
        for (i in 0 until breadCrumbLimit + 10) {
            deleted.add(
                BreadcrumbService.insertBreadcrumb(
                    BaseMPServiceTest.Companion.database,
                    context,
                    message,
                    "apiKey",
                    10L
                )
            )
        }

        // make sure that 10 (number attempted to be inserted above the breadcrumb limit) entries have been deleted
        deleted.removeAll(setOf(-1))
        Assert.assertEquals(deleted.size.toLong(), 10)
        Assert.assertEquals(
            BreadcrumbService.getBreadcrumbs(
                BaseMPServiceTest.Companion.database,
                context,
                10L
            ).length().toLong(), breadCrumbLimit.toLong()
        )
    }

    companion object {
        private var message: BaseMPMessage? = null
        private var breadCrumbLimit = 0
    }
}