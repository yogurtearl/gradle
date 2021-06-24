/*
 * Copyright 2012 the original author or authors.
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

import com.google.common.annotations.VisibleForTesting;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.gradle.api.internal.DocumentationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.util.function.Function;

/**
 * Provides some convenience and unified logging.
 */
public class HttpClientHelper extends BaseHttpClientHelper<CloseableHttpClient> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpClientHelper.class);

    @VisibleForTesting
    HttpClientHelper(
        DocumentationRegistry documentationRegistry,
        HttpSettings settings,
        Function<? super HttpSettings, ? extends CloseableHttpClient> createClient
    ) {
        super(documentationRegistry, settings, createClient);
    }

    static CloseableHttpClient createClient(HttpSettings settings) {
        HttpClientBuilder builder = HttpClientBuilder.create();
        new HttpClientConfigurer(settings).configure(HttpClientBuilderAdapter.of(builder));
        return builder.build();
    }

    @Nullable
    public HttpClientResponse performHead(String source, boolean revalidate) {
        return processResponse(performRequest(new HttpHead(source), revalidate));
    }

    @Nullable
    public HttpClientResponse performGet(String source, boolean revalidate) {
        return processResponse(performRequest(new HttpGet(source), revalidate));
    }

    private HttpClientResponse performRequest(HttpRequestBase request, boolean revalidate) {
        if (revalidate) {
            request.addHeader(HttpHeaders.CACHE_CONTROL, "max-age=0");
        }
        return performHttpRequest(request);
    }


    public HttpClientResponse performHttpRequest(HttpRequestBase request) throws HttpRequestException {
        logRequest(request);
        return withContext(httpContext -> performHttpRequest(request, httpContext));
    }

    private HttpClientResponse performHttpRequest(HttpRequestBase request, HttpContext httpContext) {
        CloseableHttpResponse response;
        try {
            response = getClient().execute(request, httpContext);
        } catch (IOException e) {
            throw toHttpRequestException(request, httpContext, e);
        }

        return toHttpClientResponse(request, httpContext, response);
    }

    @Nullable
    private HttpClientResponse processResponse(HttpClientResponse response) {
        if (response.wasMissing()) {
            LOGGER.info("Resource missing. [HTTP {}: {}]", response.getMethod(), stripUserCredentials(response.getEffectiveUri()));
            return null;
        }
        if (!response.wasSuccessful()) {
            URI effectiveUri = stripUserCredentials(response.getEffectiveUri());
            LOGGER.info("Failed to get resource: {}. [HTTP {}: {})]", response.getMethod(), response.getStatusLine(), effectiveUri);
            throw new HttpErrorStatusCodeException(response.getMethod(), effectiveUri.toString(), response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
        }
        return response;
    }

    private HttpClientResponse toHttpClientResponse(HttpRequestBase request, HttpContext httpContext, CloseableHttpResponse response) {
        return new HttpClientResponse(request.getMethod(), getEffectiveUri(request, httpContext), response);
    }

    /**
     * Factory for creating the {@link HttpClientHelper}
     */
    @FunctionalInterface
    public interface Factory {
        HttpClientHelper create(HttpSettings settings);

        /**
         * Method should only be used for DI registry and testing.
         * For other uses of {@link HttpClientHelper}, inject an instance of {@link Factory} to create one.
         */
        static Factory createFactory(DocumentationRegistry documentationRegistry) {
            return settings -> new HttpClientHelper(documentationRegistry, settings, HttpClientHelper::createClient);
        }
    }

}
