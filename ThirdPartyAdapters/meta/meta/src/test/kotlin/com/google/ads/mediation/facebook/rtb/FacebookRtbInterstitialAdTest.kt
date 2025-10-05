package com.google.ads.mediation.facebook.rtb

import android.content.Context
import androidx.core.os.bundleOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.ads.Ad
import com.facebook.ads.AdError.AD_PRESENTATION_ERROR
import com.facebook.ads.AdError.NO_FILL
import com.facebook.ads.InterstitialAd
import com.google.ads.mediation.adaptertestkit.AdErrorMatcher
import com.google.ads.mediation.adaptertestkit.AdapterTestKitConstants.TEST_AD_UNIT
import com.google.ads.mediation.adaptertestkit.createMediationInterstitialAdConfiguration
import com.google.ads.mediation.facebook.FacebookMediationAdapter.ERROR_DOMAIN
import com.google.ads.mediation.facebook.FacebookMediationAdapter.ERROR_FAILED_TO_PRESENT_AD
import com.google.ads.mediation.facebook.FacebookMediationAdapter.FACEBOOK_SDK_ERROR_DOMAIN
import com.google.ads.mediation.facebook.FacebookMediationAdapter.RTB_PLACEMENT_PARAMETER
import com.google.ads.mediation.facebook.MetaFactory
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.mediation.MediationAdLoadCallback
import com.google.android.gms.ads.mediation.MediationInterstitialAd
import com.google.android.gms.ads.mediation.MediationInterstitialAdCallback
import com.google.android.gms.ads.mediation.MediationInterstitialAdConfiguration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit tests for [FacebookRtbInterstitialAd]. */
@RunWith(AndroidJUnit4::class)
class FacebookRtbInterstitialAdTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val serverParameters = bundleOf(RTB_PLACEMENT_PARAMETER to TEST_AD_UNIT)
  private val mediationInterstitialAdConfig: MediationInterstitialAdConfiguration =
    createMediationInterstitialAdConfiguration(context, serverParameters = serverParameters)
  private val mediationInterstitialAdCallback: MediationInterstitialAdCallback = mock()
  private val mediationAdLoadCallback:
    MediationAdLoadCallback<MediationInterstitialAd, MediationInterstitialAdCallback> =
    mock {
      on { onSuccess(any()) } doReturn mediationInterstitialAdCallback
    }
  private val metaInterstitialAdLoadConfigBuilder: InterstitialAd.InterstitialAdLoadConfigBuilder =
    mock {
      on { withBid(any()) } doReturn this.mock
      on { withAdListener(any()) } doReturn this.mock
    }
  private val metaInterstitialAd: InterstitialAd = mock {
    on { buildLoadAdConfig() } doReturn metaInterstitialAdLoadConfigBuilder
  }
  private val metaFactory: MetaFactory = mock {
    on { createInterstitialAd(any(), any()) } doReturn metaInterstitialAd
  }
  private val metaAd: Ad = mock()

  /** The unit under test. */
  private lateinit var adapterInterstitialAd: FacebookRtbInterstitialAd

  @Before
  fun setUp() {
    adapterInterstitialAd = FacebookRtbInterstitialAd(mediationAdLoadCallback, metaFactory)
  }

  @Test
  fun onError_onShowAdAlreadyCalled_invokesOnAdFailedToShowCallback() {
    renderAndLoadSuccessfully()
    // Stub metaInterstitialAd.show() to return true (i.e. we are able to successfully ask Meta's
    // interstitial ad to be shown).
    whenever(metaInterstitialAd.show()) doReturn true
    // As part of setting this test up, show the ad.
    adapterInterstitialAd.showAd(context)

    // Simulate Meta reporting a show error.
    adapterInterstitialAd.onError(metaAd, AD_PRESENTATION_ERROR)

    val expectedAdError =
      AdError(
        AD_PRESENTATION_ERROR.errorCode,
        AD_PRESENTATION_ERROR.errorMessage,
        FACEBOOK_SDK_ERROR_DOMAIN,
      )
    verify(mediationInterstitialAdCallback)
      .onAdFailedToShow(argThat(AdErrorMatcher(expectedAdError)))
  }

  @Test
  fun onError_onShowAdNotYetCalled_invokesAdLoadCallbackWithFailure() {
    renderAndLoadSuccessfully()

    // Simulate a no-fill error.
    adapterInterstitialAd.onError(metaAd, NO_FILL)

    val expectedAdError =
      AdError(NO_FILL.errorCode, NO_FILL.errorMessage, FACEBOOK_SDK_ERROR_DOMAIN)
    verify(mediationAdLoadCallback).onFailure(argThat(AdErrorMatcher(expectedAdError)))
  }

  @Test
  fun onShowAd_ifInterstitialAdShowFailed_callsOnAdFailedToShow() {
    renderAndLoadSuccessfully()
    // Stub metaInterstitialAd.show() to return false (i.e. we are able to unsuccessful in Meta's
    // interstitial ad to be shown).
    whenever(metaInterstitialAd.show()) doReturn false
    val expectedAdError =
      AdError(ERROR_FAILED_TO_PRESENT_AD, "Failed to present interstitial ad.", ERROR_DOMAIN)

    // invoke the showAd callback
    adapterInterstitialAd.showAd(context)

    verify(mediationInterstitialAdCallback)
      .onAdFailedToShow(argThat(AdErrorMatcher(expectedAdError)))
  }

  @Test
  fun onInterstitialDisplayed_invokesOnAdOpenedCallback() {
    renderAndLoadSuccessfully()

    adapterInterstitialAd.onInterstitialDisplayed(metaAd)

    verify(mediationInterstitialAdCallback).onAdOpened()
  }

  @Test
  fun onInterstitialDismissed_invokesOnAdClosedCallback() {
    renderAndLoadSuccessfully()

    adapterInterstitialAd.onInterstitialDismissed(metaAd)

    verify(mediationInterstitialAdCallback).onAdClosed()
  }

  @Test
  fun onInterstitialDismissed_adAlreadyClosed_doesNotInvokeOnAdClosedCallbackTwice() {
    renderAndLoadSuccessfully()

    // simulate dismissed already called.
    adapterInterstitialAd.onInterstitialDismissed(metaAd)
    // make a second dismissed call.
    adapterInterstitialAd.onInterstitialDismissed(metaAd)

    verify(mediationInterstitialAdCallback, times(1)).onAdClosed()
  }

  @Test
  fun onAdClicked_invokesReportAdClickedAndOnAdLeftApplicationCallback() {
    renderAndLoadSuccessfully()

    adapterInterstitialAd.onAdClicked(metaAd)

    verify(mediationInterstitialAdCallback).reportAdClicked()
    verify(mediationInterstitialAdCallback).onAdLeftApplication()
  }

  @Test
  fun onLoggingImpression_invokesReportAdImpressionCallback() {
    renderAndLoadSuccessfully()

    adapterInterstitialAd.onLoggingImpression(metaAd)

    verify(mediationInterstitialAdCallback).reportAdImpression()
  }

  @Test
  fun onInterstitialActivityDestroyed_invokesOnAdClosed() {
    renderAndLoadSuccessfully()

    adapterInterstitialAd.onInterstitialActivityDestroyed()

    verify(mediationInterstitialAdCallback).onAdClosed()
  }

  @Test
  fun onInterstitialActivityDestroyed_adAlreadyClosed_doesNotInvokeOnAdClosedCallbackTwice() {
    renderAndLoadSuccessfully()

    // simulate activity destroyed call
    adapterInterstitialAd.onInterstitialActivityDestroyed()
    // make a second destroyed call
    adapterInterstitialAd.onInterstitialActivityDestroyed()

    verify(mediationInterstitialAdCallback, times(1)).onAdClosed()
  }

  /**
   * Verify there are no exceptions when onRewardedAd callbacks are invoked. Note: All onRewardedAd
   * callbacks are no-ops since this is interstitial ad.
   */
  @Test
  fun onRewardedAdCallbacks_noException() {
    adapterInterstitialAd.onRewardedAdCompleted()
    adapterInterstitialAd.onRewardedAdServerSucceeded()
    adapterInterstitialAd.onRewardedAdServerFailed()
  }

  private fun renderAndLoadSuccessfully() {
    // As part of setting this test up, render the ad.
    adapterInterstitialAd.render(mediationInterstitialAdConfig)
    // Simulate Meta ad load success.
    adapterInterstitialAd.onAdLoaded(metaAd)
  }
}
