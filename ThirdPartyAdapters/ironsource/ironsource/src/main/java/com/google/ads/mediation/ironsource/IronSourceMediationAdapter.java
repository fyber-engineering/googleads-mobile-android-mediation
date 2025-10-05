// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.ads.mediation.ironsource;

import static com.google.ads.mediation.ironsource.IronSourceConstants.KEY_APP_KEY;
import static com.google.ads.mediation.ironsource.IronSourceConstants.TAG;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.VersionInfo;
import com.google.android.gms.ads.mediation.InitializationCompleteCallback;
import com.google.android.gms.ads.mediation.MediationAdLoadCallback;
import com.google.android.gms.ads.mediation.MediationBannerAd;
import com.google.android.gms.ads.mediation.MediationBannerAdCallback;
import com.google.android.gms.ads.mediation.MediationBannerAdConfiguration;
import com.google.android.gms.ads.mediation.MediationConfiguration;
import com.google.android.gms.ads.mediation.MediationInterstitialAd;
import com.google.android.gms.ads.mediation.MediationInterstitialAdCallback;
import com.google.android.gms.ads.mediation.MediationInterstitialAdConfiguration;
import com.google.android.gms.ads.mediation.MediationRewardedAd;
import com.google.android.gms.ads.mediation.MediationRewardedAdCallback;
import com.google.android.gms.ads.mediation.MediationRewardedAdConfiguration;
import com.google.android.gms.ads.mediation.rtb.RtbAdapter;
import com.google.android.gms.ads.mediation.rtb.RtbSignalData;
import com.google.android.gms.ads.mediation.rtb.SignalCallbacks;
import com.ironsource.mediationsdk.IronSource;
import com.ironsource.mediationsdk.logger.IronSourceError;
import com.unity3d.ironsourceads.InitListener;
import com.unity3d.ironsourceads.InitRequest;
import com.unity3d.ironsourceads.IronSourceAds;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IronSource Adapter for GMA SDK used to initialize and load ads from the IronSource SDK. This
 * class should not be used directly by publishers.
 */
public class IronSourceMediationAdapter extends RtbAdapter {

  private static final AtomicBoolean isInitialized = new AtomicBoolean(false);

  // region Error codes
  /** IronSource adapter error domain. */
  public static final String ADAPTER_ERROR_DOMAIN = "com.google.ads.mediation.ironsource";

  /** IronSource SDK error domain. */
  public static final String IRONSOURCE_SDK_ERROR_DOMAIN = "com.ironsource.mediationsdk";

  @Retention(RetentionPolicy.SOURCE)
  @IntDef(
      value = {
        ERROR_INVALID_SERVER_PARAMETERS,
        ERROR_REQUIRES_ACTIVITY_CONTEXT,
        ERROR_AD_ALREADY_LOADED,
        ERROR_BANNER_SIZE_MISMATCH,
        ERROR_SDK_NOT_INITIALIZED
      })
  public @interface AdapterError {}

  /** Server parameters (e.g. instance ID) are nil. */
  public static final int ERROR_INVALID_SERVER_PARAMETERS = 101;

  /** IronSource requires an {@link Activity} context to initialize their SDK. */
  public static final int ERROR_REQUIRES_ACTIVITY_CONTEXT = 102;

  /** IronSource can only load 1 ad per IronSource instance ID. */
  public static final int ERROR_AD_ALREADY_LOADED = 103;

  /** Banner size mismatch. */
  public static final int ERROR_BANNER_SIZE_MISMATCH = 105;

  /** IronSource SDK isn't initialized. */
  public static final int ERROR_SDK_NOT_INITIALIZED = 106;

  /** Call show before loaded success. */
  public static final int ERROR_CALL_SHOW_BEFORE_LOADED_SUCCESS = 107;

  // endregion

  @NonNull
  @Override
  public VersionInfo getSDKVersionInfo() {
    String versionString = IronSourceAds.getSdkVersion();
    String[] splits = versionString.split("\\.");

    if (splits.length >= 3) {
      int major = Integer.parseInt(splits[0]);
      int minor = Integer.parseInt(splits[1]);
      int micro = Integer.parseInt(splits[2]);
      if (splits.length >= 4) {
        micro = micro * 100 + Integer.parseInt(splits[3]);
      }

      return new VersionInfo(major, minor, micro);
    }

    String logMessage =
        String.format(
            "Unexpected SDK version format: %s. Returning 0.0.0 for SDK version.", versionString);
    Log.w(TAG, logMessage);
    return new VersionInfo(0, 0, 0);
  }

