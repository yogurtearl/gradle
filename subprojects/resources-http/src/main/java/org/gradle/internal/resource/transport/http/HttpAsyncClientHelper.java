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

import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpCoreContext;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.internal.UncheckedException;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;

public class HttpAsyncClientHelper extends BaseHttpClientHelper<CloseableHttpAsyncClient> {

    public HttpAsyncClientHelper(
        DocumentationRegistry documentationRegistry,
        HttpSettings settings,
        Function<? super HttpSettings, ? extends CloseableHttpAsyncClient> clientFactory
    ) {
        super(documentationRegistry, settings, clientFactory);
    }

    static CloseableHttpAsyncClient createClient(HttpSettings settings) {
        HttpAsyncClientBuilder builder = HttpAsyncClientBuilder.create();
        new HttpClientConfigurer(settings).configure(HttpClientBuilderAdapter.of(builder));
        CloseableHttpAsyncClient client = builder.build();
        client.start();
        return client;
    }

    public <T> T request(HttpAsyncRequestProducer request, HttpAsyncResponseConsumer<T> responseConsumer) throws HttpRequestException {
        return withContext(httpContext -> {
            Future<T> future = getClient().execute(
                decorateWithLogging(request),
                responseConsumer,
                httpContext,
                noopFutureCallback()
            );

            try {
                return future.get();
            } catch (InterruptedException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    HttpRequest contextRequest = HttpCoreContext.adapt(httpContext).getRequest();
                    HttpUriRequest contextUriRequest = (HttpUriRequest) contextRequest;
                    throw toHttpRequestException(contextUriRequest, httpContext, (IOException) cause);
                } else {
                    throw UncheckedException.throwAsUncheckedException(cause);
                }
            }
        });
    }

    private HttpAsyncRequestProducer decorateWithLogging(HttpAsyncRequestProducer delegate) {
        return new DelegatingAsyncRequestProducer(delegate) {
            @Override
            public HttpRequest generateRequest() throws IOException, HttpException {
                HttpRequest httpRequest = super.generateRequest();
                if (httpRequest instanceof HttpUriRequest) {
                    logRequest((HttpUriRequest) httpRequest);
                }
                return httpRequest;
            }
        };
    }

    /**
     * Factory for creating the {@link HttpClientHelper}
     */
    @FunctionalInterface
    public interface Factory {

        HttpAsyncClientHelper create(HttpSettings settings);

        /**
         * Method should only be used for DI registry and testing.
         * For other uses of {@link HttpClientHelper}, inject an instance of {@link HttpClientHelper.Factory} to create one.
         */
        static HttpAsyncClientHelper.Factory createFactory(DocumentationRegistry documentationRegistry) {
            return settings -> new HttpAsyncClientHelper(documentationRegistry, settings, HttpAsyncClientHelper::createClient);
        }

    }

    private static <T> FutureCallback<T> noopFutureCallback() {
        return new FutureCallback<T>() {
            @Override
            public void completed(T result) {

            }

            @Override
            public void failed(Exception ex) {

            }

            @Override
            public void cancelled() {

            }
        };
    }

}
