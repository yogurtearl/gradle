/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.resource.transport.http;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.Registry;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.util.PublicSuffixMatcher;
import org.apache.http.cookie.CookieSpecProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.protocol.HttpContext;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

interface HttpClientBuilderAdapter {

    void setSsl(SSLContext sslContext, String[] sslProtocols, HostnameVerifier hostnameVerifier);

    void setDefaultAuthSchemeRegistry(Registry<AuthSchemeProvider> build);

    void addInterceptorFirst(HttpClientConfigurer.PreemptiveAuth preemptiveAuth);

    void setRoutePlanner(SystemDefaultRoutePlanner systemDefaultRoutePlanner);

    void setUserAgent(String userAgentString);

    void setPublicSuffixMatcher(PublicSuffixMatcher publicSuffixMatcher);

    void setDefaultCookieSpecRegistry(Registry<CookieSpecProvider> compatibility);

    void setDefaultRequestConfig(RequestConfig config);

    void setDefaultSocketConfig(int socketTimeoutMs, boolean keepAlive);

    void setRedirectStrategy(RedirectStrategy redirectStrategy);

    void disableRedirectHandling();

    void setDefaultCredentialsProvider(CredentialsProvider credentialsProvider);

    void setMaxConnTotal(int maxHttpConnections);

    void setMaxConnPerRoute(int maxHttpConnections);

    default void finish() {
    }

    static HttpClientBuilderAdapter of(HttpClientBuilder builder) {
        return new HttpClientBuilderAdapter() {
            @Override
            public void setSsl(SSLContext sslContext, String[] sslProtocols, HostnameVerifier hostnameVerifier) {
                builder.setSSLSocketFactory(new SSLConnectionSocketFactory(sslContext, sslProtocols, null, hostnameVerifier));
            }

            @Override
            public void setDefaultAuthSchemeRegistry(Registry<AuthSchemeProvider> build) {
                builder.setDefaultAuthSchemeRegistry(build);
            }

            @Override
            public void addInterceptorFirst(HttpClientConfigurer.PreemptiveAuth preemptiveAuth) {
                builder.addInterceptorFirst(preemptiveAuth);
            }

            @Override
            public void setRoutePlanner(SystemDefaultRoutePlanner systemDefaultRoutePlanner) {
                builder.setRoutePlanner(systemDefaultRoutePlanner);
            }


            @Override
            public void setUserAgent(String userAgentString) {
                builder.setUserAgent(userAgentString);
            }

            @Override
            public void setPublicSuffixMatcher(PublicSuffixMatcher publicSuffixMatcher) {
                builder.setPublicSuffixMatcher(publicSuffixMatcher);
            }

            @Override
            public void setDefaultCookieSpecRegistry(Registry<CookieSpecProvider> compatibility) {
                builder.setDefaultCookieSpecRegistry(compatibility);
            }

            @Override
            public void setDefaultRequestConfig(RequestConfig config) {
                builder.setDefaultRequestConfig(config);
            }

            @Override
            public void setDefaultSocketConfig(int socketTimeoutMs, boolean keepAlive) {
                builder.setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(socketTimeoutMs).setSoKeepAlive(keepAlive).build());
            }

            @Override
            public void setRedirectStrategy(RedirectStrategy redirectStrategy) {
                builder.setRedirectStrategy(redirectStrategy);
            }

            @Override
            public void disableRedirectHandling() {
                builder.disableRedirectHandling();
            }

            @Override
            public void setDefaultCredentialsProvider(CredentialsProvider credentialsProvider) {
                builder.setDefaultCredentialsProvider(credentialsProvider);
            }

            @Override
            public void setMaxConnTotal(int maxHttpConnections) {
                builder.setMaxConnTotal(maxHttpConnections);
            }

            @Override
            public void setMaxConnPerRoute(int maxHttpConnections) {
                builder.setMaxConnPerRoute(maxHttpConnections);
            }
        };
    }

    static HttpClientBuilderAdapter of(HttpAsyncClientBuilder builder) {
        return new HttpClientBuilderAdapter() {

            private final IOReactorConfig.Builder ioReactorConfig = IOReactorConfig.custom();

            @Override
            public void setSsl(SSLContext sslContext, String[] sslProtocols, HostnameVerifier hostnameVerifier) {
                builder.setSSLStrategy(new SSLIOSessionStrategy(sslContext, sslProtocols, null, hostnameVerifier));
            }

            @Override
            public void setDefaultAuthSchemeRegistry(Registry<AuthSchemeProvider> build) {
                builder.setDefaultAuthSchemeRegistry(build);
            }

            @Override
            public void addInterceptorFirst(HttpClientConfigurer.PreemptiveAuth preemptiveAuth) {
                builder.addInterceptorFirst(preemptiveAuth);
            }

            @Override
            public void setRoutePlanner(SystemDefaultRoutePlanner systemDefaultRoutePlanner) {
                builder.setRoutePlanner(systemDefaultRoutePlanner);
            }

            @Override
            public void setUserAgent(String userAgentString) {
                builder.setUserAgent(userAgentString);
            }

            @Override
            public void setPublicSuffixMatcher(PublicSuffixMatcher publicSuffixMatcher) {
                builder.setPublicSuffixMatcher(publicSuffixMatcher);
            }

            @Override
            public void setDefaultCookieSpecRegistry(Registry<CookieSpecProvider> compatibility) {
                builder.setDefaultCookieSpecRegistry(compatibility);
            }

            @Override
            public void setDefaultRequestConfig(RequestConfig config) {
                builder.setDefaultRequestConfig(config);
            }

            @Override
            public void setDefaultSocketConfig(int socketTimeoutMs, boolean keepAlive) {
                ioReactorConfig.setSoTimeout(socketTimeoutMs).setSoKeepAlive(keepAlive);
            }

            @Override
            public void setRedirectStrategy(RedirectStrategy redirectStrategy) {
                builder.setRedirectStrategy(redirectStrategy);
            }

            @Override
            public void disableRedirectHandling() {
                builder.setRedirectStrategy(new RedirectStrategy() {
                    @Override
                    public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context) {
                        return false;
                    }

                    @Override
                    public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) {
                        throw new UnsupportedOperationException();
                    }
                });
            }

            @Override
            public void setDefaultCredentialsProvider(CredentialsProvider credentialsProvider) {
                builder.setDefaultCredentialsProvider(credentialsProvider);
            }

            @Override
            public void setMaxConnTotal(int maxHttpConnections) {
                builder.setMaxConnTotal(maxHttpConnections);
            }

            @Override
            public void finish() {
                builder.setDefaultIOReactorConfig(ioReactorConfig.build());
            }

            @Override
            public void setMaxConnPerRoute(int maxHttpConnections) {
                builder.setMaxConnPerRoute(maxHttpConnections);
            }
        };
    }
}
