/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.modules.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;

import com.facebook.common.logging.FLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

/**
 * Helper class that provides the same OkHttpClient instance that will be used for all networking
 * requests.
 */
public class OkHttpClientProvider {

  // Centralized OkHttpClient for all networking requests.
  private static @Nullable OkHttpClient sClient;

  // User-provided OkHttpClient factory
  private static @Nullable OkHttpClientFactory sFactory;

  private final static Set<OkHttpClient> sClients = Collections.newSetFromMap(
    new WeakHashMap<OkHttpClient, Boolean>());

  public static void setOkHttpClientFactory(OkHttpClientFactory factory) {
    sFactory = factory;
  }

  public static OkHttpClient getOkHttpClient() {
    if (sClient == null) {
      sClient = createClient();
    }
    return sClient;
  }

  /*
    See https://github.com/facebook/react-native/issues/19709 for context.
    We know that connections get corrupted when the connectivity state
    changes to disconnected, but the debugging of this issue hasn't been
    exhaustive and it's possible that other changes in connectivity also
    corrupt idle connections. `CONNECTIVITY_ACTION`s occur infrequently
    enough to go safe and evict all idle connections and ongoing calls
    for the events DISCONNECTED and CONNECTING. Don't do this for CONNECTED
    since it's possible that new calls have already been dispatched by the
    time we receive the event.
   */
  public static void addNetworkListenerToEvictIdleConnectionsOnNetworkChange(Context context) {
    final BroadcastReceiver br = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (!intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
          return;
        }
        final Bundle extras = intent.getExtras();
        final NetworkInfo info = extras.getParcelable("networkInfo");
        final NetworkInfo.State state = info.getState();
        if (state == NetworkInfo.State.CONNECTED) {
          return;
        }
        final PendingResult result = goAsync();
        final Thread thread = new Thread() {
          public void run() {
              for (OkHttpClient client: sClients) {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
              }
              result.finish();
          }
        };
        thread.start();
      }
    };
    final IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
    context.registerReceiver(br, intentFilter);
  }
  
  // okhttp3 OkHttpClient is immutable
  // This allows app to init an OkHttpClient with custom settings.
  public static void replaceOkHttpClient(OkHttpClient client) {
    sClient = client;
  }

  public static OkHttpClient createClient() {
    if (sFactory != null) {
      return sFactory.createNewNetworkModuleClient();
    }
    final OkHttpClient client = createClientBuilder().build();
    registerClientToEvictIdleConnectionsOnNetworkChange(client);
    return client;
  }

  public static OkHttpClient.Builder createClientBuilder() {
    // No timeouts by default
    OkHttpClient.Builder client = new OkHttpClient.Builder()
      .connectTimeout(0, TimeUnit.MILLISECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .writeTimeout(0, TimeUnit.MILLISECONDS)
      .cookieJar(new ReactCookieJarContainer());

    return enableTls12OnPreLollipop(client);
  }

  public static void registerClientToEvictIdleConnectionsOnNetworkChange(OkHttpClient client) {
    sClients.add(client);
  }

  /*
    On Android 4.1-4.4 (API level 16 to 19) TLS 1.1 and 1.2 are
    available but not enabled by default. The following method
    enables it.
   */
  public static OkHttpClient.Builder enableTls12OnPreLollipop(OkHttpClient.Builder client) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
      try {
        client.sslSocketFactory(new TLSSocketFactory());

        ConnectionSpec cs = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build();

        List<ConnectionSpec> specs = new ArrayList<>();
        specs.add(cs);
        specs.add(ConnectionSpec.COMPATIBLE_TLS);
        specs.add(ConnectionSpec.CLEARTEXT);

        client.connectionSpecs(specs);
      } catch (Exception exc) {
        FLog.e("OkHttpClientProvider", "Error while enabling TLS 1.2", exc);
      }
    }

    return client;
  }

}