  @NonNull
  @Override
  public VersionInfo getVersionInfo() {
    String versionString = IronSourceAdapterUtils.getAdapterVersion();
    String[] splits = versionString.split("\\.");

    if (splits.length >= 4) {
      int major = Integer.parseInt(splits[0]);
      int minor = Integer.parseInt(splits[1]);
      int micro = Integer.parseInt(splits[2]) * 100 + Integer.parseInt(splits[3]);
      if (splits.length >= 5) {
        micro = micro * 100 + Integer.parseInt(splits[4]);
      }

      return new VersionInfo(major, minor, micro);
    }

    String logMessage =
        String.format(
            "Unexpected adapter version format: %s. Returning 0.0.0 for adapter version.",
            versionString);
    Log.w(TAG, logMessage);
    return new VersionInfo(0, 0, 0);
  }

  @Override
  public void initialize(
      @NonNull Context context,
      @NonNull final InitializationCompleteCallback initializationCompleteCallback,
      @NonNull List<MediationConfiguration> mediationConfigurations) {

    if (isInitialized.get()) {
      initializationCompleteCallback.onInitializationSucceeded();
      return;
    }

    HashSet<String> appKeys = new HashSet<>();
    for (MediationConfiguration configuration : mediationConfigurations) {
      Bundle serverParameters = configuration.getServerParameters();
      String appKeyFromServer = serverParameters.getString(KEY_APP_KEY);

      if (!TextUtils.isEmpty(appKeyFromServer)) {
        appKeys.add(appKeyFromServer);
      }
    }

    int count = appKeys.size();
    if (count <= 0) {
      AdError initializationError =
          new AdError(
              ERROR_INVALID_SERVER_PARAMETERS, "Missing or invalid app key.", ADAPTER_ERROR_DOMAIN);
      initializationCompleteCallback.onInitializationFailed(initializationError.getMessage());
      return;
    }

    // Having multiple app keys is not considered an error.
    String appKey = appKeys.iterator().next();
    if (TextUtils.isEmpty(appKey)) {
      AdError initializationError =
          new AdError(
              ERROR_INVALID_SERVER_PARAMETERS, "Missing or invalid app key.", ADAPTER_ERROR_DOMAIN);
      initializationCompleteCallback.onInitializationFailed(initializationError.getMessage());
      return;
    }

    if (count > 1) {
      String message =
          String.format(
              "Multiple '%s' entries found: %s. Using app key '%s' to initialize "
                  + "the IronSource SDK.",
              KEY_APP_KEY, appKeys, appKey);
      Log.w(TAG, message);
    }

    IronSource.setMediationType(IronSourceAdapterUtils.getMediationType());
    Log.d(TAG, "Initializing IronSource SDK with app key: " + appKey);

    List<IronSourceAds.AdFormat> adFormatsToInitialize =
        new ArrayList<>(
            Arrays.asList(
                IronSourceAds.AdFormat.BANNER,
                IronSourceAds.AdFormat.INTERSTITIAL,
                IronSourceAds.AdFormat.REWARDED));

    InitRequest initRequest =
        new InitRequest.Builder(appKey).withLegacyAdFormats(adFormatsToInitialize).build();

    IronSourceAds.init(
        context,
        initRequest,
        new InitListener() {
          @Override
          public void onInitSuccess() {
            isInitialized.set(true);
            initializationCompleteCallback.onInitializationSucceeded();
          }

          @Override
          public void onInitFailed(@NonNull final IronSourceError ironSourceError) {
            initializationCompleteCallback.onInitializationFailed(
                ironSourceError.getErrorMessage());
          }
        });

    IronSource.setISDemandOnlyInterstitialListener(
        IronSourceInterstitialAd.getIronSourceInterstitialListener());
    IronSource.setISDemandOnlyRewardedVideoListener(
        IronSourceRewardedAd.getIronSourceRewardedListener());
  }

  @Override
  public void collectSignals(
      @NonNull RtbSignalData rtbSignalData, @NonNull SignalCallbacks signalCallbacks) {
    signalCallbacks.onSuccess(IronSource.getISDemandOnlyBiddingData(rtbSignalData.getContext()));
  }

  @Override
  public void loadRewardedAd(
      @NonNull MediationRewardedAdConfiguration adConfiguration,
      @NonNull
          final MediationAdLoadCallback<MediationRewardedAd, MediationRewardedAdCallback>
              mediationAdLoadCallback) {
    if (!isInitialized.get()) {
      AdError adError =
          new AdError(
              ERROR_SDK_NOT_INITIALIZED,
              "Failed to load IronSource rewarded ad since IronSource SDK is not initialized.",
              ADAPTER_ERROR_DOMAIN);

      Log.w(TAG, adError.getMessage());
      mediationAdLoadCallback.onFailure(adError);
      return;
    }

    IronSourceRewardedAd ironSourceRewardedAd =
        new IronSourceRewardedAd(adConfiguration, mediationAdLoadCallback);
    ironSourceRewardedAd.loadWaterfallAd(adConfiguration);
  }

  @Override
  public void loadRtbRewardedAd(
      @NonNull MediationRewardedAdConfiguration adConfiguration,
      @NonNull MediationAdLoadCallback<MediationRewardedAd, MediationRewardedAdCallback> callback) {
    IronSourceRtbRewardedAd ironSourceRtbRewardedAd = new IronSourceRtbRewardedAd(callback);
    ironSourceRtbRewardedAd.loadRtbAd(adConfiguration);
  }

  @Override
  public void loadRewardedInterstitialAd(
      @NonNull MediationRewardedAdConfiguration adConfiguration,
      @NonNull
          MediationAdLoadCallback<MediationRewardedAd, MediationRewardedAdCallback>
              mediationAdLoadCallback) {
    // IronSource Rewarded Interstitial ads use the same Rewarded Video API.
    Log.d(
        TAG,
        "IronSource adapter was asked to load a rewarded interstitial ad. "
            + "Using the rewarded ad request flow to load the ad to attempt to load a "
            + "rewarded interstitial ad from IronSource.");
    loadRewardedAd(adConfiguration, mediationAdLoadCallback);
  }

  @Override
  public void loadInterstitialAd(
      @NonNull MediationInterstitialAdConfiguration adConfiguration,
      @NonNull
          MediationAdLoadCallback<MediationInterstitialAd, MediationInterstitialAdCallback>
              mediationAdLoadCallback) {
    if (!isInitialized.get()) {
      AdError loadError =
          new AdError(
              ERROR_SDK_NOT_INITIALIZED,
              "Failed to load IronSource interstitial ad since IronSource SDK is not "
                  + "initialized.",
              IRONSOURCE_SDK_ERROR_DOMAIN);
      Log.w(TAG, loadError.getMessage());
      mediationAdLoadCallback.onFailure(loadError);
      return;
    }

    IronSourceInterstitialAd ironSourceInterstitialAd =
        new IronSourceInterstitialAd(adConfiguration, mediationAdLoadCallback);
    ironSourceInterstitialAd.loadWaterfallAd(adConfiguration);
  }

  @Override
  public void loadRtbInterstitialAd(
      @NonNull MediationInterstitialAdConfiguration adConfiguration,
      @NonNull
          MediationAdLoadCallback<MediationInterstitialAd, MediationInterstitialAdCallback>
              mediationAdLoadCallback) {
    IronSourceRtbInterstitialAd ironSourceRtbInterstitialAd =
        new IronSourceRtbInterstitialAd(mediationAdLoadCallback);
    ironSourceRtbInterstitialAd.loadRtbAd(adConfiguration);
  }

  @Override
  public void loadRtbBannerAd(
      @NonNull MediationBannerAdConfiguration adConfiguration,
      @NonNull
          MediationAdLoadCallback<MediationBannerAd, MediationBannerAdCallback>
              mediationAdLoadCallback) {
    IronSourceRtbBannerAd ironSourceRtbBannerAd =
        new IronSourceRtbBannerAd(mediationAdLoadCallback);
    ironSourceRtbBannerAd.loadRtbAd(adConfiguration);
  }

  @Override
  public void loadBannerAd(
      @NonNull MediationBannerAdConfiguration adConfiguration,
      @NonNull
          MediationAdLoadCallback<MediationBannerAd, MediationBannerAdCallback>
              mediationAdLoadCallback) {
    if (!isInitialized.get()) {
      AdError loadError =
          new AdError(
              ERROR_SDK_NOT_INITIALIZED,
              "Failed to load IronSource banner ad since IronSource SDK is not initialized.",
              IRONSOURCE_SDK_ERROR_DOMAIN);
      Log.w(TAG, loadError.getMessage());
      mediationAdLoadCallback.onFailure(loadError);
      return;
    }

    IronSourceBannerAd ironSourceBannerAd = new IronSourceBannerAd(mediationAdLoadCallback);
    ironSourceBannerAd.loadAd(adConfiguration);
  }

  @VisibleForTesting
  public void setIsInitialized(boolean isInitializedValue) {
    isInitialized.set(isInitializedValue);
  }
}
